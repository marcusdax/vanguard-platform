# 03 — API contract (v1)

**Status:** contract, implemented by `backend/mock-server.mjs` and documented machine-readably in
[docs/api/fieldwright.openapi.yaml](api/fieldwright.openapi.yaml) (OpenAPI **3.1.0**).
**Contract owner:** docs-gtm. **Server owner (target):** backend workstream.
**Client:** `core:data` via the `Contracts.kt` repositories; no feature module knows HTTP exists.

The server is authoritative for **money and lifecycle**. The client is authoritative for
**nothing except what it captured**, and even that is verified on upload: chains are re-walked,
signatures are checked, and tiers/badges/seals are re-derived. A client can claim a seal; the
server decides whether it exists.

---

## 1. Conventions

| Concern | Rule |
|---|---|
| Base URL | Local emulator: `http://10.0.2.2:8787/v1` · Production: `https://api.fieldwright.example/v1` (placeholder until a domain is chosen) |
| Media type | `application/json; charset=utf-8` for everything except `GET /packets/{id}/export`, which still returns JSON (file name and custody text are fields) |
| Auth | `Authorization: Bearer <token>` on every endpoint except `POST /auth/token` and `GET /healthz` |
| Device attestation | Sent once to `/auth/token` in the body (`attestation`). The resulting token is device-bound; there is no refresh endpoint in v1 |
| Money | Integer **cents**, always suffixed `Cents` (e.g. `payoutCents`, `feeCents`). No decimal money anywhere on the wire |
| Time | ISO-8601 UTC with milliseconds and `Z`, regex `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$`. Model `*EpochMs` fields serialize as `*At` (mapping in §4) |
| Hashes | 64 lowercase hex characters, regex `^[0-9a-f]{64}$`. Genesis is 64 zeros |
| Signatures | Base64 of an ECDSA P-256 DER signature; accompanied by `signerKeyId` |
| Enums | Kotlin constant names, uppercase (`PACKET_READY`, `INSTANT`, `VERIFIED`). Never human labels |
| IDs | Opaque strings, ≤ 64 chars. Clients must not parse them |
| Idempotency | `Idempotency-Key` header (8–128 chars) is **required** on every POST that creates or changes money/evidence directly: accept, decline, arrival, en-route, capture start, packet submit, correction, anchor, payout request. Missing → `400 IDEMPOTENCY_KEY_REQUIRED`. `POST /sync/batch` carries one key per event instead of a batch header |
| Idempotent replay | Same key + same body → original status and body, plus `Idempotent-Replayed: true`. Same key + different body → `422 IDEMPOTENCY_KEY_REUSED` |
| Concurrency | Strong `ETag` (`"<resourceVersion>"`). `If-Match` required on `PUT /me/availability`; optional but honoured on corrections. Stale → `412 PRECONDITION_FAILED` |
| Version conflict | When a write is rejected because the aggregate changed and the client sent no `If-Match`, the server returns `409 VERSION_CONFLICT` with `details.currentVersion`. Clients may also send `X-FW-Base-Version: <n>` on job/packet mutations; a mismatch with the current aggregate version returns `409 VERSION_CONFLICT` before any rule runs |
| Pagination | Opaque `cursor`; `limit` 1–100 (default 20). Response `{ "items": [...], "nextCursor": "...|null", "asOf": "<timestamp>" }`. Cursors never encode business state |
| Rate limits | 120 requests/min per token by default; endpoint buckets: accept 12/min, packets 20/min, payouts 6/min, sync batches 60/min. Headers: `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`. 429 includes `Retry-After` seconds |
| Versioning | Path `/v1`. Additive changes stay in v1; a hash-affecting or breaking change needs `/v2`. Deprecations: `Deprecation` + `Sunset` headers, minimum 90 days notice |
| Request tracing | Every response includes `X-Request-Id`. Error bodies repeat it as `requestId` |
| CORS | Not enabled. This is a mobile API |

