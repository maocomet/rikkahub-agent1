package me.rerere.rikkahub.space

/**
 * Bridge from a newly stored Cat Garden notification to whichever trigger family is listening.
 *
 * Lives in the space package rather than in the workflow package on purpose: the data layer
 * announces an event, and the workflow layer subscribes to it. Putting the bridge in `workflow`
 * would make `space` depend on `workflow` for a table it owns.
 *
 * A process-local object rather than [me.rerere.rikkahub.data.event.AppEventBus]: that bus is an
 * in-memory `SharedFlow` with no replay, so a notification created while no subscriber is
 * attached would be lost forever. Here the durable row is the source of truth and this is only
 * the "wake up and look" signal — a missed signal costs latency, never the event, because the
 * consumer reads the row back.
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
