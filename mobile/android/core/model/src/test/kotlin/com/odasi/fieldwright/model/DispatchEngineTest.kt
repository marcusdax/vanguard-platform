package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispatch invariants: the marketplace may rank aggressively, but it may not be vague
 * about exclusions and it may not invent a payout. Every rejection must carry a reason,
 * and every score component is disclosed in [DispatchEngine.WEIGHTS].
 */
class DispatchEngineTest {

    private val operator = Fixtures.operator()
    private val availability = Fixtures.availability()
    private val market = Fixtures.market()

    private fun rank(
        jobs: List<Job>,
        activeCount: Int = 0,
        availabilityOverride: Availability = availability,
        marketOverride: MarketSnapshot = market,
    ): DispatchResult = DispatchEngine.rank(
        jobs = jobs,
        operator = operator,
        availability = availabilityOverride,
        operatorPoint = Fixtures.operatorPoint,
        activeCount = activeCount,
        market = marketOverride,
    )

    @Test
    fun `weights are a disclosed distribution that sums to one`() {
        assertEquals(1.0, DispatchEngine.WEIGHTS.values.sum(), 1e-9)
        assertEquals(
            setOf("proximity", "payout", "urgency", "credential", "reliability", "fairness"),
            DispatchEngine.WEIGHTS.keys,
        )
    }

    @Test
    fun `the closer job of equal payout ranks first`() {
        val near = Fixtures.job(id = "near", distanceMeters = 1_500)
        val far = Fixtures.job(id = "far", distanceMeters = 9_000)

        val result = rank(listOf(far, near))

        assertEquals(2, result.ranked.size)
        assertEquals("near", result.ranked[0].job.id)
        assertEquals("far", result.ranked[1].job.id)
        assertTrue(result.ranked[0].score > result.ranked[1].score)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `every scored job exposes the same auditable components`() {
        val scored = rank(listOf(Fixtures.job())).ranked.single()

        assertEquals(DispatchEngine.WEIGHTS.keys, scored.components.keys)
        assertTrue("score must stay in 0..1 but was ${scored.score}", scored.score in 0.0..1.0)
        assertTrue(scored.reasons.isNotEmpty())
        assertTrue(scored.reasons.all { it.isNotBlank() })
    }

    @Test
    fun `ranking is deterministic for the same market and operator`() {
        val jobs = listOf(
            Fixtures.job(id = "a", distanceMeters = 2_000),
            Fixtures.job(id = "b", distanceMeters = 2_000),
            Fixtures.job(id = "c", distanceMeters = 6_000),
        )

        val first = rank(jobs)
        val second = rank(jobs)

        assertEquals(
            first.ranked.map { it.job.id to it.score },
            second.ranked.map { it.job.id to it.score },
        )
    }

    @Test
    fun `offline rejects every job with a human reason and ranks nothing`() {
        val result = rank(
            jobs = listOf(Fixtures.job(id = "a"), Fixtures.job(id = "b")),
            availabilityOverride = Fixtures.availability(isOnline = false),
        )

        assertTrue(result.ranked.isEmpty())
        assertEquals(2, result.rejected.size)
        assertTrue(result.rejected.all { it.second.contains("Offline") })
        assertTrue(result.rejected.all { it.second.isNotBlank() })
    }

    @Test
    fun `the active-job cap rejects with the cap in the reason`() {
        val result = rank(listOf(Fixtures.job()), activeCount = DispatchEngine.MAX_ACTIVE_JOBS)

        assertTrue(result.ranked.isEmpty())
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().second.contains(DispatchEngine.MAX_ACTIVE_JOBS.toString()))
        assertTrue(result.rejected.single().second.contains("active jobs"))
    }

    @Test
    fun `a credential mismatch is rejected, never silently down-scored`() {
        val operatorWithoutRoof = Fixtures.operator(credentials = setOf(Credential.PROPERTY_INSPECTOR))
        val result = DispatchEngine.rank(
            jobs = listOf(Fixtures.job(type = JobType.ROOF_HAIL)),
            operator = operatorWithoutRoof,
            availability = availability,
            operatorPoint = Fixtures.operatorPoint,
            activeCount = 0,
            market = market,
        )

        assertTrue(result.ranked.isEmpty())
        val reason = result.rejected.single().second
        assertTrue(reason.contains("Requires"))
        assertTrue(reason.contains(Credential.ROOF_SAFETY.label))
    }

