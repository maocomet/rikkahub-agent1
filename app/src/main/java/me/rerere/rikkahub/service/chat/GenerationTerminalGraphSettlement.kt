package me.rerere.rikkahub.service.chat

import me.rerere.rikkahub.data.model.Conversation

/**
 * One run's obligation to write a graph record when it ends **without a result assistant message**.
 *
 * ## What problem this solves
 *
 * `RuntimeRunAuthority.finishFallback` is deliberately result-less: it terminalises a command
 * without a conversation and without a graph mutation, because the paths that use it — control
 * commands and dispatch failures — have nothing to say about the message graph. A generation that
 * was *admitted* is different: something may have been written into the graph before it ran, and
 * that something has to be settled when the run ends, even though the run produced no assistant
 * message to settle it against.
 *
 * So this is a **separate, generation-only** obligation. `finishFallback` keeps its existing
 * meaning for the commands that never owed a graph write, and this is attached by the run that
 * does.
 *
 * ## Why the interface is this narrow
 *
 * It carries no Claude P type, no session, no provider and no state vocabulary — the authority
 * layer has no business knowing what a continuation is. All it knows is: *here is a function that
 * turns the conversation this transaction is about to persist into the one it must persist
 * instead, or refuses.*
 *
 * `null` is a refusal, and a refusal **fails the command rather than succeeding it**: a run that
 * cannot settle what it owes must not be recorded as finished cleanly, because the alternative is a
 * durable state that says "nothing to see here" about a graph that disagrees.
 *
 * ## Contract
 *
 * - Called at most once per run, inside the command's authority transaction, with the conversation
 *   read in that same transaction.
 * - It is told [DurableCommandState], not a provider outcome: the authority layer has no vocabulary
 *   for "the model failed" and must not acquire one.
 * - Returns the conversation to persist, or `null` to refuse — which rolls the transaction back and
 *   leaves both the command and the graph untouched.
 * - Implementations must be pure: no writes, no I/O, no suspension. The repository persists what
 *   they return, in the transaction they were called in.
 */
fun interface GenerationTerminalGraphSettlement {
    /**
     * @param terminalState how the run ended, as the authority recorded it. The settlement is
     *   attached **when the barrier is written**, long before the outcome exists, so it has to be
     *   told what happened rather than assuming it — an implementation that settled everything the
     *   same way would report a cancellation as a failure, or worse, as a completion.
     */
    fun settle(conversation: Conversation, terminalState: DurableCommandState): Conversation?
}
