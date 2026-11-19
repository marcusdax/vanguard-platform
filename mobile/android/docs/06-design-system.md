# 06 — Design system

**Source of truth for tokens:** `core/design` (`FieldwrightTokens.kt`), specified in
`docs/CONTRACTS.md` §3. This document explains the system, gives the **measured** contrast
audit, and states the accessibility contract every component must satisfy. Where a rule differs
from the contract, the contract wins and this file is the defect.
**Audience:** product, UI, Android, a requester's accessibility reviewer, a designer picking up
the system cold.

> The look is warm parchment and ink, olive for action, oxide for evidence marks. The reason is
> practical: field workers read this on a phone in daylight, and a warm matte surface with dark
> ink has less glare and a higher readable contrast than a white sheet. Everything decorative
> was removed; what remains is the evidence.

---

## 1. Foundations

### 1.1 Colour tokens

| Token | Hex | Role | Notes |
|---|---|---|---|
| `Desk` | `#EFE6D4` | App background | Warm parchment; reduces glare outdoors |
| `Sheet` | `#FFFDF7` | Cards, sheets, dock surface | The paper on the desk |
| `Ink` | `#1F1C16` | Primary text | Near-black, never pure black |
| `InkSoft` | `#6E6556` | Secondary text, meta | The lightest text colour allowed for body-size copy |
| `Olive` | `#4E5D2C` | Primary action, active tab, seal, positive | Exactly one olive action per surface |
| `OliveDeep` | `#38451F` | Pressed state, high-emphasis text on tint, focus ring | |
| `Oxide` | `#94451F` | Evidence/location accent, signature rule | Accent only; not body text on `Desk` by contract |
| `Line` | `#D8CBB4` | 1 dp dividers and card borders | Decorative; never the only boundary of a control |
| `Amber` | `#9A6414` | Pending / in review | **Large text or non-text only on `Desk`** (§4.2) |
| `Flag` | `#8C2F1F` | Flagged / blocking | Contrast-safe on `Sheet` and `Desk` |
| `MapPaper` | `#E9DFC9` | Map canvas base | `InkSoft` is not allowed on it (§4.2) |
| `MapRoad` | `#D3C3A6` | Map roads | |
| `MapParcel` | `#F4EDDD` | Map parcels | |
| `OliveTint` | `#E7E8D6` | Selected rows, chip backgrounds | |
| `SheetShadow` | `#14110B` @ 8% | Elevation shadow only | Not a fill |

Rules: light-only in sprint 1 (dark scheme is a roadmap item, ADR-5); no gradients except the
static map vignette; no pure black; one accent per surface — `Olive` for action, `Oxide` for
evidence and location marks, never both competing for the same tap. Screens use tokens, never raw
hex (enforced by review; a lint rule is a roadmap item).

### 1.2 Type scale

Fonts are bundled TTFs in `core/design/src/main/res/font/`: **Fraunces** (500/600) for display and
packet titles, **Outfit** (400/500/600) for UI, body and data. Money and hashes use
`fontFeatureSettings = "tnum"`.

| Style | Font | Size/line | Role | Accessibility note |
|---|---|---|---|---|
| `displayXl` | Fraunces 600 | 40/44 | Screen titles ("Vault") | Large text; contrast requirement 3:1 |
| `displayL` | Fraunces 600 | 32/38 | Hero numbers, long packet titles | Large text |
| `title` | Fraunces 600 | 24/30 | Card titles | Large text boundary at 24 px regular |
| `heading` | Fraunces 500 | 18/24 | Section heads | Must meet 4.5:1 unless ≥18.67 px bold |
| `body` | Outfit 400 | 15/22 | Paragraphs, notes | 4.5:1 minimum |
| `bodyStrong` | Outfit 500 | 15/22 | Card values | 4.5:1 |
| `label` | Outfit 500 | 13/18 | Buttons, chips, tabs | 4.5:1; never all-caps |
| `meta` | Outfit 400 | 12/16 | Timestamps, addresses, hints | 4.5:1; the smallest body text allowed |
| `micro` | Outfit 500 | 11/14, tracking 0.08em, uppercase | Overline labels | Decorative labelling only; never the sole carrier of meaning |
| `numeric` | Outfit 500 | 15/20, tnum | Money, distances, counts | 4.5:1 |
| `numericLarge` | Outfit 600 | 28/34, tnum | Balance figures | Large text |

