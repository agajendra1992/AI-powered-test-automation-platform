"""Regression tests for the 2026-11-21 review findings (each was reproduced before the fix)."""
from __future__ import annotations

import asyncio
import time
from datetime import datetime, timezone

from langchain_core.language_models.fake_chat_models import FakeListChatModel, GenericFakeChatModel
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.graph.nodes import analysis
from app.graph.workflow import build, run_config
from app.langchain.output_parsers.schemas import IntentResult
from app.langchain.structured import StructuredLLM
from app.langchain.usage import UsageTracker
from app.mcp.client import McpError
from tests.test_workflow import FakeQa, deps, start

APPROVE = Command(resume={"decision": "approved", "by": "bob", "role": "approver"})


def test_1_environment_comes_from_the_request_not_the_model(tmp_path):
    llm = ['{"intent":"run_tests","tag_expression":"@payment","environment":"staging","browser":"chromium","explanation":"x"}']
    graph = build(deps(tmp_path, llm_responses=llm), InMemorySaver())

    async def flow():
        paused = await start(graph, "wf-1", "run the payment tests")
        return paused["__interrupt__"][0].value["plan"], (await graph.aget_state(run_config("wf-1"))).values
    plan, values = asyncio.run(flow())
    assert plan["environment"] == "local"
    assert any(f.startswith("environment: the model chose 'staging'") for f in values["fallbacks"])


def test_2_a_slow_model_call_does_not_block_the_event_loop(tmp_path):
    class Slow(FakeListChatModel):
        def _call(self, *a, **k):
            time.sleep(1.5)
            return super()._call(*a, **k)
    d = deps(tmp_path)
    d.llm.model = Slow(responses=['{"intent":"run_tests","tag_expression":"@payment","environment":"qa","browser":"chromium"}'])
    graph = build(d, InMemorySaver())

    async def flow():
        ticks = 0

        async def ticker():
            nonlocal ticks
            while True:
                await asyncio.sleep(0.05)
                ticks += 1
        t = asyncio.create_task(ticker())
        await start(graph, "wf-2", "run payment tests in qa")
        t.cancel()
        return ticks
    assert asyncio.run(flow()) >= 15  # ~30 expected; it was 1 while the call blocked the loop


class _Statuses(FakeQa):
    def __init__(self, plan, **kw):
        super().__init__(**kw)
        self.plan, self.n = plan, 0

    async def status(self, eid, wait):
        self.n += 1
        step = self.plan(self.n)
        if step == "error":
            raise McpError("unavailable", "blip")
        final = step == "PASSED"
        return {"status": step, "final": final, "message": "", "summary": {"total": 10, "passed": 10, "failed": 0} if final else None}


def test_3_only_errors_in_a_row_end_monitoring(tmp_path):
    qa = _Statuses(lambda n: "error" if n in (2, 4, 6) else ("PASSED" if n >= 8 else "RUNNING"))
    graph = build(deps(tmp_path, qa=qa), InMemorySaver())

    async def flow():
        await start(graph, "wf-3", "run payment regression in qa")
        return await graph.ainvoke(APPROVE, run_config("wf-3"))
    final = asyncio.run(flow())["final"]
    assert final["outcome"] == "COMPLETED" and final["test_result"] == "PASSED"


def test_4_step_limit_follows_the_monitor_deadline(tmp_path):
    d = deps(tmp_path, monitor_poll_s=5)
    assert run_config("wf", d.settings)["recursion_limit"] >= d.settings.monitor_timeout_s // 5 + 50
    qa = _Statuses(lambda n: "RUNNING")  # answers at once and never finishes
    d = deps(tmp_path, qa=qa, monitor_timeout_s=3)
    graph = build(d, InMemorySaver())

    async def flow():
        await start(graph, "wf-4", "run payment regression in qa")
        return await graph.ainvoke(APPROVE, run_config("wf-4", d.settings))
    final = asyncio.run(flow())["final"]
    assert final["outcome"] == "MONITOR_TIMEOUT" and qa.n <= 5  # paced: about one status call per poll interval


