package com.odasi.fieldwright.data.repo

import com.odasi.fieldwright.data.OperatorRepository
import com.odasi.fieldwright.data.db.OutboxKind
import com.odasi.fieldwright.data.demo.DemoCatalog
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.data.prefs.defaultAvailability
import com.odasi.fieldwright.model.AutoAcceptPolicy
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.DispatchEngine
import com.odasi.fieldwright.model.Idempotency
import com.odasi.fieldwright.model.Operator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Operator profile and availability. The profile is server-owned (Room snapshot); the
 * availability toggle is a **device preference** (DataStore) mirrored into Room for the
 * pure engines. Every change is an idempotent outbox event and carries the caller's
 * timestamp, so a retry cannot silently move the operator's online window.
 */
internal class OperatorRepositoryImpl(private val stores: DataStores) : OperatorRepository {

    private val demoOperatorFallback: Operator by lazy { DemoCatalog.operator() }

    override fun observeOperator(): Flow<Operator> =
        stores.operator.observeOperator().map { entity ->
            entity?.let { with(Mappers) { it.toModel() } } ?: demoOperatorFallback
        }

    override fun observeAvailability(): Flow<Availability> = flow {
        stores.preferences.availability.collect { saved ->
            emit(saved ?: storedAvailability() ?: defaultAvailability(stores.clock.nowEpochMs()))
        }
    }

    override suspend fun setOnline(online: Boolean, atEpochMs: Long): Result<Availability> = runCatching {
        val previous = currentAvailability()
        val previousRow = stores.operator.getAvailability()
        var onlineMinutes = previousRow?.onlineMinutesToday ?: 0
        var onlineSince = previousRow?.onlineSinceEpochMs

        if (online) {
            if (!previous.isOnline) onlineSince = atEpochMs
        } else {
            if (previous.isOnline && onlineSince != null) {
                onlineMinutes += ((atEpochMs - onlineSince).coerceAtLeast(0L) / 60_000L).toInt()
            }
            onlineSince = null
        }

        val updated = previous.copy(isOnline = online, updatedAtEpochMs = atEpochMs)
        persist(updated, onlineMinutes, onlineSince, atEpochMs)
        updated
    }

    override suspend fun updateRadius(radiusMeters: Int): Result<Availability> = runCatching {
        require(radiusMeters > 0) { "Radius must be a positive distance." }
        val previous = currentAvailability()
        val previousRow = stores.operator.getAvailability()
        val updated = previous.copy(
            // The dispatch engine refuses offers beyond its own 25 km ceiling; clamp here so
            // the UI never displays a radius the market will not honour.
            radiusMeters = radiusMeters.coerceAtMost(DispatchEngine.MAX_OFFER_RADIUS_METERS),
            updatedAtEpochMs = stores.clock.nowEpochMs(),
        )
        persist(updated, previousRow?.onlineMinutesToday ?: 0, previousRow?.onlineSinceEpochMs, updated.updatedAtEpochMs)
        updated
    }

    override suspend fun updateAutoAccept(policy: AutoAcceptPolicy): Result<Availability> = runCatching {
        require(policy.maxActiveStack >= 0) { "Auto-accept stack limit cannot be negative." }
        require(policy.minPayoutCents >= 0) { "Auto-accept minimum payout cannot be negative." }
        val previous = currentAvailability()
        val previousRow = stores.operator.getAvailability()
        val updated = previous.copy(
            autoAccept = policy,
            updatedAtEpochMs = stores.clock.nowEpochMs(),
        )
        persist(updated, previousRow?.onlineMinutesToday ?: 0, previousRow?.onlineSinceEpochMs, updated.updatedAtEpochMs)
        updated
    }

    private suspend fun persist(
        availability: Availability,
        onlineMinutes: Int,
        onlineSince: Long?,
        atEpochMs: Long,
    ) {
        stores.transaction {
            stores.preferences.saveAvailability(availability)
            stores.operator.upsertAvailability(
                with(Mappers) {
                    availability.toEntity(
                        onlineMinutesToday = onlineMinutes,
                        onlineSinceEpochMs = onlineSince,
                    )
                },
            )
            stores.outbox.enqueue(
                idempotencyKey = Idempotency.key("availability", atEpochMs.toString()),
                kind = OutboxKind.AVAILABILITY,
                method = "PUT",
                path = "/operators/me/availability",
                payload = mapOf(
                    "online" to availability.isOnline.toString(),
                    "radiusMeters" to availability.radiusMeters.toString(),
                    "autoAcceptEnabled" to availability.autoAccept.enabled.toString(),
                    "updatedAt" to atEpochMs.toString(),
                ),
                demo = stores.demoMode,
                nowEpochMs = atEpochMs,
            )
        }
    }

    private suspend fun currentAvailability(): Availability =
        stores.preferences.availability.first()
            ?: storedAvailability()
            ?: defaultAvailability(stores.clock.nowEpochMs())

    private suspend fun storedAvailability(): Availability? = stores.operator.getAvailability()?.let { entity ->
        with(Mappers) { entity.toModel() }
    }
}
