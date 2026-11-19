# 08 — Roadmap and decision records

**Status:** living document. ADRs are binding until superseded by a later ADR; the roadmap is a
sequencing plan, not a promise of dates. "Sprint" means a two-week engineering sprint; weeks are
relative to the first design-partner job (D0).
**Read with:** [00-product-brief](00-product-brief.md) (risks, cold start), [05-runbook](05-field-ops-runbook.md) (SLOs), [07-gtm](07-gtm-launch-playbook.md) (GTM gates).

---

## 1. Decision status legend

| Status | Meaning |
|---|---|
| **Accepted** | Binding for this sprint; code and docs must conform |
| **Proposed** | Decided in principle, not yet exercised |
| **Superseded** | Replaced; kept for history, never deleted |
| **Rejected** | Considered and refused; rationale recorded so it is not re-litigated silently |

Decision records are append-only in spirit: a changed decision gets a new ADR that supersedes the
old one. No ADR is edited to say it always said something else.

---

## 2. ADRs

### ADR-1 — Modular monolith with a one-way dependency rule

**Status:** Accepted (sprint 1)

**Context.** Six features, a data layer and a pure domain model. The team is small and the
business rules (dispatch weighting, seal eligibility, fee arithmetic) must be testable without an
emulator and must not be re-derived in a screen.

**Decision.** One Gradle build, eleven modules (`app`, `core:model`, `core:crypto`, `core:design`,
`core:data`, six `feature:*`), with `app → feature:* → core:data → core:crypto → core:model` and
`feature:* → core:design` only. Features never import each other; `core:model` imports nothing
outside the Kotlin stdlib. Route composables take `FieldwrightRepositories`, so the app owns
wiring.

**Consequences.**
- Positive: compile-time enforcement of boundaries; the domain engines are plain JUnit; a feature
  can be deleted without archaeology; one build, one APK.
- Negative: a single build graph means one compilation failure blocks everyone; module count adds
  Gradle configuration; no independent deployment (not needed for an app).
- Follow-up: a lint/`checkDependencies` rule to make the arrow direction mechanical, not a review
  habit.

**Alternatives rejected.** Microservices or a separate backend per domain (no scale need, doubles
failure modes); a single `app` module (the domain would drift into UI code within one sprint);
multi-repo (worse for a two-week sprint and a single binary).

**Revisit when** two products share a domain module, or build times exceed 5 minutes on CI.

---

### ADR-2 — Offline-first with Room as the single source of truth and an outbox

**Status:** Accepted (sprint 1)

**Context.** Field work happens where signal is poor. A capture that fails because the network is
down is a lost job. But an evidence product cannot let a conflict-free replication layer hide
conflicts: a packet is an immutable artefact, and two versions of it must be visible, not merged.

**Decision.** Room is the SSOT; the UI reads Flows only. Every mutation writes local rows in one
transaction and enqueues an outbox event with a deterministic idempotency key. `SyncWorker`
(WorkManager) drains the outbox with exponential backoff and jitter (max 8 attempts), then dead
letters visibly in Brief. Server responses are applied as writes into Room, never returned to the
UI as the source of truth. Conflicts surface; they are never auto-merged.

**Consequences.**
- Positive: capture/seal work offline; the vault and balances render identically offline; retries
  are safe by construction; conflicts are explicit.
- Negative: idempotency keys must be deterministic and correct; Room schema migrations become a
  contract; dead letters need an operational process ([05 §5.2](05-field-ops-runbook.md)).
- Follow-up: migration tests for every schema change; a dead-letter dashboard.

**Alternatives rejected.** Network-first with a cache (fails the primary scenario); Firestore-style
sync (hides conflicts and puts evidence in a third-party data model); a queue of HTTP requests
without local truth (UI cannot render offline).

**Revisit when** a real-time collaboration requirement appears (e.g. two operators on one site) —
that is a different product decision.

---

### ADR-3 — No tokens, no crypto settlement; hash chains and a Merkle log only

**Status:** Accepted (sprint 1)

