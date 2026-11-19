package com.odasi.fieldwright.data.sync

import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.RecordKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Server parity for the outbox upload body: the record wire shape must be exactly what the
 * server re-hashes (`docs/03-api-contract.md` §3.4), or every packet arrives "tampered".
 */
class WireFormatTest {

    private val record = ChainRecord(
        seq = 1,
        kind = RecordKind.CAPTURE,
        atEpochMs = 1_743_000_000_000L,
        actorId = "op-test",
        summary = "Frame 1 · establishing",
        payload = mapOf("sha256" to "ab".repeat(32), "captureStandardMet" to "true"),
        prevHash = "0".repeat(64),
        hash = "a".repeat(64),
        signatureBase64 = "c2lnbmF0dXJl",
        signerKeyId = "test-key-0001",
    )

    @Test
    fun `record fields match the server contract`() {
        val json = WireFormat.recordsJson(listOf(record))

        assertTrue(json.contains("\"seq\":1"), "seq must be a JSON number: $json")
        assertFalse(json.contains("\"seq\":\"1\""), "seq must not be a string: $json")
        assertTrue(json.contains("\"kind\":\"CAPTURE\""))
        assertTrue(json.contains("\"actorId\":\"op-test\""))
        assertTrue(json.contains("\"summary\":\"Frame 1 · establishing\""))
        assertTrue(json.contains("\"payload\":{"))
        assertTrue(json.contains("\"captureStandardMet\":\"true\""), "payload must stay nested, not flattened")
        assertTrue(json.contains("\"prevHash\":\""))
        assertTrue(json.contains("\"hash\":\"${"a".repeat(64)}\""), "the device hash is transported, never re-hashed")
        assertTrue(json.contains("\"signatureBase64\":\"c2lnbmF0dXJl\""))
        assertTrue(json.contains("\"signerKeyId\":\"test-key-0001\""))
    }

    @Test
    fun `timestamp is the ISO-8601 millisecond form the hash pre-image uses`() {
        val json = WireFormat.recordsJson(listOf(record))
        assertTrue(
            Regex("\"at\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\"").containsMatchIn(json),
            "expected ISO-8601 UTC ms timestamp in $json",
        )
        assertFalse(json.contains("atEpochMs"), "the wire shape must not carry a numeric timestamp field")
    }

    @Test
    fun `payload keys are emitted deterministically and unsigned records carry nulls`() {
        val first = WireFormat.recordsJson(listOf(record))
        val second = WireFormat.recordsJson(listOf(record))
        assertEqualsStrings(first, second)

        val unsigned = record.copy(signatureBase64 = null, signerKeyId = null)
        val json = WireFormat.recordsJson(listOf(unsigned))
        assertTrue(json.contains("\"signatureBase64\":null"), json)
        assertTrue(json.contains("\"signerKeyId\":null"), json)
    }

    @Test
    fun `a record list serialises as a JSON array`() {
        val json = WireFormat.recordsJson(listOf(record, record.copy(seq = 2)))
        assertTrue(json.trimStart().startsWith("["))
        assertTrue(json.trimEnd().endsWith("]"))
        assertTrue(json.contains("\"seq\":2"))
    }

    private fun assertEqualsStrings(first: String, second: String) {
        assertTrue(first == second, "wire encoding must be deterministic")
    }
}
