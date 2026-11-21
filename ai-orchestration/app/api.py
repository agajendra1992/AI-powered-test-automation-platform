"""FastAPI service for AI-orchestrated QA workflows (same conventions as the RAG service: bearer keys
stored as hashes, roles, JSON audit log, /health, /metrics).

  POST /ai/execute                                 operator   start a workflow (returns at once)
  GET  /ai/executions/{workflow_id}                viewer     status, pending approval, final response
  GET  /ai/executions/{workflow_id}/state          viewer     full checkpointed state
  POST /ai/executions/{workflow_id}/approve        an approver who is not the requester
  POST /ai/executions/{workflow_id}/reject         requester or approver
  POST /ai/failure-analysis                        operator   analyse a finished MCP run
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
import re
import secrets
import time
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, PlainTextResponse
from langgraph.types import Command
from pydantic import BaseModel, Field

from .config import Settings
from .graph.nodes.analysis import analyze_one
from .graph.nodes.deps import Deps
from .graph.workflow import build, run_config
from .langchain.providers.factory import ProviderUnavailable, create_chat_model
from .langchain.retrievers.rag_retriever import RagRetriever
from .langchain.structured import StructuredLLM
from .langchain.usage import UsageTracker
from .mcp.adapters import McpQaPlatform
from .mcp.client import McpError, McpGateway
from .observability.telemetry import Telemetry
from .persistence.store import WorkflowIndex
from .security.auth import KeyStore, Principal
from .security.policy import can_approve
from .security.redaction import redact, redact_tree

log = logging.getLogger("ai-orchestration")
WORKFLOW_ID = re.compile(r"wf-[0-9a-f]{16}")
RUN_ID = re.compile(r"run-\d{8}-\d{6}-[0-9a-f]{6}")


class ExecuteRequest(BaseModel):
    request: str = Field(min_length=3, max_length=2000)


class Decision(BaseModel):
    note: str = Field("", max_length=500)


class FailureAnalysisRequest(BaseModel):
    execution_id: str = Field(pattern=RUN_ID.pattern)
    scenario_name: str | None = Field(None, max_length=200)


def create_app(settings: Settings | None = None, deps: Deps | None = None, checkpointer=None) -> FastAPI:
    settings = settings or Settings.from_env()
    keys = KeyStore.load(settings.keys_file)
    telemetry = deps.telemetry if deps else Telemetry(settings.data_dir)
    index = WorkflowIndex(settings.data_dir / "workflows.db")
    tasks: dict[str, asyncio.Task] = {}
    state: dict = {}

    def make_deps() -> Deps:
        if not settings.mcp_url:
            raise RuntimeError("AI_MCP_URL is not set")
        qa = McpQaPlatform(McpGateway(settings.mcp_url, settings.mcp_token, settings.mcp_timeout_s), settings.monitor_poll_s)
        retriever = RagRetriever(url=settings.rag_url, key=settings.rag_key, k=4) if settings.rag_url else None
        try:
            model = create_chat_model(settings)
            state["llm_note"] = None
        except ProviderUnavailable as e:
            model, state["llm_note"] = None, f"LLM disabled: {e}"
        name = f"{settings.llm_provider}/{settings.llm_model}" if model else "none"
        usage = UsageTracker(settings.data_dir / "llm-usage.jsonl", settings.daily_token_budget,
                             settings.price_input_per_mtok, settings.price_output_per_mtok)
        return Deps(settings, qa, StructuredLLM(model, usage, name), retriever, telemetry)

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI):
        async with contextlib.AsyncExitStack() as stack:
            saver = checkpointer
            if saver is None:
                from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
                settings.data_dir.mkdir(parents=True, exist_ok=True)
                saver = await stack.enter_async_context(AsyncSqliteSaver.from_conn_string(str(settings.data_dir / "checkpoints.db")))
            state["deps"] = deps or make_deps()
            state["graph"] = build(state["deps"], saver)
            yield
            for t in tasks.values():
                t.cancel()

    app = FastAPI(title="QA Platform AI orchestration", version="1.0.0", lifespan=lifespan)

    def principal(request: Request) -> Principal:
        header = request.headers.get("authorization", "")
        p = keys.authenticate(header[7:].strip() if header.lower().startswith("bearer ") else None)
        if p is None:
            telemetry.count("ai_auth_failures_total")
            telemetry.audit(None, request.url.path, "unauthorized")
            raise HTTPException(401, "A valid bearer key is required", headers={"WWW-Authenticate": "Bearer"})
        return p

    def need(role: str):
        def check(p: Principal = Depends(principal)) -> Principal:
            if not p.can(role):
                telemetry.audit(p.name, "authorize", "forbidden", needed=role)
                raise HTTPException(403, f"Role '{role}' required; you are '{p.role}'")
            return p
        return check

    def check_id(workflow_id: str) -> dict:
        if not WORKFLOW_ID.fullmatch(workflow_id):
            raise HTTPException(400, "workflow ids look like wf-<16 hex>")
        row = index.get(workflow_id)
        if not row:
            raise HTTPException(404, "No such workflow")
        return row

    def launch(workflow_id: str, payload) -> None:
        async def go():
            try:
                await state["graph"].ainvoke(payload, run_config(workflow_id, settings))
            except Exception as e:  # noqa: BLE001 - recorded for the status endpoint
                log.exception("workflow %s failed", workflow_id)
                state.setdefault("crashes", {})[workflow_id] = f"{type(e).__name__}: {redact(str(e))[:300]}"
        tasks[workflow_id] = asyncio.create_task(go())

    async def snapshot(workflow_id: str) -> dict:
        snap = await state["graph"].aget_state(run_config(workflow_id, settings))
        values = snap.values or {}
        running = workflow_id in tasks and not tasks[workflow_id].done()
        interrupts = [i.value for t in snap.tasks for i in (t.interrupts or [])]
        if values.get("final"):
            status = values["final"]["outcome"]
        elif interrupts:
            status = "AWAITING_APPROVAL"
        elif running:
            status = "RUNNING"
        elif workflow_id in state.get("crashes", {}):
            status = "ERROR"
        else:
            status = "PAUSED"  # e.g. the service restarted mid-run; the checkpoint holds the state
        return {"workflow_id": workflow_id, "status": status, "current_step": (values.get("trail") or [None])[-1],
                "pending_approval": interrupts[0] if interrupts else None, "values": values, "next": list(snap.next),
                "error": state.get("crashes", {}).get(workflow_id)}

    @app.exception_handler(ValueError)
    async def value_error(_: Request, exc: ValueError):
        return JSONResponse(status_code=400, content={"detail": redact(str(exc))})

    @app.get("/health")
    def health() -> dict:
        d = state.get("deps")
        return {"status": "UP", "llm": d.llm.model_name if d else None, "llm_note": state.get("llm_note"),
                "mcp": bool(settings.mcp_url), "rag": bool(settings.rag_url),
                "auth": "enabled" if len(keys) else "no keys loaded: every protected call is rejected"}

    @app.get("/metrics")
    def metrics() -> PlainTextResponse:
        return PlainTextResponse(telemetry.render())

    @app.post("/ai/execute", status_code=202)
    async def execute(body: ExecuteRequest, p: Principal = Depends(need("operator"))) -> dict:
        workflow_id = "wf-" + secrets.token_hex(8)
        index.add(workflow_id, p.name, body.request)
        telemetry.audit(p.name, "execute", "started", workflow_id=workflow_id, request=body.request)
        telemetry.count("ai_workflows_started_total")
        launch(workflow_id, {"workflow_id": workflow_id, "request": body.request, "requested_by": p.name, "requester_role": p.role})
        return {"workflow_id": workflow_id, "status": "RUNNING", "status_url": f"/ai/executions/{workflow_id}"}

    @app.get("/ai/executions/{workflow_id}")
    async def get_execution(workflow_id: str, p: Principal = Depends(need("viewer"))) -> dict:
        check_id(workflow_id)
        s = await snapshot(workflow_id)
        v = s["values"]
        return redact_tree({"workflow_id": workflow_id, "status": s["status"], "current_step": s["current_step"],
                            "pending_approval": s["pending_approval"], "execution_id": (v.get("execution") or {}).get("execution_id"),
                            "mcp_status": (v.get("execution") or {}).get("status"), "final": v.get("final"), "error": s["error"]})

    @app.get("/ai/executions/{workflow_id}/state")
    async def get_state(workflow_id: str, p: Principal = Depends(need("viewer"))) -> dict:
        check_id(workflow_id)
        s = await snapshot(workflow_id)
        return redact_tree({"workflow_id": workflow_id, "status": s["status"], "next": s["next"], "state": s["values"]})

    async def decide(workflow_id: str, decision: str, note: str, p: Principal) -> dict:
        row = check_id(workflow_id)
        s = await snapshot(workflow_id)
        if s["status"] != "AWAITING_APPROVAL":
            raise HTTPException(409, f"Workflow is {s['status']}, not awaiting approval")
        if workflow_id in tasks and not tasks[workflow_id].done():
            raise HTTPException(409, "Workflow is busy; try again")
        if decision == "approved":
            env = s["pending_approval"]["plan"]["environment"]
            check = can_approve(settings, p, row["requested_by"], env)
            if not check.allowed:
                telemetry.audit(p.name, "approve", "forbidden", workflow_id=workflow_id, reason=check.reason)
                raise HTTPException(403, check.reason)
        elif not (p.can("approver") or p.name == row["requested_by"]):
            raise HTTPException(403, "Only the requester or an approver can reject")
        telemetry.audit(p.name, decision, "ok", workflow_id=workflow_id, note=note)
        launch(workflow_id, Command(resume={"decision": decision, "by": p.name, "role": p.role, "note": note}))
        return {"workflow_id": workflow_id, "decision": decision, "by": p.name, "status": "RUNNING" if decision == "approved" else "REJECTED"}

    @app.post("/ai/executions/{workflow_id}/approve")
    async def approve(workflow_id: str, body: Decision, p: Principal = Depends(need("viewer"))) -> dict:
        return await decide(workflow_id, "approved", body.note, p)

    @app.post("/ai/executions/{workflow_id}/reject")
    async def reject(workflow_id: str, body: Decision, p: Principal = Depends(need("viewer"))) -> dict:
        return await decide(workflow_id, "rejected", body.note, p)

    @app.post("/ai/failure-analysis")
    async def failure_analysis(body: FailureAnalysisRequest, p: Principal = Depends(need("operator"))) -> dict:
        d: Deps = state["deps"]
        start = time.perf_counter()
        try:
            names = [body.scenario_name] if body.scenario_name else [
                r.get("scenario") for r in (await d.qa.cucumber_failures(body.execution_id)).get("scenarios", []) if r.get("status") != "passed"]
            if not names:  # failures before the first step are "skipped" in Cucumber; use the framework's records
                names = [r["name"] for r in (await d.qa.failed_scenarios(body.execution_id)).get("records", []) if r.get("name")]
        except McpError as e:
            raise HTTPException(502 if e.code == "unavailable" else 404, e.message)
        analyses = []
        for name in names[: settings.max_failures_analyzed]:
            a = await analyze_one(d, body.execution_id, name)
            a.pop("_llm_meta", None)
            analyses.append(a)
        telemetry.audit(p.name, "failure_analysis", "ok", execution_id=body.execution_id, scenarios=len(analyses))
        return redact_tree({"execution_id": body.execution_id, "analyses": analyses, "seconds": round(time.perf_counter() - start, 2),
                            "note": "Categories and causes are hypotheses; the test result comes from the framework."})

    return app

