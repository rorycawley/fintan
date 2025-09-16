# Architecture Principles & Tenets

**Context**  
We build in complex, adversarial systems. Optimize for learning speed, reliability, and total cost of ownership (TCO).

> **Guiding quote (Gall’s Law)**  
> “A complex system that works is invariably found to have evolved from a simple system that worked. A complex system designed from scratch never works and cannot be patched up to make it work. You have to start over with a working simple system.”

**Goals** *(set per product/service)*  
- Read API p95 ≤ **200 ms** (p99 ≤ **500 ms**)  
- Availability ≥ **99.9%** monthly  
- **Daily** deploys; incident **MTTR p50 < 30 min**  
- Cost budgets (e.g., ≤ **$ / 1k requests**, ≤ **$ / active user**)

---

## 1) Optimize for change over reuse
*Because requirements change weekly, therefore prefer designs that are easy to modify even if they duplicate code.*

**Description**  
Prefer designs that are easy to modify tomorrow over abstractions that look elegant today. Duplication is cheaper than the wrong abstraction; let patterns emerge from real usage (3+ call sites) before you “framework-ize.” In reviews, ask: *Will this choice make the next change safer/faster?* Choose feature flags over long-lived branches so you can ship, learn, and refactor with production feedback rather than speculative generalization.

**Tenets**
- Don’t build frameworks until **3+** call sites exist.  
- Prefer **feature flags** to long-lived branches; keep branches short and integrate behind flags.  

**Fitness**
- Lead time (commit → prod) **≤ 1 day**.

---

## 2) Evolve in production (humility over hubris)
*Because complex systems “kick back,” therefore ship thin, observable changes and design for reversibility.*

**Description**  
As Gall put it, *a complex system that works evolves from a simple one that works*. Treat production as the primary learning environment: ship thin vertical slices, observe with telemetry, and keep changes reversible (flags, canaries, rollbacks). Favor parallel runs and shadow traffic over big-bang cutovers. Our north star isn’t being “right up front,” it’s shortening the distance from *idea → evidence → improvement*.

**Tenets**
- Ship **thin vertical slices**; run new paths **in parallel** before cutover.  
- Stage rollouts (canaries) with **easy rollback**; design for **graceful retreat**.

**Fitness**
- ≥ **90%** flagged releases; rollback **< 10 min**.

---

## 3) Boundaries over layers
*Because clear contracts reduce coupling, therefore model cohesive domains with explicit APIs—not shared DBs.*

**Description**  
Organize around cohesive domains with explicit APIs, not generic “n-layer” stacks. Stable contracts reduce coupling and make evolution safe; leaking internals or sharing databases creates hidden dependencies and brittle release trains. Version APIs sparingly and with deprecation plans. Count hops and N+1 calls in hot paths; keep them within budget. Let the database be an implementation detail of the owning service.

**Tenets**
- **No service** reads another service’s tables.  
- Breaking API changes require **versioning + deprecation plan**.  
- Count hops & N+1; keep hot-path hops **≤ 2**.

**Fitness**
- **≥ 95%** cross-domain calls via **HTTP/queue** (vs. DB links).

---

## 4) Evidence over fashion
*Because workloads, SLOs, and cost are the real constraints, therefore decide with measurements, not trends.*

**Description**  
Pick designs from measured workloads, SLOs, and cost—not from trends or anecdotes. Before choosing a tool, write the workload model (RPS, payloads, p95/p99), spike with realistic data, and record results in an ADR with review dates and rollback criteria. Make performance budgets first-class in CI so regressions fail fast. When a claim lacks numbers, treat it as a hypothesis to test.

**Tenets**
- Define workload model (**RPS, data shapes, p95/p99**) before choosing tech.  
- Run realistic spikes; record in **ADRs** with review dates + rollback criteria.  
- **Performance gates** in CI/CD: fail merges on **>10%** p95/CPU/bytes regressions.

**Fitness**
- % changes with ADR **≥ 95%**; CI perf gate pass rate trending **up**.

---

## 5) Data shapes drive design (compute near data)
*Because access patterns dominate cost/latency, therefore shape boundaries and algorithms around them and push compute to where bytes live.*

