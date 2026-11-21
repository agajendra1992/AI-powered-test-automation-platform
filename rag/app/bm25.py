"""Small Okapi BM25 keyword index (the keyword half of hybrid search). Rebuilt from the store at start-up."""
from __future__ import annotations

import math
import re
from collections import Counter

_TOKEN = re.compile(r"[a-z0-9]+")
_STOP = {"the", "a", "an", "and", "or", "of", "to", "in", "is", "it", "for", "on", "with", "be", "as", "by", "at", "this", "that"}


def tokenize(text: str) -> list[str]:
    # split camelCase/snake_case so `PaymentPage` matches "payment page"
    text = re.sub(r"([a-z])([A-Z])", r"\1 \2", text or "")
    return [t for t in _TOKEN.findall(text.lower()) if t not in _STOP]


class Bm25Index:
    def __init__(self, k1: float = 1.5, b: float = 0.75) -> None:
        self.k1, self.b = k1, b
        self.docs: dict[str, Counter] = {}
        self.lengths: dict[str, int] = {}
        self.df: Counter = Counter()

    def add(self, chunk_id: str, text: str) -> None:
        self.remove(chunk_id)
        tf = Counter(tokenize(text))
        self.docs[chunk_id] = tf
        self.lengths[chunk_id] = sum(tf.values())
        self.df.update(tf.keys())

    def remove(self, chunk_id: str) -> None:
        tf = self.docs.pop(chunk_id, None)
        if tf:
            self.df.subtract(tf.keys())
            self.lengths.pop(chunk_id, None)

    def search(self, query: str, allowed: set[str] | None, limit: int) -> list[tuple[str, float]]:
        terms = tokenize(query)
        n = len(self.docs) or 1
        avg = (sum(self.lengths.values()) / n) or 1.0
        scores: dict[str, float] = {}
        for cid, tf in self.docs.items():
            if allowed is not None and cid not in allowed:
                continue
            s = 0.0
            for t in terms:
                f = tf.get(t, 0)
                if f:
                    idf = math.log(1 + (n - self.df[t] + 0.5) / (self.df[t] + 0.5))
                    s += idf * f * (self.k1 + 1) / (f + self.k1 * (1 - self.b + self.b * self.lengths[cid] / avg))
            if s > 0:
                scores[cid] = s
        return sorted(scores.items(), key=lambda kv: kv[1], reverse=True)[:limit]
