package com.odasi.fieldwright.data.mapper

import com.odasi.fieldwright.data.Fixtures
import com.odasi.fieldwright.data.db.PacketAggregate
import com.odasi.fieldwright.model.Badge
import com.odasi.fieldwright.model.BadgeKey
import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.PayoutStatus
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.SealState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Entity ⇄ model round-trips. Pure JVM tests: no Room, no Android, no clock. They are the
 * guarantee that the UI sees exactly the domain object the contract promises and that the
 * opaque JSON columns (consent, checklist, gates, proofs) survive the trip.
 */
class MappersTest {

    private val now = Fixtures.NOW

    @Test
    fun `job entity round-trips every field the screens read`() {
        val job = Fixtures.job()
        val entity = Mappers.run { job.toEntity(updatedAtEpochMs = now) }
        val back = Mappers.run { entity.toModel() }

        assertEquals(job.id, back.id)
        assertEquals(job.type, back.type)
        assertEquals(job.title, back.title)
        assertEquals(job.requester, back.requester)
        assertEquals(job.site, back.site)
        assertEquals(job.status, back.status)
        assertEquals(job.bountyCents, back.bountyCents)
        assertEquals(job.bonusCents, back.bonusCents)
        assertEquals(job.payoutCents, back.payoutCents)
        assertEquals(job.checklist, back.checklist)
        assertEquals(job.requiredCredentials, back.requiredCredentials)
        assertEquals(job.acceptedAtEpochMs, back.acceptedAtEpochMs)
        assertEquals(job.arrivedAtEpochMs, back.arrivedAtEpochMs)
        assertEquals(job.demo, back.demo)
        assertEquals(now, entity.updatedAtEpochMs)
    }

    @Test
    fun `operator and availability round-trip including auto-accept policy`() {
        val operator = Fixtures.operator()
        val operatorEntity = Mappers.run { operator.toEntity(demo = true) }
        assertEquals(operator, Mappers.run { operatorEntity.toModel() })
        assertEquals(true, operatorEntity.demo)

        val availability = Fixtures.availability()
        val availabilityEntity = Mappers.run {
            availability.toEntity(onlineMinutesToday = 187, onlineSinceEpochMs = now - 120_000)
        }
        val back = Mappers.run { availabilityEntity.toModel() }
        assertEquals(availability.isOnline, back.isOnline)
        assertEquals(availability.radiusMeters, back.radiusMeters)
        assertEquals(availability.areaLabel, back.areaLabel)
        assertEquals(availability.autoAccept, back.autoAccept)
        assertEquals(187, availabilityEntity.onlineMinutesToday)
        assertEquals(now - 120_000, availabilityEntity.onlineSinceEpochMs)
    }

    @Test
    fun `money rows round-trip with signed cents and statuses`() {
        val entry = LedgerEntry(
            id = "LG-1",
            jobId = "job-test-1",
            packetId = "FW-0001",
            kind = LedgerKind.BONUS,
            amountCents = 1_500,
            status = LedgerStatus.AVAILABLE,
            atEpochMs = now,
            description = "Busy bonus",
            idempotencyKey = "demo:ledger:bonus",
            demo = true,
        )
        assertEquals(entry, Mappers.run { Mappers.run { entry.toEntity() }.toModel() })

        val payout = PayoutRequest(
            id = "PO-1",
            amountCents = 38_000,
            feeCents = 0,
            netCents = 38_000,
            mode = PayoutMode.STANDARD,
            status = PayoutStatus.PAID,
            requestedAtEpochMs = now - 1_000,
            completedAtEpochMs = now,
            destinationLast4 = "4417",
            idempotencyKey = "demo:payout:1",
        )
        assertEquals(payout, Mappers.run { Mappers.run { payout.toEntity(demo = true) }.toModel() })
    }

    @Test
    fun `media, measurement and checklist results round-trip through job-scoped rows`() {
        val media = Fixtures.media().first()
        assertEquals(media, Mappers.run { Mappers.run { media.toEntity(jobId = "job-test-1", packetId = null) }.toModel() })

        val measurement = Fixtures.measurement()
        assertEquals(
            measurement,
            Mappers.run { Mappers.run { measurement.toEntity(jobId = "job-test-1", packetId = null) }.toModel() },
        )

        val job = Fixtures.job()
        val checklist = Fixtures.checklist(job).first()
        assertEquals(
            checklist,
            Mappers.run {
                Mappers.run { checklist.toEntity(jobId = job.id, packetId = null, recordedAtEpochMs = now) }.toModel()
            },
        )
    }

