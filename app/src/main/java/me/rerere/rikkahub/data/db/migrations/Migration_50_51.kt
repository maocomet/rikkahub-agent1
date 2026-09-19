package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v51 adds the shared sticker library table. Purely additive: no existing table, column or index
 * is touched, so an upgraded install keeps every chat, Cat Garden post, memory and message row.
 *
 * The index names below are Room's auto-generated `index_<table>_<columns>` names, not hand-picked
 * ones. A migration that creates the right index under a different name fails Room's schema
 * validation at open time, so these strings must stay in lockstep with
 * `sticker/StickerEntity.kt`.
 *
 * The two UNIQUE indices are load-bearing rather than tuning: `checksum` is what makes
 * "the same picture is stored once" an invariant of the schema instead of a check a race can slip
 * past, and `relative_path` is what keeps the orphan sweep from ever having to choose between two
 * rows that name the same file.
 */

internal const val STICKER_V51_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `stickers` (" +
        "`sticker_id` TEXT NOT NULL, " +
        "`relative_path` TEXT NOT NULL, " +
        "`mime_type` TEXT NOT NULL, " +
        "`width` INTEGER NOT NULL, " +
        "`height` INTEGER NOT NULL, " +
        "`description` TEXT NOT NULL, " +
        "`tags_json` TEXT NOT NULL, " +
        "`enabled` INTEGER NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "`checksum` TEXT NOT NULL, " +
        "`vision_state` TEXT NOT NULL, " +
        "`vision_error_code` TEXT, " +
        "PRIMARY KEY(`sticker_id`))"

internal val STICKER_V51_INDEX_SQL = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stickers_checksum` " +
        "ON `stickers` (`checksum`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stickers_relative_path` " +
        "ON `stickers` (`relative_path`)",
    "CREATE INDEX IF NOT EXISTS `index_stickers_created_at_ms` " +
        "ON `stickers` (`created_at_ms`)",
)

/** Additive sticker-library schema. Existing rows in every prior table are untouched. */
val MIGRATION_50_51 = object : Migration(50, 51) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(STICKER_V51_TABLE_SQL)
        STICKER_V51_INDEX_SQL.forEach(db::execSQL)
    }
}
