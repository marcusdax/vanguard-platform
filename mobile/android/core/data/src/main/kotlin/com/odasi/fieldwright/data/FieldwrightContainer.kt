package com.odasi.fieldwright.data

import android.content.Context
import com.odasi.fieldwright.crypto.Sha256
import com.odasi.fieldwright.data.crypto.Signers
import com.odasi.fieldwright.data.db.FieldwrightDatabase
import com.odasi.fieldwright.data.demo.DemoCatalog
import com.odasi.fieldwright.data.demo.DemoPacketFactory
import com.odasi.fieldwright.data.demo.DemoSeeder
import com.odasi.fieldwright.data.demo.MarketDriver
import com.odasi.fieldwright.data.demo.RoomDemoSink
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.data.prefs.FieldwrightPreferences
import com.odasi.fieldwright.data.repo.CaptureRepositoryImpl
import com.odasi.fieldwright.data.repo.DataStores
import com.odasi.fieldwright.data.repo.DispatchRepositoryImpl
import com.odasi.fieldwright.data.repo.EarningsRepositoryImpl
import com.odasi.fieldwright.data.repo.JobRepositoryImpl
import com.odasi.fieldwright.data.repo.OperatorRepositoryImpl
import com.odasi.fieldwright.data.repo.SyncRepositoryImpl
import com.odasi.fieldwright.data.repo.VaultRepositoryImpl
import com.odasi.fieldwright.data.sync.BackendApi
import com.odasi.fieldwright.data.sync.FakeBackend
import com.odasi.fieldwright.data.sync.HttpBackendApi
import com.odasi.fieldwright.data.sync.OutboxProcessor
import com.odasi.fieldwright.data.sync.SyncScheduler
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HashAlgorithm
import com.odasi.fieldwright.model.SignatureScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one Android-Context composition root of the data layer.
 *
 * The app constructs it in `Application.onCreate`:
 * ```
 * val container = FieldwrightContainer(this)
 * applicationScope.launch { container.bootstrap() }
 * ```
 * and passes `container.repos` to the feature routes. Everything Android-specific
 * (Room, DataStore, WorkManager, Android Keystore, the in-process fake backend) is created
 * here; feature modules only see [FieldwrightRepositories].
 *
 * @param clock wall clock for *now* fallbacks; every decision still takes an explicit
 *   timestamp from its caller, so a [com.odasi.fieldwright.model.FixedClock] makes tests
 *   deterministic.
 * @param hasher injected digest (`core:crypto` Sha256 by default).
 * @param signer ECDSA P-256 signer: Android Keystore on device, a persisted software key
 *   on emulators/dev (`Signers.create`).
 * @param demoMode this sprint ships the sample book; every demo row is flagged and excluded
 *   from anchor payloads.
 * @param backend the sync transport. Demo builds use the in-process [FakeBackend]; the real
 *   backend is [HttpBackendApi] (documented stub) behind the same interface.
 */
