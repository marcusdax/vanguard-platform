# 07 — GTM and launch playbook

**Status:** plan. Nothing in this file is reported traction; every number is an input, a target or
a budget envelope, labelled as such. Claim discipline is in §11 and is not optional.
**Owner:** product/business (docs-gtm) with city lead and finance ops.
**Read with:** [00-product-brief](00-product-brief.md) (economics, risks), [05-runbook](05-field-ops-runbook.md) (operations), [08-roadmap](08-roadmap-and-decisions.md) (sequencing).

---

## 1. Positioning

### 1.1 The position in one paragraph

Fieldwright is the field-evidence marketplace for property, utility and insurance work. A
credentialed operator captures a consented, hash-chained packet; the requester pays for a record it
can defend in a claim, a file or an audit. Dispatch is table stakes. The sealed packet is the
product.

### 1.2 Positioning matrix

| Dimension | **Fieldwright** | Field Nation | Procore | Independent-adjuster networks | In-house field team |
|---|---|---|---|---|---|
| Unit sold | Sealed evidence packet | Completed work order | Project record | Inspection report | Labour + process |
| Provenance | Hash chain + device signature + consent record | Platform attests completion | Attachments inside one account | Firm's word | Employer's word |
| Consent | Explicit record, blocking gate | Not modelled | Not modelled | Not modelled | Varies |
| Portability | Export (canonical JSON + custody text) | Work history | Trapped in tenant | PDF report | Internal |
| Verification by a stranger | Inclusion proof in a public log (roadmap) | No | No | No | No |
| Price | Payout + 15% requester fee; free worker payout | Markup / hourly | Per-seat SaaS | Per-inspection fee | Salary + overhead |
| Worker status | 1099, credential-gated | 1099 | n/a | 1099/contract | W-2 |

**Where we are deliberately weaker:** brand recognition, an existing supply pool, a payments
history, and (until the log service ships) a public verifier. The plan does not pretend otherwise;
it wins on the artefact, not on marketplace scale.

### 1.3 Category and comparison

| Comparison | Use it? | How |
|---|---|---|
| "DoorDash for field evidence" | Analogy only, internal and in investor conversation | Explains the loop in one sentence. Never say it to a compliance buyer as if it were the product |
| "Field Nation with proof" | Requester conversation | The closest functional comparison; then show the custody line |
| "Procore for the field, without the project account" | Property manager conversation | Emphasise portability and speed |
| "Blockchain for evidence" | **Never** | We do not use a blockchain or tokens. Hash chains and a Merkle log are not a cryptocurrency, and saying "blockchain" invites a conversation we do not want |
| "AI-powered inspection" | **Never** | We do not use AI to produce or judge evidence. Generated content in a provenance dossier is disqualifying |

---

## 2. Messaging hierarchy

### 2.1 Levels

| Level | Message | Where it is used |
|---|---|---|
| L0 — Category | A field-evidence marketplace | App store subtitle, one-pager header |
| L1 — Promise | Work that ends the argument | Homepage hero, sales deck title |
| L2 — Requester message | Get a sealed packet you can attach to a claim, a file or an audit — with consent on file and a chain that does not change | Requester email, deck slide 2 |
| L2 — Operator message | Credential-matched work, the payout shown before you accept, and no fee taken out of the job | Operator recruitment, referral copy |
| L3 — Proof | Specific, testable product facts (below) | Deck appendix, trust page, sales follow-up |
| L4 — Objections | Direct answers (below) | Sales call, FAQ |

### 2.2 Proof points and their support

Only claims with a code or contract basis are used. Each has a status tag: **build** (in this
repository), **plan** (funded or scheduled), **target** (an objective, not a result).

