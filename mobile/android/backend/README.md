# Fieldwright backend mock

Zero-dependency Node (ESM) implementation of the Fieldwright v1 API for Android development,
demo builds and CI. It is a **contract mock**, not a production service.

- Machine contract: [`docs/api/fieldwright.openapi.yaml`](../docs/api/fieldwright.openapi.yaml)
- Written contract: [`docs/03-api-contract.md`](../docs/03-api-contract.md)
- Client interfaces: `core/data/.../Contracts.kt`

The mock exists so that client work can be built against real status codes, real idempotency
semantics, real ETag conflicts, a real SHA-256 record chain and a deterministic seeded book.

---

## Run

Requires Node 18+ (no `npm install`, no dependencies).

```bash
# from the repository root (the directory containing app/, core/, feature/)
node backend/mock-server.mjs --port 8787

# optional fixed-ish latency for flaky-network testing (p50, ±50% jitter)
node backend/mock-server.mjs --port 8787 --latency 300
```

| Flag | Default | Meaning |
|---|---|---|
| `--port`, `-p` | `8787` | Listen port on `0.0.0.0` |
| `--latency`, `-l` | `0` | Added delay in ms, p50 with ±50% jitter |
| `--help`, `-h` | — | Usage |

The server prints the seeded ids and the log head on boot. State is **in memory**: restart to
reset the world. `Ctrl+C` shuts down cleanly.

### Android emulator

The emulator reaches the host loopback at `10.0.2.2`:

```
Base URL: http://10.0.2.2:8787/v1
```

Use it from a debug build only. If the app uses cleartext HTTP in debug, the manifest needs a
debug-only `networkSecurityConfig` entry for `10.0.2.2` (or `usesCleartextTraffic` in the debug
manifest). Production must be HTTPS; the mock does not do TLS.

Physical device on the same Wi-Fi: use the host's LAN address and open the port in the firewall.

---

## Quickstart with curl

```bash
# 1. health
curl -s http://127.0.0.1:8787/healthz

# 2. token (mock accepts the dev attestation string; "reject" returns 403 ATTESTATION_REJECTED)
curl -s -X POST http://127.0.0.1:8787/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev_01","attestation":"dev-attestation","appVersion":"0.1.0","sdkInt":34}'

# Any token that starts with fw_dev_ is accepted by the mock; production tokens are opaque.
TOKEN=fw_dev_op_7d1a_0000000000000000

# 3. operator, availability, balances
curl -s http://127.0.0.1:8787/v1/me -H "Authorization: Bearer $TOKEN"
curl -s http://127.0.0.1:8787/v1/me/availability -H "Authorization: Bearer $TOKEN"
curl -s http://127.0.0.1:8787/v1/me/balances -H "Authorization: Bearer $TOKEN"

# 4. offers and an idempotent accept
curl -s http://127.0.0.1:8787/v1/offers -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://127.0.0.1:8787/v1/offers/off_1021/accept \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: idem-accept-off-1021' \
  -H 'Content-Type: application/json' -d '{}'

# 5. money with disclosure
curl -s -X POST http://127.0.0.1:8787/v1/payouts/quote \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amountCents":45000,"mode":"INSTANT"}'
```

PowerShell equivalent:

```powershell
$h = @{ Authorization = 'Bearer fw_dev_op_7d1a_0000000000000000' }
Invoke-RestMethod http://127.0.0.1:8787/v1/me/balances -Headers $h
Invoke-RestMethod -Method Post http://127.0.0.1:8787/v1/offers/off_1021/accept -Headers ($h + @{ 'Idempotency-Key' = 'idem-accept-off-1021' }) -ContentType 'application/json' -Body '{}'
```

---

## Seeded world (deterministic)

Ids and hashes are fixed; only "now" is live, so offers at boot get a 45 s TTL from start time.

