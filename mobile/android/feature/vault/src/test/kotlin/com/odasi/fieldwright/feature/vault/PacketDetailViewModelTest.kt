package com.odasi.fieldwright.feature.vault

import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Packet detail owns three writes/verdicts: export, the correction that appends (never edits),
 * and the chain verdict that gates the seal display.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PacketDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FixedClock(TEST_NOW_MS)

    @Test
    fun `correction appends a new record and keeps the history`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(pendingPacketFixture()))
        val viewModel = PacketDetailViewModel(container, "FW-1048", clock)
        advanceUntilIdle()

        val before = viewModel.ui.value.packet
        assertNotNull(before)
        assertEquals(2, before!!.recordCount)

        viewModel.openCorrection()
        viewModel.updateCorrectionNote("South slope label reads east in record 1.")
        viewModel.appendCorrection()
        advanceUntilIdle()

        assertEquals(
            listOf("FW-1048" to "South slope label reads east in record 1."),
            container.vaultRepo.correctionCalls,
        )
        val after = viewModel.ui.value.packet
        assertNotNull(after)
        assertEquals(3, after!!.recordCount)
        assertEquals(com.odasi.fieldwright.model.RecordKind.CORRECTION, after.records.last().kind)
        assertFalse(viewModel.ui.value.correctionOpen)
        assertNull(viewModel.ui.value.correctionError)
        assertTrue(viewModel.ui.value.message!!.contains("record 3"))
        assertTrue(viewModel.ui.value.message!!.contains("Nothing was edited"))
    }

    @Test
    fun `an empty correction is refused before it reaches the repository`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(pendingPacketFixture()))
        val viewModel = PacketDetailViewModel(container, "FW-1048", clock)
        advanceUntilIdle()

        viewModel.openCorrection()
        viewModel.updateCorrectionNote("   ")
        viewModel.appendCorrection()
        advanceUntilIdle()

        assertTrue(container.vaultRepo.correctionCalls.isEmpty())
        assertNotNull(viewModel.ui.value.correctionError)
        assertTrue(viewModel.ui.value.correctionOpen)
        assertFalse(viewModel.ui.value.appendingCorrection)
    }

    @Test
    fun `a failed correction keeps the dialog open with the reason`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(pendingPacketFixture()))
        container.vaultRepo.appendHandler = { _, _ ->
            Result.failure(IllegalStateException("record chain not writable"))
        }
        val viewModel = PacketDetailViewModel(container, "FW-1048", clock)
        advanceUntilIdle()

        viewModel.openCorrection()
        viewModel.updateCorrectionNote("Fix the scope wording.")
        viewModel.appendCorrection()
        advanceUntilIdle()

        assertTrue(viewModel.ui.value.correctionOpen)
        assertFalse(viewModel.ui.value.appendingCorrection)
        assertTrue(viewModel.ui.value.correctionError!!.contains("record chain not writable"))
    }

    @Test
    fun `export produces a share payload and never claims a new seal`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealedPacketFixture()))
        val viewModel = PacketDetailViewModel(container, "FW-0994", clock)
        advanceUntilIdle()

        viewModel.exportPacket()
        advanceUntilIdle()

        val share = viewModel.ui.value.share
        assertNotNull(share)
        assertEquals("FW-0994.json", share!!.fileName)
        assertEquals("9f2c1a04".padEnd(64, '0'), share.chainHead)
        assertFalse(share.demo)
        assertFalse(viewModel.ui.value.exporting)
        assertTrue(viewModel.ui.value.message!!.contains("saved to the cache"))
    }

    @Test
    fun `chain failure is shown on the detail banner`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealedPacketFixture()))
        container.vaultRepo.verifyResult = Result.success(
            ChainVerification(
                valid = false,
                entries = 4,
                brokenAtSeq = 3,
                headHash = "9f2c1a04".padEnd(64, '0'),
                detail = "Record 3's signature does not verify.",
            ),
        )
        val viewModel = PacketDetailViewModel(container, "FW-0994", clock)
        advanceUntilIdle()

        assertEquals(ChainHealthState.FAILED, viewModel.ui.value.chain.state)
        assertEquals(3, viewModel.ui.value.chain.brokenAtSeq)
        assertFalse(viewModel.ui.value.chain.wholeVault)
        assertFalse(viewModel.ui.value.verifying)
        assertEquals(listOf("FW-0994"), container.vaultRepo.verificationCalls)
    }

    @Test
    fun `seal reasons come from the engine, not the screen`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealedPacketFixture(demo = true)))
        val viewModel = PacketDetailViewModel(container, "FW-0994", clock)
        advanceUntilIdle()

        val state = viewModel.ui.value
        assertTrue(state.demo)
        // The engine owns the evaluation. Until it returns one, the UI must not invent reasons:
        // it shows the tier's own named meaning instead.
        assertEquals(com.odasi.fieldwright.model.EvidenceTier.VERIFIED_BY_AUCTUM, state.packet!!.tier)
    }

    @Test
    fun `a missing packet is a distinct state, not a crash`() = runTest {
        val container = FakeFieldwrightForVault(packets = emptyList())
        val viewModel = PacketDetailViewModel(container, "FW-0000", clock)
        advanceUntilIdle()

        assertFalse(viewModel.ui.value.loading)
        assertNull(viewModel.ui.value.packet)
    }

    @Test
    fun `payout label follows the ledger entry statuses`() = runTest {
        val ledger = listOf(
            ledgerFixture("FW-0994", 42_000).single().copy(
                status = com.odasi.fieldwright.model.LedgerStatus.AVAILABLE,
            ),
        )
        val container = FakeFieldwrightForVault(packets = listOf(sealedPacketFixture()), ledger = ledger)
        val viewModel = PacketDetailViewModel(container, "FW-0994", clock)
        advanceUntilIdle()

        assertEquals(42_000, viewModel.ui.value.payoutCents)
        assertEquals(PayoutLabel.EARNED, viewModel.ui.value.payoutLabel)
        assertEquals(1, viewModel.ui.value.packetLedger.size)
    }
}
