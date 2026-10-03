package me.rerere.rikkahub.data.claudep

import java.io.File
import me.rerere.rikkahub.service.isFrozenToolSchemaFingerprint
import org.junit.Assert.assertFalse
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

    // -------------------------------------------------------------------------------------------
    // The frozen schema identity an in-flight card may carry
    // -------------------------------------------------------------------------------------------

    /**
     * Only a whole lowercase digest may stand in as a frozen schema identity.
     *
     * This is the check that keeps the field from becoming a way for anything else to assert a
     * schema it did not freeze. A part is app-owned bookkeeping that only the internal Claude P
     * bridge writes today; this predicate is what keeps that true if another path ever carries a
     * fingerprint-shaped string, because a value that is not exactly the app's own rendering is
     * not treated as frozen identity and falls through to the surface lookup.
     *
     * The rejections are the near-misses that a looser check would wave through — a value with
     * surrounding whitespace, an uppercase digest, a length off by one, a name that merely looks
     * like a digest — and each would be a call committed against an identity nothing froze.
     */
    @Test
    fun `only a whole lowercase digest may stand in as frozen identity`() {
        assertTrue("all-zero digest", isFrozenToolSchemaFingerprint("0".repeat(64)))
        assertTrue("all-f digest", isFrozenToolSchemaFingerprint("f".repeat(64)))
        assertTrue(
            "the app's own rendering: 64 lowercase hex characters",
            isFrozenToolSchemaFingerprint("0123456789abcdef".repeat(4)),
        )

        assertFalse("absent means nothing was frozen", isFrozenToolSchemaFingerprint(null))
        assertFalse("one character short", isFrozenToolSchemaFingerprint("a".repeat(63)))
        assertFalse("one character long", isFrozenToolSchemaFingerprint("a".repeat(65)))
        assertFalse("uppercase is not this app's rendering", isFrozenToolSchemaFingerprint("A".repeat(64)))
        assertFalse("mixed case", isFrozenToolSchemaFingerprint("aB".repeat(32)))
        assertFalse("not hexadecimal", isFrozenToolSchemaFingerprint("g".repeat(64)))
        assertFalse("leading whitespace", isFrozenToolSchemaFingerprint(" " + "a".repeat(63)))
        assertFalse("trailing newline", isFrozenToolSchemaFingerprint("a".repeat(63) + "\n"))
        assertFalse("a tool name is not a digest", isFrozenToolSchemaFingerprint("mcp__b82fa262_funf__ping"))
        assertFalse("an empty string", isFrozenToolSchemaFingerprint(""))
    }

    /**
     * An in-flight card spends the identity it was published under; nothing else may supply one.
     *
     * Three things have to hold together, and each one alone is insufficient: the carried value is
     * read **only** for `IN_FLIGHT` (so an ordinary provider wire message cannot inject an identity
     * through a part), it must pass the digest shape check (so a malformed value is not spent as
     * one), and the live-surface lookup remains the fallback (so every card raised before this
     * field existed, and every other provider's card, commits exactly as it did).
     *
     * The `error` is asserted as still present: removing it would be the "delete the check" fix, and
     * it is what turns an identity that is neither carried nor resolvable into a loud refusal
     * instead of a call run under a schema nobody stated.
     */
    @Test
    fun `an in flight card prefers its frozen identity and every other card keeps the surface`() {
        assertTrue(
            "only an internal in-flight Claude P card may spend a carried identity",
            "continuation == ApprovalContinuationMode.IN_FLIGHT" in chatService,
        )
        assertTrue(
            "a carried value must pass the digest shape check before it is trusted",
            "?.takeIf(::isFrozenToolSchemaFingerprint)" in chatService,
        )
        assertTrue(
            "the live surface remains the fallback for other providers and old messages",
            "?: surfaceSnapshot" in chatService &&
                ".entry(tool.toolName)" in chatService,
        )
        assertTrue(
            "an identity that is neither carried nor resolvable is still a loud refusal",
            "?: error(\"approval_tool_schema_missing\")" in chatService,
        )
        assertTrue(
            "and the carried value is what the barrier is persisted with",
            "toolSchemaFingerprint = schemaFingerprint" in chatService,
        )
    }

    /**
     * The whole chain one mobile Claude P turn has to complete before the next one is admitted.
     *
     * The failure this pins is not the tool running — it is the tool running and the *next* message
     * then having nowhere to go. So the assertions are about the worker, not about the tool: a
     * completed turn must leave the conversation's own deque drained by the same loop, with nothing
     * between turns to refuse the next command.
     *
     * Read off the worker body rather than the whole service, because the question is what the
     * mobile path does and a match anywhere else in this 6000-line file would prove nothing about
     * it. What the body must *not* contain is as load-bearing as what it must:
     *
     *   - no `ConversationRuntime` and no durable command submission, so the admission gate whose
     *     `queuePaused` produced `当前任务已暂停` / `CONTINUATION_BLOCKED` is not on this path;
     *   - no outcome check between turns, so `invoke -> result -> assistant final -> terminal` is
     *     followed by the next turn unconditionally rather than gated on a settlement this path
     *     never writes;
     *   - no queue clear on the completion path, so a finished turn cannot drop what is behind it.
     */
    @Test
    fun `a completed tool turn leaves the conversation fifo able to admit the next command`() {
        val workerBody = chatService
            .substringAfter("private suspend fun runSimpleMobileClaudePQueue(")
            .substringBefore("private suspend fun executeRuntimeCommand(")

        assertTrue(
            "one queue per conversation, so two conversations cannot share an admission order",
            "simpleMobileClaudePQueues.getOrPut(conversationId, ::SimpleMobileQueue)" in chatService,
        )
        assertTrue(
            "the worker is a drain loop rather than a single turn",
            "while (true) {" in workerBody &&
                "queue.pending.removeFirstOrNull()?.also { queue.activeTurnId = it.id }" in workerBody,
        )
        assertTrue(
            "the only clean exit is an empty queue",
            "} ?: break" in workerBody,
        )
        assertTrue(
            "the queue is dropped only when the worker itself was cancelled",
            "if (!mayContinue) queue.pending.clear()" in workerBody,
        )
        assertTrue(
            "each turn publishes and withdraws its own run control, so a second turn gets a fresh one",
            "claudePToolRunControls.register(turn.id.toString(), control)" in workerBody &&
                "claudePToolRunControls.unregister(turn.id.toString(), control)" in workerBody,
        )
        assertTrue(
            "the turn runs the legacy send path, which owns invoke, result, the assistant turn and its terminal",
            "executeSendMessageLegacy(" in workerBody,
        )
        assertFalse(
            "the worker continues past the turn rather than branching on its outcome",
            "RunOutcome" in workerBody,
        )

        assertFalse(
            "the mobile path must not enter the durable runtime's queue",
            "ConversationRuntime" in workerBody,
        )
        assertFalse(
            "and must not acquire the pause that blocked the next command",
            "queuePaused" in workerBody,
        )
        assertFalse(
            "so a completed tool turn has no admission gate to be refused by",
            "CONTINUATION_BLOCKED" in workerBody || "claudep_continuation_refused" in workerBody,
        )
        assertFalse(
            "nor does it submit a second command to continue itself",
            "SendMessageCommand(" in workerBody || "submitCommandTracked(" in workerBody,
        )
    }

    private fun projectFile(vararg candidates: String): File = candidates
        .asSequence()
        .map(::File)
        .firstOrNull(File::isFile)
        ?: error("Could not locate project file; tried ${candidates.joinToString()}")
}
