# Vanguard Platform & Vanguard Field — Complete User & Operator Guides

**Applies to:** Vanguard Web (`/` Mode B, `/community` Mode A, `/verify`), Core API, and **Vanguard Field** Android Client (`com.vanguard.field`)

---

## Part 1: Operator Quickstart — Vanguard Tactical (`/`)

### Step 1: Sign In & Workspace Orientation
1. Open the Tactical Workbench at `/`.
2. Click **Enter Demo Operations** for instant evaluation (`operator@vanguardfield.io`), or sign in with your organization credentials at `/login`.
3. Use the left navigation rail to move across six operational stations:
   - **Network & Verticals:** View your active Tier (`T1`–`T4`), 10-Vertical demand fee matrix, and legal neutrality boundaries.
   - **H3 Batch Dispatch:** Inspect active `1.5-mile` neighborhood clusters with pre-calculated IRS `$0.76/mi` wear deductions.
   - **Open Academy:** Complete free (`$0.00`) modules (`T1-ONB` through `T4-06`) to unlock commercial verticals.
   - **Economics & DePIN:** Run interactive IRS H1/H2 mileage & 120-day half-life simulations, route earnings to your Hardware Vault, license spatial queries, and claim hardware pool shares.
   - **Field Feed & Live:** Post chronological field updates, attach imagery, trigger Vision CV shingle inspection, or launch a MediaMTX RTSP/WebRTC broadcast channel.
   - **Attestor & Security:** Co-sign dossiers (`RecordKind: ATTEST`) if licensed at Tier 4, and validate device hardware attestation.

### Step 2: Claiming Tasks & Resolving `CREDENTIAL_REQUIRED` (`403`)
- **Floor Errands (`I-1 / Check C-12`):** Tier 1 errands (`V-0A`, `V-0B`) and base exterior photo bounties never require paid subscriptions or advanced gates. Click **Claim Task**, complete the visit, confirm any 4-digit customer PIN if consumer-facing, and click **Settle Bounty Now**.
- **Gated Commercial Bounties (`I-5 / Check C-11`):** Clicking **Inspect Gate (I-5)** on a Tier 2–4 task while missing a credential opens a native **Shortest Fix** dialog naming:
  - `missingCredential` (e.g., `ROOF_SAFETY`),
  - Exact reason for the gate, and
  - Exact `$0.00` Academy module (`moduleId`, `lessonTitle`, self-paced minutes) needed to unlock the task immediately.
- **Neutrality Sanitization (`Reconciliation R-08`):** Tier 1–3 operators cannot submit valuation or coverage opinion keys (`estimatedClaimValueCents`, `appraisalOpinion`). If sent, the API strips them cleanly before hashing the canonical capture record.

### Step 3: Earn-to-Learn Hardware Vault & DePIN Portfolio
1. Open **Economics & DePIN** and pick an automatic escrow withholding split (`0%`, `5%`, `10%`, or `15%`) via **Earn-to-Learn Hardware Vault**.
2. Every settled task splits the stated bounty directly into your worker ledger and Hardware Vault escrow (zero bps deducted as platform commission).
3. Once your vault balance meets an unlock threshold (`FLIR_THERMAL`, `UAV_PART_107`, `LIDAR_RIG`, `ELECTRIC_MOWER`), click **Redeem** to unlock equipment and adjacent commercial jobs.
4. Any task captured with homeowner opt-in creates a tracked **Spatial Asset**. When carriers license parcel data (`License Query`), revenue splits automatically: `50%` original capturer, `25%` opt-in homeowner, `25%` protocol treasury/burn, decaying smoothly across a 120-day economic half-life (`refreshMultiplier` kicks in at `>= 90` days).

---

## Part 2: Vanguard Neighborhood Guide (`/community` — Mode A)

Designed specifically for seniors, family caregivers, and residential property owners with extra-large `64px` minimum buttons and clear step-by-step plain-English pricing.

