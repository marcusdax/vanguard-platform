package com.odasi.fieldwright.feature.vault

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwEmptyState
import com.odasi.fieldwright.design.FwMoneyText
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwSecondaryButton
import com.odasi.fieldwright.design.FwSectionRule
import com.odasi.fieldwright.design.FwSegmentedControl
import com.odasi.fieldwright.design.PillTone

/**
 * The Vault route. Chrome (top bar and dock) belongs to the app shell; this route renders the
 * content only and owns the ViewModel, the cache-and-share side effect, and screen-local
 * messages (the app's snackbar is for navigation-level events).
 *
 * `onOpenJob` is optional so the app root can wire job navigation where jobs live; when it is
 * absent the card's secondary action is not offered rather than dead.
 */
@Composable
fun VaultRoute(
    repos: FieldwrightRepositories,
    onOpenPacket: (String) -> Unit,
    onOpenBrief: () -> Unit,
    onOpenJob: ((String) -> Unit)? = null,
) {
    val viewModel: VaultViewModel = viewModel { VaultViewModel(repos) }
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

    VaultScreen(
        state = state,
        nowEpochMs = nowEpochMs,
        shareError = shareError,
        onDismissShareError = { shareError = null },
        onFilter = viewModel::setFilter,
        onVerify = viewModel::verifyChain,
        onDownload = viewModel::downloadPacket,
        onOpenPacket = onOpenPacket,
        onOpenJob = onOpenJob,
        onOpenBrief = onOpenBrief,
        onDismissError = viewModel::dismissError,
        onDismissMessage = viewModel::consumeMessage,
        onRetry = viewModel::retry,
    )
}

/**
 * Stateless Vault screen: header, chain-health banner, filters, packet cards in the Fieldwright
 * idiom, and every honesty state (loading, empty, filtered-empty, error, sample book).
 */
@Composable
internal fun VaultScreen(
    state: VaultUiState,
    nowEpochMs: Long,
    onFilter: (VaultFilter) -> Unit,
    onVerify: () -> Unit,
    onDownload: (String) -> Unit,
    onOpenPacket: (String) -> Unit,
    onOpenJob: ((String) -> Unit)?,
    onOpenBrief: () -> Unit,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
    onRetry: () -> Unit,
    shareError: String? = null,
    onDismissShareError: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(
                start = VaultStyle.gutter,
                end = VaultStyle.gutter,
                top = Fw.space.md,
                bottom = Fw.space.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(Fw.space.xxs),
        ) {
            Text("Vault", style = Fw.type.displayXl, color = Fw.colors.ink)
            Text(
                "Sealed evidence, paid for only when it is earned.",
                style = Fw.type.body,
                color = Fw.colors.inkSoft,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = VaultStyle.gutter,
                end = VaultStyle.gutter,
                top = Fw.space.xs,
                bottom = Fw.space.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Fw.dimens.cardGap),
        ) {
            item(key = "chain") {
                ChainHealthBanner(chain = state.chain, onVerify = onVerify, nowEpochMs = nowEpochMs)
            }
            if (state.demo) {
                item(key = "demo-notice") {
                    FwNotice(
                        title = "Sample book — demonstration data",
                        body = "Demo packets are excluded from the public log and are never presented as live evidence. Every card below is marked SAMPLE.",
                        tone = PillTone.AMBER,
                    )
                }
            }
            item(key = "filters") {
                VaultFilters(selected = state.filter, onFilter = onFilter, count = state.cards.size)
            }

            state.error?.let { error ->
                item(key = "error") {
                    FwNotice(
                        title = "Vault unavailable",
                        body = error,
                        tone = PillTone.FLAG,
                        actionLabel = "Try again",
                        onAction = onRetry,
                    )
                }
            }
            shareError?.let { error ->
                item(key = "share-error") {
                    FwNotice(
                        title = "Share sheet unavailable",
                        body = error,
                        tone = PillTone.FLAG,
                        actionLabel = "Dismiss",
                        onAction = onDismissShareError,
                    )
                }
            }
            state.message?.let { message ->
                item(key = "message") {
                    FwNotice(
                        title = "Saved to the cache",
                        body = message,
                        tone = PillTone.NEUTRAL,
                        actionLabel = "Dismiss",
                        onAction = onDismissMessage,
                    )
                }
            }

            when {
                state.loading -> item(key = "loading") {
                    LoadingRow(text = "Opening the vault…")
                }

                state.isEmpty -> item(key = "empty") {
                    FwEmptyState(
                        title = "No packets yet",
                        body = "A packet appears here once field evidence is captured, gated, and sealed. A photo upload alone is not a packet.",
                        actionLabel = "Read the field brief",
                        onAction = onOpenBrief,
                    )
                }

                state.isFilteredEmpty -> item(key = "filtered-empty") {
                    FwEmptyState(
                        title = "Nothing in this filter",
                        body = "No packets match \"${state.filter.label}\". Clear the filter to see the whole vault.",
                        actionLabel = "Show all packets",
                        onAction = { onFilter(VaultFilter.ALL) },
                    )
                }

                else -> items(items = state.cards, key = { it.packetId }) { card ->
                    VaultPacketCard(
                        card = card,
                        nowEpochMs = nowEpochMs,
                        exporting = state.exportingPacketId == card.packetId,
                        anotherExportRunning = state.exportingPacketId != null &&
                            state.exportingPacketId != card.packetId,
                        onOpen = { onOpenPacket(card.packetId) },
                        onDownload = { onDownload(card.packetId) },
                        onOpenJob = onOpenJob?.let { open -> { open(card.jobId) } },
                    )
                }
            }

            if (state.demo && state.cards.isNotEmpty()) {
                item(key = "demo-footer") {
                    Text(
                        "Sample packets are a demonstration of the vault, not a record of work. They are excluded from the log anchor.",
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                        modifier = Modifier.padding(top = Fw.space.xs),
                    )
                }
            }
        }
    }
}