**Context.** The source material proposed ERC-20 payouts, a "CitizenLedger" and Truth Credits. The
estate's own ledger records that no payout rail is live and that tokenised worker pay conflicts
with the neutrality the product sells (`ecosystem/14` R-08, R-16).

**Decision.** Money is integer cents in a ledger with idempotent payout requests; settlement goes
through a PSP adapter (planned). Evidence integrity is SHA-256 hash chains per packet plus an
RFC 6962-style Merkle log for inclusion. No blockchain write, no token, no wallet, no
cryptocurrency is part of the product.

**Consequences.**
- Positive: avoids securities/AML exposure; a wage does not fluctuate with a token; no gas fees;
  the tamper evidence is independently verifiable without a chain; the product stays legally
  boring.
- Negative: no public settlement novelty to market; anchoring is a server concern; the "public
  verifier" claim waits on a log service.
- Follow-up: the estate log service and public verifier are a separate workstream (`ecosystem/04`
  C-04).

**Alternatives rejected.** ERC-20 payouts (volatility, licensing, user harm); on-chain anchoring
per packet (cost and legal character without added admissibility); a private permissioned chain
(no clearer story than a hash chain).

**Revisit never** for settlement. Revisit the log transport only if a jurisdiction requires a
qualified timestamp authority.

---

### ADR-4 — Calibrated capture now, ARCore later

**Status:** Accepted (sprint 1)

**Context.** The AR source material is strong on capture discipline (hit-testing, plane thinking,
quality gates, capability detection) and weak on accuracy claims. Native AR needs device support,
calibration and a measurement-validation programme that does not fit one sprint.

**Decision.** Ship a calibrated-capture engine: camera (or a clearly labelled simulated source),
a framing guide, a physical reference object (site card 30 cm, shingle 90 cm, door 91 cm), two
tapped points, a result in metres and feet with the reference and pixel span recorded, and an
uncertainty statement. No AR rendering in v1. ARCore is a documented upgrade path behind the same
`CaptureSource`/`Measurement` interfaces.

**Consequences.**
- Positive: measurement is honest and auditable (the reference is in the packet); no AR device
  dependency; works on the mid-range phones the workforce actually has.
- Negative: no "wow" AR moment; measurement quality depends on operator placement of the
  reference; claims must stay conservative.
- Follow-up: validate accuracy against a physical tape in the field before any accuracy claim;
  publish the measured error, not a vendor claim.

**Alternatives rejected.** ARCore in sprint 1 (calibration and device matrix too large; risk of
confident wrong measurements); pure "estimate" without calibration (turns a claim into a guess —
the thing the product exists to prevent).

**Revisit when** the field validation shows the reference method is the limiting factor and a
supported ARCore device class exists in the supply pool.

---

### ADR-5 — Light-only visual system in sprint 1

**Status:** Accepted (sprint 1)

**Context.** The supplied Fieldwright look is parchment/ink/olive. A dark scheme is a second
design system and a second contrast audit.

**Decision.** Light-only. A dark scheme is a roadmap item, not a hidden toggle. Tokens are defined
so a dark mapping is possible without changing components; every screen uses tokens, no raw hex.

**Consequences.** Positive: one audited contrast set (see [06 §4](06-design-system.md)); less
design risk. Negative: night work in a dark cab is less comfortable; users who prefer dark mode
cannot. Follow-up: dark tokens + full re-measurement before shipping.

**Revisit when** night-shift operators are a meaningful share of supply, or the store listing
receives repeated requests.

---

### ADR-6 — WorkManager for sync, with backoff, jitter and a dead-letter state

**Status:** Accepted (sprint 1)

**Context.** Sync must run when the OS allows, survive process death, respect the network
constraint and not drain the battery.

**Decision.** `SyncWorker` runs under WorkManager with a network constraint and exponential backoff
(2 s base, jitter, cap 5 min, max 8 attempts). Sync state is a first-class domain state
(`SyncPhase`), surfaced in the top bar and Brief. Manual "Retry now" is always available. An event
that exhausts attempts becomes a dead letter, visible and never silently dropped.

