"""Dependencies shared by the nodes (injected, so tests can use fakes)."""
from __future__ import annotations

from dataclasses import dataclass

from ...config import Settings
from ...langchain.retrievers.rag_retriever import RagRetriever
from ...langchain.structured import StructuredLLM
from ...mcp.adapters import QaPlatform
from ...observability.telemetry import Telemetry


@dataclass
class Deps:
    settings: Settings
    qa: QaPlatform
    llm: StructuredLLM
    retriever: RagRetriever | None
    telemetry: Telemetry