| Seeded thing | Value |
|---|---|
| Operator | `op_7d1a` · Marisol Vega · Lead inspector · credentials RPZ, ROOF, PROP |
| Availability | online, 15 km, auto-accept under $30, max 2 stacked |
| Sealed packets | `pkt_fw0994` (roof, tier 5, anchored), `pkt_fw1012` (RPZ, tier 5, paid) |
| Attention packet | `pkt_fw1060` — `MEASURED` badge `FLAGGED`, one frame `redaction = REQUIRED` |
| Demo packet | `pkt_fw1055` — `demo = true`, seal shown locally, **never anchored** |
| Job awaiting upload | `job_fw1048` (SEALED, no packet) — use this to test `POST /jobs/{id}/packets` |
| Scheduled job | `job_fw1051` (PROPERTY_WALK, scheduled +2 h) |
| Available jobs | `job_avail_2201` METER_CLUSTER, `2202` PROPERTY_WALK, `2203` CLAIM_SWEEP, `2204` ROOF_HAIL, `2205` CLAIM_SWEEP requiring `DRONE_PILOT` (to exercise `403 CREDENTIAL_REQUIRED`) |
| Live offers | `off_1021` (ROOF_HAIL, $517.50 at 1.15× surge), `off_1022` (METER_CLUSTER, $28.00) |
| Ledger | $450.00 available, $280.00 pending, $95.00 paid lifetime, $1.43 instant fee paid |
| Payouts | `pay_1012` PAID ($95.00, fee $1.43) · `pay_0999` FAILED with a reason |
| Log | leaves for `pkt_fw1012`, `pkt_fw0994`; demo packet excluded |

Ids are stable so client tests can reference them. Restart the server to reset mutated state.

---

## Contract behaviours implemented

| Behaviour | Notes |
|---|---|
| Idempotency | `Idempotency-Key` required on mutating POSTs; same key + same body replays with `Idempotent-Replayed: true`; same key + different body → `422 IDEMPOTENCY_KEY_REUSED` |
| ETag | `ETag: "vN"` on versioned resources; `If-Match` required on `PUT /me/availability`, honoured on corrections; stale → `412 PRECONDITION_FAILED` |
| Version conflict | Optional `X-FW-Base-Version` header on job/packet mutations; mismatch → `409 VERSION_CONFLICT` with `details.currentVersion` |
| Record chain | Re-walked from 64-zero genesis on upload; broken linkage, bad seq or hash mismatch → `422 CHAIN_INVALID` with `details.seq` |
| Merkle log | RFC 6962-style `leafHash = SHA-256(0x00 || data)`, `node = SHA-256(0x01 || l || r)`; anchor returns a real audit path. A boot-time self-test generates and verifies proofs for tree sizes 1–12 at every index, and refuses to start if a valid proof fails or a forged proof (bad leaf, root, audit node or index) is accepted |
| Derivation | Tier and badge state are re-derived on upload; a client-claimed tier/seal that disagrees → `422 DERIVATION_MISMATCH` (claims are optional; send them only if your engine matches) |
| Demo exclusion | Demo packets upload but are not anchored; `POST /anchor` on a demo packet → `409` with `reason: DEMO_EXCLUDED_FROM_LOG` |
| Money | Integer cents, half-up basis points, instant fee 1.5% min $0.50 max $5.00, platform fee 15% requester-paid, quote always carries `feeDisclosure` |
| Rate limits | Per-token buckets with `RateLimit-*` headers; exhausted → `429` + `Retry-After` |
| Pagination | Opaque cursor + `limit` 1–100 |
| Errors | One envelope: `{ error: { code, message, retryable, requestId, details? } }` |

### Canonical record preimage — the one integration detail that must match

The server recomputes every record hash. Both sides must agree byte for byte:

```
hash = SHA-256( canonicalJson({
  seq, kind, at, actorId, summary, payload, prevHash
}) )
```

- `canonicalJson` sorts object keys, emits no whitespace, and preserves array order.
- `at` is ISO-8601 UTC with milliseconds and a trailing `Z` (e.g. `2026-11-17T11:40:00.000Z`).
- `payload` is an object whose values are strings.
- The first record's `prevHash` is 64 zeros; each later record's `prevHash` is the previous `hash`.
- `seq` starts at 1 and is gap-free. A hash or linkage mismatch returns `422 CHAIN_INVALID` with
  `details.seq` and `details.canonicalPreimage`.
