package me.rerere.ai.provider.claudep

/**
 * Which **local** connection a Claude P call is travelling on.
 *
 * ## What problem this solves
 *
 * `02-wire-protocol-v1.md` §6.1 allows a `session.bind` to be re-sent, but only "at most once per
 * active connection": a replay is a recovery for a bind that was *sent* and whose answer never
 * arrived, and re-sending it on the very connection that already carried it is a repeat the Server
 * has to deduplicate rather than a recovery. A caller therefore needs to answer one question —
 * *is this the connection the first attempt went out on?* — and nothing else.
 *
 * ## Why this is a local counter and not the Server's connection id
 *
 * The Server does negotiate a connection id, and it is deliberately **not** what this carries. That
 * value is the Server's identity for the socket: it appears on the wire, it identifies the device's
 * connection to a remote party, and handing it to the app would spread a remote identifier through
 * a layer that has no business holding one.
 *
 * This is the opposite: an opaque, monotonically increasing local token that the transport mints for
 * itself. It never leaves the process. It is not persisted, it is not encoded into any frame, it
 * carries no origin, device id, credential or connection id, and it is not written to a log —
 * [toString] redacts the value so that a `Log.d("... $epoch")` cannot leak even the counter.
 *
 * ## What it deliberately does not promise
 *
 * It is not a clock and not a global order across clients: two clients each start at one. It is only
 * ever compared for **equality within one client**, which is exactly what "the same connection?"
 * means. A value that is equal across two clients means nothing and is never relied on.
 *
 * ## When it is unavailable
 *
 * `null` — never a zero or an empty stand-in — whenever there is no carried connection: before a
 * handshake completes, after a socket ends, and in every client that has no transport at all. A
 * caller that cannot name a connection cannot decide "once per connection", and the only safe
 * reading of that is "do not replay".
 */
@JvmInline
value class ClaudePConnectionEpoch(val value: Long) {

    /** Redacted, so a log line can correlate two epochs but cannot disclose the counter. */
    override fun toString(): String = "ClaudePConnectionEpoch(<local>)"
}

