# 00 — Product brief

**Product:** Fieldwright — Android field-evidence marketplace
**Estate:** AUCTUM trust estate (verification protocol), ODASI Technologies Inc. as engineering entity
**Status:** sprint 1 build; this document separates *shipped behaviour* from *plan* on every line
**Owner:** product/business (docs-gtm)
**Read with:** [01-architecture](01-architecture.md) · [02-domain](02-domain-and-state-machines.md) · [03-api](03-api-contract.md) · [07-gtm](07-gtm-launch-playbook.md)

> **Thesis.** Field work is bought and sold as labour, then defended as evidence. Fieldwright
> sells the defence directly: a DoorDash-shaped marketplace where the product is a *sealed
> evidence packet*, and the worker is paid when the packet seals, not when the photo uploads.

---

## 1. The argument in five paragraphs

1. **The buyer does not want photos.** An insurer, property manager or utility district wants
   a claim it can defend six months later without paying a reinspection. Today that means
   calling a contractor, receiving an untraceable camera roll, and arguing about it later.
2. **The worker does not want another chore app.** A credentialed inspector wants paid work
   that respects their equipment, their radius and their judgement, with a payout they can
   predict and a fee nobody hides.
3. **The gap is trust infrastructure, not supply or demand.** Dispatch is a solved commodity
   (DoorDash, Uber, Field Nation). Hash-chained evidence, consent records and derived tiers
   are not. That is the part we build and the part a competitor cannot copy by reskinning a
   dispatch board.
4. **The protocol is the estate's, not this app's.** Fieldwright is one market adapter on the
   AUCTUM Trust Engine contract: records append, tiers derive, badges declare, a log proves
   inclusion, a seal appears only when both hold, and a fee is quoted with its disclosure
   attached. See `ecosystem/04-trust-engine-contract.md` and `ecosystem/05-evidence-and-seal-model.md`.
5. **What we refuse is part of the product.** No tokens, no crypto payouts, no covert
   capture, no GPS spoofing, no face or plate capture outside a recorded consent scope, no
   surveillance framing. Those choices are not positioning; they are what makes the evidence
   admissible and the marketplace legally boring. §11 gives the reasons.

---

## 2. What is actually sold

| Buyer | Job to be done | What they purchase | What they stop paying for |
|---|---|---|---|
| Insurer claims desk | *Document this loss before the adjuster spends a day on it* | A sealed packet with storm attribution, field capture, measurement and consent on file | Reinspections and disputed claim packs |
| Property manager | *Prove the state of the asset this month* | A recurring walk packet with named common areas and dated frames | Ad-hoc contractor visits, photo requests by email |
| Utility district / MUD | *Keep annual backflow compliance current* | RPZ test packets with vault, body tag and gauge evidence | Paper test forms, chasing testers for records |
| Contractor | *Show the milestone is complete to spec* | Progress-proof packet with calibrated measurement | Rework arguments and withheld draws |
| Owner / owner's rep | *Settle a scope question without a site visit* | Claim sweep packet with establishing/mid/detail frames | A second opinion visit |

The unit of value is **defensible certainty**: the end of an argument. Software is the delivery
mechanism, not the product. This is the estate's thesis applied to field work
(`ecosystem/00-ecosystem-blueprint.md` §2).

**What the worker gets paid for.** A job payout is `bounty + bonus`, multiplied by a disclosed
surge (1.0–1.6) at offer time. The worker is never charged the platform fee. The payout is
attached to the job, released through the ledger when the packet is sealed and accepted, and
payable instantly for a capped 1.5% fee or free by standard ACH. See §6.

---

## 3. Personas

### 3.1 Operator (supply side)

| Attribute | Detail |
|---|---|
| Who | Credentialed field professional: RPZ/backflow tester, roof-safety-certified inspector, property inspector, notary, Part 107 pilot (`Credential` enum in `core/model`) |
| Device | Mid-range Android, often one hand, gloves, outdoors, unreliable signal |
| Income model | 1099 independent contractor; multiple platforms; pays own vehicle, tools, insurance |
| Job to be done | *Fill the gaps in my week with work that pays what it says and does not waste a drive* |
| Pains | Hidden fees, offers that vanish before they are read, unclear scope on arrival, unpaid wind-down work, payout holds |
| Gains | Predictable payout per job, a radius the operator sets, credential-matched work, a record that protects the operator when a requester disputes |
| Success signal | Accepts 4–8 jobs/week, completes them without a support call, returns next week |

