package com.odasi.fieldwright.feature.vanguard

import com.odasi.fieldwright.model.ClusterEconomicsResult
import com.odasi.fieldwright.model.Money
import com.odasi.fieldwright.model.ShortestFix
import com.odasi.fieldwright.model.VanguardCredentialDecision
import com.odasi.fieldwright.model.VanguardEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VanguardUiState(
    val heldCredentials: Set<String> = setOf("IDENTITY_VERIFIED"),
    val completedModules: Set<String> = setOf("T1-ONB"),
    val vaultBalanceCents: Int = 18_500,
    val routingBps: Int = 1_000,
    val clusterEco: ClusterEconomicsResult = VanguardEngine.computeClusterEconomics(
        bountiesCents = listOf(1800, 4200, 2000, 2000),
        taskMinutes = 90,
        miles = 5.0,
    ),
    val activeBlock: ShortestFix? = null,
    val statusBanner: String? = null,
)

class VanguardViewModel {
    private val _state = MutableStateFlow(VanguardUiState())
    val state: StateFlow<VanguardUiState> = _state.asStateFlow()

    fun attemptClaimTask(requiredCredential: String?, isFloorTask: Boolean, label: String) {
        val reqs = if (requiredCredential.isNullOrBlank()) emptySet() else setOf(requiredCredential)
        when (
            val decision = VanguardEngine.checkCredentials(
                isFloorAction = isFloorTask,
                requiredCredentials = reqs,
                heldCredentials = _state.value.heldCredentials,
                actionLabel = label,
            )
        ) {
            is VanguardCredentialDecision.Allowed -> _state.update {
                it.copy(
                    activeBlock = null,
                    statusBanner = "$label claimed (${decision.invariant} · 0 bps worker fee)",
                )
            }

            is VanguardCredentialDecision.Blocked -> _state.update {
                it.copy(
                    activeBlock = decision.shortestFix,
                    statusBanner = decision.reason,
                )
            }
        }
    }

    fun completeModule(moduleId: String) {
        val mod = VanguardEngine.curriculumCatalog.firstOrNull { it.code == moduleId } ?: return
        _state.update { s ->
            val nextCreds = if (mod.grantsCredential != null) {
                s.heldCredentials + mod.grantsCredential!!
            } else {
                s.heldCredentials
            }
            s.copy(
                completedModules = s.completedModules + mod.code,
                heldCredentials = nextCreds,
                activeBlock = null,
                statusBanner = "Completed ${mod.code}; Earn-to-Learn Vault ${Money.format(s.vaultBalanceCents)}",
            )
        }
    }

    fun dismissShortestFix() {
        _state.update { it.copy(activeBlock = null) }
    }
}
