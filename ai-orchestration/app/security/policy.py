"""Deterministic permission and approval rules. No model output is consulted here."""
from __future__ import annotations

from dataclasses import dataclass

from ..config import Settings
from .auth import Principal

BROWSERS = ("chromium", "firefox", "webkit", "chrome", "msedge")


@dataclass(frozen=True)
class Decision:
    allowed: bool
    reason: str


def can_execute(settings: Settings, principal: Principal, environment: str) -> Decision:
    if environment in ("prod", "production", "live"):
        return Decision(False, "Production is never a test target")
    if environment not in settings.allowed_environments:
        return Decision(False, f"Environment '{environment}' is not allowed (allowed: {list(settings.allowed_environments)})")
    if not principal.can("operator"):
        return Decision(False, f"Starting test executions needs the operator role; {principal.name} is {principal.role}")
    return Decision(True, "allowed")


def approval_requirement(settings: Settings, environment: str) -> str:
    """Every execution needs approval from a second person: an approver who is not the requester (all environments)."""
    return "different_approver"


def can_approve(settings: Settings, approver: Principal, requester: str, environment: str) -> Decision:
    if not approver.can("approver"):
        return Decision(False, "Approving an execution needs the approver role")
    if approver.name == requester:
        return Decision(False, "An execution needs approval from someone other than the requester")
    return Decision(True, "approved")
