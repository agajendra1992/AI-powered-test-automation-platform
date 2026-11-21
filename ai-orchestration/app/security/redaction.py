"""Masks secrets and personal data in anything the service returns, logs or sends to a model.
Same rules as the RAG service and the MCP server (kept in sync; see tests)."""
from __future__ import annotations

import re

_RULES = [
    (re.compile(r"(?i)(authorization\s*[:=]\s*\"?)(bearer|basic)\s+[A-Za-z0-9._~+/=-]+"), r"\1\2 ****"),
    (re.compile(r"(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]{8,}"), r"\1 ****"),
    (re.compile(r"\b(sk-[A-Za-z0-9_-]{12,}|sk-ant-[A-Za-z0-9_-]{12,}|qamcp_[A-Za-z0-9_-]{20,}|rag_[A-Za-z0-9_-]{20,}|aio_[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16})"), "****key****"),
    (re.compile(r"(?i)(\"(?:password|passwd|pwd|secret|client_secret|clientSecret|token|access_token|accessToken|refresh_token|"
                r"api[_-]?key|cvv|cardNumber|card_number)\"\s*:\s*\")[^\"]*(\")"), r"\1****\2"),
    (re.compile(r"(?i)\b((?:password|passwd|pwd|secret|client_secret|token|access_token|api[_-]?key|cvv)\s*[=:]\s*)[^\s,;&\"']+"), r"\1****"),
]
_CARD = re.compile(r"(?<![\d-])(?:\d{13,19}|\d{4}([ -])\d{4}\1\d{4}\1\d{1,7})(?![\d-])")
_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
_SAFE_EMAIL = ("example.com", "example.org", "example.net")


def _luhn(digits: str) -> bool:
    total, double = 0, False
    for ch in reversed(digits):
        d = int(ch)
        if double:
            d = d * 2 - 9 if d * 2 > 9 else d * 2
        total += d
        double = not double
    return total % 10 == 0


def redact(text: str) -> str:
    if not text:
        return text
    for pattern, repl in _RULES:
        text = pattern.sub(repl, text)
    text = _CARD.sub(lambda m: "****card****" if _luhn(re.sub(r"[ -]", "", m.group(0))) else m.group(0), text)
    return _EMAIL.sub(lambda m: m.group(0) if m.group(0).lower().endswith(_SAFE_EMAIL) else "****email****", text)


def redact_tree(value):
    """Redacts every string inside nested dicts/lists (numbers and keys untouched)."""
    if isinstance(value, str):
        return redact(value)
    if isinstance(value, dict):
        return {k: redact_tree(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact_tree(v) for v in value]
    return value


def fence(source: str, text: str, max_chars: int = 6000) -> str:
    """Untrusted text for a prompt: redacted, truncated, wrapped in <data> with closing tags neutralised."""
    t = redact(text or "")
    if len(t) > max_chars:
        t = t[:max_chars] + f"\n[... truncated {len(t) - max_chars} characters]"
    t = re.sub(r"(?i)</\s*data\s*>", "</ data>", t)
    t = re.sub(r"(?i)<\s*data(\s|>)", r"< data\1", t)
    src = re.sub(r"[^A-Za-z0-9 ._:/#\[\]-]", "_", source)
    return f'<data source="{src}">\n{t}\n</data>'
