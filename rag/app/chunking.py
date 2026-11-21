"""Parsing and chunking per document type. Each chunk carries metadata for filtering and citations."""
from __future__ import annotations

import io
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

STANDARD_TAGS = {"@smoke", "@regression", "@sanity", "@critical", "@ui", "@api", "@e2e", "@positive", "@negative",
                 "@quarantine", "@noretry"}
FRAMEWORK = "cucumber-playwright-java"


@dataclass
class Chunk:
    text: str
    metadata: dict = field(default_factory=dict)


def detect_type(source: str, text: str) -> str:
    s = source.lower()
    if s.endswith(".feature"):
        return "feature"
    if s.endswith(".java"):
        return "java"
    if s.endswith((".md", ".markdown")):
        return "documentation"
    if s.endswith(".pdf"):
        return "pdf"
    if s.endswith(".log") or "/logs/" in s:
        return "log"
    if s.endswith("testng-results.xml"):
        return "testng_report"
    if re.search(r"testng-[\w-]+\.xml$", s):
        return "testng_suite"
    if s.endswith(".json"):
        return "json"
    return "text"


def chunk_document(source: str, text: str, doc_type: str | None, base_meta: dict, max_chars: int) -> list[Chunk]:
    kind = doc_type or detect_type(source, text)
    if kind == "feature":
        chunks = _feature(text)
    elif kind == "java":
        chunks = _java(source, text)
    elif kind in ("documentation", "user_story", "acceptance_criteria", "bug_report", "troubleshooting"):
        chunks = _markdown(text)
    elif kind == "log":
        chunks = _log(text)
    elif kind == "json":
        chunks, kind = _json(text)
    elif kind == "testng_report":
        chunks = _testng_results(text)
    else:
        chunks = _windows(text, 60)
    out: list[Chunk] = []
    for c in chunks:
        meta = {"document_type": kind, "framework": FRAMEWORK, **base_meta, **c.metadata}
        for part in _split(c.text, max_chars):
            out.append(Chunk(part, dict(meta)))
    return [c for c in out if c.text.strip()]


def pdf_text(data: bytes) -> str:
    from pypdf import PdfReader  # imported lazily: only needed for PDFs

    reader = PdfReader(io.BytesIO(data))
    return "\n\n".join(f"[page {i + 1}]\n{(p.extract_text() or '').strip()}" for i, p in enumerate(reader.pages))


# ---------------------------------------------------------------- feature files

def _feature(text: str) -> list[Chunk]:
    lines = text.splitlines()
    feature_name, feature_tags, background, pending_tags = "", [], [], []
    blocks: list[tuple[int, str, list[str], list[str]]] = []
    current: list[str] | None = None
    in_background = False
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("@") and not current and not in_background:
            pending_tags += stripped.split()
            continue
        if stripped.startswith("Feature:"):
            feature_name, feature_tags, pending_tags = stripped[8:].strip(), pending_tags, []
            continue
        if stripped.startswith("Background:"):
            in_background, current = True, None
            background.append(stripped)
            continue
        if stripped.startswith("@"):
            pending_tags += stripped.split()
            continue
        m = re.match(r"(Scenario Outline|Scenario Template|Scenario|Example):\s*(.*)", stripped)
        if m:
            in_background = False
            current = [stripped]
            blocks.append((i, m.group(2).strip(), feature_tags + pending_tags, current))
            pending_tags = []
            continue
        if in_background:
            if stripped:
                background.append(stripped)
        elif current is not None and stripped:
            current.append(stripped)
    module = _module_from_tags(feature_tags)
    chunks = []
    for line_no, name, tags, body in blocks:
        header = f"Feature: {feature_name}\nTags: {' '.join(tags)}"
        bg = ("\n" + "\n".join(background)) if background else ""
        chunks.append(Chunk(header + bg + "\n" + "\n".join(body), {
            "scenario": name, "feature": feature_name, "tags": " ".join(sorted(set(tags))),
            "module": _module_from_tags(tags) or module or "", "line": line_no,
            "tag": next((t[1:] for t in tags if t in ("@smoke", "@regression", "@sanity")), "")}))
    if not chunks and text.strip():
        chunks.append(Chunk(text, {"feature": feature_name, "module": module or ""}))
    return chunks


def _module_from_tags(tags: list[str]) -> str:
    for t in tags:
        if t.startswith("@") and t not in STANDARD_TAGS:
            return t[1:]
    return ""


