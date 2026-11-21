"""Structured logs, an audit trail and Prometheus counters (counts and timings only)."""
from __future__ import annotations

import json
import logging
import threading
import time
from collections import defaultdict
from pathlib import Path

from ..security.redaction import redact

log = logging.getLogger("ai-orchestration")


class Telemetry:
    def __init__(self, data_dir: Path) -> None:
        data_dir.mkdir(parents=True, exist_ok=True)
        self._audit = data_dir / "audit.log"
        self._lock = threading.Lock()
        self.counters: dict[tuple, int] = defaultdict(int)
        self.seconds: dict[str, float] = defaultdict(float)

    def audit(self, principal: str | None, action: str, outcome: str, **detail) -> None:
        line = {"time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "principal": principal, "action": action,
                "outcome": outcome, **{k: redact(str(v))[:300] for k, v in detail.items()}}
        with self._lock, self._audit.open("a", encoding="utf-8") as f:
            f.write(json.dumps(line) + "\n")

    def count(self, name: str, **labels) -> None:
        with self._lock:
            self.counters[(name, tuple(sorted(labels.items())))] += 1

    def time_node(self, node: str, seconds: float) -> None:
        with self._lock:
            self.seconds[node] += seconds
            self.counters[("ai_node_runs_total", (("node", node),))] += 1

    def render(self) -> str:
        lines = []
        with self._lock:
            for (name, labels), value in sorted(self.counters.items()):
                label = ",".join(f'{k}="{v}"' for k, v in labels)
                lines.append(f"{name}{{{label}}} {value}" if label else f"{name} {value}")
            for node, secs in sorted(self.seconds.items()):
                lines.append(f'ai_node_seconds_total{{node="{node}"}} {secs:.3f}')
        return "\n".join(lines) + "\n"