### 1.1 Error envelope

```json
{
  "error": {
    "code": "JOB_STATE_INVALID",
    "message": "Job job_fw1048 is ACCEPTED; only EN_ROUTE or CANCELLED are legal from here.",
    "retryable": false,
    "requestId": "req_01J8Z3K2",
    "details": { "currentStatus": "ACCEPTED", "allowed": ["EN_ROUTE", "CANCELLED"] }
  }
}
```

`message` is for engineers and support. `details` is machine-readable and is what the app renders
when it can explain a rule better than the server sentence.

### 1.2 Error catalog

| HTTP | Code | Retryable | When |
|---:|---|---|---|
| 400 | `VALIDATION_FAILED` | no | Malformed JSON, wrong type, unknown field, bad timestamp/hash pattern |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | no | Missing key on a mutating POST |
| 401 | `UNAUTHENTICATED` | no | Missing/expired/invalid bearer token |
| 403 | `FORBIDDEN` | no | Token does not own the resource |
| 403 | `CREDENTIAL_REQUIRED` | no | Operator lacks the job's required credential. `details` names the credential and the shortest fix (`ecosystem/04` C-11) |
| 403 | `ATTESTATION_REJECTED` | no | Unknown device attestation at token issue |
| 404 | `NOT_FOUND` | no | Unknown id (including ids that belong to another operator) |
| 405 | `METHOD_NOT_ALLOWED` | no | The path exists but not for this method |
| 409 | `OFFER_EXPIRED` | no | `expiresAt` has passed; offer is `EXPIRED` |
| 409 | `OFFER_TAKEN` | no | Another operator accepted first; offer is `WITHDRAWN` |
| 409 | `JOB_STATE_INVALID` | no | Transition not in `JobLifecycle` |
| 409 | `PACKET_CONFLICT` | no | Same job already has a packet with a different head hash |
| 409 | `VERSION_CONFLICT` | no | Aggregate changed; client should refetch and re-apply |
| 412 | `PRECONDITION_FAILED` | no | `If-Match` did not match the current ETag |
| 422 | `IDEMPOTENCY_KEY_REUSED` | no | Same key, different request body |
| 422 | `INVALID_AMOUNT` | no | Payout amount ≤ 0 or > available balance |
| 422 | `DERIVATION_MISMATCH` | no | Submitted tier/badges/seal do not match server re-derivation |
| 422 | `CHAIN_INVALID` | no | Chain hash mismatch at `details.seq` |
| 422 | `SIGNATURE_INVALID` | no | Packet signature does not verify against `signerKeyId` |
| 429 | `RATE_LIMITED` | yes | Bucket exhausted; respect `Retry-After` |
| 500 | `INTERNAL` | maybe | Unhandled server error; safe to retry idempotent requests |
| 503 | `UNAVAILABLE` | yes | Dependency unavailable (log, PSP); back off with jitter |

The client's rule for `retryable: true` is the sync state machine, not ad-hoc try/catch: backoff
2 s → 4 s → 8 s → … with jitter, cap 5 min, max 8 attempts, then dead letter.

---

## 2. Authentication and device binding

```
POST /v1/auth/token
{
  "deviceId": "and_9f2c…",           // app-generated, stable per install
  "attestation": "base64…",          // Play Integrity token; in mock mode "dev-attestation"
  "appVersion": "0.1.0",
  "sdkInt": 34
}
→ 201
{
  "token": "fw_…",
  "expiresAt": "2026-11-17T13:00:00.000Z",
  "operatorId": "op_7d1a"
}
```

- The token is bound to `deviceId`. Using it from a different device id in `X-FW-Device-Id` →
  `401 UNAUTHENTICATED` (documented as a planned control; the mock server accepts `fw_dev_` tokens).
- Attestation failure → `403 ATTESTATION_REJECTED`. There is no "skip attestation" flag in the
  production path; the mock server's `dev-attestation` is explicitly a mock feature.
