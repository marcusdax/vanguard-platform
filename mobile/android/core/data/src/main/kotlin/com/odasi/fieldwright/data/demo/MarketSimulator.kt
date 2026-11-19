package com.odasi.fieldwright.data.demo

import com.odasi.fieldwright.data.DispatchRepository
import com.odasi.fieldwright.data.LocationProvider
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.data.db.JobDao
import com.odasi.fieldwright.data.db.MarketDao
import com.odasi.fieldwright.data.db.OfferDao
import com.odasi.fieldwright.data.db.OperatorDao
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.DispatchEngine
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HotZone
import com.odasi.fieldwright.model.MarketSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic market heartbeat (CONTRACTS §8).
 *
 * The market the dispatch engine sees must be explainable, so the simulator is a *pure*
 * function of (previous snapshot, tick index, seed): same tick sequence → same market,
 * in tests and on a demo device. Demand/supply drift as a bounded random walk; hot zones
 * rotate around the operator's area with disclosed surge; daylight ticks down and weather
 * cycles, because those are the three excuses a market uses to move a price.
 *
 * The simulator never accepts work itself — [MarketDriver] hands candidate offers to
 * [DispatchEngine.autoAccept], which is the only code allowed to auto-accept.
 */
object MarketSimulator {

    /** Supply refreshes every ~8 s while online (CONTRACTS §8). */
    const val TICK_INTERVAL_MS: Long = 8_000L

    private const val DEFAULT_SEED: Long = 4_217L

    fun tick(
        previous: MarketSnapshot?,
        nowEpochMs: Long,
        tickIndex: Long,
        basePoint: GeoPoint,
        seed: Long = DEFAULT_SEED,
    ): MarketSnapshot {
        val random = Random(seed * 1_000_003L + tickIndex)
        val demand = ((previous?.demandIndex ?: 0.55) + (random.nextDouble() - 0.5) * 0.05)
            .coerceIn(0.20, 0.95)
        val supply = ((previous?.supplyIndex ?: 0.45) + (random.nextDouble() - 0.5) * 0.04)
            .coerceIn(0.15, 0.90)

        val candidates = listOf(
            zone("Creekside · MUD 14", basePoint, nowEpochMs, latDelta = 0.0028, lngDelta = -0.0019, radius = 900, reason = "Two utility crews active · feeder 14"),
            zone("Oak Hollow", basePoint, nowEpochMs, latDelta = 0.0128, lngDelta = 0.0131, radius = 1_200, reason = "Hail claim intake after the 4/11 event"),
            zone("Ridgeview", basePoint, nowEpochMs, latDelta = -0.0062, lngDelta = -0.0049, radius = 800, reason = "Property-walk backlog on the east route"),
        )
        val zoneCount = 2 + (tickIndex % 2).toInt()
        val hotZones = candidates.take(zoneCount).mapIndexed { index, template ->
            val surge = (1.05 + ((tickIndex + index * 7) % 25) / 100.0).coerceAtMost(1.50)
            template.copy(surgeMultiplier = (surge * 100).toInt() / 100.0)
        }

        val remainingMinutes = (250L - (tickIndex / 45L)).coerceIn(15L, 250L)
        val daylight = "${remainingMinutes / 60} hr ${remainingMinutes % 60} min"
        val weather = WEATHER[(tickIndex / 75L % WEATHER.size).toInt()]

        return MarketSnapshot(
            nowEpochMs = nowEpochMs,
            demandIndex = demand,
            supplyIndex = supply,
            hotZones = hotZones,
            daylightLabel = daylight,
            weatherLabel = weather,
        )
    }

    private val WEATHER = listOf(
        "Clear · 88 °F",
        "Clear · 91 °F",
        "Overcast · 84 °F",
        "Wind 12 mph · 86 °F",
    )

    private fun zone(
        label: String,
        base: GeoPoint,
        nowEpochMs: Long,
        latDelta: Double,
        lngDelta: Double,
        radius: Int,
        reason: String,
    ): HotZone {
        // Offsets are small-degree approximations valid at the demo's latitude; the point is
        // stable and human-plausible, not a GIS operation.
        val lat = base.lat + latDelta + 0.0004 * sin((label.length + latDelta) * PI)
        val lng = base.lng + lngDelta + 0.0004 * cos((label.length + lngDelta) * PI)
        return HotZone(
            label = label,
            point = GeoPoint(
                lat = lat,
                lng = lng,
                recordedAtEpochMs = nowEpochMs,
                accuracyMeters = 25f,
                source = GeoPoint.FixSource.NETWORK,
            ),
            radiusMeters = radius,
            surgeMultiplier = 1.0,
            reason = reason,
        )
    }
}

/**
 * Drives [MarketSimulator] while the operator is online: advances the market, expires
 * offers past their TTL, refreshes supply against the operator's current fix, and then
 * passes policy-eligible offers to [DispatchEngine.autoAccept].
 *
 * One job per container; [start] is idempotent and [MarketDriver] is cancelled with the
 * container's scope.
 */
class MarketDriver(
    private val clock: Clock,
    private val marketDao: MarketDao,
    private val offerDao: OfferDao,
    private val jobDao: JobDao,
    private val operatorDao: OperatorDao,
    private val dispatch: DispatchRepository,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope,
) {

    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                delay(MarketSimulator.TICK_INTERVAL_MS)
                runCatching { tickOnce() }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** One market beat. Public so a test/demo can advance the market without waiting 8 s. */
    suspend fun tickOnce() {
        val now = clock.nowEpochMs()
        val availabilityEntity = operatorDao.getAvailability() ?: return
        if (!availabilityEntity.isOnline) return

        val previousEntity = marketDao.getMarket()
        val tickIndex = (previousEntity?.tickIndex ?: 0L) + 1L
        val operatorPoint = locationProvider.currentFix() ?: DemoCatalog.OPERATOR_HOME
        val next = MarketSimulator.tick(
            previous = previousEntity?.let { entity -> with(Mappers) { entity.toModel() } },
            nowEpochMs = now,
            tickIndex = tickIndex,
            basePoint = operatorPoint,
        )
        marketDao.upsertMarket(with(Mappers) { next.toEntity(tickIndex, now) })

        dispatch.expireOffers(now)
        dispatch.refresh(operatorPoint, now)

        // Auto-accept is a written policy. The policy produced each offer's flag; the
        // hard invariant is re-checked here by the engine, and only DispatchEngine may say yes.
        val activeCount = jobDao.activeCount()
        offerDao.pending().forEach { pair ->
            val offer = with(Mappers) { pair.toModel() } ?: return@forEach
            if (!offer.autoAcceptEligible) return@forEach
            val decision = DispatchEngine.autoAccept(offer, activeCount)
            if (decision.accept) {
                dispatch.acceptOffer(offer.id, now)
            }
        }
    }
}
