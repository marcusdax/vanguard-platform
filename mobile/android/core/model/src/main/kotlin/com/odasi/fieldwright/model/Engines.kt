package com.odasi.fieldwright.model

import java.util.Locale
import kotlin.math.round

/**
 * Pure decision engines. **Implemented by the domain-core workstream**; every function
 * here is unit-tested without Android. The rest of the app may only ask these engines
 * for decisions — no screen, repository, or sync worker is allowed to re-implement a
 * scoring weight, a fee, a tier, or a seal rule locally.
 *
 * Design rule for this file: every function is deterministic, reads no clock, no disk,
 * no locale, and no platform type. Time is always a parameter; hashing and signing are
 * injected so the model stays dependency-free.
 */

/** Cryptographic primitives are injected so the model stays dependency-free and testable. */
fun interface HashAlgorithm {
    fun sha256Hex(bytes: ByteArray): String

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
}

interface SignatureScheme {
    val keyId: String
    fun sign(canonical: String): String?
    fun verify(canonical: String, signatureBase64: String): Boolean
}

/**
 * UTC civil-time helpers.
 *
 * Day/week/month boundaries and the ISO-8601 `at` string in the chain pre-image are
 * computed with Howard Hinnant's civil-date algorithms and integer arithmetic — no
 * `java.time`, no device timezone, no locale. Two devices in different zones must produce
 * the same date for the same instant, and a test must not depend on the host clock.
 */
internal object UtcTime {
    const val DAY_MS: Long = 86_400_000L
    private const val HOUR_MS: Long = 3_600_000L
    private const val MINUTE_MS: Long = 60_000L

    /** UTC midnight of the calendar day containing [nowEpochMs]. */
    fun dayStart(nowEpochMs: Long): Long = Math.floorDiv(nowEpochMs, DAY_MS) * DAY_MS

    /** UTC Monday 00:00 of the ISO week containing [nowEpochMs]. 1970-01-01 was a Thursday. */
    fun weekStart(nowEpochMs: Long): Long {
        val epochDay = Math.floorDiv(nowEpochMs, DAY_MS)
        val daysSinceMonday = Math.floorMod(epochDay + 3, 7L)
        return (epochDay - daysSinceMonday) * DAY_MS
    }

    /** UTC first-of-month for the calendar month containing [nowEpochMs]. */
    fun monthStart(nowEpochMs: Long): Long {
        val epochDay = Math.floorDiv(nowEpochMs, DAY_MS)
        val (year, month, _) = civilFromEpochDay(epochDay)
        return epochDayFromCivil(year, month, 1L) * DAY_MS
    }

    /**
     * `YYYY-MM-DDTHH:MM:SS.sssZ`, the exact shape of `Date.prototype.toISOString()` that
     * the sync server writes into `Record.at` and hashes. Milliseconds are always present,
     * so the byte string never depends on whether the instant happens to be on a whole
     * second.
     */
    fun isoInstant(epochMs: Long): String {
        val epochDay = Math.floorDiv(epochMs, DAY_MS)
        val millisOfDay = Math.floorMod(epochMs, DAY_MS)
        val (year, month, day) = civilFromEpochDay(epochDay)
        val hour = (millisOfDay / HOUR_MS).toInt()
        val minute = ((millisOfDay % HOUR_MS) / MINUTE_MS).toInt()
        val second = ((millisOfDay % MINUTE_MS) / 1_000L).toInt()
        val millis = (millisOfDay % 1_000L).toInt()
        return buildString(24) {
            append(year.toString().padStart(4, '0')).append('-')
            append(month.toString().padStart(2, '0')).append('-')
            append(day.toString().padStart(2, '0')).append('T')
            append(hour.toString().padStart(2, '0')).append(':')
            append(minute.toString().padStart(2, '0')).append(':')
            append(second.toString().padStart(2, '0')).append('.')
            append(millis.toString().padStart(3, '0')).append('Z')
        }
    }