**Description**  
Access patterns dictate structure, boundaries, and algorithms. Model ownership by read/write shapes and change cadence; push filters/joins/aggregations to where the bytes live. Every hot query should name its predicate, sort, and index. Avoid broad fetch + in-app filtering; control payloads and serialization overhead. Good designs minimize **data movement**, not just CPU time.

**Tenets**
- Express rules as **predicates/joins/group-bys**—no post-query scans.  
- Each hot query has a **matching index/sort**; prune unused indexes.  
- Control **payloads/serialization**; return only needed fields.

**Fitness**
- **≥ 90%** hot queries covered by **indexes**; payload p95 **≤ 64 KB**.

---

## 6) Flow control by design
*Because load is bursty and skewed, therefore make backpressure and fairness explicit.*

**Description**  
Throughput and latency collapse without explicit limits. Design bounded queues, timeouts, retries with budgets, and graceful shedding before traffic arrives. Tune concurrency to cores and I/O; protect fairness with per-tenant quotas and detect hot keys. Measure queue **age** (not just length) and tail latency so you see backpressure early. Resilience is mostly about controlling *how much* work happens simultaneously.

**Tenets**
- **Bounded queues**, timeouts/retries with budgets, **graceful shedding**.  
- Concurrency tuned to cores/I/O; **per-tenant quotas** and hot-key detection.  

**Fitness**
- Queue age **p95 SLO** met; per-tenant **P99/P999** within caps; retry budget respected.

---

## 7) Correctness before cleverness (with cache discipline)
*Because integrity beats speed-that-lies, therefore invariants and atomicity come first.*

**Description**  
Speed that returns the wrong answer is a bug. Make transaction boundaries and invariants explicit; use idempotency and test crash/retry paths. Treat caches as additional copies of truth with defined invalidation strategies (dogpile protection, negative caching). Track read/write amplification budgets so derived views don’t spiral. Split fast-path for the common case with a verified slow-path for correctness.

**Tenets**
- State **transaction boundaries & invariants**; idempotent commands; crash-path tests.  
- Caching has **taxonomy + invalidation tests** (dogpile protection; negative caching).  
- Track **read/write amplification**; enforce fast- vs. slow-path split.

**Fitness**
- Consistency checkers pass; amplification within budget (e.g., writes **≤ 3×** base).

---

## 8) Observability is a feature
*Because MTTR impacts customer trust, therefore instrumentation is part of “done.”*

**Description**  
If you can’t see it, you can’t operate it. Instrument every endpoint with traces, metrics, and structured logs carrying correlation IDs. Keep “golden traces” for hot flows and wire SLOs to alerting and error budgets that gate feature work. Dashboards and runbooks are acceptance criteria, not extras. Aim for the shortest path from “weird” → “root cause” → “fix.”

**Tenets**
- Every endpoint emits **traces, metrics, structured logs** with **correlation IDs**.  
- **Golden traces** maintained per hot path; **error budgets** gate feature work.

**Fitness**
- Incident **MTTR p50 < 30 min**; **100%** endpoints traced.

---

## 9) Secure and compliant by default
*Because trust is table stakes, therefore security/compliance are built-in, not bolted-on.*

**Description**  
Bake security into design, not as an afterthought. Enforce least-privilege IAM, short-lived creds, encryption in transit/at rest, and audited access paths. Keep PII in designated services with reviewed data contracts and retention policies. Treat boundary changes as security events (threat model, review, tests). Automation (linting, CI checks, secrets scanning) makes the secure path the easy path.

**Tenets**
- **Least-privilege IAM**; no **long-lived human creds**.  
- **PII** stays in designated services; data contracts reviewed; encryption **in transit/at rest**.  
- Security reviews for **boundary changes**; automated linting/secret scanning.

**Fitness**
- **0** criticals from periodic audits; secret **age & access** baselines met.

---

## 10) Cost is a first-class constraint
*Because efficiency compounds, therefore design and operate to TCO budgets.*

**Description**  
Costs are engineering signals, not just finance concerns. Make $/req and $/user visible next to latency and error rates; attribute spend to services and features. Prefer managed services when they improve SLOs or reduce TCO; otherwise justify the build/operate cost. Reduce waste by batching, compressing payloads, and eliminating unnecessary hops. Trend costs down over time as load grows and designs mature.

