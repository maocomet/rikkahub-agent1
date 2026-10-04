package me.rerere.ai.provider.claudep

/** Gateway tool-call states needed by the wire DTOs. */
enum class ClaudePToolCallState(val wireValue: String) {
    PENDING("pending"),
    COMPLETED("completed"),
    DENIED("denied"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    DISCONNECTED("disconnected"),
    UNKNOWN("");

    companion object {
        fun fromWire(raw: String?): ClaudePToolCallState =
            entries.firstOrNull { it != UNKNOWN && it.wireValue == raw } ?: UNKNOWN
    }
}
