"""Bearer keys (SHA-256 hashes only in the keys file) and roles.

Roles, lowest first: viewer (read workflow state), operator (start workflows, failure analysis),
approver (approve or reject executions; also operator). File format:
  [{"name": "qa-lead", "role": "approver", "sha256": "<hex>"}]
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
from dataclasses import dataclass
from pathlib import Path

ROLES = ("viewer", "operator", "approver")


@dataclass(frozen=True)
class Principal:
    name: str
    role: str

    def can(self, needed: str) -> bool:
        return ROLES.index(self.role) >= ROLES.index(needed)


class KeyStore:
    def __init__(self, entries: list[tuple[bytes, Principal]]):
        self._entries = entries

    @staticmethod
    def load(path: Path | None) -> "KeyStore":
        if path is None or not path.is_file():
            return KeyStore([])
        entries = []
        for item in json.loads(path.read_text(encoding="utf-8")):
            digest, role = item.get("sha256", ""), item.get("role", "viewer")
            if not re.fullmatch(r"[0-9a-fA-F]{64}", digest) or role not in ROLES:
                raise ValueError(f"Invalid key entry in {path}: need a 64-hex sha256 and a role in {ROLES}")
            entries.append((bytes.fromhex(digest), Principal(item["name"], role)))
        return KeyStore(entries)

    def __len__(self) -> int:
        return len(self._entries)

    def authenticate(self, token: str | None) -> Principal | None:
        if not token or not 20 <= len(token) <= 200:
            return None
        presented = hashlib.sha256(token.encode()).digest()
        match = None
        for digest, principal in self._entries:
            if hmac.compare_digest(digest, presented):
                match = principal
        return match


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()
