# Fieldwright — build contract & workstream briefs

**Status:** locked for sprint 1. Everything below is a contract, not a suggestion.
Read this file completely before writing code. If a requirement is ambiguous, message
the Lead rather than inventing a parallel design.

---

## 1. What we are building

**Fieldwright** is the Android field-operations app of the AUCTUM trust estate, built for
the gig-economy model of `field-commander` and the evidence discipline of Fieldwright /
`ar-inspection-platform`.

One sentence: **a DoorDash-shaped marketplace where the product being sold is verified
field evidence, and the worker gets paid when the evidence is sealed, not when the photo
is uploaded.**

Three inputs are merged, and only their best parts survive:

| Input | Kept | Dropped |
|---|---|---|
| Fieldwright web vault (HTML) | parchment/olive/oxide design language, packet cards, consent-first copy, chain-of-custody line, dock navigation | web-only navigation, sample-book framing as the product |
| Field Commander | dispatch/offer heartbeats, geofenced missions, operator tiers, offline-first ethos, payout ledger | Ghost Mode, GPS spoofing, token payouts, surveillance framing (legally and ethically unacceptable; no cryptocurrency) |
| AR Inspection Platform | AR-assisted capture discipline: hit-test/plane thinking, anchored annotations, video quality gates, device capability detection, guided frame validation | native ARKit/ARCore rendering in this sprint; we ship a calibrated-capture engine with a documented ARCore upgrade path |
| AUCTUM ecosystem blueprints (workspace `ecosystem/`) | record kinds, tier × badge evidence model, seal preconditions, append-only records, fee-as-parameter, demo data never enters the public log | the coffee/roofing-specific vocabulary; Fieldwright speaks field-work terms |

**Non-negotiables**

1. No tokens, no crypto payouts. Money is integer cents in a ledger with idempotent
   payouts (a real PSP adapter is documented, not faked).
2. No covert capture, no GPS spoofing, no face/plate capture outside a recorded consent
   scope. Redaction gates are blocking.
3. Evidence is append-only. Corrections are records; nothing is edited or deleted.
4. Demo/sample data is always flagged `demo = true` and is excluded from any log anchor.
5. Every screen renders empty, loading, offline, and error states. No happy-path-only UI.
6. Material Design 3 components are the base; the Fieldwright visual system is applied on
   top. No third-party UI kits.

---

## 2. Repository map

```
fieldwright/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├── app/                     Lead        composition root, nav host, DI, MainActivity
├── core/model/              domain-core pure Kotlin: vocabulary + pure engines + tests
├── core/crypto/             domain-core SHA-256, canonical JSON, chain, Merkle, ECDSA
├── core/design/             ui-field    tokens, theme, typography, components
├── core/data/               data-sync   Room, DataStore, repositories, outbox, demo market
├── feature/ground/          ui-field    Ground screen + ViewModel
├── feature/jobs/            ui-field    Jobs list, job detail + ViewModel
├── feature/capture/         ui-capture  consent → capture → gates → seal flow
├── feature/vault/           ui-vault-money packet list, packet detail, chain verify
├── feature/payouts/         ui-vault-money balances, ledger, payout flow
├── feature/brief/           Lead        operating rules, sync health, settings
├── docs/                    docs-gtm    product, architecture, API, security, GTM, runbook
├── backend/mock-server.mjs  docs-gtm    zero-dependency contract mock for local sync
└── .github/workflows/       docs-gtm    CI: assemble + unit tests
```

**Dependency direction is one-way:** `app → feature:* → core:data → core:crypto → core:model`,
and `feature:* → core:design → core:model`. `core:design` may use model value types
(`GeoPoint`, `HotZone`, `Money.format`) but must not depend on `core:data`; a feature must
never import another feature. `core:model` imports nothing.

**Package roots:** `com.odasi.fieldwright.model|crypto|design|data|feature.ground|...|app`.

**Build toolchain (already provisioned, workspace-local — do not touch):**

