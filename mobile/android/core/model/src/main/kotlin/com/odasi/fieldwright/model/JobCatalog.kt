package com.odasi.fieldwright.model

/**
 * The checklist each job family carries. A checklist step is an evidence obligation:
 * it names the proof the requester is paying for, in the requester's own vocabulary.
 * The wording is deliberately concrete — "gauge face", not "document condition".
 */
object JobCatalog {

    fun checklistFor(type: JobType): List<ChecklistStep> = when (type) {
        JobType.ROOF_HAIL -> listOf(
            ChecklistStep("footprint", "Storm footprint", "Recorded event covers this address and date.", ProofKind.OBSERVATION),
            ChecklistStep("slopes", "Four slopes", "North, east, south, west — one frame each, sky out of frame.", ProofKind.PHOTO),
            ChecklistStep("square", "Test square", "One 10×10 ft test square with shingle count visible.", ProofKind.PHOTO),
            ChecklistStep("paddle", "Measured paddle", "Calibrate against a known reference, then measure the hail strike.", ProofKind.MEASUREMENT),
            ChecklistStep("scope", "Scope agreed", "Subject confirms the capture scope before the first frame.", ProofKind.CONSENT),
        )
        JobType.RPZ_TEST -> listOf(
            ChecklistStep("vault", "Vault", "Inside the service vault polygon.", ProofKind.OBSERVATION),
            ChecklistStep("body", "Body", "Assembly body and tag readable, no separate tag claimed.", ProofKind.PHOTO),
            ChecklistStep("gauge", "Gauge", "Gauge face framed; the needle is not transcribed as a value.", ProofKind.PHOTO),
            ChecklistStep("cocks", "Test cocks", "Test cocks seated; no weep at the relief in this frame.", ProofKind.OBSERVATION),
            ChecklistStep("consent", "Consent", "MUD annual test authorization on file for this service.", ProofKind.CONSENT),
        )
        JobType.PROPERTY_WALK -> listOf(
            ChecklistStep("envelope", "Envelope", "Exterior envelope from the fixed corner reference.", ProofKind.PHOTO),
            ChecklistStep("common", "Common areas", "Walk route through named common areas only.", ProofKind.PHOTO),
            ChecklistStep("findings", "Findings", "Every finding gets a frame and a one-line note.", ProofKind.OBSERVATION),
            ChecklistStep("scope", "Scope agreed", "Manager confirms the route before capture starts.", ProofKind.CONSENT),
        )
        JobType.METER_CLUSTER -> listOf(
            ChecklistStep("bank", "Bank", "Exterior meter bank, wide frame.", ProofKind.PHOTO),
            ChecklistStep("serial", "Serial", "One frame per serial, legible without enlargement.", ProofKind.PHOTO),
            ChecklistStep("dials", "Dials", "Read dials photographed, not typed from memory.", ProofKind.PHOTO),
            ChecklistStep("obstructions", "Obstructions", "Note anything blocking a read.", ProofKind.OBSERVATION),
        )
        JobType.PROGRESS_PROOF -> listOf(
            ChecklistStep("milestone", "Milestone", "The milestone named in the brief, in place.", ProofKind.PHOTO),
            ChecklistStep("wide", "Wide frame", "Wide frame with site reference for context.", ProofKind.PHOTO),
            ChecklistStep("reference", "Reference", "Calibrated measurement of the changed dimension.", ProofKind.MEASUREMENT),
            ChecklistStep("scope", "Scope agreed", "Contractor confirms the stated scope.", ProofKind.CONSENT),
        )
        JobType.CLAIM_SWEEP -> listOf(
            ChecklistStep("establishing", "Establishing shot", "Address or unit reference in frame.", ProofKind.PHOTO),
            ChecklistStep("mid", "Mid shot", "The claimed item in its context.", ProofKind.PHOTO),
            ChecklistStep("detail", "Detail shot", "The claimed damage, close.", ProofKind.PHOTO),
            ChecklistStep("measure", "Measurement", "One calibrated measurement per claimed item.", ProofKind.MEASUREMENT),
            ChecklistStep("scope", "Scope agreed", "Subject confirms scope and exclusions.", ProofKind.CONSENT),
        )
    }

    fun requiredBadges(type: JobType): Set<BadgeKey> = BadgeKey.requiredFor(type)

    /** Short, human-safe packet title seeds used by the sample book. */
    fun subjectFor(type: JobType, detail: String): String = when (type) {
        JobType.ROOF_HAIL -> "Hail on the south slope · $detail"
        JobType.RPZ_TEST -> "Annual RPZ test · $detail"
        JobType.PROPERTY_WALK -> "Monthly walk · $detail"
        JobType.METER_CLUSTER -> "Meter cluster · $detail"
        JobType.PROGRESS_PROOF -> "Progress proof · $detail"
        JobType.CLAIM_SWEEP -> "Claim sweep · $detail"
    }
}
