package com.odasi.fieldwright.data.repo

import androidx.room.withTransaction
import com.odasi.fieldwright.data.db.CaptureDao
import com.odasi.fieldwright.data.db.FieldwrightDatabase
import com.odasi.fieldwright.data.db.JobDao
import com.odasi.fieldwright.data.db.MarketDao
import com.odasi.fieldwright.data.db.MoneyDao
import com.odasi.fieldwright.data.db.OfferDao
import com.odasi.fieldwright.data.db.OperatorDao
import com.odasi.fieldwright.data.db.OutboxDao
import com.odasi.fieldwright.data.db.PacketDao
import com.odasi.fieldwright.data.prefs.FieldwrightPreferences
import com.odasi.fieldwright.data.sync.OutboxProcessor
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.HashAlgorithm
import com.odasi.fieldwright.model.SignatureScheme

/**
 * The shared dependency graph of the seven repositories. Internal on purpose: the only
 * public surface of `core:data` is `FieldwrightRepositories` plus
 * [com.odasi.fieldwright.data.FieldwrightContainer].
 */
internal class DataStores(
    val database: FieldwrightDatabase,
    val preferences: FieldwrightPreferences,
    val clock: Clock,
    val hasher: HashAlgorithm,
    val signer: SignatureScheme,
    val outbox: OutboxProcessor,
    val demoMode: Boolean,
) {
    val jobs: JobDao get() = database.jobDao()
    val offers: OfferDao get() = database.offerDao()
    val packets: PacketDao get() = database.packetDao()
    val capture: CaptureDao get() = database.captureDao()
    val money: MoneyDao get() = database.moneyDao()
    val operator: OperatorDao get() = database.operatorDao()
    val market: MarketDao get() = database.marketDao()
    val outboxDao: OutboxDao get() = database.outboxDao()

    suspend fun <R> transaction(block: suspend () -> R): R = database.withTransaction { block() }
}

/** A business refusal the UI is expected to render verbatim. */
open class RepositoryException(message: String) : Exception(message)

/** Sealing was refused by the evidence rules; [reasons] are the human explanations. */
class SealBlockedException(
    val reasons: List<String>,
    val gates: List<com.odasi.fieldwright.model.GateCheck>,
) : RepositoryException("Sealing blocked: ${reasons.joinToString(" · ")}")

/** Arrival was attempted outside the geofence without a recorded override. */
class ArrivalRejectedException(message: String) : RepositoryException(message)

/** The offer is gone (expired, declined, or already accepted). */
class OfferUnavailableException(message: String) : RepositoryException(message)