| Proof point | Support | Tag |
|---|---|---|
| Every media frame is referenced by its SHA-256 hash | `MediaRef.sha256`, `core:crypto`, packet records | build |
| Packets are a gap-free hash chain with a device signature | `ChainRecord`, `PacketAssembler`, ECDSA P-256 Keystore signer | build |
| Consent is a record with name, role, scope text, method and time; no consent means no seal | `Consent`, `SealEngine` precondition | build |
| Faces and plates must be resolved before a packet can seal or export | `RedactionState`, blocking gates | build |
| The payout is shown before acceptance and no platform fee is charged to the worker | `Offer.payoutCents`, `FeeSchedule` (requester-paid 15%) | build |
| Instant payout costs 1.5%, min $0.50, max $5.00; standard ACH is free | `FeeEngine`, `PayoutQuote.feeDisclosure` | build |
| Offers expire in 45 s; max 3 active jobs; $25 payout floor; radius ≤ 25 km | `DispatchEngine` constants | build |
| A stranger can verify inclusion in a public log | Merkle leaf/proof fields and `/log/head` exist; the public verifier and log service are not live | plan |
| Requesters pay a 15% platform fee, disclosed next to every figure | `FeeSchedule.platformFeeBp`, invoice line | build (fee is a parameter) |
| Payments move to a bank account or debit card | **Not live.** The payout API and ledger exist; the PSP adapter is a roadmap item | plan |
| "Verified by AUCTUM" seal for public marketing | Requires the estate conformance suite; not passed. In-app tier language is the domain model, not a public attestation | plan / not claimed |

### 2.3 Objections and answers

| Objection | Answer | Do not say |
|---|---|---|
| "We already get photos from contractors." | Photos are attachments; this is a packet with consent, chain, measurement and a custody line your auditor can read. Compare the two exports side by side | "Our photos are better" |
| "Why would a worker use another app?" | The payout shown is the payout paid; no fee is deducted; offers are credential-matched; the record also protects the worker when a job is disputed | "It's like Uber but better" |
| "Is this another AI thing?" | No AI produces or alters evidence. The app captures, hashes and signs; humans review flags | "AI-powered" |
| "Is it on the blockchain?" | No tokens, no chain. Hash chains and a Merkle log give tamper evidence without a cryptocurrency | "Blockchain" |
| "Who is liable if the evidence is wrong?" | The operator attests their capture; Fieldwright verifies integrity, not truth. The custody record shows exactly what was captured, when, by whom, under what consent. Liability allocation is in the requester agreement | "We guarantee the finding" |
| "Can we keep the photos forever?" | Retention is contractual: 18 months by default, configurable, with a documented purge | "Forever" |
| "Does this replace our adjuster?" | No. It reduces reinspections and gives the adjuster a defensible input | "Replace your team" |
| "What if the worker does not show?" | SLA clock, ETA, arrival record, and a dispatch fallback; the requester sees the same status as the operator | "That never happens" |

### 2.4 Language rules

- Use the estate vocabulary: *packet, sealed, records, consent on file, in review, flagged,
  tier*. Never "verified" unqualified; never "certified" unless a credential is named.
