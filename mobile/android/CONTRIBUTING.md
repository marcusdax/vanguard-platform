# Contributing to Fieldwright

Fieldwright is the Android field-evidence marketplace of the AUCTUM estate. Read
`docs/CONTRACTS.md` before your first change; it is the build contract and it wins over this file
where they disagree. The product, architecture, API, security and operations documents live in
`docs/` and are part of the release, not an afterthought.

---

## 1. Module ownership

| Path | Owner | Owns |
|---|---|---|
| `app/` | Lead | Composition root (`AppContainer`), nav host, `MainActivity`, DI wiring |
| `core/model/` | domain-core | Pure Kotlin vocabularies + decision engines (`DispatchEngine`, `TierEngine`, `SealEngine`, `LedgerEngine`, `FeeEngine`, `JobLifecycle`, `PacketAssembler`), unit tests |
| `core/crypto/` | domain-core | SHA-256, canonical JSON, chain, Merkle, ECDSA signing/verification, Keystore adapter |
| `core/design/` | ui-field | Tokens, theme, typography, fonts, components in `docs/CONTRACTS.md` §4 |
| `core/data/` | data-sync | Room schema/repositories, demo market, outbox, `SyncWorker`, fake/real backend |
| `feature/ground/`, `feature/jobs/` | ui-field | Screens + ViewModels |
| `feature/capture/` | ui-capture | Consent → capture → gates → seal |
| `feature/vault/`, `feature/payouts/` | ui-vault-money | Packet list/detail/verify/export; balances/ledger/payout |
| `feature/brief/` | Lead | Operating rules, sync health, settings |
| `docs/`, `backend/`, `.github/` | docs-gtm | Product/architecture/API/security/GTM/runbook docs, OpenAPI, mock server, CI |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` | Lead only | Shared build configuration |

Rules:

- Do not edit another owner's module. If you need a shared change, message the Lead.
- Do not edit `docs/CONTRACTS.md`; it is locked for the sprint.
- Dependency direction is one-way: `app → feature:* → core:data → core:crypto → core:model`, and
  `feature:* → core:design`. A feature never imports another feature. `core:model` imports
  nothing outside the Kotlin standard library.
- `core:design` has no model dependency; components take strings, not domain types.

---

## 2. Build and test

Gradle is **Lead-owned during the sprint** (one build at a time avoids lock contention). Write your
code, then ask the Lead for a compile check. Do not start Gradle yourself unless the Lead has asked
you to.

Commands the Lead (and CI) run, from the repository root:

```bash
gradle :app:assembleDebug
gradle :core:model:test :core:crypto:test
gradle :core:data:testDebugUnitTest
gradle :app:lintDebug
```

Local mock backend (no install, no dependencies):

```bash
node backend/mock-server.mjs --port 8787
# emulator base URL: http://10.0.2.2:8787/v1
```

The mock implements `docs/api/fieldwright.openapi.yaml`. Its README documents the seeded ids, the
canonical record preimage, idempotency and the limitations. If you change the wire contract, change
the OpenAPI file, `docs/03-api-contract.md` and the mock server together.

**Known gaps:** the repository does not commit a Gradle wrapper yet; CI provisions Gradle via
`gradle/actions/setup-gradle` (see `.github/workflows/android.yml`). Committing the wrapper is a
follow-up owned by the Lead; do not add one inside this sprint without asking.

---

## 3. Commit and branch style

- Conventional Commits: `type(scope): imperative subject`.
  - Types: `feat`, `fix`, `test`, `docs`, `refactor`, `perf`, `build`, `ci`, `chore`.
  - Scopes: module or area — `model`, `crypto`, `data`, `design`, `ground`, `jobs`, `capture`,
    `vault`, `payouts`, `brief`, `app`, `docs`, `api`, `backend`, `ci`.
  - Example: `feat(capture): block seal when redaction is unresolved`
- Subject in the imperative, ≤ 72 characters, no trailing period. Body explains *why*, not *what*
  (the diff says what).
- One logical change per commit. Do not mix a rename with a behaviour change.
- Branches: `feat/<scope>-<short-slug>`, `fix/<scope>-<short-slug>`, `docs/<short-slug>`.
- Reference the shared task or issue in the body when one exists.
- **Do not commit:** signing keys, `local.properties`, real consent data, real media, PSP
  credentials, `google-services.json`, or any secret. There are no secrets in this project; if you
  think you need one, ask the Lead.

---

## 4. Code style

- Kotlin, four-space indent, no wildcard imports. Match the style of `core/model` (KDoc,
  business language, named constants).
- **Business rules live in `core/model` engines.** A screen, repository or worker must never
  re-implement a dispatch weight, fee, tier, seal rule or lifecycle transition. Ask the engine.
- Money is integer cents; use `Money.format` and `FeeEngine`. Never a `Double` in a money path.
- Time is injected (`Clock`); never read `System.currentTimeMillis()` inside a decision function.
- Hashes are 64 lowercase hex; canonical JSON is the only serialiser used for hashing.
- UI: tokens and components only — no raw hex, no off-scale dp, no hard-coded strings in
  components, content descriptions on icons, 48 dp minimum targets.
- Every screen renders empty, loading, offline and error states; stateless composables;
  `collectAsStateWithLifecycle()`; ViewModels own state, never `Context`.
- KDoc every non-obvious decision, especially anything encoding a business rule or a legal
  constraint. A magic number without a named constant is a review block.
- No analytics SDK, no network calls or file writes outside the documented layers, no third-party
  UI kits, no `TODO()` left in owned files.

---

## 5. Pull request / review checklist

Before requesting review:

- [ ] The change is inside your module ownership and write scope.
- [ ] `gradle` build and tests pass for your module (via the Lead during the sprint).
- [ ] Unit tests cover every pure decision you added or changed (fees, tiers, seal preconditions,
      transitions, idempotency). A business rule without a test is not done.
- [ ] Every new screen state is previewable: loading, empty, offline, error, success.
- [ ] Accessibility: 48 dp targets, content descriptions, contrast pairs from
      `docs/06-design-system.md` §4.2, 200% text scale, reduce-motion respected.
- [ ] No raw hex, no off-scale spacing, no hard-coded copy in components.
- [ ] No secrets, no PII, no real media, no consent names in logs.
- [ ] Docs updated where behaviour changed: `docs/03-api-contract.md` for wire changes,
      `docs/02-domain-and-state-machines.md` for state changes, `docs/06-design-system.md` for UI
      rules, `docs/05-field-ops-runbook.md` for operational changes.
- [ ] If you touched the API: OpenAPI + mock server + prose are in sync, and the mock's documented
      smoke cases still pass.

Reviewer checklist:

- [ ] The change does not violate a domain invariant (`docs/02` §6) or an estate invariant
      (`ecosystem/04` §5).
- [ ] Money and evidence remain independent; nothing edits an appended record.
- [ ] Consent and redaction gates are still blocking, not advisory.
- [ ] Failure paths return `Result`/errors and the UI renders them; no silent catch.
- [ ] No client-asserted tier, badge state or seal.
- [ ] Commit history is clean and the subject explains the change.
- [ ] The "what we will not build" list (`docs/00` §11, `docs/04` §11) is not contradicted by the
      change (no spoofing, covert capture, tokens, analytics SDKs, generated evidence).

---

## 6. Definition of done (per workstream)

Mirrors `docs/CONTRACTS.md` §9:

1. Code compiles with the Lead's build; no `TODO()` left in owned files.
2. Unit tests cover every pure decision the workstream owns (domain-core ≥ 60 assertions;
   data-sync: repository/idempotency/outbox tests; UI: ViewModel state tests).
3. Every screen has previews, all four states, content descriptions and 48 dp targets.
4. No hard-coded colours/sizes in feature code; tokens and components only.
5. No analytics, network calls, or file writes outside the documented layers.
6. KDoc on non-obvious decisions, especially business rules.
7. Report to the Lead: what was built, what was verified, what remains.

---

## 7. Working with the shared workspace

- The Lead and all teammates share one working directory. Edits are immediately visible to
  everyone; never delete or rewrite another owner's files.
- Claim your shared task before starting, keep its write scope accurate, and mark it complete when
  the acceptance criteria are met.
- If two tasks must touch the same file, coordinate through the Lead and record the write scope on
  both tasks. Write-scope overlap is advisory, not a lock.
- Gradle builds are serialised through the Lead. Do not run long builds, emulators or servers that
  others need without asking.
- Report blockers early; a message at the step boundary is cheaper than a failed integration.

