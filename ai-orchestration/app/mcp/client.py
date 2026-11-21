"""MCP client for the Java MCP server (streamable HTTP, bearer token), built on langchain-mcp-adapters:
the server's tools become LangChain tools, invoked with typed arguments.

Timeouts on every call; controlled retries only for read-only tools. Tools that start, approve,
cancel or change anything are never retried (a retried run_* call could start a second execution)."""
from __future__ import annotations

import asyncio
import json
import logging

from langchain_core.tools import BaseTool
from langchain_mcp_adapters.client import MultiServerMCPClient

log = logging.getLogger("ai-orchestration.mcp")
MUTATING_PREFIXES = ("run_", "execute_", "approve_", "reject_", "cancel_", "accept_", "import_", "ingest_", "generate_")


class McpError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code, self.message = code, message


class McpGateway:
    def __init__(self, url: str, token: str | None, timeout_s: int, retries: int = 2) -> None:
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        self._client = MultiServerMCPClient({"qa": {"transport": "streamable_http", "url": url, "headers": headers}})
        self._timeout, self._retries = timeout_s, retries
        self._tools: dict[str, BaseTool] | None = None
        self._lock = asyncio.Lock()

    async def tools(self) -> dict[str, BaseTool]:
        async with self._lock:
            if self._tools is None:
                self._tools = {t.name: t for t in await self._client.get_tools()}
        return self._tools

    async def call(self, name: str, args: dict | None = None, timeout_s: int | None = None) -> dict:
        tools = await self.tools()
        if name not in tools:
            raise McpError("unknown_tool", f"The MCP server has no tool '{name}'")
        attempts = 1 if name.startswith(MUTATING_PREFIXES) else 1 + self._retries
        last: Exception | None = None
        for attempt in range(attempts):
            try:
                msg = await asyncio.wait_for(
                    tools[name].ainvoke({"type": "tool_call", "id": f"{name}-{attempt}", "name": name, "args": args or {}}),
                    timeout=timeout_s or self._timeout)
                return _result(name, msg)
            except McpError:
                raise
            except (asyncio.TimeoutError, OSError, ConnectionError) as e:  # transient: retry read-only tools only
                last = e
                log.warning("MCP %s attempt %d failed: %s", name, attempt + 1, type(e).__name__)
                if attempt + 1 < attempts:
                    await asyncio.sleep(min(2 ** attempt, 5))
            except Exception as e:  # noqa: BLE001 - transport/adapter errors
                last = e
                if attempt + 1 < attempts:
                    await asyncio.sleep(min(2 ** attempt, 5))
        raise McpError("unavailable", f"MCP tool {name} failed after {attempts} attempt(s): {type(last).__name__}: {last}")


def _result(name: str, msg) -> dict:
    """ToolMessage → dict. Our server returns JSON text (and structuredContent); errors carry {"error", "message"}."""
    artifact = getattr(msg, "artifact", None)
    structured = artifact.get("structured_content") if isinstance(artifact, dict) else getattr(artifact, "structured_content", None)
    content = msg.content
    text = content if isinstance(content, str) else "".join(
        b.get("text", "") for b in content if isinstance(b, dict)) if isinstance(content, list) else str(content)
    data = structured
    if data is None:
        try:
            data = json.loads(text)
        except (json.JSONDecodeError, TypeError):
            data = {"text": text}
    if getattr(msg, "status", "success") == "error" or (isinstance(data, dict) and "error" in data and "message" in data):
        code = data.get("error", "tool_error") if isinstance(data, dict) else "tool_error"
        message = data.get("message", text) if isinstance(data, dict) else text
        raise McpError(str(code), str(message))
    return data
