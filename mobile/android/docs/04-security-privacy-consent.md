# 04 — Security, privacy and consent

**Status:** security design for sprint 1, with roadmap items named as roadmap.
**Scope:** the Android app, the evidence packet, the v1 API, and the operator relationship.
**Read with:** [01-architecture](01-architecture.md) §9 (zones), [02-domain](02-domain-and-state-machines.md) (invariants), `docs/CONTRACTS.md` §8.

> The product's security property is not "we are secure". It is **"a stranger can check that this
> record was not altered, and can see what its subject agreed to"**. Every control below serves
> that property, and anything that would weaken it is listed in §11 as not built.

---

## 1. Assets and who would attack them

| Asset | Where it lives | Why it matters | Primary adversary |
|---|---|---|---|
| Frame bytes (JPEG) | Device storage only until upload; then server media store (planned) | They are the evidence. Alteration destroys the product | Operator seeking a better outcome; requester disputing |
| Media hashes, packet hashes, chain | Room, packet records, server | Tamper evidence; the audit trail | Operator, server insider, malicious requester |
| Consent names, roles, signatures | Room, packet, server | Personal data; the legal authority to capture | Data thief, insider, regulator |
| Signing key (P-256) | Android Keystore, non-exportable | If forged, the operator's attestation means nothing | Malware with device access, rooted device |
| Ledger and payouts | Server (planned), mock now | Money | Fraudster, colluding operator/requester |
| Bearer tokens | App private storage | Account takeover | Malware, shoulder surfing, backup extraction |
| Accuracy/geofence history | Packet, arrival records | The difference between evidence and a story | Operator who wants to claim site presence |

**Crown jewel is the record, not the photo.** Photos can be re-taken; a chain that cannot be
rewritten is the asset. Controls are ordered accordingly.

---

## 2. STRIDE threat model

| Element | Threat category | Concrete threat | Control | Residual |
|---|---|---|---|---|
| Device / app | **Spoofing** | Another person uses the operator's session | Bearer bound to device id; attestation at token issue; token lifetime 60 min; no shared credentials | A stolen unlocked device is the operator until session expiry |
| Device / app | **Tampering** | Rooted device modifies app code or DB to fabricate records | Keystore-backed signature (hardware where available); server re-walks chain and re-derives tier; key attestation roadmap | A rooted device can still submit plausible captures; review sampling is the backstop |
| Device / app | **Repudiation** | Operator denies capturing a packet | Device signature + actor id on every record; consent record names operator | Operator can claim key was stolen; revocation process is the answer |
| Device / app | **Information disclosure** | Consent names, payloads or signatures leak through logs, screenshots, backups | No logging of names/payloads/signatures; vault requires device unlock; `FLAG_SECURE` on consent screens (roadmap); backup exclusion for media/token | Screenshots by the user are not preventable |
| Device / app | **Denial of service** | Storage exhaustion from frames or outbox growth | Frame budget per job; outbox dead-letter visibility; media hashed then compressed; storage check before capture | Long offline periods can exhaust storage |
| Device / app | **Elevation of privilege** | Simulated capture presented as real | `MediaSource.SIMULATED` recorded on every `MediaRef`; simulated source labelled in UI; server rejects non-demo packets with simulated media at seal review | A cheap camera photo cannot be distinguished from a good one; quality gates + review |
| Transport | **Spoofing** | MITM server impersonation | TLS 1.2+ with hostname verification; certificate pinning on roadmap (ADR-8 follow-up) | User-installed CA on a rooted device defeats validation |
| Transport | **Tampering** | Request body altered in flight | TLS; server signature/chain verification catches altered packets; idempotency keys catch replay | None material |
| Transport | **Information disclosure** | Passive interception | TLS; no PII in URLs; media upload over the same channel | Traffic analysis of packet sizes/timing |
| API | **Spoofing** | Stolen token used from another device | Device binding claim; attestation; per-token rate limits | Token theft on-device is game over until expiry |
| API | **Tampering** | Client asserts a tier/badge/seal it did not earn | Server re-derives; `422 DERIVATION_MISMATCH`; no write field for tier/seal in the domain model | None by design |
| API | **Repudiation** | Duplicate payout request | Idempotency keys + immutable ledger + request ids | Ops must preserve request logs |
| API | **Information disclosure** | Cross-operator data access via id guessing | All queries scoped to the token's operator; 404 for foreign ids (not 403, to avoid id oracle) | Server bug could leak; row-level tests required |
| API | **Denial of service** | Offer-accept flooding, upload flooding | Per-endpoint buckets (accept 12/min, packets 20/min, payouts 6/min), 429 + `Retry-After` | Distributed attack needs edge protection (roadmap) |
| API | **Elevation of privilege** | Client sets `demo=false` on a demo packet, or `redaction=NOT_NEEDED` on a face | Server recomputes demo from the job; redaction resolution is a blocking gate + audit sampling | Dishonest redaction labels are a human-review problem, not a code problem |
| Log | **Tampering** | Rewriting history | Append-only log; public inclusion proofs; heads recorded over time; a published head is never overwritten | A compromised log operator can fork the log; consistency proofs make it detectable |
| Ledger | **Tampering** | Editing a paid entry | Append-only entries; reversals are new records; idempotent payouts | Server DB admin access must be controlled and audited |
| Consent data | **Information disclosure** | Subject identity leaked to other requesters | Consent fields only in the packet and to the requester who paid; never in public projections | Legal process can compel disclosure |
| Insider | **Elevation of privilege** | Support agent reads packets without need | Role-based access (roadmap), audit log of packet views, break-glass procedure | Single-admin environments are a risk until RBAC ships |

