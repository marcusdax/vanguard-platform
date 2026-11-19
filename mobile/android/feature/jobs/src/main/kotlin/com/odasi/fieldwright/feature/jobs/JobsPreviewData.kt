package com.odasi.fieldwright.feature.jobs

import com.odasi.fieldwright.model.ChecklistStep
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.ProofKind
import com.odasi.fieldwright.model.Requester
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SiteRef

/**
 * Preview-only sample book for the Jobs module. Every job is `demo = true`; nothing here is
 * presented as a live record (CONTRACTS §1.4). Kept in one place so the list previews and
 * the detail previews show the same operator, the same site and the same payout maths.
 */
internal object JobsPreview {

    const val NOW: Long = 1_700_000_000_000L

    fun point(lat: Double, lng: Double, accuracy: Float = 9f): GeoPoint = GeoPoint(
        lat = lat,
        lng = lng,
        recordedAtEpochMs = NOW,
        accuracyMeters = accuracy,
    )

    val site: SiteRef = SiteRef(
        address = "1841 Creekside Dr",
        unit = "Unit B",
        point = point(30.2712, -97.7402),
        geofenceRadiusMeters = 120,
        accessNotes = "Gate code 4412. Dog in the back yard — call first. Meter vault is behind the carport.",
        planLabel = "Creekside plat 14",
    )

    val requester: Requester = Requester(
        org = "Meridian Mutual",
        contactName = "Dana Reyes",
        kind = RequesterKind.INSURER,
        billingLabel = "MERIDIAN · claims",
    )

    val checklist: List<ChecklistStep> = listOf(
        ChecklistStep("slopes", "Four roof slopes", "Photo · required · framing guide", ProofKind.PHOTO),
        ChecklistStep("square", "Test square, 10 × 10 ft", "Photo · required · measured reference", ProofKind.PHOTO),
        ChecklistStep("paddle", "Hail paddle measured", "Measurement · calibrated reference", ProofKind.MEASUREMENT),
        ChecklistStep("consent", "Consent on file", "Consent · subject signature", ProofKind.CONSENT),
    )

    fun job(
        id: String = "job-1048",
        type: JobType = JobType.ROOF_HAIL,
        status: JobStatus = JobStatus.EN_ROUTE,
        title: String = "Panel T-14 hail assessment",
        site: SiteRef = this.site,
        acceptedAtEpochMs: Long? = if (status.ordinal >= JobStatus.ACCEPTED.ordinal) NOW - 18 * 60_000L else null,
        sealedAtEpochMs: Long? = null,
        packetId: String? = null,
        demo: Boolean = true,
    ): Job = Job(
        id = id,
        type = type,
        title = title,
        requester = requester,
        site = site,
        status = status,
        bountyCents = type.baseBountyCents,
        bonusCents = 1_500,
        estimatedMinutes = type.estimatedMinutes,
        scheduledForEpochMs = null,
        slaMinutes = 90,
        distanceMeters = 2_400,
        etaMinutes = 8,
        consentScope = type.consentScope,
        checklist = checklist,
        requiredCredentials = type.requiredCredentials,
        demo = demo,
        notes = "Sample book job. Not sent to the public log.",
        acceptedAtEpochMs = acceptedAtEpochMs,
        sealedAtEpochMs = sealedAtEpochMs,
        packetId = packetId,
    )

    fun offer(
        job: Job,
        remainingMillis: Long = 32_000L,
        surgeMultiplier: Double = 1.4,
        payoutCents: Int = 42_000,
    ): JobsOffer = JobsOffer(
        offer = Offer(
            id = "offer-${job.id}",
            job = job,
            operatorId = "op-1",
            payoutCents = payoutCents,
            surgeMultiplier = surgeMultiplier,
            offeredAtEpochMs = NOW,
            expiresAtEpochMs = NOW + remainingMillis,
            score = 0.84,
            scoreReasons = listOf("3 min from your last fix", "Credential match"),
            etaMinutes = job.etaMinutes,
            autoAcceptEligible = true,
        ),
        remainingMillis = remainingMillis,
    )

    private val availableJob: Job = job(id = "job-1051", status = JobStatus.AVAILABLE, title = "Panel T-14 hail assessment")

    private val activeJob: Job = job(id = "job-1048", status = JobStatus.EN_ROUTE, title = "Panel T-14 hail assessment")

    private val capturingJob: Job = job(id = "job-1047", status = JobStatus.IN_PROGRESS, title = "Monthly property walk · Sabine Court")

    private val paidJob: Job = job(
        id = "job-1012",
        type = JobType.RPZ_TEST,
        status = JobStatus.PAID,
        title = "Annual RPZ test · 902 Ridgeview",
        acceptedAtEpochMs = NOW - 2 * 24 * 60 * 60 * 1000L,
        sealedAtEpochMs = NOW - 2 * 24 * 60 * 60 * 1000L,
        packetId = "FW-1012",
    )

    fun jobsState(filter: JobsFilter = JobsFilter.OFFERS): JobsUiState = JobsUiState(
        loading = false,
        filter = filter,
        offers = listOf(offer(availableJob)),
        activeJobs = listOf(activeJob, capturingJob),
        completedJobs = listOf(paidJob),
        packetIds = mapOf("job-1012" to "FW-1012"),
        packetHashes = mapOf("job-1012" to "9f2c41d8"),
        nowEpochMs = NOW,
    )

    fun emptyState(filter: JobsFilter): JobsUiState = JobsUiState(
        loading = false,
        filter = filter,
        nowEpochMs = NOW,
    )

    fun errorState(): JobsUiState = jobsState().copy(
        errorMessage = "Dispatch did not answer. Check your connection and retry.",
        offers = emptyList(),
    )

    fun detailState(
        status: JobStatus = JobStatus.EN_ROUTE,
        withOffer: Boolean = false,
    ): JobDetailUiState {
        val job = job(status = status, packetId = if (status == JobStatus.PACKET_READY) "FW-1048" else null)
        return JobDetailUiState(
            loading = false,
            job = job,
            offer = if (withOffer) offer(job).offer else null,
            packetId = job.packetId,
            packetHash = if (job.packetId != null) "9f2c41d8" else null,
            nowEpochMs = NOW,
        )
    }
}
