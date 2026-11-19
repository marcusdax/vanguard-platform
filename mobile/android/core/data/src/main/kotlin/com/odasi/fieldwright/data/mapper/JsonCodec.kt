package com.odasi.fieldwright.data.mapper

import com.odasi.fieldwright.model.ChecklistStep
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HotZone
import com.odasi.fieldwright.model.ProofKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * Small, explicit JSON codecs for the handful of value objects that are *genuinely opaque*
 * at the database boundary (checklist definitions, consent, record payloads, hot zones,
 * Merkle proofs, outbox payloads). Everything with a business meaning — money, ids,
 * statuses — stays a typed column.
 *
 * `core:model` is deliberately serialization-framework-free, so the codecs are written by
 * hand against `kotlinx.serialization.json` builders. Encoding is deterministic: keys are
 * emitted in a fixed order, so a re-encoded row is byte-identical (useful for hashing).
 */
internal object JsonCodec {

    val json: Json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- primitives

    fun encodeStrings(values: List<String>): String =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

    fun decodeStrings(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content } }
            .getOrElse { emptyList() }
    }

    fun encodeMap(values: Map<String, String>): String =
        buildJsonObject { values.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()

    fun decodeMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
        }.getOrElse { emptyMap() }
    }

    // ---------------------------------------------------------------- checklist definition

    fun encodeChecklist(steps: List<ChecklistStep>): String = buildJsonArray {
        steps.forEach { step ->
            add(buildJsonObject {
                put("key", JsonPrimitive(step.key))
                put("label", JsonPrimitive(step.label))
                put("hint", JsonPrimitive(step.hint))
                put("proofKind", JsonPrimitive(step.proofKind.name))
                put("required", JsonPrimitive(step.required))
            })
        }
    }.toString()

    fun decodeChecklist(raw: String?): List<ChecklistStep> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.map { element ->
                val obj = element.jsonObject
                ChecklistStep(
                    key = obj.str("key"),
                    label = obj.str("label"),
                    hint = obj.str("hint"),
                    proofKind = ProofKind.valueOf(obj.str("proofKind")),
                    required = obj["required"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }
        }.getOrElse { emptyList() }
    }

    // ---------------------------------------------------------------- consent

    fun encodeConsent(consent: Consent?): String? = consent?.let {
        buildJsonObject {
            put("name", JsonPrimitive(it.name))
            put("role", JsonPrimitive(it.role))
            put("scopeText", JsonPrimitive(it.scopeText))
            put("method", JsonPrimitive(it.method.name))
            put("capturedAtEpochMs", JsonPrimitive(it.capturedAtEpochMs))
            put("signatureHash", it.signatureHash?.let { hash -> JsonPrimitive(hash) } ?: kotlinx.serialization.json.JsonNull)
            put("verifiedByOperatorId", JsonPrimitive(it.verifiedByOperatorId))
        }.toString()
    }

    fun decodeConsent(raw: String?): Consent? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            Consent(
                name = obj.str("name"),
                role = obj.str("role"),
                scopeText = obj.str("scopeText"),
                method = ConsentMethod.valueOf(obj.str("method")),
                capturedAtEpochMs = obj.long("capturedAtEpochMs"),
                signatureHash = obj["signatureHash"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content },
                verifiedByOperatorId = obj.str("verifiedByOperatorId"),
            )
        }.getOrNull()
    }

    // ---------------------------------------------------------------- gates

    fun encodeGates(gates: List<GateCheck>): String = buildJsonArray {
        gates.forEach { gate ->
            add(buildJsonObject {
                put("id", JsonPrimitive(gate.id))
                put("label", JsonPrimitive(gate.label))
                put("passed", JsonPrimitive(gate.passed))
                put("detail", JsonPrimitive(gate.detail))
                put("blocking", JsonPrimitive(gate.blocking))
            })
        }
    }.toString()

    fun decodeGates(raw: String?): List<GateCheck> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.map { element ->
                val obj = element.jsonObject
                GateCheck(
                    id = obj.str("id"),
                    label = obj.str("label"),
                    passed = obj["passed"]?.jsonPrimitive?.booleanOrNull ?: false,
                    detail = obj.str("detail"),
                    blocking = obj["blocking"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }
        }.getOrElse { emptyList() }
    }

    // ---------------------------------------------------------------- hot zones

    fun encodeHotZones(zones: List<HotZone>): String = buildJsonArray {
        zones.forEach { zone ->
            add(buildJsonObject {
                put("label", JsonPrimitive(zone.label))
                put("lat", JsonPrimitive(zone.point.lat))
                put("lng", JsonPrimitive(zone.point.lng))
                put("recordedAtEpochMs", JsonPrimitive(zone.point.recordedAtEpochMs))
                put("accuracyMeters", JsonPrimitive(zone.point.accuracyMeters))
                put("source", JsonPrimitive(zone.point.source.name))
                put("radiusMeters", JsonPrimitive(zone.radiusMeters))
                put("surgeMultiplier", JsonPrimitive(zone.surgeMultiplier))
                put("reason", JsonPrimitive(zone.reason))
            })
        }
    }.toString()

    fun decodeHotZones(raw: String?): List<HotZone> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.map { element ->
                val obj = element.jsonObject
                HotZone(
                    label = obj.str("label"),
                    point = GeoPoint(
                        lat = obj.double("lat"),
                        lng = obj.double("lng"),
                        recordedAtEpochMs = obj.long("recordedAtEpochMs"),
                        accuracyMeters = obj.double("accuracyMeters").toFloat(),
                        source = GeoPoint.FixSource.valueOf(obj.str("source")),
                    ),
                    radiusMeters = obj.int("radiusMeters"),
                    surgeMultiplier = obj.double("surgeMultiplier"),
                    reason = obj.str("reason"),
                )
            }
        }.getOrElse { emptyList() }
    }

    // ---------------------------------------------------------------- helpers

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0
    private fun JsonObject.double(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
}
