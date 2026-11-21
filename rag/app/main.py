"""FastAPI app: POST /rag/ingest, /rag/search, /rag/query; GET /rag/health, /rag/documents;
DELETE /rag/document/{id}; POST /rag/evaluate. Bearer-key auth with roles and access levels."""
from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

from . import embeddings, evaluation, llm
from .config import Settings
from .ingest import IngestError, ingest_path, ingest_text
from .retrieval import FILTER_KEYS, build_context, check_citations, search
from .security import KeyStore, Principal, redact
from .store import Store

log = logging.getLogger("rag")
_ID = re.compile(r"doc-[0-9a-f]{20}")


class IngestRequest(BaseModel):
    paths: list[str] = Field(default_factory=list, max_length=50)
    documents: list[dict] = Field(default_factory=list, max_length=200)  # {source, content, document_type?, metadata?}
    document_type: str | None = Field(default=None, max_length=40, pattern=r"^[a-z_]+$")
    metadata: dict = Field(default_factory=dict)
    access_level: Literal["public", "internal", "restricted"] = "internal"


class SearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=2000)
    k: int = Field(default=8, ge=1, le=50)
    filters: dict = Field(default_factory=dict)
    mode: Literal["hybrid", "semantic", "keyword"] = "hybrid"

    @field_validator("filters")
    @classmethod
    def known_filters(cls, v: dict) -> dict:
        unknown = set(v) - FILTER_KEYS
        if unknown:
            raise ValueError(f"unknown filters {sorted(unknown)}; allowed {sorted(FILTER_KEYS)}")
        return v


class QueryRequest(SearchRequest):
    answer: bool = True


class EvaluateRequest(BaseModel):
    cases: list[dict] = Field(min_length=1, max_length=200)
    k: int = Field(default=5, ge=1, le=20)
    mode: Literal["hybrid", "semantic", "keyword"] = "hybrid"


