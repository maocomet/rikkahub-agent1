package me.rerere.ai.provider.claudep

import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generation-identity seam: what an identity may reach, and what it may not.
 *
 * Three claims are made here, and each one is about a *leak path* rather than about behaviour:
 *
 * 1. **A log line cannot carry an identity.** `toString` is where an identity escapes without
 *    anyone deciding to log it — an exception message, a test failure, a debug print.
 * 2. **A request body cannot carry one.** The context rides [TextGenerationParams] as a
 *    `@Transient` property, and these tests assert that declaration rather than trusting it.
 * 3. **The origin token is not interpreted here.** Nothing in this module trims, folds or
 *    defaults it, so the app's exact-match rule compares the bytes the app supplied.
 *
 * What is *not* tested here, because it is not this module's to test: the app-side mapping from a
 * token to a `ToolCallOrigin`. That mapping lives in `:app`, and its fail-closed rule is stated on
 * `ClaudePToolGenerationContext.callOrigin` for whoever writes it.
 *
 * Nothing here encodes an `@Serializable` type, so these tests run on a machine without the
 * serialization compiler plugin. The wire-side half is in `ClaudePToolFrameTest`.
 */
class ClaudePToolGenerationContextTest {

    private val runId = "run-SENTINEL-8f21c4"
    private val commandId = "command-SENTINEL-3aa9d1"
    private val conversationId = "conversation-SENTINEL-77b2e0"
    private val assistantId = "assistant-SENTINEL-c5f4a8"
    private val branchId = "branch-SENTINEL-19de63"
    private val callOrigin = "origin-SENTINEL-4b7f"

    /** Every planted value, so "none of these may appear" is one list rather than six asserts. */
    private val identities
        get() = listOf(runId, commandId, conversationId, assistantId, branchId, callOrigin)

    private fun context(
        runId: String = this.runId,
        commandId: String = this.commandId,
        conversationId: String = this.conversationId,
        assistantId: String = this.assistantId,
        branchId: String = this.branchId,
        callOrigin: String = this.callOrigin,
    ): ClaudePToolGenerationContext = ClaudePToolGenerationContext(
        runId = runId,
        commandId = commandId,
        conversationId = conversationId,
        assistantId = assistantId,
        branchId = branchId,
        callOrigin = callOrigin,
    )

    // ---------------------------------------------------------------------------------------
    // 1. Logging
    // ---------------------------------------------------------------------------------------

    @Test
    fun `toString carries no identity value`() {
        val rendered = context().toString()

        identities.forEach { identity ->
            assertFalse("toString leaked <$identity>: $rendered", rendered.contains(identity))
        }
    }

    @Test
    fun `toString is the redacted override rather than the generated one`() {
        val rendered = context().toString()

        // A generated data-class `toString` renders `field=value` and never the word "redacted",
        // so this is what distinguishes the override from the leak it replaces.
        assertTrue("the redacted rendering did not run: $rendered", rendered.contains("<redacted>"))
        assertTrue("toString should still say whether a generation can be bound: $rendered", rendered.contains("isComplete=true"))
        assertTrue(rendered.startsWith("ClaudePToolGenerationContext("))
    }

    @Test
    fun `two generations render differently so a log line still correlates`() {
        // The point of redacting to a digest rather than to a constant: two log lines can still be
        // told apart, which is the only reason a redacted `toString` beats deleting the override.
        assertNotEquals(
            context(runId = "run-SENTINEL-one").toString(),
            context(runId = "run-SENTINEL-two").toString(),
        )
        assertEquals(context().toString(), context().toString())
    }

    @Test
    fun `a partially filled context is redacted too`() {
        val partial = context(commandId = "", assistantId = "   ")
        val rendered = partial.toString()

        // The blank field is not typed into the rendering either.
        assertFalse("a blank identity was rendered: $rendered", rendered.contains("assistantId=   "))
        assertTrue("a partial context must not read as complete: $rendered", rendered.contains("isComplete=false"))
        // And the values it does hold are still only digests.
        assertFalse("toString leaked <$runId>: $rendered", rendered.contains(runId))
        assertFalse("toString leaked <$conversationId>: $rendered", rendered.contains(conversationId))
    }

