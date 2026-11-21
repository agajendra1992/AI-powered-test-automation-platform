"""Typed workflow state (LangGraph StateGraph). Every field is set by deterministic code, except the
`intent`, `failure_analysis[*].ai` and `recommendations` entries that carry `"source": "llm"`."""
from __future__ import annotations

import operator
from typing import Annotated, TypedDict


class WorkflowState(TypedDict, total=False):
    workflow_id: str
    request: str
    requested_by: str
    requester_role: str
    received_at: str
    available_tags: dict            # tag -> test cases (from MCP)
    intent: dict                    # IntentResult + {"source": "llm" | "rules"}
    validation_errors: list
    discovery: dict                 # selection, test cases, sample scenarios (from MCP)
    alternatives: list
    knowledge: list                 # RAG documents: citation, type, excerpt
    rag_status: str                 # ok | unavailable | not_configured
    plan: dict
    permission: dict
    approval: dict                  # decision, by, role, note, at
    execution: dict                 # MCP execution id, status, final, message, summary
    monitor: dict                   # polls, deadline, timed_out
    results: dict                   # framework results: status, summary, failed scenarios, allure
    failure_analysis: list
    recommendations: list
    outcome: str
    final: dict
    errors: Annotated[list, operator.add]
    trail: Annotated[list, operator.add]
    llm_calls: Annotated[list, operator.add]
    fallbacks: Annotated[list, operator.add]
