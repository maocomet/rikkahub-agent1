package me.rerere.rikkahub.data.claudep

import me.rerere.rikkahub.data.ai.GenerationRunControl

/**
 * The live `GenerationRunControl` for a run, found by that run's exact id.
 *
 * ## Why this exists, and why it is not a run manager
 *
 * A Claude P tool call arrives inside a live stream and has to be run through the app's real
 * runtime — which means the runtime needs the control for **the run that is actually executing**,
 * so that `requestCancelTool` and `awaitToolTermination` reach the real `ToolExecutionHandle` a
 * closing generation is asking about.
 *
 * The host that needs it is a long-lived singleton; the control is created per run. This is the
 * one thing that closes that gap, and it is deliberately only that: it holds a reference the app
 * already created, hands it back by exact id, and drops it when the run ends. It creates no
 * control, owns no lifecycle, persists nothing, and schedules nothing. Cancellation itself is
 * still `GenerationRunControl`'s — this class does not have an opinion about what cancelling
 * means, and must never grow one.
 *
 * ## Why the lookup is exact
 *
 * One lookup, taking a run id. There is no "the current run", no conversation fallback, no
 * most-recent match, and no method that could grow one: a tool call answered from *a* control
 * rather than *the* control would register its handle, and be cancelled, against a run that is
 * not the one executing it — and every one of those failures looks like success.
 *
 * ## Why entries are not evicted
 *
 * A run is registered before its job starts and removed when that job completes, so the map holds
 * exactly the runs that are live. Nothing is evicted under pressure: dropping a live run's entry
 * would make an in-flight tool uncancellable, which is worse than a map that is one entry too
 * large. The bound is the number of concurrent runs, which for a single user is one.
 *
 * ## Why a duplicate is refused rather than replaced
 *
 * Two runs cannot share an id — the id is the durable command id. A second registration under a
 * live id means something is wrong upstream, and replacing the entry would silently point an
 * in-flight tool call at a different control. Refusing keeps the first, which is the one whose
 * job is actually running.
 *
 * Thread-safe: registration happens on the run-start path and removal on the job-completion path,
 * which are not the same thread.
 */
class ClaudePToolRunControls {

    private val lock = Any()

    /** Live runs, by exact id. Insertion-ordered only so diagnostics are stable. */
    private val controls = LinkedHashMap<String, GenerationRunControl>()

    /** For diagnostics and tests: how many runs are currently registered. */
    val size: Int get() = synchronized(lock) { controls.size }

    /** The ids currently registered, as a snapshot. */
    fun registeredRunIds(): Set<String> = synchronized(lock) { LinkedHashSet(controls.keys) }

    /**
     * Publishes the control for a run that is starting.
     *
     * Called **before** the run's job starts, so there is no instant in which a run could be
     * serving a tool call while its control is not yet discoverable.
     *
     * @return `false` when this id is already registered. The existing entry is kept: replacing it
     *   would move an in-flight call's cancellation onto a control that is not running it.
     */
    fun register(runId: String, control: GenerationRunControl): Boolean {
        synchronized(lock) {
            if (controls.containsKey(runId)) return false
            controls[runId] = control
            return true
        }
    }

    /**
     * The control for this exact run, or `null`.
     *
     * `null` is the fail-closed answer and the only one: a caller that receives it must not
     * execute the call, and must not go looking for a control some other way.
     */
    fun find(runId: String?): GenerationRunControl? {
        if (runId == null) return null
        return synchronized(lock) { controls[runId] }
    }

    /**
     * Removes a run's control, from the `finally` that ends its job.
     *
     * Passing [control] makes the removal identity-checked: a late completion for a run whose id
     * has already been re-registered by a newer run must not remove the newer run's control.
     * Omitting it removes whatever is there, which is what a hard reset wants.
     *
     * @return `true` when an entry was removed.
     */
    fun unregister(runId: String, control: GenerationRunControl? = null): Boolean {
        synchronized(lock) {
            if (control == null) return controls.remove(runId) != null
            if (controls[runId] !== control) return false
            controls.remove(runId)
            return true
        }
    }

    /**
     * Drops every registration, for a runtime shutdown.
     *
     * Not a lifecycle decision: the runs themselves are stopped by their owner. This only means
     * that afterwards no control is discoverable here, which is the fail-closed state.
     */
    fun clear() {
        synchronized(lock) { controls.clear() }
    }
}