    /** Howard Hinnant's civil-from-days algorithm, pure integer arithmetic. */
    private fun civilFromEpochDay(epochDay: Long): Triple<Long, Long, Long> {
        val z = epochDay + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val dayOfEra = z - era * 146_097L
        val yearOfEra =
            (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val year = yearOfEra + era * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPrime = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
        val month = if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L
        return Triple(if (month <= 2L) year + 1L else year, month, day)
    }

    private fun epochDayFromCivil(year: Long, month: Long, day: Long): Long {
        val adjustedYear = if (month <= 2L) year - 1L else year
        val era = Math.floorDiv(adjustedYear, 400L)
        val yearOfEra = adjustedYear - era * 400L
        val monthPrime = if (month > 2L) month - 3L else month + 9L
        val dayOfYear = (153L * monthPrime + 2L) / 5L + day - 1L
        val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}

/**
 * The canonical record pre-image. **This must match the sync server byte for byte** —
 * `docs/03-api-contract.md` §3.4 step 0 and `backend/mock-server.mjs` `recordHash`:
 *
 * ```
 * sha256(canonicalJson({seq, kind, at, actorId, summary, payload, prevHash}))
 * ```
 *
 * Rules that cannot drift (a mismatch is a `422 CHAIN_INVALID` at upload):
 * - the **exact field set** above — no extra fields, no renames (`actorId`, `prevHash`);
 * - `seq` is a JSON **number**, 1-based, identical to the wire `Record.seq`;
 * - `at` is **ISO-8601 UTC with milliseconds** (`...sssZ`), not epoch milliseconds;
 * - `payload` is a **nested JSON object of string values** whose keys sort recursively —
 *   not flattened into the parent object, so a payload key can never shadow a structural
 *   field;
 * - keys sorted, no whitespace, JSON string escaping (see [CanonicalJson]).
 *
 * The rule lives with the domain model, not with the crypto adapter, because both the
 * writer ([PacketAssembler]) and the verifier (`:core:crypto` ChainVerifier) must reuse
 * it instead of re-deriving a second, subtly different layout. `:core:crypto`'s golden
 * vector test pins the bytes against the mock server.
 */
object ChainRule {
    /** Exact field set of the pre-image, in documented order (encoding sorts them anyway). */
    val FIELD_NAMES: List<String> =
        listOf("seq", "kind", "at", "actorId", "summary", "payload", "prevHash")

    /** The exact UTF-8 string that is hashed for [record]. */
    fun canonicalPreimage(record: ChainRecord): String = CanonicalJson.encodeTyped(
        linkedMapOf(
            "seq" to CanonicalJson.Value.Integer(record.seq.toLong()),
            "kind" to CanonicalJson.Value.Text(record.kind.name),
            "at" to CanonicalJson.Value.Text(UtcTime.isoInstant(record.atEpochMs)),
            "actorId" to CanonicalJson.Value.Text(record.actorId),
            "summary" to CanonicalJson.Value.Text(record.summary),
            "payload" to CanonicalJson.Value.Object(
                record.payload.mapValues { (_, value) -> CanonicalJson.Value.Text(value) },
            ),
            "prevHash" to CanonicalJson.Value.Text(record.prevHash),
        ),
    )

    fun hash(record: ChainRecord, hasher: HashAlgorithm): String =
        hasher.sha256Hex(canonicalPreimage(record))

    /**
     * The `at` string for a record, in the exact wire/server format
     * (`YYYY-MM-DDTHH:MM:SS.sssZ`). The sync layer must use this when serialising a
     * record for upload: the bytes that were hashed and the bytes that are sent have to
     * be the same instant, or the server recomputes a different hash.
     */
    fun isoAt(epochMs: Long): String = UtcTime.isoInstant(epochMs)
}

/** Recompute the hash of this record from its content. Used by writers and verifiers. */
fun ChainRecord.computeHash(hasher: HashAlgorithm): String = ChainRule.hash(this, hasher)

/**
 * RFC 6962-style Merkle domain separation, shared with `:core:crypto` MerkleLog.
 *
 * RFC 6962 prefixes leaves with `0x00` and interior nodes with `0x01` so a leaf can
 * never be mistaken for an interior node (second-preimage defence). The leaf rule has
 * to live in the domain model as well as in the crypto adapter: [PacketAssembler.seal]
 * stamps a packet's `merkleLeaf` on the device (which may be offline and not running the
 * log), and the public-log verifier must recompute exactly the same bytes.
 */
object MerkleHash {
    const val LEAF_PREFIX: Byte = 0x00
    const val NODE_PREFIX: Byte = 0x01

    /**
     * Leaf hash of `SHA-256(0x00 || leafData)`. [leafData] is the record/packet head hash
     * as lowercase hex text, encoded UTF-8 — deterministic on every device and every JVM.
     */
    fun leafHash(leafData: String, hasher: HashAlgorithm): String =
        hasher.sha256Hex(byteArrayOf(LEAF_PREFIX) + leafData.toByteArray(Charsets.UTF_8))

    /** Interior-node hash of `SHA-256(0x01 || left || right)`, hashes as raw 32-byte values. */
    fun nodeHash(leftHash: String, rightHash: String, hasher: HashAlgorithm): String =
        hasher.sha256Hex(
            byteArrayOf(NODE_PREFIX) + hexToBytes(leftHash) + hexToBytes(rightHash),
        )

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have an even length." }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex[i * 2])
            val lo = hexDigit(hex[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[value ushr 4]
            out[i * 2 + 1] = digits[value and 0x0F]
        }
        return String(out)
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Not a hex digit: '$c'")
    }
}

object DispatchEngine {
    /** Offers expire in 45 s. Long enough to read, short enough to keep a route moving. */
    const val OFFER_TTL_MS: Long = 45_000L
    const val MAX_ACTIVE_JOBS: Int = 3
    const val MAX_OFFER_RADIUS_METERS: Int = 25_000
    /** A payout floor so a "convenience" job cannot become unpaid drive time. */
    const val MIN_PAYOUT_FLOOR_CENTS: Int = 2_500
    /** Beyond this, the operator is done for the day whatever the market says. */
    const val FATIGUE_CAP_MINUTES: Int = 600

    /** Weights sum to 1.0. Kept as data so a pricing experiment can be reviewed, not guessed. */
    val WEIGHTS: Map<String, Double> = mapOf(
        "proximity" to 0.30,
        "payout" to 0.25,
        "urgency" to 0.15,
        "credential" to 0.15,
        "reliability" to 0.10,
        "fairness" to 0.05,
    )

    /** Payout that scores a full 1.0 on the payout weight; above it, payout is not rewarded further. */
    private const val REFERENCE_PAYOUT_CENTS = 60_000.0
    /** True walk-up work scores as urgent; scheduled work loses urgency linearly over this window. */
    private const val URGENCY_WINDOW_MINUTES = 180.0
    private const val URGENCY_FLOOR = 0.15
    private const val SURGE_MIN = 1.0
    private const val SURGE_MAX = 1.6
    private const val TRUE = "true"

    /**
     * Rank the market for one operator. Rejections carry a reason so the UI can be
     * honest about why a nearby job is not being offered.
     *
     * Rejection is deliberately *structural* (offline, at the stack limit, wrong
     * credential, outside the radius, under the floor) — never a hidden score
     * threshold. The operator can always see why a job disappeared.
     *
     * Distance is recomputed from the operator's current fix rather than trusting the
     * job's frozen `distanceMeters`, because an operator who has driven closer should
     * see the job rank up, and a stale snapshot must not create a phantom nearby job.
     */
    fun rank(
        jobs: List<Job>,
        operator: Operator,
        availability: Availability,
        operatorPoint: GeoPoint,
        activeCount: Int,
        market: MarketSnapshot,
    ): DispatchResult {
        val marketSurge = market.surgeMultiplier.coerceIn(SURGE_MIN, SURGE_MAX)

        if (!availability.isOnline) {
            return DispatchResult(
                ranked = emptyList(),
                rejected = jobs.map { it to "Offline — go online to receive offers." },
                marketSurge = marketSurge,
            )
        }
        if (activeCount >= MAX_ACTIVE_JOBS) {
            return DispatchResult(
                ranked = emptyList(),
                rejected = jobs.map {
                    it to "You already hold $activeCount active jobs — the cap is $MAX_ACTIVE_JOBS. Finish or hand off one first."
                },
                marketSurge = marketSurge,
            )
        }

        val radiusMeters = availability.radiusMeters
            .coerceAtMost(MAX_OFFER_RADIUS_METERS)
            .coerceAtLeast(1)
        val ranked = ArrayList<ScoredJob>(jobs.size)
        val rejected = ArrayList<Pair<Job, String>>(jobs.size)

        for (job in jobs) {
            val distanceMeters = GeoMath.distanceMeters(operatorPoint, job.site.point)
            val distanceKm = distanceMeters / 1000.0
            val jobSurge = surgeFor(job, market)
            val offeredPayout = offeredPayoutCents(job, jobSurge)

            val rejectReason: String? = when {
                job.status != JobStatus.AVAILABLE && job.status != JobStatus.OFFERED ->
                    "This job is ${job.status.label.lowercase(Locale.US)}; it is not open for offers."

                distanceMeters > radiusMeters ->
                    "Site is ${oneDecimal(distanceKm)} km away, outside your ${oneDecimal(radiusMeters / 1000.0)} km working radius."

                distanceMeters > MAX_OFFER_RADIUS_METERS ->
                    "Site is beyond the $MAX_OFFER_RADIUS_METERS km dispatch radius."

                job.requiredCredentials.any { it !in operator.credentials } -> {
                    val missing = job.requiredCredentials.filter { it !in operator.credentials }
                    "Requires ${missing.joinToString { it.label }} — add the credential before this job can be offered."
                }

                offeredPayout < MIN_PAYOUT_FLOOR_CENTS ->
                    "Offered payout ${Money.format(offeredPayout)} is below the ${Money.format(MIN_PAYOUT_FLOOR_CENTS)} floor."

                else -> null
            }
            if (rejectReason != null) {
                rejected += job to rejectReason
                continue
            }

            val components = mapOf(
                "proximity" to (1.0 - distanceMeters / radiusMeters).coerceIn(0.0, 1.0),
                "payout" to (offeredPayout / REFERENCE_PAYOUT_CENTS).coerceIn(0.0, 1.0),
                "urgency" to urgencyOf(job, market),
                "credential" to credentialComponent(job),
                "reliability" to reliabilityComponent(operator),
                "fairness" to fairnessComponent(job.id, operator.id),
            )
            // Score is the disclosed weighted sum; weights live in [WEIGHTS], not in code.
            val score = WEIGHTS.entries.sumOf { (name, weight) -> weight * (components[name] ?: 0.0) }
            val etaMinutes = GeoMath.etaMinutes(distanceMeters)

            ranked += ScoredJob(
                job = job,
                score = score,
                components = components,
                surgeMultiplier = jobSurge,
                payoutCents = offeredPayout,
                etaMinutes = etaMinutes,
                reasons = reasonsFor(job, operator, distanceKm, offeredPayout, jobSurge, market),
            )
        }

        // Fairness hash is a stable tie-break, so equal scores never depend on map iteration order.
        ranked.sortWith(
            compareByDescending<ScoredJob> { it.score }
                .thenBy { it.etaMinutes }
                .thenBy { it.job.id },
        )
        return DispatchResult(ranked = ranked, rejected = rejected, marketSurge = marketSurge)
    }

    /**
     * Evaluate the operator's written auto-accept policy against a concrete offer.
     *
     * This form trusts the offer's pre-computed `autoAcceptEligible` flag (the dispatch
     * repository evaluates the full policy when it builds the offer) but still enforces
     * the hard invariants itself: an expired/closed offer, the active-job cap, the payout
     * floor and the surge disclosure. Defence in depth — a stale offer must never be
     * auto-accepted just because its flag was computed before the stack limit was hit.
     */
    fun autoAccept(offer: Offer, activeCount: Int): AutoAcceptDecision {
        hardInvariantFailure(offer, activeCount)?.let { return AutoAcceptDecision(false, it) }
        if (!offer.autoAcceptEligible) {
            return AutoAcceptDecision(
                false,
                "Passed: ${Money.format(offer.payoutCents)} at ${oneDecimal(offer.job.distanceMeters / 1000.0)} km " +
                    "is outside your written auto-accept policy.",
            )
        }
        return AutoAcceptDecision(
            true,
            "Auto-accepted: ${Money.format(offer.payoutCents)} for a ${offer.job.type.shortLabel} job " +
                "${oneDecimal(offer.job.distanceMeters / 1000.0)} km away · stack ${activeCount + 1}/$MAX_ACTIVE_JOBS · within policy.",
        )
    }

    /**
     * Full policy evaluation, with the explicit reason for every accept or pass.
     *
     * [AutoAcceptPolicy] is the operator's own written statement of what may be accepted
     * without a tap. The offer's `autoAcceptEligible` flag must be produced from this
     * function so the policy has exactly one implementation, and so the Ground screen can
     * explain *why* a qualifying-looking offer was passed.
     */
    fun policyDecision(
        offer: Offer,
        activeCount: Int,
        policy: AutoAcceptPolicy,
        operator: Operator,
    ): AutoAcceptDecision {
        hardInvariantFailure(offer, activeCount)?.let { return AutoAcceptDecision(false, it) }
        if (!policy.enabled) {
            return AutoAcceptDecision(false, "Auto-accept is off in your policy.")
        }
        if (activeCount >= policy.maxActiveStack) {
            return AutoAcceptDecision(
                false,
                "Passed: your policy stacks at most ${policy.maxActiveStack} jobs (holding $activeCount).",
            )
        }
        if (offer.job.type !in policy.allowedTypes) {
            return AutoAcceptDecision(
                false,
                "Passed: ${offer.job.type.label} is not in your auto-accept job types.",
            )
        }
        if (offer.payoutCents < policy.minPayoutCents) {
            return AutoAcceptDecision(
                false,
                "Passed: ${Money.format(offer.payoutCents)} is under your ${Money.format(policy.minPayoutCents)} auto-accept minimum.",
            )
        }
        if (offer.job.distanceMeters > policy.maxDistanceMeters) {
            return AutoAcceptDecision(
                false,
                "Passed: ${oneDecimal(offer.job.distanceMeters / 1000.0)} km is beyond your " +
                    "${oneDecimal(policy.maxDistanceMeters / 1000.0)} km auto-accept distance.",
            )
        }
        if (policy.requireCredentialMatch) {
            val missing = offer.job.requiredCredentials.filter { it !in operator.credentials }
            if (missing.isNotEmpty()) {
                return AutoAcceptDecision(
                    false,
                    "Passed: missing ${missing.joinToString { it.label }} for this job.",
                )
            }
        }
        return AutoAcceptDecision(
            true,
            "Auto-accepted: ${Money.format(offer.payoutCents)} ${offer.job.type.shortLabel} · " +
                "${oneDecimal(offer.job.distanceMeters / 1000.0)} km · stack ${activeCount + 1}/${policy.maxActiveStack}.",
        )
    }

    fun expiresAt(nowEpochMs: Long): Long = nowEpochMs + OFFER_TTL_MS

    /** Payout shown on the offer card: bounty + bonus, scaled by disclosed surge. */
    fun offeredPayoutCents(job: Job, surgeMultiplier: Double): Int =
        kotlin.math.round(job.payoutCents * surgeMultiplier).toInt()

    /**
     * Surge for one job: a hot zone over the site wins over the market-wide multiplier,
     * because the zone is the disclosure the operator is shown. Clamped to the disclosed
     * 1.0–1.6 band so no data error can advertise a multiplier we would not honour.
     */
    private fun surgeFor(job: Job, market: MarketSnapshot): Double {
        val zone = market.hotZones
            .filter { GeoMath.withinMeters(job.site.point, it.point, it.radiusMeters.toDouble()) }
            .maxByOrNull { it.surgeMultiplier }
        return (zone?.surgeMultiplier ?: market.surgeMultiplier).coerceIn(SURGE_MIN, SURGE_MAX)
    }

    /**
     * Urgency: walk-up work is immediate (1.0); scheduled work is less urgent the further
     * away it is, down to a small floor rather than zero — a booked job still has value.
     */
    private fun urgencyOf(job: Job, market: MarketSnapshot): Double {
        val scheduled = job.scheduledForEpochMs ?: return 1.0
        val minutesUntil = (scheduled - market.nowEpochMs) / 60_000.0
        return when {
            minutesUntil <= 0.0 -> 1.0
            minutesUntil >= URGENCY_WINDOW_MINUTES -> URGENCY_FLOOR
            else -> 1.0 - (minutesUntil / URGENCY_WINDOW_MINUTES) * (1.0 - URGENCY_FLOOR)
        }
    }

    /**
     * Credentialed work is scarcer and pays for certified hours, so it scores above
     * uncredentialed work for the operators who hold the credential. Mismatches never
     * reach scoring — they are rejected above.
     */
    private fun credentialComponent(job: Job): Double =
        if (job.requiredCredentials.isNotEmpty()) 1.0 else 0.5

    /** The operator's own track record: on-time matters more than volume, so weight it 60/40. */
    private fun reliabilityComponent(operator: Operator): Double =
        (0.6 * operator.onTimeRate.coerceIn(0.0, 1.0) + 0.4 * operator.acceptanceRate.coerceIn(0.0, 1.0))
            .coerceIn(0.0, 1.0)

    /**
     * Fairness tie-break: a stable hash of (operator, job) so the same operator is not
     * always first to the same route. It uses no demographic, no location history and no
     * hidden priority — only two opaque ids, so it is reproducible and reviewable.
     */
    private fun fairnessComponent(jobId: String, operatorId: String): Double {
        var hash = 0x811C9DC5.toInt() // FNV-1a 32-bit offset basis
        for (ch in "$operatorId|$jobId") {
            hash = hash xor ch.code
            hash *= 16_777_619 // FNV prime; Int overflow is the intended wrapping behaviour
        }
        return (hash.toLong() and 0xFFFF_FFFFL).toDouble() / 0xFFFF_FFFFL.toDouble()
    }

    private fun reasonsFor(
        job: Job,
        operator: Operator,
        distanceKm: Double,
        payoutCents: Int,
        surge: Double,
        market: MarketSnapshot,
    ): List<String> {
        val reasons = ArrayList<String>(5)
        reasons += "${oneDecimal(distanceKm)} km · ${GeoMath.etaMinutes(distanceKm * 1000.0)} min"
        val perHour = if (job.estimatedMinutes > 0) {
            payoutCents.toDouble() * 60.0 / job.estimatedMinutes
        } else {
            null
        }
        reasons += if (perHour != null) {
            "${Money.format(payoutCents)} payout · ${Money.format(round(perHour).toInt())}/hr"
        } else {
            "${Money.format(payoutCents)} payout"
        }
        if (surge > 1.0) {
            reasons += "${oneDecimal(surge)}× surge disclosed before acceptance"
        }
        if (job.requiredCredentials.isNotEmpty()) {
            reasons += "Credential matched: ${job.requiredCredentials.joinToString { it.short }}"
        }
        val scheduled = job.scheduledForEpochMs
        if (scheduled != null) {
            val minutes = ((scheduled - market.nowEpochMs) / 60_000).toInt()
            reasons += if (minutes > 0) "Starts in $minutes min" else "Start window is open"
        }
        if (job.demo) {
            reasons += "Sample job — never uploaded to the live log"
        }
        return reasons
    }

    private fun hardInvariantFailure(offer: Offer, activeCount: Int): String? = when {
        offer.status != OfferStatus.PENDING ->
            "Passed: this offer is already ${offer.status.name.lowercase(Locale.US)}."

        activeCount >= MAX_ACTIVE_JOBS ->
            "Passed: already holding $activeCount active jobs (limit $MAX_ACTIVE_JOBS)."

        offer.expiresAtEpochMs <= offer.offeredAtEpochMs ||
            offer.expiresAtEpochMs - offer.offeredAtEpochMs > OFFER_TTL_MS ->
            "Passed: the offer window is malformed (must be 1–$OFFER_TTL_MS ms)."

        offer.payoutCents < MIN_PAYOUT_FLOOR_CENTS ->
            "Passed: ${Money.format(offer.payoutCents)} is below the ${Money.format(MIN_PAYOUT_FLOOR_CENTS)} payout floor."

        offer.surgeMultiplier < SURGE_MIN || offer.surgeMultiplier > SURGE_MAX ->
            "Passed: surge ${oneDecimal(offer.surgeMultiplier)}× is outside the disclosed ${SURGE_MIN}–${SURGE_MAX}× band."

        else -> null
    }

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
}

object TierEngine {

    /**
     * Payload conventions that encode evidence *claims*. A record says what happened;
     * the engine decides what that is worth. Keys are stable strings because records are
     * append-only — a rename would orphan every historical packet.
     */
    private const val PAYLOAD_CAPTURE_STANDARD_MET = "captureStandardMet"
    private const val PAYLOAD_MEASUREMENT_CALIBRATED = "measurementCalibrated"
    private const val PAYLOAD_PAYMENT_SETTLED = "settled"
    private const val PAYLOAD_STORM_ATTRIBUTION = "stormAttribution"
    private const val PAYLOAD_FLAG = "flag"
    private const val PAYLOAD_FLAG_BADGE = "flagBadge"
    private const val PAYLOAD_WAIVED_BADGES = "waivedBadges"
    private const val TRUE = "true"

    /**
     * Tier is derived from records only. See docs/01-architecture.md §Evidence.
     *
     * The ladder is *evidence strength*, not a progress bar: the engine reports the
     * highest claim the record set actually supports. A capture does not become a
     * payment because it is later than a report; each rung is an independent claim.
     *
     * Tiers 4 and 5 encode a deliberate business ordering:
     * - **Tier 4, payment verified** — a settled payment is the strongest external
     *   confirmation available before adjudication, because money moved between the two
     *   parties and the requester's own ledger has accepted the subject.
     * - **Tier 5, verified by AUCTUM** — every required badge is independently
     *   re-derived and verified from the immutable records. "AUCTUM" here is the trust
     *   engine itself, not an external approval queue: verification is deterministic and
     *   can therefore happen offline. The seal adds the chain/log/as-of preconditions on
     *   top in [SealEngine], which is why tier 5 alone cannot produce a seal.
     */
    fun derive(packet: EvidencePacket): EvidenceTier {
        val records = packet.records
        if (records.isEmpty()) return EvidenceTier.REPORTED

        var tier = EvidenceTier.REPORTED
        if (records.any { it.isIndependentAttestation(packet.operatorId) }) {
            tier = maxTier(tier, EvidenceTier.COUNTERPARTY_CONFIRMED)
        }
        if (records.any {
                it.kind == RecordKind.CAPTURE &&
                    it.payload[PAYLOAD_CAPTURE_STANDARD_MET].equals(TRUE, ignoreCase = true)
            }
        ) {
            tier = maxTier(tier, EvidenceTier.FIELD_CAPTURED)
        }
        if (records.any {
                it.kind == RecordKind.PAYMENT &&
                    it.payload[PAYLOAD_PAYMENT_SETTLED].equals(TRUE, ignoreCase = true)
            }
        ) {
            tier = maxTier(tier, EvidenceTier.PAYMENT_VERIFIED)
        }
        val required = badgesFor(packet).filter { it.required }
        if (required.isNotEmpty() &&
            required.all { it.state == BadgeState.VERIFIED } &&
            required.none { it.state == BadgeState.FLAGGED }
        ) {
            tier = maxTier(tier, EvidenceTier.VERIFIED_BY_AUCTUM)
        }
        return tier
    }

    /**
     * Recompute badge states from the record set; never trust a stored badge blindly.
     *
     * Only the badges that matter for this job family (plus SETTLED, which every money
     * surface needs) are returned. A stored badge list is a *cache for display*; if the
     * cache disagrees with the evidence, the evidence wins — that is the whole point of
     * an append-only record set.
     */
    fun badgesFor(packet: EvidencePacket): List<Badge> {
        val required = BadgeKey.requiredFor(packet.jobType)
        return BadgeKey.entries
            .filter { it in required || it == BadgeKey.SETTLED }
            .map { key ->
                val outcome = deriveBadge(packet, key)
                Badge(
                    key = key,
                    state = outcome.state,
                    required = key in required,
                    rationale = outcome.rationale,
                    evidenceRefs = outcome.refs,
                )
            }
    }

    private data class BadgeOutcome(
        val state: BadgeState,
        val rationale: String,
        val refs: List<String>,
    )

    private fun deriveBadge(packet: EvidencePacket, key: BadgeKey): BadgeOutcome {
        val evidence = evidenceFor(packet, key)
        if (flagRecord(packet, key) != null) {
            return BadgeOutcome(
                BadgeState.FLAGGED,
                "Flagged by an inspection record — ${evidence.rationale}",
                evidence.refs,
            )
        }
        if (isWaived(packet, key) && evidence.state != BadgeState.VERIFIED) {
            return BadgeOutcome(
                BadgeState.WAIVED,
                "Waived: the requester accepted this claim as not applicable.",
                evidence.refs,
            )
        }
        return evidence
    }

    private fun evidenceFor(packet: EvidencePacket, key: BadgeKey): BadgeOutcome = when (key) {
        BadgeKey.STORM_ATTRIBUTION -> {
            val footprint = packet.records.firstOrNull {
                it.payload[PAYLOAD_STORM_ATTRIBUTION].equals(TRUE, ignoreCase = true)
            }
            when {
                checklistPassed(packet, "footprint") || footprint != null -> BadgeOutcome(
                    BadgeState.VERIFIED,
                    "The site sits inside a recorded hail-event footprint for this date.",
                    refsFor(listOfNotNull(footprint)),
                )
                packet.records.any { it.kind == RecordKind.CAPTURE } -> BadgeOutcome(
                    BadgeState.IN_REVIEW,
                    "Frames are on file, but storm attribution has not been confirmed.",
                    refsFor(packet.records.filter { it.kind == RecordKind.CAPTURE }),
                )
                else -> BadgeOutcome(
                    BadgeState.MISSING,
                    "No storm footprint confirmation is attached to this packet.",
                    emptyList(),
                )
            }
        }

        BadgeKey.FIELD_CAPTURED -> {
            val unresolvedRedaction = packet.media.filter {
                it.isPhoto && it.redaction == RedactionState.REQUIRED
            }
            val standard = packet.records.firstOrNull {
                it.kind == RecordKind.CAPTURE &&
                    it.payload[PAYLOAD_CAPTURE_STANDARD_MET].equals(TRUE, ignoreCase = true)
            }
            val captures = packet.records.filter { it.kind == RecordKind.CAPTURE }
            when {
                unresolvedRedaction.isNotEmpty() -> BadgeOutcome(
                    BadgeState.FLAGGED,
                    "${unresolvedRedaction.size} frame(s) still require redaction (faces/plates); " +
                        "a blocking gate must be resolved before this claim can verify.",
                    refsFor(captures),
                )
                standard != null -> BadgeOutcome(
                    BadgeState.VERIFIED,
                    "Capture met the market standard: ${packet.jobType.captureStandard}",
                    refsFor(listOf(standard)),
                )
                captures.isNotEmpty() -> BadgeOutcome(
                    BadgeState.IN_REVIEW,
                    "Capture records exist but none claim the market capture standard yet.",
                    refsFor(captures),
                )
                else -> BadgeOutcome(
                    BadgeState.MISSING,
                    "No capture record: the field evidence has not been recorded.",
                    emptyList(),
                )
            }
        }

        BadgeKey.MEASURED -> {
            val validMeasurements = packet.measurements.filter {
                it.referenceMeters > 0.0 && it.pixelSpan > 0.0 && it.meters > 0.0
            }
            val calibrated = packet.records.firstOrNull {
                it.payload[PAYLOAD_MEASUREMENT_CALIBRATED].equals(TRUE, ignoreCase = true)
            }
            when {
                validMeasurements.isNotEmpty() || calibrated != null -> BadgeOutcome(
                    BadgeState.VERIFIED,
                    if (validMeasurements.isNotEmpty()) {
                        "${validMeasurements.size} measurement(s) calibrated against a known reference."
                    } else {
                        "A calibrated measurement record is on file."
                    },
                    refsFor(listOfNotNull(calibrated)),
                )
                packet.measurements.isNotEmpty() -> BadgeOutcome(
                    BadgeState.IN_REVIEW,
                    "Measurements exist but their reference scale is incomplete or uncalibrated.",
                    emptyList(),
                )
                else -> BadgeOutcome(
                    BadgeState.MISSING,
                    "Dimensions are still estimates; a calibrated measurement is required.",
                    emptyList(),
                )
            }
        }

        BadgeKey.SCOPE_AGREED -> {
            val consent = packet.consent
            when {
                checklistPassed(packet, "scope") || (consent != null && consent.scopeText.isNotBlank()) ->
                    BadgeOutcome(
                        BadgeState.VERIFIED,
                        "The subject accepted this capture scope in this session.",
                        emptyList(),
                    )
                packet.records.any { it.kind == RecordKind.CUSTODY } -> BadgeOutcome(
                    BadgeState.IN_REVIEW,
                    "The session is open; scope confirmation has not been recorded.",
                    emptyList(),
                )
                else -> BadgeOutcome(
                    BadgeState.MISSING,
                    "The capture scope has not been agreed with the subject.",
                    emptyList(),
                )
            }
        }

        BadgeKey.CONSENT_ON_FILE -> {
            val consent = packet.consent
            when {
                consent == null -> BadgeOutcome(
                    BadgeState.MISSING,
                    "No consent record: nobody has authorized this capture.",
                    emptyList(),
                )
                consent.name.isBlank() || consent.role.isBlank() || consent.scopeText.isBlank() ->
                    BadgeOutcome(
                        BadgeState.IN_REVIEW,
                        "The consent record is incomplete (name, role and scope are required).",
                        emptyList(),
                    )
                consent.method == ConsentMethod.IN_APP_SIGNATURE && consent.signatureHash.isNullOrBlank() ->
                    BadgeOutcome(
                        BadgeState.IN_REVIEW,
                        "Signed-in-app consent is missing its signature hash.",
                        emptyList(),
                    )
                else -> BadgeOutcome(
                    BadgeState.VERIFIED,
                    "Authorized by the ${consent.role} (${consent.method.label}).",
                    emptyList(),
                )
            }
        }

        BadgeKey.SETTLED -> {
            val payments = packet.records.filter { it.kind == RecordKind.PAYMENT }
            val settled = payments.firstOrNull {
                it.payload[PAYLOAD_PAYMENT_SETTLED].equals(TRUE, ignoreCase = true)
            }
            when {
                settled != null -> BadgeOutcome(
                    BadgeState.VERIFIED,
                    "The requester paid for this packet.",
                    refsFor(listOf(settled)),
                )
                payments.isNotEmpty() -> BadgeOutcome(
                    BadgeState.IN_REVIEW,
                    "A payment record is on file but settlement is not confirmed.",
                    refsFor(payments),
                )
                else -> BadgeOutcome(
                    BadgeState.MISSING,
                    "The requester has not paid for this packet yet.",
                    emptyList(),
                )
            }
        }
    }

    private fun flagRecord(packet: EvidencePacket, key: BadgeKey): ChainRecord? =
        packet.records.firstOrNull {
            it.kind == RecordKind.INSPECTION &&
                it.payload[PAYLOAD_FLAG].equals(TRUE, ignoreCase = true) &&
                it.payload[PAYLOAD_FLAG_BADGE] == key.name
        }

    private fun isWaived(packet: EvidencePacket, key: BadgeKey): Boolean =
        packet.records.any { record ->
            record.payload[PAYLOAD_WAIVED_BADGES]
                ?.split(',')
                ?.map { it.trim() }
                ?.contains(key.name) == true
        }

    private fun checklistPassed(packet: EvidencePacket, key: String): Boolean =
        packet.checklist.any { it.key == key && it.result == StepResult.PASS }

    private fun refsFor(records: List<ChainRecord>): List<String> =
        records.map { record ->
            if (record.hash.isBlank()) "seq ${record.seq}" else record.hash.take(8)
        }

    private fun ChainRecord.isIndependentAttestation(operatorId: String): Boolean =
        (kind == RecordKind.ATTEST || kind == RecordKind.INSPECTION) && actorId != operatorId

    private fun maxTier(current: EvidenceTier, candidate: EvidenceTier): EvidenceTier =
        if (candidate.level > current.level) candidate else current
}

object SealEngine {

    /**
     * Seal preconditions: tier 5, every required badge verified, nothing flagged,
     * chain valid, inclusion in the log, an as-of date. Reasons explain every failure.
     *
     * The seal is **reversible** on purpose. A seal is not a receipt for a photo; it is
     * the market's promise that this evidence can be defended. If a flag, a broken chain
     * or a missing consent is discovered later, the promise is withdrawn ([SealState]
     * has WITHDRAWN) rather than silently rewritten — append-only evidence and a
     * reversible claim are what make the packet sellable to an insurer.
     *
     * [nowEpochMs] is only used to describe an absent as-of date and as a display
     * fallback; this function never invents evidence.
     */
    fun evaluate(
        packet: EvidencePacket,
        chainValid: Boolean,
        merkleIncluded: Boolean,
        nowEpochMs: Long,
    ): SealEvaluation {
        val tier = TierEngine.derive(packet)
        val badges = TierEngine.badgesFor(packet)
        val required = badges.filter { it.required }
        val asOf = packet.asOfEpochMs
        val packetHash = packet.packetHash?.takeIf { it.isNotBlank() }

        if (packet.seal == SealState.WITHDRAWN) {
            return SealEvaluation(
                state = SealState.WITHDRAWN,
                tier = tier,
                asOfEpochMs = if (asOf > 0) asOf else nowEpochMs,
                gates = packet.gates,
                reasons = listOf(
                    "This seal was withdrawn — the evidence no longer meets the standard. " +
                        "Re-sealing requires a new packet built from fresh records.",
                ),
                packetHash = packetHash,
            )
        }

        val reasons = ArrayList<String>(8)
        if (packet.records.isEmpty()) {
            reasons += "The packet has no records — there is nothing to seal."
        }
        for (gate in packet.blockedGates) {
            reasons += "Gate '${gate.label}' is blocking: ${gate.detail}"
        }
        if (packet.consent == null) {
            reasons += "No consent record; a named person must authorize this capture before it can be sealed."
        }
        if (tier.level < EvidenceTier.VERIFIED_BY_AUCTUM.level) {
            reasons += "Evidence tier ${tier.level} (${tier.label}) — ${tier.meaning} " +
                "Sealing requires tier ${EvidenceTier.VERIFIED_BY_AUCTUM.level} (${EvidenceTier.VERIFIED_BY_AUCTUM.label})."
        }
        for (badge in required.filter { it.state != BadgeState.VERIFIED }) {
            reasons += "Required badge '${badge.key.label}' is ${badge.state.label.lowercase(Locale.US)}: ${badge.rationale}"
        }
        val flagged = badges.filter { it.state == BadgeState.FLAGGED }
        if (flagged.isNotEmpty() && flagged.none { it.required }) {
            reasons += "Badge '${flagged.first().key.label}' is flagged: ${flagged.first().rationale}"
        }
        if (!chainValid) {
            reasons += "The record chain does not verify; a record was altered, removed, or is out of order."
        }
        if (!merkleIncluded) {
            reasons += "The packet is not included in the evidence log yet (no verifiable Merkle inclusion proof)."
        }
        if (asOf <= 0) {
            reasons += "No as-of date; a sealed packet must state the instant its evidence describes."
        }

        val state = when {
            reasons.isEmpty() -> SealState.EARNED
            flagged.isNotEmpty() -> SealState.FLAGGED
            else -> SealState.PENDING
        }

        return SealEvaluation(
            state = state,
            tier = tier,
            asOfEpochMs = if (asOf > 0) asOf else nowEpochMs,
            gates = packet.gates + sealGates(packet, tier, required, chainValid, merkleIncluded, asOf),
            reasons = reasons,
            packetHash = packetHash,
        )
    }

    /** The seal preconditions as explicit, reviewable gates — the UI shows these, not prose. */
    private fun sealGates(
        packet: EvidencePacket,
        tier: EvidenceTier,
        required: List<Badge>,
        chainValid: Boolean,
        merkleIncluded: Boolean,
        asOf: Long,
    ): List<GateCheck> = listOf(
        GateCheck(
            id = "seal.tier",
            label = "Evidence tier",
            passed = tier.level >= EvidenceTier.VERIFIED_BY_AUCTUM.level,
            detail = "Tier ${tier.level} — ${tier.label}",
            blocking = true,
        ),
        GateCheck(
            id = "seal.badges",
            label = "Required badges",
            passed = required.isNotEmpty() && required.all { it.state == BadgeState.VERIFIED },
            detail = "${required.count { it.state == BadgeState.VERIFIED }} of ${required.size} verified",
            blocking = true,
        ),
        GateCheck(
            id = "seal.consent",
            label = "Consent on file",
            passed = packet.consent != null,
            detail = packet.consent?.let { "Authorized by the ${it.role}" } ?: "No consent record",
            blocking = true,
        ),
        GateCheck(
            id = "seal.chain",
            label = "Chain verifies",
            passed = chainValid,
            detail = if (chainValid) "Records hash from genesis" else "Chain is broken",
            blocking = true,
        ),
        GateCheck(
            id = "seal.log",
            label = "Log inclusion",
            passed = merkleIncluded,
            detail = if (merkleIncluded) "Merkle proof verified" else "Not included in the log",
            blocking = true,
        ),
        GateCheck(
            id = "seal.asof",
            label = "As-of date",
            passed = asOf > 0,
            detail = if (asOf > 0) "As of $asOf" else "Missing",
            blocking = true,
        ),
    )
}

object LedgerEngine {
    private fun counts(status: LedgerStatus): Boolean =
        status == LedgerStatus.PENDING || status == LedgerStatus.AVAILABLE || status == LedgerStatus.PAID

    private fun earningKind(kind: LedgerKind): Boolean = when (kind) {
        LedgerKind.EARNING, LedgerKind.BONUS, LedgerKind.TIP, LedgerKind.ADJUSTMENT -> true
        LedgerKind.INSTANT_FEE, LedgerKind.REVERSAL -> false
    }

    /**
     * Balances from the operator's ledger entries.
     *
     * Money is never recomputed from jobs here: the ledger is the single source of
     * truth. "Available" is everything the worker has earned and can request now,
     * "pending" is earned but not yet released by the requester, and "paid lifetime"
     * is gross earnings already paid out. Instant-payout fees are reported separately
     * so the worker can see exactly what speed cost — never buried in a net number.
     *
     * Demo entries are included when they are handed in; the caller scopes the list to
     * the operator and the sample book, and the sync layer excludes demo money from
     * any public anchor.
     */
    fun balances(entries: List<LedgerEntry>, operator: Operator): Balances {
        var available = 0L
        var pending = 0L
        var paidGross = 0L
        var instantFeesPaid = 0L
        for (entry in entries) {
            when (entry.status) {
                LedgerStatus.AVAILABLE -> available += entry.amountCents
                LedgerStatus.PENDING -> pending += entry.amountCents
                LedgerStatus.PAID -> {
                    if (entry.kind == LedgerKind.INSTANT_FEE) {
                        instantFeesPaid += -entry.amountCents.toLong()
                    } else {
                        paidGross += entry.amountCents
                    }
                }
                LedgerStatus.REVERSED, LedgerStatus.FAILED -> Unit
            }
        }
        return Balances(
            availableCents = intCents(available),
            pendingCents = intCents(pending),
            paidLifetimeCents = intCents(paidGross),
            instantFeePaidLifetimeCents = intCents(instantFeesPaid.coerceAtLeast(0L)),
            currentStreakDays = operator.streakDays,
            weeklyGoalCents = operator.weeklyGoalCents,
        )
    }

    /**
     * Earnings summary for the Payouts screen.
     *
     * Day/week/month windows are computed in UTC by integer arithmetic — no
     * `java.time`, no device timezone, no locale. That keeps the number reproducible in
     * tests and identical for a worker travelling across a timezone boundary; the UI is
     * responsible for the local-time label. Mileage is always an estimate and the
     * platform fee is disclosed, never charged to the worker.
     */
    fun summary(
        entries: List<LedgerEntry>,
        nowEpochMs: Long,
        onlineMinutesToday: Int,
        milesDrivenWeek: Double,
        schedule: FeeSchedule,
    ): EarningsSummary {
        val dayStart = utcDayStart(nowEpochMs)
        val weekStart = utcWeekStart(nowEpochMs)
        val monthStart = utcMonthStart(nowEpochMs)

        var today = 0L
        var week = 0L
        var month = 0L
        var bonusesWeek = 0L
        var tipsWeek = 0L
        var earningWeek = 0L
        val completedJobs = HashSet<String>()

        for (entry in entries) {
            if (!counts(entry.status) || !earningKind(entry.kind)) continue
            val at = entry.atEpochMs
            if (at < monthStart) continue
            month += entry.amountCents
            if (at >= weekStart) {
                week += entry.amountCents
                when (entry.kind) {
                    LedgerKind.BONUS -> bonusesWeek += entry.amountCents
                    LedgerKind.TIP -> tipsWeek += entry.amountCents
                    else -> Unit
                }
                if (entry.kind == LedgerKind.EARNING) {
                    earningWeek += entry.amountCents
                    entry.jobId?.let { completedJobs += it }
                }
            }
            if (at >= dayStart) {
                today += entry.amountCents
            }
        }

        val feeBase = intCents(earningWeek).coerceAtLeast(0)
        return EarningsSummary(
            todayCents = intCents(today),
            weekCents = intCents(week),
            monthCents = intCents(month),
            onlineMinutesToday = onlineMinutesToday.coerceAtLeast(0),
            jobsCompletedWeek = completedJobs.size,
            milesDrivenWeek = milesDrivenWeek.coerceAtLeast(0.0),
            bonusesWeekCents = intCents(bonusesWeek),
            tipsWeekCents = intCents(tipsWeek),
            platformFeeWeekCents = FeeEngine.platformFeeCents(feeBase, schedule),
            mileageDeductionCents = FeeEngine.mileageDeductionCents(milesDrivenWeek, schedule),
        )
    }

    /** UTC midnight of the calendar day containing [nowEpochMs]. */
    internal fun utcDayStart(nowEpochMs: Long): Long = UtcTime.dayStart(nowEpochMs)

    /** UTC Monday 00:00 of the ISO week containing [nowEpochMs]. */
    internal fun utcWeekStart(nowEpochMs: Long): Long = UtcTime.weekStart(nowEpochMs)

    /** UTC first-of-month for the calendar month containing [nowEpochMs]. */
    internal fun utcMonthStart(nowEpochMs: Long): Long = UtcTime.monthStart(nowEpochMs)

    /** Money is integer cents; a summary that cannot fit in Int is a data bug, not a rounding rule. */
    private fun intCents(value: Long): Int {
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "Ledger sum $value cents exceeds the integer-cent range."
        }
        return value.toInt()
    }
}

object FeeEngine {
    private const val INSTANT_FEE_PERCENT_LABEL = "1.5%"

