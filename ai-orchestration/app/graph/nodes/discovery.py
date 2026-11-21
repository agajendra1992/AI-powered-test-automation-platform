"""Nodes 4-5: test discovery through MCP, RAG knowledge retrieval (LangChain retriever) with fallback."""
from __future__ import annotations

from ...langchain.retrievers.rag_retriever import RagUnavailable
from ...mcp.client import McpError
from ..state import WorkflowState
from .deps import Deps


def selection(intent: dict) -> str:
    """The effective Cucumber selection; @quarantine is always excluded."""
    if intent.get("full_regression"):
        return "not @quarantine"
    if intent.get("tag_expression"):
        return f"({intent['tag_expression']}) and not @quarantine"
    return "not @quarantine"


async def test_discovery(state: WorkflowState, deps: Deps) -> dict:
    intent = state["intent"]
    try:
        found = await deps.qa.scenarios(selection(intent))
        regression = await deps.qa.scenarios("not @quarantine")
    except McpError as e:
        return {"outcome": "ERROR", "errors": [f"Discovery failed: {e.message}"], "trail": ["test_discovery"]}
    scenarios = found.get("results", [])
    test_cases = found.get("testCases", 0)
    if intent.get("scenario_name"):
        try:  # exact lookup by name: no list limit
            one = await deps.qa.scenario(intent["scenario_name"])
            named = [one] if one.get("name") == intent["scenario_name"] and "@quarantine" not in (one.get("tags") or []) else []
        except McpError as e:
            if e.code != "not_found":
                return {"outcome": "ERROR", "errors": [f"Discovery failed: {e.message}"], "trail": ["test_discovery"]}
            named = []
        scenarios, test_cases = named, sum(s.get("testCases", 1) for s in named)
    discovery = {"selection": selection(intent) if not intent.get("scenario_name") else f"scenario: {intent['scenario_name']}",
                 "test_cases": test_cases, "regression_test_cases": regression.get("testCases", 0),
                 "scenarios": [f"{s.get('id')} {s.get('name')}" for s in scenarios[:20]]}
    if test_cases == 0:
        words = {w for w in (intent.get("tag_expression") or intent.get("scenario_name") or "").lower().replace("@", " ").split() if len(w) > 2}
        tags = state.get("available_tags", {})
        close = [f"{t} ({n} test cases)" for t, n in sorted(tags.items(), key=lambda kv: -kv[1])
                 if any(w[:4] in t for w in words)] or [f"{t} ({n} test cases)" for t, n in sorted(tags.items(), key=lambda kv: -kv[1])[:6]]
        return {"discovery": discovery, "outcome": "NO_MATCH", "alternatives": close + ["full regression (every non-quarantined scenario)"],
                "trail": ["test_discovery"]}
    return {"discovery": discovery, "trail": ["test_discovery"]}


async def rag_retrieval(state: WorkflowState, deps: Deps) -> dict:
    if deps.retriever is None:
        return {"rag_status": "not_configured", "knowledge": [], "fallbacks": ["knowledge: RAG service not configured; planning without retrieved knowledge"],
                "trail": ["rag_retrieval"]}
    query = f"{state['request']} {state['discovery']['selection']}"
    try:
        docs = await deps.retriever.ainvoke(query)
    except RagUnavailable as e:
        return {"rag_status": "unavailable", "knowledge": [], "fallbacks": [f"knowledge: {e}; continuing without retrieved knowledge"],
                "trail": ["rag_retrieval"]}
    knowledge = [{"citation": d.metadata.get("citation", ""), "type": d.metadata.get("document_type", ""),
                  "excerpt": d.page_content[:300], "untrusted_instructions_flagged": bool(d.metadata.get("injection_signals"))} for d in docs]
    return {"rag_status": "ok", "knowledge": knowledge, "trail": ["rag_retrieval"]}
