package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.ImportedDatabaseReconciler
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class Migration_50_51_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate50To51_addsStickerTableAndPreservesExistingRows() {
        val old = helper.createDatabase(DB_NAME, 50)
        val legacy = """{"id":"w1","name":"Legacy","enabled":true,"trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{"text":"hi"}}],"authoring_assistant_id":"assistant-1"}"""
        old.execSQL(
            "INSERT INTO workflows(id,name,enabled,definitionJson,createdAtMs,updatedAtMs) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("w1", "Legacy", 1, legacy, 10L, 11L),
        )
        // A Cat Garden row specifically, because the point of the additive migration is that the
        // feature this fork most recently added keeps its data too.
        old.execSQL(
            "INSERT INTO space_posts(post_id,author_kind,author_id,content,created_at_ms,origin_depth) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("p1", "USER", "user-1", "hello garden", 20L, 0),
        )
        old.close()

        // `validate = true` is the strong half of this test: Room compares the migrated schema
        // against the live @Entity definitions, so the hand-written DDL in MIGRATION_50_51 —
        // including every auto-named index — must match exactly or this fails here.
        val db = helper.runMigrationsAndValidate(DB_NAME, 51, true, MIGRATION_50_51)

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'stickers'",
        ).use { cursor ->
            assertTrue("migration did not create the stickers table", cursor.moveToFirst())
        }

        db.query("SELECT name FROM workflows WHERE id = 'w1'").use { cursor ->
            assertTrue("v50 workflow row did not survive the upgrade", cursor.moveToFirst())
            assertEquals("Legacy", cursor.getString(0))
        }

        db.query("SELECT content FROM space_posts WHERE post_id = 'p1'").use { cursor ->
            assertTrue("v50 Cat Garden post did not survive the upgrade", cursor.moveToFirst())
            assertEquals("hello garden", cursor.getString(0))
        }

        db.close()
    }

    @Test
    fun migrate50To51_createsEveryAutoNamedStickerIndex() {
        val old = helper.createDatabase(DB_NAME, 50)
        old.close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 51, true, MIGRATION_50_51)
        STICKER_INDEXES.forEach { index ->
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf(index),
            ).use { cursor ->
                assertTrue("migration did not create index $index", cursor.moveToFirst())
            }
        }
        db.close()
    }

    /**
     * The two UNIQUE indices are the storage contract, not tuning — one keeps the same picture from
     * being stored twice and the other keeps the orphan sweep from having to choose between two
     * rows naming one file. A migration that created them non-unique would pass a name-only check,
     * so uniqueness is asserted directly.
     */
    @Test
    fun migrate50To51_createsTheUniqueIndicesAsUnique() {
        val old = helper.createDatabase(DB_NAME, 50)
        old.close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 51, true, MIGRATION_50_51)
        UNIQUE_STICKER_INDEXES.forEach { index ->
            db.query(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf(index),
            ).use { cursor ->
                assertTrue("index $index is missing", cursor.moveToFirst())
                val sql = cursor.getString(0) ?: ""
                assertTrue("index $index is not UNIQUE: $sql", sql.contains("UNIQUE"))
            }
        }
        db.close()
    }

    @Test
    fun migrate50To51_rejectsASecondRowWithTheSameChecksum() {
        val old = helper.createDatabase(DB_NAME, 50)
        old.close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 51, true, MIGRATION_50_51)
        db.execSQL(
            "INSERT INTO stickers(sticker_id,relative_path,mime_type,width,height,description," +
                "tags_json,enabled,created_at_ms,updated_at_ms,checksum,vision_state," +
                "vision_error_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                "s1", "stickers/s1.png", "image/png", 64, 48, "", "[]", 1, 1L, 1L,
                "deadbeef", "NONE", null,
            ),
        )

        val duplicate = runCatching {
            db.execSQL(
                "INSERT INTO stickers(sticker_id,relative_path,mime_type,width,height,description," +
                    "tags_json,enabled,created_at_ms,updated_at_ms,checksum,vision_state," +
                    "vision_error_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    "s2", "stickers/s2.png", "image/png", 64, 48, "", "[]", 1, 2L, 2L,
                    "deadbeef", "NONE", null,
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            "the unique checksum index is what makes one copy the invariant",
            duplicate != null,
        )
        db.close()
    }

    /**
     * The reconciler's pinned identity is a hand-copied constant and nothing else in the build
     * connects it to the schema. A mismatch is silent at runtime — cold restore and backup import
     * simply start refusing databases — which is the kind of failure discovered long after the
     * commit that caused it. So it is asserted here, against the identity the v51 schema actually
     * carries.
     *
     * This duplicates the JVM contract test on purpose. That one compares the constant against the
     * export on the host, where this repo's Gradle configuration prints a failing test's name but
     * not its message, so a mismatch there is visible only as "some assertion failed". Here the
     * instrumentation reporter prints the message, which is what makes the value recoverable.
     */
    @Test
    fun migrate50To51_pinnedReconcilerIdentityMatchesTheExportedSchema() {
        val db = helper.createDatabase(DB_NAME, 51)
        val actual = db.query(
            "SELECT identity_hash FROM room_master_table WHERE id = 42",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        db.close()

        assertEquals(
            "ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH does not match AppDatabase/51.json " +
                "— cold restore and backup import would fail closed",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
            actual,
        )
    }

    private companion object {
        const val DB_NAME = "migration-50-51-test"

        /** Room's auto-generated names; a rename here must be matched in StickerEntity.kt. */
        val STICKER_INDEXES = listOf(
            "index_stickers_checksum",
            "index_stickers_relative_path",
            "index_stickers_created_at_ms",
        )

        val UNIQUE_STICKER_INDEXES = listOf(
            "index_stickers_checksum",
            "index_stickers_relative_path",
        )
    }
}
