package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical JSON is the byte-level contract every hash depends on: sorted keys, escaped
 * values, no whitespace, no locale. These tests lock the format so a refactor cannot
 * silently change every historical hash.
 */
class CanonicalJsonTest {

    @Test
    fun `keys are sorted and there is no whitespace`() {
        val encoded = CanonicalJson.encode(linkedMapOf("b" to "2", "a" to "1", "c" to "3"))

        assertEquals("""{"a":"1","b":"2","c":"3"}""", encoded)
        assertFalse(encoded.contains(" "))
        assertFalse(encoded.contains("\n"))
    }

    @Test
    fun `insertion order does not change the bytes`() {
        val first = linkedMapOf("payout" to "2800", "actor" to "op-1", "seq" to "7")
        val second = linkedMapOf("seq" to "7", "actor" to "op-1", "payout" to "2800")

        assertEquals(CanonicalJson.encode(first), CanonicalJson.encode(second))
    }

    @Test
    fun `an empty map is the empty object`() {
        assertEquals("{}", CanonicalJson.encode(emptyMap()))
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        assertEquals("""a\"b""", CanonicalJson.escape("""a"b"""))
        assertEquals("""a\\b""", CanonicalJson.escape("""a\b"""))
        assertEquals("""a\\\"b""", CanonicalJson.escape("""a\"b"""))
    }

    @Test
    fun `control characters use the short JSON escapes`() {
        assertEquals("""a\nb""", CanonicalJson.escape("a\nb"))
        assertEquals("""a\tb""", CanonicalJson.escape("a\tb"))
        assertEquals("""a\rb""", CanonicalJson.escape("a\rb"))
        assertEquals("""a\bb""", CanonicalJson.escape("a\bb"))
        assertEquals("""a\fb""", CanonicalJson.escape("a\u000Cb"))
    }

    @Test
    fun `other C0 controls use lowercase unicode escapes`() {
        assertEquals("""a\u0001b""", CanonicalJson.escape("a\u0001b"))
        assertEquals("""\u001f""", CanonicalJson.escape("\u001F"))
        assertEquals("""\u0000""", CanonicalJson.escape("\u0000"))
    }

    @Test
    fun `plain text is returned untouched and unquoted`() {
        assertEquals("Creekside · MUD 14", CanonicalJson.escape("Creekside · MUD 14"))
        assertEquals("", CanonicalJson.escape(""))
    }

    @Test
    fun `non-ASCII stays raw UTF-8 so no locale or normalisation is involved`() {
        val text = "hail · 68 °F ✓"
        assertEquals(text, CanonicalJson.escape(text))
        assertEquals("""{"note":"$text"}""", CanonicalJson.encode(mapOf("note" to text)))
    }

    @Test
    fun `encoding is deterministic across repeated calls`() {
        val fields = mapOf(
            "summary" to "Packet sealed — 7 records",
            "prev" to "0".repeat(64),
            "payload.gauge" to "needle not transcribed",
        )

        val encoded = CanonicalJson.encode(fields)
        repeat(5) { assertEquals(encoded, CanonicalJson.encode(fields)) }
        assertTrue(encoded.startsWith("{"))
        assertTrue(encoded.endsWith("}"))
    }

    @Test
    fun `keys are escaped too, not only values`() {
        val encoded = CanonicalJson.encode(mapOf("""a"b""" to "v"))

        assertEquals("""{"a\"b":"v"}""", encoded)
    }

    @Test
    fun `keys sort by code unit, which is documented and stable`() {
        // Lexicographic code-unit order, not numeric order: this is part of the hash contract.
        assertEquals(
            """{"10":"ten","2":"two","a":"a"}""",
            CanonicalJson.encode(linkedMapOf("a" to "a", "2" to "two", "10" to "ten")),
        )
    }

    @Test
    fun `a value with every escapable character still round-trips visibly`() {
        val encoded = CanonicalJson.encode(mapOf("v" to "\"\\\n\t\u0001"))

        assertEquals("""{"v":"\"\\\n\t\u0001"}""", encoded)
        assertNotEquals(encoded, CanonicalJson.encode(mapOf("v" to "\"\\\n\t\u0002")))
    }
}
