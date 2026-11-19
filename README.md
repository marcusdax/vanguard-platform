# Vanguard Platform & Vanguard Field

Standalone, token-branded monorepo for the **Vanguard** dispatch, verification, DePIN spatial telemetry, and community assistance network, paired with the **Vanguard Field** Android capture client.

## Architecture

| Component | Path | Purpose | Local Port |
| --- | --- | --- | --- |
| **Web Frontend** | [`apps/web`](apps/web) | Dual-surface React/Vite/Tailwind UI (`/` Vanguard Tactical & `/community` Vanguard Neighborhood) + `/verify` | `3200` |
| **Core API** | [`services/api`](services/api) | Express + Prisma + PostgreSQL + Redis + H3 micro-zone dispatch & append-only ledger | `5200` |
| **Trust Engine** | [`packages/trust-engine`](packages/trust-engine) | Deterministic UTF-8 NFC canonical SHA-256 chain, RFC 6962 Merkle proofs, Invariants I-1/I-5, IRS `$0.76/mi` & 120d DePIN half-life | Library |
| **Vision Sidecar** | [`services/vision`](services/vision) | FastAPI NumPy CV + WebODM photogrammetry + IMU jitter verification | Internal (`8000`) |
| **MediaMTX** | `docker-compose.yml` | RTSP/HLS/WebRTC field broadcast relay | `8898` / `8899` |
| **Android Client** | [`mobile/android`](mobile/android) | Kotlin + Jetpack Compose field app (`Vanguard Field`, `com.vanguard.field`) | Mobile |

## Quick Start (Docker Compose)

```bash
docker compose -p vanguard-platform up -d --build --wait
```

Once healthy:
- **Tactical Operations HUD:** <http://localhost:3200/> (one-click demo session uses `operator@vanguardfield.io` / `Vanguard!2026`, auto-seeded by API entrypoint)
- **Neighborhood Mode Portal (Mode A 64px accessible UI):** <http://localhost:3200/community>
- **Client-Side Evidence Verifier (< 5 KB):** <http://localhost:3200/verify>
- **API Health:** <http://localhost:5200/api/v1/health>

## Token-Driven Rebranding

All product marks and surface palettes live in two single sources of truth:
1. Web & API metadata: [`brand.config.json`](brand.config.json) + [`apps/web/src/brand/brandConfig.ts`](apps/web/src/brand/brandConfig.ts)
2. Android client: [`mobile/android/core/design/src/main/kotlin/com/odasi/fieldwright/design/BrandConfig.kt`](mobile/android/core/design/src/main/kotlin/com/odasi/fieldwright/design/BrandConfig.kt)

Run the automated integrity and brand check at any time:

```bash
node scripts/brand-check.mjs
```

## Verification Suites

```bash
# Brand & engine parity check
node scripts/brand-check.mjs

# Trust engine tests
cd packages/trust-engine && npm test

# Web unit tests & build
cd apps/web && npx tsc --noEmit && npm test && npm run build

# API unit tests
cd services/api && npx prisma generate && npx tsc --noEmit && npm test
```
