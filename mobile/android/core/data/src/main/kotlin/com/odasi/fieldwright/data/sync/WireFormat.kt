package com.odasi.fieldwright.data.sync

import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.ChainRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Wire form of an evidence record, byte-aligned with the server contract
 * (`docs/03-api-contract.md` §3.4 and `backend/mock-server.mjs`).
 *
 * Field names and value shapes are not cosmetic here: the server recomputes
 * `sha256(canonicalJson({seq, kind, at, actorId, summary, payload, prevHash}))` and
 * compares it with the `hash` the device sent. If the wire mapping drifts (a flattened
 * payload, a numeric timestamp, a 0-based sequence) the server rejects every packet as
 * tampered, so the mapping lives in exactly one place and is unit-tested.
 *
 * - `seq` is a JSON **number**, 1-based (the model's sequence contract);
 * - `at` is the ISO-8601 UTC millisecond string produced by [ChainRule.isoAt], the same
 *   formatter the hash pre-image uses — the bytes sent are the bytes hashed;
 * - `payload` is a **nested** object of string values, sorted for determinism;
 * - `hash`, `signatureBase64` and `signerKeyId` are transported unchanged (never re-hashed).
 */
object WireFormat {

    /** One record as the server sees it. */
    fun recordJson(record: ChainRecord): JsonObject = buildJsonObject {
        put("seq", JsonPrimitive(record.seq))
        put("kind", JsonPrimitive(record.kind.name))
        put("at", JsonPrimitive(ChainRule.isoAt(record.atEpochMs)))
        put("actorId", JsonPrimitive(record.actorId))
        put("summary", JsonPrimitive(record.summary))
        put(
            "payload",
            buildJsonObject {
                record.payload.toSortedMap().forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            },
        )
        put("prevHash", JsonPrimitive(record.prevHash))
        put("hash", JsonPrimitive(record.hash))
        put("signatureBase64", record.signatureBase64?.let { JsonPrimitive(it) } ?: JsonNull)
        put("signerKeyId", record.signerKeyId?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** A full record list as one JSON array string (for outbox payloads and exports). */
    fun recordsJson(records: List<ChainRecord>): String = buildJsonArray {
        records.forEach { add(recordJson(it)) }
    }.toString()

    /** Parsed record array, exposed for the HTTP adapter's request-body assembly. */
    fun recordsArray(records: List<ChainRecord>): JsonArray = buildJsonArray {
        records.forEach { add(recordJson(it)) }
    }
}