Rules: sentence case everywhere except `micro`; no all-caps buttons; no letter-spacing on body;
Android `sp` for all text so system font scale applies; layouts must survive 200% text scale
without clipping or losing the primary action (§4.3).

### 1.3 Spacing, shape, elevation

| Token | Value | Use |
|---|---|---|
| Space scale | 4, 8, 12, 16, 24, 32, 48 dp | All spacing; no off-scale values |
| Screen gutter | 16 dp compact; 20 dp at ≥ 600 dp | Content inset |
| Radius `button` | 6 dp | Buttons, inputs |
| Radius `card` | 10 dp | Packet cards, job cards |
| Radius `sheet` | 16 dp | Bottom sheets |
| Radius `pill` | 999 dp | Chips, pills |
| Hairline | 1 dp `Line` | Dividers and card borders only |
| Signature olive rule | 64 × 2 dp | Top of every packet card, always |
| Elevation | Flat cards with 1 dp border; shadow only on dock and sheets | `SheetShadow` @ 8% |

### 1.4 Motion

| Motion | Duration | Easing | Reduced motion |
|---|---|---|---|
| Tap feedback / press | 120 ms | `FastOutSlowIn` | Keep (state feedback) |
| Screen/sheet transition | 180 ms | `FastOutSlowIn` | Cross-fade or none |
| Seal success mark | ≤ 600 ms, one-shot | `FastOutSlowIn` | Show final mark immediately, no animation |
| Offer countdown ring | Continuous while offer is live | Linear | Numeric seconds remain; ring static |
| Sync dot pulse | 1.2 s loop while syncing | Linear | Static dot + text "Syncing" |
| Map vignette | Static | — | — |