- Token lifetime 60 minutes in v1; re-issue via `/auth/token` (idempotent per device).
- **No scope gates a payout.** A credential may block accepting a roof job; it may never block
  withdrawing money already earned (estate invariant I-1, `ecosystem/04` C-12).

---

## 3. Endpoints

Auth column: `—` public, `Bearer` authenticated, `+Idem` requires `Idempotency-Key`, `+Match`
requires/honours `If-Match`. All authenticated endpoints are scoped to the token's operator.

### 3.1 System, operator, availability

| Method | Path | Auth | Purpose | Success |
|---|---|---|---|---|
| GET | `/healthz` | — | Liveness for local dev and CI | `200 {status, now, seeded}` |
| POST | `/auth/token` | — | Exchange device attestation for a bearer token | `201 TokenResponse` |
| GET | `/me` | Bearer | Operator profile (`Operator`) | `200` |
| GET | `/me/availability` | Bearer | Online state, radius, auto-accept policy | `200` + `ETag` |
| PUT | `/me/availability` | Bearer +Match | Replace availability (online toggle, radius, policy) | `200` + new `ETag` |
| GET | `/me/balances` | Bearer | Derived balances (`Balances`) | `200` |
| GET | `/me/earnings` | Bearer | Earnings summary (`EarningsSummary`) | `200` |
| GET | `/me/ledger?cursor&limit` | Bearer | Ledger page | `200` page of `LedgerEntry` |

`PUT /me/availability` body — every field replaced, no partial update:

```json
{
  "isOnline": true,
  "radiusMeters": 15000,
  "autoAccept": {
    "enabled": true, "minPayoutCents": 3000, "maxDistanceMeters": 12000,
    "maxActiveStack": 2, "allowedTypes": ["ROOF_HAIL", "RPZ_TEST"],
    "requireCredentialMatch": true
  },
  "areaLabel": "Creekside · MUD 14"
}
```

Server rules: `radiusMeters` must be one of `5000, 10000, 15000, 25000`; `maxActiveStack` ≤ 3;
`minPayoutCents` ≥ `MIN_PAYOUT_FLOOR_CENTS` (2500). Violation → `422 VALIDATION_FAILED`.

### 3.2 Jobs and arrival

| Method | Path | Auth | Purpose | Success |
|---|---|---|---|---|
| GET | `/jobs?scope=offers\|active\|done&cursor&limit` | Bearer | Job list projections for Jobs screen | `200` page of `Job` |
| GET | `/jobs/{jobId}` | Bearer | Job detail | `200` + `ETag` |
| POST | `/jobs/{jobId}/en-route` | Bearer +Idem | `ACCEPTED → EN_ROUTE` | `200 Job` |
| POST | `/jobs/{jobId}/arrivals` | Bearer +Idem | Geofence arrival check/confirm | `200 ArrivalCheck` |
| POST | `/jobs/{jobId}/capture/start` | Bearer +Idem | `ON_SITE → IN_PROGRESS` | `200 Job` |

Arrival request:

```json
{
  "fix": { "lat": 30.2672, "lng": -97.7431, "recordedAt": "2026-11-17T12:04:11.220Z",
           "accuracyMeters": 8.5, "source": "GPS" },
  "overrideReason": null
}
```

Arrival response:

```json
{
  "accepted": true, "insideGeofence": true, "distanceMeters": 41.7,
  "accuracyMeters": 8.5,
  "detail": "Inside the 75 m geofence.",
  "overrideRequired": false
}
```

Rules: an arrival is accepted when `distanceMeters ≤ geofenceRadiusMeters + accuracyMeters` and
`accuracyMeters ≤ 100`. If outside and no `overrideReason`, the response is `200` with
`accepted: false, overrideRequired: true` (this is a legitimate field situation, not an error).
If an override is supplied, `accepted: true` and the packet is flagged for review. A fix with
`source: "SIMULATED"` never satisfies the gate for a non-demo job; the server returns
`409 JOB_STATE_INVALID` with `details.reason = "SIMULATED_FIX_NOT_ADMISSIBLE"`. The app must never
be able to invent an arrival.

