"""Structured LLM calls: prompt template → model → Pydantic object, with redaction, budget, usage
tracking and error handling. Native structured output (tool calling) when the model supports it;
otherwise JSON parsing with one repair round. Callers validate the result against MCP facts."""
from __future__ import annotations

import asyncio
import json
import re
import time
from typing import TypeVar

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage
from pydantic import BaseModel, ValidationError

from ..security.redaction import redact
from .prompts.templates import PROMPTS
from .usage import BudgetExceeded, UsageTracker

T = TypeVar("T", bound=BaseModel)


class LLMFailure(RuntimeError):
    """Any failure to obtain a valid structured answer; workflow nodes fall back to deterministic results."""


class StructuredLLM:
    def __init__(self, model: BaseChatModel | None, usage: UsageTracker, model_name: str) -> None:
        self.model, self.usage, self.model_name = model, usage, model_name

    @property
    def enabled(self) -> bool:
        return self.model is not None

    def invoke(self, prompt_name: str, values: dict, schema: type[T]) -> tuple[T, dict]:
        if self.model is None:
            raise LLMFailure("No LLM provider configured (AI_LLM_PROVIDER=none)")
        version, template = PROMPTS[prompt_name]
        try:
            self.usage.check()
        except BudgetExceeded as e:
            raise LLMFailure(str(e)) from e
        messages = [m.model_copy(update={"content": redact(m.content) if isinstance(m.content, str) else m.content})
                    for m in template.format_messages(**values)]
        start = time.perf_counter()
        spent = {"input_tokens": 0, "output_tokens": 0}  # every model reply is billed, valid or not
        try:
            parsed, _ = self._call(messages, schema, spent)
        except LLMFailure:
            self.usage.record(prompt_name, self.model_name, spent["input_tokens"], spent["output_tokens"],
                              time.perf_counter() - start, "invalid_output")
            raise
        except Exception as e:  # noqa: BLE001 - provider errors of any SDK become LLMFailure
            self.usage.record(prompt_name, self.model_name, spent["input_tokens"], spent["output_tokens"],
                              time.perf_counter() - start, type(e).__name__)
            raise LLMFailure(f"LLM call failed: {type(e).__name__}") from e
        row = self.usage.record(prompt_name, self.model_name, spent["input_tokens"], spent["output_tokens"],
                                time.perf_counter() - start, "ok")
        return parsed, {"model": self.model_name, "prompt": f"{prompt_name} {version}", "input_tokens": row["input_tokens"],
                        "output_tokens": row["output_tokens"], "estimated_cost": row["estimated_cost"]}

    async def ainvoke(self, prompt_name: str, values: dict, schema: type[T]) -> tuple[T, dict]:
        """invoke() in a worker thread, so a slow model never blocks the service's event loop."""
        return await asyncio.to_thread(self.invoke, prompt_name, values, schema)

    def _call(self, messages: list[BaseMessage], schema: type[T], spent: dict) -> tuple[T, AIMessage | None]:
        try:
            # Claude (thinking always on for claude-opus-5-5) needs native structured outputs, not forced tool calls.
            kwargs = {"method": "json_schema"} if type(self.model).__name__ == "ChatAnthropic" else {}
            runnable = self.model.with_structured_output(schema, include_raw=True, **kwargs)
        except NotImplementedError:
            runnable = None
        if runnable is not None:
            out = runnable.invoke(messages)
            _add_usage(spent, out.get("raw"))
            if out.get("parsed") is not None:
                return out["parsed"], out.get("raw")
            raw_text = getattr(out.get("raw"), "content", "")
            raise LLMFailure(f"Model output did not match {schema.__name__}: {str(out.get('parsing_error'))[:200]}"
                             if not raw_text else f"Model output did not match {schema.__name__}")
        # Models without tool calling: ask for JSON, parse, one repair round
        instruction = HumanMessage(f"Reply with one JSON object matching this JSON schema, nothing else:\n{json.dumps(schema.model_json_schema())}")
        msgs = list(messages) + [instruction]
        raw = self.model.invoke(msgs)
        _add_usage(spent, raw)
        parsed = _parse(raw.content, schema)
        if parsed is None:
            msgs += [raw, HumanMessage("That was not valid JSON for the schema. Reply with only the JSON object.")]
            raw = self.model.invoke(msgs)
            _add_usage(spent, raw)
            parsed = _parse(raw.content, schema)
        if parsed is None:
            raise LLMFailure(f"Model output did not match {schema.__name__} after one repair round")
        return parsed, raw


def _add_usage(spent: dict, raw) -> None:
    meta = getattr(raw, "usage_metadata", None) or {}
    spent["input_tokens"] += int(meta.get("input_tokens", 0) or 0)
    spent["output_tokens"] += int(meta.get("output_tokens", 0) or 0)


def _parse(content, schema: type[T]) -> T | None:
    text = content if isinstance(content, str) else json.dumps(content)
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return schema.model_validate_json(text[start:end + 1])
    except ValidationError:
        return None
