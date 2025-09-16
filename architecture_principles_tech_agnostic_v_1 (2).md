# Architecture Principles – Tech‑Agnostic v1.1

**Purpose**  
Make better decisions faster. These principles explain **what to do** and **why**, with concrete fitness checks.

**Scope**  
All product, platform, and data work. Exceptions require an ADR with rollback.

## Precedence (when principles conflict)
**Order:** Correctness → Security/Privacy → Reliability → Observability → Operability → Cost → Speed of change → Reuse

**Why**  
This order minimizes user harm and long‑term risk. Shipping fast but wrong or insecure creates permanent costs. We first protect truth and trust, then ensure the system stays up and diagnosable, then optimize for ease of operations and money, and only then chase speed and reuse.

**What each means**
- **Correctness**: Data integrity and invariant‑preserving behavior. Never ship known wrong results; prefer a slower right answer over a fast wrong one.  
- **Security/Privacy**: Least privilege, safe handling of secrets and PII, compliance, and auditable access. If user data is at risk, stop and fix.  
- **Reliability**: Meeting SLOs for availability and latency; graceful degradation and safe rollback paths.  
- **Observability**: The ability to detect, trace, and diagnose issues quickly (metrics, logs, traces, IDs). If we can’t see it, we can’t keep SLOs.  
- **Operability**: Ease of running the system—simple deploys, backpressure, quotas, runbooks, on‑call health.  
- **Cost**: Total cost of ownership ($/req, $/user, infra + people time). Optimize without hurting the higher priorities.  
- **Speed of change**: Lead time, batch size, flags/canaries, and reversibility that keep delivery fast and safe.  
- **Reuse**: Shared libraries/frameworks after proven need (≥3 call sites). Useful, but last.

**How to use this order**
1) Identify the impacted dimensions and **user impact/SLO risk**. Anything affecting a higher item wins.  
2) Prefer **reversible** options (flags/canaries) when trade‑offs are close.  
3) Time‑box exceptions with an **ADR** (owner, exit criteria, review date).  
4) Record the trade‑off in the ADR and link the **fitness metrics** you’ll watch.

**Examples**
- A fix increases infra cost by 15%: **Correctness > Cost** → ship fix now; open a cost‑reduction ticket.  
- Proposing a shared DB to ship faster: **Security/Privacy + Boundaries > Speed** → expose an API; no cross‑service table reads.  
- Dropping logs to save CPU: **Observability > Cost** → keep essential telemetry; optimize sampling/retention instead.  
- Deadline pressures to ship without rollback: **Reliability/Operability > Speed** → slice scope and ship behind a flag.

**Fitness**  
ADRs that cite Precedence must list: impacted dimensions, user/SLO impact, the chosen trade‑off, and the rollback plan. 

**Rule of thumb**  
*Never ship fast‑wrong or fast‑insecure. Make it right, safe, and observable; then make it cheap and fast; reuse last.*

**How to apply**  
For any change: pick the **3–5 most relevant** principles, state how you meet **Do**, attach evidence for **Fitness**, and capture the decision in an ADR.

---

## 0) SLOs & users first (make reliability explicit)
**Why**: Users feel latency/availability, not internals.  
**Do**
- Define SLOs per endpoint/flow (availability, p95/p99 latency, durability).  
- Agree **RTO/RPO** and test via failure drills.  
- Use **error budgets** to gate risky changes.  
**Fitness**: 100% public endpoints have SLOs; monthly SLO report; **GameDay** at least quarterly.

---

## 1) Optimize for change over reuse
**Why**: Wrong abstractions are expensive; change is constant.  
**Do**
- Duplicate until there are **3+** call sites; then extract.  
- Ship behind **feature flags**; keep branches short.  
- In review ask: *Will this make the next change faster/safer?*  
**Fitness**: Lead time (commit→prod) **≤ 1 day** for standard changes.

---

## 2) Evolve in production (humility over hubris)
**Why**: Reality beats theory; reversible steps let us learn safely.  
**Do**
- Ship **thin vertical slices**; run new paths **in parallel** (shadow/dual‑run).  
- **Canary** releases with **fast rollback** (<10 min).  
- Prefer data/telemetry to big‑bang cutovers.  
**Fitness**: ≥ **90%** releases behind flags/canaries; median rollback < **10 min**.

