package com.odasi.fieldwright.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.design.ChecklistRowState
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwChainHash
import com.odasi.fieldwright.design.FwChecklistRow
import com.odasi.fieldwright.design.FwEmptyState
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwMoneyText
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwScopeCallout
import com.odasi.fieldwright.design.FwSealMark
import com.odasi.fieldwright.design.FwSecondaryButton
import com.odasi.fieldwright.design.FwSectionRule
import com.odasi.fieldwright.design.FwTextField
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.model.Badge
import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.JobCatalog
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.SealEvaluation
import com.odasi.fieldwright.model.StepResult

/**
 * Packet detail route: the sealed artefact, its append-only record chain, the derived seal
 * evaluation and every honesty state. `onOpenJob` is wired by the app root so the vault never
 * imports another feature.
 */
@Composable
fun PacketDetailRoute(
    repos: FieldwrightRepositories,
    packetId: String,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit,
) {
    val viewModel: PacketDetailViewModel = viewModel(key = "packet-$packetId") {
        PacketDetailViewModel(repos, packetId)
    }
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nowEpochMs = remember { System.currentTimeMillis() }
    var shareError by remember { mutableStateOf<String?>(null) }

    val share = state.share
    LaunchedEffect(share) {
        if (share == null) return@LaunchedEffect
        if (!sharePacketExport(context, share)) {
            shareError = "The packet was written to the cache but no share sheet could be opened."
        }
        viewModel.consumeShare()
    }

    PacketDetailScreen(
        state = state,
        nowEpochMs = nowEpochMs,
        shareError = shareError,
        onDismissShareError = { shareError = null },
        onBack = onBack,
        onVerify = viewModel::verifyChain,
        onExport = viewModel::exportPacket,
        onOpenCorrection = viewModel::openCorrection,
        onDismissCorrection = viewModel::dismissCorrection,
        onCorrectionNoteChange = viewModel::updateCorrectionNote,
        onAppendCorrection = viewModel::appendCorrection,
        onOpenJob = onOpenJob,
        onDismissError = viewModel::dismissError,
        onDismissMessage = viewModel::consumeMessage,
    )
}

@Composable
internal fun PacketDetailScreen(
    state: PacketDetailUiState,
    nowEpochMs: Long,
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onExport: () -> Unit,
    onOpenCorrection: () -> Unit,
    onDismissCorrection: () -> Unit,
    onCorrectionNoteChange: (String) -> Unit,
    onAppendCorrection: () -> Unit,
    onOpenJob: (String) -> Unit,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
    shareError: String? = null,
    onDismissShareError: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Fw.space.xs, vertical = Fw.space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(Fw.dimens.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to the vault",
                    tint = Fw.colors.ink,
                )
            }
            Text(
                text = "PACKET DETAIL",
                style = Fw.type.micro,
                color = Fw.colors.inkSoft,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = VaultStyle.gutter,
                    end = VaultStyle.gutter,
                    bottom = Fw.space.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(Fw.dimens.cardGap),
        ) {
            val packet = state.packet
            when {
                state.loading -> LoadingBlock("Opening the packet…")
                packet == null -> FwEmptyState(
                    title = "Packet not found",
                    body = "This packet is not in the vault. It may belong to another operator, or the sample book may have been reset.",
                    actionLabel = "Back to the vault",
                    onAction = onBack,
                )

                else -> {
                    if (packet.demo) {
                        FwNotice(
                            title = "SAMPLE — excluded from the log",
                            body = "This packet is demonstration data. It is not anchored in the public log and must not be presented as live evidence.",
                            tone = PillTone.AMBER,
                        )
                    }
                    state.error?.let { error ->
                        FwNotice(
                            title = "Something went wrong",
                            body = error,
                            tone = PillTone.FLAG,
                            actionLabel = "Dismiss",
                            onAction = onDismissError,
                        )
                    }
                    state.message?.let { message ->
                        FwNotice(
                            title = "Recorded",
                            body = message,
                            tone = PillTone.NEUTRAL,
                            actionLabel = "Dismiss",
                            onAction = onDismissMessage,
                        )
                    }
                    shareError?.let { error ->
                        FwNotice(
                            title = "Share sheet unavailable",
                            body = error,
                            tone = PillTone.FLAG,
                            actionLabel = "Dismiss",
                            onAction = onDismissShareError,
                        )
                    }
                    ChainHealthBanner(chain = state.chain, onVerify = onVerify, nowEpochMs = nowEpochMs)
                    SealHero(packet = packet, state = state)
                    TierCard(packet = packet, evaluation = state.sealEvaluation)
                    BadgesCard(badges = packet.badges)
                    // The engine re-evaluates the seal gates on the current chain verdict; show
                    // those when available so a withdrawn seal lists its reasons, not stale gates.
                    GatesCard(gates = state.sealEvaluation?.gates ?: packet.gates)
                    ConsentCard(packet = packet, nowEpochMs = nowEpochMs)
                    RecordTimeline(records = packet.records, demo = packet.demo, nowEpochMs = nowEpochMs)
                    MediaCard(media = packet.media, nowEpochMs = nowEpochMs)
                    if (packet.measurements.isNotEmpty()) {
                        MeasurementsCard(packet = packet, nowEpochMs = nowEpochMs)
                    }
                    ChecklistCard(packet = packet)
                    MoneyCard(entries = state.packetLedger, nowEpochMs = nowEpochMs)
                    LogInclusionCard(packet = packet)
                    ActionsCard(
                        packet = packet,
                        exporting = state.exporting,
                        onExport = onExport,
                        onOpenCorrection = onOpenCorrection,
                        onOpenJob = onOpenJob,
                    )
                }
            }
        }
    }

    if (state.correctionOpen) {
        CorrectionDialog(
            note = state.correctionNote,
            appending = state.appendingCorrection,
            error = state.correctionError,
            onNoteChange = onCorrectionNoteChange,
            onConfirm = onAppendCorrection,
            onDismiss = onDismissCorrection,
        )
    }
}

