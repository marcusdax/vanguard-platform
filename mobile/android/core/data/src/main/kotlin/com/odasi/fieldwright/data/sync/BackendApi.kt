package com.odasi.fieldwright.data.sync

import com.odasi.fieldwright.data.db.OutboxKind

/**
 * The transport seam between the outbox and the Fieldwright backend.
 *
 * The repositories never see HTTP: they enqueue an event with an idempotency key, and a
 * [BackendApi] implementation decides what the server said. Sprint 1 ships [FakeBackend]
 * (in-process, deterministic) so the demo has no network dependency; the real adapter is
 * [HttpBackendApi], which is a documented stub until `docs/api/fieldwright.openapi.yaml`
 * is served. Wiring the OpenAPI contract means implementing [BackendApi.submit] only —
 * no repository, outbox or worker change.
 */
data class SyncEnvelope(
    /** Stable across every retry of the same logical mutation (server dedupes on it). */
    val idempotencyKey: String,
    val kind: OutboxKind,
    val method: String,
    val path: String,
    val payload: Map<String, String>,
    val createdAtEpochMs: Long,
    /** Demo events are never uploaded to the live log; the backend must reject/ignore them. */
    val demo: Boolean,
)

sealed interface BackendResponse {

    /** The mutation was applied. [serverRef] is the server-side id, when one is returned. */
    data class Accepted(val serverRef: String? = null) : BackendResponse

    /** A transient failure (5xx, timeout, no network). Retry with the same idempotency key. */
    data class Retryable(val reason: String) : BackendResponse

    /** A permanent failure (4xx validation). Retrying cannot fix it; dead-letter it. */
    data class Rejected(val reason: String) : BackendResponse
}

interface BackendApi {
    /**
     * Submits one event. Implementations **must** be idempotent per [SyncEnvelope.idempotencyKey]:
     * submitting the same envelope twice returns the first response and applies the mutation once.
     */
    suspend fun submit(envelope: SyncEnvelope): BackendResponse
}

/**
 * Contract stub for the real backend (see docs/03-api-contract.md and
 * `docs/api/fieldwright.openapi.yaml`). It deliberately makes no network call: the demo
 * build runs with [FakeBackend], and shipping a half-wired HTTP client would be worse than
 * a loud, documented "not configured".
 *
 * Wiring recipe: POST `$baseUrl/sync/outbox` with the envelope as JSON, header
 * `Idempotency-Key: <envelope.idempotencyKey>`, bearer token from [authTokenProvider];
 * map 2xx → [BackendResponse.Accepted], 5xx/408/429 → [BackendResponse.Retryable],
 * other 4xx → [BackendResponse.Rejected]. Nothing else in `core:data` changes.
 */
class HttpBackendApi(
    private val baseUrl: String,
    private val authTokenProvider: () -> String? = { null },
) : BackendApi {

    /** Documented but unused in the demo build. */
    val endpoint: String get() = "${baseUrl.trimEnd('/')}/sync/outbox"

    /** Documented but unused in the demo build. */
    fun authorizationHeader(): String? = authTokenProvider()?.let { "Bearer $it" }

    override suspend fun submit(envelope: SyncEnvelope): BackendResponse = BackendResponse.Retryable(
        "HTTP sync is not enabled in this build (target $endpoint). " +
            "Implement BackendApi.submit against docs/api/fieldwright.openapi.yaml; " +
            "the outbox already carries the idempotency key and payload.",
    )
}
