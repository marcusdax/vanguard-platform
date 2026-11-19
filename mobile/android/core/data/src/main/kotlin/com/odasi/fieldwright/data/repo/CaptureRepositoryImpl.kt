package com.odasi.fieldwright.data.repo

import com.odasi.fieldwright.crypto.ChainVerifier
import com.odasi.fieldwright.crypto.MerkleLog
import com.odasi.fieldwright.data.CaptureRepository
import com.odasi.fieldwright.data.SealOutcome
import com.odasi.fieldwright.data.db.ChecklistRowEntity
import com.odasi.fieldwright.data.db.JobEntity
import com.odasi.fieldwright.data.db.MeasurementEntity
import com.odasi.fieldwright.data.db.MediaEntity
import com.odasi.fieldwright.data.db.OutboxKind
import com.odasi.fieldwright.data.mapper.JsonCodec
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.data.pipeline.PipelineSealResult
import com.odasi.fieldwright.data.pipeline.SealPipeline
import com.odasi.fieldwright.data.sync.WireFormat
import com.odasi.fieldwright.model.ActiveWork
import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.Idempotency
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.ProofKind
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.SealEngine
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.StepResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Locale

/**
 * Capture orchestration: the append-only work state while the operator is on site, then the
 * blocking gates and the seal through [SealPipeline] (CONTRACTS §5.1, §7.3).
 *
 * Rules encoded here, not in the UI:
 * - consent is required before sealing and must be complete (name, role, scope; a signed
 *   consent also needs its signature hash);
 * - redaction must be resolved for every photo before any capture claim can verify;
 * - every required checklist step must be PASS or N/A (a FAIL blocks; a PENDING blocks);
 * - a MEASUREMENT step requires a calibrated measurement;
 * - arrival must have been confirmed by geofence (an override is recorded and surfaces as a
 *   non-blocking review flag, never silently swallowed);
 * - the record chain is materialised, signed and re-verified at seal.
 *
 * Sealing is idempotent per (job, record count): a replay of an already-sealed job returns
 * the stored packet and its evaluation instead of appending a second seal.
 */
internal class CaptureRepositoryImpl(private val stores: DataStores) : CaptureRepository {

    private val pipeline = SealPipeline(stores.hasher, stores.signer)

    override fun observeWork(jobId: String): Flow<ActiveWork?> = combine(
        stores.jobs.observeJob(jobId),
        stores.capture.observeChecklist(jobId),
        stores.capture.observeMedia(jobId),
        stores.capture.observeMeasurements(jobId),
    ) { jobEntity, checklist, media, measurements ->
        jobEntity?.let { workFrom(it, checklist, media, measurements) }
    }

    override suspend fun begin(jobId: String, atEpochMs: Long): Result<ActiveWork> = runCatching {
        val entity = stores.jobs.get(jobId) ?: throw RepositoryException("Job $jobId not found.")
        when (entity.status) {
            JobStatus.ON_SITE, JobStatus.IN_PROGRESS, JobStatus.PACKET_READY -> Unit
            else -> throw RepositoryException(
                "Capture starts on site; job ${entity.id} is ${entity.status.label}.",
            )
        }
        val updated = when {
            entity.status == JobStatus.ON_SITE -> entity.copy(
                status = JobStatus.IN_PROGRESS,
                startedAtEpochMs = entity.startedAtEpochMs ?: atEpochMs,
                updatedAtEpochMs = atEpochMs,
            )
            entity.startedAtEpochMs == null -> entity.copy(startedAtEpochMs = atEpochMs, updatedAtEpochMs = atEpochMs)
            else -> entity
        }
        if (updated != entity) {
            stores.jobs.upsert(updated)
            stores.outbox.enqueue(
                idempotencyKey = Idempotency.key("capture.begin", jobId, atEpochMs.toString()),
                kind = OutboxKind.JOB_STATUS,
                method = "PATCH",
                path = "/jobs/$jobId",
                payload = mapOf("jobId" to jobId, "status" to updated.status.name, "at" to atEpochMs.toString()),
                demo = entity.demo,
                nowEpochMs = atEpochMs,
            )
        }
        snapshot(jobId)
    }

