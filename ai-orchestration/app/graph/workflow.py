"""The QA workflow as a LangGraph StateGraph.

user_request → intent_classification → request_validation ─(invalid/clarify)→ final_response
  → test_discovery ─(no match)→ final_response
  → rag_retrieval (fallback if unavailable) → execution_planning → permission_validation ─(denied)→ final_response
  → human_approval [interrupt; checkpointed] ─(rejected)→ final_response
  → mcp_execution ─(error)→ final_response
  → execution_monitoring ⟲ (while running, until the deadline) → result_collection
  ─(failures)→ failure_analysis → recommendation_generation → final_response → END
"""
from __future__ import annotations

import functools
import time

from langgraph.graph import END, START, StateGraph

from .edges import routing
from .nodes import analysis, discovery, execution, intake, planning
from .nodes.deps import Deps
from .state import WorkflowState

NODES = {
    "user_request": intake.user_request,
    "intent_classification": intake.intent_classification,
    "request_validation": intake.request_validation,
    "test_discovery": discovery.test_discovery,
    "rag_retrieval": discovery.rag_retrieval,
    "execution_planning": planning.execution_planning,
    "permission_validation": planning.permission_validation,
    "human_approval": planning.human_approval,
    "mcp_execution": execution.mcp_execution,
    "execution_monitoring": execution.execution_monitoring,
    "result_collection": execution.result_collection,
    "failure_analysis": analysis.failure_analysis,
    "recommendation_generation": analysis.recommendation_generation,
    "final_response": analysis.final_response,
}


def _bind(name: str, fn, deps: Deps):
    @functools.wraps(fn)
    async def node(state: WorkflowState) -> dict:
        start = time.perf_counter()
        try:
            return await fn(state, deps)
        finally:
            deps.telemetry.time_node(name, time.perf_counter() - start)
    return node


def build(deps: Deps, checkpointer):
    g = StateGraph(WorkflowState)
    for name, fn in NODES.items():
        g.add_node(name, _bind(name, fn, deps))
    g.add_edge(START, "user_request")
    g.add_edge("user_request", "intent_classification")
    g.add_conditional_edges("intent_classification", routing.after_intent, ["request_validation", routing.FINAL])
    g.add_conditional_edges("request_validation", routing.after_validation, ["test_discovery", routing.FINAL])
    g.add_conditional_edges("test_discovery", routing.after_discovery, ["rag_retrieval", routing.FINAL])
    g.add_edge("rag_retrieval", "execution_planning")
    g.add_edge("execution_planning", "permission_validation")
    g.add_conditional_edges("permission_validation", routing.after_permission, ["human_approval", routing.FINAL])
    g.add_conditional_edges("human_approval", routing.after_approval, ["mcp_execution", routing.FINAL])
    g.add_conditional_edges("mcp_execution", routing.after_execution, ["execution_monitoring", routing.FINAL])
    g.add_conditional_edges("execution_monitoring", routing.after_monitoring, ["execution_monitoring", "result_collection"])
    g.add_conditional_edges("result_collection", routing.after_results, ["failure_analysis", "recommendation_generation"])
    g.add_edge("failure_analysis", "recommendation_generation")
    g.add_edge("recommendation_generation", routing.FINAL)
    g.add_edge(routing.FINAL, END)
    return g.compile(checkpointer=checkpointer)


def run_config(workflow_id: str, settings=None) -> dict:
    # Monitoring takes one step per poll interval (paced in the node), so the limit follows the deadline:
    # deadline / poll interval, plus the other nodes and a margin. 400 covers the defaults (45 min / 60 s).
    limit = 400
    if settings is not None:
        limit = max(limit, settings.monitor_timeout_s // max(1, settings.monitor_poll_s) + 100)
    return {"configurable": {"thread_id": workflow_id}, "recursion_limit": limit}