    /**
     * Requester-paid platform fee, in integer cents, rounded half-up once.
     *
     * The fee is paid by the *requester*, not the worker: the worker's payout is the
     * bounty in full. That is why every money surface discloses this fee next to the
     * amount instead of subtracting it — a worker must never be surprised by a deduction
     * that the business model says does not exist. Basis points (15%) and not a float,
     * so two devices cannot disagree by a cent.
     */
    fun platformFeeCents(bountyCents: Int, schedule: FeeSchedule): Int =
        if (bountyCents <= 0) 0 else applyBasisPoints(bountyCents, schedule.platformFeeBp)

    /**
     * Worker-paid instant-payout fee: [FeeSchedule.instantPayoutFeeBp] of the amount,
     * clamped to the disclosed minimum and maximum, and never larger than the payout
     * itself (a fee cannot make a requested payout negative).
     */
    fun instantPayoutFeeCents(amountCents: Int, schedule: FeeSchedule): Int {
        if (amountCents <= 0) return 0
        val raw = applyBasisPoints(amountCents, schedule.instantPayoutFeeBp)
        return raw
            .coerceIn(schedule.instantPayoutMinFeeCents, schedule.instantPayoutMaxFeeCents)
            .coerceAtMost(amountCents)
    }

    /**
     * The payout quote shown *before* a payout is requested, with the fee disclosure
     * attached. A quote is an explanation as much as a number: instant speed costs a
     * capped 1.5%, standard ACH is free and slower. Nothing here is hidden behind a tap.
     */
    fun quote(amountCents: Int, mode: PayoutMode, schedule: FeeSchedule): PayoutQuote {
        val amount = amountCents.coerceAtLeast(0)
        val fee = if (mode == PayoutMode.INSTANT) instantPayoutFeeCents(amount, schedule) else 0
        val net = amount - fee
        val eta = when (mode) {
            PayoutMode.INSTANT -> "Usually under 30 minutes"
            PayoutMode.STANDARD -> "1–2 business days"
        }
        val disclosure = when (mode) {
            PayoutMode.INSTANT ->
                "Instant payout fee $INSTANT_FEE_PERCENT_LABEL " +
                    "(min ${Money.format(schedule.instantPayoutMinFeeCents)}, " +
                    "max ${Money.format(schedule.instantPayoutMaxFeeCents)}): " +
                    "${Money.format(fee)}. You receive ${Money.format(net)} now."
            PayoutMode.STANDARD ->
                "Standard ACH is free. ${Money.format(net)} arrives in 1–2 business days."
        }
        return PayoutQuote(
            amountCents = amount,
            feeCents = fee,
            netCents = net,
            mode = mode,
            etaLabel = eta,
            feeDisclosure = disclosure,
        )
    }

