package com.odasi.fieldwright.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SHA-256 must be the exact, published algorithm — no homespun variant, no encoding drift. */
class Sha256Test {

    @Test
    fun `matches the published abc vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.sha256Hex("abc"),
        )
    }

    @Test
    fun `matches the published empty string vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.sha256Hex(""),
        )
    }

    @Test
    fun `matches the published longer vector`() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun `text and byte hashing agree and use UTF-8`() {
        val text = "Creekside · 68 °F"

        assertEquals(Sha256.sha256Hex(text.toByteArray(Charsets.UTF_8)), Sha256.sha256Hex(text))
        assertEquals(64, Sha256.sha256Hex(text).length)
    }

    @Test
    fun `output is lowercase hex and never locale formatted`() {
        val hash = Sha256.sha256Hex("Fieldwright")

        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hash.lowercase(), hash)
    }

    @Test
    fun `raw bytes are the 32 byte digest the hex encodes`() {
        val bytes = Sha256.sha256Bytes("Fieldwright".toByteArray(Charsets.UTF_8))

        assertEquals(32, bytes.size)
        assertEquals(Sha256.DIGEST_BYTES, bytes.size)
        assertEquals(Sha256.sha256Hex("Fieldwright"), Sha256.toLowerHex(bytes))
    }

    @Test
    fun `different inputs never collide in this fixture set`() {
        val inputs = listOf("", "a", "b", "abc", "abd", "hail", "Hail", "Creekside · MUD 14")
        val hashes = inputs.map { Sha256.sha256Hex(it) }.toSet()

        assertEquals(inputs.size, hashes.size)
        assertNotEquals(Sha256.sha256Hex("abc"), Sha256.sha256Hex("abd"))
    }

    @Test
    fun `hashing is deterministic across calls`() {
        val first = Sha256.sha256Hex("convergence")

        repeat(5) { assertEquals(first, Sha256.sha256Hex("convergence")) }
    }
}
