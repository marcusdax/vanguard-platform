package com.odasi.fieldwright.data.demo

import com.odasi.fieldwright.model.AutoAcceptPolicy
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.Credential
import com.odasi.fieldwright.model.DispatchEngine
import com.odasi.fieldwright.model.GeoMath
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HotZone
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobCatalog
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.OfferStatus
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.PayoutStatus
import com.odasi.fieldwright.model.Requester
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SiteRef

/**
 * The sample book, verbatim from the supplied Fieldwright copy: FW-0994 "Panel T-14 is
 * set" ($420, Lamar Tilt Partners, consent on file for Chris Adeyemi), FW-1012 "Annual
 * RPZ test" ($95, Creekside MUD, sealed and paid), FW-1048 "Streak on the tank, feeder
 * 14" (active), FW-1051 "Hail on the south slope" (scheduled), four open jobs across the
 * job families, two live offers, Elena Voss's operator profile, and the ledger that the
 * vault and payouts screens are honest about.
 *
 * Pure data: no I/O, no engine decisions beyond deterministic helpers ([GeoMath],
 * [DispatchEngine.offeredPayoutCents]). [DemoSeeder] owns persistence. Every job/offer is
 * marked `demo = true` so nothing here can enter an anchor payload.
 */
object DemoCatalog {

    /**
     * Bumped when the sample book (or the evidence protocol it is built under) changes.
     * The container compares this with the persisted marker and re-seeds on mismatch, so a
     * protocol change such as the 1-based record sequence cannot leave a stale demo vault.
     */
    const val SEED_VERSION: Int = 2

    const val DEMO_OPERATOR_ID: String = "op-elena-voss"

    /** The operator's Ground-screen fix: Creekside, Austin. Also the market centre. */
    val OPERATOR_HOME: GeoPoint = GeoPoint(
        lat = 30.1845,
        lng = -97.8795,
        recordedAtEpochMs = 0L,
        accuracyMeters = 8f,
        source = GeoPoint.FixSource.SIMULATED,
    )

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    // ------------------------------------------------------------------ operator

    fun operator(): Operator = Operator(
        id = DEMO_OPERATOR_ID,
        displayName = "Elena Voss",
        initials = "EV",
        credentialTier = 3,
        ratingAvg = 4.9,
        acceptanceRate = 0.92,
        onTimeRate = 0.97,
        completedJobs = 38,
        streakDays = 6,
        credentials = setOf(
            Credential.ROOF_SAFETY,
            Credential.PROPERTY_INSPECTOR,
            Credential.RPZ_BACKFLOW_TESTER,
        ),
        payoutAccountLast4 = "4417",
        weeklyGoalCents = 120_000,
        memberSinceLabel = "March 2024",
    )

    fun availability(nowEpochMs: Long): Availability = Availability(
        isOnline = true,
        updatedAtEpochMs = nowEpochMs - 2 * HOUR,
        radiusMeters = 15_000,
        autoAccept = AutoAcceptPolicy(
            enabled = true,
            minPayoutCents = 3_000,
            maxDistanceMeters = 12_000,
            // One job at a time in the sample book: FW-1048 already fills the stack, so the
            // demo's first offers stay on screen for a human to Accept/Pass instead of being
            // swept up by auto-accept. Lower the stack and the engine will take over.
            maxActiveStack = 1,
            allowedTypes = JobType.entries.toSet(),
            requireCredentialMatch = true,
        ),
        areaLabel = "Creekside · MUD 14",
    )

    // ------------------------------------------------------------------ market

    fun market(nowEpochMs: Long): MarketSnapshot = MarketSnapshot(
        nowEpochMs = nowEpochMs,
        demandIndex = 0.62,
        supplyIndex = 0.41,
        hotZones = listOf(
            HotZone(
                label = "Creekside · MUD 14",
                point = pointAt(30.1873, -97.8814, nowEpochMs),
                radiusMeters = 900,
                surgeMultiplier = 1.25,
                reason = "Two utility crews active · feeder 14",
            ),
            HotZone(
                label = "Oak Hollow",
                point = pointAt(30.1973, -97.8674, nowEpochMs),
                radiusMeters = 1_200,
                surgeMultiplier = 1.15,
                reason = "Hail claim intake after the 4/11 event",
            ),
        ),
        daylightLabel = "4 hr 10 min",
        weatherLabel = "Clear · 88 °F",
    )