**STRIDE summary.** The threats that keep us up at night are not network attacks; they are
(a) a device that lies about its clock or media source, (b) an operator who mislabels redaction,
and (c) a support/insider path into consent data. Controls (a) and (c) are technical; (b) is a
human-review control and is staffed for in the runbook.

---

## 3. Authentication and authorisation

| Control | V1 behaviour | Notes |
|---|---|---|
| Token issue | `POST /auth/token` with Play Integrity attestation and a stable device id | Mock accepts `dev-attestation`; production rejects without a valid verdict |
| Token lifetime | 60 minutes | No refresh token in v1; re-issue per device |
| Session scope | Token is single-operator and device-bound | A different device id → `401` (server check; mock accepts `fw_dev_` tokens) |
| Authorisation | Every query scoped to the token's operator; no operator-supplied id fields for reads | Foreign ids return `404`, not `403` |
| Credential gates | Accepting a job requires the job family's credentials; server-enforced with a named missing credential and shortest fix | `403 CREDENTIAL_REQUIRED`; client also hides ineligible work |
| Money gates | **No credential, plan, feature flag or balance condition may block a payout that is owed** | Estate invariant I-1 / conformance C-12; the payout path is reviewed against this every release |
| Admin access | Not in the app; future console must be separate, MFA, RBAC, audited | Roadmap |

---

## 4. Consent

### 4.1 Consent is a record

`Consent` in `core:model` is not a checkbox:

| Field | Rule |
|---|---|
| `name` | The person who authorised capture; max 120 chars |
| `role` | Their relationship to the site ("Homeowner", "MUD service contact") |
| `scopeText` | Copied **verbatim** from the job's consent scope; the same text the subject saw |
| `method` | `IN_APP_SIGNATURE`, `VERBAL_RECORDED`, `PRE_AUTHORIZED` |
| `capturedAt` | Device time at signature; server compares to receipt time and flags > 5 min skew |
| `signatureHash` | 64-hex hash of the signed text + name + timestamp; the raw signature image is not retained (minimisation) |
| `verifiedByOperatorId` | The operator who recorded it |

A consent record is immutable once written. If the subject later withdraws consent, that is a
**new record** (`CORRECTION` or `ATTEST` noting withdrawal), not an edit.

### 4.2 Enforcement points

1. **App:** the Consent gate is the first step of capture; the seal button cannot proceed without
   a consent record (blocking `GateCheck`).
2. **Domain:** `SealEngine.evaluate` includes "consent on file" as a precondition; no consent → no
   seal, with a named reason.
3. **Server:** packet upload requires a `consent` object and re-checks the gate; a packet without
   consent is refused.
4. **Export:** a packet whose consent is under a withdrawal flag cannot be exported until Trust &
   Safety resolves the flag (runbook §5.4).

### 4.3 What consent can and cannot authorise

| Scenario | Allowed? | Rule |
|---|---|---|
| Roof exterior, no people in frame | Yes | Scope text names surfaces and excludes interiors/street |
| Meter bank serials, no residents | Yes | Exterior meter bank only |
| A bystander's face in a wide shot | Only if unavoidable and redacted before seal | Redaction gate; "no faces" framing hint; audit sample |
| A licence plate in a detail shot | Only if the scope explicitly includes plates and redaction resolves | `MediaRef.redaction` must be `APPLIED`; export blocked otherwise |
| Interior of a home when the scope says exterior | No | Scope text is the boundary; operator must not capture |
| Recording a verbal consent (method `VERBAL_RECORDED`) | Yes, for low-sensitivity jobs only | Flagged in review; property/claim jobs with a named subject prefer in-app signature |
| Child in frame | No | Not in any v1 scope; redaction/reject |
| Capturing for a purpose not stated in the job | No | Purpose limitation is part of the consent scope |