**Consequences.** Positive: OS-friendly scheduling; visible state; manual recovery. Negative:
WorkManager has its own latency (not instant); testing requires `WorkManagerTestInitHelper`;
dead letters need humans.

**Alternatives rejected.** A foreground service (battery and notification burden, app-store
friction); a plain coroutine loop (dies with the process); push-driven sync only (not offline).

**Revisit when** scheduling latency causes an SLO breach on sync freshness (S4).

---

### ADR-7 — Manual dependency injection in the composition root

**Status:** Accepted (sprint 1)

**Context.** A small graph: one container, seven repositories, a signer, a location provider, a
fake/real backend behind one interface.

**Decision.** `AppContainer` in `:app` constructs the graph manually; route composables receive
`FieldwrightRepositories`; ViewModels are created with `viewModel { ... }`. No Hilt/Koin in sprint
1.

**Consequences.** Positive: no annotation processing, no build-time cost, the graph is greppable,
tests can swap the container wholesale. Negative: as the graph grows, manual wiring becomes
error-prone; no assisted injection.

**Alternatives rejected.** Hilt (KSP cost and ceremony for this size); Koin (runtime resolution
failure modes); service locator (hidden dependencies, harder tests).

**Revisit when** the container exceeds ~30 bindings or a second entry point (widget, wear) appears.

---

### ADR-8 — Defer Room encryption and certificate pinning, with explicit launch gates

**Status:** Accepted (sprint 1), with gates

**Context.** Both are real hardening measures and both add operational risk if rushed: SQLCipher
needs key management and migration testing; pinning without a rotation/backup-pin process causes
outages. The sprint 1 build is a demo/contract build, not a public release.

**Decision.** Sprint 1 relies on the Android app sandbox plus file-based encryption for Room and
standard TLS hostname verification. Do **not** ship a public release without: (1) SQLCipher (or
equivalent) for Room with a Keystore-wrapped key, or a documented risk acceptance for low-
sensitivity cities; (2) TLS pinning with primary + backup pins and a tested rotation runbook.

**Consequences.** Positive: no half-built crypto; migrations and pinning are done once, properly.
Negative: a rooted device or a backup extraction can read local data today; a user-installed CA
can MITM. Both are tracked as launch gates in [04 §8](04-security-privacy-consent.md) and in the
debt register (§4).

**Revisit by** the public-launch gate; this ADR cannot be "forgotten", it expires at that gate.

---

### ADR-9 — 1099 independent-contractor model, with product-level classification guardrails

**Status:** Accepted (sprint 1), subject to counsel review

**Context.** W-2 employment changes the unit economics and the liability model; the est
tate's source material assumed gig workers. Misclassification is a real legal risk (product brief
R1), and app design choices are evidence in that analysis.

**Decision.** Operators are 1099 contractors. The product must not create employment indicia:
operators choose their hours and radius, accept or decline freely with a reason, cannot be
assigned work without acceptance, are not required to wear a uniform or use scripted language,
and are not disciplined through the app for declining. There is no exclusivity. Auto-accept is a
policy the operator writes and can revoke. Counsel reviews onboarding, terms and any operations
policy before public launch.

**Consequences.** Positive: standard marketplace model; worker flexibility; matches the supply
base. Negative: no guaranteed coverage; quality control is through credentials and outcomes, not
direction; legal review cost and ongoing discipline.

**Alternatives rejected.** W-2 field team (coverage and cost; a different company); "contractor
but managed" hybrid (the worst of both).

**Revisit when** counsel advises, or a jurisdiction changes its test; any ops policy that looks
like direction is a stop-ship.

---

### ADR-10 — Fee schedule as parameters; requester-paid platform fee

**Status:** Accepted (sprint 1)

**Context.** The estate has a documented fee inconsistency (`ecosystem/14` R-01). A hard-coded fee
per market is how that happens. A worker-paid commission also makes low-value jobs not worth a
drive.

