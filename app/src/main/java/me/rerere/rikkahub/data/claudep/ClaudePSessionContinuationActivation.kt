package me.rerere.rikkahub.data.claudep

/**
 * Whether production may act on a Claude P continuation at all.
 *
 * ## Why a switch exists rather than the wiring simply being present
 *
 * `mode: "auto"` is not a feature that can be partially on. A request that carries a binding
 * intent makes the Server resolve — or refuse to resolve — a *session* for a branch, and every
 * record this app writes about that is durable. So the two halves have to move together:
 *
 * - while this is `false`, no decision is produced, no barrier is written, no binding request
 *   reaches the wire, and every Claude P dispatch keeps the `mode: "new"` shape it had before M3-B
 *   — byte for byte, on the request and on the graph;
 * - while this is `true`, the app is asserting that **every** exit from an admitted generation
 *   settles its branch, because a barrier that is written and never settled closes that branch
 *   permanently.
 *
 * The second half is the whole reason this is one switch and not several. A barrier is only safe
 * once *every* way a run can end writes a terminal — a completion, a model failure, a user
 * cancellation, a dropped connection, and a process that never comes back. One unhandled exit is
 * not a degraded feature; it is a conversation that cannot be continued again.
 *
 * ## What had to be true before this flipped
 *
 * 1. Immediate admission writes `START_IN_FLIGHT` in the command's authority transaction, and the
 *    barrier commit, the settlement attachment and the dispatch happen in that order.
 * 2. A barrier or an attachment that fails dispatches nothing.
 * 3. Immediate terminals settle on completion, model failure, cancellation and disconnect.
 * 4. A `deferred` generation writes `BIND_PENDING` in the transaction that commits its variant,
 *    sends exactly one `session.bind`, and settles on every answer including a dropped connection.
 * 5. A user-triggered replay of an interrupted bind re-sends the persisted identity at most once
 *    per live connection and never starts a generation.
 * 6. **Stale reconciliation** supersedes a start no run in this process owns, and pauses rather
 *    than dispatching when it cannot.
 * 7. Every other path that can dispatch a Claude P generation either holds a barrier or is refused
 *    by name.
 *
 * Items 1–5 and 7 are delivered. Three shapes are refused rather than supported, and each is a
 * recorded limitation rather than an unfinished path: the two emergency commands that bypass
 * admission, a cancelled or failed turn's ordinary follow-up message, and final-answer recovery's
 * second model call. All three are visible, named refusals — none of them falls back to `mode:
 * "new"` and none of them can produce a hidden second session.
 *
 * ## Why this is a `val` and not a `const val`
 *
 * A `const val` is folded at compile time, which turns every guarded call site into a branch the
 * compiler can prove is dead and reports as such. This is read at run time so that the guard reads
 * as an ordinary decision — and so that the test which pins its value cannot be optimised away.
 */
object ClaudePSessionContinuationActivation {

    /**
     * Whether production acts on Claude P continuations.
     *
     * Disabled for mobile chat. The app conversation is again the only history authority, so no
     * continuation decision or graph barrier is written and Claude P uses the supported
     * `mode: "new"` request shape. Background agent continuation may be reintroduced later only
     * behind its own surface; it must not own or pause the ordinary chat queue.
     */
    val ENABLED: Boolean = false
}