/** Chain health is a claim with a time on it, so the banner always names when it was checked. */
@Composable
internal fun ChainHealthBanner(
    chain: ChainHealth,
    onVerify: () -> Unit,
    nowEpochMs: Long,
) {
    val checkedAt = chain.checkedAtEpochMs?.let { VaultTime.clockLabel(it, nowEpochMs) }
    when (chain.state) {
        ChainHealthState.UNVERIFIED -> FwNotice(
            title = "Chain not checked yet",
            body = "Every packet is a hash chain. Verify it from genesis before treating a download as evidence.",
            tone = PillTone.NEUTRAL,
            actionLabel = "Verify chain",
            onAction = onVerify,
        )

        ChainHealthState.VERIFYING -> FwNotice(
            title = "Verifying the chain…",
            body = "Re-walking the records from genesis. Nothing is being uploaded.",
            tone = PillTone.NEUTRAL,
        )

        ChainHealthState.VERIFIED -> FwNotice(
            title = "Chain verified",
            body = "${chain.entries} records check from genesis · head ${chain.headHash.take(8)}" +
                (checkedAt?.let { " · checked $it" } ?: ""),
            tone = PillTone.OLIVE,
            actionLabel = "Verify again",
            onAction = onVerify,
        )

        ChainHealthState.FAILED -> FwNotice(
            title = "Chain check failed",
            body = buildString {
                append(chain.detail.ifBlank { "The record chain does not verify from genesis." })
                chain.brokenAtSeq?.let { append(" First break at record #$it.") }
                checkedAt?.let { append(" Checked $it.") }
                append(" Do not treat this packet as evidence until it is resolved.")
            },
            tone = PillTone.FLAG,
            actionLabel = "Verify again",
            onAction = onVerify,
        )
    }
}

