"""LangChain retriever over the existing RAG service (POST /rag/search). No second ingestion pipeline
or ChromaDB client: indexing stays in rag/. Retrieved text is untrusted evidence, returned as
Documents with citations in metadata."""
from __future__ import annotations

import httpx
from langchain_core.callbacks import AsyncCallbackManagerForRetrieverRun, CallbackManagerForRetrieverRun
from langchain_core.documents import Document
from langchain_core.retrievers import BaseRetriever


class RagUnavailable(RuntimeError):
    pass


class RagRetriever(BaseRetriever):
    url: str
    key: str | None = None
    k: int = 5
    filters: dict = {}
    mode: str = "hybrid"
    timeout_s: float = 20.0

    def scoped(self, **changes) -> "RagRetriever":
        """A copy with different k / filters / mode (e.g. past failures only)."""
        return self.model_copy(update=changes)

    def _body(self, query: str) -> dict:
        return {"query": query[:1900], "k": self.k, "filters": self.filters, "mode": self.mode}

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.key}"} if self.key else {}

    @staticmethod
    def _documents(payload: dict) -> list[Document]:
        docs = []
        for r in payload.get("results", []):
            meta = dict(r.get("metadata", {}))
            meta.update({"citation": r.get("citation", ""), "score": r.get("score"), "injection_signals": r.get("injection_signals", [])})
            docs.append(Document(page_content=r.get("text", ""), metadata=meta))
        return docs

    def _get_relevant_documents(self, query: str, *, run_manager: CallbackManagerForRetrieverRun) -> list[Document]:
        try:
            r = httpx.post(self.url.rstrip("/") + "/rag/search", json=self._body(query), headers=self._headers(), timeout=self.timeout_s)
            r.raise_for_status()
        except httpx.HTTPError as e:
            raise RagUnavailable(f"RAG service unavailable: {type(e).__name__}") from e
        return self._documents(r.json())

    async def _aget_relevant_documents(self, query: str, *, run_manager: AsyncCallbackManagerForRetrieverRun) -> list[Document]:
        try:
            async with httpx.AsyncClient(timeout=self.timeout_s) as client:
                r = await client.post(self.url.rstrip("/") + "/rag/search", json=self._body(query), headers=self._headers())
                r.raise_for_status()
        except httpx.HTTPError as e:
            raise RagUnavailable(f"RAG service unavailable: {type(e).__name__}") from e
        return self._documents(r.json())
