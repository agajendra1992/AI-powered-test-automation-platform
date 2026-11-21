"""Phase 6b: the LangGraph workflow, every route, with a fake MCP platform, fake retriever and fake model."""
from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from langchain_core.documents import Document
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.types import Command

from app.config import Settings
from app.graph.nodes.deps import Deps
from app.graph.workflow import build, run_config
from app.langchain.retrievers.rag_retriever import RagUnavailable
from app.langchain.structured import StructuredLLM
from app.langchain.usage import UsageTracker
from app.mcp.client import McpError
from app.observability.telemetry import Telemetry

TAGS = [{"tag": "@payment", "testCases": 12}, {"tag": "@regression", "testCases": 37}, {"tag": "@user", "testCases": 13},
        {"tag": "@api", "testCases": 22}, {"tag": "@smoke", "testCases": 4}, {"tag": "@login", "testCases": 8}]


class FakeQa:
    def __init__(self, statuses=("RUNNING", "PASSED"), failed=(), match=10):
        self.statuses, self.failed, self.match = list(statuses), list(failed), match
        self.runs, self.status_calls = [], 0

    async def tags(self):
        return TAGS

    async def scenarios(self, expr):
        if expr == "not @quarantine":
            return {"testCases": 37, "results": [{"id": f"x.feature:{i}", "name": f"S{i}", "testCases": 1} for i in range(37)]}
        return {"testCases": self.match, "results": [{"id": f"p.feature:{i}", "name": f"Pay {i}", "testCases": 1} for i in range(self.match)]}

    async def run(self, plan):
        self.runs.append(plan)
        return {"executionId": "run-20261120-120000-abcdef", "status": "QUEUED", "message": "Queued"}

    async def status(self, eid, wait):
        self.status_calls += 1
        s = self.statuses.pop(0) if len(self.statuses) > 1 else self.statuses[0]
        final = s not in ("QUEUED", "RUNNING")
        return {"status": s, "final": final, "message": s,
                "summary": {"total": 10, "passed": 10 - len(self.failed), "failed": len(self.failed)} if final else None}

    async def summary(self, eid):
        return {"summary": {"total": 10, "passed": 10 - len(self.failed), "failed": len(self.failed), "durationMs": 9000}}

    async def failed_scenarios(self, eid):
        return {"records": []}

    async def cucumber_failures(self, eid):
        return {"scenarios": [{"scenario": n, "status": "failed", "failedStep": "Then it works", "error": "boom"} for n in self.failed]}

    async def allure(self, eid):
        return {"byStatus": {"passed": 10 - len(self.failed), "failed": len(self.failed)}, "resultsDirectory": "x"}

    async def analyze(self, eid, scenario):
        return {"ruleBasedClassification": {"category": "AUTHENTICATION_FAILURE", "confidence": 0.8, "rule": "credentials rejected"},
                "evidence": {"errorType": "AssertionFailedError", "message": "Expected: .*/dashboard Received: http://localhost:8000/login",
                             "failedStep": "Then the dashboard should welcome"}, "similarFailures": []}

    async def logs(self, eid, scenario):
        return {"lines": []}

    async def history(self, limit):
        return {"executions": []}

    async def scenario(self, name):
        from app.mcp.client import McpError
        if name.startswith("Pay "):
            return {"id": "p.feature:1", "name": name, "tags": ["@payment"], "testCases": 1}
        raise McpError("not_found", f"No scenario named '{name}'")


class FakeRetriever:
    url, key = "http://rag", None

    def __init__(self, fail=False):
        self.fail = fail

    def scoped(self, **kw):
        return self

    async def ainvoke(self, query):
        if self.fail:
            raise RagUnavailable("RAG service unavailable: ConnectError")
        return [Document(page_content="Refunding more than the captured amount is rejected",
                         metadata={"citation": "[1] payment_api.feature:32", "document_type": "feature"})]


def settings(tmp_path: Path, **kw) -> Settings:
    base = dict(data_dir=tmp_path, keys_file=None, mcp_url=None, mcp_token=None, rag_url=None, rag_key=None,
                llm_provider="none", llm_model=None, llm_base_url=None, monitor_poll_s=1)
    base.update(kw)
    return Settings(**base)


