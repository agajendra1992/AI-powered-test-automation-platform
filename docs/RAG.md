# RAG service (knowledge base)

`rag/` is a small Python service (FastAPI + ChromaDB) that indexes the project's knowledge:
features, step definitions, page objects, API services, docs, historical failures and reports. AI
assistants can then find relevant material and cite it. It is optional: the framework, the MCP
server and the AI tools all work without it.

```
Sources ──> ingest (path allow-list, type detection, redaction of secrets/PII, injection flags)
        ──> chunking per type ──> embeddings (configurable) ──> ChromaDB + SQLite registry (versions, chunk hashes)
Query   ──> semantic (ChromaDB) + keyword (BM25) ──> metadata + access-level filters ──> reciprocal-rank fusion
        ──> lexical rerank, injection down-rank, de-duplication ──> numbered citations ──> fenced context
        ──> optional answer (must cite [n]; citations are checked)
```

Retrieved knowledge is evidence for people and models to read. It is never a test result, and
it never replaces running the tests.

## Run it

```bash
python3.12 -m venv rag/.venv && rag/.venv/bin/pip install -r rag/requirements.txt
```

```bash
cd rag && RAG_ALLOWED_ROOTS=.. RAG_KEYS_FILE=/secure/rag-keys.json .venv/bin/python -m app.main
```

It listens on `http://127.0.0.1:8090`. The first start downloads the ONNX embedding model
(about 80 MB). Create a key entry the same way as for the MCP server: store only the SHA-256 of
a random token.

```json
[{"name": "mcp-server", "role": "writer", "sha256": "<sha256 of the token>", "max_access": "internal"}]
```

Connect the MCP server with `QA_RAG_URL=http://127.0.0.1:8090` and `QA_RAG_KEY=<token>`
(environment only).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `RAG_DATA_DIR` | `.rag-data` | ChromaDB files, registry, audit log |
| `RAG_ALLOWED_ROOTS` | `.` | Path ingestion is limited to these directories (`:`-separated) |
| `RAG_KEYS_FILE` | (none) | Bearer keys (hashes only). Without it every protected call is rejected |
| `RAG_EMBEDDING_PROVIDER` | `onnx` | `onnx` (all-MiniLM-L6-v2, local, no PyTorch), `ollama`, `openai` (any compatible endpoint), `hash` (tests only, not semantic) |
| `RAG_EMBEDDING_MODEL` / `RAG_EMBEDDING_BASE_URL` / `RAG_EMBEDDING_API_KEY` | – | For `ollama` / `openai` |
| `RAG_LLM_PROVIDER` | `none` | `anthropic` (official SDK, `claude-opus-5-5` by default), `openai`, `local`: answers for `/rag/query` |
| `RAG_LLM_MODEL` / `RAG_LLM_BASE_URL` / `RAG_LLM_API_KEY` | – | Model settings; `ANTHROPIC_API_KEY` for Anthropic |
| `RAG_HOST` / `RAG_PORT` | `127.0.0.1` / `8090` | Bind address |

An index records the embedder that built it. Starting with a different embedder is refused:
use a new `RAG_DATA_DIR` or re-ingest.

## API

| Endpoint | Role | Purpose |
|---|---|---|
| `POST /rag/ingest` | writer | `paths` (inside the allowed roots) and/or inline `documents` (`source`, `content`, `document_type`, `metadata`), `access_level` |
| `POST /rag/search` | reader | `query`, `k`, `filters` (`document_type`, `module`, `scenario`, `tag`, `environment`, `status`, `java_kind`, `error_type`, `feature`, `class`, `endpoint`, `source`, `document_id`), `mode` (`hybrid`, `semantic` or `keyword`) |
| `POST /rag/query` | reader | Search, then fenced numbered context, citations and an optional answer with checked citations |
| `GET /rag/health` | open | Status, chunk count, embedder; no content |
| `GET /rag/documents`, `GET /rag/document/{id}` | reader | Registry, including version history |
| `DELETE /rag/document/{id}` | writer | Removes a document and its chunks (audited) |
| `POST /rag/evaluate` | reader | Recall@k and MRR for a gold set |

**What gets indexed and how:**

| Type | Chunking | Key metadata |
|---|---|---|
| Feature files | One chunk per scenario, with feature name, tags and Background | `scenario`, `module`, `tag`, `line` |
| Java | One chunk per method with a class header | `java_kind` (step_definition, page_object, component, api_service, …), `class`, `method`, `step_expressions` |
| Markdown | By heading (headings inside code blocks ignored) | `section` |
| PDF | Text per page | – |
| JSON | Detected by shape: Cucumber report (per scenario result), failure record, execution summary, OpenAPI (per operation), other JSON | `status`, `scenario`, `error_type`, `endpoint` |
| TestNG results XML | One chunk per test method | `status`, `test` |
| Logs | 40-line windows | `has_errors`, `scenario` |

Bug reports, user stories, acceptance criteria and troubleshooting notes are ingested as inline
documents with that `document_type`, for example:

```json
{"documents": [{"source": "jira/QA-123.md", "document_type": "bug_report", "content": "..."}], "access_level": "restricted"}
```

**Incremental ingestion and versioning.** Unchanged documents are skipped. A changed document
gets a new version: its old chunks are removed, and the history is kept (`GET /rag/document/{id}`).
**Duplicate detection:** chunks whose normalised content already exists in another document are
reported at ingestion, and only one copy is returned per search.

## Security

* Bearer keys are stored as SHA-256 hashes and compared in constant time. Roles are `reader`,
  `writer` and `admin`, and every call is written to `<data>/audit.log`.
* Each chunk has an access level: `public`, `internal` or `restricted`. A key only ever sees chunks
  at or below its `max_access`, so for example bug reports can be kept from some clients. The
  check runs both in the database query and again on the results.
* Ingestion only reads files under `RAG_ALLOWED_ROOTS`. It skips `.git`, virtual environments and
  build output, and caps document size.
* Text is redacted **before** it is embedded or stored: passwords and tokens, API keys, Luhn-valid
  card numbers, e-mail addresses (except example domains) and phone numbers. The index never holds
  them.
* Prompt injection: instruction-like text ("ignore previous instructions", "mark this test as
  passed", …) is flagged at ingestion, ranked lower in search and labelled in results. Retrieved
  text is always passed to a model inside `<data>` blocks whose closing tags can't be forged,
  under a system prompt that forbids following it.

## Through MCP

| Tool | Role | |
|---|---|---|
| `search_knowledge` | viewer | Hybrid search with filters and citations |
| `ask_knowledge_base` | viewer (answer: runner + AI) | Sources, plus an answer from the MCP server's AI layer with verified `[n]` citations |
| `ingest_project_knowledge` | runner | Re-indexes features, steps, pages, components, services and docs (incremental) |
| `ingest_execution_results` | runner | Indexes a run's failure records, scenario results and summary (historical knowledge) |

`analyze_scenario_failure` adds `knowledgeBaseMatches` (past failures, bug reports and notes,
with citations) when the RAG service is configured, and passes them to the model as untrusted data.

## Evaluate retrieval

`rag/eval/gold.json` holds 15 project questions with the source each one should find. The test
suite asserts recall@5 ≥ 0.8 with the hash embedder. To compare modes with real embeddings, see
the verification log in `IMPLEMENTATION_STATUS.md`. Extend the gold set when you add knowledge,
then compare `mode=semantic`, `keyword` and `hybrid` with `POST /rag/evaluate`.