**Decision.** `FeeSchedule` holds `platformFeeBp = 1500` (15%), instant fee 150 bp with $0.50/$5.00
bounds, and the mileage rate as a January-updated parameter. The platform fee is requester-paid and
disclosed next to every money figure; it never reduces the worker payout. Quotes are
parameter-driven and carry their own `feeDisclosure`.

**Consequences.** Positive: a fee change is data, not a code change; every quote is auditable;
the worker's headline payout is honest. Negative: the requester's invoice is not just the bounty;
sales must explain the fee.

**Alternatives rejected.** Worker-paid commission (starves low-value supply); flat per-job fee
(punishes high-value work, no relation to value); no fee (not a business).

**Revisit when** a requester segment needs a rate card — as a *plan*, not a hard-coded constant.

---

### ADR-11 — Contract-first API with a mock backend

**Context.** The Android client and the (planned) backend are built by different workstreams at
the same time. Without a frozen contract the client invents wire shapes and the server disagrees.

**Decision.** The contract is `docs/api/fieldwright.openapi.yaml` (OpenAPI 3.1) plus the prose in
[03](03-api-contract.md). A zero-dependency mock server (`backend/mock-server.mjs`) implements it
with deterministic seeds, idempotency, ETags and real hash/Merkle behaviour, so the client can be
built and tested before a real backend exists. Contract changes are reviewed as a contract, not as
an implementation detail; breaking changes require a major version.

**Consequences.** Positive: parallel work; a testable wire contract; the mock's failure modes are
usable in client tests. Negative: two implementations to keep aligned; the mock can drift if not
exercised (mitigated by the documented smoke list).

**Alternatives rejected.** Generate the client from the server (too early, server absent);
client-first with a mock inside the app (no cross-team contract; drift).

**Revisit when** the real backend lands: the mock becomes a test fixture and the OpenAPI file
becomes the server's source of truth.

---

### ADR-12 — Demo mode is labelled and excluded from the log

**Status:** Accepted (sprint 1)

**Context.** A demo build that looks live is the easiest way to mislead a buyer or a user, and the
estate forbids demo data in the public log (`ecosystem/04` I-4).

**Decision.** Every seeded row carries `demo = true`; the UI renders "Sample book" in the top bar
and "SAMPLE — excluded from log" on demo packets; the backend refuses to anchor a demo packet;
the server returns `anchored: false` with `DEMO_EXCLUDED_FROM_LOG`. Demo mode is a build flavour,
not a hidden toggle.

**Consequences.** Positive: no accidental fiction; the demo book doubles as a test fixture.
Negative: demo screens look less impressive by design. That is the point.

---

### ADR-13 — No third-party analytics or advertising SDKs

**Status:** Accepted (sprint 1)

**Context.** An evidence app holds consent data and precise location. Third-party telemetry is a
supply-chain and privacy risk, and product metrics can be derived server-side.

**Decision.** No analytics, crash-reporter or ad SDK in sprint 1. Metrics are computed from
server-side records and the ledger; a crash reporter may be reconsidered only after a privacy and
supply-chain review, with PII fields explicitly excluded.

**Consequences.** Positive: smaller APK, no data-sharing agreement, no consent popup for tracking.
Negative: less client-side observability; crash diagnosis relies on platform vitals without PII.

---

### ADR-14 — Tier and seal derivation stay server-authoritative; public AUCTUM seal is gated

**Status:** Accepted (sprint 1)

**Context.** The domain model names tier 5 "Verified by AUCTUM". The estate's conformance suite
(`ecosystem/04` §9) is what makes that claim checkable, and it has not been passed.

**Decision.** The app derives tiers locally for preview and the server re-derives on upload
(`422 DERIVATION_MISMATCH` on disagreement). In-app tier language follows the domain model, but
external marketing will not use the AUCTUM wordmark or "Verified by AUCTUM" as a public
attestation until conformance evidence exists and the brand owner approves it ([07 §2.2](07-gtm-launch-playbook.md)).

