"""Typed calls to the existing Java MCP tools. The graph only uses this interface (tests use a fake).
All execution goes through the Java framework via these tools; nothing here runs tests itself."""
from __future__ import annotations

from typing import Protocol

from .client import McpGateway


class QaPlatform(Protocol):
    async def tags(self) -> list[dict]: ...
    async def scenarios(self, tag_expression: str | None) -> dict: ...
    async def run(self, plan: dict) -> dict: ...
    async def status(self, execution_id: str, wait_seconds: int) -> dict: ...
    async def summary(self, execution_id: str) -> dict: ...
    async def failed_scenarios(self, execution_id: str) -> dict: ...
    async def cucumber_failures(self, execution_id: str) -> dict: ...
    async def allure(self, execution_id: str) -> dict: ...
    async def analyze(self, execution_id: str, scenario: str) -> dict: ...
    async def logs(self, execution_id: str, scenario: str) -> dict: ...
    async def history(self, limit: int) -> dict: ...
    async def scenario(self, name: str) -> dict: ...


class McpQaPlatform:
    def __init__(self, gateway: McpGateway, monitor_poll_s: int) -> None:
        self.mcp, self.poll = gateway, monitor_poll_s

    async def tags(self) -> list[dict]:
        return (await self.mcp.call("list_cucumber_tags"))["tags"]

    async def scenarios(self, tag_expression: str | None) -> dict:
        args = {"limit": 50}
        if tag_expression:
            args["tag_expression"] = tag_expression
        return await self.mcp.call("list_scenarios", args)

    async def run(self, plan: dict) -> dict:
        common = {"environment": plan["environment"], "browser": plan["browser"], "threads": plan.get("threads", 4)}
        tool = plan["tool"]
        if tool == "run_tests_by_tag":
            return await self.mcp.call(tool, {**common, "tag_expression": plan["tag_expression"]})
        if tool == "run_scenario_by_name":
            return await self.mcp.call(tool, {**common, "name": plan["scenario_name"]})
        return await self.mcp.call(tool, common)  # run_regression_tests / run_smoke_tests / ...

    async def status(self, execution_id: str, wait_seconds: int) -> dict:
        return await self.mcp.call("get_execution_status", {"execution_id": execution_id, "wait_seconds": min(60, wait_seconds)},
                                   timeout_s=wait_seconds + 30)

    async def summary(self, execution_id: str) -> dict:
        return await self.mcp.call("get_execution_summary", {"execution_id": execution_id})

    async def failed_scenarios(self, execution_id: str) -> dict:
        return await self.mcp.call("get_failed_scenarios", {"execution_id": execution_id})

    async def cucumber_failures(self, execution_id: str) -> dict:
        return await self.mcp.call("get_cucumber_report", {"execution_id": execution_id, "status": "failed"})

    async def allure(self, execution_id: str) -> dict:
        return await self.mcp.call("get_allure_report", {"execution_id": execution_id})

    async def analyze(self, execution_id: str, scenario: str) -> dict:
        # use_ai=false: deterministic evidence + rule category from the Java side; the model step happens once, here in LangChain.
        return await self.mcp.call("analyze_scenario_failure", {"execution_id": execution_id, "scenario_name": scenario, "use_ai": False})

    async def logs(self, execution_id: str, scenario: str) -> dict:
        return await self.mcp.call("get_scenario_logs", {"execution_id": execution_id, "scenario_name": scenario, "max_lines": 40})

    async def history(self, limit: int) -> dict:
        return await self.mcp.call("get_execution_history", {"limit": limit})

    async def scenario(self, name: str) -> dict:
        """One scenario by exact name (no list limit), from get_scenario_details."""
        return await self.mcp.call("get_scenario_details", {"name": name})
