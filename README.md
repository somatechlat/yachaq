# SomaBrain

**SomaBrain is an opinionated cognitive memory runtime that gives your AI systems long-term memory, contextual reasoning, and live adaptation—backed by real math, running code, and production-ready infrastructure.**  
It ships as a FastAPI service with a documented REST surface, BHDC hyperdimensional computing under the hood, and a full Docker stack (Redis, Kafka, OPA, Postgres, Prometheus) so you can test the whole brain locally.

---

## Highlights

| Capability | What it actually does |
|------------|------------------------|
| **Binary Hyperdimensional Computing (BHDC)** | 2048‑D permutation binding, superposition, and cleanup with spectral verification (`somabrain/quantum.py`). |
| **Tiered Memory** | Multi-tenant working memory + long-term storage coordinated by `TieredMemory`, powered by governed `SuperposedTrace` vectors and cleanup indexes. |
| **Contextual Reasoning** | `/context/evaluate` builds prompts, weights memories, and returns residuals; `/context/feedback` updates tenant-specific retrieval and utility weights in Redis. |
| **Adaptive Learning** | Decoupled gains and bounds per parameter, configurable via settings/env, surfaced in Prometheus metrics and the adaptation state API. |
| **Observability Built-In** | `/health`, `/metrics`, and structured logs. Adaptation behaviour emits explicit metrics so you can see when the brain deviates. |
| **Hard Tenancy** | Each request resolves a tenant namespace (`somabrain/tenant.py`); quotas and rate limits are enforced before the memory service is called. |
| **Complete Docs** | Four-manual documentation suite (User, Technical, Development, Onboarding) plus a canonical improvement log (`docs/CANONICAL_IMPROVEMENTS.md`). |

---

## Math & Systems at a Glance

### Hyperdimensional Core
- **QuantumLayer** (`somabrain/quantum.py`) implements BHDC operations (bind, unbind, superpose) with deterministic unitary roles and spectral invariants.
- **Numerics** (`somabrain/numerics.py`) normalises vectors safely with dtype-aware tiny floors.
- **SuperposedTrace** (`somabrain/memory/superposed_trace.py`) maintains a governed superposition with decay (\(\eta\)), deterministic rotations, cleanup indexes (cosine or HNSW), and now logs when no anchors match.

### Retrieval & Scoring
- **ContextBuilder** (`somabrain/context/builder.py`) embeds queries, computes per-memory weights, and adjusts the temperature parameter \(\tau\) based on observed duplicate ratios.
- **UnifiedScorer** (`somabrain/scoring.py`) blends cosine, frequent-directions projections, and recency; weights and decay constants can be tuned via `scorer_*` settings and are exposed through diagnostics.
- **TieredMemory** (`somabrain/memory/hierarchical.py`) orchestrates working and long-term traces with configurable promotion policies and safe handling when cleanup finds no anchor.

### Learning & Neuromodulation
- **AdaptationEngine** (`somabrain/learning/adaptation.py`) provides decoupled gains for retrieval (α, β, γ, τ) and utility (λ, μ, ν), driven by tenant-specific feedback. Configured gains/bounds are mirrored in metrics and the adaptation state API.
- **Neuromodulators** (`somabrain/neuromodulators.py`) supply dopamine/serotonin/noradrenaline/acetylcholine levels that can modulate learning rate (enable with `SOMABRAIN_LEARNING_RATE_DYNAMIC`). Tune noradrenaline feedback via `SOMABRAIN_NEURO_LATENCY_SCALE` and the new `SOMABRAIN_NEURO_LATENCY_FLOOR`, keeping the latency-based term finite even if latency drops toward zero.
- **Metrics** (`somabrain/metrics.py`) track per-tenant weights, effective LR, configured gains/bounds, and feedback counts.

---

## Runtime Topology

```
HTTP Client
   │  /memory/remember /memory/recall /context/evaluate /context/feedback …
   ▼
FastAPI Runtime (somabrain/app.py)
   │   ├─ Authentication & tenancy guards
   │   ├─ ContextBuilder / Planner / AdaptationEngine
    │   ├─ MemoryService (HTTP)
   │   └─ Prometheus metrics, structured logs
   ▼
Working Memory (MultiTenantWM) ──► Redis
Long-Term Memory ───────────────► External memory HTTP service
OPA Policy Engine ──────────────► Authorization decisions
Kafka ──────────────────────────► Audit & streaming
Postgres ───────────────────────► Config & metadata
Prometheus ─────────────────────► Metrics export
```

Docker Compose (`docker-compose.yml`) starts the API plus Redis, Kafka, OPA, Postgres, Prometheus, and exporters. The API always binds host port 9696 for consistency:

- API: host 9696 -> container 9696
- Redis: 30100 -> 6379
- Kafka broker: 30102 -> 9092 (internal advertised as somabrain_kafka:9092)
- Kafka exporter: 30103 -> 9308
- OPA: 30104 -> 8181
- Prometheus: 30105 -> 9090
- Postgres: 30106 -> 5432
- Postgres exporter: 30107 -> 9187
- Schema registry: 30108 -> 8081
- Reward producer: 30183 -> 8083
- Learner online: 30184 -> 8084

Memory service: by default the API points to `http://localhost:9595` (`memory_http_endpoint` in `common/config/settings.py`). Override `SOMABRAIN_MEMORY_HTTP_ENDPOINT` and `SOMABRAIN_MEMORY_HTTP_TOKEN` if your memory backend runs elsewhere or requires auth.

**Important:** *SomaBrain never runs an internal memory container.* All agents, services, and tests **must** use the external memory service reachable at `http://localhost:9595`. The service should already be running on the host machine; you can verify it with:

```bash
curl -f http://localhost:9595/health
```

