package com.odasi.fieldwright.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The software signer is the dev/emulator identity. What matters: it is real ECDSA
 * P-256, signatures round-trip, tampering fails, and the key id is a stable fingerprint
 * of the public key rather than a random label.
 */
class SoftwareEcdsaSignerTest {

    private val signer = CryptoFixtures.signer

    @Test
    fun `sign and verify round-trip over the canonical bytes`() {
        val canonical = """{"prev":"00","seq":"0","summary":"Packet opened"}"""
        val signature = signer.sign(canonical)

        assertTrue(signature != null)
        assertTrue(signer.verify(canonical, signature!!))
        assertTrue(signer.hasPrivateKey)
    }

    @Test
    fun `a tampered canonical string does not verify`() {
        val canonical = """{"payout":"2800"}"""
        val signature = signer.sign(canonical)!!

        assertFalse(signer.verify("""{"payout":"9800"}""", signature))
        assertFalse(signer.verify("", signature))
    }

    @Test
    fun `a signature from another key does not verify`() {
        val other = SoftwareEcdsaSigner.generate()
        val canonical = "seal-head-hash"
        val signature = other.sign(canonical)!!

        assertFalse(signer.verify(canonical, signature))
        assertTrue(other.verify(canonical, signature))
        assertNotEquals(signer.publicKeyBase64, other.publicKeyBase64)
    }

    @Test
    fun `the signature is Base64 DER ECDSA, not a fake tag`() {
        val signature = signer.sign("canonical")!!
        val der = Base64.getDecoder().decode(signature)

        assertTrue(der.isNotEmpty())
        assertEquals(0x30.toByte(), der[0]) // ASN.1 SEQUENCE
    }

    @Test
    fun `the key id is the sha-256 fingerprint of the public key`() {
        val expected = Sha256.sha256Hex(Base64.getDecoder().decode(signer.publicKeyBase64))

        assertEquals(expected, signer.keyId)
        assertEquals(64, signer.keyId.length)
        assertTrue(signer.keyId.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `a key id hint never replaces the fingerprint identity`() {
        val hinted = SoftwareEcdsaSigner.generate(keyIdHint = "emulator-dev-default")

        assertEquals("emulator-dev-default", hinted.keyIdHint)
        assertEquals(
            Sha256.sha256Hex(Base64.getDecoder().decode(hinted.publicKeyBase64)),
            hinted.keyId,
        )
        assertNotEquals(hinted.keyIdHint, hinted.keyId)
    }

    @Test
    fun `exported key material restores the same identity`() {
        val material = signer.exportKeyMaterial()
        assertTrue(material != null)

        val restored = SoftwareEcdsaSigner.fromKeyMaterial(material!!)

        assertEquals(signer.keyId, restored.keyId)
        assertEquals(signer.publicKeyBase64, restored.publicKeyBase64)
        val signature = signer.sign("cross-instance")!!
        assertTrue(restored.verify("cross-instance", signature))
        val restoredSignature = restored.sign("cross-instance")!!
        assertTrue(signer.verify("cross-instance", restoredSignature))
    }

    @Test
    fun `a verify-only signer can check chains but cannot sign`() {
        val verifier = SoftwareEcdsaSigner.verifierOnly(signer.publicKeyBase64)
        val signature = signer.sign("head-hash")!!

        assertFalse(verifier.hasPrivateKey)
        assertNull(verifier.exportKeyMaterial())
        assertNull(verifier.sign("head-hash"))
        assertTrue(verifier.verify("head-hash", signature))
        assertEquals(signer.keyId, verifier.keyId)
    }

    @Test
    fun `wrapping an existing P-256 key pair keeps the same key id`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(SoftwareEcdsaSigner.CURVE))
        val pair = generator.generateKeyPair()

        val first = SoftwareEcdsaSigner.fromKeyPair(pair)
        val second = SoftwareEcdsaSigner.fromKeyPair(pair)

        assertEquals(first.keyId, second.keyId)
        assertEquals(first.publicKeyBase64, second.publicKeyBase64)
        assertTrue(second.verify("same-key", first.sign("same-key")!!))
    }

    @Test
    fun `every generated signer is a distinct identity`() {
        val other = SoftwareEcdsaSigner()

        assertNotEquals(signer.keyId, other.keyId)
        assertTrue(other.hasPrivateKey)
    }

    @Test
    fun `an empty canonical string is still signable`() {
        val signature = signer.sign("")

        assertTrue(signature != null)
        assertTrue(signer.verify("", signature!!))
    }

    @Test
    fun `invalid Base64 never verifies and never throws`() {
        assertFalse(signer.verify("canonical", "not-base64!!"))
        assertFalse(signer.verify("canonical", ""))
    }

    @Test
    fun `two signatures over the same message both verify`() {
        val first = signer.sign("head")!!
        val second = signer.sign("head")!!

        assertTrue(signer.verify("head", first))
        assertTrue(signer.verify("head", second))
    }
}
