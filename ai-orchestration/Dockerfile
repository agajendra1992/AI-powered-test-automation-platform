# syntax=docker/dockerfile:1
# AI orchestration service (FastAPI + LangChain + LangGraph). Optional: the Java framework and MCP server never need it.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    AI_HOST=0.0.0.0 AI_PORT=8095 AI_DATA_DIR=/var/lib/ai
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10002 --create-home ai \
    && mkdir -p /var/lib/ai && chown ai:ai /var/lib/ai
COPY app/ app/
COPY main.py .
USER ai

EXPOSE 8095
HEALTHCHECK --interval=5s --timeout=3s --retries=20 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8095/health', timeout=3)"]
CMD ["python", "main.py"]