Rules: respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` and the app's reduce-motion setting;
never animate a number that changes meaning faster than it can be read (money counts up only on
first display, never on every refresh); no auto-playing carousels; no parallax.

### 1.5 Iconography and imagery

- Icons from Material Symbols via `material-icons-extended` (Apache 2.0). One icon family only.
- The seal mark (circle + crosshair) is the supplied logo, used for sealed packets, chain health
  and the app icon. **Provenance of the supplied mark is unverified** ([08 §6](08-roadmap-and-decisions.md));
  it must be cleared or re-drawn before store submission.
- No stock photography, no decorative illustration. The only photographs in the product are
  evidence frames, which are real, timestamped and hash-linked. Generated imagery is never
  evidence and never appears in the vault.
- The map is a locally drawn canvas (`SiteMapCanvas`) — no third-party map SDK, no tile
  licensing dependency, no location disclosure to a map vendor.

---

## 2. Components (the contract in `core/design`)

Every component previews in `@Preview`, takes strings as parameters (no hard-coded copy), exposes
content descriptions, and meets a 48 dp minimum target. The API is specified in
`docs/CONTRACTS.md` §4.

| Component | Purpose | Accessibility contract |
|---|---|---|
| `FwScaffold` | App frame: top bar, content, dock, snackbar | One main content region; snackbar announced as a live region |
| `FwTopBar` | Operator label, sample chip, sync label, Brief action | Sync text is a polite live region; Brief icon has a label, not just a glyph |
| `FwDock` | Ground · Jobs · Vault · Payouts | ≥ 56 dp tall, ≥ 48 dp targets, labels always visible, selected state announced ("selected") and not conveyed by colour alone |
| `FwLogoMark` | Brand mark | `contentDescription = "Fieldwright"` |
| `FwSealMark` | Sealed / not sealed | Announces "Sealed" or "Seal pending"; never colour alone |
| `FwCard` | Surface for a packet/job | If clickable, the whole card is one target with one action; no nested competing taps |
| `FwSectionRule` | Section divider with optional overline | Decorative when unlabelled (`clearAndSetSemantics`) |
| `FwPrimaryButton` / `FwSecondaryButton` / `FwGhostButton` | Actions | Visible label; loading announces "Working"; disabled announced; min 48 dp |
| `FwPill` | Status/claim chip | Text always present; tone is redundant with the words |
| `FwMoneyText` | Canonical money rendering | TalkBack reads a sentence ("four hundred fifty dollars"), not "dollar four five zero"; uses `Money.format` |
| `FwKeyValue`, `FwStat` | Labelled data | Label and value read as one node in the right order |
| `FwTextField` | Text entry | Persistent visible label; error text below the field; correct `KeyboardType`; supports 200% text |
| `FwSwitchRow` | Boolean setting with subtitle | Role `Switch`; state announced; row is one target |
| `FwAvailabilityToggle` | Online/offline | Large target; state description "Online, 15 km" / "Offline"; the toggle is the only action in the card |
| `FwChecklistRow` | Evidence obligation state | State is icon **and** text (Open / Pass / Fail / N/A); tap target 48 dp; failure reason announced |
| `FwProgressSteps` | Workflow position | Announces "Step 3 of 4: Capture" |
| `FwEmptyState` | Empty/error state | Title + body + optional action; the action is focusable first |
| `FwChainHash` | Short hash display + copy | Shows 8 characters; the **full** hash is in the content description and available via copy; the measured hash is never only truncated |
| `SiteMapCanvas` | Map of jobs and hot zones | Provides a list-equivalent for every pin (a "Show as list" action); pins are ≥ 48 dp targets; map panning is single-pointer and buttons zoom |

**Copy rules inside components.** Pass strings in; no hard-coded copy in the design system. Use the
estate vocabulary: *sealed*, *consent on file*, *in review*, *flagged*, *packet*, *records*.
Never say "verified" unqualified (`ecosystem/14` R-12). Never render a tier as a percentage.

---

## 3. State models every screen must render

`docs/CONTRACTS.md` §7 requires all four; the design system provides the shapes.

| State | Visual rule | Copy rule |
|---|---|---|
| Loading | Skeleton with `Desk`/`OliveTint` blocks, no spinner over content | "Loading packet…" only if > 1 s |
| Empty | `FwEmptyState` with a specific cause and a next action | Say why it is empty ("No offers in 15 km right now"), never "Nothing here" |
| Offline | Persistent inline banner, not a blocking modal | "Offline — 3 changes queued. They will send when you have signal." |
| Error | Inline error card at the point of failure + retry | Name the failed thing and the retry; never a raw stack or status code |
| Partial / stale | Meta line with age ("Updated 4 min ago") + refresh | The age is always visible when data is cached |
| Success | Seal mark animation once; success line persists | State the outcome, the packet id and the next action |

---

## 4. Accessibility audit (WCAG 2.2 AA)

### 4.1 Method

Contrast ratios below are computed from the token hex values with the WCAG 2.2 relative-luminance
formula (sRGB, coefficients 0.2126 / 0.7152 / 0.0722), not estimated. Text thresholds: **4.5:1**
for normal text, **3:1** for large text (≥ 24 px regular or ≥ 18.67 px bold) and for non-text UI
components/graphical objects. A ratio in the table is the measured pair, rounded to two decimals.

### 4.2 Measured contrast matrix

| Foreground | Background | Ratio | Requirement | Verdict |
|---|---|---|---:|---|
| `Ink` `#1F1C16` | `Desk` `#EFE6D4` | **13.71:1** | 4.5 (AAA) | Pass |
| `Ink` | `Sheet` `#FFFDF7` | **16.70:1** | 4.5 (AAA) | Pass |
| `InkSoft` `#6E6556` | `Desk` | **4.63:1** | 4.5 | Pass — margin 0.13; not for small print on textured surfaces |
| `InkSoft` | `Sheet` | **5.64:1** | 4.5 | Pass |
| `InkSoft` | `OliveTint` `#E7E8D6` | **4.62:1** | 4.5 | Pass |
| `InkSoft` | `MapPaper` `#E9DFC9` | **4.34:1** | 4.5 | **Fail — forbidden** on the map; use `Ink` |
| `InkSoft` | `MapParcel` `#F4EDDD` | **4.92:1** | 4.5 | Pass |
| `Olive` `#4E5D2C` | `Desk` | **5.79:1** | 4.5 | Pass |
| `Olive` | `Sheet` | **7.06:1** | 4.5 | Pass |
| `Olive` | `OliveTint` | **5.78:1** | 4.5 | Pass |
| `Olive` | `MapPaper` | **5.42:1** | 4.5 | Pass |
| `OliveDeep` `#38451F` | `Desk` | **8.31:1** | 4.5 | Pass (focus ring on `Desk`) |
| `OliveDeep` | `Sheet` | **10.12:1** | 4.5 | Pass (focus ring on `Sheet`) |
| `OliveDeep` | `OliveTint` | **8.29:1** | 4.5 | Pass |
| `OliveDeep` | `MapRoad` `#D3C3A6` | **5.95:1** | 4.5 | Pass |
| `Oxide` `#94451F` | `Desk` | **5.41:1** | 4.5 | Pass (but contract reserves oxide for accents) |
| `Oxide` | `Sheet` | **6.60:1** | 4.5 | Pass |
| `Oxide` | `MapPaper` | **5.07:1** | 4.5 | Pass (evidence marks on the map) |
| `Amber` `#9A6414` | `Sheet` | **4.91:1** | 4.5 | Pass |
| `Amber` | `Desk` | **4.03:1** | 4.5 | **Fail at body size**; pass as large text (≥ 24 px / ≥ 18.67 px bold) |
| `Amber` | `OliveTint` | **4.02:1** | 4.5 | **Fail**; do not set Amber text on an olive chip |
| `Flag` `#8C2F1F` | `Desk` | **6.67:1** | 4.5 | Pass |
| `Flag` | `Sheet` | **8.12:1** | 4.5 | Pass |
| `Sheet` `#FFFDF7` | `Olive` | **7.06:1** | 4.5 | Pass (primary button label) |
| `Sheet` | `OliveDeep` | **10.12:1** | 4.5 | Pass (pressed) |
| `Sheet` | `Oxide` | **6.60:1** | 4.5 | Pass |
| `Sheet` | `Flag` | **8.12:1** | 4.5 | Pass (blocking button/banner) |
| `White` `#FFFFFF` | `Olive` | **7.18:1** | 4.5 | Pass (icon on olive) |
| `Ink` | `OliveTint` | **13.68:1** | 4.5 | Pass (selected row) |
| `Ink` | `MapPaper` | **12.83:1** | 4.5 | Pass (map labels) |
| `Ink` | `MapParcel` | **14.57:1** | 4.5 | Pass |
| `Line` `#D8CBB4` | `Desk` | **1.29:1** | 3.0 (non-text) | **Fail as a meaningful boundary**; decorative divider only |
| `Line` | `Sheet` | **1.57:1** | 3.0 (non-text) | **Fail as a control boundary**; use `Olive`/`Ink` outlines for inputs and focus |

