"""Nodes 1-3: user request, intent classification (LangChain, keyword fallback), request validation (deterministic)."""
from __future__ import annotations

import re
from datetime import datetime, timezone

from ...langchain.output_parsers.schemas import IntentResult
from ...langchain.structured import LLMFailure
from ...mcp.client import McpError
from ...security.policy import BROWSERS
from ...security.redaction import fence
from ..state import WorkflowState
from .deps import Deps

TAG_TOKEN = re.compile(r"and|or|not|@[A-Za-z0-9_-]+")
SYNONYMS = {"payments?|checkout|card": "@payment", "users?|user management|customers?": "@user",
            "log ?in|sign ?in|sign ?out|authentication": "@login", "end[- ]to[- ]end|e2e|journeys?": "@e2e",
            "ui|browser|front[- ]?end": "@ui", "api|backend": "@api", "smoke": "@smoke", "regression": "@regression",
            "critical": "@critical", "negative|error cases?": "@negative", "positive|happy[- ]path": "@positive"}
GROUPS = [{"@smoke", "@regression", "@sanity", "@critical"}, {"@ui", "@api", "@e2e"}, {"@positive", "@negative"}]


async def user_request(state: WorkflowState, deps: Deps) -> dict:
    return {"received_at": datetime.now(timezone.utc).isoformat(), "trail": ["user_request"]}


async def intent_classification(state: WorkflowState, deps: Deps) -> dict:
    try:
        tags = {t["tag"]: t["testCases"] for t in await deps.qa.tags()}
    except McpError as e:
        return {"outcome": "ERROR", "errors": [f"MCP unavailable during discovery: {e.message}"], "trail": ["intent_classification"]}
    update: dict = {"available_tags": tags, "trail": ["intent_classification"]}
    if deps.llm.enabled:
        try:
            result, meta = await deps.llm.ainvoke("intent", {
                "tags": ", ".join(f"{t} ({n})" for t, n in sorted(tags.items())),
                "environments": ", ".join(deps.settings.allowed_environments),
                "browsers": ", ".join(BROWSERS),
                "request": fence("user request", state["request"], 2000)}, IntentResult)
            intent = {**result.model_dump(), "source": "llm"}
            browser, env, notes = named_browser(state["request"]), named_environment(state["request"]), []
            if intent.get("browser") != browser:
                notes.append(f"browser: the model chose '{intent.get('browser')}' but the request names "
                             f"{'no browser' if browser == DEFAULT_BROWSER else repr(browser)}; using '{browser}'")
                intent["browser"] = browser
            if intent.get("environment") != env:
                notes.append(f"environment: the model chose '{intent.get('environment')}' but the request names "
                             f"{'no environment' if env == DEFAULT_ENVIRONMENT else repr(env)}; using '{env}'")
                intent["environment"] = env
            if notes:
                update["fallbacks"] = notes
            update["intent"] = intent
            update["llm_calls"] = [{"node": "intent_classification", **meta}]
            return update
        except LLMFailure as e:
            update["fallbacks"] = [f"intent: model unavailable ({e}); keyword rules used"]
    else:
        update["fallbacks"] = ["intent: no LLM configured; keyword rules used"]
    update["intent"] = {**rules(state["request"], tags).model_dump(), "source": "rules"}
    return update


DEFAULT_BROWSER = "chromium"


def named_browser(request: str) -> str:
    """The browser the person named, or the default. Deterministic: a model never picks a browser nobody asked for."""
    found = re.search(r"\b(chromium|chrome|firefox|webkit|safari|edge|msedge)\b", request.lower())
    browser = found.group(1) if found else DEFAULT_BROWSER
    return {"safari": "webkit", "edge": "msedge"}.get(browser, browser)


DEFAULT_ENVIRONMENT = "local"


def named_environment(request: str) -> str:
    """The environment the person named, or the default. Like the browser, never chosen by a model."""
    found = re.search(r"\b(prod|production|live|local|qa|staging)\b", request.lower())
    env = found.group(1) if found else DEFAULT_ENVIRONMENT
    return "production" if env in ("prod", "live") else env