def test_5_past_documents_do_not_verify_claims_about_this_run(tmp_path):
    d = deps(tmp_path, llm_responses=['{"category":"APPLICATION_DEFECT","probable_root_cause":"x","supporting_evidence":'
                                      '["Refunding more than the captured amount is rejected"],"confidence":0.95,"recommended_action":"x"}'])
    ai = asyncio.run(analysis.analyze_one(d, "run-x", "Administrator signs in"))["ai"]
    assert ai["evidence_verified"] == [] and ai["confidence"] == 0.3


def test_6_unverified_model_category_does_not_replace_the_rules(tmp_path):
    qa = FakeQa(statuses=["FAILED"], failed=["Administrator signs in"])
    llm = ['{"intent":"run_tests","tag_expression":"@login","environment":"qa","browser":"chromium"}',
           '{"category":"APPLICATION_DEFECT","probable_root_cause":"x","supporting_evidence":["made up"],"confidence":0.9,"recommended_action":"x"}',
           '{"items":[]}']
    graph = build(deps(tmp_path, qa=qa, llm_responses=llm), InMemorySaver())

    async def flow():
        await start(graph, "wf-6", "run login tests in qa")
        return await graph.ainvoke(APPROVE, run_config("wf-6"))
    recs = [r["title"] for r in asyncio.run(flow())["final"]["recommendations"] if r["source"] == "rules"]
    assert recs == ["Administrator signs in: AUTHENTICATION_FAILURE"]


def test_7_tokens_of_invalid_replies_count_against_the_budget(tmp_path):
    usage = UsageTracker(tmp_path / "u.jsonl", 10**6, None, None)
    msgs = iter([AIMessage(content="not json", usage_metadata={"input_tokens": 500, "output_tokens": 300, "total_tokens": 800}),
                 AIMessage(content="still not", usage_metadata={"input_tokens": 900, "output_tokens": 300, "total_tokens": 1200})])
    llm = StructuredLLM(GenericFakeChatModel(messages=msgs), usage, "fake")
    try:
        llm.invoke("intent", {"tags": "", "environments": "", "browsers": "", "request": "x"}, IntentResult)
    except Exception:  # noqa: BLE001 - LLMFailure expected
        pass
    assert usage.used_today() == 2000


def test_8_a_lost_start_answer_is_recovered_from_mcp_history(tmp_path):
    class LostAnswer(FakeQa):
        async def run(self, plan):
            self.runs.append(plan)
            raise McpError("unavailable", "TimeoutError")

        async def history(self, limit):
            now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            return {"executions": [{"executionId": "run-20261121-010101-abcdef", "status": "RUNNING", "createdAt": now,
                                    "request": {"tagExpression": "(@payment and @regression) and not @quarantine",
                                                "environment": "qa", "browser": "chromium"}}]}
    qa = LostAnswer(statuses=["PASSED"])
    graph = build(deps(tmp_path, qa=qa), InMemorySaver())

    async def flow():
        await start(graph, "wf-8", "run payment regression in qa")
        return await graph.ainvoke(APPROVE, run_config("wf-8"))
    final = asyncio.run(flow())["final"]
    assert final["execution_id"] == "run-20261121-010101-abcdef" and final["test_result"] == "PASSED" and len(qa.runs) == 1
    assert any("MCP history shows run" in f for f in final["fallbacks"])


def test_9_named_scenario_is_looked_up_exactly_not_in_a_limited_list(tmp_path):
    graph = build(deps(tmp_path), InMemorySaver())

    async def flow():
        found = await start(graph, "wf-9a", 'run "Pay 70" in qa')
        missing = await start(graph, "wf-9b", 'run "No such scenario" in qa')
        return found, missing
    found, missing = asyncio.run(flow())
    assert found["__interrupt__"][0].value["plan"]["test_cases"] == 1
    assert missing["final"]["outcome"] == "NO_MATCH"
