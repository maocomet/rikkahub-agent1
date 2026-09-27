package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Why the two transformers that *can* write to the system message do not, on the default path.
 *
 * The wire-side check refuses any system instruction the app did not freeze. That is the right
 * failure for something that genuinely drifted, and it would be the wrong failure for an ordinary
 * chat — so "ordinary Claude P chat is unaffected" needs evidence rather than confidence.
 *
 * Two transformers can reach the system message:
 *
 * - `TemplateTransformer` rewrites every message through the assistant's `messageTemplate`. Its
 *   `time` and `date` come from the *message's* `createdAt`, and the system message is rebuilt on
 *   every turn — so a template that names them produces different system text each turn.
 * - `WorkspaceReminderTransformer` appends a work-space prompt when the assistant has a ready one.
 *
 * ## What these assertions are, and what they are not
 *
 * They are assertions about **configuration**, not about executed behaviour, and the distinction is
 * deliberate rather than convenient:
 *
 * - the template is **not evaluated here**. `PebbleEngine` binds SLF4J, whose Android provider is
 *   the only one on this classpath, so constructing an engine in a plain JVM unit test throws
 *   before any template is parsed. The default template is therefore pinned as a *string*, and the
 *   reason it is the identity is that `{{ message }}` renders its own input — stated, not measured.
 * - the work-space guard is **not executed** either. `WorkspaceReminderTransformer.transform` takes
 *   a `TransformerContext`, which requires an Android `Context`. What is pinned is the precondition
 *   its first line reads.
 *
 * The executed evidence for both paths is on the other side of the wire: a system instruction that
 * differs from the frozen one is refused, and
 * `ClaudePProviderStableSystemPromptTest` proves that for a template-shaped drift by name.
 */
class StableSystemPromptDefaultPathTest {

    @Test
    fun `the default assistant template is the identity, so it cannot move the system message`() {
        // `{{ message }}` renders exactly its input. Applied to the system message it is therefore
        // a no-op whatever the clock says, which is what keeps the default path freeze-and-forget.
        assertEquals("{{ message }}", Assistant().messageTemplate)
    }

    @Test
    fun `the default assistant has no work space, so the work-space reminder never fires`() {
        // The first line of `WorkspaceReminderTransformer.transform` is
        // `ctx.assistant.workspaceId?.toString() ?: return messages`, so a null work space returns
        // the messages untouched and the system message is never appended to.
        assertNull(Assistant().workspaceId)
    }

    @Test
    fun `a freshly constructed assistant enables neither source`() {
        val assistant = Assistant(id = Uuid.random())

        assertEquals("{{ message }}", assistant.messageTemplate)
        assertNull(assistant.workspaceId)
    }
}
