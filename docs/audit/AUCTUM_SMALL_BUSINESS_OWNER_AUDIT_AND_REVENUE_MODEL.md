# AUCTUM / Vanguard — Start-to-Finish Small Business Owner Platform Audit, Ecosystem Upgrade & 36-Month Revenue Model

**Date:** 2026-11-27 · **Audit Perspective:** Independent Small Business Owner / Roofing, Restoration & Field Services Operator relying on daily accuracy, unbroken links, live storm tracking, and transparent unit economics.

---

## 1. Executive Summary & Audit Findings (Bugs Caught & Fixed)

A complete code-and-UI audit across **vanguard-platform** (`apps/web/`, `services/api/`, `packages/trust-engine/`) and **AUCTUM.atlas** (`frontend/`, `backend/`) identified **five critical operator-facing defects** that would have caused broken flows, wrong financial projections, or inaccurate storm map centering for a small business owner:

| Finding | Location | Root Cause Found | Fix Applied & Verified |
| --- | --- | --- | --- |
| **#1 Vision Photo Audit 404** | `vanguard-platform/apps/web/src/components/FieldLiveConsole.tsx` | UI called `/field/social/ai/analyze-photo` while backend route mounts `/field/social/analyze-photo` returning `{ data: analysis }`. | Fixed endpoint path and response unwrapping (`data?.data`) so click-to-inspect photo CV works end-to-end. |
| **#2 Mode A Booking Crash / Missing PIN** | `vanguard-platform/apps/web/src/pages/CommunityPortal.tsx` | Form read `confirmed.task.pin` and `confirmed.quote.disclosureCopy`, whereas backend (`microZoneDispatch.ts`) returns `{ task, verbalPin, feeQuote }`. Consent label also stated `25%` homeowner share instead of canonical `10%`. | Fixed interface & JSX bindings (`confirmed.verbalPin`, `confirmed.feeQuote?.disclosureCopy`) and aligned homeowner royalty label to `10%`. |
| **#3 DePIN Half-Life & Preimage Drift** | `vanguard-platform/apps/web/src/components/EconomicLab.tsx` | Browser simulator used `50% / 25% / 25%` & `$1.00` floor instead of `@vanguard/trust-engine` canonical formula (`$16.00` peak -> `$2.50` floor, `25%` worker / `10%` homeowner / `65%` treasury + `1.0x–1.85x` refresh), and hash sandbox omitted `at` & `payload`. | Realigned `EconomicLab.tsx` formulas and `Vanguard_User_And_Operator_Guides.md` cleanly with `@vanguard/trust-engine`. |
| **#4 GeoJSON Polygon & MultiPolygon Storm Click Bug** | `AUCTUM.atlas/frontend/src/pages/StormTracking.tsx` & `vanguard-platform/services/api/src/routes/map.routes.ts` | `StormTracking.tsx` indexed `coords?.[0]?.[1]` directly (returning a `[lng, lat]` array for NWS `Polygon` alerts), `map.routes.ts` omitted `MultiPolygon` in `/storm-markers`, and `microZoneDispatch.ts` ignored polygon centroids when emitting storm bounties. | Switched `StormTracking.tsx` to `geometryCenter(geom)`, added `MultiPolygon` support in `/maps/storm-markers`, and added `Polygon`/`MultiPolygon` centroid resolution in `dispatchBountiesFromStormEvent`. |
| **#5 Missing Dual-Engine Spatial & Storm Console** | `vanguard-platform/apps/web/` | `vanguard-platform` backend exposed `/storm-intel/*` & `/maps/*`, but the frontend lacked an interactive Storm & Map tab. | Ported dual-engine **MapLibre GL JS 5 3D + Leaflet** stack from `marcusdax/AUCTUM.Insights` into `StormMapConsole.tsx` and wired it into `TacticalWorkspace.tsx`. |

---

## 2. Value Added from Ecosystem Scraping (GitHub, MASTERNEX.os, HuggingFace, Nature Portfolio)

