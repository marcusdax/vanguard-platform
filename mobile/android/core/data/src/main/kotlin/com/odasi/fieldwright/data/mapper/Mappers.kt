package com.odasi.fieldwright.data.mapper

import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.data.db.AvailabilityEntity
import com.odasi.fieldwright.data.db.BadgeEntity
import com.odasi.fieldwright.data.db.ChainRecordEntity
import com.odasi.fieldwright.data.db.ChecklistRowEntity
import com.odasi.fieldwright.data.db.JobEntity
import com.odasi.fieldwright.data.db.LedgerEntity
import com.odasi.fieldwright.data.db.MarketEntity
import com.odasi.fieldwright.data.db.MeasurementEntity
import com.odasi.fieldwright.data.db.MediaEntity
import com.odasi.fieldwright.data.db.OfferEntity
import com.odasi.fieldwright.data.db.OfferWithJob
import com.odasi.fieldwright.data.db.OperatorEntity
import com.odasi.fieldwright.data.db.PacketAggregate
import com.odasi.fieldwright.data.db.PacketEntity
import com.odasi.fieldwright.data.db.PayoutEntity
import com.odasi.fieldwright.data.db.SyncStateEntity
import com.odasi.fieldwright.data.db.credentialsFromCsv
import com.odasi.fieldwright.data.db.jobTypesFromCsv
import com.odasi.fieldwright.data.db.toCsv
import com.odasi.fieldwright.data.db.toJobTypeCsv
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.AutoAcceptPolicy
import com.odasi.fieldwright.model.Badge
import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.OfferStatus
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.PayoutStatus
import com.odasi.fieldwright.model.Requester
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.SiteRef
import com.odasi.fieldwright.model.SyncPhase

/**
 * Entity ⇄ model mapping. This is the only place where Room row shape meets `core:model`;
 * repositories compose these mappers with DAO flows so the UI never sees a database row.
 * Maps are total: a missing optional value maps to the model's own null/empty default.
 */
object Mappers {

    // ------------------------------------------------------------------ geo / site

    private fun fixSource(raw: String): GeoPoint.FixSource =
        runCatching { GeoPoint.FixSource.valueOf(raw) }.getOrDefault(GeoPoint.FixSource.GPS)

    private fun sitePoint(
        lat: Double,
        lng: Double,
        recordedAt: Long,
        accuracy: Float,
        source: String,
    ): GeoPoint = GeoPoint(lat, lng, recordedAt, accuracy, fixSource(source))

    fun JobEntity.toModel(): Job = Job(
        id = id,
        type = type,
        title = title,
        requester = Requester(
            org = requesterOrg,
            contactName = requesterContactName,
            kind = requesterKind,
            billingLabel = requesterBillingLabel,
        ),
        site = SiteRef(
            address = siteAddress,
            unit = siteUnit,
            point = sitePoint(siteLat, siteLng, sitePointRecordedAtEpochMs, siteAccuracyMeters, siteFixSource),
            geofenceRadiusMeters = geofenceRadiusMeters,
            accessNotes = accessNotes,
            planLabel = planLabel,
        ),
        status = status,
        bountyCents = bountyCents,
        bonusCents = bonusCents,
        estimatedMinutes = estimatedMinutes,
        scheduledForEpochMs = scheduledForEpochMs,
        slaMinutes = slaMinutes,
        distanceMeters = distanceMeters,
        etaMinutes = etaMinutes,
        consentScope = consentScope,
        checklist = JsonCodec.decodeChecklist(checklistJson),
        requiredCredentials = credentialsFromCsv(requiredCredentialsCsv),
        demo = demo,
        notes = notes,
        acceptedAtEpochMs = acceptedAtEpochMs,
        arrivedAtEpochMs = arrivedAtEpochMs,
        sealedAtEpochMs = sealedAtEpochMs,
        packetId = packetId,
    )

    fun JobEntity.consent(): Consent? = JsonCodec.decodeConsent(consentJson)