```powershell
# from the workspace root
pwsh -NoProfile -File .tools\gw.ps1 :app:assembleDebug      # full APK
pwsh -NoProfile -File .tools\gw.ps1 :core:model:test        # unit tests
pwsh -NoProfile -File .tools\gw.ps1 :app:lintDebug
```

Gradle is **Lead-owned during the sprint** (one build at a time avoids locking and stale
state). Write code, then message the Lead for a compile check. Do not start Gradle yourself.

---

## 3. Visual system (the Fieldwright look)

Source of truth: the supplied Fieldwright HTML. Tokens live in `core/design`
(`FieldwrightTokens.kt`). Screens must use tokens, never raw hex.

### 3.1 Colour

| Token | Hex | Use |
|---|---|---|
| `Desk` | `#EFE6D4` | app background; warm parchment reduces glare outdoors |
| `Sheet` | `#FFFDF7` | cards, sheets, dock surface |
| `Ink` | `#1F1C16` | primary text (AAA on Desk/Sheet) |
| `InkSoft` | `#6E6556` | secondary text, meta (AA on Desk ≥ 4.6:1) |
| `Olive` | `#4E5D2C` | primary action, active tab, seal, positive |
| `OliveDeep` | `#38451F` | pressed state, high-emphasis text on tint |
| `Oxide` | `#94451F` | accent rules, urgent/inspection marks (never for body text on Desk) |
| `Line` | `#D8CBB4` | 1 dp borders and dividers |
| `Amber` | `#9A6414` | pending/in-review |
| `Flag` | `#8C2F1F` | flagged/blocking (contrast-safe on Sheet) |
| `MapPaper` | `#E9DFC9` | map canvas base |
| `MapRoad` | `#D3C3A6` | map roads |
| `MapParcel` | `#F4EDDD` | map parcels |
| `OliveTint` | `#E7E8D6` | selected rows, chip backgrounds |
| `SheetShadow` | `#14110B` @ 8% | elevation shadow only |

Rules: no gradients except the subtle map vignette; no pure black; no second accent on a
screen (one accent per surface: Olive for action, Oxide for evidence/location marks).
Light-only this sprint — a dark scheme is a documented roadmap item, not a hidden toggle.

### 3.2 Type

Bundled fonts (real TTFs in `core/design/src/main/res/font/`):
**Fraunces** (serif, 500/600) for display and packet titles. **Outfit** (400/500/600) for
UI, body, and data. Money and hashes use `fontFeatureSettings = "tnum"`.

| Style | Font | Size/line | Use |
|---|---|---|---|
| `displayXl` | Fraunces 600 | 40/44 | screen titles ("Vault") |
| `displayL` | Fraunces 600 | 32/38 | hero numbers, packet titles long |
| `title` | Fraunces 600 | 24/30 | card titles |
| `heading` | Fraunces 500 | 18/24 | section heads |
| `body` | Outfit 400 | 15/22 | paragraphs, notes |
| `bodyStrong` | Outfit 500 | 15/22 | card values |
| `label` | Outfit 500 | 13/18 | buttons, chips, tabs |
| `meta` | Outfit 400 | 12/16 | timestamps, addresses, hints |
| `micro` | Outfit 500 | 11/14, tracking 0.08em, uppercase | overline labels |
| `numeric` | Outfit 500 | 15/20, tnum | money, distances, counts |
| `numericLarge` | Outfit 600 | 28/34, tnum | balance figures |

Sentence case everywhere except `micro` overlines. No all-caps buttons. No letter-spacing
on body text.

### 3.3 Shape, spacing, motion

Spacing scale: `4, 8, 12, 16, 24, 32, 48`. Screen gutter 16 dp (20 dp on ≥ 600 dp width).
Radii: `button 6`, `card 10`, `sheet 16`, `pill 999`. Hairline rules are 1 dp `Line`;
the signature olive rule is **64 dp × 2 dp**, always at the top of a packet card.
Elevation: cards are flat with a 1 dp `Line` border; only the dock and sheets cast shadow.
Touch targets ≥ 48 dp, dock items ≥ 56 dp tall. Motion 120–180 ms, `FastOutSlowIn`; respect
`Settings.Global.ANIMATOR_DURATION_SCALE == 0` and reduce-motion by removing non-essential
animation.