---

## 3) Boundaries over layers
**Why**: Clear contracts reduce coupling and unlock independent change.  
**Do**
- **No service** reads another service’s tables.  
- Breaking changes require **versioned APIs + deprecation window**.  
- Keep hot‑path hops **≤ 2**; avoid N+1 on hot endpoints.  
**Fitness**: ≥ **95%** cross‑domain traffic via **HTTP/queue** (not DB links).

---

## 4) Evidence over fashion
**Why**: Workloads and SLOs—not trends—should pick our tools.  
**Do**
- Write the **workload model** (RPS, data shapes, p95/p99) before choosing tech.  
- Spike with realistic data; record results, review date, and rollback criteria in an **ADR**.  
- Add **CI perf gates** that fail merges on **>10%** regression (p95/CPU/bytes).  
**Fitness**: ≥ **95%** of changes have ADRs; CI perf‑gate pass rate trending **up**.

---

## 5) Data shapes drive design (compute near data)
**Why**: Moving bytes is the hidden cost; access patterns should shape design.  
**Do**
- Express rules as **predicates/joins/group‑bys**; avoid post‑fetch scans.  
- Ensure every **hot query has a matching index/sort**; prune unused indexes.  
- Return only needed fields; control serialization and payload size.  
**Fitness**: ≥ **90%** hot queries indexed; payload **p95 ≤ 64 KB**.

---

## 6) Flow control by design
**Why**: Bursty load collapses systems without backpressure and fairness.  
**Do**
- Use **bounded queues**, timeouts/retries with budgets, and graceful shedding.  
- Tune concurrency to cores/I/O; enforce **per‑tenant quotas**; detect hot keys.  
- Measure **queue age** and tail latency; alert on budgets.  
**Fitness**: Queue‑age **p95 SLO** met; per‑tenant **p99/p999** within caps; retry budget respected.

---

## 7) Correctness before cleverness (cache discipline)
**Why**: Fast‑wrong is wrong; caches add copies of truth that must be controlled.  
**Do**
- State **transaction boundaries & invariants**; make commands **idempotent**; test crash/retry paths.  
- Define **cache taxonomy & invalidation** (dogpile protection, negative caching).  
- Track **read/write amplification**; keep a verified **slow‑path** beside the fast path.  
**Fitness**: Consistency checkers pass; writes **≤ 3×** base; cache invalidation tests green.

---

## 8) Observability is a feature
**Why**: You can’t operate what you can’t see; MTTR drives trust.  
**Do**
- Emit **traces, metrics, structured logs** with **correlation IDs** for every endpoint.  
- Maintain **golden traces** for hot paths; wire SLOs to alerts and error budgets.  
- Include **dashboards & runbooks** in “done.”  
**Fitness**: **100%** endpoints traced; incident **MTTR p50 < 30 min**.

---

## 9) Secure & private by default
**Why**: Trust is table stakes; insecure defaults multiply risk.  
**Do**
- Enforce **least privilege**, short‑lived creds, encryption **in transit/at rest**.  
- Keep **PII** in designated services with contracts, minimization, and retention.  
- Threat‑model boundary changes; automate linting and **secrets scanning** in CI.  
**Fitness**: **0** criticals in audits; secrets **age/access** within baseline.

---

## 10) Cost is a first‑class constraint
**Why**: Efficiency compounds; waste steals from features and reliability.  
**Do**
- Prefer **managed services** when they improve SLOs or reduce TCO.  
- **Batch/async** where user latency isn’t visible; **compress** payloads; eliminate extra hops.  
- Show **$/req** and **$/user** next to latency on dashboards.  
**Fitness**: Cost per active user trending **down**; cost SLOs **met**.

---

## 11) Reliability & disaster readiness
**Why**: Failures are normal; graceful degradation protects users.  
**Do**
- Use **multi‑AZ** for stateful systems; document region **failover**.  
- Provide **degraded modes** for core journeys.  
- Back up and **practice restore**; measure restore time.  
**Fitness**: **RTO/RPO** met in drills; region failover passes **≥ annually**.

