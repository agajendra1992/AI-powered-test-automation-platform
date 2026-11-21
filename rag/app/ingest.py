"""Ingestion: resolves paths inside the allowed roots, extracts text, redacts, chunks and stores."""
from __future__ import annotations

from pathlib import Path

from .chunking import chunk_document, detect_type, pdf_text
from .config import Settings
from .security import ACCESS_LEVELS, injection_signals, redact
from .store import Store, content_hash, document_id

INGESTIBLE = {".feature", ".java", ".md", ".markdown", ".json", ".log", ".txt", ".xml", ".pdf", ".yml", ".yaml"}
SKIP_DIRS = {".git", ".venv", "node_modules", ".rag-data", ".qa-mcp", ".allure", "__pycache__", "classes", "test-classes",
             "generated-sources", "generated-test-sources", "maven-status", "surefire-reports"}


class IngestError(ValueError):
    pass


def resolve(settings: Settings, raw: str) -> Path:
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = settings.allowed_roots[0] / path
    path = path.resolve()
    if not any(path == root or root in path.parents for root in settings.allowed_roots):
        raise IngestError(f"Path is outside the allowed roots (RAG_ALLOWED_ROOTS): {raw}")
    if not path.exists():
        raise IngestError(f"Not found: {raw}")
    return path


def files_under(path: Path, limit: int = 5000) -> list[Path]:
    if path.is_file():
        return [path]
    found = []
    for p in sorted(path.rglob("*")):
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        if p.is_file() and p.suffix.lower() in INGESTIBLE and "-attachment" not in p.name:
            found.append(p)
            if len(found) >= limit:
                break
    return found


def ingest_text(store: Store, settings: Settings, source: str, text: str, doc_type: str | None, metadata: dict,
                access_level: str, actor: str) -> dict:
    if access_level not in ACCESS_LEVELS:
        raise IngestError(f"access_level must be one of {ACCESS_LEVELS}")
    if len(text.encode()) > settings.max_document_bytes:
        raise IngestError(f"Document larger than {settings.max_document_bytes} bytes: {source}")
    clean = redact(text)
    kind = doc_type or detect_type(source, clean)
    base = {k: str(v) for k, v in (metadata or {}).items() if isinstance(v, (str, int, float, bool))}
    chunks = chunk_document(source, clean, kind, base, settings.max_chunk_chars)
    result = store.upsert(document_id(source), source, chunks[0].metadata["document_type"] if chunks else kind,
                          content_hash(clean + "|" + kind + "|" + str(sorted(base.items()))), access_level, chunks, base, actor)
    result["source"] = source
    result["redactions"] = text != clean
    signals = injection_signals(clean)
    if signals:
        result["injection_signals"] = signals
    return result


def ingest_path(store: Store, settings: Settings, raw: str, doc_type: str | None, metadata: dict, access_level: str, actor: str) -> list[dict]:
    root = resolve(settings, raw)
    base_root = next(r for r in settings.allowed_roots if root == r or r in root.parents)
    results = []
    for f in files_under(root):
        rel = str(f.relative_to(base_root))
        try:
            text = pdf_text(f.read_bytes()) if f.suffix.lower() == ".pdf" else f.read_text(encoding="utf-8", errors="replace")
            results.append(ingest_text(store, settings, rel, text, doc_type, metadata, access_level, actor))
        except IngestError as e:
            results.append({"source": rel, "status": "skipped", "reason": str(e)})
    return results