**Consent withdrawal.** Property managers and subjects can request withdrawal through the
requester's account or support. The packet is flagged, its seal is re-evaluated (a withdrawn
consent is a `FLAGGED` badge state), further exports are blocked, and the retention clock starts.
Records are retained to preserve the audit trail (see §7). Evidence already exported before the
withdrawal cannot be recalled; that limitation is disclosed in the consent text rather than
hidden.

---

## 5. Face and plate redaction

| Control | Behaviour |
|---|---|
| Scope-driven | The job's `consentScope` text names what is excluded ("no faces", "faces and plates excluded unless consented") |
| Framing hint | Camera overlay says "no faces" and positions the guide; the operator resolves a `RedactionState` on each media ref |
| States | `NONE` (no sensitive content), `REQUIRED` (needs action), `APPLIED` (blurred/masked), `NOT_NEEDED` (operator judged none present) |
| Blocking gate | Any media with `REQUIRED` at seal → seal blocked; export also blocked if a `REQUIRED` slips through |
| Audit sample | 5% of sealed packets per city lead reviewed for redaction accuracy; failures trigger coaching and a re-review, not deletion |
| No automation | No on-device or server-side face recognition, no biometric templates, no plate OCR watchlists. Redaction is manual and recorded |

We do not claim redaction is perfect. We claim it is a **recorded, reviewable decision** attached
to a specific frame, which is more than a camera roll offers.

---

## 6. Data minimisation and the data map

| Data | Collected | Why | Stored where | Retention |
|---|---|---|---|---|
| Media frames | Yes | The evidence | Device until upload; server media store (planned) | 18 months post-seal, then media purged; hashes retained with packet |
| Media SHA-256 | Yes | Tamper evidence, dedupe | Room + packet + server | Indefinite (part of the record) |
| Consent name/role | Yes | Legal authority to capture | Packet + server | 18 months, then pseudonymised; audit record retains "consent on file: yes" |
| Signature image | **No** | Not needed; hash is enough | — | — |
| GPS fix | At arrival/capture only | Geofence gate, evidence metadata | Packet + server | Same as packet |
| Device id | Yes | Token binding, fraud | Server | 12 months after account closure |
| Operator credentials | Yes | Credential gate | Server | Account life + 12 months |
| Ledger/payout | Yes | Money | Server | 7 years (financial record) |
| Crash/diagnostic logs | Yes, minimal | Stability | Server | 30 days, no PII fields |
| Analytics events | **No third-party SDK** | — | — | — |
| Background location | **No** | Not required, invasive | — | — |

**Logging rules (non-negotiable):** never log consent names, packet payloads, signatures, full
media bytes or bearer tokens. Log ids, status codes, timings, error codes and request ids. A log
line that would contain a payload must be redacted at the logger, not at review time.

---

## 7. Privacy rights (GDPR / CCPA) and retention

### 7.1 Rights handling

| Right | Fieldwright response | SLA (plan) |
|---|---|---|
| Access (DSAR) | Export of the subject's personal data: consent records involving them, media in which they appear (if identifiable), ledger entries; delivered as a machine-readable bundle | 30 days (GDPR), 45 days (CCPA) |
| Rectification | Corrections are appended records; personal details (e.g. misspelled name) corrected via a new consent/correction record | 30 days |
| Erasure | Media bytes erased; consent name pseudonymised; hashes and record structure retained because erasure of the chain would destroy audit evidence for other parties. This trade-off is stated in the privacy notice, not buried | 30 days unless legal hold |
| Restriction | Packet flagged, exports blocked, review required | 72 hours |
| Portability | Packet export (canonical JSON + custody text) | 30 days |
| Objection to processing | Processing stops for future capture; existing evidence retained under legitimate-interest/legal-obligation analysis | 30 days |
| CCPA "do not sell" | No sale of personal information exists; no ad tech; DSAR portal provides the disclosure | — |

**Why erasure does not delete the chain.** A record that can be deleted is not evidence for the
requester who paid for it. Erasure is applied to *personal data* (media bytes, names) while the
hash-linked structure and non-personal metadata remain. The hash of erased media is retained as a
commitment, which is necessary to prove the packet is the same artefact that was sealed. Counsel
must review this position before public launch.

