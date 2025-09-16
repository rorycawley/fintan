# Corporate Registry Requirements Capture Guide

> A compact, end-to-end way to **capture, execute, and trace** requirements for a corporate registry system.  
> Store this file at the root of your repo (e.g., `docs/requirements/guide.md`). All referenced artifacts are source-controlled alongside it.

---

## Principles

- **Executable models first:** Model processes in **BPMN**, decisions in **DMN**; keep both testable and runnable.
- **Plain language + precise terms:** Maintain an **SBVR-lite glossary** so terms mean exactly what legislation intends.
- **Single source of truth:** Keep models as source (`.bpmn`, `.dmn`, `.drawio`, `.md`) — never screenshots.
- **Full traceability:** Every requirement links **Law → Business Rule → Process → Story → Tests → API → Controls**.
- **Stable IDs & versioning:** Model IDs are immutable; use semantic versioning for changes.
- **Automate verification:** CI runs DMN/BPMN unit & path tests and validates OpenAPI/AsyncAPI.

---

## Minimal Deliverables (small but complete)

- **Vision & Scope** — goals, out-of-scope, regulatory drivers.
- **Glossary (SBVR‑lite)** — authoritative definitions.
- **Use Cases** with **Gherkin** acceptance criteria.
- **BPMN** for top flows (Incorporation, Annual Return, Change‑of‑Officers, Strike‑off/Restoration, Name Change, Enforcement case, …).
- **DMN** decision tables (name approval, fee calc, eligibility, late‑fees, strike‑off triggers, restoration).
- **Domain Model** — class/entity diagram + **Data Dictionary**.
- **API Specs** — OpenAPI/AsyncAPI tied to BPMN tasks.
- **NFRs & Compliance Matrix** — mapped to legislation + controls.
- **Traceability Matrix** — links everything above.

---

## Suggested Repo Layout

```
docs/
  requirements/
    guide.md                   # this file
    vision-scope.md
    glossary/
      glossary.md              # SBVR-lite
    use-cases/
      UC-INC-001.md
      UC-ANR-001.md
    bpmn/                      # source .bpmn
      PROC-INC-001.bpmn
    dmn/                       # source .dmn
      DMN-FEE-001.dmn
      DMN-NAME-APPROVAL-001.dmn
    domain/
      class-diagram.drawio
      data-dictionary.md
    api/
      openapi.yaml
      asyncapi.yaml
    compliance/
      compliance-matrix.csv
      nfrs.md
    traceability/
      rtm.csv
tests/
  bdd/
    features/*.feature
  dmn/
    unit/*
  bpmn/
    paths/*
```

---

## ID & Naming Conventions

- **Use Case**: `UC-<FLOW>-<###>` → `UC-INC-001`
- **BPMN Process**: `PROC-<FLOW>-<###>` → `PROC-INC-001`
- **DMN Decision**: `DMN-<TOPIC>-<###>` → `DMN-FEE-001`
- **SBVR Term**: `TERM-<NAME>` → `TERM-BENEFICIAL-OWNER`
- **API Operation**: `API-<HTTP>-<PATH>` → `API-POST-/companies`
- **Gherkin Scenario**: `SCN-<FLOW>-<###>` → `SCN-INC-ACCEPT-001`

> **Rule:** IDs never change; version is tracked in the artifact (`version: 1.2.0`) and in Git tags (e.g., `dmn/DMN-FEE-001@v1.2.0`).

---

# 1) Functional Scope & Behavior

### 1.1 Use Cases & User Stories

**Purpose:** Capture actor goals and flows; ground acceptance in BDD.

**Template (`use-cases/UC-<...>.md`):**
```md
# UC-INC-001 — Incorporate a New Company
**Primary Actor:** Applicant
**Stakeholders:** Registry Officer, Payment Gateway, Sanctions Service
**Trigger:** Applicant submits incorporation request

## Main Success Scenario
1. Applicant provides required company details and officers.
2. System validates completeness and eligibility (DMN-ELIGIBILITY-001).
3. System checks name approval (DMN-NAME-APPROVAL-001).
4. System calculates fees (DMN-FEE-001) and requests payment.
5. Payment confirmed; system assigns company number; files are sealed.
6. Certificate of Incorporation issued; audit event recorded.

## Alternate / Exception Flows
A1. Name rejected → Applicant selects alternative name; loop to step 3.
E1. Payment failure → Retry or abandon; audit failure reason.
E2. Sanctions hit → Case escalated to manual review.

## Acceptance Criteria (BDD)
See `tests/bdd/features/incorporation.feature` (SCN-INC-*).

## Links
BPMN: PROC-INC-001  
Decisions: DMN-ELIGIBILITY-001, DMN-NAME-APPROVAL-001, DMN-FEE-001  
APIs: API-POST-/companies, API-POST-/payments/authorize
```

