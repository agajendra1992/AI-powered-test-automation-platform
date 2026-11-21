"""Pydantic schemas for every structured model output. Values are validated again against MCP facts.

Claude's native structured outputs reject numeric bounds, string lengths and maxItems > 1 in the JSON
schema, so these schemas carry no such keywords; the same rules are enforced by validators after
parsing (identical behaviour for every provider)."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

FailureCategory = Literal["APPLICATION_DEFECT", "AUTOMATION_DEFECT", "LOCATOR_FAILURE", "TIMING_SYNCHRONIZATION",
                          "ENVIRONMENT_FAILURE", "TEST_DATA_ISSUE", "API_FAILURE", "NETWORK_FAILURE",
                          "AUTHENTICATION_FAILURE", "INFRASTRUCTURE_FAILURE", "UNKNOWN"]


class IntentResult(BaseModel):
    """How a person's request maps onto the test suite."""
    intent: Literal["run_tests", "analyze_failure", "question", "unsupported"]
    full_regression: bool = Field(False, description="True only if the person asks for everything / the full regression")
    tag_expression: str | None = Field(None, description="Cucumber tag expression using ONLY the listed tags, e.g. '@payment and @regression'")
    scenario_name: str | None = Field(None, description="Exact name of one scenario, only if the person names it")
    environment: str = Field("local", description="One of the listed environments")
    browser: str = Field("chromium", description="One of the listed browsers")
    clarification_question: str | None = Field(None, description="Set when the request is too vague or unsafe")
    explanation: str = Field("", description="One sentence: what will run and why")


class FailureAnalysisResult(BaseModel):
    """An unconfirmed hypothesis about one failed scenario."""
    category: FailureCategory
    probable_root_cause: str
    supporting_evidence: list[str] = Field(default_factory=list, description="Short exact quotes copied from the evidence")
    confidence: float = Field(description="0.0 to 1.0")
    recommended_action: str

    @field_validator("confidence")
    @classmethod
    def clamp(cls, v: float) -> float:
        return max(0.0, min(1.0, v))


class Recommendation(BaseModel):
    title: str
    action: str
    rationale: str
    references: list[str] = Field(default_factory=list, description="Ids of the facts this is based on, e.g. 'failure:1', 'kb:2'")


class Recommendations(BaseModel):
    items: list[Recommendation] = Field(default_factory=list, description="At most 6 items")

    @field_validator("items")
    @classmethod
    def at_most_six(cls, v: list[Recommendation]) -> list[Recommendation]:
        return v[:6]
