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

    private fun projectFile(vararg candidates: String): File = candidates
        .asSequence()
        .map(::File)
        .firstOrNull(File::isFile)
        ?: error("Could not locate project file; tried ${candidates.joinToString()}")
}
