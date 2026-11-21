"""RAG service settings from environment variables (RAG_*). Secrets are read from the environment only."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    return value if value not in (None, "") else default


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    allowed_roots: tuple[Path, ...]
    keys_file: Path | None
    embedding_provider: str
    embedding_model: str | None
    embedding_base_url: str | None
    llm_provider: str
    llm_model: str | None
    llm_base_url: str | None
    max_document_bytes: int = 5_000_000
    max_chunk_chars: int = 2_400
    collection: str = "qa_knowledge"
    llm_timeout_seconds: int = 120
    allow_anonymous_read: bool = False
    extra: dict = field(default_factory=dict)

    @staticmethod
    def from_env() -> "Settings":
        data_dir = Path(_env("RAG_DATA_DIR", ".rag-data")).resolve()
        roots = tuple(Path(p).resolve() for p in (_env("RAG_ALLOWED_ROOTS", ".") or ".").split(os.pathsep) if p)
        keys = _env("RAG_KEYS_FILE")
        provider = (_env("RAG_EMBEDDING_PROVIDER", "onnx") or "onnx").lower()
        if provider not in {"onnx", "ollama", "openai", "hash"}:
            raise ValueError(f"RAG_EMBEDDING_PROVIDER must be onnx, ollama, openai or hash, got {provider}")
        llm = (_env("RAG_LLM_PROVIDER", "none") or "none").lower()
        if llm not in {"none", "anthropic", "openai", "local"}:
            raise ValueError(f"RAG_LLM_PROVIDER must be none, anthropic, openai or local, got {llm}")
        return Settings(
            data_dir=data_dir,
            allowed_roots=roots,
            keys_file=Path(keys).resolve() if keys else None,
            embedding_provider=provider,
            embedding_model=_env("RAG_EMBEDDING_MODEL"),
            embedding_base_url=_env("RAG_EMBEDDING_BASE_URL"),
            llm_provider=llm,
            llm_model=_env("RAG_LLM_MODEL", "claude-opus-5-5" if llm == "anthropic" else None),
            llm_base_url=_env("RAG_LLM_BASE_URL"),
            allow_anonymous_read=(_env("RAG_ALLOW_ANONYMOUS_READ", "false") or "false").lower() == "true",
        )
