package com.odasi.fieldwright.model

import java.security.MessageDigest

/**
 * Test-only hasher. The production one lives in `:core:crypto` (and `:core:model` must not
 * depend on it), but the model tests still need a *real* SHA-256 so that chained-hash and
 * determinism assertions mean something.
 */
internal class TestHasher : HashAlgorithm {
    override fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digits = "0123456789abcdef"
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return out.toString()
    }
}

/** Deterministic stand-in for the ECDSA signer: easy to tamper with in a test. */
internal class TestSigner(
    override val keyId: String = "test-key-1",
    private val canSign: Boolean = true,
) : SignatureScheme {
    override fun sign(canonical: String): String? =
        if (canSign) "sig:$keyId:$canonical" else null

    override fun verify(canonical: String, signatureBase64: String): Boolean =
        signatureBase64 == "sig:$keyId:$canonical"
}

/**
 * Fixed instants so day/week/month windows and chains are reproducible.
 * NOW = 2026-01-15T12:00:00Z (a Thursday); week starts Monday 2026-01-12; month 2026-01-01.
 */
internal const val NOW: Long = 1_768_478_400_000L
internal const val JAN_15_START: Long = 1_768_435_200_000L
internal const val JAN_12_START: Long = 1_768_176_000_000L
internal const val JAN_01_START: Long = 1_767_225_600_000L

internal object Fixtures {
    val hasher = TestHasher()
    val signer = TestSigner()
    const val OPERATOR_ID = "op-1"

    val operatorPoint = GeoPoint(lat = 30.2672, lng = -97.7431, recordedAtEpochMs = NOW, accuracyMeters = 4.0f)

    fun pointOffsetMeters(meters: Double): GeoPoint =
        GeoPoint(
            lat = operatorPoint.lat + meters / 111_320.0,
            lng = operatorPoint.lng,
            recordedAtEpochMs = NOW,
            accuracyMeters = 4.0f,
        )

    fun site(point: GeoPoint = operatorPoint, address: String = "14 Creekside Row"): SiteRef =
        SiteRef(
            address = address,
            unit = null,
            point = point,
            geofenceRadiusMeters = 40,
            accessNotes = "Gate code 4412; assembly is in the east vault.",
            planLabel = "T-14",
        )

    fun operator(
        id: String = OPERATOR_ID,
        credentials: Set<Credential> = Credential.entries.toSet(),
        credentialTier: Int = 3,
        onTimeRate: Double = 0.92,
        acceptanceRate: Double = 0.88,
        streakDays: Int = 12,
        weeklyGoalCents: Int = 60_000,
    ): Operator = Operator(
        id = id,
        displayName = "Mara Voss",
        initials = "MV",
        credentialTier = credentialTier,
        ratingAvg = 4.86,
        acceptanceRate = acceptanceRate,
        onTimeRate = onTimeRate,
        completedJobs = 214,
        streakDays = streakDays,
        credentials = credentials,
        payoutAccountLast4 = "4412",
        weeklyGoalCents = weeklyGoalCents,
        memberSinceLabel = "Member since 2023",
    )

    fun job(
        id: String = "job-1",
        type: JobType = JobType.RPZ_TEST,
        bountyCents: Int = type.baseBountyCents,
        bonusCents: Int = 0,
        distanceMeters: Int = 3_200,
        status: JobStatus = JobStatus.AVAILABLE,
        scheduledForEpochMs: Long? = null,
        demo: Boolean = false,
        point: GeoPoint = pointOffsetMeters(distanceMeters.toDouble()),
    ): Job = Job(
        id = id,
        type = type,
        title = JobCatalog.subjectFor(type, "T-14"),
        requester = Requester(
            org = "Creekside MUD 14",
            contactName = "Dana Reyes",
            kind = RequesterKind.UTILITY,
            billingLabel = "MUD 14 · annual contract",
        ),
        site = site(point),
        status = status,
        bountyCents = bountyCents,
        bonusCents = bonusCents,
        estimatedMinutes = type.estimatedMinutes,
        scheduledForEpochMs = scheduledForEpochMs,
        slaMinutes = 120,
        distanceMeters = distanceMeters,
        etaMinutes = GeoMath.etaMinutes(distanceMeters.toDouble()),
        consentScope = type.consentScope,
        checklist = JobCatalog.checklistFor(type),
        requiredCredentials = type.requiredCredentials,
        demo = demo,
        notes = "Gauge was replaced last spring.",
    )

    fun availability(
        isOnline: Boolean = true,
        radiusMeters: Int = 15_000,
        autoAccept: AutoAcceptPolicy = AutoAcceptPolicy(),
    ): Availability = Availability(
        isOnline = isOnline,
        updatedAtEpochMs = NOW,
        radiusMeters = radiusMeters,
        autoAccept = autoAccept,
        areaLabel = "Creekside · MUD 14",
    )

    fun market(
        demandIndex: Double = 0.55,
        supplyIndex: Double = 0.45,
        hotZones: List<HotZone> = emptyList(),
    ): MarketSnapshot = MarketSnapshot(
        nowEpochMs = NOW,
        demandIndex = demandIndex,
        supplyIndex = supplyIndex,
        hotZones = hotZones,
        daylightLabel = "3 h 40 m daylight",
        weatherLabel = "Clear, 68 °F",
    )

    fun hotZone(surge: Double = 1.4, radiusMeters: Int = 3_000, point: GeoPoint = operatorPoint): HotZone =
        HotZone(
            label = "Hail belt",
            point = point,
            radiusMeters = radiusMeters,
            surgeMultiplier = surge,
            reason = "Two tracked storms inside the zone",
        )

