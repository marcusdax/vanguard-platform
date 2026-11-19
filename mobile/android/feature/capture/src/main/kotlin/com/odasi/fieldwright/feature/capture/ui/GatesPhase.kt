package com.odasi.fieldwright.feature.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwKeyValue
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwStat
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.feature.capture.CaptureState
import com.odasi.fieldwright.feature.capture.GateReview
import com.odasi.fieldwright.model.GateCheck

/**
 * Phase 3 — gates review.
 *
 * Gates are evaluated by the repository against the records on file. The screen's job is to
 * show every gate honestly (pass/fail + reason), mark blocking failures as blocking, and
 * offer one route back to the step that can fix each one. Only blocking failures disable
 * sealing; non-blocking notes are still shown.
 */
@Composable
internal fun GatesPhase(
    state: CaptureState,
    callbacks: CaptureCallbacks,
    modifier: Modifier = Modifier,
) {
    val gates: GateReview = state.gates
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Fw.space.gutter, vertical = Fw.space.sm),
        verticalArrangement = Arrangement.spacedBy(Fw.space.md),
    ) {
        PhaseHeader(
            overline = "Gates review",
            title = "What stands between this packet and a seal",
            body = "Each gate is checked against the records on file, not against what this screen believes it captured.",
        )

        when {
            gates.running -> FwNotice(
                title = "Running gates",
                body = "Checking consent, site, media hashing and redaction, calibration and the chain.",
                tone = PillTone.NEUTRAL,
            )

            gates.error != null -> FwNotice(
                title = "Gates could not run",
                body = gates.error,
                tone = PillTone.FLAG,
                actionLabel = "Retry gates",
                onAction = callbacks.onRefreshGates,
            )

            !gates.hasRun -> FwNotice(
                title = "Gates not run",
                body = "Run the gates to see exactly what blocks the seal. Nothing is sealed until they pass.",
                tone = PillTone.AMBER,
                actionLabel = "Run gates",
                onAction = callbacks.onRefreshGates,
            )

            else -> {
                GateSummary(state = state)
                gates.checks.forEach { gate -> GateCard(gate = gate, onFix = callbacks.onFixGate) }
                when {
                    gates.blockingFailures.isNotEmpty() -> FwNotice(
                        title = "Sealing is disabled",
                        body = "Blocking: ${gates.blockingFailures.joinToString { it.label }}. Fix each one, then run the gates again.",
                        tone = PillTone.FLAG,
                    )

                    gates.nonBlockingFailures.isNotEmpty() -> FwNotice(
                        title = "Notes recorded, not blocking",
                        body = "A non-blocking note does not stop the seal, but it is recorded in the packet: ${gates.nonBlockingFailures.joinToString { it.label }}.",
                        tone = PillTone.AMBER,
                    )

                    gates.canSeal -> FwNotice(
                        title = "Ready to seal",
                        body = "Every blocking gate passes. Sealing is irreversible: the packet's records are hashed, signed and appended to the chain.",
                        tone = PillTone.OLIVE,
                    )
                }
            }
        }
    }
}

/** Packet summary shown before the seal: what exactly is about to be sealed. */
@Composable
private fun GateSummary(state: CaptureState) {
    val gates: GateReview = state.gates
    val frames = state.evidence.steps.sumOf { it.media.size }
    val measurements = state.evidence.steps.mapNotNull { it.savedMeasurement }.size
    val recorded = state.consent.recorded
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "PACKET SUMMARY",
            style = Fw.type.micro,
            color = Fw.colors.inkSoft,
        )
        Text(
            text = state.jobTitle,
            style = Fw.type.heading,
            color = Fw.colors.ink,
        )
        FwKeyValue(label = "Site", value = state.siteLabel.ifBlank { "—" })
        FwKeyValue(
            label = "Checklist",
            value = "${state.evidence.completedRequired} of ${state.evidence.requiredTotal} required complete",
        )
        FwKeyValue(label = "Frames", value = "$frames hashed" + if (state.evidence.hasSimulatedFrames) " · ${state.evidence.simulatedFrameCount} simulated" else "")
        FwKeyValue(label = "Measurements", value = "$measurements calibrated")
        FwKeyValue(
            label = "Consent",
            value = recorded?.let { "${it.name} · ${it.role}" } ?: "Not on file",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Fw.space.md),
        ) {
            FwStat(label = "Passing", value = "${gates.passedCount}/${gates.checks.size}")
            FwStat(label = "Blocking", value = "${gates.blockingFailures.size}")
            FwStat(label = "Notes", value = "${gates.nonBlockingFailures.size}")
        }
    }
}

@Composable
private fun GateCard(gate: GateCheck, onFix: (GateCheck) -> Unit) {
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            FwPill(
                text = when {
                    gate.passed -> "Pass"
                    gate.blocking -> "Blocking"
                    else -> "Note"
                },
                tone = when {
                    gate.passed -> PillTone.OLIVE
                    gate.blocking -> PillTone.FLAG
                    else -> PillTone.AMBER
                },
            )
            Column(Modifier.weight(1f).padding(start = Fw.space.xs)) {
                Text(text = gate.label, style = Fw.type.bodyStrong, color = Fw.colors.ink)
                Text(text = gate.detail, style = Fw.type.meta, color = Fw.colors.inkSoft)
            }
        }
        if (!gate.passed) {
            FwGhostButton(text = "Fix this", onClick = { onFix(gate) })
        }
    }
}

@Preview(name = "Capture · gates blocked", widthDp = 400, heightDp = 1100, showBackground = true)
@Composable
private fun GatesBlockedPreview() {
    CapturePreviewSurface {
        GatesPhase(state = CaptureSamples.gatesBlockedState(), callbacks = CaptureCallbacks())
    }
}

@Preview(name = "Capture · gates clear", widthDp = 400, heightDp = 1000, showBackground = true)
@Composable
private fun GatesClearPreview() {
    CapturePreviewSurface {
        GatesPhase(state = CaptureSamples.gatesClearState(), callbacks = CaptureCallbacks())
    }
}

@Preview(name = "Capture · gates error", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun GatesErrorPreview() {
    CapturePreviewSurface {
        GatesPhase(state = CaptureSamples.gatesErrorState(), callbacks = CaptureCallbacks())
    }
}
