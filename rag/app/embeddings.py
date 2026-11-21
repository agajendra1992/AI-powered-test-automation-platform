"""Configurable embedding providers. The index records which provider/model built it; mixing is refused."""
from __future__ import annotations

import hashlib
import math
import os
import re

import httpx

from .config import Settings


class Embedder:
    name: str
    model: str

    def embed(self, texts: list[str]) -> list[list[float]]:
        raise NotImplementedError


class OnnxMiniLM(Embedder):
    """Default: all-MiniLM-L6-v2 via ONNX Runtime (ChromaDB's built-in, ~80 MB download on first use, no PyTorch)."""

    def __init__(self) -> None:
        from chromadb.utils.embedding_functions import ONNXMiniLM_L6_V2

        self._fn = ONNXMiniLM_L6_V2()
        self.name, self.model = "onnx", "all-MiniLM-L6-v2"

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [list(map(float, v)) for v in self._fn(texts)]


class OllamaEmbedder(Embedder):
    def __init__(self, settings: Settings) -> None:
        if not settings.embedding_model:
            raise ValueError("RAG_EMBEDDING_MODEL is required for ollama (e.g. nomic-embed-text)")
        self.name, self.model = "ollama", settings.embedding_model
        self._url = (settings.embedding_base_url or "http://localhost:11434").rstrip("/") + "/api/embed"

    def embed(self, texts: list[str]) -> list[list[float]]:
        r = httpx.post(self._url, json={"model": self.model, "input": texts}, timeout=120)
        r.raise_for_status()
        return r.json()["embeddings"]


class OpenAiCompatibleEmbedder(Embedder):
    """OpenAI /v1/embeddings or any compatible server. Key from RAG_EMBEDDING_API_KEY (environment only)."""

    def __init__(self, settings: Settings) -> None:
        if not settings.embedding_model:
            raise ValueError("RAG_EMBEDDING_MODEL is required for openai")
        self.name, self.model = "openai", settings.embedding_model
        self._url = (settings.embedding_base_url or "https://api.openai.com/v1").rstrip("/") + "/embeddings"
        self._key = os.environ.get("RAG_EMBEDDING_API_KEY", "")

    def embed(self, texts: list[str]) -> list[list[float]]:
        headers = {"Authorization": f"Bearer {self._key}"} if self._key else {}
        r = httpx.post(self._url, json={"model": self.model, "input": texts}, headers=headers, timeout=120)
        r.raise_for_status()
        return [d["embedding"] for d in sorted(r.json()["data"], key=lambda d: d["index"])]


class HashEmbedder(Embedder):
    """Deterministic feature-hashing embedder (no model, no download). For tests and CI only: lexical, not semantic."""

    def __init__(self, dims: int = 384) -> None:
        self.name, self.model, self._dims = "hash", f"hash-{dims}", dims

    def embed(self, texts: list[str]) -> list[list[float]]:
        out = []
        for text in texts:
            v = [0.0] * self._dims
            tokens = re.findall(r"[a-z0-9]+", text.lower())
            for tok in tokens + [a + "_" + b for a, b in zip(tokens, tokens[1:])]:
                h = int(hashlib.md5(tok.encode()).hexdigest(), 16)
                v[h % self._dims] += 1.0 if (h >> 8) & 1 else -1.0
            norm = math.sqrt(sum(x * x for x in v)) or 1.0
            out.append([x / norm for x in v])
        return out


def create(settings: Settings) -> Embedder:
    return {"onnx": lambda: OnnxMiniLM(), "ollama": lambda: OllamaEmbedder(settings),
            "openai": lambda: OpenAiCompatibleEmbedder(settings), "hash": lambda: HashEmbedder()}[settings.embedding_provider]()
