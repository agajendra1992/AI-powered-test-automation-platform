# syntax=docker/dockerfile:1
# RAG service (FastAPI). Vectors live in the chroma service (RAG_CHROMA_HOST) or, without it, in RAG_DATA_DIR.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    RAG_HOST=0.0.0.0 RAG_PORT=8090 RAG_DATA_DIR=/var/lib/rag RAG_ALLOWED_ROOTS=/knowledge
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10001 --create-home rag \
    && mkdir -p /var/lib/rag /knowledge && chown rag:rag /var/lib/rag
COPY app/ app/
COPY eval/ eval/
USER rag
# Bake the ONNX embedding model into the image: no download at start-up, works offline.
RUN python -c "from chromadb.utils.embedding_functions import ONNXMiniLM_L6_V2 as E; E()(['warm-up'])"

EXPOSE 8090
HEALTHCHECK --interval=5s --timeout=3s --retries=20 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/rag/health', timeout=3)"]
CMD ["python", "-m", "app.main"]
