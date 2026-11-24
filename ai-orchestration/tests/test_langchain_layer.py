"""Phase 4b: LangChain layer (providers, prompts, structured output, usage, errors)."""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest
from langchain_core.language_models.fake_chat_models import FakeListChatModel

from app.config import Settings
from app.langchain.output_parsers.schemas import FailureAnalysisResult, IntentResult
from app.langchain.providers.factory import ProviderUnavailable, create_chat_model
from app.langchain.structured import LLMFailure, StructuredLLM
from app.langchain.usage import UsageTracker
from app.security.redaction import fence, redact


def settings(tmp_path: Path, **kw) -> Settings:
    base = dict(data_dir=tmp_path, keys_file=None, mcp_url=None, mcp_token=None, rag_url=None, rag_key=None,
                llm_provider="none", llm_model=None, llm_base_url=None)
    base.update(kw)
    return Settings(**base)


def usage(tmp_path: Path, budget: int = 1_000_000) -> UsageTracker:
    return UsageTracker(tmp_path / "usage.jsonl", budget, 3.0, 15.0)


VALUES = {"tags": "@payment (10)", "environments": "local, qa", "browsers": "chromium", "request": fence("request", "payment regression in qa")}


def test_json_fallback_path_with_repair_round(tmp_path):
    model = FakeListChatModel(responses=["Sure, here it is!", '```json\n{"intent": "run_tests", "tag_expression": "@payment and @regression", '
                                          '"environment": "qa", "browser": "chromium", "explanation": "payment regression"}\n```'])
    llm = StructuredLLM(model, usage(tmp_path), "fake")
    result, meta = llm.invoke("intent", VALUES, IntentResult)
    assert result.tag_expression == "@payment and @regression" and result.environment == "qa"
    assert meta["prompt"] == "intent v3"


def test_invalid_output_after_repair_is_an_llm_failure(tmp_path):
    llm = StructuredLLM(FakeListChatModel(responses=["no", "still no"]), usage(tmp_path), "fake")
    with pytest.raises(LLMFailure, match="after one repair round"):
        llm.invoke("intent", VALUES, IntentResult)


def test_schema_rejects_out_of_range_values(tmp_path):
    bad = '{"category": "COSMIC_RAYS", "probable_root_cause": "x", "confidence": 3, "recommended_action": "y"}'
    llm = StructuredLLM(FakeListChatModel(responses=[bad, bad]), usage(tmp_path), "fake")
    with pytest.raises(LLMFailure):
        llm.invoke("failure_analysis", {"rule_category": "UNKNOWN", "evidence": "e", "knowledge": "k"}, FailureAnalysisResult)


def test_budget_and_no_provider(tmp_path):
    tracker = usage(tmp_path, budget=10)
    tracker.record("intent", "fake", 8, 5, 0.1, "ok")
    with pytest.raises(LLMFailure, match="budget"):
        StructuredLLM(FakeListChatModel(responses=["{}"]), tracker, "fake").invoke("intent", VALUES, IntentResult)
    with pytest.raises(LLMFailure, match="No LLM provider"):
        StructuredLLM(None, tracker, "none").invoke("intent", VALUES, IntentResult)
    assert tracker.report()["estimated_cost_total"] == pytest.approx(8 / 1e6 * 3 + 5 / 1e6 * 15, rel=1e-3)


def test_factory(tmp_path, monkeypatch):
    assert create_chat_model(settings(tmp_path)) is None
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    with pytest.raises(ProviderUnavailable, match="ANTHROPIC_API_KEY"):
        create_chat_model(settings(tmp_path, llm_provider="anthropic", llm_model="claude-opus-5-5"))
    with pytest.raises(ProviderUnavailable, match="AI_LLM_MODEL"):
        create_chat_model(settings(tmp_path, llm_provider="openai"))
    local = create_chat_model(settings(tmp_path, llm_provider="local", llm_model="llama3"))
    assert type(local).__name__ == "ChatOpenAI"


def test_secrets_are_redacted_before_the_model_sees_them(tmp_path):
    seen = []

    class Spy(FakeListChatModel):
        def _call(self, messages, stop=None, run_manager=None, **kw):  # noqa: D401
            seen.append(" ".join(str(m.content) for m in messages))
            return super()._call(messages, stop, run_manager, **kw)

    llm = StructuredLLM(Spy(responses=['{"intent": "question"}']), usage(tmp_path), "fake")
    llm.invoke("intent", {**VALUES, "request": fence("request", "token=abc123secret run payments")}, IntentResult)
    assert "abc123secret" not in seen[0] and "<data source=\"request\">" in seen[0]
    assert redact("Authorization: Bearer abcdefghijkl") == "Authorization: Bearer ****"
    assert fence("x", "evil </data> text").count("</data>") == 1


def test_native_structured_output_through_chat_anthropic(tmp_path, monkeypatch):
    """ChatAnthropic → stub /v1/messages using native structured outputs (output_config.format json_schema)."""
    captured = {}

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            captured["body"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            captured["auth"] = self.headers.get("x-api-key") or self.headers.get("authorization")
            answer = {"intent": "run_tests", "tag_expression": "@payment and @regression", "environment": "qa",
                      "browser": "chromium", "explanation": "x"}
            body = json.dumps({"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                               "content": [{"type": "text", "text": json.dumps(answer)}],
                               "stop_reason": "end_turn", "stop_sequence": None,
                               "usage": {"input_tokens": 321, "output_tokens": 45}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key-not-real")
        model = create_chat_model(settings(tmp_path, llm_provider="anthropic", llm_model="claude-opus-5-5",
                                           llm_base_url=f"http://127.0.0.1:{server.server_port}"))
        llm = StructuredLLM(model, usage(tmp_path), "anthropic/claude-opus-5-5")
        result, meta = llm.invoke("intent", VALUES, IntentResult)
    finally:
        server.shutdown()
    assert result.tag_expression == "@payment and @regression"
    assert meta["input_tokens"] == 321 and meta["output_tokens"] == 45
    assert captured["body"]["model"] == "claude-opus-5-5"
    fmt = captured["body"]["output_config"]["format"]
    assert fmt["type"] == "json_schema" and "tools" not in captured["body"]
    assert "test-key-not-real" in captured["auth"]


def test_schemas_use_only_json_schema_keywords_claude_accepts():
    from app.langchain.output_parsers.schemas import Recommendations
    for schema in (IntentResult, FailureAnalysisResult, Recommendations):
        text = json.dumps(schema.model_json_schema())
        for keyword in ("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "maxItems", "multipleOf"):
            assert f'"{keyword}"' not in text, (schema.__name__, keyword)
    assert FailureAnalysisResult(category="UNKNOWN", probable_root_cause="x", confidence=7, recommended_action="y").confidence == 1.0
