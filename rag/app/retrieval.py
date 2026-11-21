"""Hybrid retrieval: semantic (ChromaDB) + keyword (BM25), metadata filters and access control,
reciprocal-rank fusion, lexical reranking, injection down-ranking, and numbered citations."""
from __future__ import annotations

import re

from .bm25 import tokenize
from .security import Principal, fence, injection_signals
from .store import Store

FILTER_KEYS = {"document_type", "module", "scenario", "tag", "environment", "status", "java_kind", "source", "error_type",
               "feature", "class", "endpoint", "document_id"}


def build_where(filters: dict | None, principal: Principal) -> dict:
    clauses = [{"access_level": {"$in": principal.visible_levels()}}]
    for key, value in (filters or {}).items():
        if key not in FILTER_KEYS:
            raise ValueError(f"Unknown filter '{key}'. Allowed: {sorted(FILTER_KEYS)}")
        if isinstance(value, list):
            clauses.append({key: {"$in": [str(v) for v in value]}})
        else:
            clauses.append({key: str(value)})
    return clauses[0] if len(clauses) == 1 else {"$and": clauses}


def _matches(meta: dict, filters: dict | None, principal: Principal) -> bool:
    if not principal.sees(meta.get("access_level", "internal")):
        return False
    for key, value in (filters or {}).items():
        values = [str(v) for v in value] if isinstance(value, list) else [str(value)]
        if str(meta.get(key, "")) not in values:
            return False
    return True


def search(store: Store, query: str, principal: Principal, k: int = 8, filters: dict | None = None, mode: str = "hybrid") -> list[dict]:
    where = build_where(filters, principal)
    pool = max(k * 5, 20)
    semantic: list[str] = []
    if mode in ("hybrid", "semantic") and store.count():
        res = store.collection.query(query_embeddings=store.embedder.embed([query]), n_results=min(pool, store.count()), where=where)
        semantic = res["ids"][0]
    keyword: list[str] = []
    if mode in ("hybrid", "keyword"):
        allowed = None
        if filters or principal.max_access != "restricted":
            got = store.collection.get(where=where, include=["metadatas"])
            allowed = set(got["ids"])
        keyword = [cid for cid, _ in store.bm25.search(query, allowed, pool)]
    fused: dict[str, float] = {}
    for ranking in (semantic, keyword):
        for rank, cid in enumerate(ranking):
            fused[cid] = fused.get(cid, 0.0) + 1.0 / (60 + rank + 1)
    if not fused:
        return []
    got = store.collection.get(ids=list(fused), include=["documents", "metadatas"])
    terms = set(tokenize(query))
    phrase = query.strip().lower()
    results, seen_hashes = [], set()
    for cid, text, meta in zip(got["ids"], got["documents"], got["metadatas"]):
        if not _matches(meta, filters, principal):  # defence in depth: never return what the caller may not see
            continue
        lowered = text.lower()
        overlap = len(terms & set(tokenize(text))) / (len(terms) or 1)
        signals = injection_signals(text)
        score = fused[cid] + 0.02 * overlap + (0.01 if phrase and phrase in lowered else 0) - (0.02 if signals else 0)
        if meta.get("content_hash") in seen_hashes:  # duplicate chunk from another document: keep the best one
            continue
        seen_hashes.add(meta.get("content_hash"))
        results.append({"chunk_id": cid, "document_id": meta.get("document_id"), "source": meta.get("source"),
                        "score": round(score, 5), "semantic_rank": semantic.index(cid) + 1 if cid in semantic else None,
                        "keyword_rank": keyword.index(cid) + 1 if cid in keyword else None,
                        "metadata": {k: v for k, v in meta.items() if k not in ("content_hash",)},
                        "text": text, "injection_signals": signals})
    results.sort(key=lambda r: r["score"], reverse=True)
    top = results[:k]
    for i, r in enumerate(top, 1):
        line = r["metadata"].get("line")
        r["citation"] = f"[{i}] {r['source']}" + (f":{line}" if line else "") + (
            f" ({r['metadata'].get('scenario')})" if r["metadata"].get("scenario") else "")
    return top


def build_context(results: list[dict], max_chars: int = 12_000) -> str:
    parts, used = [], 0
    for i, r in enumerate(results, 1):
        block = fence(f"[{i}] {r['source']}", r["text"])
        if used + len(block) > max_chars:
            break
        parts.append(block)
        used += len(block)
    return "\n\n".join(parts)


def check_citations(answer: str, count: int) -> dict:
    cited = sorted({int(n) for n in re.findall(r"\[(\d{1,3})\]", answer or "")})
    invalid = [n for n in cited if n < 1 or n > count]
    return {"cited": [n for n in cited if 1 <= n <= count], "invalid": invalid, "has_citations": bool(cited) and not invalid}
