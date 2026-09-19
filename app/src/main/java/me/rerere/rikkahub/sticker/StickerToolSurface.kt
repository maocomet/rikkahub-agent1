package me.rerere.rikkahub.sticker

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/**
 * The single decision point for whether the sticker tool surface exists, and what is in it.
 *
 * Mirrors `CatGardenToolSurface` deliberately, including the reason it is one object rather than a
 * condition at each call site: a second copy of `stickerToolsEnabled + origin` would be free to
 * drift from this one, and the drift would show up as an assistant still reaching the library
 * after the person switched it off.
 *
 * The origin is read from [ToolInvocationContext], which the runtime owns, rather than passed
 * alongside it, so a caller cannot evaluate the gate against one origin and build with another.
 */
object StickerToolSurface {

    /**
     * Origins permitted to carry the sticker surface.
     *
     * Local chat only, and deliberately narrower than Cat Garden's set. Cat Garden admits
     * `TrustedWorkflow` because it was built for that; this phase is about whether an assistant in
     * an ordinary conversation sends a sticker, and a remote or automation origin must not inherit
     * the ability to read and send files out of the person's local library merely because an
     * assistant has the feature switched on. Widening this set is a deliberate act, not a default.
     */
    val ALLOWED_ORIGINS: Set<ToolCallOrigin> = setOf(ToolCallOrigin.LocalChat)

    /**
     * Which sticker tools this turn's model may see.
     *
     * Fails closed in every direction: no assistant opt-in, or an absent/unknown origin, yields
     * nothing at all — so a disabled assistant is sent no sticker schema rather than a tool that
     * refuses at call time.
     */
    fun toolNamesFor(
        assistantEnabled: Boolean,
        callOrigin: ToolCallOrigin?,
    ): Set<String> {
        if (!assistantEnabled) return emptySet()
        val origin = callOrigin ?: return emptySet()
        return if (origin in ALLOWED_ORIGINS) STICKER_TOOL_NAMES else emptySet()
    }

    /**
     * Build the sticker tools for one run, or an empty list when the surface does not apply.
     *
     * The returned list is already narrowed to the names the gate allowed, so a caller that
     * concatenates it into a larger surface cannot widen it by mistake.
     */
    fun build(
        delivery: StickerDelivery,
        repository: StickerRepository,
        invocationContext: ToolInvocationContext,
        assistantEnabled: Boolean,
    ): List<Tool> {
        val names = toolNamesFor(assistantEnabled, invocationContext.callOrigin)
        if (names.isEmpty()) return emptyList()
        return createStickerTools(
            delivery = delivery,
            repository = repository,
            invocationContext = invocationContext,
            assistantEnabled = assistantEnabled,
        ).filter { tool -> tool.name in names }
    }
}