    /**
     * Writes a [Job] back to its row, carrying over capture-session fields from [previous]
     * (which the model has no slot for: started time, arrival evidence, recorded consent).
     */
    fun Job.toEntity(
        updatedAtEpochMs: Long,
        previous: JobEntity? = null,
    ): JobEntity = JobEntity(
        id = id,
        type = type,
        title = title,
        requesterOrg = requester.org,
        requesterContactName = requester.contactName,
        requesterKind = requester.kind,
        requesterBillingLabel = requester.billingLabel,
        siteAddress = site.address,
        siteUnit = site.unit,
        siteLat = site.point.lat,
        siteLng = site.point.lng,
        sitePointRecordedAtEpochMs = site.point.recordedAtEpochMs,
        siteAccuracyMeters = site.point.accuracyMeters,
        siteFixSource = site.point.source.name,
        geofenceRadiusMeters = site.geofenceRadiusMeters,
        accessNotes = site.accessNotes,
        planLabel = site.planLabel,
        status = status,
        bountyCents = bountyCents,
        bonusCents = bonusCents,
        estimatedMinutes = estimatedMinutes,
        scheduledForEpochMs = scheduledForEpochMs,
        slaMinutes = slaMinutes,
        distanceMeters = distanceMeters,
        etaMinutes = etaMinutes,
        consentScope = consentScope,
        checklistJson = JsonCodec.encodeChecklist(checklist),
        requiredCredentialsCsv = requiredCredentials.toCsv(),
        demo = demo,
        notes = notes,
        acceptedAtEpochMs = acceptedAtEpochMs,
        arrivedAtEpochMs = arrivedAtEpochMs,
        startedAtEpochMs = previous?.startedAtEpochMs,
        sealedAtEpochMs = sealedAtEpochMs,
        packetId = packetId,
        consentJson = previous?.consentJson,
        arrivalDistanceMeters = previous?.arrivalDistanceMeters,
        arrivalAccuracyMeters = previous?.arrivalAccuracyMeters,
        arrivalOverrideReason = previous?.arrivalOverrideReason,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    // ------------------------------------------------------------------ offers

    fun OfferWithJob.toModel(): Offer? {
        val jobModel = job?.toModel() ?: return null
        return Offer(
            id = offer.id,
            job = jobModel,
            operatorId = offer.operatorId,
            payoutCents = offer.payoutCents,
            surgeMultiplier = offer.surgeMultiplier,
            offeredAtEpochMs = offer.offeredAtEpochMs,
            expiresAtEpochMs = offer.expiresAtEpochMs,
            score = offer.score,
            scoreReasons = JsonCodec.decodeStrings(offer.scoreReasonsJson),
            etaMinutes = offer.etaMinutes,
            autoAcceptEligible = offer.autoAcceptEligible,
            status = offer.status,
        )
    }

    fun Offer.toEntity(rankIndex: Int): OfferEntity = OfferEntity(
        id = id,
        jobId = job.id,
        operatorId = operatorId,
        payoutCents = payoutCents,
        surgeMultiplier = surgeMultiplier,
        offeredAtEpochMs = offeredAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        score = score,
        scoreReasonsJson = JsonCodec.encodeStrings(scoreReasons),
        etaMinutes = etaMinutes,
        autoAcceptEligible = autoAcceptEligible,
        status = status,
        demo = job.demo,
        rankIndex = rankIndex,
    )

    // ------------------------------------------------------------------ evidence

    fun ChainRecordEntity.toModel(): ChainRecord = ChainRecord(
        seq = seq,
        kind = kind,
        atEpochMs = atEpochMs,
        actorId = actorId,
        summary = summary,
        payload = JsonCodec.decodeMap(payloadJson),
        prevHash = prevHash,
        hash = hash,
        signatureBase64 = signatureBase64,
        signerKeyId = signerKeyId,
    )

    fun ChainRecord.toEntity(packetId: String): ChainRecordEntity = ChainRecordEntity(
        packetId = packetId,
        seq = seq,
        kind = kind,
        atEpochMs = atEpochMs,
        actorId = actorId,
        summary = summary,
        payloadJson = JsonCodec.encodeMap(payload),
        prevHash = prevHash,
        hash = hash,
        signatureBase64 = signatureBase64,
        signerKeyId = signerKeyId,
    )

    fun BadgeEntity.toModel(): Badge = Badge(
        key = badgeKey,
        state = state,
        required = required,
        rationale = rationale,
        evidenceRefs = JsonCodec.decodeStrings(evidenceRefsJson),
    )

    fun Badge.toEntity(packetId: String): BadgeEntity = BadgeEntity(
        packetId = packetId,
        badgeKey = key,
        state = state,
        required = required,
        rationale = rationale,
        evidenceRefsJson = JsonCodec.encodeStrings(evidenceRefs),
    )

    fun MediaEntity.toModel(): MediaRef = MediaRef(
        id = id,
        kind = kind,
        sha256 = sha256,
        bytes = bytes,
        capturedAtEpochMs = capturedAtEpochMs,
        width = width,
        height = height,
        redaction = redaction,
        source = source,
        note = note,
    )

    fun MediaRef.toEntity(jobId: String, packetId: String?): MediaEntity = MediaEntity(
        id = id,
        jobId = jobId,
        packetId = packetId,
        kind = kind,
        sha256 = sha256,
        bytes = bytes,
        capturedAtEpochMs = capturedAtEpochMs,
        width = width,
        height = height,
        redaction = redaction,
        source = source,
        note = note,
    )

    fun MeasurementEntity.toModel(): Measurement = Measurement(
        id = id,
        label = label,
        meters = meters,
        referenceLabel = referenceLabel,
        referenceMeters = referenceMeters,
        pixelSpan = pixelSpan,
        calibratedAtEpochMs = calibratedAtEpochMs,
        source = source,
    )

    fun Measurement.toEntity(jobId: String, packetId: String?): MeasurementEntity = MeasurementEntity(
        id = id,
        jobId = jobId,
        packetId = packetId,
        label = label,
        meters = meters,
        referenceLabel = referenceLabel,
        referenceMeters = referenceMeters,
        pixelSpan = pixelSpan,
        calibratedAtEpochMs = calibratedAtEpochMs,
        source = source,
    )

    fun ChecklistRowEntity.toModel(): ChecklistResult = ChecklistResult(
        key = stepKey,
        result = result,
        note = note,
        mediaIds = JsonCodec.decodeStrings(mediaIdsJson),
    )

    fun ChecklistResult.toEntity(
        jobId: String,
        packetId: String?,
        recordedAtEpochMs: Long,
    ): ChecklistRowEntity = ChecklistRowEntity(
        jobId = jobId,
        stepKey = key,
        result = result,
        note = note,
        mediaIdsJson = JsonCodec.encodeStrings(mediaIds),
        packetId = packetId,
        recordedAtEpochMs = recordedAtEpochMs,
    )

    fun PacketAggregate.toModel(): EvidencePacket = EvidencePacket(
        id = packet.id,
        jobId = packet.jobId,
        jobType = packet.jobType,
        operatorId = packet.operatorId,
        title = packet.title,
        requesterLabel = packet.requesterLabel,
        site = SiteRef(
            address = packet.siteAddress,
            unit = packet.siteUnit,
            point = sitePoint(
                packet.siteLat,
                packet.siteLng,
                packet.sitePointRecordedAtEpochMs,
                packet.siteAccuracyMeters,
                packet.siteFixSource,
            ),
            geofenceRadiusMeters = packet.geofenceRadiusMeters,
            accessNotes = packet.accessNotes,
            planLabel = packet.planLabel,
        ),
        consent = JsonCodec.decodeConsent(packet.consentJson),
        badges = badges.map { it.toModel() }.sortedBy { it.key.name },
        tier = packet.tier,
        seal = packet.seal,
        records = records.sortedBy { it.seq }.map { it.toModel() },
        media = media.sortedBy { it.capturedAtEpochMs }.map { it.toModel() },
        checklist = checklist.sortedBy { it.stepKey }.map { it.toModel() },
        gates = JsonCodec.decodeGates(packet.gatesJson),
        measurements = measurements.sortedBy { it.calibratedAtEpochMs }.map { it.toModel() },
        notes = packet.notes,
        demo = packet.demo,
        createdAtEpochMs = packet.createdAtEpochMs,
        sealedAtEpochMs = packet.sealedAtEpochMs,
        merkleLeaf = packet.merkleLeaf,
        merkleProof = JsonCodec.decodeStrings(packet.merkleProofJson),
        asOfEpochMs = packet.asOfEpochMs,
        signatureBase64 = packet.signatureBase64,
    )

    fun EvidencePacket.toEntity(
        merkleTreeSize: Int?,
        merkleRoot: String?,
        merkleIndex: Int?,
        sealedHeadHash: String?,
    ): PacketEntity = PacketEntity(
        id = id,
        jobId = jobId,
        jobType = jobType,
        operatorId = operatorId,
        title = title,
        requesterLabel = requesterLabel,
        siteAddress = site.address,
        siteUnit = site.unit,
        siteLat = site.point.lat,
        siteLng = site.point.lng,
        sitePointRecordedAtEpochMs = site.point.recordedAtEpochMs,
        siteAccuracyMeters = site.point.accuracyMeters,
        siteFixSource = site.point.source.name,
        geofenceRadiusMeters = site.geofenceRadiusMeters,
        accessNotes = site.accessNotes,
        planLabel = site.planLabel,
        consentJson = JsonCodec.encodeConsent(consent),
        gatesJson = JsonCodec.encodeGates(gates),
        tier = tier,
        seal = seal,
        notes = notes,
        demo = demo,
        createdAtEpochMs = createdAtEpochMs,
        sealedAtEpochMs = sealedAtEpochMs,
        asOfEpochMs = asOfEpochMs,
        merkleLeaf = merkleLeaf,
        merkleProofJson = JsonCodec.encodeStrings(merkleProof),
        merkleTreeSize = merkleTreeSize,
        merkleRoot = merkleRoot,
        merkleIndex = merkleIndex,
        sealedHeadHash = sealedHeadHash,
        signatureBase64 = signatureBase64,
    )

    // ------------------------------------------------------------------ money

    fun LedgerEntity.toModel(): LedgerEntry = LedgerEntry(
        id = id,
        jobId = jobId,
        packetId = packetId,
        kind = kind,
        amountCents = amountCents,
        status = status,
        atEpochMs = atEpochMs,
        description = description,
        idempotencyKey = idempotencyKey,
        demo = demo,
    )

    fun LedgerEntry.toEntity(): LedgerEntity = LedgerEntity(
        id = id,
        jobId = jobId,
        packetId = packetId,
        kind = kind,
        amountCents = amountCents,
        status = status,
        atEpochMs = atEpochMs,
        description = description,
        idempotencyKey = idempotencyKey,
        demo = demo,
    )

    fun PayoutEntity.toModel(): PayoutRequest = PayoutRequest(
        id = id,
        amountCents = amountCents,
        feeCents = feeCents,
        netCents = netCents,
        mode = mode,
        status = status,
        requestedAtEpochMs = requestedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        destinationLast4 = destinationLast4,
        idempotencyKey = idempotencyKey,
        failureReason = failureReason,
    )

    fun PayoutRequest.toEntity(demo: Boolean): PayoutEntity = PayoutEntity(
        id = id,
        amountCents = amountCents,
        feeCents = feeCents,
        netCents = netCents,
        mode = mode,
        status = status,
        requestedAtEpochMs = requestedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        destinationLast4 = destinationLast4,
        idempotencyKey = idempotencyKey,
        failureReason = failureReason,
        demo = demo,
    )

    // ------------------------------------------------------------------ operator / market / sync

    fun OperatorEntity.toModel(): Operator = Operator(
        id = id,
        displayName = displayName,
        initials = initials,
        credentialTier = credentialTier,
        ratingAvg = ratingAvg,
        acceptanceRate = acceptanceRate,
        onTimeRate = onTimeRate,
        completedJobs = completedJobs,
        streakDays = streakDays,
        credentials = credentialsFromCsv(credentialsCsv),
        payoutAccountLast4 = payoutAccountLast4,
        weeklyGoalCents = weeklyGoalCents,
        memberSinceLabel = memberSinceLabel,
    )

    fun Operator.toEntity(demo: Boolean): OperatorEntity = OperatorEntity(
        id = id,
        displayName = displayName,
        initials = initials,
        credentialTier = credentialTier,
        ratingAvg = ratingAvg,
        acceptanceRate = acceptanceRate,
        onTimeRate = onTimeRate,
        completedJobs = completedJobs,
        streakDays = streakDays,
        credentialsCsv = credentials.toCsv(),
        payoutAccountLast4 = payoutAccountLast4,
        weeklyGoalCents = weeklyGoalCents,
        memberSinceLabel = memberSinceLabel,
        demo = demo,
    )

    fun AvailabilityEntity.toModel(): Availability = Availability(
        isOnline = isOnline,
        updatedAtEpochMs = updatedAtEpochMs,
        radiusMeters = radiusMeters,
        autoAccept = AutoAcceptPolicy(
            enabled = autoAcceptEnabled,
            minPayoutCents = autoAcceptMinPayoutCents,
            maxDistanceMeters = autoAcceptMaxDistanceMeters,
            maxActiveStack = autoAcceptMaxActiveStack,
            allowedTypes = jobTypesFromCsv(autoAcceptAllowedTypesCsv),
            requireCredentialMatch = autoAcceptRequireCredentialMatch,
        ),
        areaLabel = areaLabel,
    )

    fun Availability.toEntity(
        onlineMinutesToday: Int = 0,
        onlineSinceEpochMs: Long? = null,
    ): AvailabilityEntity = AvailabilityEntity(
        isOnline = isOnline,
        updatedAtEpochMs = updatedAtEpochMs,
        radiusMeters = radiusMeters,
        autoAcceptEnabled = autoAccept.enabled,
        autoAcceptMinPayoutCents = autoAccept.minPayoutCents,
        autoAcceptMaxDistanceMeters = autoAccept.maxDistanceMeters,
        autoAcceptMaxActiveStack = autoAccept.maxActiveStack,
        autoAcceptAllowedTypesCsv = autoAccept.allowedTypes.toJobTypeCsv(),
        autoAcceptRequireCredentialMatch = autoAccept.requireCredentialMatch,
        areaLabel = areaLabel,
        onlineMinutesToday = onlineMinutesToday,
        onlineSinceEpochMs = onlineSinceEpochMs,
    )

    fun MarketEntity.toModel(): MarketSnapshot = MarketSnapshot(
        nowEpochMs = nowEpochMs,
        demandIndex = demandIndex,
        supplyIndex = supplyIndex,
        hotZones = JsonCodec.decodeHotZones(hotZonesJson),
        daylightLabel = daylightLabel,
        weatherLabel = weatherLabel,
    )

    fun MarketSnapshot.toEntity(tickIndex: Long, updatedAtEpochMs: Long): MarketEntity = MarketEntity(
        nowEpochMs = nowEpochMs,
        demandIndex = demandIndex,
        supplyIndex = supplyIndex,
        daylightLabel = daylightLabel,
        weatherLabel = weatherLabel,
        hotZonesJson = JsonCodec.encodeHotZones(hotZones),
        tickIndex = tickIndex,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    fun SyncStateEntity.toModel(
        queued: Int,
        inFlight: Int,
        deadLetters: Int,
    ): SyncState = SyncState(
        phase = phase,
        queued = queued,
        inFlight = inFlight,
        deadLetters = deadLetters,
        lastSyncedAtEpochMs = lastSyncedAtEpochMs,
        lastError = lastError,
    )

    fun defaultSyncState(nowEpochMs: Long): SyncStateEntity = SyncStateEntity(
        phase = SyncPhase.IDLE,
        lastSyncedAtEpochMs = null,
        lastError = null,
        updatedAtEpochMs = nowEpochMs,
    )

    /** Reset helper for the demo book: an empty availability row to re-seed over. */
    fun availabilityOrNull(entity: AvailabilityEntity?): Availability? = entity?.toModel()

    // Kept as small named helpers so an enum change is a single-point edit.
    fun offerStatusOf(name: String): OfferStatus = OfferStatus.valueOf(name)
    fun jobStatusOf(name: String): JobStatus = JobStatus.valueOf(name)
    fun jobTypeOf(name: String): JobType = JobType.valueOf(name)
    fun tierOf(name: String): EvidenceTier = EvidenceTier.valueOf(name)
    fun sealStateOf(name: String): SealState = SealState.valueOf(name)
    fun ledgerKindOf(name: String): LedgerKind = LedgerKind.valueOf(name)
    fun ledgerStatusOf(name: String): LedgerStatus = LedgerStatus.valueOf(name)
    fun payoutModeOf(name: String): PayoutMode = PayoutMode.valueOf(name)
    fun payoutStatusOf(name: String): PayoutStatus = PayoutStatus.valueOf(name)
    fun mediaSourceOf(name: String): MediaSource = MediaSource.valueOf(name)
    fun gatesFor(packet: EvidencePacket): List<GateCheck> = packet.gates
}