- Record-level `signatureBase64`/`signerKeyId` are not part of the hash preimage.

A minimal Node snippet that builds a valid chain (copy into a scratch file, do not commit):

```js
import { createHash } from 'node:crypto';
const GENESIS = '0'.repeat(64);
const sha = (s) => createHash('sha256').update(s, 'utf8').digest('hex');
const canon = (v) => v === null || typeof v !== 'object'
  ? JSON.stringify(v)
  : Array.isArray(v) ? `[${v.map(canon).join(',')}]`
  : `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canon(v[k])}`).join(',')}}`;

const records = [];
let prev = GENESIS;
for (const [i, body] of [
  { kind: 'ATTEST',  summary: 'Scope agreed',          payload: { consent: 'in_app_signature' } },
  { kind: 'CAPTURE', summary: 'Establishing site frame', payload: { media: 'med_01' } },
  { kind: 'ATTEST',  summary: 'Sealed packet',          payload: { signerKeyId: 'fw-keystore-op_7d1a-1' } },
].entries()) {
  const at = '2026-11-17T11:40:00.000Z';
  const rec = { seq: i + 1, kind: body.kind, at, actorId: 'op_7d1a', summary: body.summary, payload: body.payload, prevHash: prev };
  rec.hash = sha(canon({ seq: rec.seq, kind: rec.kind, at: rec.at, actorId: rec.actorId, summary: rec.summary, payload: rec.payload, prevHash: rec.prevHash }));
  prev = rec.hash;
  records.push(rec);
}
```

### What the mock deliberately does not do

| Not implemented | Why it is acceptable here | What production does |
|---|---|---|
| Persistence | A reset is a feature for demos; the client treats the server as remote anyway | Durable store with backups |
| Real device attestation | Requires Play Integrity credentials and a signing setup | Verify attestation at `/auth/token` |
| ECDSA signature verification | No key provisioning in the mock; validating shape only keeps the contract testable | Verify P-256 signatures against the registered `signerKeyId` |
| TLS, CORS, horizontal scaling | Local-only contract testing | TLS 1.2+, no browser CORS, stateless API tier |
| Real PSP settlement | No provider account exists; moving money in a demo would be dishonest | PSP adapter with idempotent payout instructions |
| Full partial-payout allocation | Payouts mark whole ledger entries PAID and book any remainder as an `ADJUSTMENT`; production allocates per entry | Entry-level allocation with an immutable payout ledger |

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `listen EADDRINUSE` | Port already used | `--port 8788` and update the base URL |
| Android cannot reach the host | Emulator vs device networking | Emulator: `10.0.2.2`; device: host LAN IP + firewall |
| `401 UNAUTHENTICATED` | Missing bearer | Call `/auth/token` first; the mock also accepts any `fw_dev_…` token |
| `422 CHAIN_INVALID` | Canonical preimage differs from the app's encoder | Compare `details.seq` and the documented preimage above |
| `409 OFFER_EXPIRED` | Offers live 45 s from boot | Restart the server before a demo run, or create offers through dispatch |
| `429 RATE_LIMITED` | Bucket exhausted (e.g. >6 payout requests/min) | Wait for `Retry-After` or restart |

---

## Suggested client tests against the mock

1. Accept an offer twice with the same key → one accepted job, `Idempotent-Replayed: true`.
2. Accept twice with different keys → the second is `409 OFFER_TAKEN`.
3. Upload a valid packet, then re-upload the same bytes with a new key → `200` (duplicate, same
   hash). Upload different bytes for the same job → `409 PACKET_CONFLICT`.
4. Tamper with one record byte → `422 CHAIN_INVALID` at the correct `seq`.
5. Request a payout, kill the network mid-flight, retry with the same key → one payout.
6. `PUT /me/availability` with a stale ETag → `412`; refetch and retry.
7. Flush a sync batch containing an already-applied event → `DUPLICATE`, not a second effect.
