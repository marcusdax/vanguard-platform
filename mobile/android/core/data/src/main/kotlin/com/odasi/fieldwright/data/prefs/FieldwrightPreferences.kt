package com.odasi.fieldwright.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.odasi.fieldwright.data.db.jobTypesFromCsv
import com.odasi.fieldwright.data.db.toJobTypeCsv
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.AutoAcceptPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Process-wide DataStore. One instance per file is mandatory: DataStore throws if two
 * instances open the same file, so every consumer (preferences, signer key persistence)
 * goes through this single delegate.
 */
internal val Context.fieldwrightDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fieldwright.preferences",
)

/**
 * Device-local preferences that intentionally do **not** live in the server-owned tables:
 * availability (an operator toggling "I am working" is a device setting), the demo seed
 * marker, and the emulator/dev signing key. Room keeps a snapshot of availability so the
 * pure engines can read it synchronously, but the preference is the durable owner —
 * which also means it survives a sample-book reset.
 */
class FieldwrightPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val store: DataStore<Preferences>
        get() = appContext.fieldwrightDataStore

    private val safeData: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val demoSeeded: Flow<Boolean> = safeData.map { it[KEY_DEMO_SEEDED] ?: false }
    val seedVersion: Flow<Int> = safeData.map { it[KEY_SEED_VERSION] ?: 0 }

    suspend fun isDemoSeeded(): Boolean = demoSeeded.first()

    suspend fun markDemoSeeded(version: Int) {
        store.edit {
            it[KEY_DEMO_SEEDED] = true
            it[KEY_SEED_VERSION] = version
        }
    }

    /** Clears only the seed marker; the demo rows are wiped by the seeder itself. */
    suspend fun clearSeedMarker() {
        store.edit {
            it[KEY_DEMO_SEEDED] = false
            it[KEY_SEED_VERSION] = 0
        }
    }

    // ------------------------------------------------------------------ availability

    /** Emits the saved availability or null when the operator has never toggled it. */
    val availability: Flow<Availability?> = safeData.map { preferences ->
        if (preferences[KEY_AVAILABILITY_SAVED] != true) return@map null
        Availability(
            isOnline = preferences[KEY_ONLINE] ?: false,
            updatedAtEpochMs = preferences[KEY_AVAILABILITY_UPDATED_AT] ?: 0L,
            radiusMeters = preferences[KEY_RADIUS] ?: Availability().radiusMeters,
            areaLabel = preferences[KEY_AREA_LABEL] ?: Availability().areaLabel,
            autoAccept = AutoAcceptPolicy(
                enabled = preferences[KEY_AUTO_ENABLED] ?: true,
                minPayoutCents = preferences[KEY_AUTO_MIN_PAYOUT] ?: 3_000,
                maxDistanceMeters = preferences[KEY_AUTO_MAX_DISTANCE] ?: 12_000,
                maxActiveStack = preferences[KEY_AUTO_MAX_STACK] ?: 2,
                allowedTypes = jobTypesFromCsv(preferences[KEY_AUTO_ALLOWED_TYPES] ?: ""),
                requireCredentialMatch = preferences[KEY_AUTO_REQUIRE_CREDENTIAL] ?: true,
            ),
        )
    }

    suspend fun saveAvailability(availability: Availability) {
        store.edit { preferences ->
            preferences[KEY_AVAILABILITY_SAVED] = true
            preferences[KEY_ONLINE] = availability.isOnline
            preferences[KEY_AVAILABILITY_UPDATED_AT] = availability.updatedAtEpochMs
            preferences[KEY_RADIUS] = availability.radiusMeters
            preferences[KEY_AREA_LABEL] = availability.areaLabel
            preferences[KEY_AUTO_ENABLED] = availability.autoAccept.enabled
            preferences[KEY_AUTO_MIN_PAYOUT] = availability.autoAccept.minPayoutCents
            preferences[KEY_AUTO_MAX_DISTANCE] = availability.autoAccept.maxDistanceMeters
            preferences[KEY_AUTO_MAX_STACK] = availability.autoAccept.maxActiveStack
            preferences[KEY_AUTO_ALLOWED_TYPES] = availability.autoAccept.allowedTypes.toJobTypeCsv()
            preferences[KEY_AUTO_REQUIRE_CREDENTIAL] = availability.autoAccept.requireCredentialMatch
        }
    }

    // ------------------------------------------------------------------ dev signer key

    /** Persisted X.509 public half of the dev signing key (null when never generated). */
    suspend fun signerPublicKeyX509Base64(): String? = safeData.first()[KEY_SIGNER_PUBLIC_X509]

    /** Persisted PKCS#8 private half of the dev signing key (null when never generated). */
    suspend fun signerPrivateKeyPkcs8Base64(): String? = safeData.first()[KEY_SIGNER_PKCS8]

    suspend fun saveSignerKeyMaterial(privateKeyPkcs8Base64: String, publicKeyX509Base64: String) {
        store.edit {
            it[KEY_SIGNER_PKCS8] = privateKeyPkcs8Base64
            it[KEY_SIGNER_PUBLIC_X509] = publicKeyX509Base64
        }
    }

    // ------------------------------------------------------------------ keys

    private companion object {
        val KEY_DEMO_SEEDED = booleanPreferencesKey("demo.seeded")
        val KEY_SEED_VERSION = intPreferencesKey("demo.seed.version")

        val KEY_AVAILABILITY_SAVED = booleanPreferencesKey("availability.saved")
        val KEY_ONLINE = booleanPreferencesKey("availability.online")
        val KEY_AVAILABILITY_UPDATED_AT = longPreferencesKey("availability.updatedAt")
        val KEY_RADIUS = intPreferencesKey("availability.radiusMeters")
        val KEY_AREA_LABEL = stringPreferencesKey("availability.areaLabel")
        val KEY_AUTO_ENABLED = booleanPreferencesKey("availability.auto.enabled")
        val KEY_AUTO_MIN_PAYOUT = intPreferencesKey("availability.auto.minPayoutCents")
        val KEY_AUTO_MAX_DISTANCE = intPreferencesKey("availability.auto.maxDistanceMeters")
        val KEY_AUTO_MAX_STACK = intPreferencesKey("availability.auto.maxActiveStack")
        val KEY_AUTO_ALLOWED_TYPES = stringPreferencesKey("availability.auto.allowedTypes")
        val KEY_AUTO_REQUIRE_CREDENTIAL = booleanPreferencesKey("availability.auto.requireCredentialMatch")

        val KEY_SIGNER_PKCS8 = stringPreferencesKey("signer.pkcs8.base64")
        val KEY_SIGNER_PUBLIC_X509 = stringPreferencesKey("signer.public.x509.base64")
    }
}

/** The availability a brand-new install starts from, before the operator touches anything. */
fun defaultAvailability(nowEpochMs: Long = 0L): Availability = Availability(
    isOnline = false,
    updatedAtEpochMs = nowEpochMs,
    radiusMeters = 15_000,
    autoAccept = AutoAcceptPolicy(),
    areaLabel = "Creekside · MUD 14",
)
