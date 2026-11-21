"""Persistence: LangGraph checkpoints (SQLite, so paused workflows survive restarts) and a small
index of workflows (who started what, for listing and access checks)."""
from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path


class WorkflowIndex:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.execute("CREATE TABLE IF NOT EXISTS workflows (workflow_id TEXT PRIMARY KEY, requested_by TEXT, request TEXT, created REAL)")
        self._db.commit()
        self._lock = threading.Lock()

    def add(self, workflow_id: str, requested_by: str, request: str) -> None:
        with self._lock:
            self._db.execute("INSERT INTO workflows VALUES (?,?,?,?)", (workflow_id, requested_by, request[:500], time.time()))
            self._db.commit()

    def get(self, workflow_id: str) -> dict | None:
        row = self._db.execute("SELECT workflow_id, requested_by, request, created FROM workflows WHERE workflow_id=?", (workflow_id,)).fetchone()
        return dict(zip(("workflow_id", "requested_by", "request", "created"), row)) if row else None