    override suspend fun addMedia(jobId: String, media: MediaRef, atEpochMs: Long): Result<ActiveWork> = runCatching {
        val entity = requireOpenCapture(jobId)
        stores.capture.upsertMedia(with(Mappers) { media.toEntity(jobId = entity.id, packetId = null) })
        snapshot(jobId)
    }

    override suspend fun addMeasurement(jobId: String, measurement: Measurement): Result<ActiveWork> = runCatching {
        val entity = requireOpenCapture(jobId)
        stores.capture.upsertMeasurement(with(Mappers) { measurement.toEntity(jobId = entity.id, packetId = null) })
        snapshot(jobId)
    }

    override suspend fun setConsent(jobId: String, consent: Consent): Result<ActiveWork> = runCatching {
        require(consent.name.isNotBlank()) { "Consent needs the name of the person who authorized capture." }
        require(consent.role.isNotBlank()) { "Consent needs the subject's role." }
        require(consent.scopeText.isNotBlank()) { "Consent needs the scope the subject agreed to." }
        if (consent.method == ConsentMethod.IN_APP_SIGNATURE) {
            require(!consent.signatureHash.isNullOrBlank()) {
                "Signed-in-app consent needs the signature hash of the typed signature."
            }
        }
        val entity = stores.jobs.get(jobId) ?: throw RepositoryException("Job $jobId not found.")
        if (entity.status.isTerminal) {
            throw RepositoryException("Job ${entity.id} is ${entity.status.label}; consent cannot be added.")
        }
        stores.jobs.upsert(
            entity.copy(
                consentJson = JsonCodec.encodeConsent(consent),
                updatedAtEpochMs = stores.clock.nowEpochMs(),
            ),
        )
        snapshot(jobId)
    }

    override suspend fun completeStep(
        jobId: String,
        stepKey: String,
        result: StepResult,
        note: String,
        mediaIds: List<String>,
    ): Result<ActiveWork> = runCatching {
        val entity = requireOpenCapture(jobId)
        val job = with(Mappers) { entity.toModel() }
        val step = job.checklist.firstOrNull { it.key == stepKey }
            ?: throw RepositoryException("Step '$stepKey' is not on this job's checklist.")
        if (mediaIds.isNotEmpty()) {
            val known = stores.capture.mediaForJob(jobId).map { it.id }.toSet()
            val missing = mediaIds.filter { it !in known }
            if (missing.isNotEmpty()) {
                throw RepositoryException("Step '$stepKey' references media that is not on this job: $missing.")
            }
        }
        stores.capture.upsertChecklist(
            ChecklistRowEntity(
                jobId = jobId,
                stepKey = stepKey,
                result = result,
                note = note,
                mediaIdsJson = JsonCodec.encodeStrings(mediaIds),
                packetId = null,
                recordedAtEpochMs = stores.clock.nowEpochMs(),
            ),
        )
        // All required steps resolved -> the gate board is complete and the job is ready.
        val rows = stores.capture.checklistForJob(jobId)
        val resolved = job.checklist.filter { it.required }.all { required ->
            rows.firstOrNull { it.stepKey == required.key }?.result.let {
                it == StepResult.PASS || it == StepResult.NOT_APPLICABLE
            }
        }
        val current = stores.jobs.get(jobId)!!
        if (resolved && current.status == JobStatus.IN_PROGRESS) {
            stores.jobs.upsert(
                current.copy(status = JobStatus.PACKET_READY, updatedAtEpochMs = stores.clock.nowEpochMs()),
            )
        }
        snapshot(jobId)
    }

    override suspend fun runGates(jobId: String, nowEpochMs: Long): Result<List<GateCheck>> = runCatching {
        val entity = stores.jobs.get(jobId) ?: throw RepositoryException("Job $jobId not found.")
        val job = with(Mappers) { entity.toModel() }
        val consent = with(Mappers) { entity.consent() }
        val media = stores.capture.mediaForJob(jobId).map { row -> with(Mappers) { row.toModel() } }
        val measurements = stores.capture.measurementsForJob(jobId).map { row -> with(Mappers) { row.toModel() } }
        val checklist = stores.capture.checklistForJob(jobId).map { row -> with(Mappers) { row.toModel() } }
        buildGates(entity, job, consent, media, measurements, checklist)
    }

