package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.ImportedDatabaseReconciler
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import me.rerere.rikkahub.data.execution.ApprovalContinuationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v52 adds `pending_tool_approvals.continuation_mode`. Emulator/disposable-device only.
 *
 * The interesting half is the **default**, not the column. A v51 row belongs to the behaviour that
 * existed at v51 — a generation that had already ended — so an upgrade must read it back as
 * `RESUME_COMMAND` and not as anything that resembles "unknown". Getting that wrong would make
 * every live approval on an upgraded device take the in-flight path, which creates no resume
 * command and would strand the call.
 *
 * There is deliberately **no** `CHECK`-constraint test here, because there is no `CHECK`
 * constraint: Room cannot declare one, so the column's vocabulary is closed in Kotlin by
 * `ApprovalContinuationMode` and validated on the import/restore path by
 * `ImportedDatabaseReconciler`. `ApprovalContinuationModeTest` is where the narrowing rules are
 * asserted. This suite asserts what the database actually does, and it does not reject a third
 * value written by raw SQL.
 */
@RunWith(AndroidJUnit4::class)
class Migration_51_52_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate51To52_addsContinuationModeAndPreservesExistingRows() {
        val old = helper.createDatabase(DB_NAME, 51)
        // A live approval, written the only way v51 could write one: no continuation_mode column,
        // and therefore no way for this row to say anything about how it continues.
        old.execSQL(
            "INSERT INTO pending_tool_approvals(approval_id,execution_id,trace_id,tool_call_id," +
                "conversation_id,subject_id,subject_type,origin,capability_key,resource_category," +
                "requested_at_ms,status,state_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                "approval-1", "execution-1", "run-1", "call-1",
                "conversation-1", "subject-1", "LOCAL_SECOND_USER", "SystemAssistant",
                "tool.write", "WORKSPACE", 100L, "PENDING", 1L,
            ),
        )
        // A resolved row too, because the migration must not care about status, and a default that
        // only reached PENDING rows would be a silent rewrite of the resolved history.
        old.execSQL(
            "INSERT INTO pending_tool_approvals(approval_id,execution_id,trace_id,tool_call_id," +
                "conversation_id,subject_id,subject_type,origin,capability_key,resource_category," +
                "requested_at_ms,status,state_version,resolved_at_ms,resolution_reason) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                "approval-2", "execution-2", "run-2", "call-2",
                "conversation-1", "subject-1", "LOCAL_SECOND_USER", "SystemAssistant",
                "tool.write", "WORKSPACE", 90L, "DENIED", 2L, 95L, "approval_denied",
            ),
        )
        // A neighbouring table, because "purely additive" has to hold for the data that was there.
        old.execSQL(
            "INSERT INTO stickers(sticker_id,relative_path,mime_type,width,height,description," +
                "tags_json,enabled,created_at_ms,updated_at_ms,checksum,vision_state) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                "s1", "stickers/a.png", "image/png", 1, 1, "a", "[]", 1, 1L, 1L, "sum-1", "DONE",
            ),
        )
        old.close()

        // `validate = true`: Room compares the migrated schema against the live @Entity
        // definitions, so the column's name, affinity, NOT NULL and default must all match.
        val db = helper.runMigrationsAndValidate(DB_NAME, 52, true, MIGRATION_51_52)

        db.query(
            "SELECT continuation_mode FROM pending_tool_approvals WHERE approval_id = 'approval-1'",
        ).use { cursor ->
            assertTrue("the v51 pending row did not survive the upgrade", cursor.moveToFirst())
            assertEquals(
                "a v51 approval must read back as RESUME_COMMAND, which is what it meant",
                ApprovalContinuationMode.RESUME_COMMAND.name,
                cursor.getString(0),
            )
        }
        db.query(
            "SELECT continuation_mode FROM pending_tool_approvals WHERE approval_id = 'approval-2'",
        ).use { cursor ->
            assertTrue("the v51 resolved row did not survive the upgrade", cursor.moveToFirst())
            assertEquals(ApprovalContinuationMode.RESUME_COMMAND.name, cursor.getString(0))
        }
        db.query(
            "SELECT COUNT(*) FROM pending_tool_approvals WHERE status = 'PENDING' " +
                "AND state_version = 1 AND resolution_reason IS NULL",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the migration changed columns it does not own", 1, cursor.getInt(0))
        }
        db.query("SELECT checksum FROM stickers WHERE sticker_id = 's1'").use { cursor ->
            assertTrue("the additive migration disturbed a neighbouring table", cursor.moveToFirst())
            assertEquals("sum-1", cursor.getString(0))
        }
        db.close()
    }

    @Test
    fun migrate51To52_columnShapeAgreesBetweenFreshAndUpgradedInstalls() {
        helper.createDatabase(DB_NAME_UPGRADED, 51).close()
        val upgraded = helper.runMigrationsAndValidate(DB_NAME_UPGRADED, 52, true, MIGRATION_51_52)
        val fresh = helper.createDatabase(DB_NAME_DEFAULT, 52)

        // A fresh v52 database is created from the exported schema and an upgraded one from this
        // migration. If the two disagree about the column's shape, then which install a user has
        // decides what the column means — which is the failure mode a hand-written `CHECK` would
        // also have produced, since Room's generated DDL would not have carried it.
        assertEquals(
            "a fresh v52 database and an upgraded one disagree on continuation_mode",
            columnShape(fresh),
            columnShape(upgraded),
        )

        val shape = assertNotNull(columnShape(fresh))
        assertEquals("continuation_mode must be NOT NULL", 1, shape.first)
        assertEquals(
            "continuation_mode must default to the pre-v52 meaning",
            "'RESUME_COMMAND'",
            shape.second,
        )
        fresh.close()
        upgraded.close()
    }

    @Test
    fun migrate51To52_bothVocabularyValuesRoundTrip() {
        helper.createDatabase(DB_NAME_ROUND_TRIP, 51).close()
        val db = helper.runMigrationsAndValidate(DB_NAME_ROUND_TRIP, 52, true, MIGRATION_51_52)

        // Both values the app can mean must survive a write and a read byte for byte. The database
        // does not police a third value — see the class doc — so what is proven here is that it
        // stores and returns the two legal ones exactly.
        ApprovalContinuationMode.entries.forEachIndexed { index, mode ->
            db.execSQL(
                "INSERT INTO pending_tool_approvals(approval_id,execution_id,tool_call_id," +
                    "conversation_id,subject_id,subject_type,origin,capability_key," +
                    "resource_category,requested_at_ms,continuation_mode) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    "approval-$index", "execution-$index", "call-$index",
                    "conversation-1", "subject-1", "LOCAL_SECOND_USER", "SystemAssistant",
                    "tool.write", "WORKSPACE", 100L + index, mode.toWire(),
                ),
            )
        }

        ApprovalContinuationMode.entries.forEachIndexed { index, mode ->
            db.query(
                "SELECT continuation_mode FROM pending_tool_approvals WHERE approval_id = ?",
                arrayOf<Any?>("approval-$index"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "${mode.name} did not round-trip through the database",
                    mode.name,
                    cursor.getString(0),
                )
                assertEquals(
                    "${mode.name} did not narrow back to itself",
                    mode,
                    ApprovalContinuationMode.fromWire(cursor.getString(0)),
                )
            }
        }
        db.close()
    }

    /**
     * The reconciler's pinned identity is a hand-copied constant that nothing else in the build
     * connects to the schema, and a mismatch is silent at runtime: cold restore and backup import
     * simply start refusing databases.
     *
     * During the v52 schema bootstrap both sides of this assertion are the sentinel, so it passes
     * while the JVM contract test — which compares the constant against the identity the compiler
     * wrote — fails. That asymmetry is the point: it says the constant has not yet been pinned to
     * a real value without also claiming the database is wrong.
     */
    @Test
    fun migrate51To52_pinnedReconcilerIdentityMatchesTheExportedSchema() {
        val db = helper.createDatabase(DB_NAME_IDENTITY, 52)
        val actual = db.query(
            "SELECT identity_hash FROM room_master_table WHERE id = 42",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        db.close()

        assertEquals(
            "ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH does not match AppDatabase/52.json " +
                "— cold restore and backup import would fail closed",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
            actual,
        )
    }

    /** `notnull` and `dflt_value` for `continuation_mode`, or `null` if the column is absent. */
    private fun columnShape(db: SupportSQLiteDatabase): Pair<Int, String?>? =
        db.query("PRAGMA table_info(`pending_tool_approvals`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            var found: Pair<Int, String?>? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "continuation_mode") {
                    found = cursor.getInt(notNullIndex) to cursor.getString(defaultIndex)
                }
            }
            found
        }

    private companion object {
        const val DB_NAME = "migration-51-52-test"
        const val DB_NAME_DEFAULT = "migration-51-52-default-test"
        const val DB_NAME_UPGRADED = "migration-51-52-upgraded-test"
        const val DB_NAME_ROUND_TRIP = "migration-51-52-round-trip-test"
        const val DB_NAME_IDENTITY = "migration-51-52-identity-test"
    }
}