    /**
     * Mileage deduction *estimate* at the configured IRS-style rate. Always labelled an
     * estimate by the UI: the worker claims it on their own return, Fieldwright never
     * files it and never deducts it from a payout. Miles are rounded half-up once.
     */
    fun mileageDeductionCents(miles: Double, schedule: FeeSchedule): Int {
        if (miles <= 0.0) return 0
        return kotlin.math.round(miles * schedule.mileageRateCentsPerMile).toInt()
    }
}

/** Legal status transitions. The UI asks; it never assumes. */
object JobLifecycle {
    private val transitions: Map<JobStatus, Set<JobStatus>> = mapOf(
        JobStatus.SCHEDULED to setOf(JobStatus.AVAILABLE, JobStatus.CANCELLED, JobStatus.EXPIRED),
        JobStatus.AVAILABLE to setOf(JobStatus.OFFERED, JobStatus.CANCELLED),
        JobStatus.OFFERED to setOf(JobStatus.ACCEPTED, JobStatus.AVAILABLE, JobStatus.EXPIRED),
        JobStatus.ACCEPTED to setOf(JobStatus.EN_ROUTE, JobStatus.CANCELLED),
        JobStatus.EN_ROUTE to setOf(JobStatus.ON_SITE, JobStatus.CANCELLED),
        JobStatus.ON_SITE to setOf(JobStatus.IN_PROGRESS),
        JobStatus.IN_PROGRESS to setOf(JobStatus.PACKET_READY),
        JobStatus.PACKET_READY to setOf(JobStatus.SEALED, JobStatus.IN_PROGRESS),
        JobStatus.SEALED to setOf(JobStatus.SUBMITTED, JobStatus.IN_PROGRESS),
        JobStatus.SUBMITTED to setOf(JobStatus.UNDER_REVIEW, JobStatus.DISPUTED),
        JobStatus.UNDER_REVIEW to setOf(JobStatus.APPROVED, JobStatus.DISPUTED),
        JobStatus.APPROVED to setOf(JobStatus.PAID),
        JobStatus.DISPUTED to setOf(JobStatus.UNDER_REVIEW, JobStatus.CANCELLED),
    )