    // ------------------------------------------------------------------ jobs (sample book)

    /**
     * Ten jobs: the two sealed packets, the active and scheduled sessions, four open jobs
     * across four different families, and two jobs with live offers.
     */
    fun jobs(nowEpochMs: Long): List<Job> {
        val sealed0994At = nowEpochMs - 2 * DAY
        val sealed1012At = nowEpochMs - 5 * DAY
        return listOf(
            job(
                id = "FW-0994",
                type = JobType.ROOF_HAIL,
                title = "Panel T-14 is set",
                requester = Requester(
                    org = "Lamar Tilt Partners",
                    contactName = "Chris Adeyemi",
                    kind = RequesterKind.INSURER,
                    billingLabel = "Lamar Tilt Partners · Claim 88-2214",
                ),
                address = "1412 Creekside Bend",
                unit = null,
                lat = 30.1893,
                lng = -97.8744,
                geofenceMeters = 60,
                accessNotes = "Gate code at the call box; roof access from the alley.",
                planLabel = "Creekside parcel 14 · lot 8",
                status = JobStatus.SUBMITTED,
                bountyCents = 40_500,
                bonusCents = 1_500,
                scheduledForEpochMs = sealed0994At - 6 * HOUR,
                nowEpochMs = nowEpochMs,
                slaMinutes = 120,
                notes = "Hail event 04-11. South slope is the money shot; panel T-14 replacement is the claim.",
                acceptedAtEpochMs = sealed0994At - 5 * HOUR,
                arrivedAtEpochMs = sealed0994At - 4 * HOUR,
                sealedAtEpochMs = sealed0994At,
                packetId = "FW-0994",
            ),
            job(
                id = "FW-1012",
                type = JobType.RPZ_TEST,
                title = "Annual RPZ test",
                requester = Requester(
                    org = "Creekside MUD",
                    contactName = "Priya Raman",
                    kind = RequesterKind.UTILITY,
                    billingLabel = "Creekside MUD · District 14",
                ),
                address = "88 MUD Service Rd",
                unit = null,
                lat = 30.1828,
                lng = -97.8874,
                geofenceMeters = 25,
                accessNotes = "Vault is behind the fence; MUD key in lockbox 4417.",
                planLabel = "District 14 · vault 7",
                status = JobStatus.PAID,
                bountyCents = 9_500,
                bonusCents = 0,
                scheduledForEpochMs = sealed1012At - 5 * HOUR,
                nowEpochMs = nowEpochMs,
                slaMinutes = 60,
                notes = "Annual test; gauge face is the record, do not transcribe the needle.",
                acceptedAtEpochMs = sealed1012At - 4 * HOUR,
                arrivedAtEpochMs = sealed1012At - 3 * HOUR,
                sealedAtEpochMs = sealed1012At,
                packetId = "FW-1012",
            ),
            job(
                id = "FW-1048",
                type = JobType.RPZ_TEST,
                title = "Streak on the tank, feeder 14",
                requester = Requester(
                    org = "Creekside MUD",
                    contactName = "Priya Raman",
                    kind = RequesterKind.UTILITY,
                    billingLabel = "Creekside MUD · District 14",
                ),
                address = "220 Fannin Row",
                unit = null,
                lat = 30.1863,
                lng = -97.8814,
                geofenceMeters = 40,
                accessNotes = "Feeder 14 on the north wall; the streak runs from the relief.",
                planLabel = "Creekside parcel 09",
                status = JobStatus.ACCEPTED,
                bountyCents = 11_000,
                bonusCents = 1_500,
                scheduledForEpochMs = nowEpochMs + 90 * MINUTE,
                nowEpochMs = nowEpochMs,
                slaMinutes = 60,
                notes = "Call the district desk if the vault is locked; do not force the latch.",
                acceptedAtEpochMs = nowEpochMs - 20 * MINUTE,
            ),
            job(
                id = "FW-1051",
                type = JobType.ROOF_HAIL,
                title = "Hail on the south slope",
                requester = Requester(
                    org = "Oak Hollow Property Group",
                    contactName = "Marcus Bell",
                    kind = RequesterKind.PROPERTY_MANAGER,
                    billingLabel = "Oak Hollow · claim intake",
                ),
                address = "9 Oak Hollow Ct",
                unit = null,
                lat = 30.1973,
                lng = -97.8664,
                geofenceMeters = 50,
                accessNotes = "Call ahead; dogs in the back yard.",
                planLabel = "Oak Hollow lot 22",
                status = JobStatus.SCHEDULED,
                bountyCents = 44_000,
                bonusCents = 2_500,
                scheduledForEpochMs = nowEpochMs + DAY,
                nowEpochMs = nowEpochMs,
                slaMinutes = 120,
                notes = "Same event footprint as FW-0994; four slopes and a test square.",
            ),
            availableJob(
                id = "AV-2201",
                type = JobType.METER_CLUSTER,
                title = "Meter cluster · Lot 4 bank",
                requester = Requester("Creekside MUD", "Priya Raman", RequesterKind.UTILITY, "Creekside MUD · District 14"),
                address = "Lot 4, Creekside Loop",
                lat = 30.1878,
                lng = -97.8789,
                geofenceMeters = 20,
                accessNotes = "Exterior meter bank, street side.",
                planLabel = "Creekside parcel 04",
                bountyCents = 2_800,
                bonusCents = 700,
                nowEpochMs = nowEpochMs,
            ),
            availableJob(
                id = "AV-2202",
                type = JobType.PROPERTY_WALK,
                title = "Monthly walk · Ridgeview",
                requester = Requester("Oak Hollow Property Group", "Marcus Bell", RequesterKind.PROPERTY_MANAGER, "Oak Hollow · monthly"),
                address = "Ridgeview Commons, building C",
                lat = 30.1783,
                lng = -97.8844,
                geofenceMeters = 80,
                accessNotes = "Fixed corner reference at the loading dock.",
                planLabel = "Ridgeview · common areas",
                bountyCents = 6_500,
                bonusCents = 0,
                nowEpochMs = nowEpochMs,
            ),
            availableJob(
                id = "AV-2203",
                type = JobType.PROGRESS_PROOF,
                title = "Progress proof · Foundation pour",
                requester = Requester("BuildRight Contracting", "Tomas Reyna", RequesterKind.CONTRACTOR, "BuildRight · draw 3"),
                address = "812 Lone Star Ave",
                lat = 30.1933,
                lng = -97.8714,
                geofenceMeters = 45,
                accessNotes = "Ask for the site super; hard hat required.",
                planLabel = "Lone Star · lot 3",
                bountyCents = 12_000,
                bonusCents = 0,
                nowEpochMs = nowEpochMs,
            ),
            availableJob(
                id = "AV-2204",
                type = JobType.CLAIM_SWEEP,
                title = "Claim sweep · 14 Live Oak",
                requester = Requester("Vargas Residence", "Alma Vargas", RequesterKind.OWNER, "Vargas · homeowner claim"),
                address = "14 Live Oak Trail",
                lat = 30.1908,
                lng = -97.8869,
                geofenceMeters = 70,
                accessNotes = "No faces or plates; owner will meet at the porch.",
                planLabel = "Live Oak · lot 14",
                bountyCents = 28_000,
                bonusCents = 2_000,
                nowEpochMs = nowEpochMs,
            ),
            offeredJob(
                id = "OF-3301",
                type = JobType.RPZ_TEST,
                title = "Annual RPZ test · Feeder 14 yard",
                requester = Requester("Creekside MUD", "Priya Raman", RequesterKind.UTILITY, "Creekside MUD · District 14"),
                address = "301 Fannin Row",
                lat = 30.1853,
                lng = -97.8829,
                geofenceMeters = 30,
                accessNotes = "Yard gate is open between 8 and 4.",
                planLabel = "District 14 · vault 9",
                bountyCents = 9_500,
                bonusCents = 500,
                nowEpochMs = nowEpochMs,
            ),
            offeredJob(
                id = "OF-3302",
                type = JobType.PROPERTY_WALK,
                title = "Monthly walk · Creekside retail",
                requester = Requester("Lamar Tilt Partners", "Chris Adeyemi", RequesterKind.PROPERTY_MANAGER, "Lamar Tilt · retail portfolio"),
                address = "Creekside Retail, north service corridor",
                lat = 30.1818,
                lng = -97.8749,
                geofenceMeters = 60,
                accessNotes = "Check in with the leasing office before the corridor.",
                planLabel = "Creekside retail · common areas",
                bountyCents = 6_500,
                bonusCents = 1_500,
                nowEpochMs = nowEpochMs,
            ),
        )
    }