### 3.3 Offers

| Method | Path | Auth | Purpose | Success |
|---|---|---|---|---|
| GET | `/offers` | Bearer | Current pending offers for this operator | `200 {items, asOf}` |
| POST | `/offers/{offerId}/accept` | Bearer +Idem | Accept within TTL | `200 {offer, job, acceptedAt}` |
| POST | `/offers/{offerId}/decline` | Bearer +Idem | Pass with a human reason | `204` |

Decline body: `{ "reason": "outside my radius" }` — free text, ≤ 200 chars, required. Rejections
carry a reason all the way to dispatch analytics; "no reason" is not offered as a choice.

Accept response (the offer is returned so the client can reconcile an offline acceptance):

```json
{
  "offer": { "...": "Offer with status ACCEPTED" },
  "job": { "...": "Job with status ACCEPTED and acceptedAt" },
  "acceptedAt": "2026-11-17T12:01:03.114Z"
}
```

Server checks in one transaction: offer is `PENDING` for this operator; `now < expiresAt`; active
job count < 3; credentials cover `JobType.requiredCredentials`; fatigue cap not exceeded. Any
failure is a 4xx in the catalog above with `details` naming the rule.

### 3.4 Packets, verification, export, anchoring, corrections

| Method | Path | Auth | Purpose | Success |
|---|---|---|---|---|
| GET | `/packets?filter=all\|sealed\|attention&cursor&limit` | Bearer | Vault list | `200` page of `EvidencePacket` |
| GET | `/packets/{packetId}` | Bearer | Packet detail | `200` + `ETag` |
| POST | `/jobs/{jobId}/packets` | Bearer +Idem | Upload a sealed packet | `201 PacketReceipt` |
| POST | `/packets/{packetId}/verify` | Bearer | Re-walk chain, verify signature | `200 VerifyReceipt` |
| GET | `/packets/{packetId}/export` | Bearer | Canonical JSON + custody text | `200 PacketExport` + `ETag` |
| POST | `/packets/{packetId}/anchor` | Bearer +Idem | Append leaf to the log, return inclusion proof | `200 AnchorReceipt` |
| POST | `/packets/{packetId}/corrections` | Bearer +Idem +Match | Append a `CORRECTION` record | `200 EvidencePacket` |
| GET | `/log/head` | Bearer | Current log root and size | `200 LogHead` |

Upload body — the sealed packet, minus server-derived fields:

```json
{
  "jobId": "job_fw1048",
  "jobType": "ROOF_HAIL",
  "operatorId": "op_7d1a",
  "title": "Hail on the south slope · 14 Creekside Row",
  "requesterLabel": "Northgate Mutual · claim 88412",
  "site": { "address": "14 Creekside Row", "unit": null,
            "point": { "lat": 30.2672, "lng": -97.7431,
                       "recordedAt": "2026-11-17T11:58:02.000Z",
                       "accuracyMeters": 6.0, "source": "GPS" },
            "geofenceRadiusMeters": 75, "accessNotes": "Gate code 4412",
            "planLabel": "Creekside · MUD 14" },
  "consent": { "name": "Dana Whitfield", "role": "Homeowner",
               "scopeText": "Roof surfaces, slopes and penetrations. Not the interior of the home. Not the street.",
               "method": "IN_APP_SIGNATURE", "capturedAt": "2026-11-17T12:02:40.001Z",
               "signatureHash": "<64-hex>", "verifiedByOperatorId": "op_7d1a" },
  "checklist": [ { "key": "slopes", "result": "PASS", "note": "", "mediaIds": ["med_01"] } ],
  "media": [ { "id": "med_01", "kind": "PHOTO", "sha256": "<64-hex>", "bytes": 184320,
               "capturedAt": "2026-11-17T12:05:11.000Z", "width": 3024, "height": 4032,
               "redaction": "NOT_NEEDED", "source": "CAMERA", "note": "" } ],
  "measurements": [ { "id": "msr_01", "label": "South slope hail strike",
                      "meters": 0.041, "referenceLabel": "Shingle course",
                      "referenceMeters": 0.9, "pixelSpan": 142.0,
                      "calibratedAt": "2026-11-17T12:09:44.000Z", "source": "CAMERA" } ],
  "records": [ { "seq": 1, "kind": "CAPTURE", "at": "…", "actorId": "op_7d1a",
                 "summary": "…", "payload": { "mediaId": "med_01" },
                 "prevHash": "0000…0000", "hash": "<64-hex>",
                 "signatureBase64": null, "signerKeyId": null } ],
  "signatureBase64": "<base64 ECDSA P-256 DER over packetHash>",
  "signerKeyId": "fw-keystore-op_7d1a-1",
  "asOf": "2026-11-17T12:10:00.000Z"
}
```

