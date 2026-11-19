package com.odasi.fieldwright.feature.brief

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwKeyValue
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwSectionRule
import com.odasi.fieldwright.design.FwSecondaryButton
import com.odasi.fieldwright.design.FwSwitchRow
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.FeeSchedule
import com.odasi.fieldwright.model.Money
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.launch

/**
 * The Brief: the operating rules of the marketplace, written where the worker can read
 * them. It is deliberately not a settings dump — it states what Fieldwright promises and
 * what it refuses to do, then shows the operational facts (fees, sync health, credentials).
 */
@Composable
fun BriefRoute(
    repos: FieldwrightRepositories,
    demoMode: Boolean,
    versionLabel: String,
    onBack: () -> Unit,
    onResetSampleBook: suspend () -> Unit,
    onNotify: (String) -> Unit,
) {
    val operator by repos.operator.observeOperator().collectAsStateWithLifecycle(initialValue = null)
    val availability by repos.operator.observeAvailability().collectAsStateWithLifecycle(initialValue = null)
    val syncState by repos.sync.observeSyncState().collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }

    BriefScreen(
        operator = operator,
        availability = availability,
        syncState = syncState,
        demoMode = demoMode,
        versionLabel = versionLabel,
        resetting = resetting,
        onBack = onBack,
        onRetrySync = {
            scope.launch {
                val report = repos.sync.flushNow()
                onNotify(if (report.failed == 0) "Outbox: ${report.succeeded} synced" else "Sync failed: ${report.detail}")
            }
        },
        onToggleAutoAccept = { enabled ->
            scope.launch {
                val current = availability ?: return@launch
                repos.operator.updateAutoAccept(current.autoAccept.copy(enabled = enabled))
            }
        },
        onResetSampleBook = {
            confirmReset = true
        },
    )

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the sample book?") },
            text = { Text("This wipes the demo jobs, packets, ledger and offers on this device and reseeds them. Nothing on the server is touched — demo records never leave this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        resetting = true
                        runCatching { onResetSampleBook() }
                            .onSuccess { onNotify("Sample book reset") }
                            .onFailure { onNotify("Reset failed: ${it.message ?: "unknown error"}") }
                        resetting = false
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep it") } },
        )
    }
}

