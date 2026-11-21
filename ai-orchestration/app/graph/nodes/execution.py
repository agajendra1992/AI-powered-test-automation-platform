"""Nodes 9-11: execution through MCP (Java framework), monitoring, result collection. The test
result is whatever the framework reports; nothing here interprets or changes it."""
from __future__ import annotations

import asyncio
import time
from datetime import datetime, timedelta, timezone

from ...mcp.client import McpError
from ..state import WorkflowState
from .deps import Deps


async def mcp_execution(state: WorkflowState, deps: Deps) -> dict:
    sent_at, note = datetime.now(timezone.utc), []
    try:
        started = await deps.qa.run(state["plan"])
    except McpError as e:
        if e.code != "unavailable":  # MCP answered and refused: nothing started
            return {"outcome": "ERROR", "errors": [f"MCP refused to start the run: {e.message}"], "trail": ["mcp_execution"]}
        # The request may have reached MCP even though the answer was lost (timeout, dropped connection).
        started = await _find_started_run(deps, state["plan"], sent_at)
        if started is None:
            return {"outcome": "ERROR", "errors": [f"Starting the run through MCP failed ({e.message}). It may still have started: "
                                                   "check get_execution_history before starting it again."], "trail": ["mcp_execution"]}
        note = [f"mcp_execution: the start call failed ({e.code}) but MCP history shows run {started['executionId']}; monitoring it"]
    deps.telemetry.count("ai_executions_started_total", environment=state["plan"]["environment"])
    return {"execution": {"execution_id": started.get("executionId"), "status": started.get("status"), "final": False,
                          "message": started.get("message")},
            "monitor": {"polls": 0, "deadline": time.time() + deps.settings.monitor_timeout_s, "timed_out": False},
            "fallbacks": note, "trail": ["mcp_execution"]}


async def _find_started_run(deps: Deps, plan: dict, sent_at: datetime) -> dict | None:
    """The one run in MCP's history created since the start call that matches this plan, or None if not exactly one."""
    try:
        runs = (await deps.qa.history(10)).get("executions", [])
    except McpError:
        return None
    matches = []
    for r in runs:
        req = r.get("request") or {}
        try:
            created = datetime.fromisoformat(str(r.get("createdAt")).replace("Z", "+00:00"))
        except ValueError:
            continue
        selection_matches = _tags(plan["tag_expression"]) <= _tags(req.get("tagExpression")) if plan.get("tag_expression") else \
            plan.get("scenario_name") in (req.get("scenarioNames") or []) if plan.get("scenario_name") else not req.get("tagExpression")
        if created >= sent_at - timedelta(seconds=5) and req.get("environment") == plan["environment"] \
                and req.get("browser") == plan["browser"] and selection_matches:
            matches.append(r)
    return matches[0] if len(matches) == 1 else None


async def execution_monitoring(state: WorkflowState, deps: Deps) -> dict:
    execution, monitor = dict(state["execution"]), dict(state["monitor"])
    remaining = monitor["deadline"] - time.time()
    if remaining <= 0:
        monitor["timed_out"] = True
        return {"monitor": monitor, "fallbacks": ["monitoring: deadline reached; the run continues in the MCP server"],
                "trail": ["execution_monitoring"]}
    wait = int(min(deps.settings.monitor_poll_s, max(1, remaining)))
    asked = time.monotonic()
    try:
        status = await deps.qa.status(execution["execution_id"], wait)
    except McpError as e:
        monitor["polls"] += 1
        monitor["errors"] = monitor.get("errors", 0) + 1
        if monitor["errors"] >= 3:
            return {"monitor": monitor, "outcome": "ERROR", "errors": [f"Lost contact with the MCP server: {e.message}"],
                    "trail": ["execution_monitoring"]}
        return {"monitor": monitor, "trail": ["execution_monitoring"]}
    monitor["polls"] += 1
    monitor["errors"] = 0  # the limit is for errors in a row
    execution.update(status=status.get("status"), final=bool(status.get("final")), message=status.get("message"),
                     summary=status.get("summary"))
    early = wait - (time.monotonic() - asked)
    if not execution["final"] and early > 0:
        await asyncio.sleep(early)  # MCP answered before the poll interval: keep the loop at one step per interval
    return {"execution": execution, "monitor": monitor, "trail": ["execution_monitoring"]}


async def result_collection(state: WorkflowState, deps: Deps) -> dict:
    execution = state["execution"]
    results = {"status": execution.get("status"), "final": execution.get("final"), "summary": execution.get("summary")}
    if not execution.get("final"):
        results["note"] = "Monitoring stopped before the run finished; results are not available yet. Query the MCP server later."
        return {"results": results, "outcome": "MONITOR_TIMEOUT" if state.get("monitor", {}).get("timed_out") else state.get("outcome", "ERROR"),
                "trail": ["result_collection"]}
    eid = execution["execution_id"]
    try:
        results["summary"] = (await deps.qa.summary(eid)).get("summary", results["summary"])
    except McpError as e:
        results["summary_error"] = e.message
    try:
        rows = (await deps.qa.cucumber_failures(eid)).get("scenarios", [])
        results["failed_scenarios"] = [{"scenario": r.get("scenario"), "status": r.get("status"), "failed_step": r.get("failedStep"),
                                        "error": r.get("error")} for r in rows if r.get("status") != "passed"]
    except McpError as e:
        results["failed_scenarios"] = []
        results["cucumber_error"] = e.message
    if not results["failed_scenarios"] and (results.get("summary") or {}).get("failed"):
        # A failure before the first step (browser launch, hooks) is "skipped" in Cucumber; the framework's own
        # failure records still have it.
        try:
            records = (await deps.qa.failed_scenarios(eid)).get("records", [])
            results["failed_scenarios"] = [{"scenario": r.get("name"), "status": "failed", "failed_step": r.get("failedStep"),
                                            "error": f"{r.get('errorType')}: {(r.get('message') or '')[:300]}"} for r in records if r.get("name")]
        except McpError as e:
            results["failure_records_error"] = e.message
    try:
        allure = await deps.qa.allure(eid)
        results["allure"] = {"by_status": allure.get("byStatus"), "results_directory": allure.get("resultsDirectory"),
                             "how_to_build_html": allure.get("howToBuildHtml")}
    except McpError as e:
        results["allure_error"] = e.message
    deps.telemetry.count("ai_executions_finished_total", status=str(execution.get("status")))
    return {"results": results, "outcome": "COMPLETED", "trail": ["result_collection"]}


def _tags(expression: str | None) -> set[str]:
    return {t for t in (expression or "").replace("(", " ").replace(")", " ").split() if t.startswith("@")}
