package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class Migration_49_50_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate49To50_addsSpaceTablesAndPreservesExistingRows() {
        val old = helper.createDatabase(DB_NAME, 49)
        val legacy = """{"id":"w1","name":"Legacy","enabled":true,"trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{"text":"hi"}}],"authoring_assistant_id":"assistant-1"}"""
        old.execSQL(
            "INSERT INTO workflows(id,name,enabled,definitionJson,createdAtMs,updatedAtMs) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("w1", "Legacy", 1, legacy, 10L, 11L),
        )
        old.close()

        // `validate = true` is the strong half of this test: Room compares the migrated schema
        // against the live @Entity definitions, so the hand-written DDL in MIGRATION_49_50 —
        // including every auto-named index — must match exactly or this fails here.
        val db = helper.runMigrationsAndValidate(DB_NAME, 50, true, MIGRATION_49_50)

        SPACE_TABLES.forEach { table ->
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { cursor ->
                assertTrue("migration did not create $table", cursor.moveToFirst())
            }
        }

        db.query("SELECT name FROM workflows WHERE id = 'w1'").use { cursor ->
            assertTrue("v49 workflow row did not survive the upgrade", cursor.moveToFirst())
            assertEquals("Legacy", cursor.getString(0))
        }

        db.close()
    }

    @Test
    fun migrate49To50_createsEveryAutoNamedSpaceIndex() {
        val old = helper.createDatabase(DB_NAME, 49)
        old.close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 50, true, MIGRATION_49_50)
        SPACE_INDEXES.forEach { index ->
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf(index),
            ).use { cursor ->
                assertTrue("migration did not create index $index", cursor.moveToFirst())
            }
        }
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-49-50-test"

        val SPACE_TABLES = listOf(
            "space_posts",
            "space_likes",
            "space_comments",
            "space_notifications",
        )

        /** Room's auto-generated names; a rename here must be matched in SpaceEntities.kt. */
        val SPACE_INDEXES = listOf(
            "index_space_posts_created_at_ms",
            "index_space_posts_author_kind_author_id_created_at_ms",
            "index_space_likes_post_id",
            "index_space_likes_actor_kind_actor_id_created_at_ms",
            "index_space_comments_post_id_created_at_ms",
            "index_space_comments_author_kind_author_id_created_at_ms",
            "index_space_notifications_recipient_kind_recipient_id_created_at_ms",
            "index_space_notifications_recipient_kind_recipient_id_read_at_ms",
            "index_space_notifications_post_id",
        )
    }
}