    // ---------------------------------------------------------------------------------------
    // 2. Completeness is not a default
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a blank field in any position makes the context incomplete`() {
        val blanks = listOf(
            "runId" to context(runId = ""),
            "commandId" to context(commandId = ""),
            "conversationId" to context(conversationId = ""),
            "assistantId" to context(assistantId = ""),
            "branchId" to context(branchId = ""),
            "callOrigin" to context(callOrigin = ""),
            // Whitespace is not a value. Trimming here would quietly turn a malformed identity
            // into a well-formed one, which is the substitution the fail-closed rule forbids.
            "runId (whitespace)" to context(runId = "   "),
        )

        blanks.forEach { (position, value) ->
            assertFalse("a blank $position must not read as complete", value.isComplete)
        }
        assertTrue(context().isComplete)
    }

    // ---------------------------------------------------------------------------------------
    // 3. The origin token is carried, not interpreted
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the origin token is returned exactly as supplied`() {
        val padded = " LocalChat "
        val shifted = "localchat"

        // No trimming, no case folding, no alias table. An app that matches exactly is therefore
        // matching the bytes the app itself supplied.
        assertEquals(padded, context(callOrigin = padded).callOrigin)
        assertEquals(shifted, context(callOrigin = shifted).callOrigin)

        // Which also means this layer does not recognise `LocalChat` on the app's behalf: a padded
        // or differently cased token arrives at the mapping unchanged, and `LocalChat` is never
        // the answer to a token this layer could not read.
        assertNotEquals("LocalChat", context(callOrigin = shifted).callOrigin)
        assertNotEquals("LocalChat", context(callOrigin = padded).callOrigin)
    }

    @Test
    fun `a token with nothing in it is not an origin`() {
        // The one judgement this layer can make without the app's vocabulary: a token that says
        // nothing cannot name an origin, so the whole context is unusable.
        assertFalse(context(callOrigin = "").isComplete)
        assertFalse(context(callOrigin = "   ").isComplete)
        assertFalse(context(callOrigin = "\t").isComplete)
    }

    // ---------------------------------------------------------------------------------------
    // 4. The transient declaration itself
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the context is transient on the text generation parameters`() {
        val annotations = propertyAnnotations(
            TextGenerationParams::class.java,
            "claudePToolGenerationContext",
        )

        assertTrue(
            "TextGenerationParams.claudePToolGenerationContext carries no @Transient; it would " +
                "enter every serialized request: ${annotations.map { it.annotationClass.simpleName }}",
            annotations.any { it.annotationClass.qualifiedName == TRANSIENT },
        )
    }

    @Test
    fun `the identity type has no serializable shape of its own`() {
        // Defence in depth for the same claim: if the `@Transient` above were ever dropped, the
        // field would still have no encoder, because this type is deliberately not @Serializable.
        val serializable = ClaudePToolGenerationContext::class.java.annotations
            .map { it.annotationClass.qualifiedName }
            .filter { it == SERIALIZABLE }

        assertEquals("a serializable identity type would be encodable by accident", emptyList<String>(), serializable)
    }

    /**
     * Kotlin places a property-targeted annotation on the backing field when the annotation's Java
     * target allows it and on a synthetic `getX$annotations()` method when it does not. Which of
     * the two `kotlinx.serialization.Transient` uses is the compiler's business, so this looks in
     * both places rather than asserting the compiler's choice.
     */
    private fun propertyAnnotations(owner: Class<*>, property: String): List<Annotation> {
        val capitalized = property.replaceFirstChar { it.uppercaseChar() }
        val onField = runCatching { owner.getDeclaredField(property) }
            .getOrNull()
            ?.annotations
            ?.toList()
            .orEmpty()
        val onSyntheticAccessor = owner.declaredMethods
            .firstOrNull { it.name == "get$capitalized\$annotations" }
            ?.annotations
            ?.toList()
            .orEmpty()
        return onField + onSyntheticAccessor
    }

    private companion object {
        const val TRANSIENT = "kotlinx.serialization.Transient"
        const val SERIALIZABLE = "kotlinx.serialization.Serializable"
    }
}