    fun canTransition(from: JobStatus, to: JobStatus): Boolean =
        transitions[from]?.contains(to) == true

    fun allowedFrom(from: JobStatus): Set<JobStatus> = transitions[from].orEmpty()
}

object CanonicalJson {
    private const val HEX = "0123456789abcdef"

    /**
     * A JSON-typed canonical value.
     *
     * A flat `Map<String,String>` is not enough for the evidence pre-image: `seq` must be
     * an unquoted JSON number and `payload` a nested object. The typed form keeps the
     * encoder honest instead of string-concatenating JSON by hand.
     */
    sealed interface Value {
        data class Text(val value: String) : Value
        data class Integer(val value: Long) : Value
        data class Object(val fields: Map<String, Value>) : Value
    }

    /**
     * Deterministic serialisation for hashing: sorted keys, escaped values, no
     * whitespace, no locale dependence. Two devices must produce identical bytes.
     *
     * Keys sort by UTF-16 code unit — a total, implementation-independent order. Values
     * are always strings, so there is no number-formatting fork.
     */
    fun encode(fields: Map<String, String>): String = encodeTyped(
        fields.mapValues { (_, value) -> Value.Text(value) },
    )

    /**
     * Typed variant used by the record pre-image. Objects sort their keys recursively,
     * integers render as bare JSON numbers, strings use [escape].
     */
    fun encodeTyped(fields: Map<String, Value>): String {
        if (fields.isEmpty()) return "{}"
        val sorted = fields.entries.sortedBy { it.key }
        val builder = StringBuilder(2 + sorted.size * 24)
        builder.append('{')
        for (index in sorted.indices) {
            if (index > 0) builder.append(',')
            val (key, value) = sorted[index]
            builder.append('"').append(escape(key)).append("\":").append(encodeValue(value))
        }
        builder.append('}')
        return builder.toString()
    }

