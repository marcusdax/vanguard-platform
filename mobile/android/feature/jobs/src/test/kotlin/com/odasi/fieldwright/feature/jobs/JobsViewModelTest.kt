package com.odasi.fieldwright.feature.jobs

import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.EvidenceTier
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jobs list ViewModel: filters, the offer clock, and vault-sourced packet references. */
@OptIn(ExperimentalCoroutinesApi::class)
class JobsViewModelTest {

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

    private fun packet(jobId: String, id: String): EvidencePacket = EvidencePacket(
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

    @Test
    fun `filter changes which list is shown and keeps the data`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.dispatch.offersFlow.value = listOf(TestBook.offer(job = TestBook.job(status = JobStatus.AVAILABLE)))
        fakes.jobs.activeJobsFlow.value = listOf(TestBook.job(id = "job-active", status = JobStatus.EN_ROUTE, acceptedAtEpochMs = TestBook.NOW))
        fakes.jobs.completedJobsFlow.value = listOf(TestBook.job(id = "job-done", status = JobStatus.PAID))
        val viewModel = JobsViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        assertEquals(JobsFilter.OFFERS, viewModel.state.value.filter)
        assertEquals(1, viewModel.state.value.visibleCount)
        assertEquals(1, viewModel.state.value.offers.size)

        viewModel.setFilter(JobsFilter.ACTIVE)
        assertEquals(1, viewModel.state.value.visibleCount)
        assertEquals("job-active", viewModel.state.value.activeJobs.single().id)

        viewModel.setFilter(JobsFilter.DONE)
        assertEquals(1, viewModel.state.value.visibleCount)
        assertEquals("job-done", viewModel.state.value.completedJobs.single().id)

        assertTrue(viewModel.state.value.loading.not())
    }

    @Test
    fun `expired offers are handed back to dispatch on tick`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        var clock = TestBook.NOW
        fakes.dispatch.offersFlow.value = listOf(
            TestBook.offer(expiresAtEpochMs = TestBook.NOW + 1_000L),
        )
        val viewModel = JobsViewModel(fakes, now = { clock })
        advanceUntilIdle()

        viewModel.onTick()
        advanceUntilIdle()
        assertEquals(1_000L, viewModel.state.value.offers.single().remainingMillis)
        assertTrue(fakes.dispatch.expireCalls.isEmpty())

        clock += 5_000L
        viewModel.onTick()
        advanceUntilIdle()

        assertEquals(0L, viewModel.state.value.offers.single().remainingMillis)
        assertEquals(listOf(clock), fakes.dispatch.expireCalls)
    }

    @Test
    fun `done cards get their packet id from the vault, not the job record`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        fakes.jobs.completedJobsFlow.value = listOf(TestBook.job(id = "job-done", status = JobStatus.PAID))
        val viewModel = JobsViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        assertNull(viewModel.state.value.packetIds["job-done"])

        fakes.vault.packetsFlow.value = listOf(packet(jobId = "job-done", id = "FW-1012"))
        advanceUntilIdle()

        assertEquals("FW-1012", viewModel.state.value.packetIds["job-done"])
    }

    @Test
    fun `passing an offer records a reason and removes it from the list`() = runTest(dispatcher) {
        val fakes = FakeFieldwrightRepositories()
        val offer = TestBook.offer()
        fakes.dispatch.offersFlow.value = listOf(offer)
        val viewModel = JobsViewModel(fakes, now = { TestBook.NOW })
        advanceUntilIdle()

        viewModel.declineOffer(offer.id)
        advanceUntilIdle()

        assertEquals(offer.id, fakes.dispatch.declinedOfferIds.single().first)
        assertTrue(fakes.dispatch.declinedOfferIds.single().second.isNotBlank())
        assertTrue(viewModel.state.value.offers.isEmpty())
        assertNotNull(viewModel.state.value.actionMessage)
    }
}