### 7.2 Retention schedule

| Data | Retention | Trigger |
|---|---|---|
| Draft/unsealed packets and media | 30 days | Last edit, then purge |
| Sealed packet records and hashes | 7 years (contract/limitation period) | Seal date |
| Media bytes for sealed packets | 18 months, then purge (configurable per requester contract) | Seal date |
| Consent identity fields | 18 months, then pseudonymise | Seal date |
| Ledger and payouts | 7 years | Entry date (financial records) |
| Device/token records | 12 months after account closure | Closure |
| Support tickets | 24 months | Closure |
| Transparency log leaves | Permanent (append-only) | Anchor |

Retention runs as a scheduled job with an audit record of each purge. A legal hold pauses purges
for named subjects and is itself an audited action.

---

## 8. Encryption and key management

| Layer | Control | Status |
|---|---|---|
| Transport | TLS 1.2+ only; HSTS on the web surfaces; no cleartext to production | Required |
| Signing key | ECDSA P-256 generated in Android Keystore (`KeyGenParameterSpec`), non-exportable, hardware-backed where the device supports it; key id recorded on records (`signerKeyId`) | In sprint 1 |
| Software signer | Test/debug only. **Release builds fail closed** when the Keystore key is unavailable; they do not fall back silently | In sprint 1 |
| At rest, device | Room is **not encrypted in sprint 1** (ADR-8): the OS file-based encryption plus app sandbox is the only layer. SQLCipher (or equivalent) is a roadmap item and a launch gate for high-sensitivity cities | Roadmap |
| At rest, server | Managed database encryption at rest + encrypted object storage; keys in a KMS | Planned |
| Media at rest | Encrypted buckets; signed URLs with short TTL; no public media | Planned |
| Backup | Android backup excludes media/tokens; server backups encrypted and access-audited | Planned |
| Key rotation | Signing keys are per-device and per-install; revocation list server-side; ledger and packets retain `signerKeyId` so old signatures remain verifiable | Sprint 1 records; revocation list roadmap |
| Key compromise | Device key revocation + forced re-attestation; affected packets flagged for review; no retroactive re-signing (that would falsify history) | Runbook §5.6 |

**Why no "encrypt everything with a master key".** A non-exportable device key is a stronger
signature story than a server-held key, because the operator's attestation is tied to a device the
operator controls. Server-held keys would make the platform the signer, which weakens the claim
that a field actor attested.

---

## 9. Audit trails and monitoring

| Event | Logged (no PII) | Where | Retention |
|---|---|---|---|
| Token issue/failure | device id hash, result, reason code | Server | 12 months |
| Packet upload verified/rejected | packet id, operator id, chain result, failing seq | Server | 2 years |
| Seal created/withdrawn | packet id, actor, gate results | Server + packet record | 7 years |
| Export | packet id, actor, requester org, timestamp | Server | 7 years |
| Payout request/result | payout id, amount, fee, status, reason | Server + ledger | 7 years |
| Redaction decision | packet id, media id, state, operator | Packet + audit | 7 years |
| Support access to a packet | actor, packet id, reason code | Access log | 2 years |
| Config/fee-schedule change | old/new values, actor | Change log | Permanent |
| Purge/legal hold | subject ref, executed at, scope | Retention log | Permanent |

Security monitoring (alerts in the runbook §7): chain verification failure spike, signature
verification failures, packet conflict rate, payout failure rate, 401/403 spikes per token,
dead-letter growth, anomalous upload volume per operator.

---

## 10. Incident response

### 10.1 Severity

| Severity | Examples | Response |
|---|---|---|
| **P0** | Signing key compromise; packet chain forgery possible; consent data exposed; payout duplication | Page on-call immediately; freeze payouts and uploads if needed; notify leadership; GDPR 72-hour clock if personal data involved |
| **P1** | Redaction gate bypass; chain verification failures on live packets; auth outage; PSP outage > 30 min | Respond within 1 hour; incident channel; evidence preserved |
| **P2** | Elevated dead letters; isolated duplicate upload; one operator/requester affected | Respond within one business day |
| **P3** | Cosmetic data issue; delayed retention job | Next business day |

### 10.2 Playbook (P0/P1)

1. **Declare and timebox.** Incident commander + scribe; start a timeline with UTC timestamps.
2. **Preserve evidence.** Snapshot logs and affected packet rows before any remediation. Never
   delete a packet to "clean up".
