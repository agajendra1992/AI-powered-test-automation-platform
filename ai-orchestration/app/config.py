"""Settings from environment variables (AI_*). Secrets are read from the environment or from files
(Docker secrets) and never logged."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env(name: str, default: str | None = None) -> str | None:
    v = os.environ.get(name)
    return v if v not in (None, "") else default


def secret(value_var: str, file_var: str) -> str | None:
    """A secret from VAR, or from the file named by VAR_FILE."""
    value = _env(value_var)
    if value:
        return value
    path = _env(file_var)
    return Path(path).read_text(encoding="utf-8").strip() if path else None


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    keys_file: Path | None
    mcp_url: str | None
    mcp_token: str | None
    rag_url: str | None
    rag_key: str | None
    llm_provider: str
    llm_model: str | None
    llm_base_url: str | None
    llm_timeout_s: int = 120
    llm_max_tokens: int = 4000
    daily_token_budget: int = 500_000
    price_input_per_mtok: float | None = None
    price_output_per_mtok: float | None = None
    allowed_environments: tuple[str, ...] = ("local", "qa", "staging")
    approval_environments: tuple[str, ...] = ("staging",)
    mcp_timeout_s: int = 90
    monitor_timeout_s: int = 45 * 60
    monitor_poll_s: int = 60
    max_failures_analyzed: int = 5
    extra: dict = field(default_factory=dict)

    @staticmethod
    def from_env() -> "Settings":
        provider = (_env("AI_LLM_PROVIDER", "none") or "none").lower()
        if provider not in {"none", "anthropic", "openai", "gemini", "local"}:
            raise ValueError(f"AI_LLM_PROVIDER must be none, anthropic, openai, gemini or local, got {provider}")
        envs = tuple(e.strip() for e in (_env("AI_ALLOWED_ENVS", "local,qa,staging") or "").split(",") if e.strip())
        unknown = set(envs) - {"local", "qa", "staging"}
        if unknown:
            raise ValueError(f"Unknown environments in AI_ALLOWED_ENVS: {sorted(unknown)} (production is never allowed)")
        price_in, price_out = _env("AI_PRICE_INPUT_PER_MTOK"), _env("AI_PRICE_OUTPUT_PER_MTOK")
        keys = _env("AI_KEYS_FILE")
        return Settings(
            data_dir=Path(_env("AI_DATA_DIR", ".ai-data")).resolve(),
            keys_file=Path(keys).resolve() if keys else None,
            mcp_url=_env("AI_MCP_URL"),
            mcp_token=secret("AI_MCP_TOKEN", "AI_MCP_TOKEN_FILE"),
            rag_url=_env("AI_RAG_URL"),
            rag_key=secret("AI_RAG_KEY", "AI_RAG_KEY_FILE"),
            llm_provider=provider,
            llm_model=_env("AI_LLM_MODEL", "claude-opus-5-5" if provider == "anthropic" else None),
            llm_base_url=_env("AI_LLM_BASE_URL"),
            llm_timeout_s=int(_env("AI_LLM_TIMEOUT_SECONDS", "120")),
            llm_max_tokens=int(_env("AI_LLM_MAX_TOKENS", "4000")),
            daily_token_budget=int(_env("AI_DAILY_TOKEN_BUDGET", "500000")),
            price_input_per_mtok=float(price_in) if price_in else None,
            price_output_per_mtok=float(price_out) if price_out else None,
            allowed_environments=envs,
            approval_environments=tuple(e.strip() for e in (_env("AI_APPROVAL_ENVS", "staging") or "").split(",") if e.strip()),
            mcp_timeout_s=int(_env("AI_MCP_TIMEOUT_SECONDS", "90")),
            monitor_timeout_s=int(_env("AI_MONITOR_TIMEOUT_MINUTES", "45")) * 60,
            monitor_poll_s=min(60, int(_env("AI_MONITOR_POLL_SECONDS", "60"))),
            max_failures_analyzed=int(_env("AI_MAX_FAILURES_ANALYZED", "5")),
        )
