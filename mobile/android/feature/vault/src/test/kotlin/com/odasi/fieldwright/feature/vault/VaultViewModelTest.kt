package com.odasi.fieldwright.feature.vault

import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.FixedClock
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.SealState
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
 * The Vault list owns four decisions worth testing: filtering, the download payload, the
 * chain-health banner, and the demo marking. Everything about evidence itself is the engine's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FixedClock(TEST_NOW_MS)

    private fun sealed() = sealedPacketFixture()
    private fun pending() = pendingPacketFixture()
    private fun flagged() = pendingPacketFixture(
        id = "FW-1012",
        title = "Annual RPZ test · feeder 14",
        seal = SealState.FLAGGED,
        flagged = true,
    )

    @Test
    fun `filters split the vault into all, sealed, and needs attention`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealed(), pending(), flagged()))
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        assertEquals(3, viewModel.ui.value.cards.size)
        assertEquals(3, viewModel.ui.value.totalCards)

        viewModel.setFilter(VaultFilter.SEALED)
        assertEquals(listOf("FW-0994"), viewModel.ui.value.cards.map { it.packetId })

        viewModel.setFilter(VaultFilter.ATTENTION)
        assertEquals(setOf("FW-1012", "FW-1048"), viewModel.ui.value.cards.map { it.packetId }.toSet())

        viewModel.setFilter(VaultFilter.ALL)
        assertEquals(3, viewModel.ui.value.cards.size)
    }

    @Test
    fun `filter-empty state is distinct from the vault being empty`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealed()))
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        viewModel.setFilter(VaultFilter.ATTENTION)

        assertTrue(viewModel.ui.value.isFilteredEmpty)
        assertFalse(viewModel.ui.value.isEmpty)
        assertEquals(0, viewModel.ui.value.cards.size)
        assertEquals(1, viewModel.ui.value.totalCards)
    }

    @Test
    fun `download builds the share payload from the exported packet`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealedPacketFixture(demo = true)))
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        viewModel.downloadPacket("FW-0994")
        advanceUntilIdle()

        val share = viewModel.ui.value.share
        assertNotNull(share)
        assertEquals("FW-0994.json", share!!.fileName)
        assertEquals("{\"packet\":\"FW-0994\"}", share.canonicalJson)
        assertEquals("custody: FW-0994", share.custodyText)
        assertEquals(4, share.recordCount)
        assertTrue("a demo export must keep the sample label", share.demo)
        assertEquals(listOf("FW-0994"), container.vaultRepo.exportCalls)
        assertNull(viewModel.ui.value.exportingPacketId)
    }

    @Test
    fun `export failure is surfaced instead of a fake success`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealed()))
        container.vaultRepo.exportHandler = { Result.failure(IllegalStateException("disk full")) }
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        viewModel.downloadPacket("FW-0994")
        advanceUntilIdle()

        assertNull(viewModel.ui.value.share)
        assertNotNull(viewModel.ui.value.error)
        assertTrue(viewModel.ui.value.error!!.contains("disk full"))
        assertNull(viewModel.ui.value.exportingPacketId)
    }

    @Test
    fun `a broken chain raises the failed banner with the first broken record`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealed()))
        container.vaultRepo.verifyResult = Result.success(
            ChainVerification(
                valid = false,
                entries = 4,
                brokenAtSeq = 2,
                headHash = "9f2c1a04".padEnd(64, '0'),
                detail = "Record 2's prev hash does not match record 1.",
            ),
        )
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        val chain = viewModel.ui.value.chain
        assertEquals(ChainHealthState.FAILED, chain.state)
        assertEquals(2, chain.brokenAtSeq)
        assertTrue(chain.detail.contains("prev hash"))
        assertFalse(viewModel.ui.value.verifying)
    }

    @Test
    fun `a chain check that throws is reported as failed, never verified`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealed()))
        container.vaultRepo.verifyResult = Result.failure(IllegalStateException("signature unreadable"))
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        assertEquals(ChainHealthState.FAILED, viewModel.ui.value.chain.state)
        assertTrue(viewModel.ui.value.chain.detail.contains("could not be verified"))
        assertFalse(viewModel.ui.value.chain.verified)
    }

    @Test
    fun `verify action re-runs the whole-vault check`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealed()))
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()
        assertEquals(1, container.vaultRepo.verificationCalls.size)

        viewModel.verifyChain()
        advanceUntilIdle()

        assertEquals(2, container.vaultRepo.verificationCalls.size)
        assertNull("the list verifies the whole vault", container.vaultRepo.verificationCalls.last())
        assertEquals(ChainHealthState.VERIFIED, viewModel.ui.value.chain.state)
    }

    @Test
    fun `demo packets are flagged for the sample banner`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealedPacketFixture(demo = true)))
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        assertTrue(viewModel.ui.value.demo)
        assertTrue(viewModel.ui.value.cards.single().demo)
    }

    @Test
    fun `payout joins the ledger and excludes fees from the card figure`() = runTest {
        val ledger = listOf(
            ledgerFixture("FW-0994", 42_000).single().copy(status = LedgerStatus.PAID),
            ledgerFixture("FW-0994", 42_000).single().copy(
                id = "led-fee",
                kind = LedgerKind.INSTANT_FEE,
                amountCents = -315,
                status = LedgerStatus.PAID,
            ),
        )
        val container = FakeFieldwrightForVault(
            packets = listOf(sealedPacketFixture()),
            ledger = ledger,
        )
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        val card = viewModel.ui.value.cards.single()
        assertEquals(42_000, card.payoutCents)
        assertEquals(PayoutLabel.PAID, card.payoutLabel)
    }

    @Test
    fun `available earnings are labelled earned, not paid`() = runTest {
        val ledger = ledgerFixture("FW-0994", 42_000)
            .map { it.copy(status = LedgerStatus.AVAILABLE) }
        val container = FakeFieldwrightForVault(
            packets = listOf(sealedPacketFixture()),
            ledger = ledger,
        )
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        assertEquals(PayoutLabel.EARNED, viewModel.ui.value.cards.single().payoutLabel)
    }

    @Test
    fun `no ledger entry means no invented payout figure`() = runTest {
        val container = FakeFieldwrightForVault(packets = listOf(sealedPacketFixture()))
        val viewModel = VaultViewModel(container, clock)
        advanceUntilIdle()

        assertNull(viewModel.ui.value.cards.single().payoutCents)
        assertNull(viewModel.ui.value.cards.single().payoutLabel)
    }
}
