package me.rerere.rikkahub.space

/**
 * Bridge from a newly stored Cat Garden notification to whichever trigger family is listening.
 *
 * Lives in the space package rather than in the workflow package on purpose: the data layer
 * announces an event, and the workflow layer subscribes to it. Putting the bridge in `workflow`
 * would make `space` depend on `workflow` for a table it owns.
 *
 * A process-local object rather than [me.rerere.rikkahub.data.event.AppEventBus]: that bus is an
 * in-memory `SharedFlow` with no replay, and this signal has no replay either — it is deliberately
 * the cheap half of the design.
 *
 * The durable row is the source of truth. This signal is only "wake up and look", and the consumer
 * reads the row back — see [me.rerere.rikkahub.workflow.trigger.SpaceNotificationTriggerFamily],
 * which scans unconsumed rows for its recipients every time it binds. That scan is what actually
 * makes the delivery durable: a notification committed while the process was dead, or while no
 * family was registered, is picked up when one returns. A missed signal therefore costs latency
 * only, because replay — not this object — is what recovers the event.
 *
 * Without that scan this would be durable *storage* with a volatile wake signal and nothing else,
 * and the claim above would be false. The two must stay together.
 */
object SpaceNotificationDispatcher {
    @Volatile
    private var family: SpaceNotificationSink? = null

    internal fun bind(sink: SpaceNotificationSink) {
        family = sink
    }

    internal fun unbind(sink: SpaceNotificationSink) {
        if (family === sink) family = null
    }

    /** Called after a notification row is committed. Never throws into the writer. */
    suspend fun onCreated(notification: SpaceNotificationEntity) {
        runCatching { family?.onSpaceNotificationCreated(notification) }
    }
}

/** Implemented by the workflow trigger family; kept minimal so the space layer stays unaware. */
interface SpaceNotificationSink {
    suspend fun onSpaceNotificationCreated(notification: SpaceNotificationEntity)
}
