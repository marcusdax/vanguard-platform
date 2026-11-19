package com.odasi.fieldwright.feature.jobs

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
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.design.FieldwrightTheme
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwChainHash
import com.odasi.fieldwright.design.FwCountdownRing
import com.odasi.fieldwright.design.FwEmptyState
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwMoneyText
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwProgressSteps
import com.odasi.fieldwright.design.FwSecondaryButton
import com.odasi.fieldwright.design.FwSegmentedControl
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.model.DispatchEngine
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.Money

/**
 * Jobs list. Stateless: one [JobsUiState] in, callbacks out, so every filter and every empty
 * or failure state is previewable. Content only — AppRoot owns the top bar, the dock and the
 * app snackbar; transient outcomes are inline notices here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    state: JobsUiState,
    onFilterSelected: (JobsFilter) -> Unit,
    onRefresh: () -> Unit,
    onAcceptOffer: (String) -> Unit,
    onPassOffer: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenCapture: (String) -> Unit,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenPacket: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        JobsLoading(modifier = modifier)
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
                Column {
                    Text(text = "Jobs", style = Fw.type.displayXl, color = Fw.colors.ink)
                    Spacer(Modifier.height(Fw.space.xxs))
                    Text(
                        text = "${state.visibleCount} ${state.filter.label.lowercase()} · pull to refresh",
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                }
            }
            item(key = "filter") {
                FwSegmentedControl(
                    options = JobsFilter.entries.map { filter -> filter.label },
                    selectedIndex = state.filter.ordinal,
                    onSelect = { index -> onFilterSelected(JobsFilter.entries[index]) },
                )
            }
            state.errorMessage?.let { message ->
                item(key = "error") {
                    FwNotice(
                        title = "That did not go through",
                        body = message,
                        tone = PillTone.FLAG,
                        actionLabel = "Dismiss",
                        onAction = onDismissError,
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
            when (state.filter) {
                JobsFilter.OFFERS -> {
                    if (state.offers.isEmpty()) {
                        item(key = "offers-empty") {
                            FwCard {
                                FwEmptyState(
                                    title = "No open offers",
                                    body = "Offers last 45 seconds and then go back to dispatch. Pull down to ask for a fresh ranking.",
                                    actionLabel = "Refresh",
                                    onAction = onRefresh,
                                )
                            }
                        }
                    } else {
                        items(items = state.offers, key = { offer -> offer.offer.id }) { offer ->
                            JobsOfferCard(
                                offer = offer,
                                accepting = state.acceptingOfferId == offer.offer.id,
                                onAccept = onAcceptOffer,
                                onPass = onPassOffer,
                                onOpenJob = onOpenJob,
                            )
                        }
                    }
                }

                JobsFilter.ACTIVE -> {
                    if (state.activeJobs.isEmpty()) {
                        item(key = "active-empty") {
                            FwCard {
                                FwEmptyState(
                                    title = "No active work",
                                    body = "Accepted jobs appear here with their SLA clock, checklist progress and the single next action.",
                                    actionLabel = "See offers",
                                    onAction = { onFilterSelected(JobsFilter.OFFERS) },
                                )
                            }
                        }
                    } else {
                        items(items = state.activeJobs, key = { job -> job.id }) { job ->
                            ActiveJobCard(
                                job = job,
                                onOpenJob = onOpenJob,
                                onOpenCapture = onOpenCapture,
                            )
                        }
                    }
                }

                JobsFilter.DONE -> {
                    if (state.completedJobs.isEmpty()) {
                        item(key = "done-empty") {
                            FwCard {
                                FwEmptyState(
                                    title = "Nothing finished yet",
                                    body = "Sealed packets land here with the payout, the packet fingerprint and a way back into the job.",
                                )
                            }
                        }
                    } else {
                        items(items = state.completedJobs, key = { job -> job.id }) { job ->
                            DoneJobCard(
                                job = job,
                                packetId = state.packetIds[job.id],
                                packetHash = state.packetHashes[job.id],
                                onOpenJob = onOpenJob,
                                onOpenPacket = onOpenPacket,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobsOfferCard(
    offer: JobsOffer,
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
private fun ActiveJobCard(
    job: Job,
    onOpenJob: (String) -> Unit,
    onOpenCapture: (String) -> Unit,
) {
    val action = listActionFor(job.status)
    FwCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FwPill(text = job.status.label, tone = jobStatusTone(job.status))
            Spacer(Modifier.width(Fw.space.xs))
            FwPill(text = job.type.shortLabel, tone = PillTone.NEUTRAL)
        }
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = job.title,
            style = Fw.type.title,
            color = Fw.colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Fw.space.xxs))
        Text(
            text = "${job.requester.org} · ${job.site.address}",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Fw.space.md))
        FwProgressSteps(labels = WORKFLOW_STEPS, current = workflowStepIndex(job.status))
        Spacer(Modifier.height(Fw.space.md))
        if (action == JobsListAction.OPEN_JOB) {
            FwPrimaryButton(
                text = listActionLabel(action),
                onClick = { onOpenJob(job.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
                FwPrimaryButton(
                    text = listActionLabel(action),
                    onClick = { onOpenCapture(job.id) },
                    modifier = Modifier.weight(1f),
                )
                FwGhostButton(
                    text = "Details",
                    onClick = { onOpenJob(job.id) },
                )
            }
        }
    }
}

@Composable
private fun DoneJobCard(
    job: Job,
    packetId: String?,
    packetHash: String?,
    onOpenJob: (String) -> Unit,
    onOpenPacket: ((String) -> Unit)?,
) {
    FwCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FwPill(text = job.status.label, tone = jobStatusTone(job.status))
            Spacer(Modifier.width(Fw.space.xs))
            FwPill(text = job.type.shortLabel, tone = PillTone.NEUTRAL)
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
            text = "${job.requester.org} · ${job.site.address}",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Fw.space.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            FwMoneyText(cents = job.payoutCents, style = Fw.type.numericLarge)
            Spacer(Modifier.width(Fw.space.xs))
            Text(text = "paid to you", style = Fw.type.meta, color = Fw.colors.inkSoft)
        }
        Spacer(Modifier.height(Fw.space.xs))
        if (packetHash != null) {
            FwChainHash(hash = packetHash, label = "Packet")
        } else {
            Text(
                text = "Packet fingerprint is recorded on the packet; open the job to view it.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
        Spacer(Modifier.height(Fw.space.md))
        if (onOpenPacket != null && packetId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
                FwPrimaryButton(
                    text = "Open packet",
                    onClick = { onOpenPacket(packetId) },
                    modifier = Modifier.weight(1f),
                )
                FwSecondaryButton(
                    text = "Open job",
                    onClick = { onOpenJob(job.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            FwSecondaryButton(
                text = "Open job",
                onClick = { onOpenJob(job.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun JobsLoading(modifier: Modifier = Modifier) {
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
            Text(text = "Ranking your work", style = Fw.type.heading, color = Fw.colors.ink)
            Spacer(Modifier.height(Fw.space.xxs))
            Text(
                text = "Offers, active jobs and finished packets.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
    }
}

internal fun jobStatusTone(status: JobStatus): PillTone = when (status) {
    JobStatus.ACCEPTED, JobStatus.EN_ROUTE, JobStatus.ON_SITE, JobStatus.IN_PROGRESS -> PillTone.OLIVE
    JobStatus.PACKET_READY, JobStatus.SEALED, JobStatus.SUBMITTED, JobStatus.APPROVED, JobStatus.PAID -> PillTone.OLIVE
    JobStatus.UNDER_REVIEW -> PillTone.AMBER
    JobStatus.DISPUTED -> PillTone.FLAG
    JobStatus.CANCELLED, JobStatus.EXPIRED -> PillTone.NEUTRAL
    else -> PillTone.AMBER
}

// ---------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------

@Composable
private fun JobsPreviewScreen(state: JobsUiState) {
    JobsScreen(
        state = state,
        onFilterSelected = {},
        onRefresh = {},
        onAcceptOffer = {},
        onPassOffer = {},
        onOpenJob = {},
        onOpenCapture = {},
        onDismissError = {},
        onDismissMessage = {},
    )
}

@Preview(name = "Jobs · offers", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun JobsOffersPreview() {
    FieldwrightTheme { JobsPreviewScreen(JobsPreview.jobsState(JobsFilter.OFFERS)) }
}

@Preview(name = "Jobs · active", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun JobsActivePreview() {
    FieldwrightTheme { JobsPreviewScreen(JobsPreview.jobsState(JobsFilter.ACTIVE)) }
}

@Preview(name = "Jobs · done", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun JobsDonePreview() {
    FieldwrightTheme { JobsPreviewScreen(JobsPreview.jobsState(JobsFilter.DONE)) }
}

@Preview(name = "Jobs · empty", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun JobsEmptyPreview() {
    FieldwrightTheme { JobsPreviewScreen(JobsPreview.emptyState(JobsFilter.ACTIVE)) }
}

@Preview(name = "Jobs · error", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun JobsErrorPreview() {
    FieldwrightTheme { JobsPreviewScreen(JobsPreview.errorState()) }
}

@Preview(name = "Jobs · loading", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun JobsLoadingPreview() {
    FieldwrightTheme { JobsPreviewScreen(JobsUiState(loading = true, nowEpochMs = JobsPreview.NOW)) }
}