**Gherkin pattern (`tests/bdd/features/incorporation.feature`):**
```gherkin
Feature: Company incorporation
  As an applicant
  I want to register a company
  So that I can trade legally

  @happy @SCN-INC-ACCEPT-001
  Scenario: Successful incorporation
    Given a unique approved name "RORY TECH LTD"
      And applicant details are complete and valid
      And fees for "private-limited" are calculated
    When the applicant submits the incorporation filing
    Then the filing is accepted
      And a company number is issued
      And a certificate of incorporation is generated
      And an audit event "CompanyIncorporated" is recorded

  @exception @SCN-INC-PAYFAIL-002
  Scenario: Payment failure
    Given all validations pass
      And the payment authorization will fail
    When the applicant pays the incorporation fee
    Then the filing remains pending payment
      And the applicant is notified of the failure
      And an audit event "PaymentFailed" is recorded
```

### 1.2 Process Models (BPMN)

- Model end-to-end flows with **alternate/exception/timeout/compensation** paths.
- Each service task references the **API operation** and **DMN decision** it invokes.
- Include **business error events** for legal rejections (e.g., sanctions, name refusal).

**Reference:** Patterns for complex processes are well-covered here: <https://camunda.com/blog/2023/07/cmmn-patterns-bpmn/>

---

# 2) Decision Logic (Law‑Based Rules)

### 2.1 DMN Decision Tables (with FEEL)

Use **DMN** to encode statutory rules (eligibility, fees, deadlines, penalties). Every table:
- Declares **inputs/outputs** with data types aligned to the **Data Dictionary**.
- Uses FEEL expressions for dates/times (deadlines, late fees), lists (sanctions lists), and calculations.
- Has **unit tests** with edge cases (boundary dates, time zones, “grace period” ends).

**Example stub (`dmn/DMN-FEE-001.dmn`):**
```xml
<!-- Omitted DMN XML for brevity -->
<!-- Decision: DMN-FEE-001 (version 1.2.0) -->
<!-- Inputs: entityType, filingType, isLate; Output: feeAmount -->
```

### 2.2 SBVR‑Lite Glossary

Capture the exact meaning intended by legislation and policy.

**Template (`glossary/glossary.md`):**
```md
# Glossary (SBVR‑lite)
## TERM-BENEFICIAL-OWNER
**Definition:** A natural person who ultimately owns or controls more than 25% of a company or otherwise exercises control via other means.  
**Source/Provenance:** Companies Act 20xx, s.123(4).  
**Notes:** Include indirect holdings via nominees and trusts.
```

### 2.3 Compliance Matrix (Legal Provenance)

Map rules to law so provenance is auditable.

**CSV header (`compliance/compliance-matrix.csv`):**
```csv
LawId,Instrument,Section,ObligationType,RequirementText,RuleId,ProcessId,UseCaseId,TestId,ControlId,Notes
LAW-COMPANIES-20XX,Companies Act,123(4),Obligation,"Definition of beneficial owner",DMN-UBO-001,PROC-INC-001,UC-INC-001,SCN-INC-ACCEPT-001,CTRL-KYC-001,""
```

---

# 3) Domain Modeling

### 3.1 Class/ER Model
Model core entities: **Company, Officer, BeneficialOwner, Filing, Payment, Sanction, Address, Document, AuditEvent**.

### 3.2 Event Storming (DDD)
Discover events and commands/policies.
- **Events:** `CompanyIncorporated`, `FilingSubmitted`, `FilingRejected`, `CompanyStruckOff` …
- **Commands/Policies:** `StartIncorporation`, `ValidateFiling`, `EscalateEnforcement` …

### 3.3 Data Dictionary (align with APIs/DMN)
```md
| Field                 | Type        | Constraints / Notes                                          | Source of Truth |
|-----------------------|-------------|---------------------------------------------------------------|-----------------|
| companyNumber         | string      | Immutable, registry-assigned, format: `[A-Z]{2}\d{6}`        | PROC-INC-001    |
| entityType            | enum        | `private-limited` \| `public-limited` \| `llp`               | Glossary        |
| beneficialOwners[]    | array<UBO>  | Must include control pathway                                 | DMN-UBO-001     |
| filingTimestamp       | datetime    | Legal time source, UTC + offset; eIDAS timestamp if signed   | NFR-TS-001      |
| payment.amount        | decimal(9,2)| Currency ISO 4217; equals DMN-FEE-001 output                 | DMN-FEE-001     |
```