**Resulting rules (all enforced in code/review):**

1. **Amber is a large-text and indicator colour only.** Normal-size pending copy uses `Ink` with an
   Amber dot/icon, or sits on `Sheet` where it measures 4.91:1. Amber text on `OliveTint` is banned.
2. **`InkSoft` is never used on `MapPaper`.** Map labels use `Ink`.
3. **`Line` never identifies a control.** Inputs, buttons, checkboxes and focus states use
   `Olive`/`OliveDeep` (≥ 5.79:1) or a filled surface. Dividers stay decorative.
4. **Focus ring = 2 dp `OliveDeep` + 1 dp `Sheet` inner halo**, giving ≥ 8.31:1 against `Desk`.
5. **State is never colour alone.** Every status pill pairs `Olive`/`Amber`/`Flag` with words and,
   where space allows, an icon.
6. **The measured hash is never truncated-only.** Eight characters display, full value in the
   accessibility label and the copy action.

### 4.3 WCAG 2.2 AA conformance checklist

| Criterion | Requirement | Where it is met | Status |
|---|---|---|---|
| 1.1.1 Non-text content | Alternatives for meaningful images/icons | All icons have content descriptions; decorative rules cleared | Met |
| 1.2.1–1.2.5 Captions/audio | — | No audio or video playback in v1 (frames are still images) | N/A |
| 1.3.1 Info and relationships | Programmatic structure | Compose semantics, headings, labelled groups | Met |
| 1.3.2 Meaningful sequence | Reading order matches visual order | Verified per screen in review | Met |
| 1.3.4 Orientation | No orientation lock | Portrait and landscape both supported | Met |
| 1.3.5 Identify input purpose | Autofill/input hints | Text fields declare input type where relevant | Met (limited v1 forms) |
| 1.4.1 Use of colour | Colour not the only signal | Pills, checklist states and map pins carry text/icons | Met |
| 1.4.3 Contrast (minimum) | 4.5:1 text, 3:1 large | Matrix §4.2; two token patterns banned | Met with documented bans |
| 1.4.4 Resize text | 200% without loss | All text `sp`; layouts scroll; primary action stays reachable | Met (verify at 200% in the a11y pass) |
| 1.4.10 Reflow | No horizontal scroll at 320 dp equivalent | Single-column compact layout; no fixed-width content | Met |
| 1.4.11 Non-text contrast | 3:1 for UI components | Focus ring, inputs and selected states measured ≥ 5.4:1; `Line` not a control boundary | Met |
| 1.4.12 Text spacing | Spacing overrides survive | No fixed-height text containers; line height from type scale | Met |
| 1.4.13 Content on hover/focus | Dismissible/persistent | No hover-only content on touch; tooltips have equivalents | N/A |
| 2.1.1 Keyboard | Full keyboard operation | Hardware keyboard/D-pad focus order defined; no keyboard traps | Met |
| 2.1.2 No keyboard trap | — | Focus can always leave sheets/dialogs | Met |
| 2.2.1 Timing adjustable | Time limits can be extended | **Offer TTL is 45 s and essential to marketplace fairness**; documented exception, with a re-offer path, the auto-accept policy as an accessibility affordance, and countdown announced by TalkBack | Exception — documented |
| 2.2.2 Pause/stop/hide | Moving content controllable | Sync pulse and countdown ring honour reduce-motion; no auto-advancing content | Met |
| 2.3.1 Three flashes | — | No flashing content | Met |
| 2.4.1 Bypass blocks | — | Single-activity app; dock provides direct navigation | N/A |
| 2.4.3 Focus order | Logical | Defined per screen | Met |
| 2.4.6 Headings/labels | Descriptive | Screen and section titles from the type scale | Met |
| 2.4.7 Focus visible | Visible focus | 2 dp `OliveDeep` ring + `Sheet` halo | Met |
| 2.4.11 Focus not obscured (AA, 2.2) | Focused element not hidden by sticky bars | Dock is inset-aware; focused fields scroll above the dock | Met |
| 2.5.1 Pointer gestures | Single-pointer alternative for multipoint | Map supports pan + zoom buttons; no multi-finger requirement | Met |
| 2.5.2 Pointer cancellation | Activate on up-event | Compose click semantics; no down-only actions | Met |
| 2.5.3 Label in name | Visible label in accessible name | Buttons and fields use their visible text as the name | Met |
| 2.5.4 Motion actuation | No shake/tilt-only actions | No motion-driven actions | N/A |
| 2.5.7 Dragging movements (AA, 2.2) | Single-pointer alternative | Map pan has zoom buttons and a list view; sliders have +/- steps | Met |
| 2.5.8 Target size (minimum, AA, 2.2) | ≥ 24 dp | Design minimum 48 dp; dock 56 dp | Met (exceeds) |
| 3.1.1 Language of page | Programmatic language | English (US) declared; i18n roadmap | Met |
| 3.2.1 On focus | No context change on focus | Focus never navigates | Met |
| 3.2.2 On input | No surprise submission | No auto-submit fields | Met |
| 3.3.1 Error identification | Errors described in text | Field errors below input; gate failures named | Met |
| 3.3.2 Labels or instructions | Labels provided | Persistent labels; capture hints per checklist step | Met |
| 3.3.3 Error suggestion | Fix suggested | "Retake the gauge frame with the reference visible" style copy | Met |
| 3.3.4 Error prevention (legal/financial) | Confirmation or review | Payout confirmation sheet with fee and net; seal review before sealing | Met |
| 4.1.2 Name, role, value | Correct semantics | Component contract §2 | Met |
| 4.1.3 Status messages | Announced without focus | Sync, seal, payout and gate results are live regions | Met |

