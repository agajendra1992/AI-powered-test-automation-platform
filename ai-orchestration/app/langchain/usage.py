"""Token usage (from LangChain usage_metadata), daily budget, and a cost estimate only when prices are configured."""
from __future__ import annotations

import json
import threading
import time
from datetime import datetime, timezone
from pathlib import Path


class BudgetExceeded(RuntimeError):
    pass


class UsageTracker:
    def __init__(self, path: Path, daily_budget: int, price_in: float | None, price_out: float | None) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path, self.daily_budget, self.price_in, self.price_out = path, daily_budget, price_in, price_out
        self._lock = threading.Lock()

    def _rows(self) -> list[dict]:
        if not self.path.is_file():
            return []
        return [json.loads(l) for l in self.path.read_text(encoding="utf-8").splitlines() if l.strip()]

    def used_today(self) -> int:
        today = datetime.now(timezone.utc).date().isoformat()
        return sum(r["input_tokens"] + r["output_tokens"] for r in self._rows() if r["time"].startswith(today))

    def check(self) -> None:
        used = self.used_today()
        if used >= self.daily_budget:
            raise BudgetExceeded(f"Daily token budget used ({used} of {self.daily_budget}); resets 00:00 UTC")

    def record(self, purpose: str, model: str, input_tokens: int, output_tokens: int, seconds: float, outcome: str) -> dict:
        cost = None
        if self.price_in is not None and self.price_out is not None:
            cost = round(input_tokens / 1e6 * self.price_in + output_tokens / 1e6 * self.price_out, 6)
        row = {"time": datetime.now(timezone.utc).isoformat(), "purpose": purpose, "model": model, "input_tokens": input_tokens,
               "output_tokens": output_tokens, "seconds": round(seconds, 3), "outcome": outcome, "estimated_cost": cost}
        with self._lock, self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(row) + "\n")
        return row

    def report(self) -> dict:
        rows = self._rows()
        costs = [r["estimated_cost"] for r in rows if r.get("estimated_cost") is not None]
        return {"calls": len(rows), "used_today_tokens": self.used_today(), "daily_budget_tokens": self.daily_budget,
                "estimated_cost_total": round(sum(costs), 6) if costs else None,
                "note": "Costs only when AI_PRICE_INPUT_PER_MTOK / AI_PRICE_OUTPUT_PER_MTOK are configured; no prices are built in."}