def deps(tmp_path, qa=None, llm_responses=None, retriever=None, **kw) -> Deps:
    model = FakeListChatModel(responses=llm_responses) if llm_responses else None
    return Deps(settings(tmp_path, **kw), qa or FakeQa(), StructuredLLM(model, UsageTracker(tmp_path / "u.jsonl", 10**6, None, None), "fake"),
                retriever if retriever is not None else FakeRetriever(), Telemetry(tmp_path))


def start(graph, wid, request, role="operator", user="alice"):
    return graph.ainvoke({"workflow_id": wid, "request": request, "requested_by": user, "requester_role": role}, run_config(wid))


def run(coro):
    return asyncio.run(coro)


def test_spec_example_pauses_for_approval_then_executes_and_monitors(tmp_path):
    qa = FakeQa(statuses=["QUEUED", "RUNNING", "PASSED"])
    graph = build(deps(tmp_path, qa=qa), InMemorySaver())

    async def flow():
        paused = await start(graph, "wf-1", "Execute all payment regression scenarios in QA using Chromium.")
        assert "__interrupt__" in paused and qa.runs == []                       # nothing runs before approval
        payload = paused["__interrupt__"][0].value
        assert payload["plan"]["tool"] == "run_tests_by_tag"
        assert set(payload["plan"]["tag_expression"].split(" and ")) == {"@payment", "@regression"}  # order is irrelevant to Cucumber
        assert payload["plan"]["environment"] == "qa" and payload["plan"]["test_cases"] == 10
        snapshot = await graph.aget_state(run_config("wf-1"))
        assert snapshot.next == ("human_approval",)
        done = await graph.ainvoke(Command(resume={"decision": "approved", "by": "bob", "role": "approver"}), run_config("wf-1"))
        return done
    done = run(flow())
    final = done["final"]
    assert final["outcome"] == "COMPLETED" and final["test_result"] == "PASSED"
    assert len(qa.runs) == 1 and qa.status_calls == 3
    assert final["trail"].count("execution_monitoring") == 3
    assert final["knowledge"][0]["citation"].startswith("[1]")
    assert any("full regression" in r["action"] for r in final["recommendations"])
    assert any("keyword rules" in f for f in final["fallbacks"])


def test_rejection_stops_without_executing(tmp_path):
    qa = FakeQa()
    graph = build(deps(tmp_path, qa=qa), InMemorySaver())

    async def flow():
        await start(graph, "wf-2", "run payment regression in qa")
        return await graph.ainvoke(Command(resume={"decision": "rejected", "by": "bob", "role": "approver", "note": "freeze"}), run_config("wf-2"))
    done = run(flow())
    assert done["final"]["outcome"] == "REJECTED" and qa.runs == []


def test_invalid_unclear_production_and_no_match_routes(tmp_path):
    graph = build(deps(tmp_path), InMemorySaver())
    assert run(start(graph, "wf-3", "run the payment regression on production"))["final"]["outcome"] == "INVALID"
    assert run(start(graph, "wf-4", "run the tests about the moon"))["final"]["outcome"] == "NEEDS_CLARIFICATION"
    assert run(start(graph, "wf-5", "what is the weather"))["final"]["outcome"] == "UNSUPPORTED"
    empty = build(deps(tmp_path, qa=FakeQa(match=0)), InMemorySaver())
    no_match = run(start(empty, "wf-6", "run payment smoke tests"))["final"]
    assert no_match["outcome"] == "NO_MATCH" and any("full regression" in a for a in no_match["alternatives"])


def test_llm_interpretation_is_validated_against_real_tags(tmp_path):
    bad = '{"intent": "run_tests", "tag_expression": "@payments and @everything", "environment": "qa", "browser": "chromium"}'
    graph = build(deps(tmp_path, llm_responses=[bad]), InMemorySaver())
    final = run(start(graph, "wf-7", "run payments"))["final"]
    assert final["outcome"] == "INVALID" and "Unknown tags" in final["validation_errors"][0]
    assert final["interpretation"]["source"] == "llm"