    /** Encode one typed value; objects are emitted by [encodeTyped]. */
    fun encodeValue(value: Value): String = when (value) {
        is Value.Text -> "\"" + escape(value.value) + "\""
        is Value.Integer -> value.value.toString()
        is Value.Object -> encodeTyped(value.fields)
    }

    /**
     * JSON string escaping per RFC 8259: quote, backslash and every C0 control character.
     * Non-ASCII is kept as raw UTF-8 (a canonical encoder must not depend on a locale or
     * on a Unicode normalisation table); every device writes the same UTF-8 bytes.
     * The result does **not** include the surrounding quotes — [encode] adds them.
     *
     * This matches `JSON.stringify` for the characters our payloads can contain: the
     * server's canonical encoder is JavaScript's, so `\b`, `\f`, `\n`, `\r`, `\t` and
     * lowercase `\u00xx` for the remaining C0 controls are the bytes it produces.
     */
    fun escape(value: String): String {
        var needsEscaping = false
        for (ch in value) {
            if (ch == '"' || ch == '\\' || ch < ' ') {
                needsEscaping = true
                break
            }
        }
        if (!needsEscaping) return value

        val builder = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') {
                    builder.append("\\u00")
                    builder.append(HEX[ch.code shr 4])
                    builder.append(HEX[ch.code and 0x0F])
                } else {
                    builder.append(ch)
                }
            }
        }
        return builder.toString()
    }
}

