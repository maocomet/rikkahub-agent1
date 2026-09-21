package me.rerere.ai.provider.claudep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tombstone codec.
 *
 * The property that matters is stated once and tested many ways: **a record that cannot be
 * interpreted is never the same as no record.** Treating a malformed or unknown-version tombstone as
 * absent would silently discard the only pointer to a private key that may still exist, leaving a
 * device that looks clean while key material remains on it.
 *
 * Everything here is a plain JVM test because the rule lives in `ai` — the Android store only supplies
 * bytes and returns this verdict.
 */
class ClaudePCleanupTombstoneCodecTest {

    // ---------------------------------------------------------------------------------------
    // Round trip
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a valid tombstone encodes and decodes unchanged`() {
        val encoded = ClaudePCleanupTombstoneCodec.encode(tombstone(ALIAS))!!

        val decoded = ClaudePCleanupTombstoneCodec.decode(encoded)

        assertEquals(ClaudePCleanupTombstone(deviceKeyAlias = ALIAS), (decoded as ClaudePTombstoneRead.Valid).tombstone)
    }

    @Test
    fun `the encoded record carries only the version and the alias`() {
        val encoded = ClaudePCleanupTombstoneCodec.encode(tombstone(ALIAS))!!

        val keys = Regex("\"([a-z_]+)\"\\s*:").findAll(encoded).map { it.groupValues[1] }.toSet()
        // A closed field set is the point: no ticket, credential, key material, gateway URL, token
        // or device identity may ever appear in this file.
        assertEquals(setOf("version", "device_key_alias"), keys)
    }

    @Test
    fun `the tombstone never renders its alias`() {
        assertFalse(tombstone(ALIAS).toString().contains(ALIAS))
    }

    // ---------------------------------------------------------------------------------------
    // Rejections — every one of these must be Unusable, never Absent
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an empty record is unusable rather than absent`() {
        assertUnusable(ClaudePTombstoneRejection.EMPTY, "")
    }

    @Test
    fun `an over-large record is refused before decoding`() {
        val huge = "{\"version\":1,\"device_key_alias\":\"" + "a".repeat(
            ClaudePCleanupTombstoneCodec.MAX_RECORD_BYTES,
        ) + "\"}"

        assertUnusable(ClaudePTombstoneRejection.TOO_LARGE, huge)
    }

    @Test
    fun `malformed json is unusable rather than absent`() {
        assertUnusable(ClaudePTombstoneRejection.MALFORMED, "{not json")
    }

    @Test
    fun `a truncated record is unusable`() {
        val encoded = ClaudePCleanupTombstoneCodec.encode(tombstone(ALIAS))!!

        assertUnusable(
            ClaudePTombstoneRejection.MALFORMED,
            encoded.substring(0, encoded.length / 2),
        )
    }

    @Test
    fun `trailing data after a complete record is refused`() {
        val encoded = ClaudePCleanupTombstoneCodec.encode(tombstone(ALIAS))!!

        // kotlinx refuses content after a complete document; a decoder that tolerated it could be
        // fed a second document that a future build might interpret differently.
        assertUnusable(ClaudePTombstoneRejection.MALFORMED, "$encoded\n{\"version\":1}")
    }

    @Test
    fun `an unknown format version is unusable rather than upgraded or ignored`() {
        assertUnusable(
            ClaudePTombstoneRejection.UNKNOWN_VERSION,
            """{"version":99,"device_key_alias":"$ALIAS"}""",
        )
    }

    @Test
    fun `a record with no version field is refused rather than defaulted`() {
        // No default on `version`: defaulting would make a future format decode as today's.
        assertUnusable(ClaudePTombstoneRejection.MALFORMED, """{"device_key_alias":"$ALIAS"}""")
    }

    @Test
    fun `a record with no alias field is refused rather than defaulted to empty`() {
        assertUnusable(ClaudePTombstoneRejection.MALFORMED, """{"version":1}""")
    }

    @Test
    fun `an extra field is refused rather than silently ignored`() {
        // Accepting unknown keys would let a future field be added without an old build noticing,
        // which is exactly how a sensitive value ends up in a file nobody re-reviewed.
        assertUnusable(
            ClaudePTombstoneRejection.MALFORMED,
            """{"version":1,"device_key_alias":"$ALIAS","credential":"secret"}""",
        )
    }

    @Test
    fun `an implausible alias is refused`() {
        listOf("", " ", "has space", "../escape", "a".repeat(129), "alias\ninjection").forEach { bad ->
            val encoded = """{"version":1,"device_key_alias":"${bad.replace("\n", "\\n")}"}"""
            val decoded = ClaudePCleanupTombstoneCodec.decode(encoded)
            assertTrue(
                "alias '$bad' must not be usable, got $decoded",
                decoded is ClaudePTombstoneRead.Unusable,
            )
        }
    }

    @Test
    fun `an alias is only accepted in the shape the pairing client produces`() {
        assertTrue(ClaudePCleanupTombstoneCodec.isPlausibleAlias("rikkahub_claude_p_device_key_v1_AbC-123"))
        assertFalse(ClaudePCleanupTombstoneCodec.isPlausibleAlias(""))
        assertFalse(ClaudePCleanupTombstoneCodec.isPlausibleAlias("a".repeat(129)))
        assertFalse(ClaudePCleanupTombstoneCodec.isPlausibleAlias("semi;colon"))
    }

    @Test
    fun `encoding refuses an implausible alias instead of writing it`() {
        assertNull(ClaudePCleanupTombstoneCodec.encode(tombstone("bad alias")))
        assertNull(ClaudePCleanupTombstoneCodec.encode(tombstone("")))
    }

    @Test
    fun `encoding refuses an unknown version`() {
        assertNull(ClaudePCleanupTombstoneCodec.encode(tombstone(ALIAS, version = 99)))
    }

    @Test
    fun `a decode is never absent once bytes exist`() {
        // The whole rule, asserted directly: `decode` has no path that returns Absent. Absence is a
        // missing file, which only the store can observe.
        val inputs = listOf(
            "",
            "{",
            "{}",
            """{"version":1}""",
            """{"version":1,"device_key_alias":""}""",
            """{"version":2,"device_key_alias":"$ALIAS"}""",
            "null",
            "[]",
        )

        inputs.forEach { input ->
            val decoded = ClaudePCleanupTombstoneCodec.decode(input)
            assertTrue("'$input' must not decode as absent", decoded !is ClaudePTombstoneRead.Absent)
            assertNotNull(decoded)
        }
    }

    private fun assertUnusable(expected: ClaudePTombstoneRejection, raw: String) {
        val decoded = ClaudePCleanupTombstoneCodec.decode(raw)
        assertEquals("expected $expected for '${raw.take(40)}'", ClaudePTombstoneRead.Unusable(expected), decoded)
    }

    private fun tombstone(alias: String, version: Int = ClaudePCleanupTombstone.CURRENT_VERSION) =
        ClaudePCleanupTombstone(version = version, deviceKeyAlias = alias)

    private companion object {
        const val ALIAS = "rikkahub_claude_p_device_key_v1_AbC123-def"
    }
}