@Composable
fun BriefScreen(
    operator: Operator?,
    availability: Availability?,
    syncState: SyncState?,
    demoMode: Boolean,
    versionLabel: String,
    resetting: Boolean,
    onBack: () -> Unit,
    onRetrySync: () -> Unit,
    onToggleAutoAccept: (Boolean) -> Unit,
    onResetSampleBook: () -> Unit,
) {
    val fees = remember { FeeSchedule() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Brief", style = Fw.type.displayL)
                Text("The operating rules, in the worker's words.", style = Fw.type.body, color = Fw.colors.inkSoft)
            }
            if (demoMode) FwPill("Sample", PillTone.NEUTRAL)
        }

        FwCard {
            FwSectionRule("What you are selling")
            Text(
                "A packet is not a photo album. It is a dated, consented, signed record set that a claims desk can defend. " +
                    "Vanguard Field pays for the sealed packet, not the upload.",
                style = Fw.type.body,
            )
            Spacer(Modifier.height(8.dp))
            BriefPromise("Sealed", "Each packet carries a SHA-256 chain and a device signature. Corrections are new records; nothing is edited.")
            BriefPromise("Consented", "A named person authorises the scope before capture. Faces and plates are excluded or redacted.")
            BriefPromise("Paid on approval", "Money posts when the requester accepts the packet. Evidence and money move independently.")
        }

        FwCard {
            FwSectionRule("How money moves")
            FwKeyValue("Requester platform fee", "${fees.platformFeePercentLabel} · paid by the buyer, disclosed on the job")
            FwKeyValue("Instant payout", "${fees.instantPayoutFeeBp / 100}.${fees.instantPayoutFeeBp % 100}% · min ${Money.format(fees.instantPayoutMinFeeCents)} · max ${Money.format(fees.instantPayoutMaxFeeCents)}")
            FwKeyValue("Standard payout", "Free · arrives in 1–2 business days")
            FwKeyValue("Mileage estimate", "${fees.mileageRateCentsPerMile}¢/mi · for your records, not a Vanguard Field payment")
            Spacer(Modifier.height(8.dp))
            Text(
                "Fees are parameters, not slogans. Every money surface asks the same fee engine, so the number you see is the number that is charged.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }

        FwCard {
            FwSectionRule("What Vanguard Field never does")
            BriefNever("No tokens, no coin, no wallet. You are paid in dollars through a licensed payout rail.")
            BriefNever("No covert capture, no GPS spoofing, no fake frames. A simulated frame is labelled as simulated.")
            BriefNever("No editing or deleting evidence. A mistake becomes a correction record.")
            BriefNever("No facial recognition. Redaction gates run on-device and block the seal.")
            BriefNever("No sale of your consent records or location history.")
        }

        FwCard {
            FwSectionRule("Sync health")
            val phase = syncState?.phase ?: SyncPhase.IDLE
            Row(verticalAlignment = Alignment.CenterVertically) {
                FwPill(
                    text = when (phase) {
                        SyncPhase.IDLE -> "Up to date"
                        SyncPhase.SYNCING -> "Syncing"
                        SyncPhase.WAITING_FOR_NETWORK -> "Waiting for network"
                        SyncPhase.RETRYING -> "Retrying"
                        SyncPhase.DEAD_LETTER -> "Needs attention"
                    },
                    tone = when (phase) {
                        SyncPhase.DEAD_LETTER -> PillTone.FLAG
                        SyncPhase.WAITING_FOR_NETWORK, SyncPhase.RETRYING -> PillTone.AMBER
                        else -> PillTone.OLIVE
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Queued ${syncState?.queued ?: 0} · Stuck ${syncState?.deadLetters ?: 0}",
                    style = Fw.type.numeric,
                    color = Fw.colors.inkSoft,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Everything you capture is written to this device first. The outbox retries with backoff; a failed item stays visible here until it is delivered or you retry it.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
            syncState?.lastError?.let { error ->
                Spacer(Modifier.height(6.dp))
                Text(error, style = Fw.type.meta, color = Fw.colors.flag)
            }
            Spacer(Modifier.height(12.dp))
            FwSecondaryButton(text = "Retry now", onClick = onRetrySync, modifier = Modifier.fillMaxWidth())
        }

        FwCard {
            FwSectionRule("You")
            FwKeyValue("Operator", operator?.displayName ?: "—")
            FwKeyValue("Tier", operator?.let { "${it.tierName} · ${it.credentialTier}" } ?: "—")
            FwKeyValue("Rating", operator?.let { "%.1f · %d jobs".format(it.ratingAvg, it.completedJobs) } ?: "—")
            FwKeyValue("Credentials", operator?.credentials?.joinToString { it.short }.orEmpty().ifEmpty { "None on file" })
            FwKeyValue("Payout account", operator?.let { "•••• ${it.payoutAccountLast4}" } ?: "—")
            Spacer(Modifier.height(12.dp))
            FwSwitchRow(
                title = "Auto-accept inside my policy",
                subtitle = availability?.autoAccept?.let {
                    "Min ${Money.format(it.minPayoutCents)} · within ${it.maxDistanceMeters / 1000} km · max ${it.maxActiveStack} stacked"
                },
                checked = availability?.autoAccept?.enabled ?: false,
                onCheckedChange = onToggleAutoAccept,
            )
        }

        FwCard {
            FwSectionRule("Protocol")
            Text(
                "Vanguard Field speaks the Vanguard trust protocol: records are appended, tiers are derived, badges are declared claims, " +
                    "and a seal is earned by evidence — then withdrawn if evidence falls. Demo records are flagged and never enter the public log.",
                style = Fw.type.body,
            )
            Spacer(Modifier.height(8.dp))
            FwKeyValue("Build", versionLabel)
        }

        if (demoMode) {
            FwCard {
                FwSectionRule("Sample book")
                Text(
                    "This build carries a seeded sample book so the marketplace is explorable offline. Sealed packets here are labelled SAMPLE and are excluded from any log anchoring.",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
                Spacer(Modifier.height(12.dp))
                FwPrimaryButton(
                    text = if (resetting) "Resetting…" else "Reset sample book",
                    onClick = onResetSampleBook,
                    enabled = !resetting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BriefPromise(title: String, body: String) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(0.34f)) {
            Text(title, style = Fw.type.bodyStrong, color = Fw.colors.olive)
        }
        Text(body, style = Fw.type.body, modifier = Modifier.weight(0.66f), color = Fw.colors.inkSoft)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BriefNever(body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("—", style = Fw.type.bodyStrong, color = Fw.colors.oxide, modifier = Modifier.width(18.dp))
        Text(body, style = Fw.type.body)
    }
}
