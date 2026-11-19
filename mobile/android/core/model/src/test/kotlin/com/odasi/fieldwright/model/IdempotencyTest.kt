package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Idempotency keys: the same logical request must be retryable without duplicating money
 * or evidence, and two different requests must never collapse into one key.
 */
class IdempotencyTest {

    @Test
    fun `the same logical operation always produces the same key`() {
        val first = Idempotency.key("ledger", "job-1", "EARNING", "2800")
        val second = Idempotency.key("ledger", "job-1", "EARNING", "2800")

        assertEquals(first, second)
        repeat(10) { assertEquals(first, Idempotency.key("ledger", "job-1", "EARNING", "2800")) }
    }

    @Test
    fun `keys are version-prefixed so the encoding can evolve safely`() {
        assertTrue(Idempotency.key("seal", "job-1", "7").startsWith("fw1:"))
    }

    @Test
    fun `part order matters`() {
        assertNotEquals(Idempotency.key("seal", "job-1"), Idempotency.key("job-1", "seal"))
    }

    @Test
    fun `a separator inside a part cannot be confused with a part boundary`() {
        assertNotEquals(Idempotency.key("seal|job-1", "7"), Idempotency.key("seal", "job-1|7"))
        assertNotEquals(Idempotency.key("a|b", "c"), Idempotency.key("a", "b|c"))
    }

    @Test
    fun `a backslash inside a part cannot escape the separators`() {
        assertNotEquals(Idempotency.key("""a\""", "b"), Idempotency.key("""a""", """\b"""))
    }

    @Test
    fun `extra parts make a different key`() {
        assertNotEquals(
            Idempotency.key("payout", "op-1", "1000", "INSTANT"),
            Idempotency.key("payout", "op-1", "1000"),
        )
    }

    @Test
    fun `the empty part is distinct from a missing part`() {
        assertNotEquals(Idempotency.key("seal", ""), Idempotency.key("seal"))
        assertEquals(Idempotency.key("seal", ""), Idempotency.key("seal", ""))
    }

    @Test
    fun `at least one part is required`() {
        try {
            Idempotency.key()
            fail("A key with no parts is meaningless and must be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `keys are readable so support can match a request to its entry`() {
        assertEquals("fw1:ledger|job-1|EARNING|2800", Idempotency.key("ledger", "job-1", "EARNING", "2800"))
    }

    @Test
    fun `different jobs never share an idempotent operation key`() {
        val a = Idempotency.key("ledger", "job-1", LedgerKind.EARNING.name, "2800")
        val b = Idempotency.key("ledger", "job-2", LedgerKind.EARNING.name, "2800")

        assertNotEquals(a, b)
    }
}
