package com.odasi.fieldwright.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A GPS reading as the device saw it. `source` keeps simulated positions honest. */
data class GeoPoint(
    val lat: Double,
    val lng: Double,
    val recordedAtEpochMs: Long,
    val accuracyMeters: Float,
    val source: FixSource = FixSource.GPS,
) {
    enum class FixSource { GPS, NETWORK, SIMULATED }
}

object GeoMath {
    private const val EARTH_METERS = 6_371_000.0

    /** Great-circle distance in metres. Haversine, no library, stable on small deltas. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Compass bearing from [a] to [b], degrees clockwise from north. */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun withinMeters(a: GeoPoint, b: GeoPoint, radiusMeters: Double): Boolean =
        distanceMeters(a, b) <= radiusMeters

    /** Human-simple compass label for a bearing. */
    fun compassLabel(bearingDegrees: Double): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((bearingDegrees % 360) / 45.0).toInt() + 0) % 8
        return dirs[idx]
    }

    /** Walking/driving estimate: 22 km/h average for urban field work, floor 2 minutes. */
    fun etaMinutes(distanceMeters: Double): Int =
        ((distanceMeters / 1000.0) / 22.0 * 60.0).toInt().coerceIn(2, 240)
}

data class Requester(
    val org: String,
    val contactName: String?,
    val kind: RequesterKind,
    val billingLabel: String,
)

data class SiteRef(
    val address: String,
    val unit: String?,
    val point: GeoPoint,
    val geofenceRadiusMeters: Int,
    val accessNotes: String?,
    val planLabel: String,
)

data class ChecklistStep(
    val key: String,
    val label: String,
    val hint: String,
    val proofKind: ProofKind,
    val required: Boolean = true,
)

data class Job(
    val id: String,
    val type: JobType,
    val title: String,
    val requester: Requester,
    val site: SiteRef,
    val status: JobStatus,
    val bountyCents: Int,
    val bonusCents: Int,
    val estimatedMinutes: Int,
    val scheduledForEpochMs: Long?,
    val slaMinutes: Int,
    val distanceMeters: Int,
    val etaMinutes: Int,
    val consentScope: String,
    val checklist: List<ChecklistStep>,
    val requiredCredentials: Set<Credential>,
    val demo: Boolean,
    val notes: String,
    val acceptedAtEpochMs: Long? = null,
    val arrivedAtEpochMs: Long? = null,
    val sealedAtEpochMs: Long? = null,
    val packetId: String? = null,
) {
    val payoutCents: Int get() = bountyCents + bonusCents
    val dollarsPerHour: Double? get() = if (estimatedMinutes <= 0) null else payoutCents / 100.0 / (estimatedMinutes / 60.0)
}

/** The workers' side of the marketplace. */
data class Operator(
    val id: String,
    val displayName: String,
    val initials: String,
    val credentialTier: Int,
    val ratingAvg: Double,
    val acceptanceRate: Double,
    val onTimeRate: Double,
    val completedJobs: Int,
    val streakDays: Int,
    val credentials: Set<Credential>,
    val payoutAccountLast4: String,
    val weeklyGoalCents: Int,
    val memberSinceLabel: String,
) {
    val tierName: String
        get() = when (credentialTier) {
            1 -> "Scout"
            2 -> "Inspector"
            3 -> "Lead inspector"
            else -> "Commander"
        }
}

/** Auto-accept is a written policy, not a vibe. The engine evaluates it and reports why. */
data class AutoAcceptPolicy(
    val enabled: Boolean = true,
    val minPayoutCents: Int = 3_000,
    val maxDistanceMeters: Int = 12_000,
    val maxActiveStack: Int = 2,
    val allowedTypes: Set<JobType> = JobType.entries.toSet(),
    val requireCredentialMatch: Boolean = true,
)

data class Availability(
    val isOnline: Boolean = false,
    val updatedAtEpochMs: Long = 0,
    val radiusMeters: Int = 15_000,
    val autoAccept: AutoAcceptPolicy = AutoAcceptPolicy(),
    val areaLabel: String = "Creekside · MUD 14",
)

/** A dispatched, time-boxed offer. Offers are the marketplace heartbeat. */
data class Offer(
    val id: String,
    val job: Job,
    val operatorId: String,
    val payoutCents: Int,
    val surgeMultiplier: Double,
    val offeredAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val score: Double,
    val scoreReasons: List<String>,
    val etaMinutes: Int,
    val autoAcceptEligible: Boolean,
    val status: OfferStatus = OfferStatus.PENDING,
) {
    fun remainingMillis(nowEpochMs: Long): Long = (expiresAtEpochMs - nowEpochMs).coerceAtLeast(0)
}

data class HotZone(
    val label: String,
    val point: GeoPoint,
    val radiusMeters: Int,
    val surgeMultiplier: Double,
    val reason: String,
)

/** Market context the dispatch engine is allowed to read. No hidden inputs. */
data class MarketSnapshot(
    val nowEpochMs: Long,
    val demandIndex: Double,
    val supplyIndex: Double,
    val hotZones: List<HotZone>,
    val daylightLabel: String,
    val weatherLabel: String,
) {
    /** 1.0–1.6. Surge is a *payout* multiplier, disclosed before acceptance. */
    val surgeMultiplier: Double
        get() = (1.0 + (demandIndex - supplyIndex).coerceIn(0.0, 0.6).let { it * 1.0 }).coerceIn(1.0, 1.6)
}

data class ScoredJob(
    val job: Job,
    val score: Double,
    val components: Map<String, Double>,
    val surgeMultiplier: Double,
    val payoutCents: Int,
    val etaMinutes: Int,
    val reasons: List<String>,
)

data class DispatchResult(
    val ranked: List<ScoredJob>,
    val rejected: List<Pair<Job, String>>,
    val marketSurge: Double,
)

data class AutoAcceptDecision(
    val accept: Boolean,
    val reason: String,
)

/** Live work state for an accepted job, owned by the client until server authorisation lands. */
data class ActiveWork(
    val jobId: String,
    val status: JobStatus,
    val acceptedAtEpochMs: Long,
    val arrivedAtEpochMs: Long? = null,
    val startedAtEpochMs: Long? = null,
    val checklist: Map<String, ChecklistResult> = emptyMap(),
    val measurements: List<Measurement> = emptyList(),
    val consent: Consent? = null,
    val media: List<MediaRef> = emptyList(),
) {
    val completedRequiredSteps: Int
        get() = checklist.size
}

data class ChecklistResult(
    val key: String,
    val result: StepResult,
    val note: String,
    val mediaIds: List<String> = emptyList(),
)

/** A calibrated measurement. Scale comes from a reference object, never from hope. */
data class Measurement(
    val id: String,
    val label: String,
    val meters: Double,
    val referenceLabel: String,
    val referenceMeters: Double,
    val pixelSpan: Double,
    val calibratedAtEpochMs: Long,
    val source: MediaSource,
) {
    val feet: Double get() = meters * 3.28084
}
