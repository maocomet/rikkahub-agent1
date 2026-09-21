package me.rerere.ai.provider.claudep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings screen's action rules.
 *
 * These are safety rules, not cosmetics: "a cleanup is outstanding, so do not offer pairing" and
 * "the device is not dispatchable, so the enable switch stays off". Deriving them here rather than
 * inside a composable is what makes them enumerable.
 */
class ClaudePConfigureUiTest {

    // ---------------------------------------------------------------------------------------
    // A pending cleanup gates everything
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a pending cleanup blocks scanning even on a clean-looking device`() {
        val ui = ui(status = ClaudePUiStatus.NOT_PAIRED, cleanupPending = true)

        // Pairing over unresolved material would orphan the previous attempt's private key: the new
        // credential points at a new alias, and the old one becomes permanently undeletable.
        assertFalse(ui.canScanPairingQr)
        assertTrue(ui.canRetryCleanup)
        assertEquals(ClaudePConfigureNotice.CLEANUP_INCOMPLETE, ui.notice)
    }

    @Test
    fun `a pending cleanup blocks enabling the provider`() {
        // Even if the derived status somehow looked dispatchable, a pending cleanup outranks it.
        val ui = ui(status = ClaudePUiStatus.ONLINE, cleanupPending = true)

        assertFalse(ui.canEnableProvider)
        assertFalse(ui.showsGatewayDetails)
    }

    @Test
    fun `retry is offered only while a cleanup is outstanding`() {
        assertTrue(ui(cleanupPending = true).canRetryCleanup)
        assertFalse(ui(cleanupPending = false).canRetryCleanup)
    }

    @Test
    fun `no action is offered twice at once`() {
        val inFlight = ui(status = ClaudePUiStatus.PAIRED, cleanupPending = true, cleanupInFlight = true)

        // The double-tap guard, asserted directly rather than left to a button's `enabled` flag.
        assertFalse(inFlight.canRetryCleanup)
        assertFalse(inFlight.canUnpair)
        assertFalse(inFlight.canScanPairingQr)
    }

    // ---------------------------------------------------------------------------------------
    // The ordinary states
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an unpaired clean device offers scanning and nothing else`() {
        val ui = ui(status = ClaudePUiStatus.NOT_PAIRED)

        assertTrue(ui.canScanPairingQr)
        assertFalse(ui.canUnpair)
        assertFalse(ui.canEnableProvider)
        assertFalse(ui.cleanupIncomplete)
    }

    @Test
    fun `a paired device offers unpair and gateway details but not scanning`() {
        val ui = ui(status = ClaudePUiStatus.PAIRED)

        assertTrue(ui.canUnpair)
        assertTrue(ui.showsGatewayDetails)
        assertTrue(ui.canEnableProvider)
        assertFalse(ui.canScanPairingQr)
    }

    @Test
    fun `a device that was never paired renders no gateway details`() {
        // There is nothing to show, and rendering a placeholder would imply a pairing that does not
        // exist. A device whose credential is merely unusable *does* still know its gateway, so it
        // keeps the card — and keeps the unpair action that clears it.
        assertFalse(ui(status = ClaudePUiStatus.NOT_PAIRED).showsGatewayDetails)
        assertTrue(ui(status = ClaudePUiStatus.CREDENTIAL_INVALID).showsGatewayDetails)
        assertTrue(ui(status = ClaudePUiStatus.CREDENTIAL_INVALID).canUnpair)
    }

    @Test
    fun `a pending cleanup hides gateway details regardless of status`() {
        ClaudePUiStatus.entries.forEach { status ->
            assertFalse(
                "gateway details must be hidden while cleanup is pending for $status",
                ui(status = status, cleanupPending = true).showsGatewayDetails,
            )
        }
    }

    @Test
    fun `only paired and online states can enable the provider`() {
        val enabling = ClaudePUiStatus.entries.filter { ui(status = it).canEnableProvider }

        assertEquals(listOf(ClaudePUiStatus.PAIRED, ClaudePUiStatus.ONLINE), enabling)
    }

    @Test
    fun `offline and protocol error are surfaced rather than shown as usable`() {
        assertEquals(
            ClaudePConfigureNotice.PROTOCOL_ERROR,
            ui(status = ClaudePUiStatus.PROTOCOL_ERROR).notice,
        )
        assertEquals(
            ClaudePConfigureNotice.CREDENTIAL_INVALID,
            ui(status = ClaudePUiStatus.CREDENTIAL_INVALID).notice,
        )
        assertFalse(ui(status = ClaudePUiStatus.OFFLINE).canEnableProvider)
    }

    @Test
    fun `the incomplete-cleanup notice outranks every other notice`() {
        ClaudePUiStatus.entries.forEach { status ->
            assertEquals(
                "a pending cleanup must always be visible for $status",
                ClaudePConfigureNotice.CLEANUP_INCOMPLETE,
                ui(status = status, cleanupPending = true).notice,
            )
        }
    }

    @Test
    fun `the notice is a bounded enum so no alias path or exception can reach the user`() {
        // The screen maps these to prose. Nothing in the type carries free text, so there is no
        // field through which an alias, a file path or an exception message could be rendered.
        val notice = ui(status = ClaudePUiStatus.NOT_PAIRED, cleanupPending = true).notice

        assertTrue(notice is ClaudePConfigureNotice)
        assertEquals(
            setOf(
                ClaudePConfigureNotice.CLEANUP_INCOMPLETE,
                ClaudePConfigureNotice.CREDENTIAL_INVALID,
                ClaudePConfigureNotice.PROTOCOL_ERROR,
            ),
            ClaudePConfigureNotice.entries.toSet(),
        )
    }

    @Test
    fun `cleanupIncomplete is driven by durable state rather than the last attempt`() {
        // It survives a restart because it is derived from `cleanupPending`, which the repository
        // reads from the tombstone — not from a failure list this process happened to observe.
        assertTrue(ui(cleanupPending = true).cleanupIncomplete)
        assertFalse(ui(cleanupPending = false).cleanupIncomplete)
    }

    private fun ui(
        status: ClaudePUiStatus = ClaudePUiStatus.NOT_PAIRED,
        cleanupPending: Boolean = false,
        cleanupInFlight: Boolean = false,
    ) = ClaudePConfigureUi.reduce(
        status = status,
        cleanupPending = cleanupPending,
        cleanupInFlight = cleanupInFlight,
    )
}