### 3.4 Signature motifs

- **Seal mark:** circle + crosshair (the supplied logo) — used for sealed packets, chain
  health, and the app icon.
- **Packet card:** olive rule, micro overline "Fieldwright packet", serif title
  `FW-0994 · Panel T-14 is set`, requester · status · payout line, consent line, checklist
  lines, fingerprint footer, primary "Download packet" + secondary "Open job".
- **Dock:** four destinations — Ground, Jobs, Vault, Payouts — ≥ 56 dp, icon + label,
  active state is Olive text with a 24 dp oxide underline at the top edge.
- **Numerals are evidence:** money and hashes are tabular, never proportional.

---

## 4. Design-system API (implemented in `core/design`, consumed by every feature)

`ui-field` builds these **first** and announces completion. Other workstreams code against
this API immediately; the Lead integrates.

```kotlin
@Composable fun FieldwrightTheme(content: @Composable () -> Unit)
object Fw { val colors: FwColors; val type: FwType; val space: FwSpace; val shape: FwShapes }

@Composable fun FwScaffold(modifier: Modifier = Modifier, topBar: @Composable () -> Unit = {}, dock: @Composable () -> Unit = {}, snackbarHost: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit)
@Composable fun FwTopBar(operatorLabel: String, sampleChip: Boolean, syncLabel: String?, onBrief: () -> Unit)
@Composable fun FwDock(selected: DockTab, onSelect: (DockTab) -> Unit)      // enum GROUND, JOBS, VAULT, PAYOUTS
@Composable fun FwLogoMark(size: Dp = 32.dp)
@Composable fun FwSealMark(sealed: Boolean, size: Dp = 40.dp)
@Composable fun FwCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)
@Composable fun FwSectionRule(label: String? = null)
@Composable fun FwPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false)
@Composable fun FwSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)
@Composable fun FwGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier)
@Composable fun FwPill(text: String, tone: PillTone, modifier: Modifier = Modifier)  // OLIVE, AMBER, FLAG, NEUTRAL, OXIDE
@Composable fun FwMoneyText(cents: Int, style: TextStyle = Fw.type.numeric, signed: Boolean = false, showCents: Boolean? = null)
@Composable fun FwKeyValue(label: String, value: String, valueStyle: TextStyle = Fw.type.bodyStrong)
@Composable fun FwStat(label: String, value: String, caption: String? = null, modifier: Modifier = Modifier)
@Composable fun FwTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String = "", singleLine: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text)
@Composable fun FwSwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit)
@Composable fun FwAvailabilityToggle(isOnline: Boolean, statusLine: String, onToggle: () -> Unit, enabled: Boolean = true)
@Composable fun FwChecklistRow(label: String, hint: String, state: ChecklistRowState, onClick: (() -> Unit)?)   // OPEN, PASS, FAIL, NA
@Composable fun FwProgressSteps(labels: List<String>, current: Int, modifier: Modifier = Modifier)
@Composable fun FwEmptyState(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null)
@Composable fun FwChainHash(hash: String, label: String? = null)   // mono-ish tabular, 8-char display + copy
@Composable fun SiteMapCanvas(operatorFix: GeoPoint?, jobs: List<MapPin>, hotZones: List<HotZone>, routeTo: GeoPoint? = null, modifier: Modifier = Modifier, onPinClick: (String) -> Unit = {})
data class MapPin(val id: String, val point: GeoPoint, val label: String, val payoutCents: Int, val selected: Boolean = false)
```

Component rules: every component previews in `@Preview`; content descriptions on all
icons; no hard-coded strings in components (strings are passed in); 48 dp minimum targets.