    @Test
    fun `packet aggregate round-trips records, badges, media, gates and merkle proof`() {
        val job = Fixtures.job()
        val record = ChainRecord(
            // 1-based sequence, matching the server-parity chain contract.
            seq = 1,
            kind = RecordKind.CUSTODY,
            atEpochMs = now,
            actorId = "op-test",
            summary = "Packet opened",
            payload = mapOf("jobId" to job.id, "captureStandard" to job.type.captureStandard),
            prevHash = "0".repeat(64),
            hash = "a".repeat(64),
            signatureBase64 = "c2ln",
            signerKeyId = "test-key-0001",
        )
        val badge = Badge(
            key = BadgeKey.CONSENT_ON_FILE,
            state = BadgeState.VERIFIED,
            required = true,
            rationale = "Authorized by the facilities lead.",
            evidenceRefs = listOf("a".repeat(8)),
        )
        val packet = EvidencePacket(
            id = "FW-0001",
            jobId = job.id,
            jobType = job.type,
            operatorId = "op-test",
            title = job.title,
            requesterLabel = job.requester.org,
            site = job.site,
            consent = Fixtures.consent(),
            badges = listOf(badge),
            tier = EvidenceTier.FIELD_CAPTURED,
            seal = SealState.PENDING,
            records = listOf(record),
            media = Fixtures.media(),
            checklist = Fixtures.checklist(job),
            gates = Fixtures.passingGates(),
            measurements = listOf(Fixtures.measurement()),
            notes = "n",
            demo = true,
            createdAtEpochMs = now,
            sealedAtEpochMs = null,
            merkleLeaf = "b".repeat(64),
            merkleProof = listOf("c".repeat(64)),
            asOfEpochMs = now,
            signatureBase64 = "c2ln",
        )

        val packetEntity = Mappers.run {
            packet.toEntity(
                merkleTreeSize = 2,
                merkleRoot = "d".repeat(64),
                merkleIndex = 1,
                sealedHeadHash = "e".repeat(64),
            )
        }
        val aggregate = PacketAggregate(
            packet = packetEntity,
            records = packet.records.map { Mappers.run { it.toEntity(packet.id) } },
            badges = packet.badges.map { Mappers.run { it.toEntity(packet.id) } },
            media = packet.media.map { Mappers.run { it.toEntity(packet.jobId, packet.id) } },
            measurements = packet.measurements.map { Mappers.run { it.toEntity(packet.jobId, packet.id) } },
            checklist = packet.checklist.map { Mappers.run { it.toEntity(packet.jobId, packet.id, now) } },
        )

        assertEquals(2, packetEntity.merkleTreeSize)
        assertEquals(1, packetEntity.merkleIndex)
        assertEquals("e".repeat(64), packetEntity.sealedHeadHash)

        val back = Mappers.run { aggregate.toModel() }
        assertEquals(packet.id, back.id)
        assertEquals(packet.records, back.records)
        assertEquals(packet.badges, back.badges)
        assertEquals(packet.consent, back.consent)
        assertEquals(packet.media, back.media)
        assertEquals(packet.measurements, back.measurements)
        assertEquals(packet.checklist.sortedBy { it.key }, back.checklist.sortedBy { it.key })
        assertEquals(packet.gates, back.gates)
        assertEquals(packet.merkleProof, back.merkleProof)
        assertEquals(packet.demo, back.demo)
        assertNotNull(back.packetHash)
    }

    @Test
    fun `sync state model carries derived outbox counts`() {
        val entity = Mappers.run { defaultSyncState(now) }
        val model = Mappers.run { entity.toModel(queued = 3, inFlight = 1, deadLetters = 2) }
        assertEquals(3, model.queued)
        assertEquals(1, model.inFlight)
        assertEquals(2, model.deadLetters)
        assertEquals(now, entity.updatedAtEpochMs)
        assertNull(model.lastSyncedAtEpochMs)
    }
}