class FieldwrightContainer(
    private val context: Context,
    private val clock: Clock = SystemClock,
    private val hasher: HashAlgorithm = Sha256,
    private val signer: SignatureScheme = Signers.create(context),
    private val locationProvider: LocationProvider = DemoLocationProvider(),
    val demoMode: Boolean = true,
    private val backend: BackendApi = if (demoMode) FakeBackend() else HttpBackendApi(DEFAULT_BACKEND_URL),
) : FieldwrightRepositories {

    private val database: FieldwrightDatabase = FieldwrightDatabase.build(context.applicationContext)
    private val preferences: FieldwrightPreferences = FieldwrightPreferences(context.applicationContext)
    private val outboxProcessor: OutboxProcessor = OutboxProcessor(
        dao = database.outboxDao(),
        backend = backend,
        clock = clock,
    )
    private val stores: DataStores = DataStores(
        database = database,
        preferences = preferences,
        clock = clock,
        hasher = hasher,
        signer = signer,
        outbox = outboxProcessor,
        demoMode = demoMode,
    )
    private val demoSeeder: DemoSeeder = DemoSeeder(
        sink = RoomDemoSink(database, preferences),
        packetFactory = DemoPacketFactory(hasher, signer),
        clock = clock,
    )

    override val operator: OperatorRepository = OperatorRepositoryImpl(stores)
    override val dispatch: DispatchRepository = DispatchRepositoryImpl(stores)
    override val jobs: JobRepository = JobRepositoryImpl(stores)
    override val capture: CaptureRepository = CaptureRepositoryImpl(stores)
    override val vault: VaultRepository = VaultRepositoryImpl(stores)
    override val earnings: EarningsRepository = EarningsRepositoryImpl(stores)
    private val syncRepository: SyncRepositoryImpl = SyncRepositoryImpl(stores, outboxProcessor)
    override val sync: SyncRepository get() = syncRepository

    /** Exposed for the app module, which depends on the interface only. */
    val repos: FieldwrightRepositories get() = this

    /** Demo builds show a "Sample book" chip; kept as an alias for that wiring. */
    val isDemo: Boolean get() = demoMode

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val marketDriver = MarketDriver(
        clock = clock,
        marketDao = database.marketDao(),
        offerDao = database.offerDao(),
        jobDao = database.jobDao(),
        operatorDao = database.operatorDao(),
        dispatch = dispatch,
        locationProvider = locationProvider,
        scope = scope,
    )

    private val bootstrapMutex = Mutex()

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var bootstrapped: Boolean = false

    /**
     * First-launch preparation: verify the sync-state row exists, seed the sample book on a
     * fresh install, persist the demo seed marker, schedule WorkManager, and start the
     * market heartbeat. Idempotent — calling it twice (app + a worker fallback) is safe.
     */
    suspend fun bootstrap() {
        bootstrapMutex.withLock {
            if (closed) return
            FieldwrightContainerHolder.install(this)
            ensureSyncState()
            // Re-seed when the sample book changes under us (e.g. the evidence protocol moved
            // to 1-based record sequences): a stale demo vault would fail chain verification.
            val storedSeedVersion = preferences.seedVersion.first()
            val seeded = demoSeeder.seed(force = storedSeedVersion != DemoCatalog.SEED_VERSION)
            if (seeded) preferences.markDemoSeeded(DemoCatalog.SEED_VERSION)
            SyncScheduler.ensurePeriodic(context.applicationContext)
            marketDriver.start()
            bootstrapped = true
        }
    }

    /** Brief → "Reset sample book": wipes the demo rows and reseeds them. Demo builds only. */
    suspend fun resetSampleBook() {
        bootstrapMutex.withLock {
            if (closed) return
            demoSeeder.seed(force = true)
            preferences.markDemoSeeded(DemoCatalog.SEED_VERSION)
            ensureSyncState()
            marketDriver.start()
        }
    }

    /** Asks WorkManager for an immediate flush (packet submit, Brief "Retry now"). */
    suspend fun scheduleSync() {
        SyncScheduler.enqueueOneShot(context.applicationContext)
    }

    /** Puts dead-lettered events back in the queue. */
    suspend fun retryDeadLetters(): Int = syncRepository.retryDeadLetters()

    /** True once [bootstrap] has finished; the app can gate the first frame on it if wanted. */
    val isBootstrapped: Boolean get() = bootstrapped

    /**
     * Releases the database and cancels background work. Safe to call twice, and safe to
     * never call (the app process death is the normal shutdown).
     */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { marketDriver.stop() }
        scope.cancel()
        FieldwrightContainerHolder.clear(this)
        runCatching { if (database.isOpen) database.close() }
    }

    private suspend fun ensureSyncState() {
        if (database.outboxDao().getSyncState() == null) {
            database.outboxDao().upsertSyncState(with(Mappers) { defaultSyncState(clock.nowEpochMs()) })
        }
    }

    private companion object {
        const val DEFAULT_BACKEND_URL: String = "https://api.fieldwright.invalid"
    }
}

/**
 * Process-wide handle for code that is not constructed by the app's DI — specifically the
 * WorkManager worker. The app's container installs itself during [FieldwrightContainer.bootstrap].
 */
object FieldwrightContainerHolder {

    @Volatile
    private var instance: FieldwrightContainer? = null

    fun current(): FieldwrightContainer? = instance

    internal fun install(container: FieldwrightContainer) {
        if (instance !== container) instance = container
    }

    internal fun clear(container: FieldwrightContainer) {
        if (instance === container) instance = null
    }
}

/**
 * Default device fixes for the demo build: a stable Creekside position, labelled SIMULATED
 * by `GeoPoint.FixSource` so nothing pretends to be a GPS fix. The app can inject a real
 * `LocationProvider` via the container constructor without touching the data layer.
 */
class DemoLocationProvider(
    private var fix: GeoPoint = DemoCatalog.OPERATOR_HOME,
) : LocationProvider {

    override fun currentFix(): GeoPoint = fix.copy(recordedAtEpochMs = System.currentTimeMillis())

    override fun observeFix(): Flow<GeoPoint> = flowOf(fix)

    fun moveTo(point: GeoPoint) {
        fix = point
    }
}
