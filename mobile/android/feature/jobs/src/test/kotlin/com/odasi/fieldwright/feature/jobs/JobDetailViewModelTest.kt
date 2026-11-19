package com.odasi.fieldwright.feature.jobs

import com.odasi.fieldwright.data.ArrivalCheck
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.SealState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Job detail ViewModel tests. The rules under test are the ones a screen must never guess:
 * which action a status allows, that arrival is a geofence check against a real fix, that a
 * miss requires a recorded override reason, and that a packet is never opened before it
 * exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobDetailViewModelTest {

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

    private fun jobOf(status: JobStatus): Job = TestBook.job(
        id = JOB_ID,
        status = status,
        acceptedAtEpochMs = if (status.ordinal >= JobStatus.ACCEPTED.ordinal) TestBook.NOW - 30 * 60_000L else null,
    )

    private fun packet(jobId: String, id: String = PACKET_ID): EvidencePacket = EvidencePacket(
        id = id,
        jobId = jobId,
        jobType = JobType.ROOF_HAIL,
        operatorId = TestBook.operator.id,
        title = "Panel T-14 hail assessment",
        requesterLabel = TestBook.requester.org,
        site = TestBook.site,
        consent = null,
        badges = emptyList(),
        tier = EvidenceTier.FIELD_CAPTURED,
        seal = SealState.EARNED,
        records = emptyList(),
        media = emptyList(),
        checklist = emptyList(),
        gates = emptyList(),
        measurements = emptyList(),
        notes = "",
        demo = true,
        createdAtEpochMs = TestBook.NOW,
        sealedAtEpochMs = TestBook.NOW,
        merkleLeaf = null,
        merkleProof = emptyList(),
        asOfEpochMs = TestBook.NOW,
        signatureBase64 = null,
    )

    private fun insideGeofence(): ArrivalCheck = ArrivalCheck(
        accepted = true,
        insideGeofence = true,
        distanceMeters = 14.0,
        accuracyMeters = 9f,
        detail = "Inside the site geofence.",
        overrideRequired = false,
    )

    private fun outsideGeofence(): ArrivalCheck = ArrivalCheck(
        accepted = false,
        insideGeofence = false,
        distanceMeters = 340.0,
        accuracyMeters = 12f,
        detail = "340 m from the pin, outside the 120 m geofence.",
        overrideRequired = true,
    )

    @Test
    fun `every lifecycle status maps to exactly one action`() {
        assertEquals(JobDetailAction.ACCEPT, actionFor(JobStatus.AVAILABLE))
        assertEquals(JobDetailAction.ACCEPT, actionFor(JobStatus.OFFERED))
        assertEquals(JobDetailAction.EN_ROUTE, actionFor(JobStatus.ACCEPTED))
        assertEquals(JobDetailAction.CHECK_ARRIVAL, actionFor(JobStatus.EN_ROUTE))
        assertEquals(JobDetailAction.START_CAPTURE, actionFor(JobStatus.ON_SITE))
        assertEquals(JobDetailAction.CONTINUE_CAPTURE, actionFor(JobStatus.IN_PROGRESS))
        assertEquals(JobDetailAction.OPEN_PACKET, actionFor(JobStatus.PACKET_READY))
        assertEquals(JobDetailAction.OPEN_PACKET, actionFor(JobStatus.SEALED))
        assertEquals(JobDetailAction.NONE, actionFor(JobStatus.PAID))
        assertEquals(JobDetailAction.NONE, actionFor(JobStatus.UNDER_REVIEW))
        assertEquals(JobDetailAction.NONE, actionFor(JobStatus.CANCELLED))
        assertEquals("I've arrived", labelFor(JobDetailAction.CHECK_ARRIVAL))
    }

    @Test
    fun `marking en route writes through the repository and advances the action`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow<Job?>(jobOf(JobStatus.ACCEPTED))
        val viewModel = JobDetailViewModel(fakes, jobId = JOB_ID, now = { TestBook.NOW })
        advanceUntilIdle()

        assertEquals(JobDetailAction.EN_ROUTE, viewModel.state.value.action)
        assertFalse(viewModel.state.value.loading)

        viewModel.markEnRoute()
        advanceUntilIdle()

        assertEquals(listOf(JOB_ID), fakes.jobs.enRouteCalls)
        assertEquals(JobStatus.EN_ROUTE, viewModel.state.value.job?.status)
        assertEquals(JobDetailAction.CHECK_ARRIVAL, viewModel.state.value.action)
        assertNotNull(viewModel.state.value.actionMessage)
    }

    @Test
    fun `arrival inside the geofence confirms without an override`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow<Job?>(jobOf(JobStatus.EN_ROUTE))
        fakes.jobs.arrivalCheck = { _, _ -> Result.success(insideGeofence()) }
        val viewModel = JobDetailViewModel(
            repos = fakes,
            jobId = JOB_ID,
            now = { TestBook.NOW },
            fixProvider = { TestBook.point(lat = 30.2712, lng = -97.7402) },
        )
        advanceUntilIdle()

        viewModel.checkArrival()
        advanceUntilIdle()

        assertEquals(1, fakes.jobs.checkArrivalCalls.size)
        assertTrue(viewModel.state.value.arrival!!.insideGeofence)
        assertFalse(viewModel.state.value.overrideRequired)
        assertTrue(viewModel.state.value.arrivalSheetVisible)

        viewModel.confirmArrival()
        advanceUntilIdle()

        assertEquals(1, fakes.jobs.confirmArrivalCalls.size)
        assertNull(fakes.jobs.confirmArrivalCalls.single().third)
        assertNull(viewModel.state.value.arrival)
        assertEquals(JobStatus.ON_SITE, viewModel.state.value.job?.status)
        assertNotNull(viewModel.state.value.actionMessage)
    }

    @Test
    fun `arrival outside the geofence requires a recorded override reason`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow<Job?>(jobOf(JobStatus.EN_ROUTE))
        fakes.jobs.arrivalCheck = { _, _ -> Result.success(outsideGeofence()) }
        val viewModel = JobDetailViewModel(
            repos = fakes,
            jobId = JOB_ID,
            now = { TestBook.NOW },
            fixProvider = { TestBook.point(lat = 30.2712, lng = -97.7402) },
        )
        advanceUntilIdle()

        viewModel.checkArrival()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.overrideRequired)

        viewModel.confirmArrival()
        advanceUntilIdle()

        assertTrue(fakes.jobs.confirmArrivalCalls.isEmpty())
        assertNotNull(viewModel.state.value.errorMessage)
        assertNotNull(viewModel.state.value.arrival)

        viewModel.setOverrideReason("Pin sits on the roof; I am at the foot of the ladder.")
        viewModel.confirmArrival()
        advanceUntilIdle()

        assertEquals(1, fakes.jobs.confirmArrivalCalls.size)
        assertEquals(
            "Pin sits on the roof; I am at the foot of the ladder.",
            fakes.jobs.confirmArrivalCalls.single().third,
        )
        assertNull(viewModel.state.value.arrival)
        assertTrue(viewModel.state.value.actionMessage!!.contains("override", ignoreCase = true))
    }

    @Test
    fun `arrival without a trusted fix is refused and never calls the repository`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow<Job?>(jobOf(JobStatus.EN_ROUTE))
        val viewModel = JobDetailViewModel(fakes, jobId = JOB_ID, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.checkArrival()
        advanceUntilIdle()

        assertTrue(fakes.jobs.checkArrivalCalls.isEmpty())
        assertNull(viewModel.state.value.arrival)
        assertNotNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `start capture moves the job and requests capture navigation`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow<Job?>(jobOf(JobStatus.ON_SITE))
        val viewModel = JobDetailViewModel(fakes, jobId = JOB_ID, now = { TestBook.NOW })
        advanceUntilIdle()

        assertEquals(JobDetailAction.START_CAPTURE, viewModel.state.value.action)
        viewModel.startCapture()
        advanceUntilIdle()

        assertEquals(listOf(JOB_ID), fakes.jobs.startCaptureCalls)
        assertTrue(viewModel.state.value.navigationTarget is JobDetailNavigation.Capture)

        viewModel.consumeNavigation()
        assertNull(viewModel.state.value.navigationTarget)
    }

    @Test
    fun `open packet needs a packet on file`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow<Job?>(jobOf(JobStatus.PACKET_READY))
        val viewModel = JobDetailViewModel(fakes, jobId = JOB_ID, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.openPacket()
        advanceUntilIdle()

        assertNull(viewModel.state.value.navigationTarget)
        assertNotNull(viewModel.state.value.errorMessage)

        fakes.vault.packetsFlow.value = listOf(packet(JOB_ID))
        advanceUntilIdle()
        assertEquals(PACKET_ID, viewModel.state.value.packetId)

        viewModel.openPacket()
        advanceUntilIdle()

        val target = viewModel.state.value.navigationTarget
        assertTrue(target is JobDetailNavigation.Packet)
        assertEquals(PACKET_ID, (target as JobDetailNavigation.Packet).packetId)
    }

    @Test
    fun `accepting the detail offer succeeds and clears the offer`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val job = jobOf(JobStatus.AVAILABLE)
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow(job)
        fakes.dispatch.offersFlow.value = listOf(TestBook.offer(id = OFFER_ID, job = job))
        val viewModel = JobDetailViewModel(fakes, jobId = JOB_ID, now = { TestBook.NOW })
        advanceUntilIdle()

        assertTrue(viewModel.state.value.canAccept)
        viewModel.acceptOffer()
        advanceUntilIdle()

        assertEquals(listOf(OFFER_ID), fakes.dispatch.acceptedOfferIds)
        assertNull(viewModel.state.value.offer)
        assertNull(viewModel.state.value.errorMessage)
        assertNotNull(viewModel.state.value.actionMessage)
    }

    @Test
    fun `a refused detail accept is inline and keeps the offer visible`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val job = jobOf(JobStatus.AVAILABLE)
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow(job)
        fakes.dispatch.offersFlow.value = listOf(TestBook.offer(id = OFFER_ID, job = job))
        fakes.dispatch.acceptResult = { Result.failure(IllegalStateException("Offer expired")) }
        val viewModel = JobDetailViewModel(fakes, jobId = JOB_ID, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.acceptOffer()
        advanceUntilIdle()

        assertEquals("Offer expired", viewModel.state.value.errorMessage)
        assertNotNull(viewModel.state.value.offer)
    }

    @Test
    fun `sla remaining is derived from the acceptance time`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.jobsById[JOB_ID] = MutableStateFlow<Job?>(jobOf(JobStatus.EN_ROUTE))
        val viewModel = JobDetailViewModel(fakes, jobId = JOB_ID, now = { TestBook.NOW })
        advanceUntilIdle()

        // 90 minute window, accepted 30 minutes ago → 60 minutes left.
        assertEquals(60L, viewModel.state.value.slaRemainingMinutes)
        assertEquals(42_000 + 1_500, viewModel.state.value.payoutCents)
    }

    private companion object {
        const val JOB_ID = "job-1"
        const val OFFER_ID = "offer-1"
        const val PACKET_ID = "FW-1048"
    }
}
