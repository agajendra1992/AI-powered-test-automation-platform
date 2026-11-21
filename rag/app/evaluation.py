"""Retrieval evaluation: recall@k and MRR over a gold set of (query, filters, expected source substrings)."""
from __future__ import annotations

from .retrieval import search
from .security import Principal
from .store import Store


def evaluate(store: Store, principal: Principal, cases: list[dict], k: int = 5, mode: str = "hybrid") -> dict:
    rows, hits, rr = [], 0, 0.0
    for case in cases:
        results = search(store, case["query"], principal, k=k, filters=case.get("filters"), mode=mode)
        rank = next((i for i, r in enumerate(results, 1)
                     if any(exp in (r["source"] or "") or exp == r["metadata"].get("scenario") for exp in case["expected"])), None)
        hits += 1 if rank else 0
        rr += 1.0 / rank if rank else 0.0
        rows.append({"query": case["query"], "rank": rank, "top": [r["citation"] for r in results[:3]]})
    n = len(cases) or 1
    return {"mode": mode, "k": k, "cases": len(cases), f"recall@{k}": round(hits / n, 3), "mrr": round(rr / n, 3), "results": rows}