---

## 5. Domain rules that screens may not re-derive

All functions live in `core/model` (`Engines.kt`) and are implemented by **domain-core**.
Screens/repos call them; nobody re-implements a weight, fee, tier, or seal rule.

### 5.1 Evidence & seal

- Records: `CAPTURE, ATTEST, CUSTODY, INSPECTION, PAYMENT, CORRECTION` — append-only.
  Each record carries `prevHash`; genesis is 64 zeros; `hash = SHA-256(canonicalJson)`.
- Tiers 1–5 are **derived** from records (`TierEngine.derive`): reported → counterparty
  confirmed → field captured → payment verified → verified by AUCTUM. Never a percentage.
- Badges are declared claims with states `MISSING, IN_REVIEW, VERIFIED, FLAGGED, WAIVED`.
  Required badges come from `BadgeKey.requiredFor(jobType)`.
- Seal preconditions (`SealEngine.evaluate`): tier 5, all required badges VERIFIED, none
  FLAGGED, chain verifies, inclusion in the log, as-of date present. Failure returns reasons.
- Consent is a record: name, role, scope text, method, timestamp. No consent → no seal.
- Gates (blocking): consent on file; geofence satisfied (or override with reason recorded);
  media hashed and redaction resolved (faces/plates); checklist required steps complete;
  measurement calibrated; chain verifies.

### 5.2 Dispatch

Weights: proximity 0.30, payout 0.25, urgency 0.15, credential 0.15, reliability 0.10,
fairness 0.05. Rejections carry a human reason. Offer TTL 45 s. Max 3 active jobs. Radius
≤ 25 km. Payout floor `$25` equivalent per offer (`MIN_PAYOUT_FLOOR_CENTS = 2500`).
Surge 1.0–1.6, disclosed before acceptance. Auto-accept is a written policy with limits
and an explicit reason for every accept/pass.

### 5.3 Money

Integer cents. Requester-paid platform fee 15% (`FeeSchedule.platformFeeBp = 1500`),
disclosed next to money. Instant payout fee 1.5% (min $0.50, max $5.00), standard ACH free.
Every ledger entry has an idempotency key; a payout request can be retried safely.
Balances: available = AVAILABLE entries not yet paid; pending = PENDING entries; lifetime
paid. Mileage deduction is a parameter (70¢/mi) and always labelled an estimate.

### 5.4 Job lifecycle

`SCHEDULED → AVAILABLE → OFFERED → ACCEPTED → EN_ROUTE → ON_SITE → IN_PROGRESS →
PACKET_READY → SEALED → SUBMITTED → UNDER_REVIEW → APPROVED → PAID` plus
`CANCELLED/EXPIRED/DISPUTED`. Transitions come from `JobLifecycle`; screens ask.

---

## 6. Feature entrypoints (the only cross-module API)

Each feature exposes exactly one route composable plus its screen preview. The app module
passes repositories and navigation callbacks; features own their ViewModels.

```kotlin
// feature/ground
@Composable fun GroundRoute(repos: FieldwrightRepositories, onOpenJob: (String) -> Unit, onOpenBrief: () -> Unit)

// feature/jobs
@Composable fun JobsRoute(repos: FieldwrightRepositories, onOpenJob: (String) -> Unit, onOpenCapture: (String) -> Unit)
@Composable fun JobDetailRoute(repos: FieldwrightRepositories, jobId: String, onBack: () -> Unit, onOpenCapture: (String) -> Unit, onOpenPacket: (String) -> Unit)

// feature/capture
@Composable fun CaptureRoute(repos: FieldwrightRepositories, jobId: String, onBack: () -> Unit, onSealed: (String) -> Unit)

// feature/vault
@Composable fun VaultRoute(repos: FieldwrightRepositories, onOpenPacket: (String) -> Unit, onOpenBrief: () -> Unit)
@Composable fun PacketDetailRoute(repos: FieldwrightRepositories, packetId: String, onBack: () -> Unit)

// feature/payouts
@Composable fun PayoutsRoute(repos: FieldwrightRepositories, onOpenBrief: () -> Unit)

// app
@Composable fun AppRoot(container: AppContainer)   // routes: ground, jobs, jobs/{jobId}, jobs/{jobId}/capture, vault, vault/{packetId}, payouts, brief
```

