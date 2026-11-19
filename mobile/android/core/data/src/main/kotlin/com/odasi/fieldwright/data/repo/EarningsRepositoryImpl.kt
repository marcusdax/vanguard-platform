package com.odasi.fieldwright.data.repo

import com.odasi.fieldwright.data.EarningsRepository
import com.odasi.fieldwright.data.db.OutboxKind
import com.odasi.fieldwright.data.demo.DemoCatalog
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.FeeEngine
import com.odasi.fieldwright.model.FeeSchedule
import com.odasi.fieldwright.model.HashAlgorithm
import com.odasi.fieldwright.model.Idempotency
import com.odasi.fieldwright.model.LedgerEngine
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.Money
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutQuote
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.PayoutStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Money surfaces: balances, earnings summary, ledger, payout quotes and idempotent payout
 * requests (CONTRACTS §5.3).
 *
 * All arithmetic is delegated to `core:model` ([LedgerEngine], [FeeEngine]) — this class
 * only feeds them the ledger and the operator context. A payout request is idempotent per
 * (operator, amount, mode, minute): a duplicate tap inside the same minute collapses to the
 * existing request, and the open-payout sum is subtracted from available funds so two
 * requests cannot both consume the same balance.
 */
internal class EarningsRepositoryImpl(private val stores: DataStores) : EarningsRepository {

    private val schedule = FeeSchedule()

    override fun observeBalances(): Flow<Balances> = combine(
        stores.money.observeLedger(),
        stores.operator.observeOperator(),
    ) { ledger, operator ->
        val entries = ledger.map { row -> with(Mappers) { row.toModel() } }
        val profile = operator?.let { row -> with(Mappers) { row.toModel() } } ?: DemoCatalog.operator()
        LedgerEngine.balances(entries, profile)
    }

    override fun observeSummary(): Flow<EarningsSummary> = combine(
        stores.money.observeLedger(),
        stores.operator.observeAvailability(),
    ) { ledger, availability ->
        val now = stores.clock.nowEpochMs()
        val entries = ledger.map { row -> with(Mappers) { row.toModel() } }
        val onlineMinutesToday = onlineMinutesToday(availability?.onlineMinutesToday ?: 0, availability?.onlineSinceEpochMs, now)
        val sinceWeek = now - WEEK_MS
        val milesDrivenWeek = stores.jobs.jobsAcceptedSince(sinceWeek)
            .sumOf { it.distanceMeters.coerceAtLeast(0) } / METERS_PER_MILE
        LedgerEngine.summary(
            entries = entries,
            nowEpochMs = now,
            onlineMinutesToday = onlineMinutesToday,
            milesDrivenWeek = milesDrivenWeek,
            schedule = schedule,
        )
    }

    override fun observeLedger(): Flow<List<LedgerEntry>> =
        stores.money.observeLedger().map { rows -> rows.map { row -> with(Mappers) { row.toModel() } } }

    override fun observePayouts(): Flow<List<PayoutRequest>> =
        stores.money.observePayouts().map { rows -> rows.map { row -> with(Mappers) { row.toModel() } } }

    override fun quote(amountCents: Int, mode: PayoutMode): PayoutQuote =
        FeeEngine.quote(amountCents, mode, schedule)

