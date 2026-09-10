package me.rerere.rikkahub.data.ai.execution

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex

/**
 * Proof that the current call chain **already holds one specific global policy lock**.
 *
 * Why this exists: [DefaultToolRuntime.withPolicyLocks] acquires its `globalMutex` for every
 * [ToolConcurrency.GLOBAL_SERIAL] tool, and [Mutex] is not reentrant. A nested chain —
 * `chat → workflow_run (GLOBAL_SERIAL) → WorkflowEngine.fire → WorkflowActionRunner →
 * post_notification (GLOBAL_SERIAL)` — would therefore wait on a lock its own stack already
 * holds, and unwind only when the per-action wall-clock budget expired (`action_timeout`).
 * Non-nested fires are unaffected, which is why the same workflow succeeds from the UI
 * "Run now" path and from a real trigger.
 *
 * **The element carries the exact [Mutex] it attests to**, and the runtime skips an
 * acquisition only when that instance is *identical* to the lock it is about to take. This
 * is what makes the token safe to be constructible at all: minting one is harmless, because a
 * token for any other lock never matches and therefore never suppresses an acquisition. The
 * only way to produce a matching token is to pass the runtime's own `private` mutex — i.e. to
 * already hold the lock the element claims.
 *
 * Lifetime: installed by [DefaultToolRuntime.withPolicyLocks] strictly *inside* the critical
 * section it guards, via `withContext`, and unreachable again the moment that section returns.
 * It grants nothing on its own and is never consulted from tool arguments, JSON, or any
 * parse path.
 *
 * Scope of what it suppresses: **only** the re-acquisition of that one already-held lock.
 * Every independent gate still runs for a nested call — pre-execution gate, capability and
 * origin checks, HARDLINE, lifecycle persistence, interception, observers, and the
 * per-action wall-clock budget. It is deliberately not a general "skip locking" switch.
 */
internal class GlobalPolicyLockToken(
    /** The exact lock this element attests to. */
    val mutex: Mutex,
) : AbstractCoroutineContextElement(Key) {

    /** Context key. A distinct object is required — a self-reference does not satisfy [CoroutineContext.Key]. */
    internal companion object Key : CoroutineContext.Key<GlobalPolicyLockToken>

    override fun toString(): String = "GlobalPolicyLockToken"
}
