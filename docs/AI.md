# AI layer (LLM integration)

The AI layer lives in the MCP server (`mcp-server/`, package `io.qaplatform.ai`). The test
framework does not depend on it, and **every AI tool works without a model**: each one first
produces a deterministic result, and the model adds an opinion or a draft on top.

Ground rules, enforced in code:

* AI never changes a test result. Status comes only from the framework (Maven exit code and
  `execution-summary.json`). AI output is labelled as a hypothesis or a suggestion.
* Generated features and step classes are **drafts** stored outside the source tree. Only an
  admin can add them to the project with `accept_generated_draft`. It re-runs every check and
  never overwrites a file.
* Text from feature files, logs, reports, user stories and specs is passed to the model inside
  `<data>` blocks, with secrets redacted and closing tags neutralised. Every system prompt says
  this data is never an instruction.
* Model answers are validated before use: tags must exist, the environment must be allowed, the
  selection must match at least one scenario, failure categories must be one of the eleven, and
  cited evidence must appear verbatim in the recorded evidence. Invalid answers are discarded and
  the deterministic result is used.

## Providers

`LlmProvider` is the abstraction. Configure the provider with environment variables (or
`-Dqa.ai.*`). API keys are read from the environment only, and are never logged, stored or
returned.

| `QA_AI_PROVIDER` | Implementation | Key variable (default) | Model |
|---|---|---|---|
| `none` (default) | AI off; deterministic results only | – | – |
| `anthropic` | Official Anthropic Java SDK 3.10.0, streamed | `ANTHROPIC_API_KEY` | `claude-opus-5-5` (default) |
| `openai` | Chat Completions REST API | `OPENAI_API_KEY` | `QA_AI_MODEL` required |
| `gemini` | `generateContent` REST API (key in a header, never in the URL) | `GEMINI_API_KEY` | `QA_AI_MODEL` required |
| `local` | Any OpenAI-compatible server: Ollama, vLLM, LM Studio, llama.cpp | `LOCAL_LLM_API_KEY` (optional) | `QA_AI_MODEL` required |

| Setting | Default | Meaning |
|---|---|---|
| `QA_AI_MODEL` | see above | Model id. Required for every provider except `anthropic`, so no model id is ever guessed |
| `QA_AI_BASE_URL` | provider default (`local`: `http://localhost:11434/v1`) | Endpoint override (local servers, gateways) |
| `QA_AI_API_KEY_ENV` | per provider | Name of the environment variable that holds the key |
| `QA_AI_TIMEOUT_SECONDS` | `180` | Per-call timeout |
| `QA_AI_MAX_OUTPUT_TOKENS` | `16000` for anthropic, `4000` for the others | Answer budget per call |
| `QA_AI_DAILY_TOKEN_BUDGET` | `1000000` | Calls are refused once a UTC day's input + output tokens reach this |
| `QA_AI_CONTEXT_TOKENS` | `0` (no check) | Set it for small local models (e.g. `4096`): prompts that would not fit are refused instead of silently truncated |

`get_ai_status` shows the active provider, or why AI is disabled. `get_ai_usage` reports tokens
per provider, model, purpose and day from `<state>/ai-usage.jsonl`, which stores no prompt text.
There is no cost estimate, because prices vary by provider and contract.

**Local models.** Ollama's default context window is 4,096 tokens, which is too small for
feature generation (the prompt includes the step catalogue). Start it with
`OLLAMA_CONTEXT_LENGTH=16384` and set `QA_AI_CONTEXT_TOKENS=16384`. Small models on laptops can
take minutes per call (see the verification notes in `IMPLEMENTATION_STATUS.md`).

## Tools

Model calls need the **runner** role, because they spend the budget and send redacted project
data to the provider. Viewers get the deterministic part of each tool.

| Tool | Without a model | With a model |
|---|---|---|
| `analyze_scenario_failure` | Collects the evidence (failure record, failing step, browser diagnostics, API calls, logs, similar earlier failures) and classifies it into one of 11 categories with explainable rules | Adds an "unconfirmed hypothesis" (category, root cause, recommended action); cited evidence is checked verbatim, and confidence is capped when it can't be found |
| `plan_test_execution` → `execute_test_plan` | Keyword rules turn a request into a validated plan (tags, environment, browser, test-case count, what is left out, approval needed) | The model interprets the request; its plan is used only if it passes the same validation |
| `generate_feature_file` | – (use `validate_feature_file`) | Draft from a user story and acceptance criteria, reusing existing steps; validated, repaired once from the validator's findings, stored for review |
| `generate_step_definitions` | – | Step class for a draft's undefined steps: static rules, compilation against the real framework (in memory), every step bound, no new ambiguity |
| `validate_feature_file` | Gherkin syntax, step bindings, duplicates, tag conventions, literal secrets | – |
| `accept_generated_draft` (admin) | Re-checks and adds the files as new files; audited | – |
| `suggest_regression_tests` | Traces changed files to the scenarios that use them (or the full regression for shared code) and saves an executable plan | Validated extra tag suggestions from the change description, kept separate from the plan |
| `suggest_missing_scenarios` | Per-domain balance (positive/negative, UI/API, smoke) and OpenAPI endpoints no API automation calls | Scenario ideas for given requirements, flagged when an existing scenario looks similar |
| `summarize_execution` | Facts plus a rule-based category per failed scenario | A short narrative, discarded if it contradicts the facts |
| `get_ai_status`, `get_ai_usage`, `list_generated_drafts`, `get_generated_draft` | Status, usage and drafts | – |

## Prompts

Prompts are versioned templates in `mcp-server/src/main/resources/prompts/*.md` (a `version:`
line, then `## system` and `## user` sections). Each AI result records the provider, model and
prompt version. To change a prompt, bump its version and re-run the AI tests:

```bash
mvn -f mcp-server/pom.xml test -Dtest='AiClientTest,GenerationTest,PlanningAndAdviceTest'
```

**Storing a key:** `scripts/set-llm-key.sh openai|anthropic|gemini` saves it to `~/.config/qa-platform/<provider>.env` (hidden prompt, mode 600) and `--check` verifies it; see [AI_ORCHESTRATION.md](AI_ORCHESTRATION.md#setting-a-provider-key).
