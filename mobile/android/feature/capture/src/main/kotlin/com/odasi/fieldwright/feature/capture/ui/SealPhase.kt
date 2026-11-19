package com.odasi.fieldwright.feature.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwChainHash
import com.odasi.fieldwright.design.FwKeyValue
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwProgressSteps
import com.odasi.fieldwright.design.FwSealMark
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.feature.capture.CaptureState
import com.odasi.fieldwright.feature.capture.SealProgress
import com.odasi.fieldwright.feature.capture.SealStage

/**
 * Phase 4 — seal.
 *
 * The staged progress is a narration of the documented sequence (canonicalise → hash → sign
 * → chain append), not a claim of success. The success hero renders only when the repository
 * returns a sealed packet with a passing seal evaluation.
 */
@Composable
internal fun SealPhase(
    state: CaptureState,
    callbacks: CaptureCallbacks,
    modifier: Modifier = Modifier,
) {
    val seal = state.seal
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Fw.space.gutter, vertical = Fw.space.sm),
        verticalArrangement = Arrangement.spacedBy(Fw.space.md),
    ) {
        when {
            seal.isSealed -> SealedHero(state = state, seal = seal, callbacks = callbacks)
            seal.stage == SealStage.FAILED -> SealFailed(state = state, seal = seal, callbacks = callbacks)
            else -> SealingProgress(seal = seal)
        }
    }
}

@Composable
private fun SealingProgress(seal: SealProgress) {
    PhaseHeader(
        overline = "Seal",
        title = "Sealing the packet",
        body = "Canonicalise the records, hash them, sign the packet and append it to the chain. None of this can be undone.",
    )
    FwCard(modifier = Modifier.fillMaxWidth()) {
        FwProgressSteps(
            labels = SEAL_STEP_LABELS,
            current = currentStageIndex(seal.stage),
        )
        Text(
            text = seal.stage.detail,
            style = Fw.type.body,
            color = Fw.colors.inkSoft,
        )
    }
    FwNotice(
        title = "Stay on this screen",
        body = "The seal runs on this device. If the app closes mid-seal, nothing is sealed and the packet can be sealed again.",
        tone = PillTone.NEUTRAL,
    )
}

@Composable
private fun SealFailed(state: CaptureState, seal: SealProgress, callbacks: CaptureCallbacks) {
    PhaseHeader(
        overline = "Seal",
        title = "The packet was not sealed",
        body = "Nothing was written. The evidence is unchanged and can be sealed again once the cause is fixed.",
    )
    FwNotice(
        title = "Seal failed",
        body = seal.error ?: "The repository did not return a sealed packet.",
        tone = PillTone.FLAG,
    )
    if (state.gates.blockingFailures.isNotEmpty()) {
        FwNotice(
            title = "Still blocking",
            body = state.gates.blockingFailures.joinToString { it.label } + ". Run the gates to see the current state.",
            tone = PillTone.AMBER,
            actionLabel = "Run gates",
            onAction = callbacks.onRefreshGates,
        )
    }
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "WHAT TO DO NEXT", style = Fw.type.micro, color = Fw.colors.inkSoft)
        Text(
            text = "Retry seal if the failure was transient. If a gate is blocking, go back to the evidence and fix the step it names; the seal will not skip it.",
            style = Fw.type.body,
            color = Fw.colors.ink,
        )
    }
}

@Composable
private fun SealedHero(state: CaptureState, seal: SealProgress, callbacks: CaptureCallbacks) {
    val outcome = seal.outcome ?: return
    val packet = outcome.packet
    PhaseHeader(
        overline = "Sealed",
        title = "The packet is sealed",
        body = "Evidence is now immutable. Corrections after this point are appended records, never edits.",
    )
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FwSealMark(sealed = true, size = Fw.dimens.seal * 1.5f)
            Spacer(Modifier.width(Fw.space.sm))
            Column {
                Text(text = "VANGUARD FIELD PACKET", style = Fw.type.micro, color = Fw.colors.oliveDeep)
                Text(text = packet.id, style = Fw.type.displayL, color = Fw.colors.ink)
            }
        }
        if (packet.demo || state.demo) {
            FwPill(text = "Sample · excluded from log", tone = PillTone.NEUTRAL)
        }
        FwKeyValue(label = "Tier", value = packet.tier.label)
        FwKeyValue(label = "Records", value = "${packet.recordCount} appended")
        FwKeyValue(label = "As of", value = formatTimestamp(packet.asOfEpochMs))
        FwChainHash(hash = packet.packetHash ?: packet.shortHash, label = "Head hash")
        if (outcome.verification.valid) {
            FwNotice(
                title = "Chain verified",
                body = "${outcome.verification.entries} records walk clean from genesis. ${outcome.verification.detail}",
                tone = PillTone.OLIVE,
            )
        } else {
            FwNotice(
                title = "Chain problem",
                body = "The chain did not verify: ${outcome.verification.detail}",
                tone = PillTone.FLAG,
            )
        }
        if (outcome.evaluation.reasons.isNotEmpty()) {
            FwNotice(
                title = "Seal notes",
                body = outcome.evaluation.reasons.joinToString(" "),
                tone = PillTone.AMBER,
            )
        }
        if (seal.submitted) {
            FwNotice(
                title = "Submission queued",
                body = "The sealed packet is queued to upload. Submission does not change the evidence.",
                tone = PillTone.OLIVE,
            )
        } else {
            Text(
                text = "Submit queues the packet for upload offline-first. The seal is already recorded.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
    }
}

private val SEAL_STEP_LABELS: List<String> = listOf("Canonicalise", "Hash", "Sign", "Chain append", "Sealed")

private fun currentStageIndex(stage: SealStage): Int = when (stage) {
    SealStage.IDLE -> 0
    SealStage.CANONICALISING -> 0
    SealStage.HASHING -> 1
    SealStage.SIGNING -> 2
    SealStage.CHAIN_APPENDING -> 3
    SealStage.SEALED -> 4
    SealStage.FAILED -> 0
}

@Preview(name = "Capture · sealing", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun SealingPreview() {
    CapturePreviewSurface {
        SealPhase(state = CaptureSamples.sealInProgressState(), callbacks = CaptureCallbacks())
    }
}

@Preview(name = "Capture · sealed", widthDp = 400, heightDp = 1200, showBackground = true)
@Composable
private fun SealedPreview() {
    CapturePreviewSurface {
        SealPhase(state = CaptureSamples.sealedState(), callbacks = CaptureCallbacks())
    }
}

@Preview(name = "Capture · seal submitted", widthDp = 400, heightDp = 1200, showBackground = true)
@Composable
private fun SealSubmittedPreview() {
    CapturePreviewSurface {
        SealPhase(state = CaptureSamples.sealedState(submitted = true), callbacks = CaptureCallbacks())
    }
}

@Preview(name = "Capture · seal failed", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun SealFailedPreview() {
    CapturePreviewSurface {
        SealPhase(state = CaptureSamples.sealFailedState(), callbacks = CaptureCallbacks())
    }
}
