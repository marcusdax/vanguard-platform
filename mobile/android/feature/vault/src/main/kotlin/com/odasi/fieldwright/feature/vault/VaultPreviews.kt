package com.odasi.fieldwright.feature.vault

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.design.FieldwrightTheme
import com.odasi.fieldwright.design.Fw

@Composable
private fun VaultPreviewSurface(content: @Composable () -> Unit) {
    FieldwrightTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Fw.colors.desk) {
            content()
        }
    }
}

private val noop: () -> Unit = {}
private val noopString: (String) -> Unit = {}

@Preview(name = "Vault · packets + sample book", showBackground = false)
@Composable
private fun VaultListPreview() {
    VaultPreviewSurface {
        VaultScreen(
            state = VaultSamples.vaultState(),
            nowEpochMs = VaultSamples.NOW,
            onFilter = {},
            onVerify = {},
            onDownload = {},
            onOpenPacket = {},
            onOpenJob = {},
            onOpenBrief = {},
            onDismissError = {},
            onDismissMessage = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Vault · chain failed", showBackground = false)
@Composable
private fun VaultChainFailedPreview() {
    VaultPreviewSurface {
        VaultScreen(
            state = VaultSamples.vaultState(chain = VaultSamples.failedChain()),
            nowEpochMs = VaultSamples.NOW,
            onFilter = {},
            onVerify = {},
            onDownload = {},
            onOpenPacket = {},
            onOpenJob = {},
            onOpenBrief = {},
            onDismissError = {},
            onDismissMessage = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Vault · loading", showBackground = false)
@Composable
private fun VaultLoadingPreview() {
    VaultPreviewSurface {
        VaultScreen(
            state = VaultUiState(loading = true, chain = ChainHealth(state = ChainHealthState.VERIFYING)),
            nowEpochMs = VaultSamples.NOW,
            onFilter = {},
            onVerify = {},
            onDownload = {},
            onOpenPacket = {},
            onOpenJob = {},
            onOpenBrief = {},
            onDismissError = {},
            onDismissMessage = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Vault · empty", showBackground = false)
@Composable
private fun VaultEmptyPreview() {
    VaultPreviewSurface {
        VaultScreen(
            state = VaultUiState(loading = false, chain = ChainHealth()),
            nowEpochMs = VaultSamples.NOW,
            onFilter = {},
            onVerify = {},
            onDownload = {},
            onOpenPacket = {},
            onOpenJob = {},
            onOpenBrief = {},
            onDismissError = {},
            onDismissMessage = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Vault · filter empty + error", showBackground = false)
@Composable
private fun VaultErrorPreview() {
    VaultPreviewSurface {
        VaultScreen(
            state = VaultSamples.vaultState(
                cards = emptyList(),
                filter = VaultFilter.SEALED,
                chain = VaultSamples.failedChain(),
            ).copy(
                totalCards = 2,
                error = "Could not read the vault: the local database is locked.",
            ),
            nowEpochMs = VaultSamples.NOW,
            onFilter = {},
            onVerify = {},
            onDownload = {},
            onOpenPacket = {},
            onOpenJob = {},
            onOpenBrief = {},
            onDismissError = {},
            onDismissMessage = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Packet detail · sealed sample", showBackground = false)
@Composable
private fun PacketDetailPreview() {
    VaultPreviewSurface {
        PacketDetailScreen(
            state = VaultSamples.detailState(),
            nowEpochMs = VaultSamples.NOW,
            onBack = {},
            onVerify = {},
            onExport = {},
            onOpenCorrection = {},
            onDismissCorrection = {},
            onCorrectionNoteChange = {},
            onAppendCorrection = {},
            onOpenJob = noopString,
            onDismissError = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Packet detail · pending, needs attention", showBackground = false)
@Composable
private fun PacketDetailPendingPreview() {
    VaultPreviewSurface {
        PacketDetailScreen(
            state = VaultSamples.detailState(
                packet = VaultSamples.pendingPacket(),
                chain = VaultSamples.verifiedChain(),
            ).copy(
                payoutCents = 9_500,
                payoutLabel = PayoutLabel.EARNED,
                sealEvaluation = null,
            ),
            nowEpochMs = VaultSamples.NOW,
            onBack = {},
            onVerify = {},
            onExport = {},
            onOpenCorrection = {},
            onDismissCorrection = {},
            onCorrectionNoteChange = {},
            onAppendCorrection = {},
            onOpenJob = noopString,
            onDismissError = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Packet detail · chain broken", showBackground = false)
@Composable
private fun PacketDetailChainFailedPreview() {
    VaultPreviewSurface {
        PacketDetailScreen(
            state = VaultSamples.detailState(chain = VaultSamples.failedChain()),
            nowEpochMs = VaultSamples.NOW,
            onBack = {},
            onVerify = {},
            onExport = {},
            onOpenCorrection = {},
            onDismissCorrection = {},
            onCorrectionNoteChange = {},
            onAppendCorrection = {},
            onOpenJob = noopString,
            onDismissError = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Packet detail · correction dialog", showBackground = false)
@Composable
private fun PacketDetailCorrectionPreview() {
    VaultPreviewSurface {
        PacketDetailScreen(
            state = VaultSamples.detailState().copy(
                correctionOpen = true,
                correctionNote = "South slope label reads east in record 1.",
            ),
            nowEpochMs = VaultSamples.NOW,
            onBack = {},
            onVerify = {},
            onExport = {},
            onOpenCorrection = {},
            onDismissCorrection = {},
            onCorrectionNoteChange = {},
            onAppendCorrection = {},
            onOpenJob = noopString,
            onDismissError = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Packet detail · loading / not found", showBackground = false)
@Composable
private fun PacketDetailEmptyPreview() {
    VaultPreviewSurface {
        PacketDetailScreen(
            state = PacketDetailUiState(loading = false, packet = null),
            nowEpochMs = VaultSamples.NOW,
            onBack = noop,
            onVerify = {},
            onExport = {},
            onOpenCorrection = {},
            onDismissCorrection = {},
            onCorrectionNoteChange = {},
            onAppendCorrection = {},
            onOpenJob = noopString,
            onDismissError = {},
            onDismissMessage = {},
        )
    }
}

@Preview(name = "Packet detail · loading", showBackground = false)
@Composable
private fun PacketDetailLoadingPreview() {
    VaultPreviewSurface {
        PacketDetailScreen(
            state = PacketDetailUiState(loading = true),
            nowEpochMs = VaultSamples.NOW,
            onBack = noop,
            onVerify = {},
            onExport = {},
            onOpenCorrection = {},
            onDismissCorrection = {},
            onCorrectionNoteChange = {},
            onAppendCorrection = {},
            onOpenJob = noopString,
            onDismissError = {},
            onDismissMessage = {},
        )
    }
}
