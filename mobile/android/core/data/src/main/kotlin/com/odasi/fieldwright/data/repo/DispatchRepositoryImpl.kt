package com.odasi.fieldwright.data.repo

import com.odasi.fieldwright.data.DispatchRepository
import com.odasi.fieldwright.data.db.OutboxKind
import com.odasi.fieldwright.data.demo.DemoCatalog
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.data.prefs.defaultAvailability
import com.odasi.fieldwright.model.DispatchEngine
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Idempotency
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.OfferStatus
import com.odasi.fieldwright.model.Operator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Dispatch: ranked, time-boxed offers and the market the operator may see.
 *
 * The ranking and every policy decision are `core:model` engines. This class only moves
 * data: it asks [DispatchEngine.rank] for the ranked supply, builds one offer per top job,
 * asks [DispatchEngine.policyDecision] for the auto-accept flag (so the policy has exactly
 * one implementation), enforces the hard accept invariants, and records every accept /
 * decline / expiry as an idempotent outbox event.
 */
internal class DispatchRepositoryImpl(private val stores: DataStores) : DispatchRepository {

    override fun observeOffers(): Flow<List<Offer>> =
        stores.offers.observePending().map { rows -> rows.mapNotNull { row -> with(Mappers) { row.toModel() } } }

    override fun observeMarket(): Flow<MarketSnapshot> =
        stores.market.observeMarket().map { entity ->
            entity?.let { with(Mappers) { it.toModel() } } ?: DemoCatalog.market(stores.clock.nowEpochMs())
        }

    /** Number of hot zones currently disclosed on the market map. */
    override fun observeHotZonesZone(): Flow<Int> =
        stores.market.observeMarket().map { entity ->
            entity?.let { with(Mappers) { it.toModel() } }?.hotZones?.size ?: 0
        }

    override suspend fun refresh(operatorPoint: GeoPoint, atEpochMs: Long): Result<Int> = runCatching {
        val operatorEntity = stores.operator.getOperator()
            ?: throw RepositoryException("No operator profile yet; finish onboarding before refreshing supply.")
        val operator = with(Mappers) { operatorEntity.toModel() }
        val availability = stores.preferences.availability.first()
            ?: stores.operator.getAvailability()?.let { row -> with(Mappers) { row.toModel() } }
            ?: defaultAvailability(atEpochMs)
        if (!availability.isOnline) return@runCatching 0

        // Expire stale supply before ranking; the market never offers dead work.
        expireOffers(atEpochMs)

        val market = stores.market.getMarket()?.let { row -> with(Mappers) { row.toModel() } }
            ?: DemoCatalog.market(atEpochMs).also { seeded ->
                stores.market.upsertMarket(with(Mappers) { seeded.toEntity(tickIndex = 0, updatedAtEpochMs = atEpochMs) })
            }
        val activeCount = stores.jobs.activeCount()
        val availableJobs = stores.jobs.availableJobs().map { row -> with(Mappers) { row.toModel() } }
        if (availableJobs.isEmpty()) return@runCatching 0

        val ranked = DispatchEngine.rank(availableJobs, operator, availability, operatorPoint, activeCount, market).ranked
        val topJobs = ranked.take(MAX_OFFERS_PER_REFRESH)
        var created = 0
        stores.transaction {
            topJobs.forEachIndexed { index, candidate ->
                val baseOffer = Offer(
                    id = offerId(candidate.job.id, atEpochMs),
                    job = candidate.job,
                    operatorId = operator.id,
                    payoutCents = candidate.payoutCents,
                    surgeMultiplier = candidate.surgeMultiplier,
                    offeredAtEpochMs = atEpochMs,
                    expiresAtEpochMs = DispatchEngine.expiresAt(atEpochMs),
                    score = candidate.score,
                    scoreReasons = candidate.reasons,
                    etaMinutes = candidate.etaMinutes,
                    autoAcceptEligible = false,
                    status = OfferStatus.PENDING,
                )
                // The operator's written policy is evaluated once, by the engine, and the
                // result is stored on the offer so the UI can explain the auto-accept chip.
                val policyDecision = DispatchEngine.policyDecision(
                    offer = baseOffer,
                    activeCount = activeCount,
                    policy = availability.autoAccept,
                    operator = operator,
                )
                val offer = baseOffer.copy(autoAcceptEligible = policyDecision.accept)
                stores.offers.upsert(with(Mappers) { offer.toEntity(index) })

                val jobRow = stores.jobs.get(candidate.job.id) ?: return@forEachIndexed
                if (jobRow.status == JobStatus.AVAILABLE) {
                    stores.jobs.upsert(
                        jobRow.copy(status = JobStatus.OFFERED, updatedAtEpochMs = stores.clock.nowEpochMs()),
                    )
                }
                created++
            }
        }
        created
    }