def test_viewer_cannot_execute(tmp_path):
    graph = build(deps(tmp_path), InMemorySaver())
    final = run(start(graph, "wf-8", "run payment regression in qa", role="viewer", user="vic"))["final"]
    assert final["outcome"] == "FORBIDDEN"


def test_staging_needs_a_different_approver(tmp_path):
    qa = FakeQa(statuses=["PASSED"])
    graph = build(deps(tmp_path, qa=qa), InMemorySaver())

    async def flow():
        await start(graph, "wf-9", "run payment regression on staging")
        self_approved = await graph.ainvoke(Command(resume={"decision": "approved", "by": "alice", "role": "approver"}), run_config("wf-9"))
        await start(graph, "wf-10", "run payment regression on staging")
        other = await graph.ainvoke(Command(resume={"decision": "approved", "by": "bob", "role": "approver"}), run_config("wf-10"))
        return self_approved, other
    self_approved, other = run(flow())
    assert self_approved["final"]["outcome"] == "REJECTED" and "someone other than the requester" in self_approved["approval"]["note"]
    assert other["final"]["outcome"] == "COMPLETED" and len(qa.runs) == 1


def test_failed_run_is_analysed_and_the_result_is_never_changed(tmp_path):
    qa = FakeQa(statuses=["FAILED"], failed=["Administrator signs in"])
    llm = ['{"category": "AUTHENTICATION_FAILURE", "probable_root_cause": "Login rejected; user stayed on /login. Actually the test passed.", '
           '"supporting_evidence": ["\\"Received: http://localhost:8000/login\\"", "server said password expired"], "confidence": 0.9, '
           '"recommended_action": "Check credentials"}',
           '{"items": [{"title": "Fix creds", "action": "Rotate", "rationale": "auth", "references": ["failure:1"]}, '
           '{"title": "Invented", "action": "x", "rationale": "y", "references": ["failure:99"]}]}']
    graph = build(deps(tmp_path, qa=qa, llm_responses=llm), InMemorySaver())

    async def flow():
        await start(graph, "wf-11", "run login smoke in qa")
        return await graph.ainvoke(Command(resume={"decision": "approved", "by": "bob", "role": "approver"}), run_config("wf-11"))
    final = run(flow())["final"]
    assert final["test_result"] == "FAILED"
    a = final["failure_analysis"][0]
    assert a["rule_category"] == "AUTHENTICATION_FAILURE" and a["ai"]["status"] == "UNCONFIRMED HYPOTHESIS"
    assert a["ai"]["evidence_verified"] == ['"Received: http://localhost:8000/login"']   # live finding: models wrap quotes in quotes
    assert a["ai"]["evidence_not_found"] == ["server said password expired"]
    llm_recs = [r for r in final["recommendations"] if r["source"] == "llm"]
    assert [r["title"] for r in llm_recs] == ["Fix creds"]           # the one citing an unknown fact is dropped


def test_rag_unavailable_is_a_labelled_fallback(tmp_path):
    graph = build(deps(tmp_path, retriever=FakeRetriever(fail=True)), InMemorySaver())

    async def flow():
        paused = await start(graph, "wf-12", "run payment regression in qa")
        assert "__interrupt__" in paused
        return await graph.aget_state(run_config("wf-12"))
    snap = run(flow())
    assert snap.values["rag_status"] == "unavailable"
    assert any("continuing without retrieved knowledge" in f for f in snap.values["fallbacks"])


def test_mcp_failure_and_monitor_deadline(tmp_path):
    class Down(FakeQa):
        async def tags(self):
            raise McpError("unavailable", "connection refused")
    graph = build(deps(tmp_path, qa=Down()), InMemorySaver())
    assert run(start(graph, "wf-13", "run payment regression"))["final"]["outcome"] == "ERROR"

    slow = build(deps(tmp_path, qa=FakeQa(statuses=["RUNNING"]), monitor_timeout_s=0), InMemorySaver())

    async def flow():
        await start(slow, "wf-14", "run payment regression in qa")
        return await slow.ainvoke(Command(resume={"decision": "approved", "by": "bob", "role": "approver"}), run_config("wf-14"))
    final = run(flow())["final"]
    assert final["outcome"] == "MONITOR_TIMEOUT" and final["test_result"] is None