Operator tiers come from the code (`Operator.tierName`): **Scout → Inspector → Lead inspector →
Commander**. The tier labels describe credential depth and dispatch standing. They are not a
quality score and must never be rendered as a percentage.

### 3.2 Compliance desk (demand side)

| Attribute | Detail |
|---|---|
| Who | Claims adjuster, property manager's operations lead, utility compliance officer, contractor's PM |
| Job to be done | *Give me a packet I can attach to a claim, a file or an audit without editing it* |
| Pains | Chasing field staff for images, screenshots with no provenance, rework when scope was wrong, audit findings on consent |
| Gains | One link per job, named badges, a tier with reasons, an export that includes the custody line |
| Success signal | Packet accepted first time; dispute rate falls; renews the monthly programme |

### 3.3 Internal operator of the business

| Role | Accountable for |
|---|---|
| City lead (field ops) | Supply liquidity, credential verification, dispute queue, SLA |
| Trust & safety | Consent failures, redaction, flagged badges, account suspension |
| Finance ops | Ledger reconciliation, payout failures, PSP exceptions, 1099 reporting |
| Engineering on-call | Sync SLO, upload failures, dead-letter queue |

---

## 4. The loop (DoorDash-shaped, evidence-shaped)

```
Demand                  Supply                 Dispatch                Field                  Settlement
──────                  ──────                 ────────                ─────                  ──────────
requester posts    →    operator online   →    rank + offer      →    accept → en route →    seal → submit →
a priced job            with radius           (45 s TTL)              arrive (geofence)      review → approve →
                                                                     consent → capture →    ledger → payout
                                                                     gates → seal
```

Six states matter commercially, and each maps to a ship-blocking requirement:

| Step | What the operator sees | What the requester gets | Why it is load-bearing |
|---|---|---|---|
| Offer | Payout, `$xx/hr`, distance, surge pill, 45 s countdown, accept/pass | Nothing yet | An offer that is not legible in 45 s destroys acceptance rate |
| Accept | Job detail, SLA clock, consent scope, access notes | Job accepted, ETA window | Scope disputes start here and are cheap to prevent |
| Arrive | Geofence check; override requires a recorded reason | Arrival event | A fabricated arrival is the fastest way to lose the evidence argument |
| Capture | Consent gate → checklist → calibrated measurement → gates | Nothing until seal | Redaction and consent are blocking, not advisory |
| Seal | Seal mark, packet id, head hash, submit | Packet available | This is the moment the worker earned the payout |
| Pay | Balance, ledger, instant or ACH | Invoice with disclosed fee | Money and evidence move on separate ledgers by design |

---

## 5. Scope: v1 and explicitly not-in-v1

### 5.1 In v1 (this build)

| Capability | Where it lives | Status |
|---|---|---|
| Offer dispatch with weighted ranking, 45 s TTL, auto-accept policy | `core/model` `DispatchEngine`; `feature/ground`, `feature/jobs` | in sprint 1 build |
| Job lifecycle with server-authoritative transitions | `JobLifecycle`; mock backend | in sprint 1 build |
| Consent as a record (name, role, scope, method, time, signature hash) | `Consent`; `feature/capture` | in sprint 1 build |
| Capture with SHA-256 media refs, redaction state, simulated-camera fallback | `MediaRef`; `feature/capture` | in sprint 1 build |
| Calibrated measurement against a known reference (site card 30 cm, shingle 90 cm, door 91 cm) | `Measurement`; `feature/capture` | in sprint 1 build |
| Blocking gates and derived seal evaluation | `SealEngine`; `feature/capture` | in sprint 1 build |
| Hash-chained packets, Merkle leaf/proof fields, ECDSA P-256 signature | `core/crypto`; `EvidencePacket` | in sprint 1 build |
| Offline-first Room store, outbox with idempotency keys, WorkManager sync | `core/data` | in sprint 1 build |
| Ledger, balances, instant/standard payout quote | `FeeEngine`, `LedgerEngine`; `feature/payouts` | in sprint 1 build |
| Contract-first mock backend for local development | `backend/mock-server.mjs` | in sprint 1 build |