---

# 4) Interfaces

### 4.1 API Contracts

- **OpenAPI (REST)** for synchronous operations; **AsyncAPI (events)** for domain events.
- Tie API operations to BPMN tasks and DMN decisions via IDs in operation descriptions.

**OpenAPI snippet (`api/openapi.yaml`):**
```yaml
paths:
  /companies:
    post:
      operationId: API-POST-/companies
      summary: Create company (PROC-INC-001 task: "Create Company")
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/IncorporationRequest' }
      responses:
        '201':
          description: Created
          headers:
            X-Audit-Event: { schema: { type: string }, description: 'AuditEvent ID' }
```

**AsyncAPI snippet (`api/asyncapi.yaml`):**
```yaml
channels:
  company.events.incorporated:
    subscribe:
      operationId: API-SUB-company.events.incorporated
      message:
        name: CompanyIncorporated
        payload:
          $ref: '#/components/schemas/CompanyIncorporated'
```

### 4.2 Document Templates

- Templates for **forms, receipts, certificates** with fields mapped to Data Dictionary keys.
- Embed the **model IDs** (e.g., `PROC-INC-001`, `DMN-FEE-001`) for traceability.

---

# 5) Quality Attributes / Constraints (NFRs)

Capture as structured items (`nfrs.md`) and test where possible.

**Catalog (examples):**
- **Auditability:** All state changes create an `AuditEvent` with actor, timestamp, before/after.
- **Immutability of filings:** Legal filings are write-once; amendments create new versions.
- **Legal timestamps:** eIDAS/PKI-based trusted timestamps.
- **Data retention:** Per statute; automatic redaction/expiry jobs.
- **PII handling:** Encryption at rest, field‑level access control, purpose limitation.
- **Availability/RPO/RTO:** e.g., `RTO ≤ 1h, RPO ≤ 5m` for filing services.
- **Access control:** Role- and attribute-based; registry officer overrides audited.
- **Performance:** e.g., `P95 submission < 2s`, `P99 name check < 500ms`.
- **Localization:** Multilingual UI artifacts; date/number formatting per locale.

Each NFR links to **controls** (e.g., `CTRL-TS-001`) and **tests** (e.g., chaos/latency tests).

---

## 6) Traceability

Maintain a **Requirements Traceability Matrix (RTM)** that links across artifacts.

**CSV header (`traceability/rtm.csv`):**
```csv
LawId,RuleId (DMN/SBVR),ProcessId (BPMN),UseCaseId,ScenarioId,ApiId,Artifact,TestId,ControlId,Notes
LAW-COMPANIES-20XX,DMN-FEE-001,PROC-INC-001,UC-INC-001,SCN-INC-ACCEPT-001,API-POST-/companies,openapi.yaml@#/paths/~1companies/post,TEST-DMN-FEE-001,CTRL-TS-001,""
```

**Rules:**
- Every **Use Case** has ≥1 **Scenario(s)**.
- Every **Scenario** maps to ≥1 **Process step(s)** and **Decision(s)**.
- Every **Decision** maps to a **Law** (or policy) via **Compliance Matrix**.
- Every **API** operation maps to a **Process task** and **Scenario**.
- Tests reference artifact IDs so CI can verify **completeness** (no orphans).

---

# 7) Testable, Executable Specs

- **BDD (Gherkin)** for end-to-end acceptance.
- **DMN unit tests:** Small input/output fixtures; include boundary tests.
- **BPMN process tests:** Cover happy path, exception, timeout, compensation; assert business error handling.

**DMN unit test stub (`tests/dmn/unit/test_DMN-FEE-001.json`):**
```json
[
  { "name": "private-limited on time", "in": {"entityType":"private-limited","filingType":"incorporation","isLate":false}, "out": {"feeAmount":100.00} },
  { "name": "private-limited late", "in": {"entityType":"private-limited","filingType":"annual-return","isLate":true}, "out": {"feeAmount":150.00} }
]
```