    fun offer(
        job: Job = job(),
        payoutCents: Int = job.payoutCents,
        surgeMultiplier: Double = 1.0,
        autoAcceptEligible: Boolean = true,
        status: OfferStatus = OfferStatus.PENDING,
        offeredAtEpochMs: Long = NOW,
        windowMs: Long = DispatchEngine.OFFER_TTL_MS,
        operatorId: String = OPERATOR_ID,
    ): Offer = Offer(
        id = "offer-1",
        job = job,
        operatorId = operatorId,
        payoutCents = payoutCents,
        surgeMultiplier = surgeMultiplier,
        offeredAtEpochMs = offeredAtEpochMs,
        expiresAtEpochMs = offeredAtEpochMs + windowMs,
        score = 0.812,
        scoreReasons = listOf("close", "credential matched"),
        etaMinutes = job.etaMinutes,
        autoAcceptEligible = autoAcceptEligible,
        status = status,
    )

    fun consent(
        name: String = "Dana Reyes",
        role: String = "property manager",
        scope: String = JobType.ROOF_HAIL.consentScope,
        method: ConsentMethod = ConsentMethod.IN_APP_SIGNATURE,
        signatureHash: String? = "a".repeat(64),
    ): Consent = Consent(
        name = name,
        role = role,
        scopeText = scope,
        method = method,
        capturedAtEpochMs = NOW - 5_000,
        signatureHash = signatureHash,
        verifiedByOperatorId = OPERATOR_ID,
    )

    fun photo(redaction: RedactionState = RedactionState.NOT_NEEDED, id: String = "media-1"): MediaRef =
        MediaRef(
            id = id,
            kind = MediaKind.PHOTO,
            sha256 = "b".repeat(64),
            bytes = 240_000,
            capturedAtEpochMs = NOW - 4_000,
            width = 1920,
            height = 1080,
            redaction = redaction,
            source = MediaSource.SIMULATED,
            note = "South slope",
        )

    fun measurement(): Measurement = Measurement(
        id = "meas-1",
        label = "hail strike",
        meters = 0.032,
        referenceLabel = "site card 30 cm",
        referenceMeters = 0.30,
        pixelSpan = 210.0,
        calibratedAtEpochMs = NOW - 3_000,
        source = MediaSource.SIMULATED,
    )

    fun checklistResults(
        type: JobType = JobType.ROOF_HAIL,
        passed: Set<String> = setOf("footprint", "scope", "slopes", "square", "paddle"),
    ): List<ChecklistResult> = JobCatalog.checklistFor(type).map { step ->
        ChecklistResult(
            key = step.key,
            result = if (step.key in passed) StepResult.PASS else StepResult.PENDING,
            note = if (step.key in passed) "done" else "",
            mediaIds = emptyList(),
        )
    }

    data class RecordSpec(
        val kind: RecordKind,
        val actorId: String = OPERATOR_ID,
        val summary: String = "record",
        val payload: Map<String, String> = emptyMap(),
        val atEpochMs: Long = NOW - 10_000,
    )

    /** Build a correctly chained, correctly signed record list with the test hasher/signer. */
    fun chain(vararg specs: RecordSpec): List<ChainRecord> {
        var previous = PacketAssembler.GENESIS_HASH
        return specs.mapIndexed { index, spec ->
            val unsigned = ChainRecord(
                seq = index + 1,
                kind = spec.kind,
                atEpochMs = spec.atEpochMs + index,
                actorId = spec.actorId,
                summary = spec.summary,
                payload = spec.payload,
                prevHash = previous,
                hash = "",
                signatureBase64 = null,
                signerKeyId = null,
            )
            val hash = unsigned.computeHash(hasher)
            val signed = unsigned.copy(
                hash = hash,
                signatureBase64 = signer.sign(hash),
                signerKeyId = signer.keyId,
            )
            previous = hash
            signed
        }
    }

    /** A ROOF_HAIL packet whose evidence supports tier 5 by default. */
    fun packet(
        type: JobType = JobType.ROOF_HAIL,
        records: List<ChainRecord> = chain(
            RecordSpec(kind = RecordKind.CUSTODY, summary = "Packet opened"),
            RecordSpec(
                kind = RecordKind.CAPTURE,
                summary = "Four slopes captured",
                payload = mapOf("captureStandardMet" to "true", "mediaCount" to "4"),
            ),
        ),
        consent: Consent? = consent(),
        checklist: List<ChecklistResult> = checklistResults(type),
        media: List<MediaRef> = listOf(photo()),
        measurements: List<Measurement> = listOf(measurement()),
        gates: List<GateCheck> = listOf(
            GateCheck("consent", "Consent on file", true, "Signed in app", true),
            GateCheck("redaction", "Redaction resolved", true, "No faces or plates", true),
        ),
        asOfEpochMs: Long = NOW,
        seal: SealState = SealState.PENDING,
        badges: List<Badge> = emptyList(),
        demo: Boolean = false,
    ): EvidencePacket = EvidencePacket(
        id = "FW-1001",
        jobId = "job-1",
        jobType = type,
        operatorId = OPERATOR_ID,
        title = "Hail on the south slope · T-14",
        requesterLabel = "Creekside MUD 14",
        site = site(),
        consent = consent,
        badges = badges,
        tier = EvidenceTier.REPORTED,
        seal = seal,
        records = records,
        media = media,
        checklist = checklist,
        gates = gates,
        measurements = measurements,
        notes = "North slope is clear.",
        demo = demo,
        createdAtEpochMs = NOW - 60_000,
        sealedAtEpochMs = null,
        merkleLeaf = null,
        merkleProof = emptyList(),
        asOfEpochMs = asOfEpochMs,
        signatureBase64 = null,
    )
}