---

## 12) Ownership & paved road
**Why**: Consistency lowers cognitive load and speeds delivery.  
**Do**
- Default to **templates** (logging, metrics, CI gates) and a curated stack.  
- Each service has an **owner, on‑call**, and **runbooks**.  
- ADR required for deviations with a support plan.  
**Fitness**: ≥ **90%** services on paved road; on‑call SLOs met (time‑to‑ack, alerts per shift).

---

## 13) Documentation as code
**Why**: Shared context prevents drift and re‑discovery.  
**Do**
- Version **C4‑lite diagrams** and data flows with code.  
- Validate **contracts** (OpenAPI/IDL/schema) in CI; flag breaking changes.  
- Treat **runbooks & dashboards** as part of “done.”  
**Fitness**: Docs updated in the same PR for ≥ **95%** changes; schema‑drift alerts **= 0**.

---

# Operationalization

### ADR template
**Context → Options → Decision → Consequences → Metrics/SLOs → Rollback criteria → Review date → Owner**

### Review checklist (1 page)
- [ ] Pick **3–5** relevant principles.  
- [ ] Link workload model (RPS, payloads, p95/p99).  
- [ ] Evidence attached (traces/benchmarks/cost).  
- [ ] Rollback + flag plan stated.  
- [ ] Contracts/diagrams/runbooks updated.  
- [ ] SLO/error‑budget impact acknowledged.  
- [ ] Security/PII review if boundaries/data change.  
- [ ] Fitness metrics set; owner assigned.

### Scorecard (map to dashboards)
| Principle | Primary metric(s) |
|---|---|
| 0 SLOs & users first | Availability %, p95/p99, RTO/RPO drill results |
| 1 Change over reuse | Lead time, change‑failure rate |
| 2 Evolve in prod | % flagged/canary, rollback time |
| 3 Boundaries over layers | % cross‑domain via API/queue, hot‑path hop count |
| 4 Evidence over fashion | ADR coverage %, CI perf‑gate trend |
| 5 Data shapes | Index coverage %, payload p95 |
| 6 Flow control | Queue‑age p95, retry budget, per‑tenant p99/p999 |
| 7 Correctness | Consistency checks, amplification budget |
| 8 Observability | Endpoint trace coverage, MTTR p50 |
| 9 Security & privacy | Audit criticals, secret age/access baseline |
|10 Cost | $/req, $/user, cost SLO adherence |
|11 Reliability/DR | RTO/RPO met, failover drill pass |
|12 Ownership/paved road | % services on paved road, on‑call health |
|13 Docs as code | % PRs updating docs/contracts, schema‑drift alerts |

### Exception process
- File an ADR tagged **Exception** with time limit and exit criteria.  
- Create a **Principle‑debt** ticket linked to measurable risk.  
- Review exceptions **monthly**.

**Glossary**: SLO/SLA/Error budget, RTO/RPO, GameDay, Paved road, Golden trace.



---

# Non‑Functional Requirements — Demo .NET Modular Monolith

**Context**  
Modular monolith using **Vertical Slice Architecture**, **DDD**, **CQRS (no Event Sourcing)**, **Outbox** for async integration, and **domain events** to trigger background work. Goals: **easy to change**, **easy to reason about in production**, **fast**, **reliable**, **secure**.

> Numbers below are starter targets for a demo scale. Tune in your ADR to match real workloads while keeping the **precedence** order.

---

## Performance (PERF)
**PERF‑1 – Request latency (reads)**  
**Target**: p95 ≤ **100 ms**, p99 ≤ **250 ms** for hot read endpoints at 1× baseline load.  
**Measure**: HTTP server timings; OpenTelemetry histogram per endpoint.  
**Notes**: Read model uses pre‑joined shapes; responses return only needed fields; every hot query indexed.

**PERF‑2 – Command latency (writes)**  
**Target**: p95 ≤ **150 ms**, p99 ≤ **400 ms** for standard commands (payload ≤ 4 KB).  
**Measure**: Command pipeline timer around handler; include DB round‑trip and Outbox insert.  
**Notes**: Single transaction per command; avoid chatty ORM patterns; batch derived updates async.

