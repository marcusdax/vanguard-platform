package com.odasi.fieldwright.data.sync

import com.odasi.fieldwright.data.db.OutboxKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [FakeBackend] behaviour that the real backend must share: idempotent retries, a
 * deterministic failure stream, and honest handling of demo events.
 */
class FakeBackendTest {

    private fun envelope(key: String, demo: Boolean = false): SyncEnvelope = SyncEnvelope(
        idempotencyKey = key,
        kind = OutboxKind.PACKET_SEALED,
        method = "POST",
        path = "/packets",
        payload = mapOf("packetId" to "FW-0994"),
        createdAtEpochMs = 1_743_000_000_000L,
        demo = demo,
    )

    @Test
    fun `retrying the same idempotency key returns the first acceptance`() = runTest {
        val backend = FakeBackend(latencyMillis = 0..0, failureRate = 0.0, randomSeed = 11, delayFn = {})
        val first = assertIs<BackendResponse.Accepted>(backend.submit(envelope("pkt-FW-0994")))
        val second = assertIs<BackendResponse.Accepted>(backend.submit(envelope("pkt-FW-0994")))

        assertEquals(first.serverRef, second.serverRef)
        assertEquals(2, backend.submissionCount)
    }

    @Test
    fun `transient 503s are retryable and a later attempt can be accepted`() = runTest {
        val flaky = FakeBackend(latencyMillis = 0..0, failureRate = 1.0, randomSeed = 5, delayFn = {})
        assertIs<BackendResponse.Retryable>(flaky.submit(envelope("pkt-1")))
        assertIs<BackendResponse.Retryable>(flaky.submit(envelope("pkt-1")))
    }

    @Test
    fun `a demo event is accepted locally but marked as demo in the server reference`() = runTest {
        val backend = FakeBackend(latencyMillis = 0..0, failureRate = 0.0, randomSeed = 3, delayFn = {})
        val response = backend.submit(envelope("demo:pkt", demo = true))
        val accepted = assertIs<BackendResponse.Accepted>(response)
        assertTrue(accepted.serverRef!!.startsWith("demo-local:"))
    }

    @Test
    fun `an event without an idempotency key is rejected permanently`() = runTest {
        val backend = FakeBackend(latencyMillis = 0..0, failureRate = 0.0, randomSeed = 3, delayFn = {})
        assertIs<BackendResponse.Rejected>(backend.submit(envelope("")))
    }

    @Test
    fun `the http stub makes no network call and says so`() = runTest {
        val stub = HttpBackendApi("https://api.fieldwright.invalid")
        val response = stub.submit(envelope("pkt-http"))
        val retryable = assertIs<BackendResponse.Retryable>(response)
        assertTrue(retryable.reason.contains("not enabled", ignoreCase = true))
    }
}
