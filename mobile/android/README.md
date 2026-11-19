# Fieldwright

**Verified field work, paid like gig work.**

Fieldwright is an Android field-operations app for a marketplace where the product is
*defensible evidence*, not a delivery. An independent field operator goes online, gets a
nearby job (roof hail assessment, annual RPZ test, monthly property walk, meter cluster,
contractor progress proof, claim evidence sweep), captures it under a recorded consent
scope, seals the result into a signed hash-chained packet, and gets paid when the requester
accepts the packet.

It is the Android field client of the AUCTUM trust protocol, merging three earlier assets:

| Source | What survived |
|---|---|
| Fieldwright vault (web) | parchment/olive visual language, packet card, consent-first copy, chain-of-custody |
| `field-commander` | dispatch/offer loop, geofenced work, operator tiers, offline-first, payout ledger |
| `ar-inspection-platform` | capture discipline: guided frames, measurement with scale calibration, quality/redaction gates, capability-aware fallback |
| AUCTUM ecosystem blueprints | record kinds, tier × badge evidence model, seal preconditions, append-only records, fees as parameters |

What did **not** survive, deliberately: token/coin payouts, GPS spoofing, covert capture,
face recognition. See [the Brief screen](feature/brief/src/main/kotlin/com/odasi/fieldwright/feature/brief/BriefScreen.kt)
for the same rules in the product's own words, and [docs/04](docs/04-security-privacy-consent.md).

## The loop

```
GO ONLINE ──► OFFER (45 s) ──► ACCEPT ──► EN ROUTE ──► ARRIVE (geofence)
                                                          │
   PAID ◄── APPROVED ◄── SUBMITTED ◄── SEALED ◄── CAPTURE ◄┘
                                         (consent → frames → measurement →
                                          gates → canonicalise → SHA-256 →
                                          sign → chain → outbox)
```

Money and evidence move independently: a payment reversal lowers the evidence tier but
never claws back a consented record; a disputed packet blocks the seal but not the payout
ledger's arithmetic. That separation is the product's core invariant.

## Screens

| Route | What it answers |
|---|---|
| **Ground** (`ground`) | Should I go online, and what should I take next? Live map, surge zones, ranked offers, auto-accept policy, today's earnings |
| **Jobs** (`jobs`, `jobs/{jobId}`) | What is mine, where is it, what does it require, what is the next legal action |
| **Capture** (`jobs/{jobId}/capture`) | Consent → evidence → gates → seal, one obvious action per step, works offline and without a camera |
| **Vault** (`vault`, `vault/{packetId}`) | What did I seal, can it still be verified, what does the custody trail say |
| **Payouts** (`payouts`) | What am I owed, what does it cost to move it now, where did the money go |
| **Brief** (`brief`) | The operating rules: fees, consent, sync health, credentials, what Fieldwright refuses to do |

## Build

The repository is a standard Gradle Android project (Kotlin 2.0, AGP 8.7, compileSdk 35,
minSdk 26, Jetpack Compose + Material 3). Requirements: JDK 17+, Android SDK platform 35.

```bash
./gradlew :app:assembleDebug
./gradlew :core:model:test :core:crypto:test :core:data:test
```

`local.properties` must point `sdk.dir` at your Android SDK. In this workspace a
self-contained toolchain lives under `.work/tooling` and is driven by `.tools/gw.ps1`:

```powershell
pwsh -NoProfile -File .tools\gw.ps1 :app:assembleDebug
```

The release build has no signing config on purpose — add your own keystore before shipping.
The app runs entirely in **sample-book mode**: seeded local data, in-process dispatch
simulation, and a fake backend for the outbox, so it is demonstrable offline with no server.

## Architecture

```
app ──► feature:{ground,jobs,capture,vault,payouts,brief} ──► core:data ──► core:crypto ──► core:model
 │                                                                                      ▲
 └──────────────────────────────► core:design ──────────────────────────────────────────┘
```

- `core:model` — pure Kotlin vocabulary + decision engines (dispatch scoring, evidence
  tiers, seal preconditions, fee arithmetic, canonical JSON). No Android, no I/O.
- `core:crypto` — SHA-256 chains, RFC 6962-style Merkle log, ECDSA P-256 signing.
- `core:data` — Room as single source of truth, DataStore, repositories, demo market
  simulator, idempotent outbox + WorkManager sync, Keystore signer.
- `core:design` — Fieldwright visual system (tokens, Fraunces/Outfit typography, components,
  Compose Canvas site map).
- Features are stateless screens + ViewModels; they never talk to Room or HTTP directly.

Full detail: [docs/01-architecture.md](docs/01-architecture.md),
[docs/CONTRACTS.md](docs/CONTRACTS.md).

## Evidence model in one paragraph

Every capture appends a record (`CAPTURE`, `ATTEST`, `CUSTODY`, `INSPECTION`, `PAYMENT`,
`CORRECTION`) carrying the previous record's SHA-256. The evidence **tier** (1–5) is derived
from those records — reported, counterparty confirmed, field captured, payment verified,
verified by AUCTUM — and is always rendered as a named state with reasons, never a
percentage. **Badges** are declared claims (`VERIFIED`, `IN_REVIEW`, `FLAGGED`, `WAIVED`)
whose required set comes from the job family. A packet **seals** only when the tier is 5,
every required badge verifies, nothing is flagged, the chain is intact, the packet is in the
log, and an as-of date exists. Seals are reversible; records never are.

## Product, business, operations

| Document | Contents |
|---|---|
| [00-product-brief](docs/00-product-brief.md) | market, personas, unit economics, cold start, risks |
| [01-architecture](docs/01-architecture.md) | C4, data flow, offline sync, ERD, failure modes |
| [02-domain-and-state-machines](docs/02-domain-and-state-machines.md) | lifecycles, invariants |
| [03-api-contract](docs/03-api-contract.md) · [OpenAPI](docs/api/fieldwright.openapi.yaml) | v1 REST contract |
| [04-security-privacy-consent](docs/04-security-privacy-consent.md) | STRIDE, consent ledger, retention, redaction |
| [05-field-ops-runbook](docs/05-field-ops-runbook.md) | onboarding, disputes, SLIs, recovery |
| [06-design-system](docs/06-design-system.md) | tokens, accessibility audit |
| [07-gtm-launch-playbook](docs/07-gtm-launch-playbook.md) | positioning, supply-first launch, 90 days |
| [08-roadmap-and-decisions](docs/08-roadmap-and-decisions.md) | ADRs, phased roadmap |

## Status

Sample-book build. Verified locally: Android build, unit tests, static analysis. Not
verified: behaviour on physical Android hardware, CameraX on a real sensor, Play Console
distribution, a live payment rails provider, and a production backend (the contract and a
zero-dependency mock server exist in `backend/`).

Fieldwright is an ODASI Technologies Inc. product built on the AUCTUM protocol. Demo data
never enters a public log.
