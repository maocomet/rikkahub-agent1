package me.rerere.rikkahub.data.claudep

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production surface is closed, and opening it has to be a deliberate act.
 *
 * ## Why this is a test rather than a comment
 *
 * `offerCatalog` is one boolean inside a dependency-injection lambda. Nothing at runtime can
 * observe it without an Android `Context`, no unit test can construct the host, and a reviewer
 * reading two files in different directories cannot see that the flag and the evidence behind it
 * have drifted apart. So the guard is a source read: it fails the moment the flag stops being
 * `false`, and the failure message says what has to be true first.
 *
 * That is a weaker claim than a behavioural one, and it is stated rather than dressed up — the
 * repo already uses this technique for boundaries a unit test cannot reach
 * (`LearningArchitectureBoundaryTest`, `ClaudePToolGenerationContextWiringTest`). What it buys is
 * the property that matters here: activation cannot happen **silently**, as a side effect of some
 * other change.
 */
class ClaudePToolActivationStateTest {

    private fun dataSourceModule(): String =
        projectFile(
            "src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
            "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
        ).readText(Charsets.UTF_8)

    private fun hostBinding(): String {
        val source = dataSourceModule()
        val start = source.indexOf("ClaudePToolBridgeHostImpl(")
        check(start >= 0) { "the production host binding is not in DataSourceModule" }
        // To the end of that construction: the closing line of the `single` block that follows it.
        val end = source.indexOf("\n    }", start)
        check(end > start) { "the production host binding has no end" }
        return source.substring(start, end)
    }

    @Test
    fun `the production host offers the catalog`() {
        val binding = hostBinding()

        assertTrue(
            "the activation flag must be present and explicit; a default is how it gets opened " +
                "by accident",
            binding.contains("offerCatalog ="),
        )
        assertEquals(
            "the surface is open, and both gates answered before it was: `build-debug-apk.yml` " +
                "on the closed-surface parent, and the managed-device run whose approval barrier " +
                "suite reported tests=6 against a real AppDatabase. Closing it again means " +
                "editing this test and stating why.",
            1,
            Regex("offerCatalog\\s*=\\s*true").findAll(binding).count(),
        )
    }

    /**
     * Activation changes the flag and nothing else, because everything else is already here.
     *
     * The point of asserting the other arguments is that it makes "just flip the boolean" a claim
     * this file can check. If a dependency the execution paths need were missing, the host would
     * fail closed in production and the activation commit would flip a flag that gates nothing but
     * an empty catalog — the failure mode where every test stays green and no tool ever runs.
     */
    @Test
    fun `every dependency the execution paths need is already bound`() {
        val binding = hostBinding()

        listOf(
            "toolRuntime =",
            "runControls =",
            "toolStartableResolver =",
            "gate =",
            "publications =",
            "inFlightWaiters =",
            "executionHost =",
            "subjectFor =",
        ).forEach { argument ->
            assertTrue(
                "the production host is missing `$argument`; activation would open a surface " +
                    "whose calls cannot be answered",
                binding.contains(argument),
            )
        }
    }

    private fun projectFile(vararg candidates: String): File =
        requireNotNull(candidates.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${candidates.joinToString()} from ${File(".").absolutePath}"
        }
}
