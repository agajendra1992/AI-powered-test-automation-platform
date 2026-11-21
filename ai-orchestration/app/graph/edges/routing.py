"""Conditional routing. Pure functions of the state: no I/O, no model calls."""
from __future__ import annotations

from ..state import WorkflowState

FINAL = "final_response"


def after_intent(state: WorkflowState) -> str:
    return FINAL if state.get("outcome") == "ERROR" else "request_validation"


def after_validation(state: WorkflowState) -> str:
    # Invalid request or a clarification question → straight to the final response
    return FINAL if state.get("outcome") in ("INVALID", "NEEDS_CLARIFICATION", "UNSUPPORTED") else "test_discovery"


def after_discovery(state: WorkflowState) -> str:
    # No matching scenarios → final response with alternatives
    return FINAL if state.get("outcome") in ("NO_MATCH", "ERROR") else "rag_retrieval"


def after_permission(state: WorkflowState) -> str:
    return FINAL if not state.get("permission", {}).get("allowed") else "human_approval"


def after_approval(state: WorkflowState) -> str:
    # Rejected → stop; granted → execute through MCP
    return "mcp_execution" if state.get("approval", {}).get("decision") == "approved" else FINAL


def after_execution(state: WorkflowState) -> str:
    return FINAL if state.get("outcome") == "ERROR" else "execution_monitoring"


def after_monitoring(state: WorkflowState) -> str:
    # Running → keep monitoring; finished (or monitor deadline passed) → collect the actual results
    execution, monitor = state.get("execution", {}), state.get("monitor", {})
    if execution.get("final") or monitor.get("timed_out") or state.get("outcome") == "ERROR":
        return "result_collection"
    return "execution_monitoring"


def after_results(state: WorkflowState) -> str:
    results = state.get("results", {})
    failed = results.get("failed_scenarios") or []
    return "failure_analysis" if failed or results.get("status") in ("FAILED", "ERROR", "TIMED_OUT") else "recommendation_generation"
