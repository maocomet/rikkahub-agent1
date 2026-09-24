package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v52 adds `pending_tool_approvals.continuation_mode`. Purely additive: one column with a database
 * default, so every pre-existing row and every pre-existing index is untouched.
 *
 * The default is the load-bearing part of this migration. Every row written before v52 belongs to
 * the behaviour that existed before v52 — a generation that had already ended, where approval
 * creates a deterministic resume command. So `'RESUME_COMMAND'` is not a convenient placeholder
 * standing in for "unknown": it is what those rows *mean*, and backfilling them to anything else
 * would silently reclassify live approvals.
 *
 * There is deliberately no `CHECK` here. Room cannot declare one, so a hand-written `CHECK` would
 * make the migrated table differ from the table Room creates on a fresh install and from the
 * schema KSP exports for v52. The vocabulary is closed in Kotlin instead; see
 * `ApprovalContinuationMode` for what that does and does not guarantee.
 */

internal const val APPROVAL_V52_CONTINUATION_MODE_COLUMN_SQL =
    "ALTER TABLE `pending_tool_approvals` ADD COLUMN " +
        "`continuation_mode` TEXT NOT NULL DEFAULT 'RESUME_COMMAND'"

internal val APPROVAL_V52_CONTINUATION_MODE_COLUMNS = listOf(
    "continuation_mode" to "TEXT NOT NULL DEFAULT 'RESUME_COMMAND'",
)

/** Additive approval-ledger column. Every v51 row becomes `RESUME_COMMAND` by the default. */
val MIGRATION_51_52 = object : Migration(51, 52) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(APPROVAL_V52_CONTINUATION_MODE_COLUMN_SQL)
    }
}