**Consequences.** Positive: no unfalsifiable claim; the derivation path is testable. Negative: the
strongest brand asset is unusable in launch marketing until the engine work is evidenced.

**Revisit when** the estate conformance suite is run against Fieldwright and passes.

---

## 3. Roadmap

Sprints are two weeks. Milestones are gates, not dates. D0 = first design-partner job.

| Sprint / phase | Focus | Deliverables | Exit gate |
|---|---|---|---|
| **S1 (current)** | Contract build | Modules compiled against `CONTRACTS.md`; domain engines + crypto; design system + fonts; Room schema/repos/outbox; six feature screens; this docs pack; OpenAPI + mock server; CI workflow | App builds; domain tests pass; mock server contract checks pass; every screen has four states; docs reviewed |
| **S2** | Hardening | Engine tests ≥ 60 assertions; canonical JSON golden vectors; chain/Merkle tamper tests; repository idempotency tests; accessibility sweep (TalkBack, 200% text); offline/dead-letter UX; screenshot tests for stable screens | Test suite green in CI; a11y checklist signed; no P0/P1 defect open |
| **S3** | Pilot readiness | PSP adapter behind `PayoutRequest` (sandbox); production device attestation; certificate pinning with backup pins; backup/restore drill; log service integration (staging); ops dashboards for S1–S8 | Dry run of 5 sealed packets end-to-end on production-like infrastructure; runbook rehearsal |
| **S4** | Design-partner pilot (D0–D30) | 40 jobs/week; 25 operators; seal-rate and dispute telemetry; first corrections flow exercised | Seal rate ≥ 90%, dispute < 5%, zero chain failures, zero redaction escapes |
| **S5** | Public soft launch (D31–D60) | Waitlist release; referral loop; claim-sweep expansion; Room encryption decision executed; public verifier (if log service ready) | SLOs green 2 consecutive weeks; contribution-positive on the model; launch checklist complete |
| **S6** | Second district (D61–D90) | Radius expansion; ops rota; second requester type; conformance evidence for the seal | Go/no-go decision for city 2 recorded with metrics |

### 3.1 Milestone gates (must all pass; a partial pass is a no-go)

| Gate | Requirements |
|---|---|
| **M0 — Internal demo** | Builds, demo book seals, chain verifies, mock contract checks pass, docs exist |
| **M1 — Design-partner pilot** | M0 + engine tests + a11y sweep + PSP sandbox payouts + attestation on a physical device + backup drill |
| **M2 — Public launch** | M1 + Room encryption (or signed risk acceptance) + certificate pinning + DSAR dry run + pen-test findings triaged + legal/consent review + claim checklist applied to all assets |
| **M3 — Second district** | M2 + two consecutive weeks of SLOs + contribution-positive unit economics on actuals + supply retention ≥ 50% |

### 3.2 What is deliberately not on the roadmap

Dark mode, tablet two-pane, localisation, wearables, a web vault for requesters, AI quality
scoring, `ARCore`, and a second product surface. Each is either a later ADR or explicitly rejected;
none is "squeezed in" to this one.

---

## 4. Technical debt register (scored)

Score = **impact (1–5) × likelihood (1–5) × 10 ÷ effort (1–5)**. Higher is worse. A score above
30 requires a scheduled sprint; above 50 blocks the milestone that exposes it.

