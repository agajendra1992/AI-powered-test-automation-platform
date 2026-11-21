"""Nodes 6-8: execution planning, permission validation, human approval (LangGraph interrupt). Deterministic."""
from __future__ import annotations

from datetime import datetime, timezone

from langgraph.types import interrupt

from ...security.auth import Principal
from ...security.policy import approval_requirement, can_approve, can_execute
from ..state import WorkflowState
from .deps import Deps


async def execution_planning(state: WorkflowState, deps: Deps) -> dict:
    intent, discovery = state["intent"], state["discovery"]
    if intent.get("full_regression"):
        tool, extra = "run_regression_tests", {}
    elif intent.get("scenario_name"):
        tool, extra = "run_scenario_by_name", {"scenario_name": intent["scenario_name"]}
    else:
        tool, extra = "run_tests_by_tag", {"tag_expression": intent["tag_expression"]}
    env = intent["environment"]
    plan = {"tool": tool, **extra, "environment": env, "browser": intent["browser"], "threads": 4,
            "test_cases": discovery["test_cases"], "regression_test_cases": discovery["regression_test_cases"],
            "approval": approval_requirement(deps.settings, env),
            "engine": "Java Playwright + Cucumber + TestNG via the MCP server (mvn test)"}
    if not intent.get("full_regression"):
        plan["not_included"] = (f"{discovery['regression_test_cases'] - discovery['test_cases']} regression test cases are not in this plan; "
                                "the complete regression can always be run instead")
    if env in deps.settings.approval_environments:
        plan["mcp_note"] = f"The MCP server also holds '{env}' runs until an MCP admin other than the service account approves them"
    return {"plan": plan, "trail": ["execution_planning"]}


async def permission_validation(state: WorkflowState, deps: Deps) -> dict:
    principal = Principal(state["requested_by"], state["requester_role"])
    decision = can_execute(deps.settings, principal, state["plan"]["environment"])
    update = {"permission": {"allowed": decision.allowed, "reason": decision.reason}, "trail": ["permission_validation"]}
    if not decision.allowed:
        update["outcome"] = "FORBIDDEN"
    return update


async def human_approval(state: WorkflowState, deps: Deps) -> dict:
    """Pauses the workflow (checkpointed) until /approve or /reject resumes it with a decision."""
    decision = interrupt({"type": "approval_required", "workflow_id": state.get("workflow_id"), "plan": state["plan"],
                          "requested_by": state["requested_by"], "requirement": state["plan"]["approval"]})
    approver = Principal(str(decision.get("by")), str(decision.get("role")))
    approval = {"decision": decision.get("decision"), "by": approver.name, "role": approver.role, "note": decision.get("note", ""),
                "at": datetime.now(timezone.utc).isoformat()}
    if approval["decision"] == "approved":
        check = can_approve(deps.settings, approver, state["requested_by"], state["plan"]["environment"])
        if not check.allowed:  # re-checked here, not only in the API: the graph never trusts its caller
            approval.update(decision="rejected", note=f"Approval not valid: {check.reason}")
    update = {"approval": approval, "trail": ["human_approval"]}
    if approval["decision"] != "approved":
        update["outcome"] = "REJECTED"
    return update