**Known gaps to close before public launch (tracked, not hidden):**

1. TalkBack sweep on all six screens with real data and at 200% text scale (not yet performed).
2. Automated accessibility checks in CI (`compose-ui-test` + manual checklist) — plan.
3. Localisation-ready strings (v1 is English-only; all strings already live in resources).
4. Contrast of any future dark scheme — cannot inherit these ratios; must be re-measured.
5. The offer-TTL exception (2.2.1) needs a written product/legal rationale in the privacy/UX
   review, not just a ticket.

---

## 5. Responsive rules

Window size classes follow Material 3 guidance; the breakpoints are the ones the app uses.

| Class | Width | Layout | Gutter | Typography | Dock |
|---|---|---|---|---|---|
| Compact | < 600 dp | Single column; cards full width; map 38% of height; sheets full width | 16 dp | Base scale | Bottom dock, 4 items |
| Medium | 600–840 dp | Single column, max content width 720 dp; packet cards 2-up where the card permits; map 32% height, side-by-side with the list | 20 dp | Base scale | Bottom dock |
| Expanded | > 840 dp | Two-pane: list + detail for Jobs and Vault (plan); max content width 960 dp | 24 dp | Base scale | Dock becomes a navigation rail (plan) |

Rules: content respects system bars and the keyboard inset; sheets never cover the primary
action; no fixed pixel heights for text; the dock is always reachable in compact; tables inside
packet detail scroll horizontally only if wrapped in an explicit scroll container with a visible
affordance. Foldables use the same size classes; no hinge-specific layout in v1.

