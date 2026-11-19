package com.odasi.fieldwright.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * The Room single source of truth (CONTRACTS §8). Version 1 is the sprint-1 schema; the
 * database is created and seeded by [com.odasi.fieldwright.data.FieldwrightContainer.bootstrap].
 *
 * **Encryption at rest:** pass [build] a `SupportSQLiteOpenHelper.Factory` from a SQLCipher
 * (or equivalent) provider and the database will use it. `core:data` deliberately does not
 * depend on SQLCipher in this sprint — the hook exists so encryption can be enabled by the
 * app composition root without touching the schema or repositories. See
 * docs/04-security-privacy-consent.md for the key-management roadmap (Keystore-wrapped
 * passphrase, rotation, backup exclusion).
 */
@Database(
    entities = [
        JobEntity::class,
        OfferEntity::class,
        PacketEntity::class,
        ChainRecordEntity::class,
        BadgeEntity::class,
        MediaEntity::class,
        MeasurementEntity::class,
        ChecklistRowEntity::class,
        LedgerEntity::class,
        PayoutEntity::class,
        OperatorEntity::class,
        AvailabilityEntity::class,
        MarketEntity::class,
        OutboxEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(FieldwrightConverters::class)
abstract class FieldwrightDatabase : RoomDatabase() {

    abstract fun jobDao(): JobDao
    abstract fun offerDao(): OfferDao
    abstract fun packetDao(): PacketDao
    abstract fun captureDao(): CaptureDao
    abstract fun moneyDao(): MoneyDao
    abstract fun operatorDao(): OperatorDao
    abstract fun marketDao(): MarketDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val DB_NAME: String = "fieldwright.db"

        /**
         * Builds the production database.
         *
         * @param encryptionFactory optional SQLCipher-compatible helper factory. When null the
         * database is stored as a plain SQLite file (the sprint-1 demo build; the demo log is
         * flagged and never anchored, so there is no live evidence at rest).
         */
        fun build(
            context: Context,
            encryptionFactory: SupportSQLiteOpenHelper.Factory? = null,
        ): FieldwrightDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                FieldwrightDatabase::class.java,
                DB_NAME,
            ).fallbackToDestructiveMigration()
            if (encryptionFactory != null) {
                builder.openHelperFactory(encryptionFactory)
            }
            return builder.build()
        }

        /** In-memory database for instrumentation/Robolectric tests only. */
        fun inMemory(context: Context): FieldwrightDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, FieldwrightDatabase::class.java)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build()
    }
}