If the health check reports `kv_store:false` or any component flag as false, the stack will refuse to start and the integration tests will fail – this is intentional to enforce the **real‑server‑only** rule.

In production the same endpoint is injected via Kubernetes manifests; for local development we rely on the host‑side service.

Note: Kafka’s advertised listener is internal to the Docker network by default. For host-side consumers, run your clients inside the Compose network or add a dual-listener config. For WSL2 or remote clients, set the EXTERNAL listener host before running dev scripts:

```bash
KAFKA_EXTERNAL_HOST=192.168.1.10 ./scripts/dev_up.sh
```

### Kubernetes external ports (NodePort)

Kubernetes defaults to ClusterIP internally. If you need host access without an ingress/controller, enable NodePorts in the Helm chart using a centralized 30200+ range:

- API: 30200 → 9696
- Integrator health: 30201 → 8091
- Segmentation health: 30202 → 8092
- Unified Predictor health: 30203 → 8093 (Consolidated service)
- Reward Producer (optional): 30206 → 8083
- Learner Online (optional): 30207 → 8084

How to enable (values):
- `.Values.expose.apiNodePort=true` → sets API service type to NodePort at `.Values.ports.apiNodePort`
- `.Values.expose.healthNodePorts=true` → exposes all cog-thread health services at their respective NodePorts
- `.Values.expose.learnerNodePorts=true` → exposes learner services at NodePorts

All NodePort numbers are centralized in `infra/helm/charts/soma-apps/values.yaml` under `.Values.ports.*`. Container and target ports remain internal and unchanged.

---

## API Overview

### Core Memory Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Checks Redis, Postgres, Kafka, OPA, memory backend, embedder, and circuit breaker state.
| `POST /memory/remember` | Store a memory with signals (importance, novelty, ttl), attachments, links, and policy tags. Returns coordinate, WM promotion status, and signal feedback.
| `POST /memory/recall` | Retrieve memories via HDC query, filters, and scoring. Returns memories, weights, and retrieval diagnostics.
| `POST /memory/forget` | Delete memories by key/coordinate (tenant-scoped) and emit metrics + audit logs.
| `POST /memory/purge` | Tenant purge endpoint for cleanup/testing (requires admin).
| `GET /memory/stats` | Per-tenant and per-tier memory stats.

### Context & Adaptation

| Endpoint | Description |
|----------|-------------|
| `POST /context/evaluate` | Build a context window from memories + runtime state. Returns context, weights, residuals, diagnostics.
| `POST /context/feedback` | Submit user feedback about context usefulness; updates retrieval + utility weights and emits metrics.
| `GET /context/state` | Inspect adaptation state, configured gains/bounds, and recent feedback summaries.

### Planning & Actions

| Endpoint | Description |
|----------|-------------|
| `POST /plan` | Build a plan from a goal and context (optional).
| `POST /action` | Execute an action (policy-gated) and log results.

### Neuromodulation & Sleep

| Endpoint | Description |
|----------|-------------|
| `POST /neuro/step` | Apply neuromodulators and update effective learning rates.
| `POST /sleep` | Background consolidation / cleanup pass (optional).

### Admin & Operations

| Endpoint | Description |
|----------|-------------|
| `GET /metrics` | Prometheus metrics.
| `GET /health` | Health checks.
| `GET /info` | Build and runtime info.

---

## Quick Start

```bash
# Clone and launch the full stack
git clone <repo>
cd <repo>

# Ensure your memory backend is accessible at http://localhost:9595
curl -f http://localhost:9595/health

# Start API locally with Compose
docker compose up --build

# Check health and scrape metrics
curl -f http://localhost:9696/health
curl -f http://localhost:9696/metrics | head
```

---

## Monitoring (no dashboards)

Prometheus metrics and Alertmanager alerts only. See `docs/technical-manual/monitoring.md`.

Alertmanager playbooks and escalation examples: see `docs/monitoring/alertmanager-playbooks.md`.

---

## Cognitive Threads (Predictors, Integrator, Segmentation)

This repo includes services for predictor threads, segmentation, and integrator logic.

### Benchmarks

See `docs/technical-manual/benchmarks-quickstart.md`.

---

## Documentation

- docs/technical-manual/benchmarks-quickstart.md
- **User Manual** – Installation, quick start, feature guides, FAQ (`docs/user-manual/`).
- **Technical Manual** – Architecture, deployment, monitoring, runbooks, security (`docs/technical-manual/`).
- **Development Manual** – Repository layout, coding standards, testing strategy, contribution workflow (`docs/development-manual/`).
- **Onboarding Manual** – Project context, code walkthroughs, checklists (`docs/onboarding-manual/`).
- **Canonical Improvements** – Living record of all hardening and transparency work (`docs/CANONICAL_IMPROVEMENTS.md`).
- **Cognitive-Thread Configuration** – Feature flags, environment variables, and Helm overrides for the predictor, segmentation, and integrator services (`docs/cog-threads/configuration.md`).
- **Predictor Service API** – Health probe behaviour and Kafka emission contract for the predictor services (`docs/cog-threads/predictor-api.md`).
- **Tau & Entropy Alignment** – See `docs/technical-manual/tau-entropy-alignment.md` for how tau annealing, context diversity heuristic, and entropy caps co-exist.

---

## Contributing & Next Steps

1. Read the [Development Manual](docs/development-manual/index.md) and follow the local setup + testing instructions (`pytest`, `ruff`, `mypy`).
2. Read `docs/technical-manual/architecture.md` before proposing large refactors.
3. Add a short design note to `docs/onboarding-manual/` for any major new subsystem.
4. File issues or update `docs/CANONICAL_IMPROVEMENTS.md` whenever you add a new capability or harden an assumption.