### 5.2 Not in v1, with the reason

| Not building | Why | What ships instead |
|---|---|---|
| Real payment rails (PSP integration) | No provider account, no money movement in a demo build; faking it would be the exact dishonesty this product exists to prevent | Documented `PayoutRequest` contract + mock; PSP adapter is a roadmap item (§08 ADR-2) |
| ARCore/ARKit rendering | Device capability and calibration work is larger than one sprint; AR without calibration produces confident wrong measurements | Calibrated capture engine with documented ARCore upgrade path (ADR-4) |
| Transparency log service | Server-side log is an estate service, not an app feature | Merkle leaf/proof fields and inclusion checks through the contract; log head served by the backend |
| Background location tracking | Not required for the job; privacy-invasive; app-store risk | Fix taken at arrival/capture only, `FixSource` records provenance |
| Photo *storage* in the app vault | The vault stores hashes and references; media bytes stay on device until upload | `MediaRef` with SHA-256, byte count, dimensions; export re-derives canonical JSON |
| Reviews and star ratings | Ratings create an incentive to game evidence; reliability is derived from outcomes, not opinions | `Operator.reliability` inputs: acceptance rate, on-time rate, completed jobs |

---

## 6. Unit economics

### 6.1 Fee model (parameterised, never hard-coded)

All money is **integer cents**. The fee schedule is data (`FeeSchedule` in `Money.kt`):

| Parameter | Value | Payer | Disclosure rule |
|---|---|---|---|
| `platformFeeBp` | **1500 bp = 15.00%** | Requester | Printed next to every money figure the requester sees |
| `instantPayoutFeeBp` | **150 bp = 1.50%** | Worker, only when instant is chosen | Fee sheet before confirmation; `PayoutQuote.feeDisclosure` |
| `instantPayoutMinFeeCents` | 50 ($0.50) | Worker | Same |
| `instantPayoutMaxFeeCents` | 500 ($5.00) | Worker | Same |
| Standard ACH | $0.00 | — | "Free, 1–3 business days" |
| `mileageRateCentsPerMile` | 70¢/mi | n/a | Labelled an *estimate*, never tax advice |

**Who pays what.** The requester pays `payout + platform fee`; the worker receives `payout`
untouched. The platform fee is computed from the payout with half-up rounding applied once:
`((payout × 1500) + 5000) / 10000`, integer arithmetic. No float ever enters the money path.

### 6.2 Worked example — one roof hail assessment

| Line | Amount | Note |
|---|---:|---|
| Worker payout (`bounty + bonus`) | $450.00 | `JobType.ROOF_HAIL.baseBountyCents = 42_000` plus a $30 bonus |
| Platform fee (15%, requester-paid) | $67.50 | Disclosed as "Platform fee (15%) — $67.50" |
| **Requester charge** | **$517.50** | |
| Worker instant-payout fee, if chosen | $5.00 | 1.5% of $450 = $6.75, capped at $5.00 |
| **Worker receives instantly** | **$445.00** | Or $450.00 by free standard ACH |
| Worker mileage estimate (23 mi × $0.70) | $16.10 | Estimate only; labelled in the app |

### 6.3 Contribution model (assumptions, not traction)

This table is a **model**, not a reported result. Every cost row is an input to be replaced by
a contracted rate before any external use. No subscriptions, no funding, no GMV are claimed.

| Per job ($450 payout) | Amount | Basis |
|---|---:|---|
| Revenue: platform fee | $67.50 | 15% of payout |
| Revenue: instant fee (attach-rate weighted) | $1.50 | Assumes 30% attach × $5.00 cap; **assumption** |
| PSP cost on requester charge | −$15.31 | Assumes 2.9% + $0.30 on $517.50; **assumption, obtain quote** |
| Instant rail cost (when instant used) | −$0.45 | 30% attach × $1.50; **assumption** |
| Support and dispute handling | −$6.00 | ~12 min of desk time at loaded cost; **assumption** |
| Insurance allocation | −$2.00 | General liability + E&O per sealed packet; **assumption** |
| Field ops and credential checks | −$4.00 | Amortised city-lead cost; **assumption** |
| Infrastructure (app + backend) | −$0.40 | Cloud/storage per packet; **assumption** |
| **Contribution per sealed job** | **$40.84** | 59% of revenue, 9.1% of GMV |

