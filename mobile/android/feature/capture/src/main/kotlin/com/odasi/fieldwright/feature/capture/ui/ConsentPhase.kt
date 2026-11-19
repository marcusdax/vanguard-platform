package com.odasi.fieldwright.feature.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwChainHash
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwKeyValue
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwScopeCallout
import com.odasi.fieldwright.design.FwSegmentedControl
import com.odasi.fieldwright.design.FwSealMark
import com.odasi.fieldwright.design.FwSwitchRow
import com.odasi.fieldwright.design.FwTextField
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.feature.capture.CaptureState
import com.odasi.fieldwright.feature.capture.ConsentForm
import com.odasi.fieldwright.model.ConsentMethod

/**
 * Phase 1 — the consent gate.
 *
 * Nothing is captured before a named person agrees to a named scope. The scope text comes
 * from the job and is displayed verbatim; the operator cannot edit it. The typed signature
 * is reduced to a SHA-256 bound to the scope/method/time and shown back as a digest.
 */
@Composable
internal fun ConsentPhase(
    state: CaptureState,
    callbacks: CaptureCallbacks,
    modifier: Modifier = Modifier,
) {
    val consent: ConsentForm = state.consent
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Fw.space.gutter, vertical = Fw.space.sm),
        verticalArrangement = Arrangement.spacedBy(Fw.space.md),
    ) {
        PhaseHeader(
            overline = "Consent gate",
            title = "Who agreed to what",
            body = "A named person records what may be captured and what may not. No consent, no capture, and no seal.",
        )
        FwScopeCallout(
            title = "Scope on file · ${state.job?.requester?.org ?: "requester"}",
            body = consent.scopeText.ifBlank { "This job has no consent scope on file. Ask the requester before capturing." },
        )
        if (consent.isSigned && consent.recorded != null) {
            SignedConsentCard(state = state, callbacks = callbacks)
        } else {
            ConsentFormCard(state = state, callbacks = callbacks)
        }
        FwNotice(
            title = "What is stored",
            body = "The record stores the subject's name and role, the scope text, the method, the time, and a SHA-256 digest of the signature bound to those values. The typed signature itself is not logged or exported.",
            tone = PillTone.NEUTRAL,
        )
    }
}

@Composable
private fun ConsentFormCard(state: CaptureState, callbacks: CaptureCallbacks) {
    val consent = state.consent
    val methods = remember { ConsentMethod.entries }
    FwCard(modifier = Modifier.fillMaxWidth()) {
        if (consent.editing) {
            FwNotice(
                title = "Correcting a consent record",
                body = "Saving again appends a new consent record. The earlier record is not edited or deleted.",
                tone = PillTone.AMBER,
            )
        }
        Text(text = "SUBJECT", style = Fw.type.micro, color = Fw.colors.inkSoft)
        FwTextField(
            value = consent.subjectName,
            onValueChange = callbacks.onSubjectNameChange,
            label = "Full name",
            placeholder = "e.g. Dana Whitlock",
        )
        FwTextField(
            value = consent.subjectRole,
            onValueChange = callbacks.onSubjectRoleChange,
            label = "Role at the site",
            placeholder = "Owner, tenant, or site contact",
        )
        Text(text = "METHOD", style = Fw.type.micro, color = Fw.colors.inkSoft)
        FwSegmentedControl(
            options = methods.map { it.label },
            selectedIndex = methods.indexOf(consent.method).coerceAtLeast(0),
            onSelect = { index -> callbacks.onConsentMethodChange(methods[index]) },
        )
        Spacer(Modifier.padding(top = Fw.space.xxs))
        FwSwitchRow(
            title = "The subject read and agreed to this scope",
            subtitle = "The person giving consent is the one named above. A typed signature is the record.",
            checked = consent.confirmed,
            onCheckedChange = callbacks.onConsentConfirmedChange,
        )
        FwTextField(
            value = consent.typedSignature,
            onValueChange = callbacks.onSignatureChange,
            label = "Typed signature",
            placeholder = consent.subjectName.ifBlank { "Type the name exactly" },
        )
        consent.error?.let { message -> FieldError(message) }
        FieldHint("Signed in app preferred. Verbal consent must be recorded, and pre-authorisation must already be on file.")
        if (consent.editing) {
            FwGhostButton(text = "Cancel correction", onClick = callbacks.onCancelConsentCorrection)
        }
    }
}

@Composable
private fun SignedConsentCard(state: CaptureState, callbacks: CaptureCallbacks) {
    val recorded = state.consent.recorded ?: return
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FwSealMark(sealed = true, size = Fw.dimens.seal)
            Spacer(Modifier.padding(start = Fw.space.xs))
            Column {
                Text(text = "CONSENT ON FILE", style = Fw.type.micro, color = Fw.colors.oliveDeep)
                Text(
                    text = "${recorded.name} · ${recorded.role}",
                    style = Fw.type.heading,
                    color = Fw.colors.ink,
                )
            }
        }
        FwKeyValue(label = "Method", value = recorded.method.label)
        FwKeyValue(label = "Recorded", value = formatTimestamp(recorded.capturedAtEpochMs))
        FwKeyValue(label = "Signed by operator", value = state.operatorLabel.ifBlank { recorded.verifiedByOperatorId })
        FwKeyValue(label = "Scope", value = recorded.scopeText)
        FwChainHash(hash = recorded.signatureHash ?: "", label = "Signature digest")
        FwGhostButton(text = "Correct consent", onClick = callbacks.onCorrectConsent)
    }
}

@Preview(name = "Capture · consent gate", widthDp = 400, heightDp = 1000, showBackground = true)
@Composable
private fun ConsentPhasePreview() {
    CapturePreviewSurface {
        ConsentPhase(state = CaptureSamples.consentState(), callbacks = CaptureCallbacks())
    }
}

@Preview(name = "Capture · consent recorded", widthDp = 400, heightDp = 1000, showBackground = true)
@Composable
private fun ConsentRecordedPreview() {
    CapturePreviewSurface {
        ConsentPhase(state = CaptureSamples.consentSignedState(), callbacks = CaptureCallbacks())
    }
}