1. **Choose Your Service:** Tap **Grocery Carry-In & Check (`$18.00` + `$0.90` community escrow)** or **Quarter-Acre Lawn Care (`$42.00` + `$2.10` community escrow)**.
2. **Enter Contact Details:** Type your full name and home street address directly below the explanatory prompts. Optionally enter a **Family Member or Caregiver** name so relatives receive an automated digital receipt.
3. **Optional Privacy-Safe Photo Royalty:** Leave **Share exterior yard condition photos for 25% Homeowner Data Royalty** checked if you wish to earn data licensing shares from non-medical exterior yard scans (faces and license plates remain blurred before upload).
4. **Receive Your 4-Digit Verbal PIN:** Press **Request Helper Now**. Your screen displays and announces a unique **4-digit Verbal Arrival PIN**. When your helper arrives at the door, speak this 4-digit PIN aloud so they can verify arrival and unlock escrow safely.

---

## Part 3: Independent Public Verifier (`/verify`)

Adjusters, property owners, and legal reviewers can verify any Vanguard packet directly inside their browser without trusting the backend server:

1. Navigate to `/verify` or open any shared `/verify/<packetId>` URL.
2. Wait for `vanguard-verify.min.js` (`2,464` bytes, zero external dependencies) to load client-side WebCrypto primitives (`globalThis.VanguardVerifier`).
3. Enter a sealed Packet ID and click **Verify Bundle Client-Side**.
4. Review the dual cryptographic report:
   - **Client WebCrypto Chain Check:** Recomputes `SHA-256` digests over UTF-8 NFC canonical JSON preimages (`seq`, `kind`, `at`, `actorId`, `summary`, `payload`, `prevHash`) from genesis (`0x00..00`) to `headHash`.
   - **RFC 6962 Inclusion Proof:** Verifies the leaf hash and sibling `auditPath` hashes up to the anchored `rootHash`.
   - **Canonical Record Ledger:** Lists each append-only entry (`CAPTURE`, `INSPECTION`, `ATTEST`, `PAYMENT`, `CORRECTION`) with sequence numbers and hashes.

---

## Part 4: Vanguard Field Mobile App & Android Device Hub QA Runbook

**Vanguard Field** (`com.vanguard.field`, module `mobile/android/`) mirrors the Tactical workflows on Android hardware (`minSdk 26`, `compileSdk 35`):
- **Brief Screen:** Reviews flat payout rules, IRS mileage tracking, and platform safety commitments.
- **Spaces & Feed:** Territory H3 desks, field photo bursts, and live MediaMTX broadcasts.
- **Capture & Seal:** Hardware-keyed capture, LiteRT on-device redaction gate, and local `Sha256` / `MerkleLog` packet assembly matching `@vanguard/trust-engine` golden vectors byte-for-byte.
- **Anti-Spoofing Readiness:** Supplies nonce-bound Play Integrity verdicts, OEM Keybox verification, and live IMU jitter traces (`organicHumanMotion: true` required to clear `HARDWARE_ATTESTATION_T3`).

### Using Android Device Hub for Visual QA & Screen Inspection

When testing **Vanguard Field** on USB devices or AVDs via **Android Device Hub**:

1. **Discover Connected Devices First:**
   ```powershell
   adb devices -l
   android emulator list
   ```
   *(Environment note: running `adb devices -l` in the current Windows host confirmed no physical handset or emulator was attached yet; start an AVD in the Device Hub pane or connect a USB device with USB debugging authorized before capturing screens.)*
2. **Start & Mirror in Device Hub:** Open the **Android Device Hub** sidecar pane in Antigravity IDE and click **Mirror** on an online emulator/device.
3. **Inspect Live Compose Hierarchy (Text-First, Fast):**
   ```bash
   android layout --device <serial> --pretty
   ```
4. **Capture Annotated Screenshots:**
   ```bash
   android screen capture --device <serial> --annotate --output ./scratch/vanguard-field-screen.png
   ```
5. **Drive Deterministic Tap / Text Flows During QA:**
   ```bash
   adb -s <serial> shell input tap <x> <y>
   adb -s <serial> shell input text "4829"
   ```
   Wait until `getprop sys.boot_completed` equals `1` after cold emulator boots before invoking UI automation commands.