Break-even is a formula, not a claim:
`jobs_per_month = fixed_monthly_cost / contribution_per_job`. At an illustrative $60,000/month
fixed cost (3 FTE + infrastructure, **plan input**), break-even is ≈1,469 sealed jobs/month,
≈70/day. The plan below is sized against that number, not against an assumed market share.

**Why the fee is 15% and requester-paid.** A worker-paid commission on a $28 meter-cluster job
is $4.20 — enough to make the job not worth the drive, which starves supply. A requester-paid
fee is a procurement line the buyer already budgets, and it keeps the worker's headline payout
honest, which is what makes offers acceptable in 45 seconds.

**Instant fee cap is the point.** 1.5% uncapped on a $450 payout is $6.75; the $5.00 cap keeps
the fee legible and prevents the "convenience fee" resentment that erodes worker retention.

---

## 7. Pricing

| Surface | Price | What is included |
|---|---|---|
| Requester per job | `payout + 15%` | Dispatch, capture, sealed packet, export, dispute handling for 30 days |
| Requester programme (plan) | Monthly minimum volume commitment in exchange for a rate card | Same, with named proof standard and SLA; **not offered at launch** |
| Worker | $0 to accept work | Free standard ACH payout |
| Worker instant payout | 1.5%, min $0.50, max $5.00 | Same day to debit card |
| Worker mileage | No charge | Estimate shown for tax awareness only |

Pricing rules that cannot be waived by a screen: (a) the fee disclosure travels with the
figure; (b) surge is disclosed *before* acceptance; (c) a payout quote is immutable for its
idempotency key; (d) no fee may reduce a worker payout below the job's displayed payout.

---

## 8. Cold-start plan (plan, not history)

Launch one city, one utility district, three recurring job families. Recurring compliance work
is the wedge because it is scheduled, credentialed and already evidence-shaped; storm work is
the expansion, not the beachhead.

| Phase | Window | Demand | Supply | Gate to next phase |
|---|---|---|---|---|
| P0 — Supply first | Weeks −8 to −4 | 3 design partners (1 MUD, 1 PM, 1 contractor) co-sign the proof standard | Recruit 30 credentialed operators in one radius; paid onboarding session | 25 verified operators, 3 signed pilot scopes |
| P1 — Seeded pilot | Weeks −4 to 0 | 40 scheduled jobs/week from design partners (mixed RPZ + property walk + meter) | Same operators; 15 km radius; auto-accept off by default | Seal rate ≥ 90% of submitted packets; dispute rate < 5% |
| P2 — Public soft launch | Weeks 1–4 | Open requester signup, waitlist release 100/week | Operator referral loop; cap at 75 active operators | Offer accept rate ≥ 60%; p95 seal-to-submit < 5 min |
| P3 — One-family expansion | Weeks 5–8 | Add claim sweeps to existing requesters | Credential campaign (property inspector) | Claim sweep repeat rate ≥ 40% |
| P4 — Second district | Weeks 9–12 | Same demand types, adjacent district | Same supply pool, radius expansion only | Contribution-positive city on the model in §6.3 |

Liquidity mechanics, all labelled plan:

- **Supply-first seeding.** No requester is onboarded until 25 verified operators exist in the
  radius. Empty-marketplace screens are a retention killer (see §10.3).
- **Offer floor.** Every offer is at least `MIN_PAYOUT_FLOOR_CENTS = 2500` ($25) so a short job
  cannot become unpaid drive time.
- **Pilot minimum week.** Design-partner jobs carry a weekly minimum *for the pilot only*,
  capped and paid from the launch budget; it is withdrawn once accept rate exceeds 60%.
- **Recurring anchor.** One MUD contract funds predictable work; it is the demand floor while
  one-off demand ramps.
- **Referral loop.** Operator refers a credentialed peer; both receive a bonus ledger entry
  (`LedgerKind.BONUS`) after the referee's first sealed packet. No cash outside the ledger.

---

## 9. Competitive matrix

