package com.odasi.fieldwright.crypto

import com.odasi.fieldwright.model.SignatureScheme
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Portable key material for the software signer, so an emulator or test run keeps the
 * same signing identity across process restarts. Both halves are needed: JCA can
 * reconstruct a [PrivateKey] from PKCS#8 and a [PublicKey] from X.509, but it cannot
 * derive one from the other.
 *
 * This is **not** production key storage. A real device keeps its signing key in the
 * Android Keystore (`AndroidKeystoreSigner` lives in `core:data`); the software signer
 * exists for tests, emulators, and desktop verification where no secure element exists.
 */
data class SoftwareKeyMaterial(
    val privateKeyPkcs8Base64: String,
    val publicKeyX509Base64: String,
)

/**
 * ECDSA P-256 signer over the JDK JCA, used for packet and chain-record signatures when
 * no Android Keystore is available (unit tests, emulators, desktop verification).
 *
 * Design decisions that matter:
 * - **P-256 (secp256r1)**, not P-521 or secp256k1: universally supported by Android,
 *   the JDK, and every server-side verifier, and 256-bit is the standard field-evidence
 *   strength.
 * - **`keyId` is a deterministic fingerprint of the public key** (lowercase SHA-256 hex
 *   of the X.509/SPKI encoding), never a random label. A record's `signerKeyId` can then
 *   be verified against the key itself, and two runs that generate the same key produce
 *   the same id. `keyIdHint` is only an operator-facing alias and never changes the id.
 * - **`sign` returns Base64 DER** (`SHA256withECDSA`), the widest-supported encoding; the
 *   signature covers the caller's canonical string, which for records is the record hash
 *   and for packets is the chain head hash.
 * - **`sign` returns null when the signer holds no private key** (a verify-only instance)
 *   rather than throwing: a verifier device must be able to walk a chain without being
 *   able to sign one.
 */
class SoftwareEcdsaSigner private constructor(
    private val privateKey: PrivateKey?,
    val publicKey: PublicKey,
    /** Advisory operator-facing label (e.g. a keystore alias); never part of [keyId]. */
    val keyIdHint: String?,
) : SignatureScheme {

    /** Generates a fresh P-256 key pair in memory. */
    constructor() : this(generateKeyPair())

    /** Wraps an existing P-256 key pair (e.g. one loaded from test fixtures). */
    constructor(keyPair: KeyPair) : this(keyPair.private, keyPair.public, null)

    /** Stable, deterministic signing identity: SHA-256 fingerprint of the public key. */
    override val keyId: String = fingerprint(publicKey)

    /** The X.509 public key, Base64 — safe to log and to send; it is public by definition. */
    val publicKeyBase64: String = Base64.getEncoder().encodeToString(publicKey.encoded)

    /** True when this instance can sign; false for a [verifierOnly] instance. */
    val hasPrivateKey: Boolean get() = privateKey != null

    override fun sign(canonical: String): String? {
        val key = privateKey ?: return null
        return try {
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initSign(key)
            signature.update(canonical.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(signature.sign())
        } catch (failure: GeneralSecurityException) {
            // An unavailable provider must not crash the capture flow; an unsigned record
            // is visible (null signature + null signerKeyId) and fails verification loudly.
            null
        }
    }

    override fun verify(canonical: String, signatureBase64: String): Boolean {
        return try {
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initVerify(publicKey)
            signature.update(canonical.toByteArray(Charsets.UTF_8))
            signature.verify(Base64.getDecoder().decode(signatureBase64))
        } catch (failure: GeneralSecurityException) {
            false
        } catch (failure: IllegalArgumentException) {
            false
        }
    }

    /** Export both key halves so a dev/emulator install keeps one identity across restarts. */
    fun exportKeyMaterial(): SoftwareKeyMaterial? {
        val key = privateKey ?: return null
        return SoftwareKeyMaterial(
            privateKeyPkcs8Base64 = Base64.getEncoder().encodeToString(key.encoded),
            publicKeyX509Base64 = publicKeyBase64,
        )
    }

    companion object {
        const val CURVE: String = "secp256r1"
        const val SIGNATURE_ALGORITHM: String = "SHA256withECDSA"
        private const val KEY_ALGORITHM: String = "EC"

        /** Generates a P-256 signer. [keyIdHint] is an alias only; [keyId] stays the fingerprint. */
        fun generate(keyIdHint: String? = null): SoftwareEcdsaSigner {
            val pair = generateKeyPair()
            return SoftwareEcdsaSigner(pair.private, pair.public, keyIdHint)
        }

        fun fromKeyPair(keyPair: KeyPair): SoftwareEcdsaSigner =
            SoftwareEcdsaSigner(keyPair.private, keyPair.public, null)

        /** Rebuilds a signer from exported material (both halves are required by JCA). */
        fun fromKeyMaterial(material: SoftwareKeyMaterial): SoftwareEcdsaSigner {
            val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
            val privateKey = keyFactory.generatePrivate(
                PKCS8EncodedKeySpec(Base64.getDecoder().decode(material.privateKeyPkcs8Base64)),
            )
            val publicKey = keyFactory.generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(material.publicKeyX509Base64)),
            )
            return SoftwareEcdsaSigner(privateKey, publicKey, null)
        }

        /** A verify-only instance: walks and checks chains but cannot sign. */
        fun verifierOnly(publicKeyBase64: String): SoftwareEcdsaSigner {
            val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
            val publicKey = keyFactory.generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)),
            )
            return SoftwareEcdsaSigner(null, publicKey, null)
        }

        /** Lowercase SHA-256 hex of the encoded public key. Deterministic across devices. */
        fun fingerprint(publicKey: PublicKey): String = Sha256.sha256Hex(publicKey.encoded)

        private fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
            generator.initialize(ECGenParameterSpec(CURVE), SecureRandom())
            return generator.generateKeyPair()
        }
    }
}