Server processing, in order (each step can fail independently):

0. **Chain preimage.** A record hash must reproduce
   `SHA-256(canonicalJson({seq, kind, at, actorId, summary, payload, prevHash}))` — sorted keys,
   no whitespace, string values in `payload`, ISO-8601 `at`. The client's canonical encoder and
   the server must agree byte for byte; this is the one integration detail that cannot drift.
1. Validate shapes, hash patterns, time formats (`400 VALIDATION_FAILED`).
2. `packetHash = records.last.hash`; re-walk chain from `GENESIS`; mismatch →
   `422 CHAIN_INVALID` with `details.seq`, `details.expectedPrevHash`, `details.actualPrevHash`.
3. Verify `signatureBase64` over `packetHash` with `signerKeyId` (mock: shape only, documented) →
   `422 SIGNATURE_INVALID`.
4. Re-derive tier and badge states from records (server-side); compare with the client's claims
   **if present** → `422 DERIVATION_MISMATCH` (invariant INV-6: no client-asserted tier). A
   client may omit `tier`, `seal` and `badges`; if it sends them, they must match the server's
   derivation. The mock derives tier 5 when every badge required by the job family is `VERIFIED`,
   nothing is `FLAGGED`, and the chain verifies — that is the seal gate the app implements.
5. Check `demo`: a demo packet is accepted into the demo store but **never anchored**; response
   includes `"anchored": false, "reason": "DEMO_EXCLUDED_FROM_LOG"` (invariant I-4).
6. Check job state: must be `SEALED` (or `PACKET_READY` for a resubmission after withdrawal). A
   packet that is not sealable is refused with `409 JOB_STATE_INVALID`,
   `details.reason: SEAL_NOT_EARNED`.
7. Conflict: existing packet for the job with a different `packetHash` → `409 PACKET_CONFLICT`.
   Same hash → `200` replay of the receipt with `Idempotent-Replayed: true`.
8. Store, append Merkle leaf, return receipt.

`PacketReceipt`:

```json
{
  "packetId": "pkt_fw1048", "packetHash": "<64-hex>",
  "merkleLeaf": "<64-hex>", "recordCount": 6,
  "uploadedAt": "2026-11-17T12:10:04.512Z",
  "status": "SUBMITTED", "anchored": true
}
```

`VerifyReceipt` wraps the model's `ChainVerification` plus signature status:

```json
{
  "valid": true, "entries": 6, "brokenAtSeq": null, "headHash": "<64-hex>",
  "detail": "Chain verified from genesis; 6 records.",
  "signatureValid": true, "signerKeyId": "fw-keystore-op_7d1a-1",
  "included": true, "verifiedAt": "2026-11-17T12:11:00.000Z"
}
```

