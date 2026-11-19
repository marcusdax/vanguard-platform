# Vanguard Platform — B2B Partner Prospectus, Technical Assurance & Empirical Economics

**Prepared by:** Vanguard Field Networks (`vanguardfield.io`) · **Target Partners:** P&C Insurance Carriers, Regional Restoration Contractors, Underwriting / Risk APIs, Solar O&M Operators, and Supply Chain Principals

---

## 1. The Enterprise Verification Problem

Enterprise property, insurance, and distributed asset buyers lose billions annually across three structural failure modes:
1. **Unverified Field Origin & Spoofing:** Static uploaded JPEGs lack cryptographic proof of physical presence, hardware integrity, and monotonic sequence custody (`Play Integrity + Keybox CRL + IMU liveness` are typically absent).
2. **Unauthorized Practice / Licensing Contamination:** Standard contractor apps allow unlicensed field scouts to submit valuation fields or coverage opinions, creating carrier defense liabilities (`TX Ins. Code Ch. 4102`, `TREC 531.20`, `RESPA §8`).
3. **Unit-Economics Collapse on Rural/Suburban Dispatch:** Single-purpose field apps dispatch long solo drives without clustering around IRS `$0.76/mi` vehicle costs, creating high churn and missed SLA turnarounds.

**Vanguard solves all three at the protocol level** before an exhibit ever reaches your underwriting or claims desk.

---

## 2. Commercial Fee Schedule & Verified Exploratory Data Analysis

Vanguard separates marketplace monetization cleanly from worker compensation (**Invariant I-1 / Check C-12**): buyers/requesters fund an explicit escrow verification fee (`2.50%` to `15.00%`), while workers retain `100%` of stated task rates (`0 bps` operator fee).

### Canonical Fee Register (`registers/fees.csv`)

| Surface Identifier | Payer | Platform Fee (`bps`) | Milestone | Disclosure Standard |
| --- | --- | --- | --- | --- |
| `vanguard.b2b.bounty` | Enterprise Buyer | `1,000 bps` (`10.00%`) | `VERIFIED_CAPTURE` | Worker keeps `100%`; buyer pays `10.00%` escrow verification fee |
| `helios.local.b2c` | Community Requester | `500 bps` (`5.00%`) | `COUNTERPARTY_OR_AR_SCAN` | Worker keeps `100%`; requester pays `5.00%` community escrow |
| `atlas.roofing` | Restoration Contractor | `250 bps` (`2.50%`) | `ESTIMATE_OR_INVOICE` | `2.50%` contractor platform fee (Active) |
| `vanguard.marketplace` | Specialty Requester | `1,500 bps` (`15.00%`) | `SEALED_PACKET` | `15.00%` requester platform fee; zero operator deduction |
| `vanguard.instant.payout` | Worker (Optional) | `150 bps` (`$0.50–$5.00`) | `INSTANT_CASHOUT` | Standard ACH payout remains `$0.00` |

### Bounded Exploratory Data Analysis (EDA) Summary

To audit fee structure stability and bounty dispersion without altering raw source registers, bounded automated profiling (`exploratory-data-analysis` toolkit v1.4, Python stdlib local core) was executed on both `registers/fees.csv` and the extracted 10-vertical canonical rate table (`docs/eda/vanguard_vertical_rates.csv`, `N = 10` configured verticals `V-0A` through `V-08`):

- **Fee Register Integrity (`docs/eda/fees_profile.eda.json`, `5` rows, `8` columns):** Zero duplicate rows (`duplicate_row_count_in_scanned_rows: 0`), `rate_bps` spans `150` to `1,500` bps with median `500` bps (`Q1 = 250`, `Q3 = 1,000`, mean `680.0`, sample SD `564.14`).
- **Vertical Bounty Dispersion (`docs/eda/bounty_sensitivity.json`, `N = 10` verticals, `0` missing):**
  - **Mean Bounty:** `8,050` cents (`$80.50`) | **Sample SD:** `4,638.07` cents (`$46.38`)
  - **Median Bounty:** `8,000` cents (`$80.00`) | **IQR:** `6,475` cents (`Q1 = $47.75`, `Q3 = $112.50`) | **MAD:** `3,900` cents (`$39.00`)
  - **Outlier Sensitivity:** `0` verticals fall outside deterministic IQR fences (`[-4,937.5, 20,962.5]` cents); `10%` trimmed mean (`$79.63`) and `10%` winsorized mean (`$79.70`) closely align with median (`$80.00`), confirming balanced tier-to-tier pricing without extreme skew (`raw skewness = 0.097`).
  - **Scope & Limitations:** Reported statistics are complete-table descriptive aggregates over configured system parameters (not randomized observational market transaction telemetry) and permit no causal inference (`hypothesis_tests_run: 0`, raw files preserved unmodified).

---

## 3. Cryptographic Custody, Anti-Spoofing & Compliance Architecture

