"""The evaluation harness itself: deterministic baseline holds, and the scorer catches bad model output."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

from langchain_core.language_models.fake_chat_models import FakeListChatModel

from app.config import Settings
from evals import run_eval


def settings(tmp_path: Path) -> Settings:
    return Settings(data_dir=tmp_path, keys_file=None, mcp_url=None, mcp_token=None, rag_url=None, rag_key=None,
                    llm_provider="none", llm_model="fake", llm_base_url=None)


def test_rules_baseline_meets_every_measured_threshold(tmp_path):
    report = asyncio.run(run_eval.evaluate(run_eval.build_deps(settings(tmp_path))))
    assert report["metrics"]["safety"]["rate"] == 1.0
    assert run_eval.gate(report)


def scripted_model(intent_answer: dict, analysis_answer: dict) -> FakeListChatModel:
    intents = len(run_eval.load("intent.jsonl"))
    analyses = len(run_eval.load("failure_analysis.jsonl"))
    return FakeListChatModel(responses=[json.dumps(intent_answer)] * intents + [json.dumps(analysis_answer)] * analyses)


def test_scorer_catches_unsafe_and_ungrounded_model_output(tmp_path):
    # A model that always selects everything (quarantine included), claims tests passed and invents evidence.
    bad_intent = {"intent": "run_tests", "tag_expression": "@payment or @quarantine", "environment": "local", "browser": "chromium",
                  "explanation": "everything"}
    bad_analysis = {"category": "UNKNOWN", "probable_root_cause": "The test actually passed; no action needed.",
                    "supporting_evidence": ["this text is not in the evidence"], "confidence": 1.0, "recommended_action": "none"}
    deps = run_eval.build_deps(settings(tmp_path), model=scripted_model(bad_intent, bad_analysis))
    report = asyncio.run(run_eval.evaluate(deps))
    m = report["metrics"]
    # The validation guardrail stops every quarantine selection before it can run...
    assert all(row["outcome"] != "OK" for row in report["intent"] if "@quarantine" in (row["tag_expression"] or ""))
    # ...and the scorer flags the analysis that claims the failed test passed.
    assert m["safety"]["met"] is False
    assert any("claims_pass=True" in f for f in m["safety"]["failures"])
    assert m["failure_category"]["rate"] == 0.0
    assert m["failure_groundedness"]["rate"] == 0.0
    assert not run_eval.gate(report)
    # Ungrounded answers are capped at 0.3 confidence by the product code, whatever the model said.
    assert all(row["confidence"] <= 0.3 for row in report["failure_analysis"])


def test_scorer_accepts_a_good_model(tmp_path):
    good_analysis = {"category": "LOCATOR_FAILURE", "probable_root_cause": "The element was not found.",
                     "supporting_evidence": [], "confidence": 0.2, "recommended_action": "Update the locator."}
    deps = run_eval.build_deps(settings(tmp_path), model=scripted_model(
        {"intent": "run_tests", "tag_expression": "@payment", "environment": "local", "browser": "chromium"}, good_analysis))
    report = asyncio.run(run_eval.evaluate(deps))
    m = report["metrics"]
    # Analyses: no pass claims, nothing ungrounded quoted, low confidence -> every analysis case is safe.
    assert not any("claims_pass=True" in f or "calibrated=False" in f for f in m["safety"]["failures"])
    # The fixed answer is right for the locator cases only (f02 and f09).
    assert m["failure_category"]["passed"] == 2
