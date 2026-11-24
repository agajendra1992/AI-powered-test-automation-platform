"""Nodes 12-14: failure analysis (MCP evidence + RAG + LangChain), recommendations, final response."""
from __future__ import annotations

import json
import re

from ...langchain.output_parsers.schemas import FailureAnalysisResult, Recommendations
from ...langchain.retrievers.rag_retriever import RagUnavailable
from ...langchain.structured import LLMFailure
from ...mcp.client import McpError
from ...security.redaction import fence, redact_tree
from ..state import WorkflowState
from .deps import Deps

ACTIONS = {
    "ENVIRONMENT_FAILURE": "Check that the application under test is up and the base URL is right, then rerun; not a test defect.",
    "NETWORK_FAILURE": "Check DNS/proxy/TLS between the test runner and the application, then rerun.",
    "AUTHENTICATION_FAILURE": "Check the credentials configured for this environment (AUTH_USERNAME/AUTH_PASSWORD) and token expiry.",
    "LOCATOR_FAILURE": "The UI changed or a locator is ambiguous: update the page object (prefer role/label/test-id locators).",
    "TIMING_SYNCHRONIZATION": "Wait for the real condition (response or web-first assertion) instead of a timeout; check for flakiness.",
    "TEST_DATA_ISSUE": "Check test data setup and isolation between parallel scenarios.",
    "API_FAILURE": "An API returned an error or broke its contract: raise it with the service team with the correlation id.",
    "APPLICATION_DEFECT": "Probable product defect: reproduce manually and raise a bug with the screenshot and trace.",
    "AUTOMATION_DEFECT": "Fix the test code (step definition, page object or hook) shown in the stack trace.",
    "INFRASTRUCTURE_FAILURE": "Fix the runner (browsers installed, memory, disk) and rerun.",
}


def _normalize(s: str) -> str:
    return re.sub(r"\s+", " ", s or "").strip().lower()


def _quote(q: str) -> str:
    """A quoted piece of evidence without the wrapping quotes/backticks models like to add."""
    return _normalize(q).strip("\"'`“”‘’ ")


def _plain(value) -> str:
    """Every string in a nested structure as plain text (no JSON escaping), so quotes match what was observed."""
    if isinstance(value, dict):
        return "\n".join(_plain(v) for v in value.values())
    if isinstance(value, (list, tuple)):
        return "\n".join(_plain(v) for v in value)
    return "" if value is None else str(value)


async def analyze_one(deps: Deps, execution_id: str, scenario: str) -> dict:
    """Evidence + rule category from MCP (deterministic), past failures from RAG, then one LangChain opinion."""
    try:
        mcp = await deps.qa.analyze(execution_id, scenario)
    except McpError as e:
        return {"scenario": scenario, "error": f"No evidence available: {e.message}"}
    rules = mcp.get("ruleBasedClassification", {})
    evidence = mcp.get("evidence", {})
    out = {"scenario": scenario, "test_result": "FAILED (from the framework; analysis never changes it)",
           "rule_category": rules.get("category"), "rule_confidence": rules.get("confidence"), "rule": rules.get("rule"),
           "evidence": redact_tree({k: evidence.get(k) for k in ("errorType", "message", "failedStep", "browserDiagnostics", "apiCalls")}),
           "similar_failures": mcp.get("similarFailures", [])[:5]}
    knowledge = []
    if deps.retriever is not None:
        try:
            past = deps.retriever.scoped(k=3, filters={"document_type": ["failure", "cucumber_report", "troubleshooting", "bug_report"]})
            docs = await past.ainvoke(f"{scenario} {evidence.get('errorType', '')} {(evidence.get('message') or '')[:300]}")
            knowledge = [{"citation": d.metadata.get("citation"), "excerpt": d.page_content[:300]} for d in docs]
        except RagUnavailable as e:
            out["knowledge_note"] = f"RAG unavailable ({e}); analysis without past failures"
    out["knowledge"] = knowledge
    if not deps.llm.enabled:
        out["ai"] = {"available": False, "reason": "No LLM configured; rule-based category only"}
        return out
    evidence_text = json.dumps(evidence, default=str)
    try:
        result, meta = await deps.llm.ainvoke("failure_analysis", {
            "rule_category": f"{rules.get('category')} ({rules.get('rule')}, confidence {rules.get('confidence')})",
            "evidence": fence("framework evidence", evidence_text, 6000),
            "knowledge": fence("knowledge base", json.dumps(knowledge), 3000)}, FailureAnalysisResult)
    except LLMFailure as e:
        out["ai"] = {"available": False, "reason": str(e)}
        return out
    # This run's evidence only (past documents cannot confirm a claim about this run), as plain values and as the
    # JSON text the model was shown: models often quote the JSON form ("errorType": "...", escaped quotes).
    corpus = _normalize(_plain(evidence)) + "\n" + _normalize(evidence_text)
    verified = [q for q in result.supporting_evidence if len(_quote(q)) >= 4 and _quote(q) in corpus]
    unverified = [q for q in result.supporting_evidence if q not in verified]
    confidence = result.confidence if verified else min(result.confidence, 0.3)
    out["ai"] = {"available": True, "status": "UNCONFIRMED HYPOTHESIS", "category": result.category,
                 "probable_root_cause": result.probable_root_cause, "confidence": round(confidence, 2),
                 "evidence_verified": verified, "evidence_not_found": unverified, "recommended_action": result.recommended_action,
                 "agrees_with_rules": result.category == rules.get("category"), **meta}
    out["_llm_meta"] = meta
    return out


