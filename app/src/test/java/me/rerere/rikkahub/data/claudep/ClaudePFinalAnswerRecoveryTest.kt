package me.rerere.rikkahub.data.claudep

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may make the second model call that final-answer recovery exists for.
 *
 * The rule has two halves and both are asserted here, because getting either wrong is invisible: a
 * Claude P turn that took the recovery would start a second, unbound session whose answer the user
 * reads while the branch stays bound to the first — and a **non**-Claude-P provider that stopped
 * taking it would lose a recovery it has always had. The file this tests explains why the two are
 * different; this is the matrix.
 */
class ClaudePFinalAnswerRecoveryTest {

    private fun claudeP() = ProviderSetting.ClaudeP()
    private fun other() = ProviderSetting.OpenAI()

    // ---------------------------------------------------------------------------------------
    // Claude P: refused, by name
    // ---------------------------------------------------------------------------------------

    /**
     * The named refusal. It is what the message annotation carries and what the diagnostics record,
     * so it is a user-visible string and a rename here is a user-visible change.
     */
    @Test
    fun `a Claude P turn does not take the recovery`() {
        val refusal = ClaudePFinalAnswerRecovery.refusal(
            provider = claudeP(),
            activationEnabled = true,
        )

        assertEquals("claude_p_final_answer_recovery_not_supported", refusal)
        assertEquals(ClaudePFinalAnswerRecovery.NOT_SUPPORTED, refusal)
    }

    /**
     * The reason is namespaced like every other refusal this batch produces, so a reader can tell
     * which layer refused without reading the call site.
     */
    @Test
    fun `the refusal is namespaced and survives annotation truncation`() {
        assertTrue(ClaudePFinalAnswerRecovery.NOT_SUPPORTED.startsWith("claude_p_"))
        // `withFinalAnswerRecovery` takes 200 characters; a longer code would be silently cut.
        assertTrue(ClaudePFinalAnswerRecovery.NOT_SUPPORTED.length < 200)
    }

    // ---------------------------------------------------------------------------------------
    // Every other provider: unchanged, byte for byte
    // ---------------------------------------------------------------------------------------

    /**
     * **The recovery is not touched for anyone else.** It is the whole reason the rule is a decision
     * over the provider rather than a change inside the recovery loop.
     */
    @Test
    fun `another provider still takes the recovery`() {
        for (provider in listOf(
            other(),
            ProviderSetting.Claude(),
            ProviderSetting.Google(),
            ProviderSetting.Codex(),
        )) {
            assertNull(
                "$provider must keep its recovery",
                ClaudePFinalAnswerRecovery.refusal(provider, activationEnabled = true),
            )
            assertNull(
                "$provider must keep its recovery",
                ClaudePFinalAnswerRecovery.refusal(provider, activationEnabled = false),
            )
        }
    }

    /**
     * **While M3-B is off, Claude P is unchanged too.** No branch carries a binding, so the
     * recovery's `mode: "new"` is the shape every Claude P request has always had — there is
     * nothing to refuse, and refusing it would be a behaviour change with no cause.
     */
    @Test
    fun `an inactive Claude P keeps its old behaviour`() {
        assertNull(
            ClaudePFinalAnswerRecovery.refusal(
                provider = claudeP(),
                activationEnabled = false,
            ),
        )
    }

    /**
     * The switch is the only thing that decides, and it decides per call rather than being captured
     * once — so a test that flips it sees the behaviour move.
     */
    @Test
    fun `the activation switch is the only input that changes for Claude P`() {
        val provider = claudeP()

        assertNull(ClaudePFinalAnswerRecovery.refusal(provider, activationEnabled = false))
        assertEquals(
            ClaudePFinalAnswerRecovery.NOT_SUPPORTED,
            ClaudePFinalAnswerRecovery.refusal(provider, activationEnabled = true),
        )
    }
}