| Product | What it sells | Overlap with Fieldwright | Where it stops | Our wedge |
|---|---|---|---|---|
| **Field Nation** | Vetted technician marketplace, work orders | Supply pool, dispatch, credentials | Work completion is attested by the buyer's process, not by a portable evidence artefact | Every job produces a sealed packet the requester can attach to a claim or audit |
| **Procore** | Construction project management | Field photos, inspections, project record | Evidence is trapped inside one project's account; nothing is independently verifiable | Portable, hash-chained packet; verifiable without trusting the platform |
| **DoorDash / Uber** | Dispatch, pricing, supply liquidity | The loop mechanics we deliberately copied | No evidentiary product; no credential gate; no consent record | Dispatch is commodity; evidence is the product |
| **Snappt** | Income-document fraud detection for property screening | Trust in property transactions | Screens documents; does not capture field evidence | Different unit of proof (a site, not a document) and a field workforce |
| **Adjuster networks / IA firms** | Human inspection capacity | Field capture for insurers | The report is the product; provenance is the firm's word | Cryptographic provenance and consent, verifiable by a stranger |
| **In-house field teams** | Direct control | Full process ownership | Cost, coverage, and no independent record | Variable capacity with a defensible record per job |

The claim narrows to one sentence: **dispatch is a commodity, the sealed packet is not.**

---

## 10. KPIs and the measurement tree

### 10.1 North star and tree

North star: **weekly sealed packet GMV** (requester-paid, packets in `SealState.EARNED`).
It cannot be inflated by unsealed uploads, which is why it is the right number to steer.

```
Weekly sealed packet GMV
├── Demand: jobs posted → jobs accepted → jobs sealed
│    ├── poster activation (first job in 7 days)
│    ├── repeat request rate (30-day)
│    └── job cancellation reasons
├── Supply: verified operators → online hours → offers accepted
│    ├── credential verification pass rate
│    ├── offer accept rate (accepted / offered)
│    ├── acceptance decision time (p50/p95 vs 45 s TTL)
│    └── operator weekly retention (returned within 7 days)
├── Quality of the artefact: seal rate → review pass rate → dispute rate
│    ├── gate failure reasons (top 5)
│    ├── redaction required → applied rate
│    ├── correction rate per 100 packets
│    └── chain-verification failure count (target 0)
└── Money: payout → ledger → payout completion
     ├── instant-fee attach rate
     ├── payout failure rate and reason codes
     └── payout float days
```

### 10.2 AARRR mapping and instrumentation source

Every metric below is computable from the schema or the backend log. No metric requires new
data collection beyond what the product already writes. Baselines are **unset**; that is a
deliberate gap, not a hidden estimate.

| Stage | Metric | Formula / source | Baseline |
|---|---|---|---|
| Acquisition | Requester orgs with ≥1 posted job | `jobs.requester.org` distinct in 7 days | unset |
| Activation | Offer accepted within first session | `offers(status=ACCEPTED AND acceptedAt − offeredAt ≤ TTL)` | unset |
| Retention | Operator returns in 7 days | operator with ≥1 job in week N+1 after week N | unset |
| Revenue | Sealed GMV | Σ requester charge on `SEALED`/`APPROVED` packets | unset |
| Referral | Referred operators completing first seal | referral ledger entry + first `SEALED` | unset |
| Quality | Seal rate | `SEALED / PACKET_READY` | target ≥ 90% |
| Quality | Dispute rate | `DISPUTED / SUBMITTED` | target < 5% |
| Ops | p95 sync flush | `SyncReport` timestamps | target < 10 s online |
| Ops | Dead-letter count | `sync_state.deadLetters` | target 0 steady state |
| Trust | Chain verification failures | `verifyChain.valid=false` events | target 0 |

### 10.3 The one anti-metric

**Open-app-and-do-nothing.** A worker who opens the app, sees no offers, and closes it is the
most expensive event in the funnel; it trains the operator that the app is not worth opening.
The Ground screen therefore renders *off-the-clock*, *no supply*, *offline* and *error* as
designed states (`docs/06-design-system.md` §6), and the supply-first rule in §8 exists to keep
the empty state rare.

---

## 11. Rejected designs (and the reasons)

These are rejections, not roadmap items. Each one was present in the source material and is
dropped on legal or ethical grounds, plus a product argument for why it would fail anyway.