### A. GitHub (`marcusdax/AUCTUM.Insights`)
- Inspected `AUCTUM.Insights/index.html`, `property_insight_architecture_pack.md`, and `property_underwriting_widget.html`.
- Ported the **Dual-Engine Spatial Map Stack** into [StormMapConsole.tsx](file:///c:/Users/test%20123/Documents/deepseek-harness/default-workspace/vanguard-platform/apps/web/src/components/StormMapConsole.tsx):
  - **MapLibre GL JS 5 3D Vectors:** `OpenFreeMap` (`Fiord`, `Liberty`, `Positron`) with extruded 3D building footprints and `2D` / `45° Iso` / `62° Street` camera perspectives.
  - **Leaflet 1.9.4 Multi-Layer Raster:** `Esri World Imagery` satellite aerial, `Carto Dusk Dark`, `OpenTopoMap Contours` + **NWS/IEM NEXRAD Radar Reflectivity** + **Uber H3 Res-7 territory hexagons** (`/storm-intel/hex-grid`) + `0.5 / 1.0 / 1.5 mi` micro-zone rings + click-to-score storm impact (`/storm-intel/impact`) + one-click **Emit H3 Bounties** (`/vanguard/dispatch/from-storm/:id`).

### B. MASTERNEX.os MCP (`status_matrix` + `academic_federated_search`)
- Audited live `status_matrix` (`45` Built / `11` Partial components) to prevent speculative claims: confirmed working Python/Rust-adjacent engines include `ACP-SQL ledger` (SQLite append-only SHA3 chain), `Sovereign Shield` (`X25519+ML-KEM-1024`), `GIE/Mamba` (axial hex trajectories), `NADE-2.0` (`0.977–0.995` synthetic sparse-corruption AUC), `BNE`, `PRINCE` federated learning, and `ShapleyExplainer` — while clearly marking `Polygon settlement` (`Not built`), `Mastermind 2B` (`Partial — trained to 414M`), and hardware TEE as non-production until funded.
- Used `academic_federated_search` (producing SHA3-256 ACP-SQL provenance receipts) across **Nature**, **OpenAlex**, and **arXiv** to ground routing & storm risk modules.

### C. HuggingFace (`hugging-science` Catalog) & Research Literature (Nature / OpenAlex / arXiv)
Candidate open-science benchmarks identified for Phase 2–3 ML radar/aerial upgrades (discovery-level candidates, not yet integrated into production inference):
1. **HuggingFace Earth-Science & Climate Catalogs:** `nasa-impact/WxC-Bench` (storm & turbulence benchmark), `isp-uv-es/WorldFloodsv2` (Sentinel-1/2 & Landsat flood extent), `ONERA/SARLO-80` (`119K` 80cm SAR/optical remote-sensing pairs), `nasa-impact/EO-via-NLP`.
2. **Nature Portfolio & Operations Literature:**
   - **Laurie, B., Langley, A., & Kasper, E. (2013).** *Certificate Transparency* (`RFC 6962`). DOI: `10.17487/RFC6962`
   - **Brito, E., Hadachi, A., Kamm, L., & Norbisrath, U. (2025).** *Decentralized Proof-of-Location systems for trust, scalability, and privacy in digital societies*. *Scientific Reports* (`10.1038/s41598-025-04566-4`).
   - **Alshater, M. M. (2025).** *Decentralized physical infrastructure networks (DePIN) tokenomics*. *Frontiers in Blockchain* (`10.3389/fbloc.2025.1644115`).
   - **Nature / Operations Research:** *A multi-depot period vehicle routing problem arising in the utilities sector*, *Journal of the Operational Research Society* (`10.1038/sj.jors.2600641`), paired with *An illumination algorithm approach to solving the micro-depot routing problem* (`10.1145/3321707.3321767`).
   - **Hail Damage Claim Modeling:** *Bayesian modeling of insurance claims for hail damage* (`arXiv:2308.04926`) and *Change-Agent: Remote Sensing Change Interpretation* (`arXiv:2403.19646`).
   - **Scientific Agent Methodology:** **Kassis, T., Agarwal, V., He, Y., Patel, D., & Brueckner, A. M. (2026).** *Scientific Agent Skills: A Library of Procedural Knowledge for Research Agents* (`arXiv:2609.00065`).

---

## 3. 36-Month Quantitative Revenue Scenario Model & Sensitivity Analysis

Generated deterministically by [build_auctum_revenue_model.py](file:///c:/Users/test%20123/Documents/deepseek-harness/default-workspace/vanguard-platform/docs/financial/build_auctum_revenue_model.py) into [auctum_36m_revenue_model.xlsx](file:///c:/Users/test%20123/Documents/deepseek-harness/default-workspace/vanguard-platform/docs/financial/auctum_36m_revenue_model.xlsx), [auctum_36m_pnl_scenarios.csv](file:///c:/Users/test%20123/Documents/deepseek-harness/default-workspace/vanguard-platform/docs/financial/auctum_36m_pnl_scenarios.csv) (`432` rows), and [auctum_sensitivity_matrix.csv](file:///c:/Users/test%20123/Documents/deepseek-harness/default-workspace/vanguard-platform/docs/financial/auctum_sensitivity_matrix.csv) (`60` sensitivity rows):

| Scenario | Business Model | M36 ARR Run-Rate | 36M Total Revenue | Blended GM% | 36M Cumulative EBITDA | Peak Cash Draw | Breakeven Month |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| **Conservative** | `Model_A_Escrow` | `$93,041` | `$134,874` | `49.3%` | `-$583,375` | `-$583,375` | `>36M` |
| **Conservative** | `Model_B_SaaS` | `$29,583` | `$88,749` | `80.7%` | `-$632,588` | `-$632,588` | `>36M` |
| **Conservative** | `Model_C_DePIN` | `$162,816` | `$181,446` | `86.8%` | `-$987,244` | `-$987,244` | `>36M` |
| **Conservative** | **`Blended_Flywheel`** | **`$318,005`** | **`$434,722`** | **`77.0%`** | **`-$511,262`** | **`-$511,262`** | **`>36M`** |
| **Base** | `Model_A_Escrow` | `$543,947` | `$633,146` | `65.2%` | `-$558,088` | `-$558,088` | `>36M` |
| **Base** | `Model_B_SaaS` | `$305,063` | `$441,686` | `81.2%` | `-$583,046` | `-$583,046` | `>36M` |
| **Base** | `Model_C_DePIN` | `$1,400,996` | `$1,329,537` | `86.8%` | `-$148,709` | `-$602,004` | `M24` |
| **Base** | **`Blended_Flywheel`** | **`$2,440,387`** | **`$2,552,747`** | **`82.8%`** | **`+$862,087`** | **`-$242,953`** | **`M16`** |
| **Storm_Surge** | `Model_A_Escrow` | `$2,174,003` | `$2,328,518` | `71.8%` | `+$150,914` | `-$263,449` | `M17` |
| **Storm_Surge** | `Model_B_SaaS` | `$1,752,070` | `$1,688,823` | `81.7%` | `-$16,357` | `-$343,310` | `M24` |
| **Storm_Surge** | `Model_C_DePIN` | `$7,369,045` | `$6,200,165` | `86.8%` | `+$3,922,255` | `-$445,927` | `M15` |
| **Storm_Surge** | **`Blended_Flywheel`** | **`$12,056,018`** | **`$10,801,139`** | **`84.5%`** | **`+$7,078,401`** | **`-$143,462`** | **`M12`** |

### Key Sensitivity Levers (Base Case `Blended_Flywheel`, `M16` Breakeven Baseline)
1. **DePIN Annual Parcel Reuse (`1.5x` vs `4.2x` vs `8.0x`):** Largest profitability lever — `total_36m_ebitda_usd` swings from `+$140,712` (`M20` breakeven at `1.5x`) to **`+$1,877,355`** (`M15` breakeven at `8.0x` carrier density).
2. **Buyer Verification Fee Rate (`500` vs `1,000` vs `1,500 bps`):** Shifts 36M EBITDA from `+$561,014` to **`+$1,163,160`** (`M15` breakeven).
3. **Payment Rail Efficiency (`0.8%` ACH vs `2.9%` Card):** Moving B2B buyers onto prefunded ACH escrow expands `Blended_Flywheel` M36 Gross Margin from `79.34%` to **`86.42%`** (`+$191,408` saved in payment COGS over 36M).
4. **Exploratory Sensitivity Audit (`eda_analyzer.py`, `N = 432` P&L rows & `N = 60` sensitivity rows):** Zero missing cells across all generated scenarios; across all 60 sensitivity points, 36M EBITDA ranges around median `-$406,170` (`MAD $257,460`), isolating `DePIN_Annual_Query_Reuse` and `Buyer_B2B_Fee_BPS` as primary upside drivers while keeping `Blended_Flywheel` EBITDA positive (`+$140.7k` to `+$1.88M`) across every Base-case stress test.