- Numbers before adjectives: "4 frames, 1 measurement, 2 records" beats "comprehensive".
- No urgency theatre, no exclamation marks, no "revolutionary", no "seamless".
- No number appears in external copy unless it is a code constant, a contract parameter, or
  labelled *plan*/*target*. If a copywriter needs a number we do not have, the answer is the
  claim-approval process (§11), not a plausible estimate.

---

## 3. Channel mix

| Channel | Audience | Role | Owner | 90-day budget (plan envelope) | Leading indicator |
|---|---|---|---|---|---|
| Direct outbound (20 named accounts) | Insurers, property managers, MUDs | Design partners and first paying requesters | City lead | $0 cash + time | Meetings held; pilot scopes signed |
| Utility-district partnership | MUD compliance officers | Recurring RPZ demand anchor | City lead | $0–2k (association events) | Annual test calendar access |
| Credential bodies and training providers | RPZ testers, roof-safety inspectors | Verified supply, in one room | City lead | $2k | Operators verified per session |
| Operator referral | Existing operators | Cheapest verified supply | City lead | $75 per successful referral, capped (ledger bonus) | Referred operators who finish first seal |
| Trade groups and supply houses | Field workers | Awareness and trust, not hire-ads | City lead | $1k | Conversation → application rate |
| App store (ASO) | Both sides | Store presence for "field inspection", "RPZ test", "property walk" | Product | $0 | Store listing conversion |
| Content and trust page | Requester researchers | Explain what makes a photo evidence; publish the contract and audit | Product | $3k | Qualified inbound |
| Webinars with associations | Compliance desks | Education, not product demo | City lead | $2k | Attendee → pilot conversation |
| Paid social | **Not used at launch** | Local density is the bottleneck; broad reach dilutes it | — | $0 | — |
| Recruiting ads promising income | **Never** | Misleading pay claims create classification and trust risk | — | $0 | — |

**Channel principle:** supply density in one radius beats reach. Every dollar spent on awareness
outside the launch district is a dollar not spent on the three operators who make the district
work.

---

## 4. Supply-first seeding in one city

### 4.1 Choice of city and district (decision, not a guess)

Criteria, in order: (1) an annual RPZ test calendar administered by one MUD; (2) a property
manager with ≥ 500 units in a 15 km radius; (3) one insurer claims desk active in the area; (4) an
existing pool of credentialed testers within a 40-minute drive; (5) a climate with a real hail or
storm season so claim work has a natural trigger. The city is chosen by the city lead against these
criteria and written down in the launch charter before any spend.

### 4.2 The 30-operator supply plan (plan)

| Week | Supply action | Target | Gate |
|---|---|---|---|
| −8 | Map credentialed testers/inspectors in the district; build a list of 120 | 120 names | List reviewed by city lead |
| −7 | Two credential-provider evenings; ride-along offer | 40 conversations | 20 interested |
| −6 | Onboarding sessions (10 per session, 20 min each) | 30 verified | 25 verified with credentials on file |
| −5 | Demo-mode capture training; payout-account setup | 25 ready | 25 pass demo seal |
| −4 | Design-partner dry run jobs; collect time-per-job data | 20 sealed packets | Seal rate ≥ 90% |
| −3 | Referral loop opens (only for verified operators) | +5 referred | Referral quality check |
| −2 | Availability calendar for launch week | 20 operators committed | 3 operators per 10 expected jobs |
| −1 | War-room rehearsal; support rota confirmed | — | Dry run passes |
| Launch | Ramp jobs; daily supply check | 60% accept rate | P1 gate in [00 §8](00-product-brief.md) |

**Operator offer (what is actually promised):** credential-matched work, the payout shown before
acceptance, no worker-paid platform fee, free standard ACH, instant payout at cost + 1.5% (capped
$5), a record that protects the operator in a dispute, and a written auto-accept policy they
control. No income guarantee outside the capped pilot minimum in [00 §8](00-product-brief.md).

### 4.3 Demand seeding

| Week | Demand action | Target | Gate |
|---|---|---|---|
| −8 | Three design partners sign a pilot scope (jobs/week, proof standard, dispute SLA) | 3 scopes | Legal review complete |
| −6 | MUD annual-test calendar mapped to operator capacity | 80% coverage | Test window agreed |
| −4 | First design-partner jobs posted | 40/week | Fill rate ≥ 90% |
| −2 | Requester waitlist opens for adjacent property managers | 20 waitlisted orgs | Supply gate holds |
| Launch | Waitlist release 100 requesters/week (plan) | Activated requesters produce 1 job | Activation ≥ 50% |
| +2 | First requester case note (after written consent) | 1 | Legal-approved |

---

## 5. Waitlist, onboarding funnel and gating

### 5.1 Requester waitlist

- Landing page: what a packet contains, an anonymised sample export, the fee schedule, the proof
  standard, and a waitlist form asking job family, volume/month, district and role.
- Gate: no requester is activated until **25 verified operators** exist in their radius. The
  waitlist page states the gate honestly; empty-marketplace signups churn and poison the account.
- Weekly release: up to 100 requesters/week (plan), prioritised by job family that matches current
  supply.

### 5.2 Operator funnel

| Stage | Metric definition | Target (plan) | Instrumentation |
|---|---|---|---|
| Awareness → application | Applications / conversations | 25% | referral codes, event sheets |
| Application → verified | Credentials + insurance + W-9 complete | 70% | onboarding checklist |
| Verified → first job | Accepted a job within 10 days | 80% | dispatch records |
| First job → sealed | Sealed and submitted | ≥ 90% | packet records |
| First seal → week 2 active | Accepted ≥ 2 jobs in week 2 | 60% | dispatch records |
| Week 4 retention | Accepted ≥ 1 job in week 4 | 50% | dispatch records |

These are **targets**, not history. A funnel target without an owner is a wish; each row has the
city lead as owner in the weekly review.

### 5.3 Referral loop (plan)

| Element | Rule |
|---|---|
| Who | Verified operators only |
| Reward | A `BONUS` ledger entry of $75 for the referrer, $25 for the referee |
| Trigger | Referee's **first sealed and submitted** packet, not merely signup |
| Cap | 10 successful referrals per operator per quarter |
| Fraud checks | No shared payout account; no shared device id; credential verified independently; referral code tied to referrer |
| Disclosure | Bonus is taxable income; shown in the ledger with its own line; not a cash payment outside the ledger |
| Abuse response | Bonus reversed with a `REVERSAL` entry, account review, no clawback of job earnings |

---

## 6. Launch week

### 6.1 Cadence

| Day | Demand | Supply | Comms | Gate to next day |
|---|---|---|---|---|
| −1 | Freeze scope changes; seed production jobs | Confirm availability; test attestation on devices | Internal note; status page ready | Dry run: 5 sealed packets end-to-end |
| 1 | Design partners only (40 jobs) | 20 operators, 15 km radius | Requester: "your pilot starts"; operator: day-one brief | Seal rate ≥ 90%; zero P0 |
| 2 | + first 25% waitlist | Add 5 operators | Publish the sample packet explainer | Accept rate ≥ 50% |
| 3 | + next 25% | Support ride-alongs | First case note draft | Dispute rate < 8% |
| 4 | Hold | Quality review of day 1–3 packets | Operator feedback session | Redaction sample passes |
| 5 | + remaining 50% | Hold | Weekly metrics to leadership | All SLOs green for 48 h |

### 6.2 War room

- One channel; a single dashboard with the north star and the SLOs in [05 §6](05-field-ops-runbook.md).
- Roles: incident commander (on-call), dispatcher (city lead), trust reviewer (T&S), money
  watcher (finance ops), comms (lead).
- Two status posts per day during launch week, even when nothing changed.
- **Rollback triggers** (close the funnel to a cohort, not the whole city): seal rate < 80% for
  4 h; dispute rate ≥ 8% in 24 h; any chain-verification failure; any consent/redaction escape;
  payout failure rate > 2%; API 5xx > 2% for 15 min.

---

## 7. 90-day roadmap (GTM)

Owners are roles. Metrics refer to the definitions in [00 §10](00-product-brief.md).

| Phase | Weeks | Demand actions | Supply actions | Product dependency | Owner | Exit metrics (plan/target) |
|---|---|---|---|---|---|---|
| **P0 — Supply first** | −8 to −4 (runs into D0) | 3 design partners signed; proof standard agreed; legal templates | 25 verified operators; credential data verified; insurance checked | Onboarding, demo book, credentials | City lead + finance ops | 25 verified operators, 3 signed scopes |
| **P1 — Seeded pilot** | −4 to 0 | 40 design-partner jobs/week; fee schedule shown | 20 committed operators; ride-alongs | Seal flow, gates, vault export, ledger | City lead + engineering | Seal rate ≥ 90%, dispute < 5% |
| **P2 — Public soft launch** | 1–4 (D0–D30) | Waitlist release 100/week; first 3 paying requesters | Referral loop live; 30 more verified operators | Payout request flow (PSP mock), sync health | City lead + product | Accept rate ≥ 60%, 7-day operator return ≥ 50%, 25 sealed jobs/week |
| **P3 — One-family expansion** | 5–8 (D31–D60) | Add claim sweeps to existing requesters; first insurer desk | Property-inspector credential campaign (+10) | Claim sweep templates; measurement UX | City lead + T&S | Claim sweep repeat rate ≥ 40%, disputes < 5% |
| **P4 — Second district** | 9–12 (D61–D90) | Open adjacent district to existing requesters | Radius-only expansion of current pool; +10 operators | Dispatch and market snapshot at new density | City lead + finance ops | Contribution-positive on the model in [00 §6.3](00-product-brief.md); SLOs met |
| **P5 — Scale decision** | D90 | Go/no-go: second city, second job family, or fundraise | Same | Conformance evidence for the AUCTUM seal; PSP live | Founder + lead | Written decision with the metrics that justify it |

Sequencing rationale: **credentialed recurring work first** (RPZ, property walk) because it is
predictable, already evidence-shaped and needs no storm; **storm work second**, when the operator
pool and the evidence pipeline are proven; **new cities last**, because liquidity does not travel.

---

## 8. Launch budget envelope (plan, not a forecast)

| Line | 90-day estimate | Basis |
|---|---:|---|
| City lead (1 FTE, loaded) | $30,000 | Plan input |
| Trust & safety (0.5 FTE, loaded) | $15,000 | Plan input |
| Finance ops (0.25 FTE, loaded) | $8,000 | Plan input |
| Engineering (2 FTE, loaded) | $75,000 | Plan input |
| Operator onboarding (sessions, materials, 30 workers) | $6,000 | $200/worker |
| Referral bonuses (25 successes) | $2,500 | $100 average per success |
| Pilot minimum top-up (capped) | $5,000 | At most $1,000/week for 5 weeks, then withdrawn |
| Insurance (E&O/cyber, 3 months allocated) | $2,000 | Broker estimate to be replaced |
| Legal (1099/classification, terms, privacy, consent) | $15,000 | Fixed-fee estimate |
| PSP integration and setup | $5,000 | Engineering + PSP fees |
| Content, trust page, design | $4,000 | External |
| Events, credential-provider evenings | $3,000 | Two events |
| Contingency (10%) | $17,000 | — |
| **Total envelope** | **≈ $187,500** | Replace every line with a contracted number before use in any financial statement |

This is a cost envelope for the first city and 90 days, not a projection of revenue. It exists so
the supply-first plan can be funded and so no one mistakes marketing spend for traction.

---

## 9. Measurement

| Layer | Metric | Cadence | Owner |
|---|---|---|---|
| North star | Weekly sealed packet GMV | Weekly | Lead |
| Demand | Requester activation, repeat rate, jobs/week/org | Weekly | City lead |
| Supply | Verified operators, online hours, accept rate, week-4 retention | Daily during launch, weekly after | City lead |
| Quality | Seal rate, dispute rate, redaction escapes, consent escapes | Daily | T&S |
| Money | Payout success, instant fee attach, float days | Weekly | Finance ops |
| Trust | Chain verification failures, dead letters | Daily | Engineering |
| Cost | Cost per sealed packet, cost per verified operator | Monthly | Finance ops |

Experiment rules: one variable at a time, pre-registered hypothesis and decision rule, minimum two
weeks or 100 offers (whichever is later), and no experiment may change a disclosed fee, a payout,
or an evidence gate. The dispatch weights (`DispatchEngine.WEIGHTS`) may be experimented with only
under that rule, with the change visible in the ADR log.

---

## 10. GTM risks

| Risk | Trigger | Response | Owner |
|---|---|---|---|
| Supply churn after week 2 | Week-4 retention < 50% | Interview 10 churned operators; fix the top reason; do not add marketing spend | City lead |
| Demand concentration | One requester > 40% of weekly GMV | Sign a second requester type before P3; cap design-partner volume | Lead |
| Credential bottleneck | < 25 verified operators at gate | Add a second credential partner; extend radius only with operator consent | City lead |
| Empty-marketplace churn | Requester job fill < 80% in first hour | Re-SLA or cancel with a reason; raise supply before releasing more waitlist | City lead |
| Misleading pay copy | Any ad or post with an income figure | Remove immediately; review the approval checklist (§11) | Lead |
| Price pushback on 15% | Requester compares to hourly markup | Show the reinspection cost avoided and the fee disclosure; never discount below the fee schedule | Lead |
| Hail event overload | Storm demand > 3× supply | Surge within 1.0–1.6 disclosure; waitlist by area; no promise of same-day coverage | City lead |
| Public "AUCTUM seal" overclaim | Any external copy using the seal | Stop-ship; conformance evidence required first | Lead |

---

## 11. Claim approval checklist (every external artefact)

Before any deck, page, ad, email, store listing or press note ships:

- [ ] Every number is a code constant, a contract parameter, or labelled *plan*/*target*/*estimate*.
- [ ] No traction, customer name, revenue, GMV, retention or accuracy figure without a source.
- [ ] No "blockchain", "token", "AI-powered", "verified" unqualified, "certified" without a named
      credential, "guaranteed", "seamless", "revolutionary".
- [ ] The AUCTUM seal and wordmark are not used to imply conformance that has not been evidenced.
- [ ] Payout language says *request* unless a live PSP settles it; the current state is stated.
- [ ] Consent and redaction claims match the blocking-gate behaviour, not an aspiration.
- [ ] A "what we will not build" line is available for any conversation about covert capture,
      spoofing or tokens ([00 §11](00-product-brief.md)).
- [ ] Approval recorded in the launch charter with a name and a date.