ViewModels are created inside the route with `viewModel { ... }` and take
`FieldwrightRepositories` + `SavedStateHandle` as needed. Screens are stateless and
previewable. Use `collectAsStateWithLifecycle()`.

---

## 7. Screen specifications

### 7.1 Ground (`feature/ground`)

Purpose: *should I go online, and what should I take next?*

1. Top bar (design system) — operator name + "Sample book" chip in demo + sync dot.
2. Availability card: `FwAvailabilityToggle` with a status line that names the area and
   radius ("Online · 15 km · Creekside"), a radius stepper (5/10/15/25 km), the auto-accept
   one-liner ("Auto-accept under $30 · max 2 stacked"), and today's earnings so far.
3. Map card: `SiteMapCanvas` with operator fix, 3–6 nearby job pins (payout labels), hot
   zone ring with surge multiplier, and route to the next accepted job. Tap a pin → inline
   mini card with Accept/Open.
4. "Next up" active job card when a job is ACCEPTED/EN_ROUTE/ON_SITE: status, ETA, SLA
   remaining, checklist progress, primary action routing to the job detail.
5. Opportunity list: ranked offers — payout (large tnum), `$xx/hr`, type, distance, address
   area, surge pill, 45 s countdown ring, Accept (primary) + Pass (ghost), auto-accept chip.
6. Market strip: demand level, weather, daylight remaining, offer response rate.
7. States: off the clock (map muted, no offers, CTA to go online), no supply, offline
   (queued count, retry), error (retry). Pull-to-refresh triggers dispatch refresh.

### 7.2 Jobs & Job detail (`feature/jobs`)

List: segmented filter (Offers · Active · Done). Offers mirror the Ground card with a
countdown; Active shows workflow progress (`FwProgressSteps`) and next action; Done shows
payout, packet short hash, and "Open packet".
Detail: job id + serif title, status pill, requester org/kind, site address + access notes
+ geofence radius, payout breakdown (bounty, bonus, surge, disclosed platform fee note),
SLA clock, checklist preview with proof kinds, consent scope callout, actions by status:
Accept → En route → "I've arrived" (geofence check + override reason sheet) → "Start
capture" → "Continue capture" → "Open packet". Travel: "Navigate" launches a geo intent.
Never fabricate an arrival: arrival is a geofence check with an override that records a
reason and flags the packet for review.

### 7.3 Capture (`feature/capture`)

Full-screen, one obvious next action, works offline and without a camera.

1. **Consent gate:** scope text from the job, subject name/role, method (in-app signature
   preferred), typed signature + explicit confirmation, "Signed by operator" record.
2. **Evidence:** checklist steps. For each: camera preview (`CameraX` behind a
   `CaptureSource` interface; `SimulatedCaptureSource` fallback when no camera/permission),
   framing guide overlay (corners, level line, "no faces" hint), shutter → JPEG bytes →
   SHA-256 → `MediaRef`; thumbnail row shows short hash; note field; measurement tool:
   choose a reference (site card 30 cm, shingle 90 cm, door 91 cm), tap two points on the
   preview, show calibrated m/ft result and uncertainty, save → `Measurement`.
3. **Gates review:** run `capture.runGates`; show each gate pass/fail with reason; only
   blocking failures prevent sealing; allow retake/replacement.
4. **Seal:** summary of packet; "Seal packet" with progress states (canonicalise → hash →
   sign → chain append); success shows seal mark, `FW-xxxx`, head hash, "Submit packet";
   submission is queued offline-first.