| # | Debt | Introduced by | Interest (what gets worse) | I | L | E | Score | Owner | Scheduled |
|---|---|---|---|---|---|---|---|---|---|
| D1 | Room unencrypted at rest | ADR-8 deferral | Local consent/media readable on rooted or backed-up devices | 5 | 3 | 3 | **50** | data-sync | S5 / M2 gate |
| D2 | No certificate pinning | ADR-8 deferral | MITM with a user-installed CA | 4 | 2 | 2 | 40 | Lead | S3 / M2 gate |
| D3 | Mock server does not verify ECDSA | mock scope | False confidence in the upload path | 3 | 4 | 2 | 60 | docs-gtm + backend | S3 (real server) |
| D4 | Manual DI graph | ADR-7 | Wiring mistakes as modules grow | 2 | 3 | 2 | 30 | Lead | Revisit at ~30 bindings |
| D5 | `Engines.kt` functions still `TODO()` at contract hand-off | sprint 1 contract-first | App cannot seal or price until domain-core lands | 5 | 4 | 2 | 100 | domain-core | S1 (in progress) |
| D6 | No canonical JSON golden-vector file shipped | sprint 1 | Two implementations can hash differently | 5 | 3 | 1 | **150** | domain-core | S2 (must) |
| D7 | No lint rule for the module dependency arrow | ADR-1 | Boundary creep by habit | 3 | 4 | 2 | 60 | Lead | S2 |
| D8 | No screenshots/a11y automation in CI | sprint 1 | Visual and a11y regressions slip | 3 | 4 | 3 | 40 | ui-field | S2 |
| D9 | Offer TTL (45 s) is a WCAG 2.2 timing exception | product design | Accessibility complaint / procurement block | 3 | 3 | 2 | 45 | product | Documented; review at M2 |
| D10 | Fonts lack shipped `OFL.txt` notices | sprint 1 | Licence compliance gap | 3 | 3 | 1 | 90 | ui-field | S1 (must) |
| D11 | Seal mark provenance unverified | supplied asset | Trademark risk at store submission | 4 | 3 | 2 | 60 | product | Before M2 |
| D12 | Mock payout allocation is whole-entry + change | mock simplification | Divergence from production ledger semantics | 2 | 2 | 2 | 20 | backend | Replace with real ledger |
| D13 | No backup/restore drill run | sprint 1 | RTO/RPO are theoretical | 4 | 3 | 2 | 60 | Lead | S3 |
| D14 | No dark scheme | ADR-5 | Night-shift comfort; user requests | 2 | 3 | 3 | 20 | ui-field | Not scheduled |
| D15 | English-only strings | sprint 1 | Market reach; some workers' first language differs | 2 | 3 | 3 | 20 | product | Later ADR |
| D16 | No rate limiting beyond per-token buckets | mock/backend scope | Distributed abuse | 3 | 2 | 3 | 20 | backend | S3 (edge protection) |
| D17 | `TODO()`-free requirement applies to owned files only | sprint 1 plan | A green build can hide an unimplemented engine | 5 | 2 | 1 | 100 | Lead | S1: build must fail if any engine TODO remains in release path |

Notes: D5, D6, D10 and D17 are sprint-1 blockers and must be resolved before the build is called
done. D1 and D2 are public-launch gates per ADR-8 and cannot be waived without a written,
owner-signed risk acceptance.

---

## 5. Open questions that need an owner

| # | Question | Owner | Blocks | Recommended default |
|---|---|---|---|---|
| Q1 | Employment entity and state for 1099 operators | Counsel + lead | Onboarding, payroll vendor | ODASI as marketplace of record |
| Q2 | PSP choice and contracted rates | Finance ops | Unit economics, payout quotes | Stripe Connect, sandbox first |
| Q3 | Room encryption approach (SQLCipher vs field-level) | data-sync + Lead | M2 gate | SQLCipher with Keystore-wrapped key |
| Q4 | Log service ownership (estate vs Fieldwright) | Lead | Public verifier, seal claim | Estate service, Fieldwright integrates |
| Q5 | Seal mark ownership or redraw | Product | Store listing | Commission an original mark |
| Q6 | Dispute adjudicator role and independence | T&S | Dispute SLA, flagged-badge resolution | Independent adjudicator, documented decisions |
| Q7 | City 1 and MUD partnership terms | City lead | Cold-start spend | One MUD, annual RPZ calendar |
| Q8 | Media retention default (18 months?) and requester override | Product + counsel | Privacy notice, storage cost | 18 months default, contract override |

---

## 6. Change log

| Date | Change |
|---|---|
| 2026-11-17 | Initial decision set and roadmap for sprint 1; ADR-1…ADR-14 accepted; M0–M3 gates defined; debt register scored |