| Rejected | Source | Why it is rejected | What we do instead |
|---|---|---|---|
| **Token payouts** (ERC-20, "Truth Credits", on-chain DTA) | `field-commander` README §3.8 | Securities/AML exposure, price volatility means a payout can lose value between accept and spend, no licensed rail, and the estate already records that no payout rail is live (`ecosystem/14` R-08/R-16). A token converts a wage into a speculative instrument | Integer-cent ledger, idempotent `PayoutRequest`, documented PSP adapter |
| **Crypto settlement / blockchain anchoring as a product promise** | `field-commander`, `ecosystem/14` R-03 | Chain-of-hashes (SHA-256) gives tamper evidence without a chain; an on-chain write adds cost and a legal character without adding admissibility | Merkle leaf/proof fields + log head; anchor is a server concern |
| **Ghost Mode** (Tor routing, "operational invisibility") | `field-commander` §3.4 | Covert tooling has no place in a consent-gated evidence product; it defeats the geofence and consent gates the product depends on; app-store and investigatory risk | Standard TLS to the backend; device attestation; no covert channels |
| **GPS spoofing** | `field-commander` §3.4 | Directly falsifies an evidence gate. A product that sells verification cannot ship a lying location API | `FixSource` records `GPS / NETWORK / SIMULATED`; simulated fixes are labelled and never satisfy the arrival gate for a sealable packet |
| **Face / plate capture outside consent** | `field-commander`, `ar-inspection-platform` LPR | Biometric and vehicle data carry consent, retention and statute exposure; frames are exactly where a field product accidentally becomes surveillance | Consent scope is a blocking gate; `RedactionState` must resolve before seal; "no faces" framing hint; plate hashing only if in scope |
| **Hotlist/LPR intelligence network** | `field-commander` §3.8 | Watchlist matching turns a field-inspection app into a surveillance system. Unacceptable regardless of legality in a given jurisdiction | Not built; no LPR, no face recognition, no watchlists |
| **Ambient audio / sentiment / body-language analysis** | `field-commander` §3.3 | Covert recording of bystanders; no evidence value; unacceptable | Not built |
| **AI-generated or AI-altered evidence** | estate-wide temptation | Generated media in a provenance dossier destroys the dossier. The estate rule is explicit: generated content never stands behind a score or a record (`ecosystem/04` I-7) | Cameras and sensors only; every media ref is a hash of a captured frame |
| **Surveillance framing** ("see the unseen", "expect me") | `field-commander` voice | Contradicts the consent product, repels the compliance buyer, and attracts the wrong operator | Plain, procedural voice: what happened, what it proves, what was agreed |
| **Commission/percentage tiers** | early estate drafts | Tiers are derived from records, never assigned (`ecosystem/05` §3); a percentage implies a completeness that does not exist | Named tier + reasons |

---

## 12. Risks

Severity: H/M/L. Likelihood: H/M/L. Owners are roles, not names. "Trigger" is the signal that
escalates the risk from watch to action.