Camera rules: request permission once with rationale; deny → simulated source with a clear
label (never pretend a camera frame exists); respect lifecycle; no burst loops; cancel
work when leaving the screen.

### 7.4 Vault & packet detail (`feature/vault`)

List: header + chain-health banner (verify-on-demand), filter chips (All · Sealed ·
Needs attention), packet cards exactly in the Fieldwright card idiom with olive rule,
consent line, checklist lines, fingerprint (`records · last time · short hash`), primary
"Download packet" (writes `PacketExport` to cache + share sheet) and secondary "Open job".
Detail: seal hero (mark, id, title, requester, payout, as-of), badges with states and
rationales, tier with reasons (never a progress bar), gates, record timeline (seq, kind,
actor, time, hash, signed ✓), media hashes with redaction state, chain verify action with
result banner, corrections (append note), export/share.
Honesty: demo packets show "SAMPLE — excluded from log" and are never presented as live.

### 7.5 Payouts (`feature/payouts`)

Balances card: available (numericLarge), pending, paid lifetime, instant-payout CTA with a
fee-disclosing sheet (`PayoutQuote`), standard ACH option, destination last 4, streak and
weekly-goal ring. Ledger: grouped by day with kind, description, signed amount, status.
Earnings summary: today/week/month, online minutes, jobs completed, miles, bonuses, tips,
platform fee not charged to worker (disclosure), mileage deduction estimate. Payout history
with statuses and failure reasons. All money through `Money.format` and `FeeEngine`.

### 7.6 Brief (`feature/brief`, Lead)

Operating rules in the estate's voice: what "sealed" means, consent rules, fee schedule,
what Fieldwright never does, sync health (`SyncState` + "Retry now"), credentials, settings
(radius, auto-accept policy, notifications), sample-book reset, version/build info, and the
AUCTUM protocol note (demo records never enter the public log).

---

## 8. Data layer expectations (`core/data`)

- Room is the single source of truth; UI reads Flows only. Writes go through repositories.
- Tables: `jobs, offers, packets, packet_records, badges, media, measurements, checklist,
  ledger, payouts, operator, availability, outbox, sync_state, market`.
- Demo mode seeds the sample book: FW-0994 (panel), FW-1012 (RPZ, paid), FW-1048 (active),
  FW-1051 (scheduled), plus 3–5 available jobs and 2 live offers. A deterministic
  `MarketSimulator` ticks demand/supply and refreshes supply every ~8 s while online.
- Outbox: every mutation enqueues an event with an idempotency key; `SyncWorker` flushes
  with exponential backoff + jitter (max 8 attempts → dead letter visible in Brief).
  A `FakeBackend` simulates 200–800 ms latency and occasional 503s; real HTTP lives behind
  the same interface using the contract in `docs/api/fieldwright.openapi.yaml`.
- Crypto: `AndroidKeystoreSigner` (ECDSA P-256) via `KeyGenParameterSpec`; software signer
  fallback for tests. Never log consent names, packet payloads, or signatures.

---

## 9. Definition of done (per workstream)

- Code compiles with the Lead's build; no `TODO()` left in the workstream's owned files.
- Unit tests cover every pure decision the workstream owns (domain-core: ≥ 60 assertions;
  data-sync: repository/idempotency/outbox tests; UI: ViewModel state tests).
- Every screen has previews, empty/loading/offline/error states, content descriptions, and
  48 dp targets.
- No hard-coded colours/sizes in feature code; tokens and components only.
- No analytics, network calls, or file writes outside the documented layers.
- KDoc on non-obvious decisions, especially anything that encodes a business rule.
- Report back to the Lead: what was built, what was verified, what remains.

## 10. Ownership and file discipline

Only edit files inside your task's write scopes. Never edit another workstream's module,
`settings.gradle.kts`, `gradle/libs.versions.toml`, or `app/`. If you need a shared change,
message the Lead. Keep files focused; use the existing packages; match the code style of
`core/model` (KDoc, explicit business language, no magic numbers without a named constant).