**Process path test idea:**
```md
- Deploy PROC-INC-001@1.3.0 and DMN-FEE-001@1.2.0
- Simulate: valid application → ensure events emitted (`CompanyIncorporated`), docs generated, audit entries present.
- Simulate: payment failure → ensure compensation path triggers and state remains consistent.
```

---

## Definition of Done (per artifact)

- **Use Case:** Actors, triggers, main + alternate flows, linked scenarios, IDs to BPMN/DMN/API.
- **Scenario (BDD):** Given/When/Then complete, deterministic, tagged with IDs, negative paths covered.
- **BPMN:** All tasks/gateways labeled, error/timeout handling modeled, IDs stable, test coverage ≥ 80% of paths.
- **DMN:** Inputs/outputs typed, FEEL validated, unit tests with boundaries, coverage of hit policies.
- **Glossary:** Authoritative source + notes; referenced by data model and rules.
- **API Spec:** Schemas validated; examples present; security schemes defined.
- **NFR:** Measurable target + verification method + control mapping.
- **Traceability:** RTM row(s) added/updated; no orphan artifacts in CI report.

---

## Change Control & Versioning

- **Stable IDs; semantic versioning:** `MAJOR.MINOR.PATCH` per artifact.
- Breaking change → bump **MAJOR** and add migration notes.
- CI checks: 
  - Orphan detection (artifact present but unmapped).
  - Contract breaking API changes.
  - DMN test failures for previous known-good fixtures.

---

## Tooling (pick what fits your stack)

- **Modeling:** Camunda Modeler (BPMN/DMN), Signavio, bpmn.io.
- **Rules execution:** Camunda DMN, Drools/Kogito DMN; keep rules externalized.
- **Requirements & Traceability:** Jira + Xray/Zephyr (BDD) or Azure DevOps; link by IDs.
- **API:** Stoplight, SwaggerHub, or plain OpenAPI in Git.
- **Docs:** Markdown in monorepo; site via Docusaurus/MkDocs; diagrams as source (`.bpmn/.dmn/.drawio`).

---

## Pragmatic Plan (How to Approach)

1. **Legislative mapping:** enumerate obligations & powers → glossary → compliance matrix.
2. **Event storming + use cases:** discover domain events & main flows.
3. **Sketch BPMN** for each filing; identify decisions → extract into **DMN**.
4. **Write acceptance tests (Gherkin)** for each flow & decision.
5. **Define the data model & APIs;** align with forms/certificates.
6. **Add NFRs & controls** (audit, security, retention).
7. **Wire up automation tests** that execute DMN and process paths.

---

## Appendix: Drop‑in Templates

### A. Use Case (Markdown)
```md
# <ID> — <Title>
**Primary Actor:** <Role>
**Stakeholders:** <List>
**Trigger:** <Event>

## Main Success Scenario
1. ...
## Alternate / Exception Flows
A1. ...
E1. ...
## Acceptance Criteria
Link: tests/bdd/features/<file>.feature (#SCN-...)
## Links
BPMN: <PROC-...> | DMN: <DMN-...> | APIs: <API-...>
```

### B. SBVR Term
```md
## TERM-<NAME>
**Definition:** <Authoritative definition>
**Source/Provenance:** <Law/policy reference>
**Notes:** <Edge cases, clarifications>
```

### C. Compliance Matrix (CSV columns)
```csv
LawId,Instrument,Section,ObligationType,RequirementText,RuleId,ProcessId,UseCaseId,TestId,ControlId,Notes
```

### D. Data Dictionary (Markdown table)
```md
| Field | Type | Constraints / Notes | Source of Truth |
|------|------|----------------------|-----------------|
```

### E. Gherkin Scenario
```gherkin
@tag1 @tag2 @SCN-<FLOW>-<###>
Scenario: <Name>
  Given ...
  When  ...
  Then  ...
```

### F. OpenAPI Operation (YAML)
```yaml
operationId: API-<METHOD>-<PATH>
summary: <Purpose> (PROC-<...> task: "<Task name>")
```

### G. AsyncAPI Event (YAML)
```yaml
operationId: API-SUB-<event-topic>
message:
  name: <DomainEventName>
```

---

### Notes & References

- BPMN for processes, DMN for decisions — use CMMN patterns as needed for complex cases.  
  See: <https://camunda.com/blog/2023/07/cmmn-patterns-bpmn/>
- Keep **model IDs stable**; reference them in tests, API specs, and document templates.
- Prefer **source-controlled artifacts** over embedded images. CI should validate schemas and run model tests.
