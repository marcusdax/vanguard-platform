package com.odasi.fieldwright.data.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * In-process stand-in for the Fieldwright sync backend (CONTRACTS §8).
 *
 * Behaviour is intentionally *unpleasant in the way real networks are*:
 * - 200–800 ms of latency per request (overridable in tests);
 * - ~10% of first attempts answer `503` and must be retried;
 * - retries with the same idempotency key return the original acceptance instead of
 *   applying the mutation twice (the property every real sync endpoint must have).
 *
 * Determinism: the failure stream comes from a seeded [Random], so a given sequence of
 * events produces the same retries in tests and in the demo. The cache of accepted
 * envelopes is what proves the idempotency property.
 */
class FakeBackend(
    private val latencyMillis: IntRange = 200..800,
    private val failureRate: Double = 0.10,
    randomSeed: Long = 20_261_117L,
    /** Tests inject a no-op delay so virtual time stays empty. */
    private val delayFn: suspend (Long) -> Unit = { millis -> delay(millis) },
) : BackendApi {

    private val random = Random(randomSeed)
    private val mutex = Mutex()
    private val accepted = LinkedHashMap<String, BackendResponse.Accepted>()
    private val rejected = LinkedHashMap<String, BackendResponse.Rejected>()
    private var attempts: Int = 0

    /** Number of submissions attempted, including retries. Exposed for tests/diagnostics. */
    val submissionCount: Int get() = attempts

    override suspend fun submit(envelope: SyncEnvelope): BackendResponse {
        val latency = (latencyMillis.first + (latencyMillis.last - latencyMillis.first) * random.nextDouble()).toLong()
        delayFn(latency)
        mutex.withLock { attempts++ }

        // Idempotency first: a retry must never re-apply the mutation or re-roll its luck.
        mutex.withLock {
            accepted[envelope.idempotencyKey]?.let { return it }
            rejected[envelope.idempotencyKey]?.let { return it }
        }
        if (envelope.idempotencyKey.isBlank()) {
            return BackendResponse.Rejected("An outbox event without an idempotency key is not accepted.")
        }
        if (envelope.demo) {
            // The real backend must exclude sample-book rows from the public log; the fake
            // accepts them locally so the demo's sync health is honest, but records that it did.
            return BackendResponse.Accepted(serverRef = "demo-local:${envelope.idempotencyKey}")
        }

        val transient = mutex.withLock { random.nextDouble() < failureRate }
        if (transient) {
            return BackendResponse.Retryable("503 Service Unavailable — retry with the same idempotency key")
        }
        val response = BackendResponse.Accepted(serverRef = "srv-${envelope.idempotencyKey.takeLast(12)}")
        mutex.withLock { accepted[envelope.idempotencyKey] = response }
        return response
    }
}