3. **Contain.** Revoke tokens for affected device ids; disable the affected key id; if payouts are
   implicated, freeze payout processing (never freeze evidence capture; workers must keep working).
4. **Assess scope.** Which packets, which requesters, which subjects, which jurisdictions.
5. **Notify.** Legal and DPO decide on regulator (72 h under GDPR) and individual notification;
   requester notification for affected packets.
6. **Remediate and verify.** Patch, re-run chain verification over affected packets, confirm no
   silent history rewrite.
7. **Postmortem within 5 business days.** Blameless, with the detection gap named and a test added.

**Never do during an incident:** edit a packet, delete a log leaf, re-sign a historical record, or
tell a requester "it's fine" before verification passes. Evidence of the incident must survive the
incident.

---

## 11. What we will not build

| Not built | Reason | Where the temptation came from |
|---|---|---|
| Covert capture / hidden recording | Illegal in many jurisdictions, destroys admissibility, contradicts the consent product | `field-commander` "Ghost Mode" |
| GPS spoofing / location override | A verification product cannot ship a lying location API; it defeats the geofence gate | `field-commander` §3.4 |
| Token/crypto payouts | Securities and AML exposure, volatility turns a wage into a bet, no licensed rail | `field-commander` DTA/Truth Credit |
| Face recognition / watchlists | Biometric special-category data; surveillance harm; not needed to deliver evidence | `field-commander` sentiment and LPR stack |
| LPR hotlist lookups | Turns inspection into a police-adjacent intelligence network | `field-commander` §3.8 |
| Sentiment / body-language analysis | Covert bystander analysis with no evidence value | `field-commander` §3.3 |
| AI-generated or AI-altered evidence | Generated content in a provenance dossier destroys the dossier (`ecosystem/04` I-7) | estate-wide temptation |
| User-level tracking analytics SDKs | Third-party telemetry in an evidence app is a consent leak and a supply-chain risk | industry default |
| Silent background location | Not required for the job; invasive; app-store risk | gig-app default |
| Dark patterns in payout/instant-fee flows | The fee is disclosed before confirmation or the flow does not ship | industry default |

---

## 12. Third parties and subprocessors (planned)

| Party | Purpose | Data exposed | Status |
|---|---|---|---|
| Cloud infrastructure provider | Hosting, storage | Packets, media, ledger | Planned; DPA required |
| Payment service provider | Payouts, settlement | Payout instructions, account last 4, identity for KYC | Planned; PSP KYC/AML responsibility split documented |
| Transparency log operator (estate) | Merkle log hosting | Public leaf hashes only | Planned |
| Play Integrity | Device attestation | Device integrity verdict | Planned; no content |
| Crash reporting | Stability | Crash stack traces, no PII fields | Planned, subject to review; may be dropped entirely for supply-chain simplicity |
| Email/SMS provider | Notifications | Operator contact | Planned |

No analytics, advertising or data-broker subprocessor is approved, now or by default.

---

## 13. Assurance and testing (sprint 1 and before launch)

| Test | What it proves | Status |
|---|---|---|
| Chain tests: genesis, tamper detection at correct seq, canonical stability | Tamper evidence | unit tests (domain-core) |
| Canonical JSON golden vectors | Two implementations produce one hash | required; golden file to ship with the contract |
| Signature tests: sign/verify, wrong key rejection, missing key failure | Attestation integrity | unit tests |
| Repository tests: cross-operator scoping, idempotent writes | No data leak / no duplicate money | data-sync tests |
| Redaction gate tests: `REQUIRED` blocks seal and export | Privacy gate is real | UI + domain tests |
| Consent gate tests: no consent → no seal | Consent is a precondition | domain tests |
| Demo-exclusion test: demo packet cannot anchor | Invariant I-4 | backend + app tests |
| API contract tests against `backend/mock-server.mjs` | Client and server agree on the wire | 49-assertion smoke run documented in `backend/README.md` (local verification; CI boots the mock and checks health) |
| Dependency review (`gradle/libs.versions.toml`) | No unreviewed third-party libraries | review; no analytics SDKs |
| Device attestation rehearsal on a physical device | Token flow works on real hardware | before beta |
| External penetration test | Independent check of API and app | before public launch (plan) |
| DSAR dry run (access + erasure) | Privacy rights are operable, not aspirational | before public launch (plan) |

Open security questions are tracked in [08-roadmap-and-decisions.md](08-roadmap-and-decisions.md) §6
and in the product brief §13. The two that gate launch are Room encryption (ADR-8) and a
counsel-reviewed erasure position (§7.1).

