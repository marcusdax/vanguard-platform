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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.data.ArrivalCheck
import com.odasi.fieldwright.design.ChecklistRowState
import com.odasi.fieldwright.design.FieldwrightTheme
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwChecklistRow
import com.odasi.fieldwright.design.FwEmptyState
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwKeyValue
import com.odasi.fieldwright.design.FwMoneyText
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwScopeCallout
import com.odasi.fieldwright.design.FwSecondaryButton
import com.odasi.fieldwright.design.FwSectionRule
import com.odasi.fieldwright.design.FwStat
import com.odasi.fieldwright.design.FwTextField
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.model.FeeSchedule
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.Money
import com.odasi.fieldwright.model.ProofKind
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Job detail. Stateless: the lifecycle decides which one action exists (see
 * [JobDetailUiState.action]); the screen only renders it.
 *
 * Arrival is shown as what it is — a geofence check with a distance and an accuracy — and a
 * miss opens an override sheet that requires a written reason. The screen never offers a
 * "mark arrived" button that bypasses the check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    state: JobDetailUiState,
    onBack: () -> Unit,
    onAcceptOffer: () -> Unit,
    onEnRoute: () -> Unit,
    onCheckArrival: () -> Unit,
    onDismissArrival: () -> Unit,
    onOverrideReasonChange: (String) -> Unit,
    onConfirmArrival: () -> Unit,
    onStartCapture: () -> Unit,
    onContinueCapture: () -> Unit,
    onOpenPacket: () -> Unit,
    onNavigate: (Job) -> Unit,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Fw.colors.desk),
    ) {
        when {
            state.loading -> JobDetailLoading()
            state.job == null -> MissingJob(onBack = onBack)
            else -> JobDetailContent(
                state = state,
                job = state.job,
                onBack = onBack,
                onAcceptOffer = onAcceptOffer,
                onEnRoute = onEnRoute,
                onCheckArrival = onCheckArrival,
                onDismissArrival = onDismissArrival,
                onConfirmArrival = onConfirmArrival,
                onStartCapture = onStartCapture,
                onContinueCapture = onContinueCapture,
                onOpenPacket = onOpenPacket,
                onNavigate = onNavigate,
                onDismissError = onDismissError,
                onDismissMessage = onDismissMessage,
            )
        }
        if (state.arrival != null && state.overrideRequired) {
            ModalBottomSheet(
                onDismissRequest = onDismissArrival,
                containerColor = Fw.colors.sheet,
                shape = Fw.shape.sheet,
            ) {
                OverrideSheetContent(
                    state = state,
                    onOverrideReasonChange = onOverrideReasonChange,
                    onConfirmArrival = onConfirmArrival,
                    onDismissArrival = onDismissArrival,
                )
            }
        }
    }
}