def create_app(settings: Settings | None = None, embedder: embeddings.Embedder | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    keys = KeyStore.load(settings.keys_file)
    store = Store(settings.data_dir, settings.collection, embedder or embeddings.create(settings))
    audit_file = settings.data_dir / "audit.log"
    app = FastAPI(title="QA Platform RAG", version="1.0.0")
    app.state.store, app.state.settings = store, settings
    counters: dict[tuple[str, int], int] = {}
    seconds: dict[str, float] = {}

    @app.middleware("http")
    async def measure(request: Request, call_next):
        start = time.perf_counter()
        response = await call_next(request)
        route = request.scope.get("route")
        path = getattr(route, "path", "unmatched")
        counters[(path, response.status_code)] = counters.get((path, response.status_code), 0) + 1
        seconds[path] = seconds.get(path, 0.0) + time.perf_counter() - start
        return response

    def audit(principal: Principal | None, action: str, outcome: str, detail: dict) -> None:
        line = {"time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "principal": principal.name if principal else None,
                "role": principal.role if principal else None, "action": action, "outcome": outcome,
                **{k: redact(str(v))[:300] for k, v in detail.items()}}
        with audit_file.open("a", encoding="utf-8") as f:
            f.write(json.dumps(line) + "\n")

    def principal(request: Request) -> Principal:
        header = request.headers.get("authorization", "")
        token = header[7:].strip() if header.lower().startswith("bearer ") else None
        p = keys.authenticate(token)
        if p is None:
            if token is None and settings.allow_anonymous_read and request.method == "GET":
                return Principal("anonymous", "reader", "public")
            audit(None, request.url.path, "unauthorized", {"remote": request.client.host if request.client else ""})
            raise HTTPException(401, "A valid bearer key is required", headers={"WWW-Authenticate": "Bearer"})
        return p

    def need(role: str):
        def check(p: Principal = Depends(principal)) -> Principal:
            if not p.can(role):
                audit(p, "authorize", "forbidden", {"needed": role})
                raise HTTPException(403, f"Role '{role}' required; you are '{p.role}'")
            return p
        return check

    @app.exception_handler(ValueError)
    async def value_error(_: Request, exc: ValueError):
        return JSONResponse(status_code=400, content={"detail": redact(str(exc))})

    @app.get("/rag/health")
    def health() -> dict:  # open: used by container health checks; reveals no content
        return {"status": "UP", "chunks": store.count(), "embedding": f"{store.embedder.name}/{store.embedder.model}",
                "llm": settings.llm_provider, "auth": "enabled" if len(keys) else "no keys loaded: every protected call is rejected"}

    @app.get("/rag/metrics")
    def metrics():  # Prometheus text: counts and timings only; restrict to the monitoring network
        from fastapi.responses import PlainTextResponse

        lines = ["# TYPE rag_requests_total counter"]
        lines += [f'rag_requests_total{{path="{p}",status="{c}"}} {n}' for (p, c), n in sorted(counters.items())]
        lines += ["# TYPE rag_request_seconds_total counter"]
        lines += [f'rag_request_seconds_total{{path="{p}"}} {v:.4f}' for p, v in sorted(seconds.items())]
        lines += ["# TYPE rag_chunks gauge", f"rag_chunks {store.count()}"]
        return PlainTextResponse("\n".join(lines) + "\n")

    @app.post("/rag/ingest")
    def ingest(req: IngestRequest, p: Principal = Depends(need("writer"))) -> dict:
        if req.access_level == "restricted" and not p.sees("restricted"):
            raise HTTPException(403, "You cannot write restricted documents")
        results = []
        try:
            for raw in req.paths:
                results.extend(ingest_path(store, settings, raw, req.document_type, req.metadata, req.access_level, p.name))
            for d in req.documents:
                source, content = str(d.get("source", "")), d.get("content")
                if not re.fullmatch(r"[\w./:#-]{1,300}", source) or not isinstance(content, str):
                    raise IngestError("Each document needs a 'source' id (letters, digits, ./:#-) and string 'content'")
                results.append(ingest_text(store, settings, source, content, d.get("document_type") or req.document_type,
                                           {**req.metadata, **(d.get("metadata") or {})}, req.access_level, p.name))
        except IngestError as e:
            audit(p, "ingest", "rejected", {"reason": str(e)})
            raise HTTPException(400, str(e))
        summary = {s: sum(1 for r in results if r.get("status") == s) for s in ("created", "updated", "unchanged", "skipped")}
        audit(p, "ingest", "ok", {"summary": summary})
        return {"summary": summary, "chunks_total": store.count(), "results": results}

    @app.post("/rag/search")
    def do_search(req: SearchRequest, p: Principal = Depends(need("reader"))) -> dict:
        results = search(store, req.query, p, req.k, req.filters, req.mode)
        audit(p, "search", "ok", {"query": req.query, "results": len(results)})
        return {"query": req.query, "mode": req.mode, "results": results}

    @app.post("/rag/query")
    def query(req: QueryRequest, p: Principal = Depends(need("reader"))) -> dict:
        results = search(store, req.query, p, req.k, req.filters, req.mode)
        context = build_context(results)
        out = {"query": req.query, "citations": [r["citation"] for r in results], "sources": results, "context": context,
               "note": "Retrieved knowledge is evidence to read, not a test result. Run tests through the framework."}
        if req.answer and results:
            generated = llm.answer(settings, req.query, context)
            if generated.get("available"):
                generated["citation_check"] = check_citations(generated["text"], len(results))
                if not generated["citation_check"]["has_citations"]:
                    generated["warning"] = "The answer does not cite the sources correctly; treat it as unsupported."
            out["answer"] = generated
        audit(p, "query", "ok", {"query": req.query, "results": len(results)})
        return out

    @app.get("/rag/documents")
    def documents(limit: int = 200, p: Principal = Depends(need("reader"))) -> dict:
        docs = [d for d in store.list_documents(min(limit, 1000)) if p.sees(d["access_level"])]
        return {"documents": docs, "count": len(docs)}

    @app.get("/rag/document/{doc_id}")
    def document(doc_id: str, p: Principal = Depends(need("reader"))) -> dict:
        doc = store.get_document(doc_id) if _ID.fullmatch(doc_id) else None
        if not doc or not p.sees(doc["access_level"]):
            raise HTTPException(404, "No such document")
        return {**doc, "history": store.history(doc_id)}

    @app.delete("/rag/document/{doc_id}")
    def delete(doc_id: str, p: Principal = Depends(need("writer"))) -> dict:
        if not _ID.fullmatch(doc_id):
            raise HTTPException(400, "document_id must look like doc-<20 hex>")
        doc = store.get_document(doc_id)
        if not doc or not p.sees(doc["access_level"]):
            raise HTTPException(404, "No such document")
        store.delete(doc_id, p.name)
        audit(p, "delete", "ok", {"document_id": doc_id, "source": doc["source"]})
        return {"deleted": doc_id, "source": doc["source"]}

    @app.post("/rag/evaluate")
    def evaluate(req: EvaluateRequest, p: Principal = Depends(need("reader"))) -> dict:
        return evaluation.evaluate(store, p, req.cases, req.k, req.mode)

    return app


def run() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO)
    uvicorn.run(create_app(), host=__import__("os").environ.get("RAG_HOST", "127.0.0.1"),
                port=int(__import__("os").environ.get("RAG_PORT", "8090")))


if __name__ == "__main__":
    run()