    override suspend fun seal(jobId: String, nowEpochMs: Long): Result<SealOutcome> = runCatching {
        val entity = stores.jobs.get(jobId) ?: throw RepositoryException("Job $jobId not found.")
        if (entity.packetId != null && entity.status == JobStatus.SEALED) {
            return@runCatching replaySeal(entity.packetId!!, nowEpochMs)
        }
        if (!entity.status.isOpenForCapture && entity.status != JobStatus.IN_PROGRESS) {
            throw RepositoryException(
                "Job ${entity.id} is ${entity.status.label}; only an on-site capture can be sealed.",
            )
        }
        val operator = stores.operator.getOperator()?.let { row -> with(Mappers) { row.toModel() } }
            ?: throw RepositoryException("No operator profile; cannot seal.")
        val job = with(Mappers) { entity.toModel() }
        val consent = with(Mappers) { entity.consent() }
            ?: throw RepositoryException("No consent on file; a named person must authorize capture before sealing.")
        val media = stores.capture.mediaForJob(jobId).map { row -> with(Mappers) { row.toModel() } }
        val measurements = stores.capture.measurementsForJob(jobId).map { row -> with(Mappers) { row.toModel() } }
        val checklistRows = stores.capture.checklistForJob(jobId)
        val checklist = checklistRows.map { row -> with(Mappers) { row.toModel() } }
        val gates = buildGates(entity, job, consent, media, measurements, checklist)
        val deviceLogHeads = deviceLogHeads()

        val result = pipeline.assembleAndSeal(
            job = job,
            operator = operator,
            consent = consent,
            media = media,
            measurements = measurements,
            checklist = checklist,
            gates = gates,
            deviceLogHeads = deviceLogHeads,
            nowEpochMs = nowEpochMs,
        )
        persistSealAttempt(entity, result, success = result.evaluation.canSeal, nowEpochMs = nowEpochMs)
        if (!result.evaluation.canSeal) {
            throw SealBlockedException(result.evaluation.reasons, result.evaluation.gates)
        }
        SealOutcome(
            packet = result.packet,
            evaluation = result.evaluation,
            verification = result.verification,
            badges = result.packet.badges,
            merkleIncluded = result.merkleIncluded,
        )
    }

    // ------------------------------------------------------------------ persistence

    private suspend fun persistSealAttempt(
        entity: JobEntity,
        result: PipelineSealResult,
        success: Boolean,
        nowEpochMs: Long,
    ) {
        val packet = result.packet
        stores.transaction {
            val existing = stores.packets.getForJob(entity.id)
            if (existing != null && existing.packet.seal != SealState.EARNED) {
                // Supersede an earlier unsealed attempt. Evidence rows are job-scoped and
                // re-adopted below; only the committed chain is append-only.
                stores.packets.deleteById(existing.packet.id)
            }
            stores.packets.insertPacket(
                with(Mappers) {
                    packet.toEntity(
                        merkleTreeSize = result.merkleTreeSize,
                        merkleRoot = result.merkleRoot,
                        merkleIndex = result.merkleIndex,
                        sealedHeadHash = packet.packetHash,
                    )
                },
            )
            stores.packets.insertRecords(packet.records.map { record -> with(Mappers) { record.toEntity(packet.id) } })
            stores.packets.insertBadges(packet.badges.map { badge -> with(Mappers) { badge.toEntity(packet.id) } })
            stores.capture.adoptMedia(entity.id, packet.id)
            stores.capture.adoptMeasurements(entity.id, packet.id)
            stores.capture.adoptChecklist(entity.id, packet.id)

            val current = stores.jobs.get(entity.id) ?: entity
            val next = if (success) {
                current.copy(
                    status = JobStatus.SEALED,
                    sealedAtEpochMs = nowEpochMs,
                    packetId = packet.id,
                    updatedAtEpochMs = nowEpochMs,
                )
            } else {
                current.copy(
                    status = if (current.status == JobStatus.IN_PROGRESS || current.status == JobStatus.ON_SITE) {
                        JobStatus.PACKET_READY
                    } else {
                        current.status
                    },
                    updatedAtEpochMs = nowEpochMs,
                )
            }
            stores.jobs.upsert(next)
            if (success) {
                stores.outbox.enqueue(
                    idempotencyKey = Idempotency.key("packet.seal", packet.id, packet.records.size.toString()),
                    kind = OutboxKind.PACKET_SEALED,
                    method = "POST",
                    path = "/packets",
                    payload = mapOf(
                        "packetId" to packet.id,
                        "jobId" to packet.jobId,
                        "chainHead" to (packet.packetHash ?: ""),
                        "recordCount" to packet.records.size.toString(),
                        "sealedAt" to nowEpochMs.toString(),
                        "demo" to packet.demo.toString(),
                        // Server-parity wire form: the exact record objects the server
                        // re-hashes (nested payload, ISO timestamp, 1-based seq).
                        "records" to WireFormat.recordsJson(packet.records),
                    ),
                    demo = packet.demo,
                    nowEpochMs = nowEpochMs,
                )
            }
        }
    }

