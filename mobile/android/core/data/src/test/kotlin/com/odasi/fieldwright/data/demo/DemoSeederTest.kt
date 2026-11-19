package com.odasi.fieldwright.data.demo

import com.odasi.fieldwright.data.Fixtures
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.FixedClock
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.PayoutStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records what a seed pass writes without a database (and therefore without Robolectric). */
private class FakeDemoSink : DemoSink {

    val operators = LinkedHashMap<String, Operator>()
    val availability = mutableListOf<Availability>()
    val markets = mutableListOf<MarketSnapshot>()
    val jobs = LinkedHashMap<String, Job>()
    val offers = LinkedHashMap<String, Offer>()
    val ledger = LinkedHashMap<String, LedgerEntry>()
    val payouts = LinkedHashMap<String, PayoutRequest>()
    val packets = LinkedHashMap<String, EvidencePacket>()
    var clearCount: Int = 0

    override suspend fun isSeeded(): Boolean = operators.isNotEmpty()

    override suspend fun clearAll() {
        clearCount++
        operators.clear()
        availability.clear()
        markets.clear()
        jobs.clear()
        offers.clear()
        ledger.clear()
        payouts.clear()
        packets.clear()
    }

    override suspend fun saveOperator(operator: Operator) {
        operators[operator.id] = operator
    }

    override suspend fun saveAvailability(availability: Availability, onlineMinutesToday: Int, nowEpochMs: Long) {
        this.availability += availability
    }

    override suspend fun saveMarket(market: MarketSnapshot, tickIndex: Long, nowEpochMs: Long) {
        markets += market
    }

    override suspend fun saveJobs(jobs: List<Job>) {
        jobs.forEach { this.jobs[it.id] = it }
    }

    override suspend fun saveOffers(offers: List<Offer>) {
        offers.forEach { this.offers[it.id] = it }
    }

    override suspend fun saveLedger(entries: List<LedgerEntry>) {
        entries.forEach { ledger[it.id] = it }
    }

    override suspend fun savePayouts(payouts: List<PayoutRequest>) {
        payouts.forEach { this.payouts[it.id] = it }
    }

    override suspend fun savePacket(packet: EvidencePacket, merkleTreeSize: Int, merkleRoot: String?, merkleIndex: Int) {
        packets[packet.id] = packet
    }
}

/**
 * Demo seeding idempotency: seeding twice must not duplicate a single row, and the explicit
 * reset must wipe and rebuild exactly the same book. The packet factory is `null` here so
 * this test stays pure data (the sealed demo packets are covered by [DemoPacketFactory]).
 */
class DemoSeederTest {

    private val clock: Clock = FixedClock(Fixtures.NOW) as Clock

    @Test
    fun `seeding twice writes the same rows only once`() = kotlinx.coroutines.test.runTest {
        val sink = FakeDemoSink()
        val seeder = DemoSeeder(sink = sink, packetFactory = null, clock = clock)

        assertTrue(seeder.seed())
        assertEquals(1, sink.operators.size)
        assertEquals(10, sink.jobs.size)
        assertEquals(2, sink.offers.size)
        assertEquals(5, sink.ledger.size)
        assertEquals(1, sink.payouts.size)
        assertEquals(0, sink.clearCount)

        val jobCount = sink.jobs.size
        assertFalse(seeder.seed())
        assertEquals(jobCount, sink.jobs.size, "a second seed must not duplicate rows")
        assertEquals(0, sink.clearCount)
    }

    @Test
    fun `an explicit reset wipes and rebuilds exactly the sample book`() = kotlinx.coroutines.test.runTest {
        val sink = FakeDemoSink()
        val seeder = DemoSeeder(sink = sink, packetFactory = null, clock = clock)
        seeder.seed()
        val firstBook = sink.jobs.keys.toList()

        assertTrue(seeder.seed(force = true))
        assertEquals(1, sink.clearCount)
        assertEquals(firstBook, sink.jobs.keys.toList())
        assertEquals(10, sink.jobs.size)
        assertTrue(sink.jobs.values.all { it.demo })
        assertTrue(sink.offers.values.all { it.job.demo })
    }

    @Test
    fun `sample book inventory matches the supplied Fieldwright copy`() {
        val now = Fixtures.NOW
        val jobs = DemoCatalog.jobs(now).associateBy { it.id }

        val panel = jobs.getValue("FW-0994")
        assertEquals("Panel T-14 is set", panel.title)
        assertEquals(JobType.ROOF_HAIL, panel.type)
        assertEquals(42_000, panel.payoutCents)
        assertEquals("Lamar Tilt Partners", panel.requester.org)
        assertEquals(JobStatus.SUBMITTED, panel.status)
        assertEquals("FW-0994", panel.packetId)
        assertTrue(panel.demo)

        val rpz = jobs.getValue("FW-1012")
        assertEquals("Annual RPZ test", rpz.title)
        assertEquals(JobType.RPZ_TEST, rpz.type)
        assertEquals(9_500, rpz.payoutCents)
        assertEquals("Creekside MUD", rpz.requester.org)
        assertEquals(JobStatus.PAID, rpz.status)
        assertTrue(rpz.demo)

        val active = jobs.getValue("FW-1048")
        assertEquals("Streak on the tank, feeder 14", active.title)
        assertEquals(JobStatus.ACCEPTED, active.status)

        val scheduled = jobs.getValue("FW-1051")
        assertEquals("Hail on the south slope", scheduled.title)
        assertEquals(JobStatus.SCHEDULED, scheduled.status)
        assertTrue(scheduled.scheduledForEpochMs!! > now)

        val available = jobs.values.filter { it.status == JobStatus.AVAILABLE }
        assertEquals(4, available.size)
        assertEquals(4, available.map { it.type }.distinct().size, "open jobs must span four job families")

        // The consent subject on the roofing packet comes from the sample copy.
        val fixture = DemoCatalog.packetFixture("FW-0994", now)!!
        assertEquals("Chris Adeyemi", fixture.consentName)
        assertEquals(4, fixture.photoLabels.size)
        assertFalse(fixture.paid)
        assertTrue(DemoCatalog.packetFixture("FW-1012", now)!!.paid)
    }

    @Test
    fun `operator, offers, ledger and payout inventories are the sample book`() {
        val now = Fixtures.NOW
        val operator = DemoCatalog.operator()
        assertEquals("Elena Voss", operator.displayName)
        assertEquals("EV", operator.initials)
        assertEquals(3, operator.credentialTier)
        assertEquals("Lead inspector", operator.tierName)
        assertEquals(4.9, operator.ratingAvg)
        assertEquals(38, operator.completedJobs)

        val offers = DemoCatalog.offers(now)
        assertEquals(2, offers.size)
        assertTrue(offers.all { it.status == com.odasi.fieldwright.model.OfferStatus.PENDING })
        assertTrue(offers.all { it.job.demo })

        val ledger = DemoCatalog.ledger(now)
        assertTrue(ledger.all { it.demo })
        assertTrue(ledger.any { it.kind == com.odasi.fieldwright.model.LedgerKind.EARNING })
        assertTrue(ledger.any { it.kind == com.odasi.fieldwright.model.LedgerKind.BONUS })

        val payouts = DemoCatalog.payouts(now)
        assertEquals(1, payouts.size)
        assertEquals(PayoutStatus.PAID, payouts.single().status)
        assertEquals(38_000, payouts.single().amountCents)
    }
}
