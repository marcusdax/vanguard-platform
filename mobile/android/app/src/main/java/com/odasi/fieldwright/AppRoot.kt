package com.odasi.fieldwright

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.odasi.fieldwright.data.FieldwrightContainer
import com.odasi.fieldwright.design.DockTab
import com.odasi.fieldwright.design.FieldwrightTheme
import com.odasi.fieldwright.design.FwDock
import com.odasi.fieldwright.design.FwScaffold
import com.odasi.fieldwright.design.FwTopBar
import com.odasi.fieldwright.feature.brief.BriefRoute
import com.odasi.fieldwright.feature.capture.CaptureRoute
import com.odasi.fieldwright.feature.ground.GroundRoute
import com.odasi.fieldwright.feature.jobs.JobDetailRoute
import com.odasi.fieldwright.feature.jobs.JobsRoute
import com.odasi.fieldwright.feature.payouts.PayoutsRoute
import com.odasi.fieldwright.feature.spaces.SpacesRoute
import com.odasi.fieldwright.feature.vanguard.VanguardRoute
import com.odasi.fieldwright.feature.vault.PacketDetailRoute
import com.odasi.fieldwright.feature.vault.VaultRoute
import com.odasi.fieldwright.locations.LocationProvider

/**
 * Navigation and shared chrome.
 *
 * The dock and top bar are app-owned on purpose: the four daily destinations must stay
 * in the same thumb-reachable place on every screen. Feature routes therefore render
 * content only — they never draw their own scaffold.
 */
object Routes {
    const val GROUND = "ground"
    const val JOBS = "jobs"
    const val JOB_DETAIL = "jobs/{jobId}"
    const val CAPTURE = "jobs/{jobId}/capture"
    const val VAULT = "vault"
    const val PACKET_DETAIL = "vault/{packetId}"
    const val PAYOUTS = "payouts"
    const val SPACES = "spaces"
    const val VANGUARD = "vanguard"
    const val BRIEF = "brief"

    fun jobDetail(jobId: String) = "jobs/$jobId"
    fun capture(jobId: String) = "jobs/$jobId/capture"
    fun packetDetail(packetId: String) = "vault/$packetId"
}

@Composable
fun AppRoot(
    container: FieldwrightContainer,
    locationProvider: LocationProvider,
) {
    val appViewModel: FieldwrightAppViewModel = viewModel {
        FieldwrightAppViewModel(container, locationProvider)
    }
    val chrome by appViewModel.chrome.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val immersive = route == Routes.CAPTURE
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    FieldwrightTheme {
        FwScaffold(
            topBar = {
                if (!immersive) {
                    FwTopBar(
                        operatorLabel = chrome.operatorLabel,
                        sampleChip = chrome.isDemo,
                        syncLabel = chrome.syncLabel,
                        onBrief = { navController.navigateSingleTop(Routes.BRIEF) },
                    )
                }
            },
            dock = {
                if (!immersive) {
                    FwDock(
                        selected = tabFor(route),
                        onSelect = { tab ->
                            navController.navigate(tab.route) {
                                popUpTo(Routes.GROUND) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.GROUND,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.GROUND) {
                    GroundRoute(
                        repos = container,
                        onOpenJob = { navController.navigate(Routes.jobDetail(it)) },
                        onOpenBrief = { navController.navigateSingleTop(Routes.BRIEF) },
                    )
                }
                composable(Routes.JOBS) {
                    JobsRoute(
                        repos = container,
                        onOpenJob = { navController.navigate(Routes.jobDetail(it)) },
                        onOpenCapture = { navController.navigate(Routes.capture(it)) },
                    )
                }
                composable(
                    route = Routes.JOB_DETAIL,
                    arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
                ) { entry ->
                    JobDetailRoute(
                        repos = container,
                        jobId = entry.arguments?.getString("jobId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenCapture = { navController.navigate(Routes.capture(it)) },
                        onOpenPacket = { navController.navigate(Routes.packetDetail(it)) },
                    )
                }
                composable(
                    route = Routes.CAPTURE,
                    arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
                ) { entry ->
                    CaptureRoute(
                        repos = container,
                        jobId = entry.arguments?.getString("jobId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onSealed = { packetId ->
                            navController.navigate(Routes.packetDetail(packetId)) {
                                popUpTo(Routes.JOBS) { inclusive = false }
                            }
                        },
                    )
                }
                composable(Routes.VAULT) {
                    VaultRoute(
                        repos = container,
                        onOpenPacket = { navController.navigate(Routes.packetDetail(it)) },
                        onOpenBrief = { navController.navigateSingleTop(Routes.BRIEF) },
                        onOpenJob = { navController.navigate(Routes.jobDetail(it)) },
                    )
                }
                composable(
                    route = Routes.PACKET_DETAIL,
                    arguments = listOf(navArgument("packetId") { type = NavType.StringType }),
                ) { entry ->
                    PacketDetailRoute(
                        repos = container,
                        packetId = entry.arguments?.getString("packetId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenJob = { navController.navigate(Routes.jobDetail(it)) },
                    )
                }
                composable(Routes.PAYOUTS) {
                    PayoutsRoute(
                        repos = container,
                        onOpenBrief = { navController.navigateSingleTop(Routes.BRIEF) },
                    )
                }
                composable(Routes.SPACES) {
                    SpacesRoute(
                        repos = container,
                        onOpenBrief = { navController.navigateSingleTop(Routes.BRIEF) },
                    )
                }
                composable(Routes.VANGUARD) {
                    VanguardRoute()
                }
                composable(Routes.BRIEF) {
                    BriefRoute(
                        repos = container,
                        demoMode = chrome.isDemo,
                        versionLabel = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                        onBack = { navController.popBackStack() },
                        onResetSampleBook = {
                            container.resetSampleBook()
                        },
                        onNotify = appViewModel::notify,
                    )
                }
            }
        }
    }
}

private fun tabFor(route: String?): DockTab = when (route) {
    Routes.JOBS, Routes.JOB_DETAIL, Routes.CAPTURE -> DockTab.JOBS
    Routes.SPACES -> DockTab.SPACES
    Routes.VAULT, Routes.PACKET_DETAIL -> DockTab.VAULT
    Routes.PAYOUTS -> DockTab.PAYOUTS
    else -> DockTab.GROUND
}

private fun androidx.navigation.NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}

private val DockTab.route: String
    get() = when (this) {
        DockTab.GROUND -> Routes.GROUND
        DockTab.JOBS -> Routes.JOBS
        DockTab.SPACES -> Routes.SPACES
        DockTab.VAULT -> Routes.VAULT
        DockTab.PAYOUTS -> Routes.PAYOUTS
    }
