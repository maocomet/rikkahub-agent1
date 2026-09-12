package me.rerere.rikkahub.data.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the reconciler's pinned identity and the checked-in Room export in lockstep.
 *
 * `EXPECTED_IDENTITY_HASH` is a hard-coded copy of the identity Room writes into
 * `schemas/.../AppDatabase/<version>.json`. Nothing else connects the two, and a mismatch is
 * silent at runtime: cold restore and backup import simply start refusing databases, which is
 * exactly the kind of failure that is discovered long after the commit that caused it.
 *
 * `:app:kspDebugKotlin` regenerates that export during this build, so at test time the file holds
 * Room's own value rather than whatever was committed. That makes this test a real cross-check of
 * the pinned constant against the compiler, not a restatement of it.
 */
class AppDatabaseSchemaIdentityContractTest {

    @Test
    fun `pinned reconciler identity matches the exported schema for the pinned version`() {
        val version = ImportedDatabaseReconciler.EXPECTED_VERSION
        val database = readDatabase(version)

        assertEquals(
            "AppDatabase/$version.json describes a different version than the reconciler pins",
            version,
            database.getValue("version").jsonPrimitive.content.toInt(),
        )
        assertEquals(
            "ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH no longer matches " +
                "AppDatabase/$version.json — cold restore and backup import would fail closed",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
            database.getValue("identityHash").jsonPrimitive.content,
        )
    }

    @Test
    fun `every pinned predecessor identity still exists as a real export`() {
        // The reconciler accepts these older identities verbatim, so each must still correspond
        // to a checked-in export. A typo here would silently strand every backup at that version.
        val pinned = mapOf(
            49 to ImportedDatabaseReconciler.FINAL_V49_IDENTITY_HASH,
            48 to ImportedDatabaseReconciler.FINAL_V48_IDENTITY_HASH,
            47 to ImportedDatabaseReconciler.FINAL_V47_IDENTITY_HASH,
            46 to ImportedDatabaseReconciler.FINAL_V46_IDENTITY_HASH,
        )
        pinned.forEach { (version, pinnedHash) ->
            assertEquals(
                "AppDatabase/$version.json does not match the identity pinned for v$version",
                pinnedHash,
                readDatabase(version).getValue("identityHash").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `the exported schema binds its own identity into the Room master row`() {
        val version = ImportedDatabaseReconciler.EXPECTED_VERSION
        val database = readDatabase(version)
        val identity = database.getValue("identityHash").jsonPrimitive.content
        val identityQueries = database.getValue("setupQueries").toString()

        assertTrue(
            "AppDatabase/$version.json does not stamp its identity into room_master_table",
            identityQueries.contains("INSERT OR REPLACE INTO room_master_table") &&
                identityQueries.contains(identity),
        )
    }

    @Test
    fun `the exported schema declares every Space table`() {
        val exported = readDatabase(ImportedDatabaseReconciler.EXPECTED_VERSION)
            .getValue("entities")
            .jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
            .toSet()
        SPACE_TABLES.forEach { table ->
            assertTrue("AppDatabase export is missing $table", table in exported)
        }
    }

    private fun readDatabase(version: Int) =
        readSchema(version).jsonObject.getValue("database").jsonObject

    private fun readSchema(version: Int) = run {
        val relative = "schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json"
        val file = sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?: error("Missing AppDatabase/$version.json")
        Json.parseToJsonElement(file.readText())
    }

    private companion object {
        val SPACE_TABLES = listOf(
            "space_posts",
            "space_likes",
            "space_comments",
            "space_notifications",
        )
    }
}