    private suspend fun replaySeal(packetId: String, nowEpochMs: Long): SealOutcome {
        val aggregate = stores.packets.get(packetId)
            ?: throw RepositoryException("Sealed packet $packetId is missing from the vault.")
        val packet = with(Mappers) { aggregate.toModel() }
        val verification = ChainVerifier.verify(packet.records, stores.hasher, stores.signer)
        val head = packet.packetHash
        val treeSize = aggregate.packet.merkleTreeSize
        val index = aggregate.packet.merkleIndex
        val root = aggregate.packet.merkleRoot
        val merkleIncluded = head != null && treeSize != null && index != null && root != null &&
            MerkleLog.verify(
                leaf = head,
                proof = packet.merkleProof,
                index = index,
                treeSize = treeSize,
                root = root,
            )
        val evaluation = SealEngine.evaluate(packet, verification.valid, merkleIncluded, nowEpochMs)
        return SealOutcome(
            packet = packet,
            evaluation = evaluation,
            verification = verification,
            badges = packet.badges,
            merkleIncluded = merkleIncluded,
        )
    }

    /** Heads of already-sealed, non-demo packets in log order (demo data never anchors). */
    private suspend fun deviceLogHeads(): List<String> = stores.packets.all()
        .asSequence()
        .filter { aggregate -> aggregate.packet.seal == SealState.EARNED && !aggregate.packet.demo }
        .sortedBy { aggregate -> aggregate.packet.sealedAtEpochMs ?: aggregate.packet.createdAtEpochMs }
        .mapNotNull { aggregate -> with(Mappers) { aggregate.toModel() }.packetHash }
        .toList()

    // ------------------------------------------------------------------ state

    private suspend fun snapshot(jobId: String): ActiveWork {
        val entity = stores.jobs.get(jobId) ?: throw RepositoryException("Job $jobId not found.")
        return workFrom(
            entity = entity,
            checklist = stores.capture.checklistForJob(jobId),
            media = stores.capture.mediaForJob(jobId),
            measurements = stores.capture.measurementsForJob(jobId),
        )
    }

    private fun workFrom(
        entity: JobEntity,
        checklist: List<ChecklistRowEntity>,
        media: List<MediaEntity>,
        measurements: List<MeasurementEntity>,
    ): ActiveWork = ActiveWork(
        jobId = entity.id,
        status = entity.status,
        acceptedAtEpochMs = entity.acceptedAtEpochMs ?: entity.updatedAtEpochMs,
        arrivedAtEpochMs = entity.arrivedAtEpochMs,
        startedAtEpochMs = entity.startedAtEpochMs,
        checklist = checklist.associate { row -> row.stepKey to with(Mappers) { row.toModel() } },
        measurements = measurements.map { row -> with(Mappers) { row.toModel() } },
        consent = with(Mappers) { entity.consent() },
        media = media.map { row -> with(Mappers) { row.toModel() } },
    )

    private suspend fun requireOpenCapture(jobId: String): JobEntity {
        val entity = stores.jobs.get(jobId) ?: throw RepositoryException("Job $jobId not found.")
        if (entity.status != JobStatus.IN_PROGRESS && entity.status != JobStatus.PACKET_READY) {
            throw RepositoryException(
                "Job ${entity.id} is ${entity.status.label}; start capture on site before recording evidence.",
            )
        }
        return entity
    }

    // ------------------------------------------------------------------ gates