    override suspend fun requestPayout(
        amountCents: Int,
        mode: PayoutMode,
        atEpochMs: Long,
    ): Result<PayoutRequest> = runCatching {
        require(amountCents > 0) { "A payout request needs a positive amount." }
        val operatorEntity = stores.operator.getOperator()
            ?: throw RepositoryException("No operator profile; cannot request a payout.")
        val operator = with(Mappers) { operatorEntity.toModel() }
        val ledger = stores.money.ledger().map { row -> with(Mappers) { row.toModel() } }
        val balances = LedgerEngine.balances(ledger, operator)
        val openPayoutCents = stores.money.openPayouts().sumOf { it.amountCents }
        val availableForPayout = balances.availableCents - openPayoutCents
        if (amountCents > availableForPayout) {
            throw RepositoryException(
                "You can request up to ${Money.format(availableForPayout)} right now" +
                    if (openPayoutCents > 0) " (${Money.format(openPayoutCents)} is already in flight)." else ".",
            )
        }

        // Idempotent per logical request. A retried tap in the same minute finds this row.
        val idempotencyKey = PayoutKeys.forRequest(operator.id, amountCents, mode, atEpochMs)
        stores.money.payoutByKey(idempotencyKey)?.let { existing ->
            return@runCatching with(Mappers) { existing.toModel() }
        }

        val quote = FeeEngine.quote(amountCents, mode, schedule)
        val payout = PayoutRequest(
            id = "PO-${PayoutKeys.shortId(idempotencyKey, stores.hasher)}",
            amountCents = amountCents,
            feeCents = quote.feeCents,
            netCents = quote.netCents,
            mode = mode,
            status = PayoutStatus.REQUESTED,
            requestedAtEpochMs = atEpochMs,
            completedAtEpochMs = null,
            destinationLast4 = operator.payoutAccountLast4,
            idempotencyKey = idempotencyKey,
        )
        val demo = operatorEntity.demo || stores.demoMode
        stores.transaction {
            stores.money.insertPayout(with(Mappers) { payout.toEntity(demo = demo) })
            if (quote.feeCents > 0) {
                stores.money.insertLedger(
                    with(Mappers) {
                        LedgerEntry(
                            id = "LG-${PayoutKeys.shortId(idempotencyKey, stores.hasher)}-FEE",
                            jobId = null,
                            packetId = null,
                            kind = LedgerKind.INSTANT_FEE,
                            amountCents = -quote.feeCents,
                            status = LedgerStatus.PENDING,
                            atEpochMs = atEpochMs,
                            description = "Instant payout fee · ${payout.id}",
                            idempotencyKey = Idempotency.key("payout.fee", idempotencyKey),
                            demo = demo,
                        ).toEntity()
                    },
                )
            }
            stores.outbox.enqueue(
                idempotencyKey = Idempotency.key("payout.request", idempotencyKey),
                kind = OutboxKind.PAYOUT,
                method = "POST",
                path = "/payouts",
                payload = mapOf(
                    "payoutId" to payout.id,
                    "amountCents" to amountCents.toString(),
                    "feeCents" to quote.feeCents.toString(),
                    "mode" to mode.name,
                    "requestedAt" to atEpochMs.toString(),
                    "demo" to demo.toString(),
                ),
                demo = demo,
                nowEpochMs = atEpochMs,
            )
        }
        stores.money.payoutByKey(idempotencyKey)?.let { row -> with(Mappers) { row.toModel() } }
            ?: throw RepositoryException("The payout request could not be recorded.")
    }

    private fun onlineMinutesToday(accumulated: Int, onlineSinceEpochMs: Long?, nowEpochMs: Long): Int {
        val live = if (onlineSinceEpochMs != null) {
            ((nowEpochMs - onlineSinceEpochMs).coerceAtLeast(0L) / 60_000L).toInt()
        } else {
            0
        }
        return (accumulated + live).coerceAtLeast(0)
    }

    private companion object {
        const val WEEK_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val METERS_PER_MILE: Double = 1_609.344
    }
}

/**
 * Deterministic payout idempotency keys. Public so tests can prove "same key once" without
 * a database: the key is a stable function of the logical request.
 */
object PayoutKeys {

    /**
     * Same operator + amount + mode within the same minute = the same logical request.
     * The minute bucket is deliberate: the payout interface has no client token, so the
     * only stable identifier a retried tap can share is the request window.
     */
    fun forRequest(operatorId: String, amountCents: Int, mode: PayoutMode, atEpochMs: Long): String =
        Idempotency.key("payout", operatorId, amountCents.toString(), mode.name, (atEpochMs / 60_000L).toString())

    fun shortId(key: String, hasher: HashAlgorithm): String =
        hasher.sha256Hex(key).take(10).uppercase(Locale.US)
}