| # | Risk | Sev | Lik | Mitigation | Trigger |
|---|---|---|---|---|---|
| R1 | **Worker misclassification** (1099 operating like W-2) | H | M | Operators choose hours, radius, whether to accept; no exclusivity; no mandatory training; offers are not assignments; no performance discipline that resembles employment. Counsel review before public launch. Decision criteria recorded in ADR-9 | Any ops policy that mandates availability, accepts-on-behalf, or penalises declining |
| R2 | **Insurance gap** (general liability, E&O, cyber) | H | M | Operator carries own liability + we carry E&O for the packet product; certificate verification during onboarding; per-job insurance allocation in §6.3; broker quote before P1 | First requester asking for a certificate; first claim against a packet |
| R3 | **Consent or redaction failure** | H | M | Blocking gates, no seal without consent record, redaction state must resolve, audit sampling; a packet that seals without consent is a P0 incident | Any gate bypass; any exported packet with `redaction = REQUIRED` |
| R4 | **Measurement accuracy overstatement** | H | M | Calibration against a physical reference, uncertainty shown, no claim of AR-grade accuracy, measured-value disclaimer, ARCore deferred (ADR-4) | Requester disputes a measured dimension; calibration error > 5% in audit |
| R5 | **Offline fraud** (replay, clock manipulation, simulated capture passed as real) | H | M | Idempotency keys, device time plus server time, `FixSource`/`MediaSource` recorded, simulated capture visibly labelled, chain verify on upload, duplicate-packet conflict | Any packet whose media source is `SIMULATED` above the demo book; duplicate idempotency key with different body |
| R6 | **Payout float / PSP failure** | H | M | No money moves without a ledger entry; payout idempotent and retryable; standard ACH as fallback; failed payouts surface reason codes in-app | PSP outage > 30 min; payout failure rate > 2% |
| R7 | **Supply liquidity** | H | H | Supply-first seeding, recurring anchor contract, $25 offer floor, radius controls, referral bonus | Accept rate < 40% in a district for 2 weeks |
| R8 | **Demand concentration** (one requester is most GMV) | M | H | Design partners capped at 40% of weekly GMV in P1/P2; pipeline of second requester type | Any requester > 40% for 2 weeks |
| R9 | **Evidence dispute becomes legal dispute** | M | M | Custody text export, append-only corrections, dispute procedure with SLA, counsel-reviewed disclaimer; packet is evidence, not a legal opinion | ≥2 disputes/month; any dispute naming a party |
| R10 | **Device/OS fragmentation** (camera, Keystore, camera-less devices) | M | H | `SimulatedCaptureSource` fallback with visible label; software signer fallback for tests; min SDK policy | ANR rate > 0.5%; seal failure attributable to Keystore |
| R11 | **App-store policy** (camera, location, background) | M | M | Foreground-only location, camera permission with rationale, no background tracking, privacy manifest and data-safety form reviewed before submission | Rejection or policy warning |
| R12 | **Privacy regulation** (GDPR/CCPA, biometric data) | H | M | Data map, DSAR procedure, retention schedule, no face recognition, no derived biometric templates | New state biometric law; DSAR volume |
| R13 | **Key compromise** (signing key on device) | H | L | Android Keystore P-256, hardware-backed where available, signed key attestation, revocation list, no key export | Any key-attestation failure; signature verify failure spike |
| R14 | **Dependence on the estate protocol** | M | M | Fieldwright implements the contract locally in `core:model`/`core:crypto`; the server can be swapped without the app changing | Engine extraction slips beyond the roadmap or changes hash rules |

**Claims discipline.** No number in this brief is traction. Any sentence using a revenue,
customer, market-share, retention or accuracy figure must either cite a code constant in this
repository or be labelled *plan* / *assumption* / *target*. If a claim cannot be supported, it
is moved to the "open questions" table instead of the body copy.

---

## 13. Open questions (need an owner)

| # | Question | Blocks | Recommended default |
|---|---|---|---|
| Q1 | Which entity employs the operator of record for 1099 purposes, and in which state(s)? | PAY, onboarding, insurance | ODASI Technologies Inc. as marketplace of record, with counsel sign-off |
| Q2 | PSP choice and fee schedule (Stripe Connect vs separate rails) | Unit economics precision, payout quotes | Stripe Connect standard; replace assumption in §6.3 |
| Q3 | Is the seal mark licensed or owned? The supplied mark's provenance is not recorded | Store listing, brand use | Commission/original work before public launch; no unlicensed asset in the app |
| Q4 | Which dispute adjudicator can lift a `FLAGGED` badge? The estate records this as unresolved (`ecosystem/05` Q-5) | Trust & safety procedure | Independent adjudicator role with signed decision; operator cannot lift own flag |
| Q5 | City 1 identity and MUD partnership terms | Cold-start plan | Pick one MUD with an annual RPZ calendar; start 8 weeks before test season |
| Q6 | Data retention period for media bytes (after packet export) | Privacy policy, storage cost | 18 months for sealed packets, 30 days for unsealed drafts; record in the data map |

---

## 14. What success looks like in 90 days

Not revenue. Three falsifiable outcomes for the P2 gate:

1. **Supply works:** 25+ verified operators, offer accept rate ≥ 60%, 7-day operator return ≥ 50%.
2. **Evidence works:** seal rate ≥ 90% of submitted packets, zero chain-verification failures,
   zero packets exported with unresolved redaction, dispute rate < 5%.
3. **Money works:** every payout traceable from ledger entry to PSP settlement in the mock
   ledger model; payout failure rate < 2%; instant fee disclosed on 100% of instant requests.

The 90-day roadmap with owners and weekly cadence is in [07-gtm-launch-playbook.md](07-gtm-launch-playbook.md);
the technical sequencing is in [08-roadmap-and-decisions.md](08-roadmap-and-decisions.md).



