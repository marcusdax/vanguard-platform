package com.odasi.fieldwright.feature.jobs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.model.GeoPoint
import kotlinx.coroutines.delay

/** How often the offer countdown is recomputed while the list is composed. */
private const val TICK_INTERVAL_MS = 250L

/**
 * Jobs list entrypoint (CONTRACTS §6). Content only — AppRoot owns the chrome.
 *
 * @param fixProvider optional device fix from the app; used for dispatch refresh ranking.
 * When absent the ViewModel uses a labelled SIMULATED estimate rather than pretending.
 * @param onOpenPacket optional vault navigation for a Done card whose packet is on file;
 * when null the Done card routes through the job detail instead.
 */
@Composable
fun JobsRoute(
    repos: FieldwrightRepositories,
    onOpenJob: (String) -> Unit,
    onOpenCapture: (String) -> Unit,
    fixProvider: (() -> GeoPoint?)? = null,
    onOpenPacket: ((String) -> Unit)? = null,
) {
    val viewModel: JobsViewModel = viewModel { JobsViewModel(repos, fixProvider = fixProvider) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.onTick()
            delay(TICK_INTERVAL_MS)
        }
    }

    JobsScreen(
        state = state,
        onFilterSelected = viewModel::setFilter,
        onRefresh = viewModel::refresh,
        onAcceptOffer = viewModel::acceptOffer,
        onPassOffer = viewModel::declineOffer,
        onOpenJob = onOpenJob,
        onOpenCapture = onOpenCapture,
        onDismissError = viewModel::dismissError,
        onDismissMessage = viewModel::dismissMessage,
        onOpenPacket = onOpenPacket,
    )
}
