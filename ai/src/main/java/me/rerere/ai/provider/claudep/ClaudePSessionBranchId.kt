package me.rerere.ai.provider.claudep

import java.security.MessageDigest

/**
 * The canonical identity of one Claude P continuation branch.
 *
 * ## What a "branch" is here, and why it is not a tip
 *
 * A RikkaHub conversation is not a tree. It is an ordered list of `MessageNode`s, and each node
 * holds its own list of message *variants* plus a `selectIndex` choosing which one is displayed.
 * The user's branch is therefore a **combination of choices**, not a path that can be read off the
 * last message.
 *
 * So this digest is taken over the *whole selection vector*: the nodes whose `selectIndex` selects
 * a non-default variant, in the conversation's own node order. A node on `selectIndex == 0` is
 * already described by the default and is deliberately **not** encoded — encoding it would make the
 * digest depend on how many plain messages happen to precede the branch, which is not a property of
 * the branch. Consequence: a normal linear append adds a node at `selectIndex == 0` and leaves this
 * digest **unchanged**, which is exactly what continuation requires.
 *
 * Nothing here is inherited, searched for, or guessed. Two conversations with the same node
 * identities and the same selection vector get the same digest; two different vectors get
 * different digests. There is no dependence on the current tip, the most recent command, the
 * wall clock, the node count, `branchAnchorMessageId`, or any process-local state.
 *
 * ## The framing, and why it is not invented here
 *
 * The byte framing is the one this project already froze for request fingerprints in
 * `02-wire-protocol-v1.md` §12.3 and implements twice (`ClaudePRequestFingerprint` on Android,
 * `computeRequestDigest` in the Worker): a domain-separated label/value stream where every label
 * and every present value is length-prefixed with a 4-byte big-endian length, and presence is
 * carried by an explicit byte so that *absent* and *empty* cannot collide.
 *
 * The one rule that matters most here: **labels are length-prefixed**. Without that, the pairs
 * `("ab", "c")` and `("a", "bc")` would produce identical bytes, and a digest that two different
 * selection vectors share is worse than no digest.
 *
 * ## The root branch
 *
 * An empty selection vector is a real branch — the conversation's default line — and it gets a real
 * versioned digest. It is never the empty string, and never a sentinel: a sentinel would be a
 * second spelling of "no branch" that a caller could mistake for a malformed one.
 *
 * ## Malformed input is refused, never repaired
 *
 * A blank identity, a duplicated node, or a duplicated selected message is [Result.Malformed]. The
 * caller must treat that as "no branch identity" and fail closed. Nothing here trims, folds case,
 * normalises Unicode, or drops a bad entry to produce a digest of whatever was left — every one of
 * those would be a way for a malformed graph to acquire a plausible-looking identity.
 */
object ClaudePSessionBranchId {

    /** Versioned domain label. A change to the encoding must change this string. */
    const val DOMAIN: String = "rikkahub-claude-p-session-branch-v1"

    /** One node that selects a non-default variant. All fields are opaque identities. */
    data class Selection(
        val nodeId: String,
        val selectedMessageId: String,
    )

    /** Why a selection vector could not be given a branch identity. A closed set. */
    enum class Reason {
        BLANK_NODE_ID,
        BLANK_SELECTED_MESSAGE_ID,
        DUPLICATE_NODE_ID,
        DUPLICATE_SELECTED_MESSAGE_ID,
    }

    sealed interface Result {
        data class Valid(val id: String) : Result
        data class Malformed(val reason: Reason) : Result
    }

    /**
     * The digest of [selections], or [Result.Malformed].
     *
     * The caller supplies the selections **in conversation node order**; order is part of the
     * contract and is encoded, so two vectors that differ only by order are different branches.
     */
    fun compute(selections: List<Selection>): Result {
        val seenNodes = HashSet<String>(selections.size)
        val seenMessages = HashSet<String>(selections.size)

        for (selection in selections) {
            if (selection.nodeId.isBlank()) return Result.Malformed(Reason.BLANK_NODE_ID)
            if (selection.selectedMessageId.isBlank()) {
                return Result.Malformed(Reason.BLANK_SELECTED_MESSAGE_ID)
            }
            if (!seenNodes.add(selection.nodeId)) return Result.Malformed(Reason.DUPLICATE_NODE_ID)
            if (!seenMessages.add(selection.selectedMessageId)) {
                return Result.Malformed(Reason.DUPLICATE_SELECTED_MESSAGE_ID)
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.field("domain", DOMAIN)
        // The count is encoded rather than implied by the stream's end. A digest whose input has no
        // length is a digest two different streams can share by concatenation.
        digest.field("selection_count", selections.size.toString())
        selections.forEachIndexed { index, selection ->
            digest.field("selection_$index.node_id", selection.nodeId)
            digest.field("selection_$index.selected_message_id", selection.selectedMessageId)
        }
        return Result.Valid(digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        })
    }

    /**
     * Writes one labelled field: the length-prefixed label, then a presence byte and the value.
     *
     * Presence is explicit for the same reason it is in the request fingerprint: an absent value
     * and an empty one must not share a digest. No caller here passes null today, and the byte is
     * kept so that adding one later cannot silently change what an existing digest means.
     */
    private fun MessageDigest.field(label: String, value: String?) {
        updateLengthPrefixed(label.toByteArray(Charsets.UTF_8))
        if (value == null) {
            update(0)
            return
        }
        update(1)
        updateLengthPrefixed(value.toByteArray(Charsets.UTF_8))
    }

    private fun MessageDigest.updateLengthPrefixed(bytes: ByteArray) {
        val size = bytes.size
        update((size ushr 24).toByte())
        update((size ushr 16).toByte())
        update((size ushr 8).toByte())
        update(size.toByte())
        update(bytes)
    }
}
