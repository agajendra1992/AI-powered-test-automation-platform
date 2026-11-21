# syntax=docker/dockerfile:1
# Bundled demo application under test. In-memory, demo credentials only: NOT for production.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10001 --no-create-home sut
COPY app.py ui.py ./
USER sut

EXPOSE 8000
HEALTHCHECK --interval=2s --timeout=2s --retries=15 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/health', timeout=2)"]
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--log-level", "warning"]