/**
 * Builds packets by appending records. A record is never mutated after it is appended:
 * sealing computes the final hash over the whole record list.
 *
 * Sequence numbers are **1-based** and the canonical pre-image is the sync server's
 * (see [ChainRule]), so an assembled record can be uploaded without re-hashing or
 * renumbering: `records[0].seq == 1`, `records.last().hash == packetHash`.
 *
 * A **draft** is an intention, not evidence. Its opening record carries the capture
 * standard, the consent scope and the checklist obligations, but its hash is left blank
 * ([DRAFT_HASH]) because [draft] has no hasher: a packet starts life on a device that
 * has not yet committed anything. The first [appendRecord] or [seal] *materialises* the
 * draft record — hashes it, links it to genesis and signs it — so a draft that is never
 * used as evidence cannot be mistaken for a verified chain.
 */
object PacketAssembler {
    const val GENESIS_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"

    /** Sentinel hash on an unmaterialised draft record. Never valid in a verified chain. */
    const val DRAFT_HASH: String = ""

    /** Version string stamped into the seal record so old packets can be re-verified forever. */
    const val SEAL_STANDARD: String = "auctum-evidence-v1"

    private const val TRUE = "true"

    /**
     * Start a packet for [job]. The draft carries the job's capture standard, consent
     * scope and required checklist steps as the opening (genesis-anchored) custody
     * record, plus the badge and tier states the evidence currently supports. It is not
     * sealable until records are appended and the seal preconditions are evaluated.
     */
    fun draft(job: Job, operator: Operator, nowEpochMs: Long): EvidencePacket {
        val checklist = job.checklist.map { step ->
            ChecklistResult(key = step.key, result = StepResult.PENDING, note = "", mediaIds = emptyList())
        }
        val payload = linkedMapOf(
            "jobId" to job.id,
            "jobType" to job.type.code,
            "requester" to job.requester.org,
            "site" to job.site.address,
            "captureStandard" to job.type.captureStandard,
            "consentScope" to job.consentScope,
            "checklist" to job.checklist.joinToString(",") { step ->
                step.key + if (step.required) "*" else ""
            },
            "openedAt" to nowEpochMs.toString(),
            "demo" to job.demo.toString(),
        )
        val seed = ChainRecord(
            seq = 1,
            kind = RecordKind.CUSTODY,
            atEpochMs = nowEpochMs,
            actorId = operator.id,
            summary = "Packet opened — ${job.type.label} at ${job.site.address}",
            payload = payload,
            prevHash = GENESIS_HASH,
            hash = DRAFT_HASH,
            signatureBase64 = null,
            signerKeyId = null,
        )
        var packet = EvidencePacket(
            id = draftPacketId(job),
            jobId = job.id,
            jobType = job.type,
            operatorId = operator.id,
            title = job.title.ifBlank { JobCatalog.subjectFor(job.type, job.site.planLabel) },
            requesterLabel = job.requester.org,
            site = job.site,
            consent = null,
            badges = emptyList(),
            tier = EvidenceTier.REPORTED,
            seal = SealState.PENDING,
            records = listOf(seed),
            media = emptyList(),
            checklist = checklist,
            gates = emptyList(),
            measurements = emptyList(),
            notes = job.notes,
            demo = job.demo,
            createdAtEpochMs = nowEpochMs,
            sealedAtEpochMs = null,
            merkleLeaf = null,
            merkleProof = emptyList(),
            asOfEpochMs = 0,
            signatureBase64 = null,
        )
        packet = packet.copy(badges = TierEngine.badgesFor(packet), tier = TierEngine.derive(packet))
        return packet
    }