    private fun buildGates(
        entity: JobEntity,
        job: Job,
        consent: Consent?,
        media: List<MediaRef>,
        measurements: List<Measurement>,
        checklist: List<ChecklistResult>,
    ): List<GateCheck> {
        val requiredSteps = job.checklist.filter { it.required }
        val resultsByKey = checklist.associateBy { it.key }
        val unresolved = requiredSteps.filter { step ->
            val result = resultsByKey[step.key]?.result
            result == null || result == StepResult.PENDING || result == StepResult.FAIL
        }
        val unresolvedRedaction = media.filter { it.isPhoto && it.redaction == RedactionState.REQUIRED }
        val photoCount = media.count { it.isPhoto }
        val needsMeasurement = job.checklist.any { it.proofKind == ProofKind.MEASUREMENT }
        val measurementValid = measurements.any {
            it.referenceMeters > 0.0 && it.pixelSpan > 0.0 && it.meters > 0.0
        }
        val consentComplete = consent != null &&
            consent.name.isNotBlank() &&
            consent.role.isNotBlank() &&
            consent.scopeText.isNotBlank() &&
            (consent.method != ConsentMethod.IN_APP_SIGNATURE || !consent.signatureHash.isNullOrBlank())
        val arrived = entity.arrivedAtEpochMs != null
        val arrivalDetail = when {
            entity.arrivalOverrideReason != null ->
                "Arrival override recorded: ${entity.arrivalOverrideReason}"
            entity.arrivalDistanceMeters != null ->
                "Arrived ${meters(entity.arrivalDistanceMeters)} from the site point, inside the ${entity.geofenceRadiusMeters} m fence."
            arrived -> "Arrival confirmed on site."
            else -> "Arrival has not been confirmed; confirm the geofence check before sealing."
        }

        return listOf(
            GateCheck(
                id = "gate.consent",
                label = "Consent on file",
                passed = consentComplete,
                detail = consent?.let { "Authorized by ${it.name} (${it.role}) · ${it.method.label}" }
                    ?: "No consent record — a named person must authorize capture.",
                blocking = true,
            ),
            GateCheck(
                id = "gate.geofence",
                label = "Geofence satisfied",
                passed = arrived,
                detail = arrivalDetail,
                blocking = true,
            ),
            GateCheck(
                id = "gate.redaction",
                label = "Media hashed and redaction resolved",
                passed = photoCount > 0 && unresolvedRedaction.isEmpty(),
                detail = when {
                    photoCount == 0 -> "No captured frames on this job yet."
                    unresolvedRedaction.isNotEmpty() ->
                        "${unresolvedRedaction.size} frame(s) still require redaction (faces/plates)."
                    else -> "$photoCount frame(s) hashed; redaction resolved."
                },
                blocking = true,
            ),
            GateCheck(
                id = "gate.checklist",
                label = "Required checklist",
                passed = requiredSteps.isNotEmpty() && unresolved.isEmpty(),
                detail = if (unresolved.isEmpty()) {
                    "${requiredSteps.size} of ${requiredSteps.size} required steps recorded."
                } else {
                    "Open or failed: ${unresolved.joinToString { it.label }}."
                },
                blocking = true,
            ),
            GateCheck(
                id = "gate.measurement",
                label = "Measurement calibrated",
                passed = if (needsMeasurement) measurementValid else true,
                detail = when {
                    !needsMeasurement -> "No measured step on this job."
                    measurementValid -> "${measurements.size} calibrated measurement(s) on file."
                    else -> "Measured steps need a calibrated reference (site card, shingle, door)."
                },
                blocking = true,
            ),
            GateCheck(
                id = "gate.arrival",
                label = "Arrival review",
                passed = entity.arrivalOverrideReason == null,
                detail = entity.arrivalOverrideReason?.let { "Override: $it — packet flagged for review." }
                    ?: "Arrival matched the site geofence.",
                blocking = false,
            ),
            GateCheck(
                id = "gate.chain",
                label = "Chain verifies",
                passed = true,
                detail = "The genesis-anchored chain is materialised, signed and re-verified at seal.",
                blocking = true,
            ),
        )
    }

    private fun meters(value: Double): String = String.format(Locale.US, "%.0f m", value)
}
