package me.rerere.rikkahub.data.claudep

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the single-owner shape of a Claude P tool approval raised by a live generation. */
class ClaudePInFlightApprovalWiringTest {

    private val chatService by lazy {
        projectFile(
            "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt",
            "src/main/java/me/rerere/rikkahub/service/ChatService.kt",
        ).readText(Charsets.UTF_8)
    }

    private val chatViewModel by lazy {
        projectFile(
            "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt",
            "src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt",
        ).readText(Charsets.UTF_8)
    }

    @Test
    fun `in flight approval is delivered to the exact live run without a second command`() {
        assertTrue("IN_FLIGHT decisions must take the direct path", 
            "if (projection?.isInFlightContinuation() == true)" in chatService)
        assertTrue("the direct path must resolve the exact run control", 
            "claudePToolRunControls.find(projection.traceId)" in chatService)
        assertTrue("the direct decision must not claim its own runtime command", 
            "ownsRuntimeCommand = false" in chatService)
        assertTrue("the original run authority must not be terminalized by the tap", 
            "if (ownsRuntimeCommand) {" in chatService)
    }

    @Test
    fun `in flight card does not checkpoint the root command as waiting`() {
        assertTrue(
            "only ended-generation approvals may use the WAITING command checkpoint",
            "authority != null && continuation != ApprovalContinuationMode.IN_FLIGHT" in chatService,
        )
        assertTrue(
            "the live generation must persist its card without changing root command state",
            "val directBarrierReceipts =" in chatService &&
                "secondUserApprovalLifecycle.persistPendingBarrier(" in chatService,
        )
        assertTrue(
            "a committed direct barrier must acknowledge the bridge waiter",
            "claudePToolPublicationReceipts.complete(" in chatService,
        )
    }

    @Test
    fun `mobile Claude P uses one fifo worker while background commands retain the runtime`() {
        assertTrue(
            "the app UI route must select Claude P independently of continuation activation",
            "if (!usesClaudePProvider(conversation))" in chatService,
        )
        assertTrue(
            "mobile Claude P turns must enter the simple FIFO",
            "val turnId = enqueueSimpleMobileClaudePTurn(" in chatService,
        )
        assertTrue(
            "the simple worker must publish and withdraw the exact live run",
            "claudePToolRunControls.register(turn.id.toString(), control)" in chatService &&
                "claudePToolRunControls.unregister(turn.id.toString(), control)" in chatService &&
                "claudePToolBranchIdOverride = turn.branchAnchorMessageId" in chatService &&
                "?: claudePToolBranchIdOverride" in chatService,
        )
        assertTrue(
            "background and non-Claude-P submissions must retain the durable command path",
            "return submitUserMessage(" in chatService &&
                "origin = CommandOrigin.APP_UI" in chatService &&
                "private suspend fun executeRuntimeCommand(" in chatService,
        )
    }

    @Test
    fun `main chat submits through the mobile provider router`() {
        assertTrue(
            "the real main-chat ViewModel must enter the provider-aware mobile boundary",
            "chatService.submitMobileUserMessage(" in chatViewModel,
        )
        assertTrue(
            "the main-chat send path must not bypass the mobile Claude P FIFO",
            "chatService.submitUserMessage(" !in chatViewModel.substringAfter("fun handleMessageSend")
                .substringBefore("fun handleSteer"),
        )
        assertTrue(
            "the mobile boundary must route Claude P to the simple queue",
            "val turnId = enqueueSimpleMobileClaudePTurn(" in chatService,
        )
    }

    private fun projectFile(vararg candidates: String): File = candidates
        .asSequence()
        .map(::File)
        .firstOrNull(File::isFile)
        ?: error("Could not locate project file; tried ${candidates.joinToString()}")
}
