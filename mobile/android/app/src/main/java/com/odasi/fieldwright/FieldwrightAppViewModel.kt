package com.odasi.fieldwright

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightContainer
import com.odasi.fieldwright.locations.DemoDistrict
import com.odasi.fieldwright.locations.LocationProvider
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the shared chrome needs, and nothing a screen could not survive without. */
data class ChromeState(
    val operatorLabel: String = "Elena Voss",
    val operatorInitials: String = "EV",
    val areaLabel: String = DemoDistrict.LABEL,
    val isOnline: Boolean = false,
    val syncLabel: String? = null,
    val queuedCount: Int = 0,
    val isDemo: Boolean = true,
)

/**
 * App-level state only: who is working, whether the outbox is healthy, and where the
 * device thinks it is. Screen state belongs to the feature ViewModels.
 */
class FieldwrightAppViewModel(
    private val container: FieldwrightContainer,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    /** One-shot messages for the shared snackbar (navigation-level events only). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val chrome: StateFlow<ChromeState> = combine(
        container.operator.observeOperator(),
        container.operator.observeAvailability(),
        container.sync.observeSyncState(),
    ) { operator, availability, sync ->
        ChromeState(
            operatorLabel = operator.displayName,
            operatorInitials = operator.initials,
            areaLabel = availability.areaLabel,
            isOnline = availability.isOnline,
            syncLabel = syncLabel(sync.phase, sync.queued, sync.deadLetters),
            queuedCount = sync.queued,
            isDemo = container.demoMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChromeState(isDemo = container.demoMode),
    )

    private var lastFix: GeoPoint? = null

    init {
        viewModelScope.launch {
            locationProvider.observeFix().collect { lastFix = it }
        }
    }

    /** Latest fix, or the labelled demo fix when the device has none yet. */
    fun currentFix(): GeoPoint = lastFix ?: locationProvider.currentFix() ?: DemoDistrict.fix(System.currentTimeMillis())

    fun notify(message: String) {
        viewModelScope.launch { messages.emit(message) }
    }

    private fun syncLabel(phase: SyncPhase, queued: Int, deadLetters: Int): String? = when {
        deadLetters > 0 -> "$deadLetters stuck"
        phase == SyncPhase.RETRYING -> "Retry needed"
        queued > 0 -> "$queued waiting"
        else -> null
    }
}
