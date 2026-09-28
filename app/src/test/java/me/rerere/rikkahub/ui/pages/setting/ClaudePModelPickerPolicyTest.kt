package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which providers may be handed a model id typed by hand.
 *
 * ### The defect this pins
 *
 * The models tab offers two affordances: a picker over whatever `listModels` returned, and an
 * "add new model" form that opens a blank editor for an arbitrary id. For Claude P the second one
 * was wrong. Its models are the aliases the paired Gateway's catalog returns, and the provider
 * refuses any other id at dispatch time — so the form could only produce a model that fails later.
 * The user's report of the symptom was exactly that: the Claude P page offered the generic manual
 * form and no Sonnet/Opus/Haiku.
 *
 * The rule is asserted through [allowsManualModelEntry] rather than by rendering the dialog, because
 * the composable that applies it is private. An inline `is ProviderSetting.ClaudeP` check inside a
 * private composable is untestable without a UI harness, which is how a rule like this quietly stops
 * being true the next time someone edits the toolbar.
 */
class ClaudePModelPickerPolicyTest {

    @Test
    fun `Claude P does not accept a hand-typed model id`() {
        assertFalse(ProviderSetting.ClaudeP().allowsManualModelEntry())
    }

    @Test
    fun `every other provider keeps its manual entry`() {
        // The control. A predicate that returned false for everything would satisfy the assertion
        // above while removing a feature every other provider depends on.
        assertTrue(ProviderSetting.OpenAI().allowsManualModelEntry())
        assertTrue(ProviderSetting.Google().allowsManualModelEntry())
        assertTrue(ProviderSetting.Claude().allowsManualModelEntry())
        assertTrue(ProviderSetting.AICore().allowsManualModelEntry())
        assertTrue(ProviderSetting.LiteRtLocal().allowsManualModelEntry())
        assertTrue(ProviderSetting.Codex().allowsManualModelEntry())
    }

    @Test
    fun `the rule is a property of the provider type, not of its state`() {
        // A Claude P that is unpaired, revoked or disabled is still a provider whose ids come from a
        // catalog. Gating this on `pairingState` would re-open the manual form for exactly the users
        // who cannot yet reach a gateway — the ones most likely to type an id that will never work.
        listOf(
            ProviderSetting.ClaudeP(pairingState = me.rerere.ai.provider.claudep.ClaudePPairingState.NOT_PAIRED),
            ProviderSetting.ClaudeP(pairingState = me.rerere.ai.provider.claudep.ClaudePPairingState.PAIRED),
            ProviderSetting.ClaudeP(pairingState = me.rerere.ai.provider.claudep.ClaudePPairingState.REVOKED),
        ).forEach { setting ->
            assertFalse("${setting.pairingState} must not re-open manual entry", setting.allowsManualModelEntry())
        }
    }
}
