"""Authentication, roles, access levels, redaction and prompt-injection flags.

* API keys: bearer tokens; the keys file stores only SHA-256 hashes
  ([{"name": "mcp-server", "role": "reader", "sha256": "...", "max_access": "internal"}]).
* Roles: reader (search/query), writer (+ ingest, delete), admin (+ everything).
* Access levels on every chunk: public < internal < restricted. A key only sees chunks at or
  below its max_access, so restricted material (e.g. bug reports) can be kept from some clients.
* Ingested text is redacted (secrets, card numbers, e-mail addresses, phone numbers) before it is
  embedded or stored: the index never holds a secret.
* Chunks that look like instructions to an AI are flagged and down-ranked; retrieved text is always
  handed to a model as fenced, untrusted data.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
from dataclasses import dataclass
from pathlib import Path

ROLES = ("reader", "writer", "admin")
ACCESS_LEVELS = ("public", "internal", "restricted")


@dataclass(frozen=True)
class Principal:
    name: str
    role: str
    max_access: str

    def can(self, needed: str) -> bool:
        return ROLES.index(self.role) >= ROLES.index(needed)

    def sees(self, level: str) -> bool:
        return ACCESS_LEVELS.index(self.max_access) >= ACCESS_LEVELS.index(level)

    def visible_levels(self) -> list[str]:
        return [lvl for lvl in ACCESS_LEVELS if self.sees(lvl)]


class KeyStore:
    def __init__(self, entries: list[tuple[bytes, Principal]]):
        self._entries = entries

    @staticmethod
    def load(path: Path | None) -> "KeyStore":
        if path is None or not path.is_file():
            return KeyStore([])
        entries = []
        for item in json.loads(path.read_text(encoding="utf-8")):
            digest = item.get("sha256", "")
            role = item.get("role", "reader")
            access = item.get("max_access", "internal")
            if not re.fullmatch(r"[0-9a-fA-F]{64}", digest) or role not in ROLES or access not in ACCESS_LEVELS:
                raise ValueError(f"Invalid entry in {path}: need sha256 (64 hex), role {ROLES}, max_access {ACCESS_LEVELS}")
            entries.append((bytes.fromhex(digest), Principal(item["name"], role, access)))
        return KeyStore(entries)

    def __len__(self) -> int:
        return len(self._entries)

    def authenticate(self, token: str | None) -> Principal | None:
        if not token or not (20 <= len(token) <= 200):
            return None
        presented = hashlib.sha256(token.encode()).digest()
        match = None
        for digest, principal in self._entries:  # compare all entries: no timing hint
            if hmac.compare_digest(digest, presented):
                match = principal
        return match


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


# ---------------------------------------------------------------- redaction

_RULES = [
    (re.compile(r"(?i)(authorization\s*[:=]\s*\"?)(bearer|basic)\s+[A-Za-z0-9._~+/=-]+"), r"\1\2 ****"),
    (re.compile(r"(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]{8,}"), r"\1 ****"),
    (re.compile(r"\b(sk-[A-Za-z0-9_-]{12,}|sk-ant-[A-Za-z0-9_-]{12,}|qamcp_[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,})"), "****key****"),
    (re.compile(r"(?i)(\"(?:password|passwd|pwd|secret|client_secret|clientSecret|token|access_token|accessToken|refresh_token|"
                r"api[_-]?key|cvv|cardNumber|card_number)\"\s*:\s*\")[^\"]*(\")"), r"\1****\2"),
    (re.compile(r"(?i)\b((?:password|passwd|pwd|secret|client_secret|token|access_token|api[_-]?key|cvv)\s*[=:]\s*)[^\s,;&\"']+"), r"\1****"),
]
_CARD = re.compile(r"(?<![\d-])(?:\d{13,19}|\d{4}([ -])\d{4}\1\d{4}\1\d{1,7})(?![\d-])")
_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
_PHONE = re.compile(r"(?<![\w-])\+?\d{1,3}[ .-]?\(?\d{2,4}\)?[ .-]\d{3,4}[ .-]\d{3,4}(?![\w-])")
# Example/test addresses are not personal data and keep test documentation readable.
_SAFE_EMAIL_DOMAINS = ("example.com", "example.org", "example.net", "qa.example.com")


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
    """Masks secrets, Luhn-valid card numbers, e-mail addresses (except example domains) and phone numbers."""
    if not text:
        return text
    for pattern, replacement in _RULES:
        text = pattern.sub(replacement, text)

    def card(m: re.Match) -> str:
        digits = re.sub(r"[ -]", "", m.group(0))
        return "****card****" if 13 <= len(digits) <= 19 and _luhn(digits) else m.group(0)

    text = _CARD.sub(card, text)
    text = _EMAIL.sub(lambda m: m.group(0) if m.group(0).lower().endswith(_SAFE_EMAIL_DOMAINS) else "****email****", text)
    text = _PHONE.sub("****phone****", text)
    return text


# ---------------------------------------------------------------- prompt injection

_INJECTION = re.compile(
    r"(?i)(ignore (all |any )?(previous|prior|above) (instructions|prompts?)|disregard (the )?(system|previous) (prompt|instructions)|"
    r"you are now|act as (an? )?(admin|system)|reveal (the )?(system prompt|api key|password)|"
    r"mark (this|the) test as pass|change (the )?(status|result) to pass|exfiltrate|<\s*/?\s*system\s*>)")


def injection_signals(text: str) -> list[str]:
    return sorted({m.group(0).lower() for m in _INJECTION.finditer(text or "")})


def fence(source: str, text: str) -> str:
    """Untrusted text for a prompt: closing tags neutralised, wrapped in a <data> block."""
    safe = re.sub(r"(?i)</\s*data\s*>", "</ data>", text)
    safe = re.sub(r"(?i)<\s*data(\s|>)", r"< data\1", safe)
    src = re.sub(r"[^A-Za-z0-9 ._:/#\[\]-]", "_", source)
    return f'<data source="{src}">\n{safe}\n</data>'