# ---------------------------------------------------------------- Java

_JAVA_KIND = [("/bdd/steps/", "step_definition"), ("/ui/pages/", "page_object"), ("/ui/components/", "component"),
              ("/api/services/", "api_service"), ("/api/client/", "api_client"), ("/bdd/hooks/", "hooks"),
              ("/bdd/context/", "scenario_context"), ("/core/", "framework"), ("/mcp/", "mcp_server"), ("/ai/", "ai_layer")]
_METHOD = re.compile(r"^(?P<lead>(?:[ \t]*(?:/\*\*[\s\S]*?\*/|@[\w.]+(?:\([^)]*\))?)\s*\n)*)"
                     r"[ \t]*(?:public|protected|private|static|final|synchronized|\s)+[\w<>\[\],.? ]+\s+(?P<name>\w+)\s*\([^)]*\)[^{;]*\{",
                     re.MULTILINE)


def _java(source: str, text: str) -> list[Chunk]:
    kind = next((k for marker, k in _JAVA_KIND if marker in source.replace("\\", "/")), "java_source")
    pkg = re.search(r"^package\s+([\w.]+);", text, re.MULTILINE)
    cls = re.search(r"\b(?:class|record|interface|enum)\s+(\w+)", text)
    class_name = cls.group(1) if cls else ""
    module = ""
    for domain in ("payment", "user", "login", "auth", "file", "dashboard"):
        if domain in source.lower() or domain in class_name.lower():
            module = domain
            break
    header = f"// {pkg.group(1) + '.' if pkg else ''}{class_name} ({kind})"
    chunks = []
    for m in _METHOD.finditer(text):
        start = m.start()
        depth, end = 0, len(text)
        for j in range(text.index("{", m.end() - 1), len(text)):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    end = j + 1
                    break
        body = text[start:end].strip()
        steps = re.findall(r'@(?:Given|When|Then|And|But)\(\s*"((?:[^"\\]|\\.)*)"', body)
        meta = {"java_kind": kind, "class": class_name, "method": m.group("name"), "module": module,
                "line": text.count("\n", 0, start) + 1}
        if steps:
            meta["step_expressions"] = " | ".join(steps)
        chunks.append(Chunk(header + "\n" + body, meta))
    if not chunks:
        chunks = [Chunk(header + "\n" + text, {"java_kind": kind, "class": class_name, "module": module})]
    return chunks


# ---------------------------------------------------------------- markdown / text / logs

def _markdown(text: str) -> list[Chunk]:
    chunks, heading_path, buf = [], [], []

    def flush():
        if any(line.strip() for line in buf):
            chunks.append(Chunk(("# " + " > ".join(heading_path) + "\n" if heading_path else "") + "\n".join(buf).strip(),
                                {"section": " > ".join(heading_path)}))

    in_code = False
    for line in text.splitlines():
        if line.lstrip().startswith(("```", "~~~")):
            in_code = not in_code
        m = None if in_code else re.match(r"^(#{1,4})\s+(.*)", line)
        if m:
            flush()
            buf = []
            level = len(m.group(1))
            heading_path = heading_path[: level - 1] + [m.group(2).strip()]
        else:
            buf.append(line)
    flush()
    return chunks


def _windows(text: str, lines_per_chunk: int, overlap: int = 5) -> list[Chunk]:
    lines = text.splitlines()
    chunks, i = [], 0
    while i < len(lines):
        part = lines[i: i + lines_per_chunk]
        chunks.append(Chunk("\n".join(part), {"line": i + 1}))
        i += max(1, lines_per_chunk - overlap)
    return chunks


def _log(text: str) -> list[Chunk]:
    chunks = _windows(text, 40, 5)
    for c in chunks:
        scenarios = sorted(set(re.findall(r"\] \[([^\]]{3,200})\] \[", c.text)))
        c.metadata["has_errors"] = bool(re.search(r"\bERROR\b|Exception|FAILED", c.text))
        if scenarios:
            c.metadata["scenario"] = scenarios[0]
    return chunks


# ---------------------------------------------------------------- JSON and reports

