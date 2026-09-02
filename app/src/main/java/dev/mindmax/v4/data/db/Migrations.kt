package dev.mindmax.v4.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Central registry for future Room migrations. Schema is currently at version 1.
 * When you add a non-destructive migration, bump `MindMaxDatabase.version` and
 * append the migration here, then wire it into `ServiceLocator.init` via
 * `.addMigrations(Migrations.ALL)`.
 */
object Migrations {

    /**
     * Reserved for the first non-trivial schema change. Left as a no-op example
     * so CI can verify the surface compiles. Delete or replace when bumping.
     */
    val M_1_2_NOOP: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No-op until there is a real schema change to ship.
        }
    }

    @Suppress("unused")
    val ALL: Array<Migration> = arrayOf()
}