### A. Append-Only Postgres Ledger & Client-Side Verification
- Every capture, telemetry event, payment split, and correction appends sequentially per `subjectRef` under transaction-scoped `pg_advisory_xact_lock` serialization (`services/api/src/services/vanguard/ledgerChain.ts`).
- Database-level Postgres `BEFORE UPDATE OR DELETE` triggers enforce append-only immutability on `audit_ledger_entries`.
- Canonical UTF-8 NFC JSON serialization guarantees identical `SHA-256` hashes across Kotlin (`core:crypto`), Node (`@vanguard/trust-engine`), and the dependency-free `2,464`-byte browser verifier (`apps/web/public/verifier/vanguard-verify.min.js`).
- Company-scoped Merkle roots anchor leaves using RFC 6962 prefix conventions (`0x00` leaf, `0x01` node), allowing any buyer claims system or opposing counsel to validate inclusion offline via `GET /api/v1/public/vanguard/verify/:packetId`.

### B. Anti-Spoofing Gate (`TrickyStore` + IMU Jitter Liveness)
Before sensitive hardware-backed bounties clear, `services/api/src/services/vanguard/attestationGate.ts` and the Python Vision sidecar (`services/vision/main.py`) enforce:
1. **Single-Use Nonce Challenges:** Server-issued cryptographic challenges bound to subject and operator session.
2. **Play Integrity + Keybox CRL:** Rejects untrusted verdicts and revokes known leaked OEM bootloader keybox serials (e.g., `LEAKED_OEM_KEYBOX_*`).
3. **Organic IMU Jitter Variance Threshold (`var >= 0.0005`):** Rejects synthetically flat or zero-variance emulator accelerometer/gyroscope trajectories.

### C. Statutory Compliance & Neutrality Firewall
- **TX Ins. Code Ch. 4102 & TREC `531.20`:** Automated key sanitization (`rules.ts`) strips unauthorized adjustment/appraisal fields from Tier 1–3 payloads so field personnel function strictly as objective technical data collectors.
- **Tier 4 Dual-Sign Co-Signing (`RecordKind: ATTEST`):** Formal legal exhibits requiring `ATTEST` sign-off enforce active state license validation (`T4-01`) via Invariant `I-5`.
- **RESPA §8 Separation:** Spatial asset royalties use fixed fair-market data pricing decoupled from real estate settlement referrals (`FIXED_FAIR_MARKET_DATA_FEE`).

---

## 4. DePIN Spatial Licensing & Integration APIs

Enterprise risk teams can license fresh parcel condition datasets at deterministic programmatic rates without dispatching bespoke field visits:

- **120-Day Economic Half-Life Model (`economics.ts`):** Query price starts at `$16.00` (`1,600` cents) on capture day `0` and decays cleanly (`1600 * 0.5^(ageDays / 120)`), routing `50%` to the original capturer, `25%` to opt-in parcel homeowners, and `25%` to protocol treasury/burn.
- **Automated Stale Parcel Refresh (`>= 90` days):** Aging assets automatically boost refresh multipliers to summon re-capture in active H3 cells before underwriting models go stale.
- **Core B2B REST Endpoints:**
  - `GET /api/v1/public/vanguard/catalog` — Canonical vertical specs, fee schedule, and half-life curves
  - `POST /api/v1/vanguard/dispatch/from-storm/:stormEventId` — Auto-emit geo-scoped H3 inspection bounties from NOAA severe weather swaths
  - `POST /api/v1/vanguard/depin/assets/:id/license-query` — License parcel telemetry with instant royalty ledgering
  - `GET /api/v1/public/vanguard/verify/:packetId` — Retrieve full record chain, RFC 6962 inclusion proof, C2PA manifest stub, and Rekor anchor metadata

---

## 5. Research Methodology, Literature Grounding & References

1. **Laurie, B., Langley, A., & Kasper, E. (2013).** *Certificate Transparency* (`RFC 6962`). Internet Engineering Task Force (IETF). DOI: 10.17487/RFC6962 — Establishes the append-only Merkle tree `0x00`/`0x01` domain separation and verifiable inclusion proof design implemented in `@vanguard/trust-engine` and `vanguard-verify.min.js`.
2. **Brito, E., Hadachi, A., Kamm, L., & Norbisrath, U. (2025).** *Decentralized Proof-of-Location systems for trust, scalability, and privacy in digital societies*. Scientific Reports (Springer Nature). DOI: 10.1038/s41598-025-04566-4 — Grounds fault-tolerant spatial witnessing and anti-spoofing location verification requirements against emulator tampering.
3. **Alshater, M. M. (2025).** *Decentralized physical infrastructure networks (DePIN) tokenomics*. Frontiers in Blockchain. DOI: 10.3389/fbloc.2025.1644115 — Grounds Proof-of-Physical-Work incentive alignment and separation between verifiable physical delivery and royalty accounting.
4. **Kassis, T., Agarwal, V., He, Y., Patel, D., & Brueckner, A. M. (2026).** *Scientific Agent Skills: A Library of Procedural Knowledge for Research Agents*. arXiv:2609.00065 (`https://arxiv.org/abs/2609.00065`). Procedural methodology framework governing the bounded local exploratory data analysis (`exploratory-data-analysis`) and reproducible citation verification workflows used to generate Section 2 and Section 5 of this prospectus.
