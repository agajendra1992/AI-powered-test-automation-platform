"""LangChain chat model factory. Replaceable providers; keys from the environment only.

  none       → no model: every workflow step uses its deterministic path
  anthropic  → ChatAnthropic (default model claude-opus-5-5), key ANTHROPIC_API_KEY
  openai     → ChatOpenAI, key OPENAI_API_KEY, AI_LLM_MODEL required
  gemini     → ChatGoogleGenerativeAI, key GEMINI_API_KEY (or GOOGLE_API_KEY), AI_LLM_MODEL required
  local      → ChatOpenAI against an OpenAI-compatible server (Ollama, vLLM, ...), AI_LLM_BASE_URL + AI_LLM_MODEL
"""
from __future__ import annotations

import os

from langchain_core.language_models import BaseChatModel

from ...config import Settings


class ProviderUnavailable(RuntimeError):
    pass


def create_chat_model(settings: Settings) -> BaseChatModel | None:
    p = settings.llm_provider
    if p == "none":
        return None
    if p != "anthropic" and not settings.llm_model:
        raise ProviderUnavailable(f"AI_LLM_MODEL is required for provider '{p}'")
    common = {"timeout": settings.llm_timeout_s, "max_retries": 2}
    if p == "anthropic":
        from langchain_anthropic import ChatAnthropic

        if not os.environ.get("ANTHROPIC_API_KEY"):
            raise ProviderUnavailable("ANTHROPIC_API_KEY is not set")
        return ChatAnthropic(model=settings.llm_model, max_tokens=settings.llm_max_tokens, **common,
                             **({"base_url": settings.llm_base_url} if settings.llm_base_url else {}))
    if p in ("openai", "local"):
        from langchain_openai import ChatOpenAI

        key = os.environ.get("OPENAI_API_KEY") if p == "openai" else (os.environ.get("LOCAL_LLM_API_KEY") or "not-needed")
        if not key:
            raise ProviderUnavailable("OPENAI_API_KEY is not set")
        base = settings.llm_base_url or ("http://localhost:11434/v1" if p == "local" else None)
        return ChatOpenAI(model=settings.llm_model, api_key=key, max_tokens=settings.llm_max_tokens, **common,
                          **({"base_url": base} if base else {}))
    if p == "gemini":
        from langchain_google_genai import ChatGoogleGenerativeAI

        key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
        if not key:
            raise ProviderUnavailable("GEMINI_API_KEY is not set")
        return ChatGoogleGenerativeAI(model=settings.llm_model, api_key=key, max_output_tokens=settings.llm_max_tokens, **common)
    raise ProviderUnavailable(f"Unknown provider {p}")