    override suspend fun acceptOffer(offerId: String, atEpochMs: Long): Result<Job> = runCatching {
        var accepted: Job? = null
        stores.transaction {
            val pair = stores.offers.get(offerId)
                ?: throw OfferUnavailableException("That offer is no longer available.")
            if (pair.offer.status != OfferStatus.PENDING) {
                throw OfferUnavailableException("That offer was already ${pair.offer.status.name.lowercase(Locale.US)}.")
            }
            val jobRow = pair.job ?: throw OfferUnavailableException("The job for this offer is gone.")
            if (pair.offer.expiresAtEpochMs <= atEpochMs) {
                stores.offers.updateStatus(offerId, OfferStatus.EXPIRED)
                if (jobRow.status == JobStatus.OFFERED) {
                    stores.jobs.upsert(jobRow.copy(status = JobStatus.AVAILABLE, updatedAtEpochMs = atEpochMs))
                }
                throw OfferUnavailableException("That offer expired. Pull to refresh for current supply.")
            }
            val operator = stores.operator.getOperator()?.let { row -> with(Mappers) { row.toModel() } }
                ?: throw RepositoryException("No operator profile; cannot accept work.")
            val activeCount = stores.jobs.activeCount()
            if (activeCount >= DispatchEngine.MAX_ACTIVE_JOBS) {
                throw RepositoryException(
                    "You are holding $activeCount active jobs (limit ${DispatchEngine.MAX_ACTIVE_JOBS}). Finish or hand off one first.",
                )
            }
            val job = with(Mappers) { jobRow.toModel() }
            if (job.status != JobStatus.OFFERED && job.status != JobStatus.AVAILABLE) {
                throw OfferUnavailableException("Job ${job.id} is ${job.status.label}; it cannot be accepted.")
            }
            val missing = job.requiredCredentials.filter { it !in operator.credentials }
            if (missing.isNotEmpty()) {
                throw RepositoryException(
                    "This job requires ${missing.joinToString { it.label }} — add the credential before accepting.",
                )
            }

            val updated = jobRow.copy(
                status = JobStatus.ACCEPTED,
                acceptedAtEpochMs = atEpochMs,
                updatedAtEpochMs = atEpochMs,
            )
            stores.jobs.upsert(updated)
            stores.offers.updateStatus(offerId, OfferStatus.ACCEPTED)
            stores.offers.withdrawOtherOffersForJob(job.id, offerId)
            stores.outbox.enqueue(
                idempotencyKey = Idempotency.key("offer.accept", offerId, atEpochMs.toString()),
                kind = OutboxKind.OFFER,
                method = "POST",
                path = "/offers/$offerId/accept",
                payload = mapOf(
                    "offerId" to offerId,
                    "jobId" to job.id,
                    "payoutCents" to pair.offer.payoutCents.toString(),
                    "surgeMultiplier" to pair.offer.surgeMultiplier.toString(),
                    "acceptedAt" to atEpochMs.toString(),
                ),
                demo = updated.demo,
                nowEpochMs = atEpochMs,
            )
            accepted = with(Mappers) { updated.toModel() }
        }
        accepted ?: throw RepositoryException("Offer $offerId could not be accepted.")
    }

    override suspend fun declineOffer(offerId: String, reason: String, atEpochMs: Long): Result<Unit> = runCatching {
        stores.transaction {
            val pair = stores.offers.get(offerId)
                ?: throw OfferUnavailableException("That offer is no longer available.")
            if (pair.offer.status != OfferStatus.PENDING) {
                throw OfferUnavailableException("That offer was already ${pair.offer.status.name.lowercase(Locale.US)}.")
            }
            stores.offers.updateStatus(offerId, OfferStatus.DECLINED)
            val jobRow = pair.job
            if (jobRow != null && jobRow.status == JobStatus.OFFERED) {
                stores.jobs.upsert(jobRow.copy(status = JobStatus.AVAILABLE, updatedAtEpochMs = atEpochMs))
            }
            stores.outbox.enqueue(
                idempotencyKey = Idempotency.key("offer.decline", offerId, atEpochMs.toString()),
                kind = OutboxKind.OFFER,
                method = "POST",
                path = "/offers/$offerId/decline",
                payload = mapOf("offerId" to offerId, "reason" to reason, "declinedAt" to atEpochMs.toString()),
                demo = jobRow?.demo ?: stores.demoMode,
                nowEpochMs = atEpochMs,
            )
        }
    }

    override suspend fun expireOffers(nowEpochMs: Long): Result<Int> = runCatching {
        var expired = 0
        stores.transaction {
            stores.offers.expiredPending(nowEpochMs).forEach { offer ->
                stores.offers.updateStatus(offer.id, OfferStatus.EXPIRED)
                val jobRow = stores.jobs.get(offer.jobId)
                if (jobRow != null && jobRow.status == JobStatus.OFFERED && jobRow.packetId == null) {
                    stores.jobs.upsert(jobRow.copy(status = JobStatus.AVAILABLE, updatedAtEpochMs = nowEpochMs))
                }
                expired++
            }
        }
        expired
    }

    private fun offerId(jobId: String, atEpochMs: Long): String = "OF-${jobId.removePrefix("OF-")}-$atEpochMs"

    private companion object {
        const val MAX_OFFERS_PER_REFRESH = 4
    }
}