@Composable
private fun VaultFilters(
    selected: VaultFilter,
    onFilter: (VaultFilter) -> Unit,
    count: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
        FwSectionRule(label = "Packet index")
        FwSegmentedControl(
            options = VaultFilter.entries.map { it.label },
            selectedIndex = selected.ordinal,
            onSelect = { index -> onFilter(VaultFilter.entries[index]) },
        )
        Text(
            if (count == 1) "1 packet" else "$count packets",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

/**
 * The packet card, in the supplied Fieldwright idiom: olive rule and overline, serif
 * "FW-xxxx · title", requester · status · payout, consent line, checklist lines, fingerprint
 * footer, primary "Download packet" and secondary "Open job".
 */
@Composable
internal fun VaultPacketCard(
    card: PacketCard,
    nowEpochMs: Long,
    exporting: Boolean,
    anotherExportRunning: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onOpenJob: (() -> Unit)?,
) {
    FwCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                FwSectionRule(label = "Vanguard Field packet")
            }
            if (card.demo) {
                FwPill(text = "Sample", tone = PillTone.AMBER)
            }
        }
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = "${card.packetId} · ${card.title}",
            style = Fw.type.title,
            color = Fw.colors.ink,
        )
        Spacer(Modifier.height(Fw.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.requesterLabel,
                style = Fw.type.bodyStrong,
                color = Fw.colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Fw.space.xs))
            FwPill(text = card.sealLabel, tone = VaultStyle.sealTone(card.sealState))
            if (card.payoutCents != null) {
                Spacer(Modifier.width(Fw.space.xs))
                Column(horizontalAlignment = Alignment.End) {
                    FwMoneyText(cents = card.payoutCents, style = Fw.type.numeric, color = Fw.colors.ink)
                    card.payoutLabel?.let { label ->
                        Text(
                            text = payoutLabelText(label),
                            style = Fw.type.micro,
                            color = Fw.colors.inkSoft,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = card.consentLine,
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.checklistLines.isNotEmpty()) {
            Spacer(Modifier.height(Fw.space.xs))
            card.checklistLines.take(5).forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("·", style = Fw.type.meta, color = Fw.colors.inkSoft)
                    Spacer(Modifier.width(Fw.space.xxs))
                    Text(
                        text = line.label,
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = VaultStyle.stepResultLabel(line.result),
                        style = Fw.type.meta,
                        color = toneColor(VaultStyle.stepResultTone(line.result)),
                    )
                }
            }
        }
        if (card.demo) {
            Spacer(Modifier.height(Fw.space.xxs))
            Text(
                text = "SAMPLE — excluded from the log",
                style = Fw.type.micro,
                color = Fw.colors.oxide,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Fw.space.sm))
        Text(
            text = VaultTime.fingerprint(
                recordCount = card.recordCount,
                lastRecordAtEpochMs = card.lastRecordAtEpochMs,
                shortHash = card.shortHash,
                nowEpochMs = nowEpochMs,
            ),
            style = Fw.type.hash,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.height(Fw.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
            FwPrimaryButton(
                text = "Download packet",
                onClick = onDownload,
                modifier = Modifier.weight(1f),
                enabled = !anotherExportRunning,
                loading = exporting,
            )
            if (card.jobId.isNotBlank() && onOpenJob != null) {
                FwSecondaryButton(
                    text = "Open job",
                    onClick = onOpenJob,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Fw.space.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = Fw.colors.olive, strokeWidth = Fw.dimens.stroke)
        Spacer(Modifier.width(Fw.space.sm))
        Text(text, style = Fw.type.body, color = Fw.colors.inkSoft)
    }
}

@Composable
internal fun toneColor(tone: PillTone): Color = when (tone) {
    PillTone.OLIVE -> Fw.colors.olive
    PillTone.AMBER -> Fw.colors.amber
    PillTone.FLAG -> Fw.colors.flag
    PillTone.OXIDE -> Fw.colors.oxide
    PillTone.NEUTRAL -> Fw.colors.inkSoft
}
