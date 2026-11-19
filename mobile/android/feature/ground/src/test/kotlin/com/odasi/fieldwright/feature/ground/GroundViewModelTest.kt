package com.odasi.fieldwright.feature.ground

import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ground ViewModel state tests. Every dependency is a recording fake, the clock is injected,
 * so the countdown/expiry behaviour is exercised deterministically without an emulator or a
 * real timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroundViewModelTest {

    private lateinit var dispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `availability toggle writes through the repository and reflects availability`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.operator.availabilityFlow.value = Availability(isOnline = false, radiusMeters = 15_000)
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isOnline)
        assertFalse(viewModel.state.value.loading)

        viewModel.toggleOnline()
        advanceUntilIdle()

        assertEquals(listOf(true), fakes.operator.setOnlineCalls.map { it.first })
        assertTrue(viewModel.state.value.isOnline)
        assertNotNull(viewModel.state.value.actionMessage)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `accepting an offer marks it accepted and confirms inline`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val offer = TestBook.offer()
        fakes.dispatch.offersFlow.value = listOf(offer)
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.offers.size)
        assertEquals(45_000L, viewModel.state.value.offers.first().remainingMillis)

        viewModel.acceptOffer(offer.id)
        advanceUntilIdle()

        assertEquals(listOf(offer.id), fakes.dispatch.acceptedOfferIds)
        assertNull(viewModel.state.value.acceptingOfferId)
        assertNull(viewModel.state.value.errorMessage)
        assertNotNull(viewModel.state.value.actionMessage)
    }

    @Test
    fun `a refused accept surfaces an inline error and never marks work accepted`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val offer = TestBook.offer()
        fakes.dispatch.offersFlow.value = listOf(offer)
        fakes.dispatch.acceptResult = { Result.failure(IllegalStateException("Offer expired")) }
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.acceptOffer(offer.id)
        advanceUntilIdle()

        assertEquals("Offer expired", viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.acceptingOfferId)
        assertNull(viewModel.state.value.actionMessage)
        assertEquals(1, viewModel.state.value.offers.size)
    }

    @Test
    fun `passing an offer records a human reason and removes it from the list`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val offer = TestBook.offer()
        fakes.dispatch.offersFlow.value = listOf(offer)
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.declineOffer(offer.id)
        advanceUntilIdle()

        assertEquals(1, fakes.dispatch.declinedOfferIds.size)
        val (declinedId, reason) = fakes.dispatch.declinedOfferIds.single()
        assertEquals(offer.id, declinedId)
        assertTrue(reason.isNotBlank())
        assertTrue(viewModel.state.value.offers.isEmpty())
    }

    @Test
    fun `the countdown is recomputed each tick and an expired offer asks dispatch to expire it`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        var clock = TestBook.NOW
        val offer = TestBook.offer(expiresAtEpochMs = TestBook.NOW + 1_000L)
        fakes.dispatch.offersFlow.value = listOf(offer)
        val viewModel = GroundViewModel(fakes, now = { clock })
        advanceUntilIdle()

        viewModel.onTick()
        advanceUntilIdle()
        assertEquals(1_000L, viewModel.state.value.offers.first().remainingMillis)
        assertTrue(fakes.dispatch.expireCalls.isEmpty())

        clock += 2_000L
        viewModel.onTick()
        advanceUntilIdle()

        assertEquals(0L, viewModel.state.value.offers.first().remainingMillis)
        assertEquals(listOf(clock), fakes.dispatch.expireCalls)
        assertEquals(clock, viewModel.state.value.nowEpochMs)
    }

    @Test
    fun `ticking with no offers does not ask dispatch to expire anything`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.onTick()
        advanceUntilIdle()

        assertTrue(fakes.dispatch.expireCalls.isEmpty())
    }

    @Test
    fun `refresh failure is inline and retryable, success reports the ranked count`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.dispatch.refreshResult = Result.failure(IllegalStateException("No network"))
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("No network", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.refreshing)
        assertEquals(1, fakes.dispatch.refreshFixes.size)

        fakes.dispatch.refreshResult = Result.success(3)
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.actionMessage!!.contains("3"))
    }

    @Test
    fun `next up prefers the earliest lifecycle stage and every map job gets a pin`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.activeJobsFlow.value = listOf(
            TestBook.job(id = "job-en-route", status = JobStatus.EN_ROUTE, acceptedAtEpochMs = TestBook.NOW - 600_000L),
            TestBook.job(id = "job-accepted", status = JobStatus.ACCEPTED, acceptedAtEpochMs = TestBook.NOW - 60_000L),
        )
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        assertEquals("job-accepted", viewModel.state.value.nextUp?.id)
        assertEquals(2, viewModel.state.value.mapPins.size)
        assertNotNull(viewModel.state.value.operatorFix)
    }

    @Test
    fun `offline sync phase is surfaced as an offline state with a queued count`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.sync.syncFlow.value = SyncState(
            phase = SyncPhase.WAITING_FOR_NETWORK,
            queued = 3,
            inFlight = 0,
            deadLetters = 0,
            lastSyncedAtEpochMs = TestBook.NOW - 60_000L,
            lastError = "No network",
        )
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isOffline)
        assertEquals(3, viewModel.state.value.queuedMutations)

        viewModel.retrySync()
        advanceUntilIdle()
        assertEquals(1, fakes.sync.flushCalls.get())
        assertNotNull(viewModel.state.value.actionMessage)
    }

    @Test
    fun `radius and auto-accept write through the repository and radius is capped`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.setRadius(25_000)
        advanceUntilIdle()
        assertEquals(listOf(25_000), fakes.operator.radiusCalls)
        assertEquals(25_000, viewModel.state.value.availability.radiusMeters)

        viewModel.setRadius(99_000)
        advanceUntilIdle()
        assertEquals(25_000, fakes.operator.radiusCalls.last())

        viewModel.setAutoAccept(false)
        advanceUntilIdle()
        assertFalse(fakes.operator.autoAcceptCalls.last().enabled)
        assertFalse(viewModel.state.value.availability.autoAccept.enabled)
    }

    @Test
    fun `selecting a pin resolves the matching offer and active job`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val offer = TestBook.offer(id = "offer-1", job = TestBook.job(id = "job-1"))
        val active = TestBook.job(id = "job-2", status = JobStatus.EN_ROUTE, acceptedAtEpochMs = TestBook.NOW)
        fakes.dispatch.offersFlow.value = listOf(offer)
        fakes.jobs.activeJobsFlow.value = listOf(active)
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.selectPin("job-1")
        assertEquals("offer-1", viewModel.state.value.selectedOffer?.offer?.id)
        assertNull(viewModel.state.value.selectedActiveJob)

        viewModel.selectPin("job-2")
        assertNull(viewModel.state.value.selectedOffer)
        assertEquals("job-2", viewModel.state.value.selectedActiveJob?.id)

        viewModel.selectPin(null)
        assertNull(viewModel.state.value.selectedPinJobId)
    }

    @Test
    fun `demo jobs set the sample-book flag`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.dispatch.offersFlow.value = listOf(TestBook.offer(job = TestBook.job(demo = true)))
        val viewModel = GroundViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        assertTrue(viewModel.state.value.demo)
    }
}
