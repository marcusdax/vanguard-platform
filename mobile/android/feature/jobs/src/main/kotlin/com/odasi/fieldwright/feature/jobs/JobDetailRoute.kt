package com.odasi.fieldwright.feature.jobs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import kotlinx.coroutines.delay

/** How often the SLA clock is recomputed while the detail is composed. */
private const val TICK_INTERVAL_MS = 1000L

/**
 * Job detail entrypoint (CONTRACTS §6). Content only — the screen renders its own compact
 * back row; AppRoot owns the app chrome.
 *
 * @param fixProvider device fix from the app. Arrival is a geofence check against a trusted
 * fix, so without one the ViewModel refuses the check rather than borrowing the site's
 * coordinates.
 */
@Composable
fun JobDetailRoute(
    repos: FieldwrightRepositories,
    jobId: String,
    onBack: () -> Unit,
    onOpenCapture: (String) -> Unit,
    onOpenPacket: (String) -> Unit,
    fixProvider: (() -> GeoPoint?)? = null,
) {
    val viewModel: JobDetailViewModel = viewModel(key = jobId) {
        JobDetailViewModel(repos = repos, jobId = jobId, fixProvider = fixProvider)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.navigationTarget) {
        when (val target = state.navigationTarget) {
            is JobDetailNavigation.Capture -> {
                onOpenCapture(jobId)
                viewModel.consumeNavigation()
            }

            is JobDetailNavigation.Packet -> {
                onOpenPacket(target.packetId)
                viewModel.consumeNavigation()
            }

            null -> Unit
        }
    }
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.onTick()
            delay(TICK_INTERVAL_MS)
        }
    }

    JobDetailScreen(
        state = state,
        onBack = onBack,
        onAcceptOffer = viewModel::acceptOffer,
        onEnRoute = viewModel::markEnRoute,
        onCheckArrival = viewModel::checkArrival,
        onDismissArrival = viewModel::dismissArrival,
        onOverrideReasonChange = viewModel::setOverrideReason,
        onConfirmArrival = viewModel::confirmArrival,
        onStartCapture = viewModel::startCapture,
        onContinueCapture = viewModel::continueCapture,
        onOpenPacket = viewModel::openPacket,
        onNavigate = { job -> launchGeoIntent(context, job) },
        onDismissError = viewModel::dismissError,
        onDismissMessage = viewModel::dismissMessage,
    )
}

/**
 * Travel: hand the site to whatever maps app the operator already trusts. A missing maps app
 * is not an error state for Fieldwright — the address and access notes remain on screen.
 */
private fun launchGeoIntent(context: Context, job: Job) {
    val point = job.site.point
    val label = Uri.encode(job.site.address)
    val uri = Uri.parse("geo:${point.lat},${point.lng}?q=${point.lat},${point.lng}($label)")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}