---

## 6. Copy and tone (design-system level)

| Rule | Example |
|---|---|
| Name the thing and the state | "Packet sealed · FW-0994 · head 4f2a…" |
| Numbers before adjectives | "4 frames · 1 measurement · 2 records" |
| No unqualified "verified" | "Consent on file", "Field captured", "Verified by AUCTUM" only for tier 5 |
| Never blame the user | "The gauge frame needs the reference visible" not "Invalid photo" |
| Disclose money at the point of action | "Instant payout fee 1.5% (max $5.00). You receive $445.00." |
| Say what will not be done | "Fieldwright never records faces outside the consented scope." |
| No exclamation marks, no emoji, no urgency theatre | — |

The Brief screen (`feature:brief`) carries the operating rules in the same voice; it is the
in-product expression of this document and must be updated together with it.

---

## 7. Assets and licensing

| Asset | Source | Licence | Obligation |
|---|---|---|---|
| **Fraunces** (500/600) | `core/design/res/font/fraunces_*.ttf`; copyright "The Fraunces Project Authors" (`github.com/undercasetype/Fraunces`); embedded name table declares licence URL `https://scripts.sil.org/OFL` | SIL Open Font License 1.1 | Ship `OFL.txt` for Fraunces in `core/design/src/main/resources/`; attribution in About; do not sell the font on its own |
| **Outfit** (400/500/600) | `core/design/res/font/outfit_*.ttf`; copyright "The Outfit Project Authors" (`github.com/Outfitio/Outfit-Fonts`); embedded licence URL `https://scripts.sil.org/OFL` | SIL Open Font License 1.1 | Ship `OFL.txt` for Outfit; attribution in About |
| Material Symbols icons | `compose-material-icons-extended` | Apache License 2.0 | Attribution in About; keep the licence notice |
| Compose / AndroidX / Room / WorkManager / CameraX | Gradle version catalog | Apache 2.0 | Attribution in About; no analytics SDKs |
| Seal mark (circle + crosshair) | Supplied by the project owner | **Unverified** | Do not use in a store listing or external marketing until provenance is confirmed or the mark is redrawn ([00 §13 Q3](00-product-brief.md), [08 §6](08-roadmap-and-decisions.md)) |
| Map canvas | Drawn in-app (`SiteMapCanvas`) | In-house | No third-party map/tile licence, no vendor location sharing |
| Evidence photography | Captured by operators | Operator-uploaded, governed by consent scope | Never stock, never generated, never decorative |

**Font note.** OFL permits modification and embedding; the reserved font name may not be used for a
modified version. The bundled files are unmodified subsets; if a subset is produced, keep the
original name and ship the OFL notice. The OFL files must be added before the first external
build — this is an open task in [08](08-roadmap-and-decisions.md).

---

## 8. Design review checklist (per screen PR)

- [ ] Uses tokens only; no raw hex, no off-scale spacing.
- [ ] Fraunces for display/titles; Outfit for UI/body; `tnum` on money and hashes.
- [ ] One accent per surface; oxide reserved for evidence/location marks.
- [ ] Renders loading, empty, offline, error and success states.
- [ ] Every interactive target ≥ 48 dp; dock items ≥ 56 dp.
- [ ] Contrast pair chosen from the passing set in §4.2; amber and `Line` rules respected.
- [ ] Content descriptions on all icons; map has a list alternative.
- [ ] 200% text scale and landscape pass.
- [ ] Motion respects reduce-motion; no looping decoration beyond the sync indicator.
- [ ] Copy follows §6 and the estate vocabulary; no unqualified "verified".
- [ ] `@Preview` for each state; screenshot test added where the screen is stable.
- [ ] No third-party UI kit, analytics, or network call introduced by the screen.
