package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.owner.OwnerToolFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondUserToolAllowlistTest {

    private val canonical = LocalToolOption.PRIVILEGED_IMPLEMENTED

    @Test
    fun `missing stored key decodes to null which follows the full default`() {
        assertNull(SecondUserToolAllowlist.decodeOptionalStringSet(null))
        assertNull(SecondUserToolAllowlist.decodeOptionalStringSet(""))
        // null = follow current full -> byte-for-byte identical to legacy PRIVILEGED_IMPLEMENTED.
        assertEquals(canonical, SecondUserToolAllowlist.resolveLocalOptions(null))
        assertEquals(
            canonical.size,
            SecondUserToolAllowlist.canonicalLocalOptions.size,
        )
    }

    @Test
    fun `empty stored set means explicit all-off and stays empty`() {
        assertEquals(emptySet<String>(), SecondUserToolAllowlist.decodeOptionalStringSet("[]"))
        assertEquals(emptyList<LocalToolOption>(), SecondUserToolAllowlist.resolveLocalOptions(emptySet()))
    }

    @Test
    fun `decode keeps unknown tokens at string layer and resolve drops them`() {
        val raw = """["termux","future_unknown","browser"]"""
        assertEquals(
            setOf("termux", "future_unknown", "browser"),
            SecondUserToolAllowlist.decodeOptionalStringSet(raw),
        )
        // Unknown entries never survive the use boundary.
        val options = SecondUserToolAllowlist.resolveLocalOptions(setOf("termux", "future_unknown"))
        assertEquals(listOf(LocalToolOption.Termux), options)
    }

    @Test
    fun `disabling one family removes it and re-enabling restores full`() {
        val full = SecondUserToolAllowlist.allLocalOptionTokens()
        val withoutTermux = SecondUserToolAllowlist.resolveLocalOptions(full - "termux")
        assertEquals(canonical.size - 1, withoutTermux.size)
        assertTrue(LocalToolOption.Termux !in withoutTermux)

        val restored = SecondUserToolAllowlist.resolveLocalOptions(full)
        assertEquals(canonical, restored)
    }

    @Test
    fun `ordinary assistant branch returns assistant localTools untouched`() {
        val assistantTools = listOf(LocalToolOption.TimeInfo)
        val resolved = SecondUserToolAllowlist.resolveLocalSurfaceTools(
            assistantLocalTools = assistantTools,
            privileged = false,
            privilegedEnabledTokens = setOf("termux", "files"),
        )
        assertEquals(assistantTools, resolved)
    }

    @Test
    fun `owner families resolve from names and drop unknown names`() {
        assertEquals(OwnerToolFamily.entries.toSet(), SecondUserToolAllowlist.resolveOwnerFamilies(null))
        val filtered = SecondUserToolAllowlist.resolveOwnerFamilies(setOf("DOCTOR", "RUN", "FUTURE_UNKNOWN"))
        assertEquals(setOf(OwnerToolFamily.DOCTOR, OwnerToolFamily.RUN), filtered)
        assertTrue(SecondUserToolAllowlist.resolveOwnerFamilies(emptySet()).isEmpty())
    }
}
