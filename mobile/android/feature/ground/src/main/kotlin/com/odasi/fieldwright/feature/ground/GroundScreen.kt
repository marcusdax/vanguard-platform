package com.odasi.fieldwright.feature.ground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.design.FieldwrightTheme
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwAvailabilityToggle
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwCountdownRing
import com.odasi.fieldwright.design.FwEmptyState
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwKeyValue
import com.odasi.fieldwright.design.FwMoneyText
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwSecondaryButton
import com.odasi.fieldwright.design.FwSectionRule
import com.odasi.fieldwright.design.FwSegmentedControl
import com.odasi.fieldwright.design.FwStat
import com.odasi.fieldwright.design.FwSwitchRow
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.design.SiteMapCanvas
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.AutoAcceptPolicy
import com.odasi.fieldwright.model.ChecklistStep
import com.odasi.fieldwright.model.DispatchEngine
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HotZone
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Money
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.ProofKind
import com.odasi.fieldwright.model.Requester
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SiteRef
import com.odasi.fieldwright.model.SyncPhase
import kotlin.math.abs
import kotlin.math.roundToInt

/** Radii the operator can choose from, capped by the dispatch contract (≤ 25 km). */
internal val RADIUS_OPTIONS_METERS: List<Int> = listOf(5_000, 10_000, 15_000, 25_000)

private val RADIUS_OPTIONS_LABELS: List<String> = RADIUS_OPTIONS_METERS.map { meters ->
    "${meters / 1_000} km"
}