@Composable
private fun JobDetailContent(
    state: JobDetailUiState,
    job: Job,
    onBack: () -> Unit,
    onAcceptOffer: () -> Unit,
    onEnRoute: () -> Unit,
    onCheckArrival: () -> Unit,
    onDismissArrival: () -> Unit,
    onConfirmArrival: () -> Unit,
    onStartCapture: () -> Unit,
    onContinueCapture: () -> Unit,
    onOpenPacket: () -> Unit,
    onNavigate: (Job) -> Unit,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val remainingMinutes = state.slaRemainingMinutes
    val feeSchedule = FeeSchedule()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Fw.space.gutter,
            end = Fw.space.gutter,
            top = Fw.space.sm,
            bottom = Fw.space.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Fw.dimens.cardGap),
    ) {
        item(key = "back") {
            Row(Modifier.fillMaxWidth()) {
                FwGhostButton(text = "← Back", onClick = onBack)
            }
        }
        item(key = "header") {
            Column {
                Text(
                    text = job.id.uppercase(),
                    style = Fw.type.micro,
                    color = Fw.colors.inkSoft,
                )
                Spacer(Modifier.height(Fw.space.xxs))
                Text(
                    text = job.title,
                    style = Fw.type.title,
                    color = Fw.colors.ink,
                )
                Spacer(Modifier.height(Fw.space.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Fw.space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FwPill(text = job.status.label, tone = jobStatusTone(job.status))
                    FwPill(text = job.requester.kind.label, tone = PillTone.NEUTRAL)
                    if (job.demo) {
                        FwPill(text = "Sample book", tone = PillTone.AMBER)
                    }
                }
                Spacer(Modifier.height(Fw.space.xs))
                Text(
                    text = "${job.requester.org} · ${job.requester.contactName ?: job.requester.billingLabel}",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
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
            item(key = "action-message") {
                FwNotice(
                    title = message,
                    body = "",
                    tone = PillTone.OLIVE,
                    actionLabel = "Dismiss",
                    onAction = onDismissMessage,
                )
            }
        }
        item(key = "sla") {
            FwCard {
                FwSectionRule(label = "SLA clock")
                Spacer(Modifier.height(Fw.space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.md)) {
                    FwStat(
                        label = "Window",
                        value = "${job.slaMinutes} min",
                        caption = if (job.acceptedAtEpochMs == null) "starts when you accept" else null,
                        modifier = Modifier.weight(1f),
                    )
                    FwStat(
                        label = "Remaining",
                        value = remainingMinutes?.let { minutes ->
                            if (minutes >= 0) "$minutes min" else "${-minutes} min over"
                        } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (remainingMinutes != null && remainingMinutes < 0) {
                    Spacer(Modifier.height(Fw.space.sm))
                    FwNotice(
                        title = "SLA overrun",
                        body = "The window closed ${-remainingMinutes} min ago. The overrun is recorded on the job; capture can still be completed.",
                        tone = PillTone.FLAG,
                    )
                }
            }
        }
        item(key = "site") {
            FwCard {
                FwSectionRule(label = "Site & access")
                Spacer(Modifier.height(Fw.space.sm))
                Text(
                    text = listOfNotNull(job.site.address, job.site.unit).joinToString(", "),
                    style = Fw.type.bodyStrong,
                    color = Fw.colors.ink,
                )
                job.site.accessNotes?.let { notes ->
                    Spacer(Modifier.height(Fw.space.sm))
                    FwScopeCallout(title = "Access notes", body = notes)
                }
                Spacer(Modifier.height(Fw.space.sm))
                FwKeyValue(label = "Geofence", value = "${job.site.geofenceRadiusMeters} m")
                FwKeyValue(label = "Plan", value = job.site.planLabel)
                Spacer(Modifier.height(Fw.space.md))
                FwSecondaryButton(
                    text = "Navigate",
                    onClick = { onNavigate(job) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "payout") {
            FwCard {
                FwSectionRule(label = "Payout")
                Spacer(Modifier.height(Fw.space.sm))
                Row(verticalAlignment = Alignment.Bottom) {
                    FwMoneyText(cents = state.payoutCents, style = Fw.type.numericLarge)
                    Spacer(Modifier.width(Fw.space.xs))
                    Text(text = "to you", style = Fw.type.meta, color = Fw.colors.inkSoft)
                }
                Spacer(Modifier.height(Fw.space.sm))
                FwKeyValue(label = "Bounty", value = Money.format(job.bountyCents))
                if (job.bonusCents > 0) {
                    FwKeyValue(label = "Bonus", value = Money.format(job.bonusCents))
                }
                state.surgeUpliftCents?.let { uplift ->
                    FwKeyValue(
                        label = "Surge ${String.format(Locale.US, "%.1f", state.surgeMultiplier ?: 1.0)}× uplift",
                        value = Money.format(uplift),
                    )
                }
                Spacer(Modifier.height(Fw.space.sm))
                FwNotice(
                    title = "Platform fee is paid by the requester",
                    body = "${feeSchedule.platformFeeBp / 100}% of the bounty is charged to ${job.requester.org}, not to you. Nothing is deducted from your payout.",
                    tone = PillTone.NEUTRAL,
                )
            }
        }
        state.packetHash?.let { hash ->
            item(key = "packet") {
                FwCard {
                    FwSectionRule(label = "Packet")
                    Spacer(Modifier.height(Fw.space.sm))
                    FwKeyValue(label = "Packet id", value = state.packetId ?: "—")
                    FwKeyValue(
                        label = "Chain head",
                        value = if (state.packetHash == null) "Not sealed" else "Sealed",
                    )
                    Spacer(Modifier.height(Fw.space.sm))
                    com.odasi.fieldwright.design.FwChainHash(hash = hash, label = "Fingerprint")
                }
            }
        }
        item(key = "checklist") {
            FwCard {
                FwSectionRule(label = "Checklist preview")
                Spacer(Modifier.height(Fw.space.sm))
                Text(
                    text = "${state.checklistProgress.first} of ${state.checklistProgress.second} recorded",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
                Spacer(Modifier.height(Fw.space.xs))
                job.checklist.forEach { step ->
                    FwChecklistRow(
                        label = step.label,
                        hint = "${proofKindLabel(step.proofKind)} · ${if (step.required) "required" else "optional"}",
                        state = ChecklistRowState.OPEN,
                        onClick = null,
                    )
                }
            }
        }
        item(key = "consent") {
            FwCard {
                FwScopeCallout(title = "Consent scope", body = job.consentScope)
                Spacer(Modifier.height(Fw.space.sm))
                Text(
                    text = "Capture outside this scope is blocked at the gate, not cleaned up afterwards.",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
        }
        val arrival = state.arrival
        if (arrival != null && !state.overrideRequired) {
            item(key = "arrival") {
                FwCard {
                    FwSectionRule(label = "Arrival check")
                    Spacer(Modifier.height(Fw.space.sm))
                    FwNotice(
                        title = "Inside the geofence",
                        body = arrivalBody(arrival, job.site.geofenceRadiusMeters),
                        tone = PillTone.OLIVE,
                    )
                    Spacer(Modifier.height(Fw.space.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
                        FwPrimaryButton(
                            text = "Confirm arrival",
                            onClick = onConfirmArrival,
                            modifier = Modifier.weight(1f),
                            loading = state.busy,
                        )
                        FwGhostButton(text = "Not yet", onClick = onDismissArrival)
                    }
                }
            }
        }
        item(key = "next-action") {
            FwCard {
                FwSectionRule(label = "Next action")
                Spacer(Modifier.height(Fw.space.sm))
                when (state.action) {
                    JobDetailAction.ACCEPT -> {
                        if (state.canAccept) {
                            FwPrimaryButton(
                                text = state.actionLabel,
                                onClick = onAcceptOffer,
                                modifier = Modifier.fillMaxWidth(),
                                loading = state.busy,
                            )
                        } else {
                            FwNotice(
                                title = "No live offer",
                                body = "Offers expire after 45 seconds and return to dispatch. Watch the Jobs list for the next match.",
                                tone = PillTone.AMBER,
                            )
                        }
                    }

                    JobDetailAction.EN_ROUTE -> FwPrimaryButton(
                        text = state.actionLabel,
                        onClick = onEnRoute,
                        modifier = Modifier.fillMaxWidth(),
                        loading = state.busy,
                    )

                    JobDetailAction.CHECK_ARRIVAL -> FwPrimaryButton(
                        text = state.actionLabel,
                        onClick = onCheckArrival,
                        modifier = Modifier.fillMaxWidth(),
                        loading = state.busy,
                    )

                    JobDetailAction.START_CAPTURE -> FwPrimaryButton(
                        text = state.actionLabel,
                        onClick = onStartCapture,
                        modifier = Modifier.fillMaxWidth(),
                        loading = state.busy,
                    )

                    JobDetailAction.CONTINUE_CAPTURE -> FwPrimaryButton(
                        text = state.actionLabel,
                        onClick = onContinueCapture,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    JobDetailAction.OPEN_PACKET -> FwPrimaryButton(
                        text = state.actionLabel,
                        onClick = onOpenPacket,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    JobDetailAction.NONE -> Text(
                        text = "No action needed from you right now. ${job.status.label} is the current state; the requester and Vanguard hold the next move.",
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                }
            }
        }
    }
}

/** The override sheet: the reason is mandatory and is recorded on the packet. */
@Composable
internal fun OverrideSheetContent(
    state: JobDetailUiState,
    onOverrideReasonChange: (String) -> Unit,
    onConfirmArrival: () -> Unit,
    onDismissArrival: () -> Unit,
) {
    val arrival = state.arrival
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Fw.space.lg)
            .padding(bottom = Fw.space.lg),
    ) {
        FwSectionRule(label = "Geofence override")
        Spacer(Modifier.height(Fw.space.sm))
        Text(
            text = "You are outside the site geofence",
            style = Fw.type.title,
            color = Fw.colors.ink,
        )
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = arrivalBody(arrival, state.geofenceRadiusMeters),
            style = Fw.type.body,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.height(Fw.space.md))
        FwTextField(
            value = state.overrideReason,
            onValueChange = onOverrideReasonChange,
            label = "Reason (recorded on the packet)",
            placeholder = "e.g. Pin sits on the roof; I am at the foot of the ladder.",
            singleLine = false,
        )
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = "The reason is appended to the packet, and the packet is flagged for review. An override is never silent.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.height(Fw.space.md))
        FwPrimaryButton(
            text = "Record override and confirm",
            onClick = onConfirmArrival,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.overrideReason.isNotBlank(),
            loading = state.busy,
        )
        Spacer(Modifier.height(Fw.space.xs))
        FwGhostButton(
            text = "Not yet",
            onClick = onDismissArrival,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MissingJob(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Fw.space.gutter),
    ) {
        FwGhostButton(text = "← Back", onClick = onBack)
        FwEmptyState(
            title = "Job not found",
            body = "This job is no longer on your list. It may have been cancelled, expired, or reassigned to another operator.",
            actionLabel = "Back to jobs",
            onAction = onBack,
        )
    }
}

@Composable
private fun JobDetailLoading() {
    Box(
        modifier = Modifier
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
            Text(text = "Opening job", style = Fw.type.heading, color = Fw.colors.ink)
        }
    }
}

private fun arrivalBody(arrival: ArrivalCheck?, geofenceRadiusMeters: Int): String {
    if (arrival == null) {
        return "No geofence reading yet."
    }
    val accuracy = arrival.accuracyMeters.roundToInt()
    // The repository's detail already names the distance and the fence; only add the fix
    // accuracy, which is the part the operator needs to judge the reading.
    return "${arrival.detail} Fix ±${accuracy} m, fence ${geofenceRadiusMeters} m."
}

private fun proofKindLabel(kind: ProofKind): String = when (kind) {
    ProofKind.PHOTO -> "Photo"
    ProofKind.MEASUREMENT -> "Measurement"
    ProofKind.OBSERVATION -> "Observation"
    ProofKind.CONSENT -> "Consent"
}

// ---------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------

@Composable
private fun JobDetailPreviewScreen(state: JobDetailUiState) {
    JobDetailScreen(
        state = state,
        onBack = {},
        onAcceptOffer = {},
        onEnRoute = {},
        onCheckArrival = {},
        onDismissArrival = {},
        onOverrideReasonChange = {},
        onConfirmArrival = {},
        onStartCapture = {},
        onContinueCapture = {},
        onOpenPacket = {},
        onNavigate = {},
        onDismissError = {},
        onDismissMessage = {},
    )
}

private fun insideGeofenceCheck(): ArrivalCheck = ArrivalCheck(
    accepted = true,
    insideGeofence = true,
    distanceMeters = 14.0,
    accuracyMeters = 9f,
    detail = "Inside the site geofence.",
    overrideRequired = false,
)

private fun outsideGeofenceCheck(): ArrivalCheck = ArrivalCheck(
    accepted = false,
    insideGeofence = false,
    distanceMeters = 340.0,
    accuracyMeters = 12f,
    detail = "You are 340 m from the pin, outside the 120 m geofence.",
    overrideRequired = true,
)

@Preview(name = "Job detail · en route", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
private fun JobDetailEnRoutePreview() {
    FieldwrightTheme {
        JobDetailPreviewScreen(JobsPreview.detailState(status = com.odasi.fieldwright.model.JobStatus.EN_ROUTE))
    }
}

@Preview(name = "Job detail · offer to accept", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
private fun JobDetailAcceptPreview() {
    FieldwrightTheme {
        JobDetailPreviewScreen(
            JobsPreview.detailState(
                status = com.odasi.fieldwright.model.JobStatus.AVAILABLE,
                withOffer = true,
            ),
        )
    }
}

@Preview(name = "Job detail · arrival inside", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
private fun JobDetailArrivalInsidePreview() {
    FieldwrightTheme {
        JobDetailPreviewScreen(
            JobsPreview.detailState(status = com.odasi.fieldwright.model.JobStatus.EN_ROUTE).copy(
                arrival = insideGeofenceCheck(),
                lastFix = JobsPreview.point(30.2712, -97.7402, accuracy = 9f),
            ),
        )
    }
}

@Preview(name = "Job detail · packet ready", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
private fun JobDetailPacketReadyPreview() {
    FieldwrightTheme {
        JobDetailPreviewScreen(JobsPreview.detailState(status = com.odasi.fieldwright.model.JobStatus.PACKET_READY))
    }
}

@Preview(name = "Job detail · override sheet", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun JobDetailOverrideSheetPreview() {
    FieldwrightTheme {
        Column(Modifier.background(Fw.colors.sheet)) {
            OverrideSheetContent(
                state = JobsPreview.detailState(status = com.odasi.fieldwright.model.JobStatus.EN_ROUTE).copy(
                    arrival = outsideGeofenceCheck(),
                    overrideReason = "Pin sits on the roof; I am at the foot of the ladder.",
                ),
                onOverrideReasonChange = {},
                onConfirmArrival = {},
                onDismissArrival = {},
            )
        }
    }
}

@Preview(name = "Job detail · loading", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun JobDetailLoadingPreview() {
    FieldwrightTheme {
        JobDetailPreviewScreen(JobDetailUiState(loading = true, nowEpochMs = JobsPreview.NOW))
    }
}
