# agentic-operations

Evidence-grounded incident analysis for box1 Operations ([box1-docs#20](https://github.com/box1-org/box1-docs/issues/20)).

Given an incident from the operations console (`inc_<orderId>`), the service collects read-only evidence about that one order. It asks the local model (vLLM `box1-coder`) for a structured analysis and returns findings, each of which cites the evidence behind it. The same capability is exposed as REST and as MCP tools.

It also serves the operations console's **Assistant** ([box1-docs#101](https://github.com/box1-org/box1-docs/issues/101), decision D-016). This is a free-form chat in which the model may call the read-only evidence tools listed below, in a bounded loop, under the asking person's own roles.

The service is **read and propose only**:

- It has no write route or tool, and no connector that can change a system.
- Suggested actions are proposals that are never executed (`executable: false`).

## Interfaces

| Interface | Path | Role |
|---|---|---|
| Queue an analysis | `POST /api/v1/ai/incidents/{incidentId}/analyze` → 202 with `analysisId` | `aiops-analyst` |
| Read an analysis | `GET /api/v1/ai/analyses/{analysisId}` | `aiops-viewer` or `aiops-analyst` |
| Start a conversation | `POST /api/v1/ai/conversations` → 201 with `conversationId` | `aiops-viewer` or `aiops-analyst` |
| Ask a question | `POST /api/v1/ai/conversations/{conversationId}/messages` with body `{"text"}` → 202 with `turnId` | same, own conversations only |
| Read a conversation | `GET /api/v1/ai/conversations/{conversationId}` | same, own conversations only |
| MCP (Streamable HTTP) | `/mcp` | as below |
| OpenAPI | `/q/openapi` | — |

### MCP tools

| Tool | Role | Notes |
|---|---|---|
| `get_order_timeline` | viewer | order-service timeline |
| `get_fulfillment_status` | viewer | located through the event window |
| `get_inventory_reservation` | viewer | |
| `list_recent_events` | viewer | order or asset; in-memory window, hours not history |
| `list_telemetry_alerts` | viewer | `UNAVAILABLE` in v1 |
| `get_deployed_revision` | viewer | order-service, inventory-service, fulfillment-adapter |
| `analyze_incident` | analyst | same as the REST POST |
| `get_analysis` | viewer | same as the REST GET |
| `get_trace` | viewer | Tempo trace summary: services, root span, duration, error and slowest spans (box1-docs#111) |
| `search_logs` | viewer | LokiStack lines for one `orderId` or `traceId` in the order-path namespaces, last 72 h, at most 50 |
| `list_service_alerts` | viewer | Active alerts and down scrape targets for one allowlisted service's namespace |

The server exposes tools only: no resources, prompts or sampling. Every result is wrapped as `{"untrusted": true, "result": ...}`.

### Analysis statuses

| Status | Meaning |
|---|---|
| `QUEUED`, `RUNNING` | In progress. |
| `COMPLETED` | Findings cite evidence. Sources that are unavailable in v1 are listed in `unknowns`. |
| `DEGRADED` | An expected source failed (error, timeout, unauthorized, unreachable). If the order itself could not be read, no model call is made. |
| `MODEL_UNAVAILABLE` | Model down or timed out. Evidence is returned with no findings and no summary. |
| `MODEL_INVALID_OUTPUT` | Invalid JSON, truncated output, a tool-call attempt, or a schema violation. No findings. |
| `FAILED` | Internal error. |

Other responses:

- `429 BUSY` with `Retry-After: 30` when the model queue is full.
- `404 ANALYSIS_NOT_FOUND` for an unknown or expired ID. Analyses live in memory and are lost on restart.

### Assistant chat (D-016)

A message is answered on the shared model queue by a bounded loop:

- The model may call `get_order_timeline`, `get_fulfillment_status`, `get_inventory_reservation`, `list_recent_events`, `get_deployed_revision`, `get_analysis`, `get_trace`, `search_logs` and `list_service_alerts`. These are the same reads as the MCP tools.
- It cannot start an analysis, and nothing it can call writes, executes, runs a shell, calls the Kubernetes API or fetches a URL.
- Every argument is validated against the contract ID patterns before any read. An unknown tool or an invalid argument comes back to the model as an error, with no read made.
- Every result is wrapped as untrusted data and capped at `source-max-bytes`.

**Per-message limits:**
- 8 tool calls and 4 model rounds. When the budget is spent, one last call without tools gives the answer, and the turn status is `LIMIT_REACHED`.
- A 90 s deadline.
- At most 1024 answer tokens.
- At most 2000 question characters.

**History:** the model sees the earlier questions and answers of the same conversation, but not their tool results.

**Conversations:**
- They live in memory: 200 at most, forgotten after 2 h idle, lost on restart.
- Each one belongs to the person who started it. Anyone else gets `404 CONVERSATION_NOT_FOUND`.
- There are at most 20 messages per conversation, and one message at a time. A second message while one runs gets `409 TURN_IN_PROGRESS`.

**Turn statuses:**

| Status | Meaning |
|---|---|
| `QUEUED`, `RUNNING` | In progress. `steps` grows as tools are read. |
| `COMPLETED` | `answer` is the model's text. |
| `LIMIT_REACHED` | `answer` was given after the tool budget ran out. |
| `MODEL_UNAVAILABLE`, `MODEL_INVALID_OUTPUT`, `FAILED` | No answer is shown. |

Each step records `tool`, `target` (a validated ID only), `decision` (`ok`, `invalid_argument`, `not_found`, `unknown_tool`, `limit` or `error`), `retrievalStatus` and `durationMs`.

## Security boundaries

- **Identity:** Keycloak realm `box1`, audience `agentic-operations`, with roles taken from `resource_access/agentic-operations/roles`. Only callers whose `azp` is in `AIOPS_ALLOWED_AZP` (default `agentic-operations-cli`) are accepted.
- **Rejection:** at the HTTP layer, on `/api/v1/ai/*` and `/mcp`, anonymous calls get 401 and calls without an `aiops-*` role get 403. Roles and the accepted client are checked again in the service layer for REST and MCP alike. MCP tool filters are not used for authorization, because they fail open.
- **Tokens:**
  - Tokens are verified locally only; introspection is off.
  - The OIDC provider logger is set to ERROR, because Quarkus logs the raw token at WARN on failure.
- **Evidence scope:** evidence is fetched only for the requested order, with IDs validated against the contract patterns before any call. A referenced fulfillment or reservation whose `orderId` differs is reported as `CONFLICTING`, and its data is not shown.
  - Upstream fields are allowlisted and their strings are bounded.
  - Kafka payloads keep only the contract fields of each event type, as scalars of 128 characters or fewer.
- **Model:**
  - **Analyses:** the model gets no tools (D-006). Evidence goes only in the user message, framed as untrusted JSON, and the system policy is fixed.
  - **Assistant chat (D-016, supersedes D-006 and D-010 for chat only):**
    - The model chooses among the read-only tools above, so injected text in evidence can steer which reads happen.
    - Every read is still validated, read-only, limited by the per-message budget, and within what the asking person may already see.
    - Answers return only to that person, and no tool can send data elsewhere.
    - The system policy `aiops-chat-policy-2` tells the model to treat tool results as data and never to claim it did anything. It also says only an `OK` read counts as checked: `UNAVAILABLE`, `UNAUTHORIZED` and other failures are "could not check", never "no record". This follows the 2026-11-08 live check (box1-docs#101).
    - Answers are model text and can be wrong; the console labels them so.
  - Output must match the schema. Findings without valid citations, and actions outside the allowlist, are dropped and counted. If every finding is dropped, the model's summary is replaced with a fixed "no supported diagnosis" text.
- **Logs:** prompts, completions, chat questions and answers, evidence and reasoning output are never logged. Logs carry IDs, outcomes, token counts and durations. The audit log records a caller-supplied ID only when it has a known shape.
- **Upstream reads:** each has one overall deadline covering headers and body, and the body is capped. A stalled upstream ends as `TIMEOUT`.
- **Customers:** v1 is operator-only; box1 has no customer identity.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `VLLM_BASE_URL` | `http://vllm-test-2.vllm.svc:8000/v1` | In-cluster vLLM; never the public route |
| `MODEL_ID` | `box1-coder` | Served model name |
| `MODEL_TIMEOUT` | `120s` | One attempt per analysis |
| `AIOPS_CONTEXT_PROFILE` | `32k` | `32k`: 48 KiB of evidence and 4096 output tokens. `16k`: half of each |
| `ORDER_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `FULFILLMENT_ADAPTER_URL` | dev in-cluster Services | Evidence sources |
| `KAFKA_BOOTSTRAP_SERVERS` | — | Evidence window |
| `AIOPS_CONSUMER_GROUP` | `agentic-operations` | One declared group per environment. The window is per pod |
| `OIDC_AUTH_SERVER_URL` | box1 realm | Token issuer |
| `AIOPS_ALLOWED_AZP` | `agentic-operations-cli` | Accepted clients, comma-separated |
| `AIOPS_PLATFORM_TOKEN_PATH`, `AIOPS_PLATFORM_CA_PATH` | unset | Projected ServiceAccount token and service CA for platform reads (box1-docs#111). Unset means trace, log and alert evidence is `UNAVAILABLE` ("not configured") |
| `AIOPS_TEMPO_URL`, `AIOPS_LOKI_URL`, `AIOPS_THANOS_TENANCY_URL`, `AIOPS_ALERTMANAGER_TENANCY_URL` | unset | Tempo gateway (tenant `dev`), LokiStack gateway (tenant `application`), Thanos querier tenancy port 9092 and Alertmanager tenancy port 9095 |
| `AIOPS_PLATFORM_ORDER_NAMESPACES` | order, inventory and fulfillment-adapter `-dev` | The namespaces whose logs and alerts an order's evidence covers. Never taken from callers or the model |
| `AIOPS_ANALYSIS_*`, `AIOPS_WINDOW_*` | see `AiopsConfig` | Cache size and TTL (200, 24h), concurrency 1, queue 4, window age 6h, 20,000 events, 200 per key |

### Model client

`ModelClient` is implemented with LangChain4j's OpenAI-compatible chat model (`langchain4j-open-ai`, version from the Quarkus platform's `quarkus-langchain4j-bom`). It uses the plain library, not the `quarkus-langchain4j` extension, and only its low-level chat call. LangChain4j never runs a tool: `ChatService` decides which tool calls run.

- **Transport:** requests go through `BoundedHttpClient`, which uses `BoundedHttp`. That gives HTTP/1.1 only (vLLM answers HTTP 400 to an h2c upgrade offer), no redirects, one deadline over headers and body, and a 256 KiB response cap. No credential is sent, and trace headers are added.
- **Request settings:** retries are off, request and response logging is off, and reasoning output is not returned. Every request sets `temperature 0` and `chat_template_kwargs.enable_thinking=false`. Analyses use strict `json_schema` output.
- **Tool schemas:** these are sent without `additionalProperties`, because LangChain4j adds it only for strict tools. Strict tools would make every parameter required, and `search_logs` takes either `orderId` or `traceId`. `ChatTools` reads only the keys it knows and validates each value.
- **Live check:** `AIOPS_LIVE_MODEL_URL=https://<vllm-route>/v1 ./mvnw test -Dtest=LiveModelTest` runs both calls against a real vLLM endpoint with the production prompts and schemas. It is skipped when the variable is unset.

## Kafka evidence window

- **Topics:** consumer group `agentic-operations` (`AIOPS_CONSUMER_GROUP`) on the five `box1.*.events.v1` topics. DLQs are not read. Run **one replica with the `Recreate` strategy**: with two pods in the group, each holds only part of the partitions.
- **Indexes:** by `aggregateId` (order or asset) and by `correlationId`. Only contract payload fields are kept.
- **Read-only:** no offsets are committed and nothing is produced.
- **Restart:** on partition assignment, each partition seeks to now minus the window age, so a restart refills the window.
- **Bad records:** duplicates are dropped by `eventId`, or by `alertId` for threshold alerts. Unusable records, including a wrong `schemaVersion` or a missing `causationId`, are skipped and counted in `aiops_kafka_records_total{outcome="skipped",reason}`.
- **Seek failure:** if the start-of-window seek fails, `aiops_window_seek_failures_total` counts it. The window then fills from now.
- **Readiness:** Kafka is not part of readiness.

## Operations runbook

- **Metrics:**
  - `aiops_analyses_total{status}`
  - `aiops_analysis_duration_seconds{status}`
  - `aiops_model_tokens_total{kind}`
  - `aiops_queue_depth`
  - `aiops_mcp_tool_calls_total{tool,decision}`
  - `aiops_chat_turns_total{status}`
  - `aiops_chat_turn_duration_seconds{status}`
  - `aiops_chat_tool_calls_total{tool,decision}` (tool is a known name or `unknown`)
  - `aiops_evidence_fetch_total{type,status}`
  - `aiops_window_events`
  - `aiops_window_oldest_age_seconds`
  - `aiops_window_seek_failures_total`
  - Kafka consumer lag: `kafka_consumer_fetch_manager_records_lag_max{topic,partition}`, from the Micrometer Kafka binder. Group lag from a Kafka exporter means nothing here, because this group never commits offsets.
  - None of these are scraped until the box1-workloads-dev ServiceMonitor exists.
- **Model down:** analyses end `MODEL_UNAVAILABLE`, and nothing else is affected. Check the `vllm-test` Deployment in namespace `vllm` (box1-docs#26).
- **Busy (429):** one model job runs at a time, matching vLLM `--max-num-seqs 1`. Analyses and Assistant messages share the queue of 4, and a message holds the slot for its whole tool loop (90 s at most). Raise `AIOPS_ANALYSIS_CONCURRENCY` only together with vLLM.
- **Empty window after a restart:** expected while the consumer re-reads the last 6h from every partition. The refill time has not been measured yet. `aiops_window_oldest_age_seconds` grows as the window refills. If it stays at 0, check the Kafka bootstrap address, consumer group membership and `aiops_window_seek_failures_total`.
- **Shutdown:** queued and running analyses and chat messages are abandoned (in memory, by design). They are logged and counted as `aiops_model_work_abandoned_total`.
- **Tracing:** each chat message runs in an `aiops.chat.turn` span, with one `aiops.chat.tool` span per read. Each analysis runs in an `aiops.analysis` span with the `aiops.analysis_id` attribute, under the submitting request's trace. Upstream and model calls carry W3C `traceparent`. Worker logs carry `analysisId` in MDC.
- **Lost analyses after a restart:** expected (in-memory, single replica). Re-run the analysis. Durable state is box1-docs#97.
- **Rollback:** promote the previous image digest through box1-workloads-dev. The service keeps no state.

## Known limits in v1

- `quarkus-mcp-server` 2.0.4 is built against Quarkus 3.33.3.1. On Quarkus 3.40.1.1 it is **tested, not declared compatible**. Check again when either is upgraded.
- The release smoke test (`box1-quarkus-smoke`) checks only the template endpoints. The 401s on `/api/v1/ai/*` and `/mcp`, and the new OpenAPI paths, are checked by hand at live acceptance.

- **Platform evidence** (box1-docs#111) is read with the service's own projected ServiceAccount token over TLS that trusts only the service CA.
  - **Sources:** `TRACE_CONTENT` (Tempo), `LOGS` (LokiStack) and `METRICS_AND_ALERTS` (Alertmanager active alerts, plus `up` from the Thanos querier, both per namespace).
  - **Scope:** RBAC limits these reads to the Box1 service namespaces: `box1-observability-reader` and `monitoring-rules-view` RoleBindings in box1-workloads-dev, plus `box1-traces-read-dev` and `cluster-logging-application-view` in box1-gitops.
  - **Not CORE:** a refusal or failure is shown, as `UNAUTHORIZED`, `UNREACHABLE` and so on, and listed in `unknowns`. It never degrades an analysis.
  - **Untrusted text:** log lines and span names are written by services, so they are treated as untrusted data, like Kafka payloads. Only named fields survive, each cut to 256 characters.
- **Still unavailable:** telemetry alerts from asset-service. They need a confidential Keycloak client and `ASSET_READ_CLIENTS` (box1-docs#20 Q-5). Threshold alerts may still appear in the event window.
- **agentic-operations' own logs** are not collected into LokiStack.
- The timeline is the first 50 entries, oldest first.
- `fulfillmentRequestId` is known only while its events are in the window (box1-docs#78).