**PERF‑3 – Throughput**  
**Target**: Sustain **300 RPS** average and **1000 RPS** 1‑minute bursts without violating PERF‑1/2.  
**Measure**: k6/Locust load test; record SLI time series.  
**Notes**: Enable **Server GC**, minimize allocations, use response compression for text/json.

**PERF‑4 – Payload & serialization**  
**Target**: Response size p95 ≤ **64 KB**; request body p95 ≤ **32 KB**.  
**Measure**: Middleware to log sizes; histogram.  
**Notes**: System.Text.Json source‑gen where applicable.

**PERF‑5 – DB query budgets**  
**Target**: Hot reads ≤ **2 queries**; hot writes ≤ **1 query + outbox insert**; query p95 ≤ **15 ms**.  
**Measure**: EF Core interceptor to count/trace queries; SQL timing.

---

## Scalability (SCAL)
**SCAL‑1 – Horizontal scale‑out**  
**Target**: API is stateless; scales linearly to **4 instances** with ≤ **20%** efficiency loss.  
**Measure**: Load test at 1,2,4 instances; compare RPS/latency.  
**Notes**: Sessionless auth; cache is per‑request or distributed.

**SCAL‑2 – Worker throughput**  
**Target**: Background processors scale by **partition key** (e.g., tenant/aggregate); no hot‑key exceeds **2×** median processing time.  
**Measure**: Per‑partition queue metrics; p95/p99 per key.  
**Notes**: Use **IHostedService**/**BackgroundService** with bounded channels; parallelism capped by CPU/I/O.

**SCAL‑3 – Startup & readiness**  
**Target**: Cold start ≤ **5 s**; readiness becomes **true** only after DB, Outbox, and message transport are reachable.  
**Measure**: Startup probe timing; health checks.

---

## Availability (AVAIL)
**AVAIL‑1 – Service availability**  
**Target**: Monthly API availability **≥ 99.9%** for public endpoints.  
**Measure**: Synthetic checks + server SLI (success ratio).  
**Notes**: Rolling deploys; health‑based load balancer; graceful shutdown (drain ≤ **30 s**).

**AVAIL‑2 – Zero‑downtime deploys**  
**Target**: No 5xx spikes during deploys; error budget untouched.  
**Measure**: Compare error rate/latency before vs during rollout.  
**Notes**: Migrate DB with **expand/contract**; gate risky paths behind flags.

---

## Reliability (RELY)
**RELY‑1 – Outbox delivery**  
**Target**: Commit‑to‑publish delay p95 **≤ 30 s**, p99 **≤ 120 s**; delivery success **≥ 99.99%**.  
**Measure**: Timestamps on outbox row insert and publish; success ratio.  
**Notes**: Exactly‑once effects via **idempotent handlers** and **de‑dupe** by message ID.

**RELY‑2 – Retry budgets & DLQ**  
**Target**: Max **3** exponential retries per message, then DLQ; DLQ rate **≤ 0.01%** of processed.  
**Measure**: Per‑reason counters; alert on budget breach.  
**Notes**: Include jitter; poison‑message quarantine with replay tool.

**RELY‑3 – Queue age & backpressure**  
**Target**: Queue age p95 **≤ 30 s** under peak; shed/slow producers when age > **60 s**.  
**Measure**: Age histogram; producer 429/503 counters.  
**Notes**: Bounded channels; per‑tenant quotas to protect fairness.

**RELY‑4 – Crash/restart safety**  
**Target**: No message loss or duplicate side‑effects after **kill‑9** of workers.  
**Measure**: Chaos test in CI; invariants checker passes.  
**Notes**: Handlers idempotent; transactional outbox + **at‑least‑once** semantics.

---

## Security (SEC)
**SEC‑1 – Transport & secrets**  
**Target**: TLS **1.2+** end‑to‑end; secrets stored in a secret manager; **no long‑lived human creds**.  
**Measure**: TLS scanner; CI secret‑scan; infra policy checks.

**SEC‑2 – AuthN/Z**  
**Target**: JWT/OIDC with scopes/roles; **deny‑by‑default**.  
**Measure**: AuthZ tests for each endpoint; 100% of endpoints require policy.  
**Notes**: Use **[Authorize]** with policy names; require anti‑forgery for cookie flows.

**SEC‑3 – Data protection**  
**Target**: PII confined to designated slices; encryption at rest; data minimization enforced.  
**Measure**: Schema tags for PII; weekly scan for drift.  
**Notes**: Map PII to DTOs; log redaction; audit access.

**SEC‑4 – AppSec hygiene**  
**Target**: OWASP ASVS **L2** baseline; SAST/SCA gate builds; high‑severity vulns **= 0**.  
**Measure**: Pipeline reports; dependency‑update cadence.

---

## Maintainability (MAINT)
**MAINT‑1 – Change lead time**  
**Target**: Median commit→prod **≤ 1 day**.  
**Measure**: DORA metrics from CI/CD.  
**Notes**: Trunk‑based dev; **feature flags** over long‑lived branches.

**MAINT‑2 – Vertical slice boundaries**  
**Target**: Handlers own their data access; cross‑slice calls go through interfaces/domain events—**no direct table reads** across slices.  
**Measure**: Architecture tests (e.g., **NetArchTest**) enforce allowed references.

**MAINT‑3 – Complexity & size**  
**Target**: Handler cyclomatic complexity **< 15**; file length **< 300** LOC.  
**Measure**: Analyzer in CI; block on regressions > **10%**.

**MAINT‑4 – Docs as code**  
**Target**: Each slice has a **README** (purpose, endpoints, data shapes) and updated **C4‑lite** diagram in the repo.  
**Measure**: PRs touching code also touch docs **≥ 95%**.

**MAINT‑5 – Tests**  
**Target**: Unit tests for handlers and domain; integration tests for endpoints; mutation score **≥ 60%** on critical domain libs.  
**Measure**: CI test reports; mutation testing tool.

---

## Observability & Operability (OBS)
**OBS‑1 – Tracing & logging**  
**Target**: **100%** endpoints and message handlers emit **OpenTelemetry** traces with correlation IDs; structured JSON logs.  
**Measure**: Trace coverage; log schema check.  
**Notes**: Use `ActivitySource`/`ILogger` scopes; propagate `traceparent` via events.

**OBS‑2 – Golden traces**  
**Target**: Maintain golden traces for top **3** user flows and one async pipeline.  
**Measure**: Periodic re‑capture; compare spans and budgets.

**OBS‑3 – SLO dashboards & alerts**  
**Target**: Dashboards display availability, latency (p95/p99), error rate, queue age, outbox delay, $/req. Alerts tied to error budgets.  
**Measure**: Dashboard checklists; alert tests.

---

## Verification plan
- **Load & soak**: k6/Locust scenarios covering hot reads/writes and 1‑minute burst; assert PERF & AVAIL SLOs.  
- **DB profiling**: EF Core interceptor + SQL statistics to enforce PERF‑5.  
- **Chaos**: kill/restart workers during message processing; verify RELY‑4.  
- **Security**: SAST/SCA/secret‑scan in CI; authz e2e tests per endpoint.  
- **Architecture tests**: enforce MAINT‑2 references; fail build on violations.  
- **Observability checks**: unit test for `Activity` creation; smoke test for trace propagation.

## Operational notes (.NET specifics)
- Use **Minimal APIs** or thin controllers → MediatR‑style handlers per slice.  
- **Outbox**: EF Core transaction boundary; background publisher with exactly‑once via de‑dupe table/hash.  
- **Background concurrency**: `Channel<T>` or queue client with bounded capacity; exponential backoff with jitter.  
- **Health checks**: `IHealthChecksBuilder` for DB, outbox publisher, and message transport; expose `/health/ready` & `/health/live`.  
- **Config**: strongly‑typed `IOptions<T>`; validate on startup; fail fast.  
- **Analyzers**: enable nullable reference types, code‑style analyzers, and architecture tests in CI.