def _json(text: str) -> tuple[list[Chunk], str]:
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return _windows(text, 60), "text"
    if isinstance(data, list) and data and isinstance(data[0], dict) and "elements" in data[0]:
        return _cucumber_report(data), "cucumber_report"
    if isinstance(data, dict) and "errorType" in data and "name" in data:
        return [_failure_record(data)], "failure"
    if isinstance(data, dict) and {"total", "passed", "failed"} <= data.keys():
        return [Chunk("Execution summary\n" + json.dumps(data, indent=1)[:4000],
                      {"status": "failed" if data.get("failed") else "passed", "environment": str(data.get("environment", "")),
                       "suite": str(data.get("suite", ""))})], "execution_summary"
    if isinstance(data, dict) and "paths" in data:
        return _openapi(data), "api_spec"
    return _windows(json.dumps(data, indent=1), 60), "json"


def _cucumber_report(features: list) -> list[Chunk]:
    chunks = []
    for f in features:
        for e in f.get("elements", []):
            if e.get("type") != "scenario":
                continue
            steps = e.get("before", []) + e.get("steps", []) + e.get("after", [])
            status, failing, error = "passed", "", ""
            for s in steps:
                st = s.get("result", {}).get("status", "unknown")
                if st != "passed" and status == "passed":
                    status = st
                    failing = (s.get("keyword", "") + s.get("name", "")).strip() or "hook"
                    error = "\n".join((s.get("result", {}).get("error_message") or "").splitlines()[:6])
            tags = [t.get("name", "") for t in e.get("tags", [])]
            lines = [f"Scenario result: {e.get('name')} ({status})", f"Feature: {f.get('name')}", f"Tags: {' '.join(tags)}"]
            if failing:
                lines += [f"Failed step: {failing}", f"Error: {error}"]
            chunks.append(Chunk("\n".join(lines), {"scenario": e.get("name", ""), "status": status, "tags": " ".join(tags),
                                                  "module": _module_from_tags(tags), "feature": f.get("name", "")}))
    return chunks


def _failure_record(d: dict) -> Chunk:
    stack = "\n".join(str(d.get("stackTrace") or "").splitlines()[:12])
    text = (f"Test failure: {d.get('name')}\nError type: {d.get('errorType')}\nMessage: {d.get('message')}\n"
            f"Environment: {d.get('environment')} Browser: {d.get('browser')} Retry attempt: {d.get('retryAttempt')}\n"
            f"Groups: {' '.join(d.get('groups') or [])}\nStack:\n{stack}")
    return Chunk(text, {"scenario": str(d.get("name", "")), "status": "failed", "error_type": str(d.get("errorType", "")),
                        "environment": str(d.get("environment", "")), "module": _module_from_tags(d.get("groups") or []),
                        "timestamp": str(d.get("timestamp", ""))})


def _openapi(spec: dict) -> list[Chunk]:
    chunks = []
    for path, ops in spec.get("paths", {}).items():
        for method, op in ops.items():
            if method.lower() not in {"get", "post", "put", "patch", "delete"}:
                continue
            summary = op.get("summary") or op.get("operationId") or ""
            responses = ", ".join(sorted(op.get("responses", {}).keys()))
            chunks.append(Chunk(f"API endpoint {method.upper()} {path}\nSummary: {summary}\nResponses: {responses}\n"
                                f"Parameters: {', '.join(p.get('name', '') for p in op.get('parameters', []))}",
                                {"endpoint": f"{method.upper()} {path}", "module": path.strip('/').split('/')[1] if path.count('/') > 1 else ""}))
    return chunks


def _testng_results(text: str) -> list[Chunk]:
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return _windows(text, 60)
    chunks = []
    for m in root.iter("test-method"):
        if m.get("is-config") == "true":
            continue
        message = m.find(".//exception/message")
        params = " ".join((p.findtext("value") or "").strip() for p in m.findall(".//param"))[:300]
        status = (m.get("status") or "").lower()
        chunks.append(Chunk(f"TestNG {m.get('name')} {status}\nParameters: {params}\n"
                            f"Exception: {(m.find('exception').get('class') if m.find('exception') is not None else '')}\n"
                            f"Message: {(message.text or '').strip()[:800] if message is not None else ''}",
                            {"status": "failed" if status == "fail" else status, "test": m.get("name", "")}))
    return chunks


def _split(text: str, max_chars: int) -> list[str]:
    if len(text) <= max_chars:
        return [text]
    parts, buf = [], ""
    for line in text.splitlines(keepends=True):
        if len(buf) + len(line) > max_chars and buf:
            parts.append(buf)
            buf = ""
        buf += line if len(line) <= max_chars else line[:max_chars]
    if buf:
        parts.append(buf)
    return parts
