package com.odasi.fieldwright.data.db

import androidx.room.TypeConverter
import com.odasi.fieldwright.model.BadgeKey
import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.OfferStatus
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutStatus
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase

/**
 * Enum ⇄ TEXT converters. Storing the enum *name* (never the ordinal) keeps the schema
 * readable in a SQLite browser and stable if the domain vocabulary is reordered — the
 * vocabulary in `core:model` is a contract, so renames are the only allowed change.
 */
class FieldwrightConverters {

    @TypeConverter fun jobTypeToString(value: JobType): String = value.name
    @TypeConverter fun stringToJobType(value: String): JobType = JobType.valueOf(value)

    @TypeConverter fun jobStatusToString(value: JobStatus): String = value.name
    @TypeConverter fun stringToJobStatus(value: String): JobStatus = JobStatus.valueOf(value)

    @TypeConverter fun requesterKindToString(value: RequesterKind): String = value.name
    @TypeConverter fun stringToRequesterKind(value: String): RequesterKind = RequesterKind.valueOf(value)

    @TypeConverter fun offerStatusToString(value: OfferStatus): String = value.name
    @TypeConverter fun stringToOfferStatus(value: String): OfferStatus = OfferStatus.valueOf(value)

    @TypeConverter fun evidenceTierToString(value: EvidenceTier): String = value.name
    @TypeConverter fun stringToEvidenceTier(value: String): EvidenceTier = EvidenceTier.valueOf(value)

    @TypeConverter fun sealStateToString(value: SealState): String = value.name
    @TypeConverter fun stringToSealState(value: String): SealState = SealState.valueOf(value)

    @TypeConverter fun recordKindToString(value: RecordKind): String = value.name
    @TypeConverter fun stringToRecordKind(value: String): RecordKind = RecordKind.valueOf(value)

    @TypeConverter fun badgeKeyToString(value: BadgeKey): String = value.name
    @TypeConverter fun stringToBadgeKey(value: String): BadgeKey = BadgeKey.valueOf(value)

    @TypeConverter fun badgeStateToString(value: BadgeState): String = value.name
    @TypeConverter fun stringToBadgeState(value: String): BadgeState = BadgeState.valueOf(value)

    @TypeConverter fun mediaKindToString(value: MediaKind): String = value.name
    @TypeConverter fun stringToMediaKind(value: String): MediaKind = MediaKind.valueOf(value)

    @TypeConverter fun mediaSourceToString(value: MediaSource): String = value.name
    @TypeConverter fun stringToMediaSource(value: String): MediaSource = MediaSource.valueOf(value)

    @TypeConverter fun redactionStateToString(value: RedactionState): String = value.name
    @TypeConverter fun stringToRedactionState(value: String): RedactionState = RedactionState.valueOf(value)

    @TypeConverter fun stepResultToString(value: StepResult): String = value.name
    @TypeConverter fun stringToStepResult(value: String): StepResult = StepResult.valueOf(value)

    @TypeConverter fun ledgerKindToString(value: LedgerKind): String = value.name
    @TypeConverter fun stringToLedgerKind(value: String): LedgerKind = LedgerKind.valueOf(value)

    @TypeConverter fun ledgerStatusToString(value: LedgerStatus): String = value.name
    @TypeConverter fun stringToLedgerStatus(value: String): LedgerStatus = LedgerStatus.valueOf(value)

    @TypeConverter fun payoutModeToString(value: PayoutMode): String = value.name
    @TypeConverter fun stringToPayoutMode(value: String): PayoutMode = PayoutMode.valueOf(value)

    @TypeConverter fun payoutStatusToString(value: PayoutStatus): String = value.name
    @TypeConverter fun stringToPayoutStatus(value: String): PayoutStatus = PayoutStatus.valueOf(value)

    @TypeConverter fun outboxKindToString(value: OutboxKind): String = value.name
    @TypeConverter fun stringToOutboxKind(value: String): OutboxKind = OutboxKind.valueOf(value)

    @TypeConverter fun outboxStatusToString(value: OutboxStatus): String = value.name
    @TypeConverter fun stringToOutboxStatus(value: String): OutboxStatus = OutboxStatus.valueOf(value)

    @TypeConverter fun syncPhaseToString(value: SyncPhase): String = value.name
    @TypeConverter fun stringToSyncPhase(value: String): SyncPhase = SyncPhase.valueOf(value)
}
