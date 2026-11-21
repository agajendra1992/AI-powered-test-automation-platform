# Performance validation

All measurements were taken on the development machine: Apple Silicon, 8 cores, **8 GB RAM**, with
other applications running, and Docker Desktop limited to 4 GB. They show relative costs and
bottlenecks, not production capacity. **Re-measure on your CI runners and target machines before
choosing settings.** No speed-up is claimed without a measurement.

## Framework (37-scenario regression against the local demo app, Chromium)

| Setting | Suite duration | Avg time per scenario (incl. set-up/clean-up) |
|---|---|---|
| 1 thread | 30.5 s | ~0.8 s |
| 4 threads | 28.0 s | 2.8 s |
| 1 thread, tracing off | 29.7 s | 0.76 s |
| 4 threads, tracing off | 27.3 s | 2.6 s |
| (Phase 1, same suite, 4 threads, quieter machine) | 18.3 s | |

**Findings**
* On this 8 GB machine, 4 threads are barely faster than 1. Per-scenario time triples under
  parallel load: four browsers compete for CPU and memory. The sum of the step durations is only
  18.4 s; set-up, clean-up and browser contexts dominate.
* Tracing in `retain-on-failure` mode costs about 3 %. It stays on by default because failure
  traces are worth it.
* **Scale out, not up, on small machines:** CI shards (`regression.yml`, 3 shards by default) and
  Docker workers each get their own CPU. Set `threads` per machine from a measurement (try 2, 4 and
  8 with `-Dthreads=N` and compare `durationMs` in `execution-summary.json`).
* API-only suites start no browser (verified: 0 launches), and run with
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` in CI.

## Containers

| Measurement | Result |
|---|---|
| Building the three platform images (MCP, RAG, demo app), cold | 3 min |
| `docker compose --profile platform up --wait` until all healthy | 18 s |
| Idle memory: MCP / RAG / ChromaDB / demo app | 139 / 99 / 48 / 50 MB |
| API suite inside the MCP container (20 scenarios) | 10.1 s, 20/20 passed |
| Smoke in the test container (4 scenarios) | 4.8 s |

## RAG

| Measurement | Result |
|---|---|
| Embedding 64 chunks: host, 8 cores | 4.6 s |
| … in the container at 1.5 / 2 / 4 CPUs | 34 s / 33 s / 11 s (CPU-bound; ONNX thread settings made no difference) |
| ChromaDB server: add 64 vectors / read all metadata | 0.08 s / 0.012 s |
| First full index of the project (46 documents, 285 chunks): host embedded / container at 1.5 CPUs | 13 s / 238 s |
| Search latency, 248-chunk index, 60 queries: keyword / semantic / hybrid | p50 5 / 75 / 83 ms; p95 8 / 86 / 107 ms |

**Findings**
* Embedding dominates ingestion and scales with CPUs. The Compose default is `RAG_CPUS=2`; raise
  it for a large first index. Re-ingestion is incremental, so only changed files are embedded.
* Query latency is mostly embedding the query (keyword-only search is 15 times faster). Hybrid
  adds about 8 ms over semantic.

## MCP

| Measurement | Result |
|---|---|
| Discovery tools (`list_scenarios`, `get_scenario_details`, …) | ~0.1 s |
| Reporting tools on a finished run | < 0.1 s |
| History analytics (`get_flaky_tests`, `correlate_failures`) over 5 runs | < 0.1 s |
| Compile check of a generated step class (whole framework compiled in memory) | a few seconds; ~1 GB heap |
| Local LLM (llama3 8B, Ollama) on this machine | 78 s for a 2-word reply; up to 300 s (timeout) for full prompts, so too slow here |

## How to re-measure

```bash
for t in 1 2 4; do mvn -q clean test -DsuiteXmlFile=testng-regression.xml -Dthreads=$t; python3 -c "import json;d=json.load(open('target/execution-summary.json'));print($t, d['durationMs'], d['avgScenarioMs'])"; done
```

```bash
RAG_CPUS=4 docker compose --profile platform up -d --wait rag
```

RAG retrieval quality: `POST /rag/evaluate` with `rag/eval/gold.json` (recall@k, MRR per mode).