Correction body: `{ "note": "Gauge frame replaced; needle not transcribed.", "supersedesSeq": 3 }`.
The server appends a `CORRECTION` record (no edit), re-derives badge state, and may lower the tier
and terminate the seal. The response is the updated `EvidencePacket` with a new `ETag`.

### 3.5 Ledger, payouts

| Method | Path | Auth | Purpose | Success |
|---|---|---|---|---|
| POST | `/payouts/quote` | Bearer | Fee-disclosed quote; pure calculation | `200 PayoutQuote` |
| POST | `/payouts` | Bearer +Idem | Request a payout | `201 PayoutRequest` |
| GET | `/payouts?cursor&limit` | Bearer | Payout history | `200` page of `PayoutRequest` |
| GET | `/payouts/{payoutId}` | Bearer | Single payout | `200` |

Quote request: `{ "amountCents": 45000, "mode": "INSTANT" }` → quote:

```json
{
  "amountCents": 45000, "feeCents": 500, "netCents": 44500,
  "mode": "INSTANT", "etaLabel": "Minutes to debit card",
  "feeDisclosure": "Instant payout fee: 1.5% (capped at $5.00). You receive $445.00."
}
```

Payout request: `{ "amountCents": 45000, "mode": "INSTANT" }` (fee is recomputed server-side from
the same `FeeSchedule`; a client-supplied fee is ignored and logged if it differs). Rules: amount
> 0, ≤ `availableCents`; a `STANDARD` payout is free; a `FAILED` payout surfaces
`failureReason` and is retried only with a new idempotency key after acknowledgement.

### 3.6 Sync batch

One endpoint for the outbox, so a phone that reconnects after two days sends one request rather
than 40 round trips. Events are ordered per aggregate; the server returns a per-event result and
the batch itself is idempotent per event key.

```
POST /v1/sync/batch
{
  "deviceId": "and_9f2c…",
  "sentAt": "2026-11-17T12:12:00.000Z",
  "events": [
    { "eventId": "evt_01", "idempotencyKey": "idem_…", "type": "job.en_route",
      "aggregateId": "job_fw1048", "occurredAt": "2026-11-17T11:40:00.000Z",
      "payload": { "jobId": "job_fw1048" } }
  ]
}
→ 200
{
  "results": [
    { "idempotencyKey": "idem_…", "eventId": "evt_01", "status": "APPLIED",
      "result": { "jobId": "job_fw1048", "status": "EN_ROUTE" } }
  ],
  "serverTime": "2026-11-17T12:12:00.412Z",
  "nextRetryAfterMs": null
}
```

| Per-event status | Meaning | Client action |
|---|---|---|
| `APPLIED` | Applied now | Ack and remove from outbox |
| `DUPLICATE` | Key seen before; original result returned | Ack and remove (this is success) |
| `REJECTED` | Permanent failure (validation, state, credential) | Move to dead letter, surface in Brief |
| `RETRY` | Transient (5xx, rate limit, dependency down) | Keep in outbox; honour `nextRetryAfterMs` |
| `CONFLICT` | Aggregate changed; needs human reconciliation | Keep local, surface in Brief, do not silently overwrite |

Event types in v1: `job.en_route`, `arrival.confirm`, `capture.start`, `packet.submit`,
`correction.append`, `payout.request`, `availability.upsert`, `offer.accept`, `offer.decline`.

---

## 4. Model ↔ wire mapping

The model is the server's schema and the client's domain. One rename is deliberate: model
`*EpochMs` (Long, epoch milliseconds) serialises as `*At` (ISO-8601). Nothing else is renamed,
so a field-name search works across Kotlin, OpenAPI, the mock server and these docs.

