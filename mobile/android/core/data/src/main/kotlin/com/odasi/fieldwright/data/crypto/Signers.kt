package com.odasi.fieldwright.data.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.odasi.fieldwright.crypto.Sha256
import com.odasi.fieldwright.crypto.SoftwareEcdsaSigner
import com.odasi.fieldwright.crypto.SoftwareKeyMaterial
import com.odasi.fieldwright.data.prefs.FieldwrightPreferences
import com.odasi.fieldwright.model.SignatureScheme
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Hardware-backed signer for real devices. The ECDSA P-256 private key is generated in the
 * Android Keystore and never leaves it: signing happens inside the TEE/StrongBox boundary
 * and the app only ever sees a signature. The key id follows the same rule as the software
 * signer (SHA-256 of the X.509 public key encoding) so a packet signed by either backend
 * names its signer consistently in the chain.
 *
 * Evidence signing happens while the operator is on site, so the key must not require a
 * biometric prompt per record; `setUserAuthenticationRequired(false)` is a deliberate,
 * documented trade-off (device unlock is still required to run the app's capture UI).
 *
 * This class is intentionally Android-only and lives in `core:data`; `core:crypto` remains
 * pure JDK (CONTRACTS §2 dependency rule).
 */
class AndroidKeystoreSigner private constructor(
    private val keyStore: KeyStore,
    private val alias: String,
    private val publicKey: PublicKey,
) : SignatureScheme {

    override val keyId: String = SoftwareEcdsaSigner.fingerprint(publicKey)

    private val privateKey: PrivateKey
        get() = keyStore.getKey(alias, null) as PrivateKey

    override fun sign(canonical: String): String? = runCatching {
        val signature = Signature.getInstance(SIGN_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(canonical.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(signature.sign())
    }.getOrNull()

    override fun verify(canonical: String, signatureBase64: String): Boolean = runCatching {
        val signature = Signature.getInstance(SIGN_ALGORITHM)
        signature.initVerify(publicKey)
        signature.update(canonical.toByteArray(Charsets.UTF_8))
        signature.verify(Base64.getDecoder().decode(signatureBase64))
    }.getOrDefault(false)

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val SIGN_ALGORITHM = "SHA256withECDSA"
        const val DEFAULT_ALIAS: String = "fieldwright.packet.signer"

        /** Loads or creates the device signing key. Safe across concurrent process starts. */
        fun create(alias: String = DEFAULT_ALIAS): AndroidKeystoreSigner {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val publicKey = synchronized(AndroidKeystoreSigner::class.java) {
                if (!keyStore.containsAlias(alias)) {
                    val generator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC,
                        KEYSTORE_PROVIDER,
                    )
                    val spec = KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build()
                    generator.initialize(spec)
                    generator.generateKeyPair()
                }
                keyStore.getCertificate(alias).publicKey
            }
            return AndroidKeystoreSigner(keyStore, alias, publicKey)
        }
    }
}

/**
 * Chooses the signing backend once per process:
 *
 * - emulator / dev: [SoftwareEcdsaSigner] with its key material persisted in DataStore, so
 *   packet signatures and the key id survive a process restart;
 * - real device: [AndroidKeystoreSigner], hardware-backed.
 *
 * A keystore failure degrades to the software signer rather than blocking capture — a
 * signing outage must not cost a worker a job, and every chain record names the key that
 * actually signed it.
 */
object Signers {

    fun create(context: Context): SignatureScheme {
        if (!isEmulator()) {
            runCatching { return AndroidKeystoreSigner.create() }
        }
        return softwareSigner(context)
    }

    /** The emulator/dev signing identity, persisted so `keyId` is stable across launches. */
    internal fun softwareSigner(context: Context): SoftwareEcdsaSigner {
        val preferences = FieldwrightPreferences(context)
        val privateKey = runBlocking { preferences.signerPrivateKeyPkcs8Base64() }
        val publicKey = runBlocking { preferences.signerPublicKeyX509Base64() }
        if (privateKey != null && publicKey != null) {
            runCatching {
                return SoftwareEcdsaSigner.fromKeyMaterial(
                    SoftwareKeyMaterial(
                        privateKeyPkcs8Base64 = privateKey,
                        publicKeyX509Base64 = publicKey,
                    ),
                )
            }
        }
        val fresh = SoftwareEcdsaSigner.generate(keyIdHint = "emulator-software-signer")
        runBlocking {
            fresh.exportKeyMaterial()?.let { material ->
                preferences.saveSignerKeyMaterial(
                    privateKeyPkcs8Base64 = material.privateKeyPkcs8Base64,
                    publicKeyX509Base64 = material.publicKeyX509Base64,
                )
            }
        }
        return fresh
    }

    internal fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.PRODUCT.contains("sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for")
}