@Composable
private fun SealHero(packet: EvidencePacket, state: PacketDetailUiState) {
    FwCard {
        Row(verticalAlignment = Alignment.Top) {
            FwSealMark(sealed = packet.isSealed, size = VaultStyle.heroSealSize)
            Spacer(Modifier.width(Fw.space.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "VANGUARD FIELD PACKET",
                        style = Fw.type.micro,
                        color = Fw.colors.inkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    if (packet.demo) FwPill(text = "Sample", tone = PillTone.AMBER)
                }
                Text(
                    text = "${packet.id} · ${packet.title}",
                    style = Fw.type.title,
                    color = Fw.colors.ink,
                )
                Spacer(Modifier.height(Fw.space.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Fw.space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FwPill(text = packet.seal.label, tone = VaultStyle.sealTone(packet.seal))
                    FwPill(text = packet.tier.label, tone = PillTone.NEUTRAL)
                }
            }
        }
        Spacer(Modifier.height(Fw.space.md))
        FwSectionRule()
        Spacer(Modifier.height(Fw.space.sm))
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text("REQUESTER", style = Fw.type.micro, color = Fw.colors.inkSoft)
                Text(packet.requesterLabel, style = Fw.type.bodyStrong, color = Fw.colors.ink)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = state.payoutLabel?.let(::payoutLabelText)?.uppercase() ?: "PAYOUT",
                    style = Fw.type.micro,
                    color = Fw.colors.inkSoft,
                )
                if (state.payoutCents != null) {
                    FwMoneyText(cents = state.payoutCents, style = Fw.type.numericLarge, color = Fw.colors.ink)
                } else {
                    Text("Not settled", style = Fw.type.bodyStrong, color = Fw.colors.inkSoft)
                }
            }
        }
        Spacer(Modifier.height(Fw.space.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Fw.space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FwChainHash(
                hash = packet.packetHash.orEmpty(),
                label = "Chain head",
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "As of ${VaultTime.dateLabel(packet.asOfEpochMs)}",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = "${packet.recordCount} records · ${packet.photoCount} photos · ${packet.media.size} hashed media",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        if (!packet.isSealed) {
            Text(
                text = "This packet is not sealed. A download is a draft record set, not saleable evidence.",
                style = Fw.type.meta,
                color = Fw.colors.amber,
            )
        }
    }
}

@Composable
private fun TierCard(packet: EvidencePacket, evaluation: SealEvaluation?) {
    // A seal is reversible. When the current chain verdict makes the engine reach a different
    // state than the one recorded on the packet, say so explicitly instead of showing the
    // recorded pill and a contradictory reason list.
    val reEvaluatedState = evaluation?.takeIf { it.state != packet.seal }?.state
    FwCard {
        FwSectionRule(label = "Derived tier")
        Spacer(Modifier.height(Fw.space.xs))
        Text(packet.tier.label, style = Fw.type.heading, color = Fw.colors.ink)
        Text(packet.tier.meaning, style = Fw.type.body, color = Fw.colors.inkSoft)
        if (reEvaluatedState != null) {
            Spacer(Modifier.height(Fw.space.xs))
            FwNotice(
                title = "Re-evaluated: ${reEvaluatedState.label}",
                body = "The packet records ${packet.seal.label}; against the current chain verdict the seal engine reaches ${reEvaluatedState.label}. A seal that cannot be withdrawn is not a receipt — the reasons are below.",
                tone = if (reEvaluatedState == com.odasi.fieldwright.model.SealState.EARNED) {
                    PillTone.OLIVE
                } else {
                    PillTone.FLAG
                },
            )
        }
        Spacer(Modifier.height(Fw.space.sm))
        Text("WHY THIS STATE", style = Fw.type.micro, color = Fw.colors.inkSoft)
        Spacer(Modifier.height(Fw.space.xxs))
        val reasons: List<String> = when {
            evaluation != null && evaluation.reasons.isNotEmpty() -> evaluation.reasons
            packet.isSealed -> listOf(
                "Every required badge is verified and no badge is flagged.",
                "The record chain verifies from genesis.",
                "The packet carries an as-of date (${VaultTime.dateLabel(packet.asOfEpochMs)}).",
            )

            evaluation != null -> listOf("No blocking reason reported by the seal engine.")
            else -> listOf("Seal preconditions are re-evaluated when the record chain verifies.")
        }
        reasons.forEach { reason ->
            Row {
                Text("·", style = Fw.type.body, color = Fw.colors.inkSoft)
                Spacer(Modifier.width(Fw.space.xxs))
                Text(reason, style = Fw.type.body, color = Fw.colors.ink)
            }
        }
        Spacer(Modifier.height(Fw.space.sm))
        Text(
            text = "Tier is derived from the record set and the badge states. It is never assigned and never shown as a percentage.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

@Composable
private fun BadgesCard(badges: List<Badge>) {
    FwCard {
        FwSectionRule(label = "Badges · declared claims")
        Spacer(Modifier.height(Fw.space.xs))
        badges.forEachIndexed { index, badge ->
            if (index > 0) {
                Spacer(Modifier.height(Fw.space.sm))
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(badge.key.label, style = Fw.type.bodyStrong, color = Fw.colors.ink)
                        if (badge.required) {
                            Spacer(Modifier.width(Fw.space.xxs))
                            Text("REQUIRED", style = Fw.type.micro, color = Fw.colors.inkSoft)
                        }
                    }
                    Text(badge.rationale, style = Fw.type.meta, color = Fw.colors.inkSoft)
                }
                Spacer(Modifier.width(Fw.space.xs))
                FwPill(text = badge.state.label, tone = VaultStyle.badgeTone(badge.state))
            }
        }
    }
}

@Composable
private fun GatesCard(gates: List<GateCheck>) {
    FwCard {
        FwSectionRule(label = "Seal gates")
        Spacer(Modifier.height(Fw.space.xs))
        gates.forEachIndexed { index, gate ->
            if (index > 0) Spacer(Modifier.height(Fw.space.sm))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(gate.label, style = Fw.type.bodyStrong, color = Fw.colors.ink)
                        Spacer(Modifier.width(Fw.space.xxs))
                        Text(
                            text = if (gate.blocking) "BLOCKING" else "ADVISORY",
                            style = Fw.type.micro,
                            color = Fw.colors.inkSoft,
                        )
                    }
                    Text(gate.detail, style = Fw.type.meta, color = Fw.colors.inkSoft)
                }
                Spacer(Modifier.width(Fw.space.xs))
                FwPill(
                    text = VaultStyle.gateLabel(gate.passed, gate.blocking),
                    tone = VaultStyle.gateTone(gate.passed, gate.blocking),
                )
            }
        }
        if (gates.isEmpty()) {
            Text("No gates recorded for this packet.", style = Fw.type.meta, color = Fw.colors.inkSoft)
        }
    }
}

