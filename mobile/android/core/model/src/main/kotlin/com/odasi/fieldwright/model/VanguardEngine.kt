package com.odasi.fieldwright.model

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure domain parity for AUCTUM Vanguard (@auctum/engine):
 *  - Invariant I-1 (Check C-12): Floor tasks & floor payments are NEVER credential-gated.
 *  - Invariant I-5 (Check C-11): Gated commercial tasks return an explainable [ShortestFix].
 *  - H2 2026 IRS mileage netting (76 cents/mile) & 120-day DePIN economic half-life curve.
 */
data class ShortestFix(
    val moduleId: String,
    val lessonTitle: String,
    val minutes: Int,
    val costCents: Int = 0,
)

sealed interface VanguardCredentialDecision {
    data class Allowed(val invariant: String) : VanguardCredentialDecision
    data class Blocked(
        val missingCredential: String,
        val reason: String,
        val shortestFix: ShortestFix,
    ) : VanguardCredentialDecision
}

data class VanguardModuleMeta(
    val code: String,
    val tier: Int,
    val title: String,
    val minutes: Int,
    val grantsCredential: String?,
)

data class ClusterEconomicsResult(
    val grossCents: Int,
    val vehicleCostCents: Int,
    val netCents: Int,
    val totalMinutes: Int,
    val netHourlyCents: Int,
)

data class HalfLifeQuote(
    val ageDays: Int,
    val queryPriceCents: Int,
    val workerRoyaltyCents: Int,
    val homeownerRoyaltyCents: Int,
    val treasuryBurnCents: Int,
    val refreshMultiplier: Double,
)

object VanguardEngine {
    const val IRS_H1_2026_MILEAGE_CENTS = 72.5
    const val IRS_H2_2026_MILEAGE_CENTS = 76.0

    val curriculumCatalog: List<VanguardModuleMeta> = listOf(
        VanguardModuleMeta("T1-ONB", 1, "Identity & Background Verification", 20, "IDENTITY_VERIFIED"),
        VanguardModuleMeta("T1-01", 1, "Community Safety & Senior Care", 30, "SENIOR_SAFE_CARE"),
        VanguardModuleMeta("T1-02", 1, "Vanguard Lens: Basic Photo Documentation", 45, "LENS_BASIC_PHOTO"),
        VanguardModuleMeta("T1-05", 1, "Neutrality Boundary — TREC 531.20 & TX §4102", 25, "TREC_SAFE_HARBOR"),
        VanguardModuleMeta("T2-01", 2, "Counterparty Confirmation Protocol", 60, "COUNTERPARTY_ATTEST_T2"),
        VanguardModuleMeta("T3-01", 3, "Roof Access & OSHA Safety Certification", 180, "ROOF_SAFETY"),
        VanguardModuleMeta("T3-02", 3, "Hail & Wind Damage Documentation", 120, "DART_FFI_INCLINOMETER_T3"),
        VanguardModuleMeta("T3-03", 3, "Drone Operations (FAA Part 107 Prep)", 90, "DRONE_PILOT"),
        VanguardModuleMeta("T3-04", 3, "Moisture & Thermal Imaging Basics", 120, "THERMAL_MOISTURE_T3"),
        VanguardModuleMeta("T4-01", 4, "Attestor Certification (Formal RecordKind: ATTEST)", 480, "VANGUARD_COMMANDER_T4"),
    )

    fun evaluateFloorPayment(): VanguardCredentialDecision.Allowed =
        VanguardCredentialDecision.Allowed(invariant = "I-1")

    fun checkCredentials(
        isFloorAction: Boolean,
        requiredCredentials: Set<String>,
        heldCredentials: Set<String>,
        actionLabel: String = "Assignment",
    ): VanguardCredentialDecision {
        if (isFloorAction || requiredCredentials.isEmpty()) {
            return evaluateFloorPayment()
        }
        val heldUpper = heldCredentials.map { it.uppercase() }.toSet()
        val missing = requiredCredentials.firstOrNull { !heldUpper.contains(it.uppercase()) }
            ?: return VanguardCredentialDecision.Allowed(invariant = "I-5")
        val mod = curriculumCatalog.firstOrNull {
            it.grantsCredential?.equals(missing, ignoreCase = true) == true
        }
        return VanguardCredentialDecision.Blocked(
            missingCredential = missing,
            reason = "$actionLabel requires $missing. Complete the free module below to unlock.",
            shortestFix = ShortestFix(
                moduleId = mod?.code ?: "T2-01",
                lessonTitle = mod?.title ?: "Unlock $missing",
                minutes = mod?.minutes ?: 30,
                costCents = 0,
            ),
        )
    }

    fun computeClusterEconomics(
        bountiesCents: List<Int>,
        taskMinutes: Int,
        miles: Double,
        irsCentsPerMile: Double = IRS_H2_2026_MILEAGE_CENTS,
    ): ClusterEconomicsResult {
        val gross = bountiesCents.sum()
        val vehicleCost = (max(0.0, miles) * irsCentsPerMile).roundToInt()
        val net = gross - vehicleCost
        val driveMinutes = ((max(0.0, miles) / 20.0) * 60.0).roundToInt()
        val totalMinutes = max(15, taskMinutes + driveMinutes)
        val netHourly = ((net.toLong() * 60L) / totalMinutes.toDouble()).roundToInt()
        return ClusterEconomicsResult(gross, vehicleCost, net, totalMinutes, netHourly)
    }

    fun computeEconomicHalfLife(ageDays: Int): HalfLifeQuote {
        val clamped = max(0, ageDays)
        val decay = 0.5.pow(clamped / 120.0)
        val queryPrice = max(250, (1600.0 * decay).roundToInt())
        val worker = (queryPrice * 0.25).roundToInt()
        val homeowner = (queryPrice * 0.10).roundToInt()
        val burn = queryPrice - worker - homeowner
        val mult = if (clamped < 90) 1.0 else {
            val raw = 1.0 + ((minOf(clamped, 365) - 90) / 275.0) * 0.85
            (raw * 100.0).roundToInt() / 100.0
        }
        return HalfLifeQuote(clamped, queryPrice, worker, homeowner, burn, mult)
    }
}
