"""Persistent knowledge store: ChromaDB (vectors + chunk text + metadata) and a SQLite registry
(documents, versions, chunk hashes) for incremental ingestion, versioning and duplicate detection."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
import time
from pathlib import Path

import chromadb
from chromadb.config import Settings as ChromaSettings

from .bm25 import Bm25Index
from .embeddings import Embedder

_SCHEMA = """
CREATE TABLE IF NOT EXISTS documents (document_id TEXT PRIMARY KEY, source TEXT, document_type TEXT, content_hash TEXT,
  version INTEGER, chunk_count INTEGER, access_level TEXT, metadata TEXT, created_at REAL, updated_at REAL);
CREATE TABLE IF NOT EXISTS history (document_id TEXT, version INTEGER, content_hash TEXT, action TEXT, at REAL, actor TEXT);
CREATE TABLE IF NOT EXISTS chunks (chunk_id TEXT PRIMARY KEY, document_id TEXT, content_hash TEXT);
CREATE INDEX IF NOT EXISTS chunks_hash ON chunks(content_hash);
"""


def document_id(source: str) -> str:
    return "doc-" + hashlib.sha256(source.encode()).hexdigest()[:20]


def content_hash(text: str) -> str:
    return hashlib.sha256(" ".join(text.split()).encode()).hexdigest()


class Store:
    def __init__(self, data_dir: Path, collection: str, embedder: Embedder) -> None:
        data_dir.mkdir(parents=True, exist_ok=True)
        self.embedder = embedder
        self._lock = threading.RLock()
        self._db = sqlite3.connect(data_dir / "registry.db", check_same_thread=False)
        self._db.executescript(_SCHEMA)
        import os

        host = os.environ.get("RAG_CHROMA_HOST")
        if host:
            # ChromaDB server (e.g. the chroma container with a persistent volume)
            self._client = chromadb.HttpClient(host=host, port=int(os.environ.get("RAG_CHROMA_PORT", "8000")),
                                               settings=ChromaSettings(anonymized_telemetry=False))
        else:
            # Embedded ChromaDB in RAG_DATA_DIR (local development, tests)
            self._client = chromadb.PersistentClient(path=str(data_dir / "chroma"), settings=ChromaSettings(anonymized_telemetry=False))
        self.collection = self._client.get_or_create_collection(
            collection, embedding_function=None,
            metadata={"hnsw:space": "cosine", "embedding": f"{embedder.name}/{embedder.model}"})
        built_with = (self.collection.metadata or {}).get("embedding")
        if built_with and built_with != f"{embedder.name}/{embedder.model}":
            raise RuntimeError(f"Index was built with {built_with}; current embedder is {embedder.name}/{embedder.model}. "
                               "Use a new RAG_DATA_DIR or re-ingest.")
        self.bm25 = Bm25Index()
        existing = self.collection.get(include=["documents"])
        for cid, text in zip(existing["ids"], existing["documents"] or []):
            self.bm25.add(cid, text or "")

    # ------------------------------------------------------------ documents

    def get_document(self, doc_id: str) -> dict | None:
        row = self._db.execute("SELECT document_id, source, document_type, content_hash, version, chunk_count, access_level, metadata, "
                               "created_at, updated_at FROM documents WHERE document_id=?", (doc_id,)).fetchone()
        if not row:
            return None
        keys = ["document_id", "source", "document_type", "content_hash", "version", "chunk_count", "access_level", "metadata",
                "created_at", "updated_at"]
        doc = dict(zip(keys, row))
        doc["metadata"] = json.loads(doc["metadata"] or "{}")
        return doc

    def list_documents(self, limit: int = 200) -> list[dict]:
        ids = [r[0] for r in self._db.execute("SELECT document_id FROM documents ORDER BY updated_at DESC LIMIT ?", (limit,))]
        return [self.get_document(i) for i in ids]

    def history(self, doc_id: str) -> list[dict]:
        return [dict(zip(["version", "content_hash", "action", "at", "actor"], r)) for r in self._db.execute(
            "SELECT version, content_hash, action, at, actor FROM history WHERE document_id=? ORDER BY at", (doc_id,))]

    def upsert(self, doc_id: str, source: str, doc_type: str, full_hash: str, access_level: str, chunks: list, doc_meta: dict,
               actor: str) -> dict:
        """Replaces a document's chunks if its content changed. Returns what happened."""
        with self._lock:
            current = self.get_document(doc_id)
            if current and current["content_hash"] == full_hash and current["access_level"] == access_level:
                return {"document_id": doc_id, "status": "unchanged", "version": current["version"], "chunks": current["chunk_count"]}
            version = (current["version"] + 1) if current else 1
            self._delete_chunks(doc_id)
            ids, texts, metas, duplicates = [], [], [], []
            for i, c in enumerate(chunks):
                h = content_hash(c.text)
                dup = self._db.execute("SELECT chunk_id, document_id FROM chunks WHERE content_hash=? AND document_id<>?",
                                       (h, doc_id)).fetchone()
                if dup:
                    duplicates.append({"chunk": i, "duplicate_of_document": dup[1]})
                cid = f"{doc_id}:{version}:{i}"
                meta = {k: v for k, v in {**c.metadata, "document_id": doc_id, "source": source, "version": version,
                                          "access_level": access_level, "chunk_index": i, "content_hash": h}.items()
                        if isinstance(v, (str, int, float, bool)) and v != ""}
                ids.append(cid)
                texts.append(c.text)
                metas.append(meta)
            for start in range(0, len(ids), 64):
                batch = slice(start, start + 64)
                self.collection.add(ids=ids[batch], documents=texts[batch], metadatas=metas[batch],
                                    embeddings=self.embedder.embed(texts[batch]))
            for cid, text, meta in zip(ids, texts, metas):
                self.bm25.add(cid, text)
                self._db.execute("INSERT OR REPLACE INTO chunks VALUES (?,?,?)", (cid, doc_id, meta["content_hash"]))
            now = time.time()
            self._db.execute("INSERT OR REPLACE INTO documents VALUES (?,?,?,?,?,?,?,?,?,?)",
                             (doc_id, source, doc_type, full_hash, version, len(ids), access_level, json.dumps(doc_meta),
                              current["created_at"] if current else now, now))
            self._db.execute("INSERT INTO history VALUES (?,?,?,?,?,?)",
                             (doc_id, version, full_hash, "updated" if current else "created", now, actor))
            self._db.commit()
            return {"document_id": doc_id, "status": "updated" if current else "created", "version": version, "chunks": len(ids),
                    "duplicate_chunks": duplicates}

    def delete(self, doc_id: str, actor: str) -> bool:
        with self._lock:
            current = self.get_document(doc_id)
            if not current:
                return False
            self._delete_chunks(doc_id)
            self._db.execute("DELETE FROM documents WHERE document_id=?", (doc_id,))
            self._db.execute("INSERT INTO history VALUES (?,?,?,?,?,?)", (doc_id, current["version"], current["content_hash"], "deleted",
                                                                         time.time(), actor))
            self._db.commit()
            return True

    def _delete_chunks(self, doc_id: str) -> None:
        ids = [r[0] for r in self._db.execute("SELECT chunk_id FROM chunks WHERE document_id=?", (doc_id,))]
        if ids:
            self.collection.delete(ids=ids)
            for cid in ids:
                self.bm25.remove(cid)
            self._db.execute("DELETE FROM chunks WHERE document_id=?", (doc_id,))

    def count(self) -> int:
        return self.collection.count()
