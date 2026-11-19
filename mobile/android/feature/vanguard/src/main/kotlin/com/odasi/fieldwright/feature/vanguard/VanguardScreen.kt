package com.odasi.fieldwright.feature.vanguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odasi.fieldwright.model.Money
import com.odasi.fieldwright.model.VanguardEngine

@Composable
fun VanguardRoute(modifier: Modifier = Modifier) {
    val vm = remember { VanguardViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vanguard Field · Tactical Intelligence", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Loop Net (@$0.76/mi H2 2026): ${Money.format(state.clusterEco.netCents)} " +
                "(${Money.format(state.clusterEco.netHourlyCents)}/hr)",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Earn-to-Learn Vault: ${Money.format(state.vaultBalanceCents)} · Pre-launch staging",
            style = MaterialTheme.typography.bodySmall,
        )

        state.statusBanner?.let { banner ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(banner, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(
            onClick = { vm.attemptClaimTask(null, isFloorTask = true, "Grocery Carry-In + Lawn Loop ($100)") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Claim Tier 1 Floor Cluster ($0 Paywall · Invariant I-1)")
        }

        OutlinedButton(
            onClick = { vm.attemptClaimTask("ROOF_SAFETY", isFloorTask = false, "4-Slope Hail Roof Capture ($65)") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Attempt Tier 3 Roof Claim (Triggers Check C-11)")
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Vanguard Academy Modules", style = MaterialTheme.typography.titleMedium)

        VanguardEngine.curriculumCatalog.forEach { mod ->
            val done = state.completedModules.contains(mod.code)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${mod.code} · Tier ${mod.tier} · ${mod.minutes} min", style = MaterialTheme.typography.labelSmall)
                        Text(mod.title, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (done) {
                        Text("Unlocked", style = MaterialTheme.typography.labelMedium)
                    } else {
                        TextButton(onClick = { vm.completeModule(mod.code) }) {
                            Text("Complete")
                        }
                    }
                }
            }
        }
    }

    state.activeBlock?.let { fix ->
        AlertDialog(
            onDismissRequest = vm::dismissShortestFix,
            title = { Text("Credential Required · Invariant I-5") },
            text = {
                Text("Complete free module ${fix.moduleId}: ${fix.lessonTitle} (${fix.minutes} min, $0).")
            },
            confirmButton = {
                Button(onClick = { vm.completeModule(fix.moduleId) }) {
                    Text("Complete ${fix.moduleId}")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissShortestFix) {
                    Text("Keep Browsing Floor")
                }
            },
        )
    }
}