@Composable
private fun ConsentCard(packet: EvidencePacket, nowEpochMs: Long) {
    val consent = packet.consent
    if (consent == null) {
        FwNotice(
            title = "No consent on file",
            body = "A packet without a recorded consent scope cannot be sealed. Append a correction only after the consent is recorded.",
            tone = PillTone.FLAG,
        )
        return
    }
    FwCard {
        FwSectionRule(label = "Consent record")
        Spacer(Modifier.height(Fw.space.xs))
        Text("${consent.name} · ${consent.role}", style = Fw.type.bodyStrong, color = Fw.colors.ink)
        Spacer(Modifier.height(Fw.space.xs))
        FwScopeCallout(title = "Consent scope", body = consent.scopeText)
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = "Method: ${consent.method.label} · captured ${VaultTime.clockLabel(consent.capturedAtEpochMs, nowEpochMs)}",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        consent.signatureHash?.let { hash ->
            Spacer(Modifier.height(Fw.space.xxs))
            FwChainHash(hash = hash, label = "Signature hash")
        } ?: Text(
            "No signature hash recorded.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

@Composable
private fun RecordTimeline(records: List<ChainRecord>, demo: Boolean, nowEpochMs: Long) {
    FwCard {
        FwSectionRule(label = "Record chain · append-only")
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            "Corrections are new records. Nothing here is edited or deleted.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.height(Fw.space.sm))
        records.sortedBy { it.seq }.forEachIndexed { index, record ->
            if (index > 0) Spacer(Modifier.height(Fw.space.sm))
            RecordRow(record = record, demo = demo, nowEpochMs = nowEpochMs)
        }
        if (records.isEmpty()) {
            Text("No records yet.", style = Fw.type.meta, color = Fw.colors.inkSoft)
        }
    }
}

@Composable
private fun RecordRow(record: ChainRecord, demo: Boolean, nowEpochMs: Long) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(VaultStyle.timelineDotSize)
                .clip(CircleShape)
                .background(Fw.colors.oliveTint),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = record.seq.toString(),
                style = Fw.type.micro,
                color = Fw.colors.oliveDeep,
            )
        }
        Spacer(Modifier.width(Fw.space.xs))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Record ${record.seq} · ${record.kind.label}",
                    style = Fw.type.bodyStrong,
                    color = Fw.colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Fw.space.xs))
                if (record.signatureBase64.isNullOrBlank()) {
                    FwPill(
                        text = if (demo) "Unsigned (demo)" else "Unsigned",
                        tone = PillTone.FLAG,
                    )
                } else {
                    FwPill(text = "Signed", tone = PillTone.OLIVE)
                }
            }
            Text(record.summary, style = Fw.type.body, color = Fw.colors.ink)
            Text(
                text = "${record.actorId} · ${VaultTime.clockLabel(record.atEpochMs, nowEpochMs)}",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Fw.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FwChainHash(
                    hash = record.hash,
                    label = "Record hash",
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "prev ${VaultFormat.shortHash(record.prevHash)}",
                    style = Fw.type.hash,
                    color = Fw.colors.inkSoft,
                )
            }
            if (!record.signerKeyId.isNullOrBlank()) {
                Text(
                    text = "Signer key ${record.signerKeyId}",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun MediaCard(media: List<MediaRef>, nowEpochMs: Long) {
    FwCard {
        FwSectionRule(label = "Media · hashed at capture")
        Spacer(Modifier.height(Fw.space.xs))
        if (media.isEmpty()) {
            Text("No media in this packet.", style = Fw.type.meta, color = Fw.colors.inkSoft)
        }
        media.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(Fw.space.sm))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${item.kind.name.lowercase().replace('_', ' ')} · ${VaultStyle.mediaSourceLabel(item.source)}",
                        style = Fw.type.bodyStrong,
                        color = Fw.colors.ink,
                    )
                    Text(
                        text = "${VaultFormat.bytes(item.bytes)} · ${item.width}×${item.height} · ${VaultTime.clockLabel(item.capturedAtEpochMs, nowEpochMs)}",
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                    if (item.note.isNotBlank()) {
                        Text(item.note, style = Fw.type.meta, color = Fw.colors.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    FwChainHash(hash = item.sha256, label = "Media hash")
                }
                Spacer(Modifier.width(Fw.space.xs))
                Column(horizontalAlignment = Alignment.End) {
                    FwPill(
                        text = VaultStyle.redactionLabel(item.redaction),
                        tone = VaultStyle.redactionTone(item.redaction),
                    )
                    if (item.source == MediaSource.SIMULATED) {
                        Spacer(Modifier.height(Fw.space.xxs))
                        FwPill(text = "Simulated", tone = PillTone.AMBER)
                    }
                }
            }
        }
        if (media.any { it.source == MediaSource.SIMULATED }) {
            Spacer(Modifier.height(Fw.space.xs))
            Text(
                "Simulated frames are labelled. They are never presented as camera captures.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
    }
}

@Composable
private fun MeasurementsCard(packet: EvidencePacket, nowEpochMs: Long) {
    FwCard {
        FwSectionRule(label = "Measurements · calibrated")
        Spacer(Modifier.height(Fw.space.xs))
        packet.measurements.forEachIndexed { index, measurement ->
            if (index > 0) Spacer(Modifier.height(Fw.space.sm))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(measurement.label, style = Fw.type.bodyStrong, color = Fw.colors.ink)
                    Text(
                        "Reference: ${measurement.referenceLabel} (${VaultFormat.meters(measurement.referenceMeters)})",
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                    Text(
                        "Calibrated ${VaultTime.clockLabel(measurement.calibratedAtEpochMs, nowEpochMs)} · ${VaultStyle.mediaSourceLabel(measurement.source)}",
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                }
                Spacer(Modifier.width(Fw.space.xs))
                Column(horizontalAlignment = Alignment.End) {
                    Text(VaultFormat.meters(measurement.meters), style = Fw.type.numeric, color = Fw.colors.ink)
                    Text(VaultFormat.feet(measurement.feet), style = Fw.type.numeric, color = Fw.colors.inkSoft)
                }
            }
        }
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            "Dimensions come from a calibrated reference, not an estimate.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

@Composable
private fun ChecklistCard(packet: EvidencePacket) {
    val steps = JobCatalog.checklistFor(packet.jobType).associateBy { it.key }
    FwCard {
        FwSectionRule(label = "Checklist")
        Spacer(Modifier.height(Fw.space.xs))
        packet.checklist.forEach { result ->
            val step = steps[result.key]
            FwChecklistRow(
                label = step?.label ?: result.key.replaceFirstChar { it.uppercase() },
                hint = result.note.ifBlank { step?.hint.orEmpty() },
                state = result.result.toChecklistState(),
                onClick = null,
            )
        }
        if (packet.checklist.isEmpty()) {
            Text("No checklist results recorded.", style = Fw.type.meta, color = Fw.colors.inkSoft)
        }
    }
}

@Composable
private fun MoneyCard(entries: List<LedgerEntry>, nowEpochMs: Long) {
    if (entries.isEmpty()) return
    FwCard {
        FwSectionRule(label = "Money on this packet")
        Spacer(Modifier.height(Fw.space.xs))
        entries.forEachIndexed { index, entry ->
            if (index > 0) Spacer(Modifier.height(Fw.space.sm))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.kind.label, style = Fw.type.bodyStrong, color = Fw.colors.ink)
                    Text(entry.description, style = Fw.type.meta, color = Fw.colors.inkSoft)
                    Text(
                        VaultTime.clockLabel(entry.atEpochMs, nowEpochMs),
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                }
                Spacer(Modifier.width(Fw.space.xs))
                Column(horizontalAlignment = Alignment.End) {
                    FwMoneyText(
                        cents = entry.amountCents,
                        style = Fw.type.numeric,
                        signed = true,
                        color = if (entry.amountCents < 0) Fw.colors.flag else Fw.colors.ink,
                    )
                    FwPill(
                        text = vaultLedgerStatusLabel(entry.status),
                        tone = VaultStyle.ledgerTone(entry.status),
                    )
                }
            }
        }
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            "Money is linked to the packet through the ledger; it is never derived from the job bounty.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

@Composable
private fun LogInclusionCard(packet: EvidencePacket) {
    // `merkleLeaf` is a public core:model property, so it cannot be smart-cast across the
    // module boundary; hoist it into a local val once.
    val logLeaf = packet.merkleLeaf
    FwCard {
        FwSectionRule(label = "Public log")
        Spacer(Modifier.height(Fw.space.xs))
        when {
            packet.demo -> Text(
                "Sample packet — deliberately excluded from the log anchor. It proves the display, not the work.",
                style = Fw.type.body,
                color = Fw.colors.inkSoft,
            )

            logLeaf != null -> {
                Text(
                    "Included in the log anchor with a ${packet.merkleProof.size}-step inclusion proof.",
                    style = Fw.type.body,
                    color = Fw.colors.ink,
                )
                Spacer(Modifier.height(Fw.space.xxs))
                FwChainHash(hash = logLeaf, label = "Log leaf")
            }

            else -> Text(
                "Not anchored yet. Submission is queued and retried while offline.",
                style = Fw.type.body,
                color = Fw.colors.amber,
            )
        }
    }
}

@Composable
private fun ActionsCard(
    packet: EvidencePacket,
    exporting: Boolean,
    onExport: () -> Unit,
    onOpenCorrection: () -> Unit,
    onOpenJob: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
            FwPrimaryButton(
                text = "Download packet",
                onClick = onExport,
                modifier = Modifier.weight(1f),
                loading = exporting,
            )
            FwSecondaryButton(
                text = "Append correction",
                onClick = onOpenCorrection,
                modifier = Modifier.weight(1f),
            )
        }
        if (packet.jobId.isNotBlank()) {
            FwSecondaryButton(
                text = "Open job",
                onClick = { onOpenJob(packet.jobId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "Download writes a copy to the cache and opens the share sheet. It does not create a new seal or change the record chain.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

@Composable
private fun CorrectionDialog(
    note: String,
    appending: Boolean,
    error: String?,
    onNoteChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Fw.colors.sheet,
        shape = Fw.shape.sheet,
        title = {
            Text("Append a correction", style = Fw.type.heading, color = Fw.colors.ink)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Fw.space.sm)) {
                Text(
                    "Corrections are records. The original stays; this note is appended with its own hash and signer. Nothing is edited or deleted.",
                    style = Fw.type.body,
                    color = Fw.colors.inkSoft,
                )
                FwTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    label = "What needs correcting?",
                    placeholder = "Name the field and the corrected value.",
                    singleLine = false,
                )
                error?.let {
                    Text(it, style = Fw.type.meta, color = Fw.colors.flag)
                }
            }
        },
        confirmButton = {
            FwPrimaryButton(
                text = "Append record",
                onClick = onConfirm,
                enabled = !appending,
                loading = appending,
            )
        },
        dismissButton = {
            FwGhostButton(text = "Cancel", onClick = onDismiss, enabled = !appending)
        },
    )
}

@Composable
private fun LoadingBlock(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Fw.space.xl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = Fw.colors.olive, strokeWidth = Fw.dimens.stroke)
        Spacer(Modifier.width(Fw.space.sm))
        Text(text, style = Fw.type.body, color = Fw.colors.inkSoft)
    }
}

private fun StepResult.toChecklistState(): ChecklistRowState = when (this) {
    StepResult.PASS -> ChecklistRowState.PASS
    StepResult.FAIL -> ChecklistRowState.FAIL
    StepResult.PENDING -> ChecklistRowState.OPEN
    StepResult.NOT_APPLICABLE -> ChecklistRowState.NA
}
