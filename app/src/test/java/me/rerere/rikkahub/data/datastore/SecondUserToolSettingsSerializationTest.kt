package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.SecondUserToolAllowlist
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Old settings / backups that predate the second-user tool allowlist must decode to `null`
 * (i.e. "follow the current full surface"), NOT to an explicit full snapshot. Unknown future
 * tokens are tolerated as plain strings and never crash the whole-`Settings` decode.
 */
class SecondUserToolSettingsSerializationTest {

    @Test
    fun `legacy empty settings defaults both allowlist fields to null`() {
        val settings = JsonInstant.decodeFromString<Settings>("{}")
        assertNull(settings.secondUserEnabledLocalToolTokens)
        assertNull(settings.secondUserEnabledOwnerFamilyNames)
    }

    @Test
    fun `explicit allowlist decodes and survives`() {
        val settings = JsonInstant.decodeFromString<Settings>(
            """{
                "secondUserEnabledLocalToolTokens": ["termux", "files"],
                "secondUserEnabledOwnerFamilyNames": ["DOCTOR", "RUN"]
            }""",
        )
        assertEquals(setOf("termux", "files"), settings.secondUserEnabledLocalToolTokens)
        assertEquals(setOf("DOCTOR", "RUN"), settings.secondUserEnabledOwnerFamilyNames)
    }

    @Test
    fun `explicit empty set means all disabled and is preserved`() {
        val settings = JsonInstant.decodeFromString<Settings>(
            """{
                "secondUserEnabledLocalToolTokens": [],
                "secondUserEnabledOwnerFamilyNames": []
            }""",
        )
        assertEquals(emptySet<String>(), settings.secondUserEnabledLocalToolTokens)
        assertEquals(emptySet<String>(), settings.secondUserEnabledOwnerFamilyNames)
    }

    @Test
    fun `unknown future tokens are tolerated and preserved at the string layer`() {
        val settings = JsonInstant.decodeFromString<Settings>(
            """{
                "secondUserEnabledLocalToolTokens": ["termux", "future_unknown"],
                "secondUserEnabledOwnerFamilyNames": ["FUTURE_FAMILY"]
            }""",
        )
        assertEquals(setOf("termux", "future_unknown"), settings.secondUserEnabledLocalToolTokens)
        assertEquals(setOf("FUTURE_FAMILY"), settings.secondUserEnabledOwnerFamilyNames)
    }

    /**
     * Regression: SettingsStore.update() must persist the allowlists as a plain SORTED
     * List<String> JSON array — NOT `.toSortedSet()` (a TreeSet), which kotlinx.serialization
     * polymorphic encoding rejects ("Serializer for subclass 'TreeSet' is not found ...").
     * Pinning the exact written JSON shape here guards against reintroducing a SortedSet.
     */
    @Test
    fun `allowlist write shape is a plain sorted json array and round-trips`() {
        // Non-empty -> sorted JSON array of strings.
        val nonEmpty = setOf("termux", "files")
        val written = JsonInstant.encodeToString(nonEmpty.sorted())
        assertEquals("[\"files\",\"termux\"]", written)
        assertEquals(
            nonEmpty,
            SecondUserToolAllowlist.decodeOptionalStringSet(written),
        )

        // Explicit all-off -> empty JSON array (NOT absent/null).
        val emptyWritten = JsonInstant.encodeToString(emptyList<String>())
        assertEquals("[]", emptyWritten)
        assertEquals(
            emptySet<String>(),
            SecondUserToolAllowlist.decodeOptionalStringSet(emptyWritten),
        )

        // null (follow default) corresponds to a missing key: the DataStore writer removes it.
        assertNull(SecondUserToolAllowlist.decodeOptionalStringSet(null))
    }
}