**Tenets**
- **Managed services** unless they blow SLOs or cost.  
- **Batch/async** where user latency isn’t visible.  
- Dashboard **$/req** and **$/user** next to latency.

**Fitness**
- Cost per active user trends **down** monthly; cost SLOs **met**.

---

### Non-Goals / Anti-Patterns
- Microservices/polyglot persistence **“because industry.”**  
- Broad fetch → in-app filtering on **hot paths**.  
- **TTL-only** caches for correctness-critical data.

### Exceptions
- Temporary cross-table/service reads during **migrations** (time-boxed; ADR’d).  
- Exceed hop/fan-out budgets **only** with a dated rollback plan + perf evidence.

### Operationalization
- **ADR**: Context → Options → Decision → Consequences → Metrics/SLOs → Review date → Owner.  
- **Review checklist**: Pick **3–5** relevant principles; record pass/fail; attach traces/benchmarks.

---

# Architectures that Fit These Principles

## Baseline Architecture (Recommended Default)
Use a **Modular Monolith** with **Hexagonal (Ports & Adapters)** boundaries and **DDD** inside. Keep one relational database per domain (start with a single DB), add a **Transactional Outbox** for reliable events, and build **selective read models** and **caches** off the stream. Optionally add **BFFs** per client and **edge workers/CDN** for request shaping and caching. This maximizes change velocity, minimizes distribution, and cleanly supports reversibility, observability, and cost controls.

```mermaid
graph LR
  subgraph Client
    Web[Web] --- Mobile[Mobile]
  end

  Web --> BFF[BFF (optional)]
  Mobile --> BFF

  BFF --> App[App Core (Modular Monolith)]
  CDN[CDN / Edge Workers] -.cache/ab-> BFF

  subgraph App Core (Hexagonal)
    Ports[Ports/Adapters] --- Domains[Domain Modules (Bounded Contexts)]
  end

  App --> DB[(Relational DB)]
  App --> Outbox[(Transactional Outbox)]
  Outbox --> Stream[(Event Stream / Queue)]

  Stream --> RM[(Read Models / Materialized Views)]
  Stream --> Cache[(Caches / Search Index)]
  RM --> BFF
  Cache --> BFF

  subgraph Cross-Cutting
    Obs[Observability: Traces/Metrics/Logs]
    Sec[Security: mTLS/IAM/PII Enclaves]
    Cost[Cost Panels: $/req, $/user]
  end

  App -.emit.-> Obs
  BFF -.emit.-> Obs
  DB -.audit.-> Sec
  App -.enforce.-> Sec
  App -.report.-> Cost
```

### Why this fits the principles
- **Change > Reuse**: Modules + ports localize change; flags/canaries make it reversible.  
- **Boundaries > Layers**: Domain contracts are explicit; DB is an implementation detail.  
- **Compute Near Data**: Pushdown via SQL, indexes, MVs; avoid post-query scans.  
- **Flow Control**: Async work via queue stages; bounded concurrency and backpressure.  
- **Correctness**: Outbox ensures durable events; caches with explicit invalidation.  
- **Observability & Security**: Tracing/logging everywhere; least-privilege IAM, PII enclaves.  
- **Cost**: Managed services first; dashboards for $/req next to latency.

---

## When to Introduce Other Styles
- **BFFs** — When clients need tailored payloads or to kill chatty UIs.  
- **SEDA / Queue Stages** — For bursty/heavy compute; measure queue *age* p95.  
- **Selective CQRS** — When read/write shapes diverge significantly; keep writes strongly consistent.  
- **Cell/Shard Architecture** — For multi-tenant isolation, blast-radius control, or regional scaling.  
- **Serverless/Edge** — For spiky or edge-heavy workloads; measure cold starts and egress.  
- **Microservices** *(surgical, domain-aligned)* — Only when numbers say so (scaling, cadence conflicts, regulatory isolation).

---

## Service Extraction Checklist (Go/No-Go)

> Use this as a gate before splitting the monolith. All **Must** items must pass; **Should** items need strong justification if they don’t.