| Model type (`core:model`) | Wire type | Field mapping notes |
|---|---|---|
| `Operator` | `Operator` | `tierName` is a read-only derived field on the wire |
| `Availability` | `Availability` | `updatedAtEpochMs → updatedAt` |
| `AutoAcceptPolicy` | `AutoAcceptPolicy` | `allowedTypes` array of `JobType` |
| `Job` | `Job` | `scheduledForEpochMs → scheduledFor?`, `acceptedAtEpochMs → acceptedAt?`, `arrivedAtEpochMs → arrivedAt?`, `sealedAtEpochMs → sealedAt?`; `payoutCents` and `dollarsPerHour` are read-only derived |
| `Requester` | `Requester` | `kind` enum |
| `SiteRef` | `Site` | `point` is `GeoPoint` |
| `GeoPoint` | `GeoPoint` | `recordedAtEpochMs → recordedAt`; `source` enum `GPS/NETWORK/SIMULATED` |
| `ChecklistStep` | `ChecklistStep` | `proofKind` enum |
| `Offer` | `Offer` | `offeredAtEpochMs → offeredAt`, `expiresAtEpochMs → expiresAt`; `remainingMillis` is a client computation |
| `MarketSnapshot` | `Market` | `nowEpochMs → asOf`, `hotZones` array |
| `HotZone` | `HotZone` | `point` GeoPoint |
| `Consent` | `Consent` | `capturedAtEpochMs → capturedAt`; `signatureHash` is 64-hex or null |
| `MediaRef` | `MediaRef` | `capturedAtEpochMs → capturedAt`; `sha256` 64-hex; `bytes` integer |
| `ChainRecord` | `Record` | `atEpochMs → at`; `payload` is `object<string,string>`; `prevHash`/`hash` 64-hex |
| `Badge` | `Badge` | `key`, `state`, `required`, `rationale`, `evidenceRefs` |
| `GateCheck` | `Gate` | Derived at seal time; returned inside packets, never uploaded as truth |
| `EvidencePacket` | `EvidencePacket` | `createdAtEpochMs → createdAt`, `sealedAtEpochMs → sealedAt?`, `asOfEpochMs → asOf`; derived `packetHash`, `shortHash`, `recordCount` are read-only |
| `Measurement` | `Measurement` | `calibratedAtEpochMs → calibratedAt`; `feet` is read-only |
| `ChainVerification` | `VerifyReceipt.chain` + top-level fields | `brokenAtSeq` nullable |
| `PacketExport` | `PacketExport` | `canonicalJson` and `custodyText` are strings |
| `LedgerEntry` | `LedgerEntry` | `atEpochMs → at`; `amountCents` signed |
| `PayoutQuote` / `PayoutRequest` | same | `requestedAtEpochMs → requestedAt`, `completedAtEpochMs → completedAt?` |
| `Balances` / `EarningsSummary` | same | all `*Cents` integer; `milesDrivenWeek` number |
| `SyncReport` / `SyncState` | `SyncBatchResponse` / client-local | There is no `GET /sync/state`; sync health is device-local by design |

**Enums on the wire** are the Kotlin constant names: `JobStatus`, `BadgeKey`, `BadgeState`,
`EvidenceTier` (serialised as the integer 1–5 plus `tierName`), `SealState`, `RecordKind`,
`StepResult`, `ProofKind`, `MediaKind`, `MediaSource`, `RedactionState`, `ConsentMethod`,
`LedgerKind`, `LedgerStatus`, `PayoutMode`, `PayoutStatus`, `RequesterKind`, `Credential`,
`JobType`, `SyncPhase`.

---

## 5. Examples that pin down ambiguous behaviour

### 5.1 Idempotent accept, twice

```
POST /v1/offers/off_1021/accept   Idempotency-Key: idem-accept-off_1021
→ 200 {offer, job(ACCEPTED)}                     Idempotent-Replayed: false

(device retries after timeout, same key and body)
POST /v1/offers/off_1021/accept   Idempotency-Key: idem-accept-off_1021
→ 200 {offer, job(ACCEPTED)}                     Idempotent-Replayed: true
```

The second call does not create a second acceptance, does not touch the ledger, and does not
extend the SLA. If the device had derived a different key for the same tap, the server would
have returned `409 OFFER_TAKEN` on the second call — which is exactly why keys are derived from
canonical inputs, not generated per attempt.