/**
 * Ground: should I go online, and what should I take next?
 *
 * Stateless by design — every value comes from [GroundUiState] and every action is a
 * callback, so the whole screen (including each failure state) is previewable. Content only:
 * AppRoot owns the top bar, the dock and the app snackbar, so error/offline/confirmation
 * messages are inline [FwNotice]s that the screen can dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroundScreen(
    state: GroundUiState,
    onToggleOnline: () -> Unit,
    onRadiusSelected: (Int) -> Unit,
    onAutoAcceptChanged: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onAcceptOffer: (String) -> Unit,
    onPassOffer: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenBrief: () -> Unit,
    onSelectPin: (String?) -> Unit,
    onRetrySync: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        GroundLoading(modifier = modifier)
        return
    }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier
            .fillMaxSize()
            .background(Fw.colors.desk),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Fw.space.gutter,
                end = Fw.space.gutter,
                top = Fw.space.md,
                bottom = Fw.space.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Fw.dimens.cardGap),
        ) {
            item(key = "title") {
                GroundTitle(
                    areaLabel = state.availability.areaLabel,
                    radiusMeters = state.availability.radiusMeters,
                    onOpenBrief = onOpenBrief,
                )
            }
            item(key = "availability") {
                AvailabilityCard(
                    state = state,
                    onToggleOnline = onToggleOnline,
                    onRadiusSelected = onRadiusSelected,
                    onAutoAcceptChanged = onAutoAcceptChanged,
                )
            }
            state.errorMessage?.let { message ->
                item(key = "error") {
                    FwNotice(
                        title = "That did not go through",
                        body = message,
                        tone = PillTone.FLAG,
                        actionLabel = "Retry",
                        onAction = onRefresh,
                    )
                }
            }
            if (state.isOffline) {
                item(key = "offline") {
                    FwNotice(
                        title = "Offline",
                        body = "${state.queuedMutations} changes are queued. Nothing is lost — they sync when you are back in coverage.",
                        tone = PillTone.AMBER,
                        actionLabel = "Retry now",
                        onAction = onRetrySync,
                    )
                }
            }
            state.actionMessage?.let { message ->
                item(key = "action") {
                    FwNotice(
                        title = message,
                        body = "",
                        tone = PillTone.OLIVE,
                        actionLabel = "Dismiss",
                        onAction = onDismissMessage,
                    )
                }
            }
            item(key = "map") {
                MapCard(
                    state = state,
                    onSelectPin = onSelectPin,
                    onAcceptOffer = onAcceptOffer,
                    onOpenJob = onOpenJob,
                )
            }
            if (!state.isOnline) {
                item(key = "off-clock") {
                    FwCard {
                        FwEmptyState(
                            title = "You are off the clock",
                            body = "Go online to see ranked work in ${state.availability.areaLabel}. Dispatch sends you nothing while you are paused, and your acceptance rate is not affected.",
                            actionLabel = "Go online",
                            onAction = onToggleOnline,
                        )
                    }
                }
            }
            state.nextUp?.let { job ->
                item(key = "next-up") {
                    NextUpCard(job = job, nowEpochMs = state.nowEpochMs, onOpenJob = onOpenJob)
                }
            }
            if (state.isOnline) {
                item(key = "offers-heading") {
                    SectionHeading(
                        title = "Ranked offers",
                        detail = if (state.offers.isEmpty()) {
                            "Dispatch is scanning your ring"
                        } else {
                            "${state.offers.size} open · ${state.availability.radiusMeters / 1_000} km"
                        },
                    )
                }
                if (state.offers.isEmpty()) {
                    item(key = "no-supply") {
                        FwCard {
                            FwEmptyState(
                                title = "No jobs in your ring",
                                body = "Supply refreshes every few seconds while you are online. Pull down to ask dispatch for a fresh ranking.",
                                actionLabel = "Refresh now",
                                onAction = onRefresh,
                            )
                        }
                    }
                } else {
                    items(items = state.offers, key = { it.offer.id }) { offer ->
                        OfferCard(
                            offer = offer,
                            accepting = state.acceptingOfferId == offer.offer.id,
                            onAccept = onAcceptOffer,
                            onPass = onPassOffer,
                            onOpenJob = onOpenJob,
                        )
                    }
                }
            }
            item(key = "market") {
                MarketCard(state = state)
            }
        }
    }
}

@Composable
private fun GroundTitle(
    areaLabel: String,
    radiusMeters: Int,
    onOpenBrief: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Ground",
                    style = Fw.type.displayXl,
                    color = Fw.colors.ink,
                )
                Spacer(Modifier.height(Fw.space.xxs))
                Text(
                    text = "$areaLabel · ${radiusMeters / 1_000} km ring",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
            FwGhostButton(text = "Rules", onClick = onOpenBrief)
        }
    }
}

@Composable
private fun AvailabilityCard(
    state: GroundUiState,
    onToggleOnline: () -> Unit,
    onRadiusSelected: (Int) -> Unit,
    onAutoAcceptChanged: (Boolean) -> Unit,
) {
    val availability = state.availability
    val policy = availability.autoAccept
    val radiusIndex = RADIUS_OPTIONS_METERS.indices.minByOrNull { index ->
        abs(RADIUS_OPTIONS_METERS[index] - availability.radiusMeters)
    } ?: RADIUS_OPTIONS_METERS.lastIndex
    val statusLine = if (availability.isOnline) {
        "Online · ${availability.radiusMeters / 1_000} km · ${availability.areaLabel}"
    } else {
        "Off the clock · ${availability.radiusMeters / 1_000} km · ${availability.areaLabel}"
    }
    FwCard {
        FwSectionRule(label = "Availability")
        Spacer(Modifier.height(Fw.space.sm))
        FwAvailabilityToggle(
            isOnline = availability.isOnline,
            statusLine = statusLine,
            onToggle = onToggleOnline,
        )
        Spacer(Modifier.height(Fw.space.md))
        Text(
            text = "Radius",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.height(Fw.space.xs))
        FwSegmentedControl(
            options = RADIUS_OPTIONS_LABELS,
            selectedIndex = radiusIndex,
            onSelect = { index -> onRadiusSelected(RADIUS_OPTIONS_METERS[index]) },
        )
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = "Wider radius means more drive time between jobs.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.height(Fw.space.sm))
        HorizontalDivider(
            color = Fw.colors.line,
            thickness = Fw.dimens.hairline,
        )
        FwSwitchRow(
            title = "Auto-accept under ${Money.format(policy.minPayoutCents)} · max ${policy.maxActiveStack} stacked",
            subtitle = "Written policy · every automatic accept and every pass records a reason",
            checked = policy.enabled,
            onCheckedChange = onAutoAcceptChanged,
        )
        HorizontalDivider(
            color = Fw.colors.line,
            thickness = Fw.dimens.hairline,
        )
        Spacer(Modifier.height(Fw.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.lg)) {
            FwStat(
                label = "Today",
                value = Money.format(state.todayEarningsCents),
                modifier = Modifier.weight(1f),
            )
            FwStat(
                label = "Acceptance",
                value = state.operator?.let { "${(it.acceptanceRate * 100).roundToInt()}%" } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MapCard(
    state: GroundUiState,
    onSelectPin: (String?) -> Unit,
    onAcceptOffer: (String) -> Unit,
    onOpenJob: (String) -> Unit,
) {
    FwCard {
        FwSectionRule(
            label = if (state.isOnline) "Nearby · dispatch view" else "Nearby · paused",
        )
        Spacer(Modifier.height(Fw.space.sm))
        SiteMapCanvas(
            operatorFix = state.operatorFix,
            jobs = state.mapPins,
            hotZones = state.hotZones,
            routeTo = state.routeTo,
            onPinClick = { jobId ->
                onSelectPin(if (state.selectedPinJobId == jobId) null else jobId)
            },
            geofences = listOfNotNull(state.nextUpGeofence),
            modifier = Modifier
                .fillMaxWidth()
                .height(Fw.dimens.mapHeight)
                .alpha(if (state.isOnline) 1f else Fw.alpha.muted),
            accessibleDescription = "Your site map. ${state.mapPins.size} job pins and ${state.hotZones.size} hot zones.",
        )
        val offer = state.selectedOffer
        val active = state.selectedActiveJob
        if (state.operatorFix?.source == GeoPoint.FixSource.SIMULATED) {
            Spacer(Modifier.height(Fw.space.xs))
            Text(
                text = "Estimated position · simulated fix, ±${state.operatorFix?.accuracyMeters?.roundToInt()} m",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
        if (offer != null) {
            Spacer(Modifier.height(Fw.space.sm))
            SelectedOfferRow(
                offer = offer,
                accepting = state.acceptingOfferId == offer.offer.id,
                onAccept = onAcceptOffer,
                onOpenJob = onOpenJob,
            )
        } else if (active != null) {
            Spacer(Modifier.height(Fw.space.sm))
            SelectedJobRow(job = active, onOpenJob = onOpenJob)
        } else if (state.mapPins.isNotEmpty()) {
            Spacer(Modifier.height(Fw.space.xs))
            Text(
                text = "Tap a pin to see the job and its payout.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
    }
}

@Composable
private fun SelectedOfferRow(
    offer: GroundOffer,
    accepting: Boolean,
    onAccept: (String) -> Unit,
    onOpenJob: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = offer.job.title,
                style = Fw.type.bodyStrong,
                color = Fw.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Fw.space.xxs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FwMoneyText(cents = offer.offer.payoutCents)
                Text(
                    text = " · ${Money.perHour(offer.offer.payoutCents, offer.job.estimatedMinutes)}",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
        }
        Spacer(Modifier.width(Fw.space.sm))
        FwPrimaryButton(
            text = "Accept",
            onClick = { onAccept(offer.offer.id) },
            loading = accepting,
        )
        FwGhostButton(
            text = "Open",
            onClick = { onOpenJob(offer.job.id) },
        )
    }
}

@Composable
private fun SelectedJobRow(job: Job, onOpenJob: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = job.title,
                style = Fw.type.bodyStrong,
                color = Fw.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Fw.space.xxs))
            Text(
                text = "${job.status.label} · ${job.site.address}",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
        Spacer(Modifier.width(Fw.space.sm))
        FwPrimaryButton(text = "Open job", onClick = { onOpenJob(job.id) })
    }
}

@Composable
private fun NextUpCard(job: Job, nowEpochMs: Long, onOpenJob: (String) -> Unit) {
    val slaOverdue = slaRemainingMinutes(job, nowEpochMs) < 0
    FwCard {
        FwSectionRule(label = "Next up")
        Spacer(Modifier.height(Fw.space.sm))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Fw.space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FwPill(text = job.status.label, tone = statusTone(job.status))
                    if (job.type.requiredCredentials.isNotEmpty()) {
                        FwPill(text = job.type.shortLabel, tone = PillTone.NEUTRAL)
                    }
                }
                Spacer(Modifier.height(Fw.space.xs))
                Text(
                    text = job.title,
                    style = Fw.type.title,
                    color = Fw.colors.ink,
                )
                Spacer(Modifier.height(Fw.space.xxs))
                Text(
                    text = "${job.requester.org} · ${job.site.address}",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
        }
        Spacer(Modifier.height(Fw.space.md))
        FwKeyValue(label = "ETA", value = "${job.etaMinutes} min · ${Money.miles(job.distanceMeters.toDouble())}")
        FwKeyValue(label = "SLA", value = slaLabel(job, nowEpochMs))
        FwKeyValue(
            label = "Checklist",
            value = "${job.checklist.size} steps · open the job to record",
        )
        if (slaOverdue) {
            Spacer(Modifier.height(Fw.space.sm))
            FwNotice(
                title = "SLA window is over",
                body = "The packet can still be sealed, but the overrun is recorded on the job.",
                tone = PillTone.FLAG,
            )
        }
        Spacer(Modifier.height(Fw.space.md))
        FwPrimaryButton(
            text = "Open job",
            onClick = { onOpenJob(job.id) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionHeading(title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Fw.space.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = Fw.type.heading, color = Fw.colors.ink)
            Text(text = detail, style = Fw.type.meta, color = Fw.colors.inkSoft)
        }
    }
}

@Composable
private fun OfferCard(
    offer: GroundOffer,
    accepting: Boolean,
    onAccept: (String) -> Unit,
    onPass: (String) -> Unit,
    onOpenJob: (String) -> Unit,
) {
    val job = offer.job
    FwCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Fw.space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FwPill(text = job.type.shortLabel, tone = PillTone.NEUTRAL)
                    if (offer.offer.surgeMultiplier > 1.0) {
                        FwPill(
                            text = "Surge ${String.format(java.util.Locale.US, "%.1f", offer.offer.surgeMultiplier)}×",
                            tone = PillTone.AMBER,
                        )
                    }
                    if (offer.offer.autoAcceptEligible) {
                        FwPill(text = "Auto-accept", tone = PillTone.OLIVE)
                    }
                }
                Spacer(Modifier.height(Fw.space.sm))
                Row(verticalAlignment = Alignment.Bottom) {
                    FwMoneyText(
                        cents = offer.offer.payoutCents,
                        style = Fw.type.numericLarge,
                        color = Fw.colors.ink,
                    )
                    Spacer(Modifier.width(Fw.space.xs))
                    Text(
                        text = Money.perHour(offer.offer.payoutCents, job.estimatedMinutes),
                        style = Fw.type.numeric,
                        color = Fw.colors.oliveDeep,
                    )
                }
                Spacer(Modifier.height(Fw.space.xs))
                Text(
                    text = job.title,
                    style = Fw.type.heading,
                    color = Fw.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Fw.space.xxs))
                Text(
                    text = "${Money.miles(job.distanceMeters.toDouble())} · ${offer.offer.etaMinutes} min · ${job.site.address}",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Fw.space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
                    FwPrimaryButton(
                        text = "Accept",
                        onClick = { onAccept(offer.offer.id) },
                        modifier = Modifier.weight(1f),
                        loading = accepting,
                    )
                    FwSecondaryButton(
                        text = "Details",
                        onClick = { onOpenJob(job.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Fw.space.xxs))
                FwGhostButton(
                    text = "Pass",
                    onClick = { onPass(offer.offer.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(Fw.space.md))
            FwCountdownRing(
                remainingMillis = offer.remainingMillis,
                totalMillis = DispatchEngine.OFFER_TTL_MS,
                label = "Offer expires in",
            )
        }
    }
}

@Composable
private fun MarketCard(state: GroundUiState) {
    val market = state.market
    FwCard {
        FwSectionRule(label = "Market")
        Spacer(Modifier.height(Fw.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.md)) {
            FwStat(
                label = "Demand",
                value = market?.let { demandLabel(it.demandIndex) } ?: "—",
                caption = "ranked for you",
                modifier = Modifier.weight(1f),
            )
            FwStat(
                label = "Weather",
                value = market?.weatherLabel ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Fw.space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.md)) {
            FwStat(
                label = "Daylight",
                value = market?.daylightLabel ?: "—",
                modifier = Modifier.weight(1f),
            )
            FwStat(
                label = "Response",
                value = state.operator?.let { "${(it.acceptanceRate * 100).roundToInt()}%" } ?: "—",
                caption = "your acceptance",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GroundLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Fw.colors.desk),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Fw.colors.olive,
                strokeWidth = Fw.dimens.stroke,
                trackColor = Fw.colors.line,
                modifier = Modifier.size(Fw.dimens.iconLarge),
            )
            Spacer(Modifier.height(Fw.space.md))
            Text(
                text = "Reading your market",
                style = Fw.type.heading,
                color = Fw.colors.ink,
            )
            Spacer(Modifier.height(Fw.space.xxs))
            Text(
                text = "Availability, offers and the site map.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
    }
}

private fun demandLabel(index: Double): String = when {
    index >= 0.66 -> "High"
    index >= 0.33 -> "Steady"
    else -> "Quiet"
}

private fun slaRemainingMinutes(job: Job, nowEpochMs: Long): Long {
    val acceptedAt = job.acceptedAtEpochMs ?: return job.slaMinutes.toLong()
    return ((job.slaMinutes * 60_000L) - (nowEpochMs - acceptedAt)) / 60_000L
}

private fun slaLabel(job: Job, nowEpochMs: Long): String {
    val remaining = slaRemainingMinutes(job, nowEpochMs)
    return if (remaining >= 0) {
        "$remaining min left"
    } else {
        "${-remaining} min over"
    }
}

private fun statusTone(status: JobStatus): PillTone = when (status) {
    JobStatus.ACCEPTED, JobStatus.EN_ROUTE, JobStatus.ON_SITE, JobStatus.IN_PROGRESS -> PillTone.OLIVE
    JobStatus.PACKET_READY, JobStatus.SEALED, JobStatus.SUBMITTED -> PillTone.OLIVE
    JobStatus.UNDER_REVIEW -> PillTone.AMBER
    JobStatus.DISPUTED -> PillTone.FLAG
    JobStatus.CANCELLED, JobStatus.EXPIRED -> PillTone.NEUTRAL
    else -> PillTone.AMBER
}

// ---------------------------------------------------------------------------------------
// Preview fixtures. The sample book is always demo = true (§1.4).
// ---------------------------------------------------------------------------------------

private const val PREVIEW_NOW = 1_700_000_000_000L

private fun previewPoint(lat: Double, lng: Double, accuracy: Float = 9f) = GeoPoint(
    lat = lat,
    lng = lng,
    recordedAtEpochMs = PREVIEW_NOW,
    accuracyMeters = accuracy,
)

private fun previewOperator() = Operator(
    id = "op-1",
    displayName = "Tam Nguyen",
    initials = "TN",
    credentialTier = 3,
    ratingAvg = 4.92,
    acceptanceRate = 0.86,
    onTimeRate = 0.94,
    completedJobs = 412,
    streakDays = 12,
    credentials = JobType.RPZ_TEST.requiredCredentials + JobType.PROPERTY_WALK.requiredCredentials,
    payoutAccountLast4 = "4412",
    weeklyGoalCents = 120_000,
    memberSinceLabel = "Since Mar 2023",
)

private fun previewRequester() = Requester(
    org = "Meridian Mutual",
    contactName = "Dana Reyes",
    kind = RequesterKind.INSURER,
    billingLabel = "MERIDIAN · claims",
)

private fun previewSite(address: String, lat: Double, lng: Double) = SiteRef(
    address = address,
    unit = null,
    point = previewPoint(lat, lng),
    geofenceRadiusMeters = 120,
    accessNotes = "Gate code 4412. Dog in the back yard; call first.",
    planLabel = "Creekside plat 14",
)

private fun previewChecklist(): List<ChecklistStep> = listOf(
    ChecklistStep("slope", "Four slopes", "Photo · required", ProofKind.PHOTO),
    ChecklistStep("square", "Test square", "Photo · required", ProofKind.PHOTO),
    ChecklistStep("paddle", "Measured paddle", "Measurement · required", ProofKind.MEASUREMENT),
    ChecklistStep("consent", "Consent on file", "Consent · required", ProofKind.CONSENT),
)

private fun previewJob(
    id: String,
    type: JobType,
    status: JobStatus,
    title: String,
    address: String,
    lat: Double,
    lng: Double,
    distanceMeters: Int,
    etaMinutes: Int,
    acceptedAtEpochMs: Long? = null,
): Job = Job(
    id = id,
    type = type,
    title = title,
    requester = previewRequester(),
    site = previewSite(address, lat, lng),
    status = status,
    bountyCents = type.baseBountyCents,
    bonusCents = 1_500,
    estimatedMinutes = type.estimatedMinutes,
    scheduledForEpochMs = null,
    slaMinutes = 90,
    distanceMeters = distanceMeters,
    etaMinutes = etaMinutes,
    consentScope = type.consentScope,
    checklist = previewChecklist(),
    requiredCredentials = type.requiredCredentials,
    demo = true,
    notes = "Sample book job. Not sent to the public log.",
    acceptedAtEpochMs = acceptedAtEpochMs,
)

private fun previewOffer(job: Job, surge: Double, remainingMillis: Long): GroundOffer {
    val payout = DispatchEngine.offeredPayoutCents(job, surge)
    return GroundOffer(
        offer = Offer(
            id = "offer-${job.id}",
            job = job,
            operatorId = "op-1",
            payoutCents = payout,
            surgeMultiplier = surge,
            offeredAtEpochMs = PREVIEW_NOW,
            expiresAtEpochMs = PREVIEW_NOW + remainingMillis,
            score = 0.84,
            scoreReasons = listOf("3 min from your last fix", "Credential match"),
            etaMinutes = job.etaMinutes,
            autoAcceptEligible = true,
        ),
        remainingMillis = remainingMillis,
    )
}

private fun previewMarket() = MarketSnapshot(
    nowEpochMs = PREVIEW_NOW,
    demandIndex = 0.72,
    supplyIndex = 0.31,
    hotZones = listOf(
        HotZone(
            label = "Creekside",
            point = previewPoint(30.2701, -97.7435),
            radiusMeters = 850,
            surgeMultiplier = 1.4,
            reason = "Two inspectors offline after the hail event",
        ),
    ),
    daylightLabel = "2h 40m",
    weatherLabel = "Clear 78°",
)

private fun previewAvailability(online: Boolean) = Availability(
    isOnline = online,
    updatedAtEpochMs = PREVIEW_NOW,
    radiusMeters = 15_000,
    autoAccept = AutoAcceptPolicy(enabled = true, minPayoutCents = 3_000, maxActiveStack = 2),
    areaLabel = "Creekside · MUD 14",
)

private fun previewOnlineState(): GroundUiState {
    val roof = previewJob(
        id = "job-1",
        type = JobType.ROOF_HAIL,
        status = JobStatus.AVAILABLE,
        title = "Roof hail assessment · 1841 Creekside Dr",
        address = "1841 Creekside Dr",
        lat = 30.2712,
        lng = -97.7402,
        distanceMeters = 2_400,
        etaMinutes = 8,
    )
    val rpz = previewJob(
        id = "job-2",
        type = JobType.RPZ_TEST,
        status = JobStatus.AVAILABLE,
        title = "Annual RPZ test · 902 Ridgeview",
        address = "902 Ridgeview",
        lat = 30.2662,
        lng = -97.7458,
        distanceMeters = 5_100,
        etaMinutes = 14,
    )
    val active = previewJob(
        id = "job-3",
        type = JobType.PROPERTY_WALK,
        status = JobStatus.EN_ROUTE,
        title = "Monthly property walk · Sabine Court",
        address = "410 Sabine Ct",
        lat = 30.2728,
        lng = -97.7471,
        distanceMeters = 3_800,
        etaMinutes = 11,
        acceptedAtEpochMs = PREVIEW_NOW - 25 * 60_000L,
    )
    return GroundUiState(
        loading = false,
        operator = previewOperator(),
        availability = previewAvailability(online = true),
        market = previewMarket(),
        hotZones = previewMarket().hotZones,
        operatorFix = previewPoint(30.2691, -97.7421, accuracy = 14f),
        offers = listOf(
            previewOffer(roof, surge = 1.4, remainingMillis = 32_000L),
            previewOffer(rpz, surge = 1.0, remainingMillis = 11_000L),
        ),
        activeJobs = listOf(active),
        todayEarningsCents = 18_500,
        selectedPinJobId = "job-1",
        nowEpochMs = PREVIEW_NOW,
        demo = true,
    )
}

private fun previewOffTheClockState(): GroundUiState = previewOnlineState().copy(
    availability = previewAvailability(online = false),
    offers = emptyList(),
    selectedPinJobId = null,
)

private fun previewNoSupplyState(): GroundUiState = previewOnlineState().copy(
    offers = emptyList(),
    activeJobs = emptyList(),
    selectedPinJobId = null,
)

private fun previewOfflineState(): GroundUiState = previewOnlineState().copy(
    sync = SyncState(
        phase = SyncPhase.WAITING_FOR_NETWORK,
        queued = 3,
        inFlight = 0,
        deadLetters = 0,
        lastSyncedAtEpochMs = PREVIEW_NOW - 12 * 60_000L,
        lastError = "No network",
    ),
)

private fun previewErrorState(): GroundUiState = previewOnlineState().copy(
    errorMessage = "Dispatch did not answer. Check your connection and retry.",
    offers = emptyList(),
)

private fun previewLoadingState(): GroundUiState = GroundUiState(loading = true, nowEpochMs = PREVIEW_NOW)

@Preview(name = "Ground · online", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun GroundOnlinePreview() {
    FieldwrightTheme {
        GroundScreen(state = previewOnlineState(), callbacks = previewNoOps())
    }
}

@Preview(name = "Ground · off the clock", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun GroundOffTheClockPreview() {
    FieldwrightTheme {
        GroundScreen(state = previewOffTheClockState(), callbacks = previewNoOps())
    }
}

@Preview(name = "Ground · no supply", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun GroundNoSupplyPreview() {
    FieldwrightTheme {
        GroundScreen(state = previewNoSupplyState(), callbacks = previewNoOps())
    }
}

@Preview(name = "Ground · offline", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun GroundOfflinePreview() {
    FieldwrightTheme {
        GroundScreen(state = previewOfflineState(), callbacks = previewNoOps())
    }
}

@Preview(name = "Ground · error", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun GroundErrorPreview() {
    FieldwrightTheme {
        GroundScreen(state = previewErrorState(), callbacks = previewNoOps())
    }
}

@Preview(name = "Ground · loading", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun GroundLoadingPreview() {
    FieldwrightTheme {
        GroundScreen(state = previewLoadingState(), callbacks = previewNoOps())
    }
}

/**
 * GroundScreen takes twelve callbacks; previews only need them present. Keeping one
 * helper stops six preview bodies from drifting apart.
 */
