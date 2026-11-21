"""Phase 5b integration: real Java MCP server (HTTP + token) through langchain-mcp-adapters, and the real
RAG service through the LangChain retriever. Skipped unless AI_IT_MCP_URL / AI_IT_RAG_URL are set:

  AI_IT_MCP_URL=http://127.0.0.1:8766/mcp AI_IT_MCP_TOKEN_FILE=... AI_IT_RAG_URL=http://127.0.0.1:8090 AI_IT_RAG_KEY_FILE=... pytest tests
"""
from __future__ import annotations

import asyncio
import os
from pathlib import Path

import pytest

from app.langchain.retrievers.rag_retriever import RagRetriever, RagUnavailable
from app.mcp.adapters import McpQaPlatform
from app.mcp.client import McpError, McpGateway

MCP_URL = os.environ.get("AI_IT_MCP_URL")
RAG_URL = os.environ.get("AI_IT_RAG_URL")


def _read(var: str) -> str | None:
    path = os.environ.get(var)
    return Path(path).read_text().strip() if path else None


@pytest.mark.skipif(not MCP_URL, reason="AI_IT_MCP_URL not set")
def test_existing_mcp_tools_are_loaded_as_langchain_tools_and_called():
    async def run():
        gateway = McpGateway(MCP_URL, _read("AI_IT_MCP_TOKEN_FILE"), timeout_s=60)
        tools = await gateway.tools()
        for name in ("list_feature_files", "list_scenarios", "get_scenario_details", "list_cucumber_tags", "run_smoke_tests",
                     "run_regression_tests", "run_tests_by_tag", "get_execution_status", "get_execution_summary",
                     "get_failed_scenarios", "get_scenario_logs", "get_allure_report"):
            assert name in tools, name
        assert type(tools["list_scenarios"]).__name__ == "StructuredTool"
        qa = McpQaPlatform(gateway, 60)
        tags = {t["tag"]: t["testCases"] for t in await qa.tags()}
        assert tags["@payment"] == 12  # all @payment test cases (UI + API); 10 of them are @regression
        payment = await qa.scenarios("@payment and @regression and not @quarantine")
        assert payment["testCases"] == 10
        with pytest.raises(McpError) as invalid:
            await gateway.call("run_tests_by_tag", {"tag_expression": "@api; rm -rf /"})
        assert invalid.value.code == "invalid_argument"
        with pytest.raises(McpError) as unknown:
            await gateway.call("format_disk", {})
        assert unknown.value.code == "unknown_tool"
    asyncio.run(run())


@pytest.mark.skipif(not MCP_URL, reason="AI_IT_MCP_URL not set")
def test_wrong_token_is_rejected_by_the_mcp_server():
    async def run():
        gateway = McpGateway(MCP_URL, "qamcp_" + "x" * 40, timeout_s=20)
        with pytest.raises(Exception):
            await gateway.tools()
    asyncio.run(run())


@pytest.mark.skipif(not RAG_URL, reason="AI_IT_RAG_URL not set")
def test_rag_retriever_returns_cited_documents():
    retriever = RagRetriever(url=RAG_URL, key=_read("AI_IT_RAG_KEY_FILE"), k=3, filters={"module": "payment"})
    docs = retriever.invoke("refund more than the captured amount")
    assert docs and all(d.metadata["module"] == "payment" for d in docs)
    assert docs[0].metadata["citation"].startswith("[1] ")
    assert "Refunding more than the captured amount" in docs[0].metadata.get("scenario", "") + docs[0].page_content
    adocs = asyncio.run(retriever.ainvoke("declined card"))
    assert adocs


def test_rag_retriever_reports_unavailability():
    with pytest.raises(RagUnavailable):
        RagRetriever(url="http://127.0.0.1:9", timeout_s=2).invoke("anything")