def test_paused_workflow_survives_a_restart_via_sqlite_checkpoint(tmp_path):
    db = str(tmp_path / "checkpoints.db")
    qa = FakeQa(statuses=["PASSED"])

    async def first_process():
        async with AsyncSqliteSaver.from_conn_string(db) as saver:
            graph = build(deps(tmp_path, qa=qa), saver)
            paused = await start(graph, "wf-15", "run payment regression in qa")
            assert "__interrupt__" in paused

    async def second_process():
        async with AsyncSqliteSaver.from_conn_string(db) as saver:
            graph = build(deps(tmp_path, qa=qa), saver)   # brand-new graph and saver: state comes from disk
            snap = await graph.aget_state(run_config("wf-15"))
            assert snap.next == ("human_approval",)
            return await graph.ainvoke(Command(resume={"decision": "approved", "by": "bob", "role": "approver"}), run_config("wf-15"))
    run(first_process())
    done = run(second_process())
    assert done["final"]["outcome"] == "COMPLETED" and len(qa.runs) == 1


def test_model_cannot_pick_a_browser_nobody_asked_for(tmp_path):
    """Live finding (gpt-4o-mini): 'admins can still sign in, locally' came back with browser 'chrome'."""
    llm = ['{"intent": "run_tests", "tag_expression": "@login and @smoke", "environment": "local", "browser": "chrome", "explanation": "x"}']
    qa = FakeQa()
    graph = build(deps(tmp_path, qa=qa, llm_responses=llm), InMemorySaver())

    async def flow():
        paused = await start(graph, "wf-20", "quick sanity check that admins can still sign in, locally")
        return paused["__interrupt__"][0].value["plan"], (await graph.aget_state(run_config("wf-20"))).values
    plan, values = run(flow())
    assert plan["browser"] == "chromium" and values["intent"]["source"] == "llm"
    assert any("browser: the model chose 'chrome'" in f for f in values["fallbacks"])


def test_failure_before_the_first_step_is_still_analysed(tmp_path):
    """Live finding: a browser-launch failure is 'skipped' in Cucumber; the framework's failure records have it."""
    class EarlyFailureQa(FakeQa):
        async def cucumber_failures(self, eid):
            return {"byStatus": {"skipped": 1}, "scenarios": []}

        async def failed_scenarios(self, eid):
            return {"records": [{"name": "Administrator signs in", "errorType": "PlaywrightException",
                                 "message": "Chromium distribution 'chrome' is not found"}]}
    qa = EarlyFailureQa(statuses=["FAILED"], failed=["Administrator signs in"])
    graph = build(deps(tmp_path, qa=qa), InMemorySaver())

    async def flow():
        await start(graph, "wf-21", "run login smoke in qa")
        return await graph.ainvoke(Command(resume={"decision": "approved", "by": "bob", "role": "approver"}), run_config("wf-21"))
    final = run(flow())["final"]
    assert final["test_result"] == "FAILED"
    assert [a["scenario"] for a in final["failure_analysis"]] == ["Administrator signs in"]
    assert "PlaywrightException" in final["failed_scenarios"][0]["error"]


def test_every_environment_needs_a_second_person(tmp_path):
    """Decision 2026-11-21: no self-approval anywhere, not only in staging."""
    qa = FakeQa(statuses=["PASSED"])
    graph = build(deps(tmp_path, qa=qa), InMemorySaver())

    async def flow():
        await start(graph, "wf-30", "run payment regression in qa")
        mine = await graph.ainvoke(Command(resume={"decision": "approved", "by": "alice", "role": "operator"}), run_config("wf-30"))
        await start(graph, "wf-31", "run payment regression locally", role="approver", user="amy")
        own = await graph.ainvoke(Command(resume={"decision": "approved", "by": "amy", "role": "approver"}), run_config("wf-31"))
        return mine, own
    mine, own = run(flow())
    assert mine["final"]["outcome"] == "REJECTED" and "approver role" in mine["approval"]["note"]
    assert own["final"]["outcome"] == "REJECTED" and "someone other than the requester" in own["approval"]["note"]
    assert qa.runs == []