    @Test
    fun `credential matching stays a ranking invariant even when the auto-accept policy waives it`() {
        val permissive = Fixtures.availability(
            autoAccept = AutoAcceptPolicy(requireCredentialMatch = false),
        )
        val operatorWithoutRoof = Fixtures.operator(credentials = emptySet())

        val result = DispatchEngine.rank(
            jobs = listOf(Fixtures.job(type = JobType.ROOF_HAIL)),
            operator = operatorWithoutRoof,
            availability = permissive,
            operatorPoint = Fixtures.operatorPoint,
            activeCount = 0,
            market = market,
        )

        assertTrue(result.ranked.isEmpty())
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `a job outside the working radius is rejected with both distances`() {
        val result = rank(
            jobs = listOf(Fixtures.job(distanceMeters = 30_000)),
            availabilityOverride = Fixtures.availability(radiusMeters = 15_000),
        )

        val reason = result.rejected.single().second
        assertTrue(reason.contains("outside"))
        assertTrue(reason.contains("15.0 km"))
        assertTrue(reason.contains("30.0 km"))
    }

    @Test
    fun `a job that is not open for offers is rejected with its status`() {
        val result = rank(
            jobs = listOf(Fixtures.job(id = "busy", status = JobStatus.IN_PROGRESS)),
        )

        assertTrue(result.ranked.isEmpty())
        val reason = result.rejected.single().second
        assertTrue(reason.contains("not open for offers"))
        assertTrue(reason.contains(JobStatus.IN_PROGRESS.label.lowercase()))
    }

    @Test
    fun `a payout below the floor is rejected before it can be offered`() {
        val result = rank(
            jobs = listOf(Fixtures.job(type = JobType.METER_CLUSTER, bountyCents = 2_000)),
        )

        assertTrue(result.ranked.isEmpty())
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().second.contains("floor"))
        assertTrue(result.rejected.single().second.contains(Money.format(DispatchEngine.MIN_PAYOUT_FLOOR_CENTS)))
    }

    @Test
    fun `surge can lift a low bounty above the floor and is disclosed`() {
        val job = Fixtures.job(type = JobType.METER_CLUSTER, bountyCents = 2_000)
        val surgedMarket = Fixtures.market(hotZones = listOf(Fixtures.hotZone(surge = 1.6, radiusMeters = 5_000)))

        val scored = rank(listOf(job), marketOverride = surgedMarket).ranked.single()

        assertEquals(3_200, scored.payoutCents)
        assertEquals(1.6, scored.surgeMultiplier, 1e-9)
        assertTrue(scored.reasons.any { it.contains("surge") })
    }

    @Test
    fun `surge is clamped to the disclosed 1_0 to 1_6 band`() {
        val job = Fixtures.job(type = JobType.METER_CLUSTER)
        // The site must sit inside the hot zone, or the engine correctly falls back to the
        // market-wide multiplier — that fallback is what the card discloses too.
        val runaway = Fixtures.market(hotZones = listOf(Fixtures.hotZone(surge = 3.0, radiusMeters = 5_000)))

        val scored = rank(listOf(job), marketOverride = runaway).ranked.single()

        assertEquals(1.6, scored.surgeMultiplier, 1e-9)
    }

    @Test
    fun `a hot zone over the site wins over the market-wide surge`() {
        val job = Fixtures.job(type = JobType.METER_CLUSTER)
        val hot = Fixtures.market(
            demandIndex = 1.0,
            supplyIndex = 0.0,
            hotZones = listOf(Fixtures.hotZone(surge = 1.2, radiusMeters = 5_000)),
        )

        val scored = rank(listOf(job), marketOverride = hot).ranked.single()

        assertEquals(1.2, scored.surgeMultiplier, 1e-9)
    }

    @Test
    fun `market surge never leaves the disclosed band`() {
        val result = rank(
            jobs = listOf(Fixtures.job()),
            marketOverride = Fixtures.market(demandIndex = 9.0, supplyIndex = -4.0),
        )

        assertEquals(1.6, result.marketSurge, 1e-9)
    }

    @Test
    fun `offers live for exactly the advertised 45 seconds`() {
        assertEquals(45_000L, DispatchEngine.OFFER_TTL_MS)
        assertEquals(NOW + 45_000L, DispatchEngine.expiresAt(NOW))
        assertEquals(45_000L, DispatchEngine.expiresAt(NOW) - NOW)
    }

    @Test
    fun `offered payout applies the disclosed surge and rounds once`() {
        assertEquals(14_250, DispatchEngine.offeredPayoutCents(Fixtures.job(bountyCents = 9_500), 1.5))
        assertEquals(2_800, DispatchEngine.offeredPayoutCents(Fixtures.job(type = JobType.METER_CLUSTER), 1.0))
        assertEquals(31_500, DispatchEngine.offeredPayoutCents(Fixtures.job(bountyCents = 21_000), 1.5))
    }

    @Test
    fun `scored jobs carry a travel estimate`() {
        val scored = rank(listOf(Fixtures.job(distanceMeters = 11_000))).ranked.single()

        assertTrue(scored.etaMinutes >= 2)
        assertTrue(scored.reasons.any { it.contains("km") })
    }

    // ---- auto-accept: hard invariants ----

    @Test
    fun `an eligible offer inside the policy is auto-accepted with a reason`() {
        val decision = DispatchEngine.autoAccept(Fixtures.offer(), activeCount = 1)

        assertTrue(decision.accept)
        assertTrue(decision.reason.contains("Auto-accepted"))
    }

