# agentic-operations

Evidence-grounded incident analysis for box1 Operations ([box1-docs#20](https://github.com/box1-org/box1-docs/issues/20)).

Given an incident from the operations console (`inc_<orderId>`), the service collects read-only evidence about that one order. It asks the local model (vLLM `box1-coder`) for a structured analysis and returns findings, each of which cites the evidence behind it. The same capability is exposed as REST and as MCP tools.

The service is **read and propose only**:

- It has no write route or tool, and no connector that can change a system.
- Suggested actions are proposals that are never executed (`executable: false`).

## Interfaces

| Interface | Path | Role |
|---|---|---|
| Queue an analysis | `POST /api/v1/ai/incidents/{incidentId}/analyze` → 202 with `analysisId` | `aiops-analyst` |
| Read an analysis | `GET /api/v1/ai/analyses/{analysisId}` | `aiops-viewer` or `aiops-analyst` |
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

## Security boundaries

- **Identity:** Keycloak realm `box1`, audience `agentic-operations`, with roles taken from `resource_access/agentic-operations/roles`. Only callers whose `azp` is in `AIOPS_ALLOWED_AZP` (default `agentic-operations-cli`) are accepted.
- **Rejection:** anonymous calls get 401 at the HTTP layer on `/api/v1/ai/*` and `/mcp`. Roles are checked again in the service layer for REST and MCP alike. MCP tool filters are not used for authorization, because they fail open.
- **Tokens:**
  - Tokens are verified locally only; introspection is off.
  - The OIDC provider logger is set to ERROR, because Quarkus logs the raw token at WARN on failure.
- **Evidence scope:** evidence is fetched only for the requested order, with IDs validated against the contract patterns before any call.
  - Upstream fields are allowlisted and their strings are bounded.
  - Kafka payloads keep top-level scalars of 128 characters or fewer.
- **Model:**
  - The model gets no tools.
  - Evidence goes only in the user message, framed as untrusted JSON, and the system policy is fixed.
  - Output must match the schema. Findings without valid citations, and actions outside the allowlist, are dropped and counted.
- **Logs:** prompts, completions, evidence and reasoning output are never logged. Logs carry IDs, outcomes, token counts and durations.
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
| `OIDC_AUTH_SERVER_URL` | box1 realm | Token issuer |
| `AIOPS_ALLOWED_AZP` | `agentic-operations-cli` | Accepted clients, comma-separated |
| `AIOPS_ANALYSIS_*`, `AIOPS_WINDOW_*` | see `AiopsConfig` | Cache size and TTL (200, 24h), concurrency 1, queue 4, window age 6h, 20,000 events, 200 per key |

## Kafka evidence window

- **Topics:** consumer group `agentic-operations` on the five `box1.*.events.v1` topics. DLQs are not read.
- **Read-only:** no offsets are committed and nothing is produced.
- **Restart:** on partition assignment, each partition seeks to now minus the window age, so a restart refills the window.
- **Bad records:** duplicates are dropped by `eventId`, or by `alertId` for threshold alerts. Unusable records are skipped and counted in `aiops_kafka_records_total{outcome="skipped",reason}`.
- **Readiness:** Kafka is not part of readiness.

## Operations runbook

- **Metrics:**
  - `aiops_analyses_total{status}`
  - `aiops_analysis_duration_seconds`
  - `aiops_model_tokens_total{kind}`
  - `aiops_queue_depth`
  - `aiops_mcp_tool_calls_total{tool,decision}`
  - `aiops_evidence_fetch_total{type,status}`
  - `aiops_window_events`
  - `aiops_window_oldest_age_seconds`
  - Kafka client lag, through the Micrometer Kafka binder
- **Model down:** analyses end `MODEL_UNAVAILABLE`, and nothing else is affected. Check the `vllm-test` Deployment in namespace `vllm` (box1-docs#26).
- **Busy (429):** one model call runs at a time, matching vLLM `--max-num-seqs 1`. Raise `AIOPS_ANALYSIS_CONCURRENCY` only together with vLLM.
- **Empty window after a restart:** expected for a few seconds. `aiops_window_oldest_age_seconds` grows as the window refills. If it stays at 0, check the Kafka bootstrap address and consumer group membership.
- **Lost analyses after a restart:** expected (in-memory, single replica). Re-run the analysis. Durable state is box1-docs#97.
- **Rollback:** promote the previous image digest through box1-workloads-dev. The service keeps no state.

## Known limits in v1

- Evidence that is unavailable in v1, each listed in `unknowns`:
  - telemetry alerts: no asset-service read grant;
  - trace contents, metrics and alerts: no read RBAC. The trace ID is still cited as a reference;
  - logs (box1-docs#82).
- The timeline is the first 50 entries, oldest first.
- `fulfillmentRequestId` is known only while its events are in the window (box1-docs#78).