    /**
     * Append one record to the chain, materialising any draft record first.
     *
     * The new record is signed over its own hash (which commits to `prevHash`, and
     * therefore to every earlier record). A `null` signature from the signer is
     * tolerated — an offline device may hold a verify-only key — but the record's
     * `signerKeyId` is then left null so the omission is visible instead of pretended.
     */
    fun appendRecord(
        packet: EvidencePacket,
        kind: RecordKind,
        actorId: String,
        summary: String,
        payload: Map<String, String>,
        atEpochMs: Long,
        hasher: HashAlgorithm,
        signer: SignatureScheme,
    ): EvidencePacket {
        val materialised = materialise(packet, hasher, signer)
        val appended = appendSignedRecord(
            packet = materialised,
            kind = kind,
            actorId = actorId,
            summary = summary,
            payload = payload,
            atEpochMs = atEpochMs,
            hasher = hasher,
            signer = signer,
        )
        // Badges and tier are derived caches; refresh them so the UI never shows a stale claim.
        return appended.copy(
            badges = TierEngine.badgesFor(appended),
            tier = TierEngine.derive(appended),
        )
    }

    /**
     * Materialise the chain and append the final seal record, then stamp the packet with
     * its seal state, as-of date, Merkle leaf and packet signature.
     *
     * The seal is earned only when the local evidence is complete: no blocking gate, all
     * required badges verified, no flags, and the recomputed chain intact. Full
     * preconditions (log inclusion, tier, as-of) are surfaced by [SealEngine.evaluate];
     * this function refuses to mark a packet EARNED that the data layer would then have to
     * un-seal. A packet that cannot be sealed is returned PENDING with its records intact —
     * append-only means the attempt is recorded, not erased.
     */
    fun seal(
        packet: EvidencePacket,
        gates: List<GateCheck>,
        hasher: HashAlgorithm,
        signer: SignatureScheme,
        nowEpochMs: Long,
    ): EvidencePacket {
        val withGates = packet.copy(gates = gates)
        val materialised = materialise(withGates, hasher, signer)
        val blocked = gates.filter { it.blocking && !it.passed }
        val badges = TierEngine.badgesFor(materialised)
        val required = badges.filter { it.required }
        val evidenceComplete = required.isNotEmpty() && required.all { it.state == BadgeState.VERIFIED } &&
            badges.none { it.state == BadgeState.FLAGGED }
        val chainIntact = recomputeChainValid(materialised.records, hasher)
        val asOf = if (materialised.asOfEpochMs > 0) materialised.asOfEpochMs else nowEpochMs
        val canEarn = blocked.isEmpty() && evidenceComplete && chainIntact

        val sealPayload = linkedMapOf(
            "standard" to SEAL_STANDARD,
            "recordCount" to (materialised.records.size + 1).toString(),
            "tier" to TierEngine.derive(materialised).level.toString(),
            "gatesPassed" to gates.count { it.passed }.toString(),
            "gatesBlocked" to blocked.size.toString(),
            "asOf" to asOf.toString(),
            "evidenceComplete" to evidenceComplete.toString(),
            "chainIntact" to chainIntact.toString(),
            "canEarnSeal" to canEarn.toString(),
            "demo" to materialised.demo.toString(),
        )
        val sealedRecords = appendSignedRecord(
            packet = materialised,
            kind = RecordKind.CUSTODY,
            actorId = materialised.operatorId,
            summary = if (canEarn) {
                "Packet sealed — ${materialised.records.size + 1} records, ${SEAL_STANDARD}"
            } else {
                "Seal attempted — evidence incomplete; packet remains pending"
            },
            payload = sealPayload,
            atEpochMs = nowEpochMs,
            hasher = hasher,
            signer = signer,
        )
        val headHash = sealedRecords.records.last().hash
        val signature = signer.sign(headHash)
        return sealedRecords.copy(
            badges = badges,
            tier = TierEngine.derive(sealedRecords),
            gates = gates,
            seal = if (canEarn) SealState.EARNED else SealState.PENDING,
            sealedAtEpochMs = if (canEarn) nowEpochMs else null,
            asOfEpochMs = asOf,
            // A single-value Merkle leaf keeps the packet verifiable before it is anchored
            // to the shared log: root == leaf for a one-leaf RFC 6962 tree.
            merkleLeaf = MerkleHash.leafHash(headHash, hasher),
            merkleProof = emptyList(),
            signatureBase64 = signature,
        )
    }

    /**
     * Materialise draft records: recompute the hash of any record whose hash is
     * [DRAFT_HASH], link it to the previous record, and sign it. Records that already
     * carry a hash are left untouched — evidence is append-only and materialisation must
     * never rewrite something that was already committed.
     */
    private fun materialise(
        packet: EvidencePacket,
        hasher: HashAlgorithm,
        signer: SignatureScheme,
    ): EvidencePacket {
        if (packet.records.none { it.hash.isBlank() }) return packet
        var prevHash = GENESIS_HASH
        val rebuilt = ArrayList<ChainRecord>(packet.records.size)
        for (record in packet.records) {
            if (record.hash.isNotBlank()) {
                prevHash = record.hash
                rebuilt += record
            } else {
                val unhashed = record.copy(prevHash = prevHash, hash = DRAFT_HASH, signatureBase64 = null, signerKeyId = null)
                val hash = unhashed.computeHash(hasher)
                val signature = signer.sign(hash)
                val signed = unhashed.copy(
                    hash = hash,
                    signatureBase64 = signature,
                    signerKeyId = if (signature != null) signer.keyId else null,
                )
                rebuilt += signed
                prevHash = hash
            }
        }
        return packet.copy(records = rebuilt)
    }

    private fun appendSignedRecord(
        packet: EvidencePacket,
        kind: RecordKind,
        actorId: String,
        summary: String,
        payload: Map<String, String>,
        atEpochMs: Long,
        hasher: HashAlgorithm,
        signer: SignatureScheme,
    ): EvidencePacket {
        val prevHash = packet.records.lastOrNull()?.hash ?: GENESIS_HASH
        val unsigned = ChainRecord(
            seq = packet.records.size + 1,
            kind = kind,
            atEpochMs = atEpochMs,
            actorId = actorId,
            summary = summary,
            payload = payload,
            prevHash = prevHash,
            hash = DRAFT_HASH,
            signatureBase64 = null,
            signerKeyId = null,
        )
        val hash = unsigned.computeHash(hasher)
        val signature = signer.sign(hash)
        val signed = unsigned.copy(
            hash = hash,
            signatureBase64 = signature,
            signerKeyId = if (signature != null) signer.keyId else null,
        )
        return packet.copy(records = packet.records + signed)
    }

    /** Local integrity check used before marking a packet sealed. */
    internal fun recomputeChainValid(records: List<ChainRecord>, hasher: HashAlgorithm): Boolean {
        var prevHash = GENESIS_HASH
        for ((index, record) in records.withIndex()) {
            // Wire-compatible 1-based sequence: the sync server expects records[0].seq == 1.
            if (record.seq != index + 1) return false
            if (record.prevHash != prevHash) return false
            if (record.hash.isBlank()) return false
            if (record.hash != record.computeHash(hasher)) return false
            prevHash = record.hash
        }
        return records.isNotEmpty()
    }

    /**
     * Human packet id: deterministic for a job, so a retried draft cannot create a second
     * packet for the same work. Display uses `FW-####` in the sample-book idiom.
     */
    private fun draftPacketId(job: Job): String {
        var hash = 0x811C9DC5.toInt()
        for (ch in job.id) {
            hash = hash xor ch.code
            hash *= 16_777_619
        }
        val digits = (hash.toLong() and 0x7FFF_FFFFL) % 9_000L + 1_000L
        return "FW-$digits"
    }
}

/** Deterministic idempotency keys: same logical operation, same key, across retries. */
object Idempotency {
    private const val VERSION = "fw1"

    /**
     * Build an idempotency key from the parts that identify a logical operation.
     *
     * The key must be *stable* across retries (so a repeated request is recognised) and
     * *injective* in its parts (so `("seal","a|b")` and `("seal|a","b")` cannot collide
     * into one key and swallow a second, different operation). Parts are backslash-escaped
     * before the `|` join and the key is version-prefixed so the encoding can evolve
     * without reusing old keys.
     */
    fun key(vararg parts: String): String {
        require(parts.isNotEmpty()) { "An idempotency key needs at least one identifying part." }
        val builder = StringBuilder(VERSION).append(':')
        for (index in parts.indices) {
            if (index > 0) builder.append('|')
            appendEscaped(builder, parts[index])
        }
        return builder.toString()
    }

    private fun appendEscaped(builder: StringBuilder, part: String) {
        for (ch in part) {
            when (ch) {
                '\\' -> builder.append("\\\\")
                '|' -> builder.append("\\|")
                else -> builder.append(ch)
            }
        }
    }
}