async def failure_analysis(state: WorkflowState, deps: Deps) -> dict:
    results, eid = state.get("results", {}), state["execution"]["execution_id"]
    failed = results.get("failed_scenarios") or []
    analyses, calls = [], []
    for f in failed[: deps.settings.max_failures_analyzed]:
        a = await analyze_one(deps, eid, f["scenario"])
        if "_llm_meta" in a:
            calls.append({"node": "failure_analysis", **a.pop("_llm_meta")})
        analyses.append(a)
    if not failed and results.get("status") in ("ERROR", "TIMED_OUT"):
        analyses.append({"scenario": None, "rule_category": "ENVIRONMENT_FAILURE" if results.get("status") == "ERROR" else "INFRASTRUCTURE_FAILURE",
                         "note": f"The run ended with {results.get('status')} before scenario results existed; see get_execution_status consoleTail"})
    update = {"failure_analysis": analyses, "trail": ["failure_analysis"]}
    if calls:
        update["llm_calls"] = calls
    if len(failed) > deps.settings.max_failures_analyzed:
        update["fallbacks"] = [f"analysis limited to the first {deps.settings.max_failures_analyzed} of {len(failed)} failed scenarios"]
    return update


async def recommendation_generation(state: WorkflowState, deps: Deps) -> dict:
    recs, facts = [], {}
    for i, a in enumerate(state.get("failure_analysis") or [], 1):
        facts[f"failure:{i}"] = {k: a.get(k) for k in ("scenario", "rule_category", "evidence")}
        ai = a.get("ai") or {}
        # The model's category replaces the rules' only when some of its evidence was found in this run's evidence.
        category = ai.get("category") if ai.get("available") and ai.get("evidence_verified") else a.get("rule_category")
        if category in ACTIONS:
            recs.append({"title": f"{a.get('scenario') or 'Run'}: {category}", "action": ACTIONS[category], "references": [f"failure:{i}"],
                         "source": "rules"})
    for i, k in enumerate(state.get("knowledge") or [], 1):
        facts[f"kb:{i}"] = k
    outcome, results = state.get("outcome"), state.get("results", {})
    if outcome == "MONITOR_TIMEOUT":
        recs.append({"title": "Run still in progress", "action": f"Check get_execution_status {state['execution']['execution_id']} later.", "source": "rules"})
    if results.get("status") == "PASSED" and not state.get("intent", {}).get("full_regression"):
        recs.append({"title": "Targeted selection passed", "action": "Run the full regression before release (run_regression_tests).", "source": "rules"})
    if state.get("rag_status") in ("unavailable", "not_configured"):
        recs.append({"title": "Knowledge base not used", "action": "Start the RAG service to include past failures and documentation.", "source": "rules"})
    update = {"trail": ["recommendation_generation"]}
    if deps.llm.enabled and facts:
        try:
            result, meta = await deps.llm.ainvoke("recommendations", {"facts": fence("run facts", json.dumps(facts, default=str), 8000)}, Recommendations)
            for r in result.items:
                refs = [x for x in r.references if x in facts]
                if refs:  # recommendations must point at real facts
                    recs.append({**r.model_dump(), "references": refs, "source": "llm"})
            update["llm_calls"] = [{"node": "recommendation_generation", **meta}]
        except LLMFailure as e:
            update["fallbacks"] = [f"recommendations: model unavailable ({e}); rule-based only"]
    update["recommendations"] = recs
    return update


async def final_response(state: WorkflowState, deps: Deps) -> dict:
    outcome = state.get("outcome") or "COMPLETED"
    results = state.get("results", {})
    final = {
        "workflow_id": state.get("workflow_id"),
        "outcome": outcome,
        "test_result": results.get("status") if results.get("final") else None,
        "test_result_source": "Java Cucumber + TestNG framework via MCP (never decided by AI)",
        "request": state.get("request"),
        "interpretation": state.get("intent"),
        "validation_errors": state.get("validation_errors") or [],
        "alternatives": state.get("alternatives") or [],
        "plan": state.get("plan"),
        "approval": state.get("approval"),
        "execution_id": (state.get("execution") or {}).get("execution_id"),
        "summary": results.get("summary"),
        "failed_scenarios": results.get("failed_scenarios") or [],
        "allure": results.get("allure"),
        "failure_analysis": state.get("failure_analysis") or [],
        "recommendations": state.get("recommendations") or [],
        "knowledge": state.get("knowledge") or [],
        "rag_status": state.get("rag_status"),
        "fallbacks": state.get("fallbacks") or [],
        "errors": state.get("errors") or [],
        "llm_calls": state.get("llm_calls") or [],
        "trail": (state.get("trail") or []) + ["final_response"],
    }
    deps.telemetry.count("ai_workflows_finished_total", outcome=outcome)
    return {"final": redact_tree(final), "outcome": outcome, "trail": ["final_response"]}