### Must
- **Clear Domain Boundary**: Module owns its **own tables/events**; no cross-domain ACID transactions required.  
- **SLO Safety**: Adding a hop keeps hot-path **p95 within budget** (e.g., ≤ 200 ms). Perf harness proves it.  
- **Deploy Cadence Conflict**: Module needs independent releases (e.g., ≥ 3/week) and is **regularly blocked** by unrelated changes.  
- **Operational Readiness**: Contract tests, golden traces, and dashboards exist; **on-call** ownership is defined.  
- **Migration Plan**: **Strangler Fig** with dual-run/shadow traffic, canary stages, and **tested rollback**.  
- **Cost Model**: Forecasted **$/req** and infra deltas are within budget; egress/storage multipliers accounted for.

### Should
- **Scaling Isolation**: Module’s CPU/IO profile or data volume differs enough to warrant independent scaling.  
- **Security/Compliance**: PII enclave/regulatory boundary benefits from isolation and audited access paths.  
- **Data Movement**: Split **reduces bytes moved** or serialization overhead across hot paths.  
- **Team Boundaries**: Clear owning team with **2+ maintainers**; reduced cross-team contention is expected.

### Anti-Signals (Do **not** extract if any apply)
- Frequent cross-module **transactions** or synchronous **choreography** required.  
- Split **increases** hop count on the **hot path** beyond budget (e.g., > 2 hops).  
- Goal is primarily **tech churn** (“because microservices”) without a workload/SLO/cost driver.  
- You don’t have **observability** to measure pre/post SLO and $/req.

---

## Extraction Playbook (Strangler Fig)
1. **Stabilize Contract** — Define and freeze the module’s API (OpenAPI/proto); add contract tests.  
2. **Isolate Data** — Move owned tables to a separate schema; remove cross-domain reads; add a data access layer.  
3. **Outbox & Events** — Publish domain events via **transactional outbox**; build/adjust subscribers.  
4. **Shadow Traffic** — Mirror read/write traffic to the new service; compare responses & side effects.  
5. **Progressive Cutover** — 1% → 10% → 50% → 100% behind a **feature flag/canary**; monitor p95/p99, error budget, $/req.  
6. **Rollback Ready** — Pre-compute and test rollback steps; keep dual-write/dual-read until deltas **< 0.1%**.  
7. **Debt Cleanup** — Remove flags, dead code, and old pathways; update ADR with final state and lessons.

---

## Quick Style Selector (Cheat Sheet)
- **Default**: Modular Monolith (Hexagonal + DDD) + Outbox → Stream → Read Models.  
- **Chatty UI or payload bloat?** Add **BFF** and payload schemas; push filtering/sorting to storage.  
- **Bursty workloads or long-running tasks?** Add **SEDA** stages with bounded queues and idempotent handlers.  
- **Divergent read vs. write needs?** Apply **selective CQRS**; keep writes strongly consistent.  
- **Tenant isolation/blast radius?** Move toward **cell/shard** topology.  
- **Sporadic or edge-heavy work?** Use **serverless/edge** (measure cold starts/egress).

---

### ADR Snippet for Service Extraction (Template)
```md
# ADR: Extract <Module> into Service

**Context**  
Hot paths: <endpoint(s)>, current p95/p99, hops, $/req. Conflicts: <deploy cadence, team ownership>. Data: <tables/events owned>.

**Decision**  
Extract <Module> as <Service>, contract <v1>, storage <engine>, deployment <region/cell>.

**Options Considered**  
1) Stay in monolith (optimize)  
2) Extract read model only (CQRS)  
3) Full extraction (chosen)

**Consequences**  
+ Expected: p95 −<X> ms on <flows>, reduced contention, isolation for <PII/scale>.  
− Added hop for <non-hot> paths, new on-call surface.

**Measurements / Budgets**  
- p95 target: <value> (must meet under canary at ≥10% traffic)  
- Hop budget: ≤ 2 on hot path  
- Cost: ≤ <$/req> and <$/user>  
- Error budget: <SLO, burn rate>  

**Plan** (Strangler)  
Contract freeze → schema isolation → outbox → shadow → canary (1/10/50/100%) → rollback tested → cleanup.

**Review Date**  
<YYYY-MM-DD>
```