    // ------------------------------------------------------------------ offers

    /** The two live offers shown on the Ground screen; TTL is the engine's 45 s. */
    fun offers(nowEpochMs: Long): List<Offer> {
        val market = market(nowEpochMs)
        val jobs = jobs(nowEpochMs).associateBy { it.id }
        val surge = market.surgeMultiplier
        fun offer(jobId: String, score: Double, autoEligible: Boolean, reasons: List<String>): Offer {
            val job = jobs.getValue(jobId)
            return Offer(
                id = "OF-${jobId.removePrefix("OF-")}",
                job = job,
                operatorId = DEMO_OPERATOR_ID,
                payoutCents = DispatchEngine.offeredPayoutCents(job, surge),
                surgeMultiplier = surge,
                offeredAtEpochMs = nowEpochMs - 3_000,
                expiresAtEpochMs = DispatchEngine.expiresAt(nowEpochMs - 3_000),
                score = score,
                scoreReasons = reasons,
                etaMinutes = job.etaMinutes,
                autoAcceptEligible = autoEligible,
                status = OfferStatus.PENDING,
            )
        }
        return listOf(
            offer(
                jobId = "OF-3301",
                score = 0.87,
                autoEligible = false,
                reasons = listOf(
                    "Closest open job · ${String.format(java.util.Locale.US, "%.1f", jobs.getValue("OF-3301").distanceMeters / 1000.0)} km",
                    "RPZ credential on file",
                    "Payout above your \$30 floor",
                    "Auto-accept waits: your 1-job stack is filled by FW-1048",
                ),
            ),
            offer(
                jobId = "OF-3302",
                score = 0.74,
                autoEligible = false,
                reasons = listOf(
                    "Property inspector credential on file",
                    "Surge 1.21 disclosed",
                    "Auto-accept waits: your 1-job stack is filled by FW-1048",
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ money

    fun ledger(nowEpochMs: Long): List<LedgerEntry> {
        val sealed0994At = nowEpochMs - 2 * DAY
        val sealed1012At = nowEpochMs - 5 * DAY
        return listOf(
            LedgerEntry(
                id = "LG-3001",
                jobId = "FW-1012",
                packetId = "FW-1012",
                kind = LedgerKind.EARNING,
                amountCents = 9_500,
                status = LedgerStatus.PAID,
                atEpochMs = sealed1012At,
                description = "FW-1012 · Annual RPZ test · Creekside MUD",
                idempotencyKey = "demo:ledger:fw-1012:earning",
                demo = true,
            ),
            LedgerEntry(
                id = "LG-3002",
                jobId = null,
                packetId = null,
                kind = LedgerKind.EARNING,
                amountCents = 28_500,
                status = LedgerStatus.PAID,
                atEpochMs = nowEpochMs - 6 * DAY,
                description = "FW-0968 · Meter cluster read · Oak Hollow",
                idempotencyKey = "demo:ledger:fw-0968:earning",
                demo = true,
            ),
            LedgerEntry(
                id = "LG-3003",
                jobId = "FW-0994",
                packetId = "FW-0994",
                kind = LedgerKind.EARNING,
                amountCents = 40_500,
                status = LedgerStatus.AVAILABLE,
                atEpochMs = sealed0994At,
                description = "FW-0994 · Panel T-14 is set · Lamar Tilt Partners",
                idempotencyKey = "demo:ledger:fw-0994:earning",
                demo = true,
            ),
            LedgerEntry(
                id = "LG-3004",
                jobId = "FW-0994",
                packetId = "FW-0994",
                kind = LedgerKind.BONUS,
                amountCents = 1_500,
                status = LedgerStatus.AVAILABLE,
                atEpochMs = sealed0994At,
                description = "Busy bonus · Creekside surge",
                idempotencyKey = "demo:ledger:fw-0994:bonus",
                demo = true,
            ),
            LedgerEntry(
                id = "LG-3005",
                jobId = "FW-0994",
                packetId = "FW-0994",
                kind = LedgerKind.TIP,
                amountCents = 1_200,
                status = LedgerStatus.PENDING,
                atEpochMs = nowEpochMs - 3 * HOUR,
                description = "Requester tip · pending review",
                idempotencyKey = "demo:ledger:fw-0994:tip",
                demo = true,
            ),
        )
    }

    /** One already-paid standard-ACH payout, so the payout history is not a lie. */
    fun payouts(nowEpochMs: Long): List<PayoutRequest> = listOf(
        PayoutRequest(
            id = "PO-2201",
            amountCents = 38_000,
            feeCents = 0,
            netCents = 38_000,
            mode = PayoutMode.STANDARD,
            status = PayoutStatus.PAID,
            requestedAtEpochMs = nowEpochMs - 6 * DAY,
            completedAtEpochMs = nowEpochMs - 5 * DAY,
            destinationLast4 = "4417",
            idempotencyKey = "demo:payout:PO-2201",
        ),
    )

    // ------------------------------------------------------------------ packet fixtures

    /** FW-0994 is the sealed roofing packet: Chris Adeyemi consented to the roof scope. */
    fun packetFixture(packetId: String, nowEpochMs: Long): DemoPacketFixture? = when (packetId) {
        "FW-0994" -> DemoPacketFixture(
            jobId = "FW-0994",
            consentName = "Chris Adeyemi",
            consentRole = "Facilities lead, Lamar Tilt Partners",
            consentCapturedAtEpochMs = nowEpochMs - 2 * DAY - 4 * HOUR,
            photoLabels = listOf(
                "South slope · establishing",
                "South slope · test square",
                "Panel T-14 · replacement set",
                "North slope · reference",
            ),
            measurement = MeasurementFixture(
                label = "South slope test square",
                meters = 3.05,
                referenceLabel = "Site card 30 cm",
                referenceMeters = 0.30,
                pixelSpan = 1_240.0,
            ),
            paid = false,
        )
        "FW-1012" -> DemoPacketFixture(
            jobId = "FW-1012",
            consentName = "Priya Raman",
            consentRole = "MUD 14 service coordinator",
            consentCapturedAtEpochMs = nowEpochMs - 5 * DAY - 3 * HOUR,
            photoLabels = listOf(
                "Vault · polygon",
                "Body · tag readable",
                "Gauge · face framed",
                "Test cocks · seated",
            ),
            measurement = MeasurementFixture(
                label = "Vault lid clear span",
                meters = 0.91,
                referenceLabel = "Door 91 cm",
                referenceMeters = 0.91,
                pixelSpan = 980.0,
            ),
            paid = true,
        )
        else -> null
    }

    // ------------------------------------------------------------------ helpers

    data class MeasurementFixture(
        val label: String,
        val meters: Double,
        val referenceLabel: String,
        val referenceMeters: Double,
        val pixelSpan: Double,
    )

    data class DemoPacketFixture(
        val jobId: String,
        val consentName: String,
        val consentRole: String,
        val consentCapturedAtEpochMs: Long,
        val photoLabels: List<String>,
        val measurement: MeasurementFixture,
        val paid: Boolean,
    )

    private fun pointAt(lat: Double, lng: Double, nowEpochMs: Long): GeoPoint = GeoPoint(
        lat = lat,
        lng = lng,
        recordedAtEpochMs = nowEpochMs,
        accuracyMeters = 8f,
        source = GeoPoint.FixSource.SIMULATED,
    )

    private fun site(
        address: String,
        unit: String?,
        lat: Double,
        lng: Double,
        geofenceMeters: Int,
        accessNotes: String?,
        planLabel: String,
        nowEpochMs: Long,
    ): SiteRef = SiteRef(
        address = address,
        unit = unit,
        point = pointAt(lat, lng, nowEpochMs),
        geofenceRadiusMeters = geofenceMeters,
        accessNotes = accessNotes,
        planLabel = planLabel,
    )

    private fun baseJob(
        id: String,
        type: JobType,
        title: String,
        requester: Requester,
        site: SiteRef,
        status: JobStatus,
        bountyCents: Int,
        bonusCents: Int,
        nowEpochMs: Long,
        scheduledForEpochMs: Long?,
        slaMinutes: Int,
        notes: String,
        acceptedAtEpochMs: Long?,
        arrivedAtEpochMs: Long?,
        sealedAtEpochMs: Long?,
        packetId: String?,
    ): Job {
        val distance = GeoMath.distanceMeters(OPERATOR_HOME, site.point)
        return Job(
            id = id,
            type = type,
            title = title,
            requester = requester,
            site = site,
            status = status,
            bountyCents = bountyCents,
            bonusCents = bonusCents,
            estimatedMinutes = type.estimatedMinutes,
            scheduledForEpochMs = scheduledForEpochMs,
            slaMinutes = slaMinutes,
            distanceMeters = distance.toInt(),
            etaMinutes = GeoMath.etaMinutes(distance),
            consentScope = type.consentScope,
            checklist = JobCatalog.checklistFor(type),
            requiredCredentials = type.requiredCredentials,
            demo = true,
            notes = notes,
            acceptedAtEpochMs = acceptedAtEpochMs,
            arrivedAtEpochMs = arrivedAtEpochMs,
            sealedAtEpochMs = sealedAtEpochMs,
            packetId = packetId,
        )
    }

    @Suppress("LongParameterList")
    private fun job(
        id: String,
        type: JobType,
        title: String,
        requester: Requester,
        address: String,
        unit: String?,
        lat: Double,
        lng: Double,
        geofenceMeters: Int,
        accessNotes: String?,
        planLabel: String,
        status: JobStatus,
        bountyCents: Int,
        bonusCents: Int,
        scheduledForEpochMs: Long?,
        nowEpochMs: Long,
        slaMinutes: Int,
        notes: String,
        acceptedAtEpochMs: Long? = null,
        arrivedAtEpochMs: Long? = null,
        sealedAtEpochMs: Long? = null,
        packetId: String? = null,
    ): Job = baseJob(
        id = id,
        type = type,
        title = title,
        requester = requester,
        site = site(address, unit, lat, lng, geofenceMeters, accessNotes, planLabel, nowEpochMs),
        status = status,
        bountyCents = bountyCents,
        bonusCents = bonusCents,
        nowEpochMs = nowEpochMs,
        scheduledForEpochMs = scheduledForEpochMs,
        slaMinutes = slaMinutes,
        notes = notes,
        acceptedAtEpochMs = acceptedAtEpochMs,
        arrivedAtEpochMs = arrivedAtEpochMs,
        sealedAtEpochMs = sealedAtEpochMs,
        packetId = packetId,
    )

    private fun availableJob(
        id: String,
        type: JobType,
        title: String,
        requester: Requester,
        address: String,
        lat: Double,
        lng: Double,
        geofenceMeters: Int,
        accessNotes: String?,
        planLabel: String,
        bountyCents: Int,
        bonusCents: Int,
        nowEpochMs: Long,
    ): Job = baseJob(
        id = id,
        type = type,
        title = title,
        requester = requester,
        site = site(address, null, lat, lng, geofenceMeters, accessNotes, planLabel, nowEpochMs),
        status = JobStatus.AVAILABLE,
        bountyCents = bountyCents,
        bonusCents = bonusCents,
        nowEpochMs = nowEpochMs,
        scheduledForEpochMs = null,
        slaMinutes = 90,
        notes = type.captureStandard,
        acceptedAtEpochMs = null,
        arrivedAtEpochMs = null,
        sealedAtEpochMs = null,
        packetId = null,
    )

    private fun offeredJob(
        id: String,
        type: JobType,
        title: String,
        requester: Requester,
        address: String,
        lat: Double,
        lng: Double,
        geofenceMeters: Int,
        accessNotes: String?,
        planLabel: String,
        bountyCents: Int,
        bonusCents: Int,
        nowEpochMs: Long,
    ): Job = baseJob(
        id = id,
        type = type,
        title = title,
        requester = requester,
        site = site(address, null, lat, lng, geofenceMeters, accessNotes, planLabel, nowEpochMs),
        status = JobStatus.OFFERED,
        bountyCents = bountyCents,
        bonusCents = bonusCents,
        nowEpochMs = nowEpochMs,
        scheduledForEpochMs = null,
        slaMinutes = 90,
        notes = type.captureStandard,
        acceptedAtEpochMs = null,
        arrivedAtEpochMs = null,
        sealedAtEpochMs = null,
        packetId = null,
    )
}