### 5.2 Same key, different body

```
POST /v1/payouts  Idempotency-Key: idem-payout-1  {amountCents: 45000, mode: "INSTANT"}
→ 201 payout_01
POST /v1/payouts  Idempotency-Key: idem-payout-1  {amountCents: 99000, mode: "INSTANT"}
→ 422 IDEMPOTENCY_KEY_REUSED
```

### 5.3 Stale availability write

```
GET /v1/me/availability          → 200, ETag: "v7"
PUT /v1/me/availability If-Match: "v6" → 412 PRECONDITION_FAILED (details.currentETag: "v7")
```

The app refetches, re-applies the user's intent to the fresh object, and retries with `"v7"`.
It never blind-overwrites, because doing so could silently re-enable auto-accept the operator
had just tuned off on another device.

### 5.4 Packet conflict

```
POST /v1/jobs/job_fw1048/packets  (hash A)  → 201 pkt_A
POST /v1/jobs/job_fw1048/packets  (hash B, new key) → 409 PACKET_CONFLICT
```

The app keeps **both** local records, uploads nothing further, and surfaces the conflict in Brief
for a support decision. It never deletes the local packet to make the error go away.

---

## 6. What the mock server does and does not do

Implemented in `backend/mock-server.mjs` (zero dependencies, Node ESM, deterministic seed):

| Implemented | Not implemented (documented honestly) |
|---|---|
| Every path in §3, with the error codes and status codes above | Persistence — state is in memory and resets on restart |
| Idempotency store with replay and reuse detection | Real device attestation (accepts `dev-attestation`) |
| Strong ETags, `If-Match` 412, optional `X-FW-Base-Version` → `409 VERSION_CONFLICT` | ECDSA verification (validates signature shape and `signerKeyId`; the real server verifies) |
| Chain re-walk with SHA-256 (`node:crypto`) against the documented canonical preimage, and Merkle inclusion proofs (real RFC 6962-style leaf/node hashing) | Rate limiting that matters (headers and 429 exist, counters are generous) |
| Deterministic seeded demo book: FW-0994, FW-1012, FW-1048, FW-1051, 4 available jobs, 2 offers, ledger, payouts, log head | PSP settlement: an INSTANT payout is marked `PAID`, a STANDARD payout stays `PROCESSING`; no money moves |
| `--port` and `--latency` flags for emulator and flaky-network testing | TLS, CORS, horizontal scaling |

Two mock behaviours that differ from the written production machine, on purpose and only in the
mock: (a) a payout marks whole ledger entries `PAID` and books any unspent remainder as a new
`ADJUSTMENT` entry (append-only), where production allocates per entry; (b) rate-limit buckets
are per token and per endpoint family, with the quotes endpoint in its own bucket.

Run it:

```bash
node backend/mock-server.mjs --port 8787
node backend/mock-server.mjs --port 8787 --latency 300   # simulate 300 ms p50
```

The Android emulator reaches the host at `10.0.2.2`, so the debug build's base URL is
`http://10.0.2.2:8787/v1`. Details and curl examples: `backend/README.md`.

---

## 7. Compatibility and change policy

| Change | Version impact | Client rule |
|---|---|---|
| Add a response field | none (additive) | Ignore unknown fields |
| Add optional request field | none | Old clients keep working |
| New enum value in a response | minor | Client renders an "unknown state" fallback, never crashes |
| Remove/rename a field, change a hash rule, change money semantics | **major** (`/v2`) | Never silently accepted |
| New endpoint | minor | Opt-in by the client that needs it |
| New idempotency requirement | major | A client that does not send the key receives `400` and must upgrade |

The hash-affecting rule is the estate's: a change to canonicalisation, leaf hashing or node
hashing breaks every historical proof, so it is a major version *and* a new log (`ecosystem/04`
§8). The verifier is the component that must be frozen forever.