@Composable
private fun GroundScreen(state: GroundUiState, callbacks: PreviewCallbacks) {
    GroundScreen(
        state = state,
        onToggleOnline = callbacks.onToggleOnline,
        onRadiusSelected = callbacks.onRadiusSelected,
        onAutoAcceptChanged = callbacks.onAutoAcceptChanged,
        onRefresh = callbacks.onRefresh,
        onAcceptOffer = callbacks.onAcceptOffer,
        onPassOffer = callbacks.onPassOffer,
        onOpenJob = callbacks.onOpenJob,
        onOpenBrief = callbacks.onOpenBrief,
        onSelectPin = callbacks.onSelectPin,
        onRetrySync = callbacks.onRetrySync,
        onDismissMessage = callbacks.onDismissMessage,
        onDismissError = callbacks.onDismissError,
    )
}

private class PreviewCallbacks {
    val onToggleOnline: () -> Unit = {}
    val onRadiusSelected: (Int) -> Unit = {}
    val onAutoAcceptChanged: (Boolean) -> Unit = {}
    val onRefresh: () -> Unit = {}
    val onAcceptOffer: (String) -> Unit = {}
    val onPassOffer: (String) -> Unit = {}
    val onOpenJob: (String) -> Unit = {}
    val onOpenBrief: () -> Unit = {}
    val onSelectPin: (String?) -> Unit = {}
    val onRetrySync: () -> Unit = {}
    val onDismissMessage: () -> Unit = {}
    val onDismissError: () -> Unit = {}
}

private fun previewNoOps(): PreviewCallbacks = PreviewCallbacks()