    @Test
    fun `auto-accept refuses at the active-job cap even when the offer is flagged eligible`() {
        val decision = DispatchEngine.autoAccept(Fixtures.offer(), activeCount = 3)

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("limit 3"))
    }

    @Test
    fun `auto-accept refuses a closed offer`() {
        val decision = DispatchEngine.autoAccept(
            Fixtures.offer(status = OfferStatus.ACCEPTED),
            activeCount = 0,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("already accepted"))
    }

    @Test
    fun `auto-accept refuses a payout below the floor even if flagged eligible`() {
        val decision = DispatchEngine.autoAccept(
            Fixtures.offer(payoutCents = 1_000),
            activeCount = 0,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("floor"))
    }

    @Test
    fun `auto-accept refuses a malformed offer window`() {
        val decision = DispatchEngine.autoAccept(
            Fixtures.offer(windowMs = 90_000),
            activeCount = 0,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("malformed"))
    }

    @Test
    fun `auto-accept refuses an undisclosed surge`() {
        val decision = DispatchEngine.autoAccept(
            Fixtures.offer(surgeMultiplier = 2.0),
            activeCount = 0,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("surge"))
    }

    @Test
    fun `auto-accept explains a policy miss instead of silently passing`() {
        val decision = DispatchEngine.autoAccept(
            Fixtures.offer(autoAcceptEligible = false),
            activeCount = 0,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("policy"))
        assertTrue(decision.reason.isNotBlank())
    }

    // ---- auto-accept: the written policy itself ----

    @Test
    fun `policy accepts a job that satisfies every limit`() {
        val decision = DispatchEngine.policyDecision(
            offer = Fixtures.offer(),
            activeCount = 1,
            policy = AutoAcceptPolicy(),
            operator = operator,
        )

        assertTrue(decision.accept)
        assertTrue(decision.reason.contains("Auto-accepted"))
    }

    @Test
    fun `policy respects being switched off`() {
        val decision = DispatchEngine.policyDecision(
            offer = Fixtures.offer(),
            activeCount = 0,
            policy = AutoAcceptPolicy(enabled = false),
            operator = operator,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("Auto-accept is off"))
    }

    @Test
    fun `policy enforces its own stack limit`() {
        val decision = DispatchEngine.policyDecision(
            offer = Fixtures.offer(),
            activeCount = 2,
            policy = AutoAcceptPolicy(maxActiveStack = 2),
            operator = operator,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("stacks at most 2"))
    }

    @Test
    fun `policy enforces its allowed job types`() {
        val decision = DispatchEngine.policyDecision(
            offer = Fixtures.offer(job = Fixtures.job(type = JobType.RPZ_TEST)),
            activeCount = 0,
            policy = AutoAcceptPolicy(allowedTypes = setOf(JobType.METER_CLUSTER)),
            operator = operator,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("not in your auto-accept job types"))
    }

    @Test
    fun `policy enforces its payout minimum`() {
        val decision = DispatchEngine.policyDecision(
            offer = Fixtures.offer(payoutCents = 9_500),
            activeCount = 0,
            policy = AutoAcceptPolicy(minPayoutCents = 20_000),
            operator = operator,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("auto-accept minimum"))
    }

    @Test
    fun `policy enforces its distance maximum`() {
        val farJob = Fixtures.job(distanceMeters = 20_000)
        val decision = DispatchEngine.policyDecision(
            offer = Fixtures.offer(job = farJob),
            activeCount = 0,
            policy = AutoAcceptPolicy(maxDistanceMeters = 12_000),
            operator = operator,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("auto-accept distance"))
    }

    @Test
    fun `policy refuses a job whose credential the operator lacks`() {
        val operatorWithoutRoof = Fixtures.operator(credentials = emptySet())
        val decision = DispatchEngine.policyDecision(
            offer = Fixtures.offer(job = Fixtures.job(type = JobType.ROOF_HAIL)),
            activeCount = 0,
            policy = AutoAcceptPolicy(),
            operator = operatorWithoutRoof,
        )

        assertFalse(decision.accept)
        assertTrue(decision.reason.contains("missing"))
        assertTrue(decision.reason.contains(Credential.ROOF_SAFETY.label))
    }

    @Test
    fun `rejected and scored sets never overlap`() {
        val jobs = listOf(
            Fixtures.job(id = "ok", distanceMeters = 2_000),
            Fixtures.job(id = "too-far", distanceMeters = 30_000),
            Fixtures.job(id = "not-open", status = JobStatus.PAID),
        )

        val result = rank(jobs)

        val rankedIds = result.ranked.map { it.job.id }.toSet()
        val rejectedIds = result.rejected.map { it.first.id }.toSet()
        assertEquals(setOf("ok"), rankedIds)
        assertEquals(setOf("too-far", "not-open"), rejectedIds)
        assertTrue(rankedIds.intersect(rejectedIds).isEmpty())
    }

    @Test
    fun `payout score is capped so a single whale offer cannot dominate forever`() {
        val small = rank(listOf(Fixtures.job(id = "small", bountyCents = 9_500))).ranked.single()
        val large = rank(listOf(Fixtures.job(id = "large", bountyCents = 500_000))).ranked.single()

        assertEquals(1.0, large.components.getValue("payout"), 1e-9)
        assertTrue(large.components.getValue("payout") > small.components.getValue("payout"))
        assertNotEquals(small.score, large.score)
    }
}
