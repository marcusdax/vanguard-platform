package com.odasi.fieldwright.feature.ground

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.model.GeoPoint
import kotlinx.coroutines.delay

/** How often the countdown/expiry is recomputed while Ground is on screen. */
private const val TICK_INTERVAL_MS = 250L

/**
 * Ground entrypoint (CONTRACTS §6).
 *
 * The route owns the ViewModel, drives the offer clock while the screen is composed, and
 * forwards navigation. It renders content only: AppRoot supplies the top bar, the dock and
 * the app snackbar. The ticker lives here rather than in the ViewModel so the ViewModel has
 * no infinite coroutine and stays deterministic under `kotlinx-coroutines-test`.
 *
 * @param fixProvider optional device fix supplied by the app (AppRoot passes the real
 * location provider). When absent, the map uses a labelled SIMULATED centroid so the demo
 * never pretends to have a GPS fix it does not have.
 */
@Composable
fun GroundRoute(
    repos: FieldwrightRepositories,
    onOpenJob: (String) -> Unit,
    onOpenBrief: () -> Unit,
    fixProvider: (() -> GeoPoint?)? = null,
) {
    val viewModel: GroundViewModel = viewModel { GroundViewModel(repos, fixProvider = fixProvider) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.onTick()
            delay(TICK_INTERVAL_MS)
        }
    }

    GroundScreen(
        state = state,
        onToggleOnline = viewModel::toggleOnline,
        onRadiusSelected = viewModel::setRadius,
        onAutoAcceptChanged = viewModel::setAutoAccept,
        onRefresh = viewModel::refresh,
        onAcceptOffer = viewModel::acceptOffer,
        onPassOffer = viewModel::declineOffer,
        onOpenJob = onOpenJob,
        onOpenBrief = onOpenBrief,
        onSelectPin = viewModel::selectPin,
        onRetrySync = viewModel::retrySync,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError,
    )
}