def rules(request: str, tags: dict) -> IntentResult:
    text = f" {request.lower()} "
    if re.search(r"\b(prod|production|live)\b", text):
        return IntentResult(intent="run_tests", environment="production", clarification_question="Production is never a test target. Which test environment?")
    env = named_environment(text)
    browser = named_browser(text)
    if not re.search(r"\b(run|execute|test|tests|check|regression|smoke|verify)\b", text):
        return IntentResult(intent="unsupported", environment=env, browser=browser, explanation="Not a request to run tests")
    quoted = re.search(r"\"([^\"]{3,200})\"", request)
    if quoted:
        return IntentResult(intent="run_tests", scenario_name=quoted.group(1), environment=env, browser=browser, explanation="Named scenario")
    if re.search(r"\b(full|complete|entire|whole) (regression|suite)\b|\ball (the )?(tests|scenarios)\b|\beverything\b", text) \
            and not any(re.search(rf"\b({rx})\b", text) for rx in SYNONYMS if SYNONYMS[rx] not in ("@regression", "@smoke")):
        return IntentResult(intent="run_tests", full_regression=True, environment=env, browser=browser, explanation="Full regression")
    found = [t for t in tags if re.search(rf"\b{re.escape(t[1:])}s?\b", text)]
    found += [t for rx, t in SYNONYMS.items() if t in tags and re.search(rf"\b({rx})\b", text) and t not in found]
    if not found:
        return IntentResult(intent="run_tests", environment=env, browser=browser,
                            clarification_question=f"Which tests? Name a tag ({', '.join(sorted(tags))}) or say 'full regression'.")
    grouped: dict[int, list[str]] = {}
    for t in found:
        grouped.setdefault(next((i for i, g in enumerate(GROUPS) if t in g), 3), []).append(t)
    expr = " and ".join(g[0] if len(g) == 1 else "(" + " or ".join(g) + ")" for _, g in sorted(grouped.items()))
    return IntentResult(intent="run_tests", tag_expression=expr, environment=env, browser=browser, explanation=f"Matched words to {found}")


async def request_validation(state: WorkflowState, deps: Deps) -> dict:
    intent, tags, errors = state.get("intent", {}), state.get("available_tags", {}), []
    if intent.get("environment") in ("prod", "production", "live"):
        return {"outcome": "INVALID", "validation_errors": ["Production is never a test target"], "trail": ["request_validation"]}
    if intent.get("clarification_question"):
        return {"outcome": "NEEDS_CLARIFICATION", "validation_errors": [intent["clarification_question"]], "trail": ["request_validation"]}
    if intent.get("intent") != "run_tests":
        return {"outcome": "UNSUPPORTED", "validation_errors": ["Only requests to run tests are supported by this workflow; "
                                                               "use /ai/failure-analysis for a finished run"], "trail": ["request_validation"]}
    if intent.get("environment") not in deps.settings.allowed_environments:
        errors.append(f"Environment '{intent.get('environment')}' is not allowed (allowed: {list(deps.settings.allowed_environments)})")
    if intent.get("browser") not in BROWSERS:
        errors.append(f"Browser '{intent.get('browser')}' is not supported ({list(BROWSERS)})")
    expr = intent.get("tag_expression")
    if expr:
        tokens = expr.replace("(", " ").replace(")", " ").split()
        bad = [t for t in tokens if not TAG_TOKEN.fullmatch(t)]
        unknown = [t for t in tokens if t.startswith("@") and t not in tags]
        if bad:
            errors.append(f"Invalid tag expression tokens: {bad}")
        if unknown:
            errors.append(f"Unknown tags: {unknown} (known: {sorted(tags)})")
    if not (intent.get("full_regression") or expr or intent.get("scenario_name")):
        errors.append("Nothing selected: give tags, a scenario name, or ask for the full regression")
    if errors:
        return {"outcome": "INVALID", "validation_errors": errors, "trail": ["request_validation"]}
    return {"validation_errors": [], "trail": ["request_validation"]}
