package me.rerere.rikkahub.data.ai.prompt

import me.rerere.rikkahub.data.ai.transformers.escapeXmlText

/** Where a relocated section was originally destined, preserved as metadata rather than acted on. */
enum class RuntimeContextPlacement(val wireName: String) {
    BEFORE_SYSTEM_PROMPT("before_system_prompt"),
    AFTER_SYSTEM_PROMPT("after_system_prompt"),
}

/** What produced a relocated section. A closed vocabulary; the value travels, so it cannot be text. */
enum class RuntimeContextOrigin(val wireName: String) {
    LOREBOOK("lorebook"),
    MODE_INJECTION("mode_injection"),
}

/**
 * One piece of content that would have been written into the system message and is carried in the
 * turn's runtime context instead.
 *
 * The placement is kept because it is the only record of where the content was meant to sit. Losing
 * it would make the move lossy in a way nobody could see: the model would still receive the text,
 * but a reviewer asking "did the BEFORE injection stay before?" would have nothing to check against.
 */
data class RuntimeContextSection(
    val placement: RuntimeContextPlacement,
    val origin: RuntimeContextOrigin,
    val content: String,
)

/**
 * The per-request accumulation of everything that must stay out of a stable system prompt.
 *
 * ## It is a value object, and that is the whole safety argument
 *
 * One instance belongs to one generation. It is constructed at the start of a request, written to by
 * the transformers of that request, rendered once, and dropped — there is no static state, no
 * thread-local, and no cache that outlives the call. Two generations running at the same time hold
 * two instances and cannot see each other's values or sections, which is the property that stops one
 * conversation's runtime context appearing in another's prompt.
 *
 * Nothing here survives a cancel, an exception or a normal ending, because nothing here is *able* to
 * survive: there is no owner outside the request that could keep it. A second generation does not
 * reuse the first one's session; it makes a new one.
 *
 * ## Why values are recorded rather than re-resolved
 *
 * The value of `cur_date` is read once, by the transformer that has the platform inputs, and handed
 * over. Re-resolving at render time would let the system prompt and the runtime context disagree
 * about which turn they describe — the clock could cross a date boundary between the two reads — and
 * a reference whose value arrives from a different moment is worse than no reference at all.
 */
class StableSystemPromptSession {

    private val values = LinkedHashMap<String, String>()
    private val sections = mutableListOf<RuntimeContextSection>()

    /** The values recorded so far, in canonical emission order. */
    fun values(): Map<String, String> = orderedValues()

    /** The relocated sections, in the order they were recorded. */
    fun sections(): List<RuntimeContextSection> = sections.toList()

    /**
     * Records the resolved value of one dynamic placeholder.
     *
     * @throws PromptReferenceException when the key is not a dynamic key this build classifies, or
     *   when it was already recorded with a *different* value. Two values for one name is not
     *   something to settle by last-write-wins: the system prompt refers to the name once, and a
     *   conflicted name means the request does not determine what the model is being told.
     */
    fun recordValue(key: String, value: String) {
        if (!PromptReferencePolicy.isDynamic(key)) {
            throw PromptReferenceException(PromptReferenceRejection.UNCLASSIFIED, key)
        }
        val existing = values[key]
        if (existing != null && existing != value) {
            throw PromptReferenceException(PromptReferenceRejection.CONFLICT, key)
        }
        values[key] = value
    }

    /** Records several values at once, with the same rules as [recordValue]. */
    fun recordValues(resolved: Map<String, String>) {
        for ((key, value) in resolved) recordValue(key, value)
    }

    /**
     * Records a section that would otherwise have rewritten the system message.
     *
     * Blank content is dropped rather than recorded: an empty section would still add envelope bytes
     * to every turn, which is dynamic-looking text that carries nothing.
     */
    fun recordSection(section: RuntimeContextSection) {
        if (section.content.isBlank()) return
        sections.add(section)
    }

    /**
     * Rewrites every recorded section's content in place.
     *
     * Called once, by the pass that knows how to resolve placeholders, because relocated content is
     * app-composed text: a lorebook entry containing `{{cur_date}}` would otherwise reach the model
     * as a token nothing will ever resolve — the same defect the system-prompt reference syntax
     * exists to remove, one message layer down.
     */
    fun rewriteSectionContent(rewrite: (String) -> String) {
        for (index in sections.indices) {
            val section = sections[index]
            sections[index] = section.copy(content = rewrite(section.content))
        }
    }

    /**
     * Renders this turn's contribution, or `""` when there is nothing to say.
     *
     * The empty case matters and is deliberate. A request with no dynamic values and no relocated
     * sections must add **no** text at all — an empty `<runtime_values>` block would be text that
     * differs from a request that had none, for no reason, and it would make "did anything volatile
     * happen this turn" unanswerable by reading the prompt.
     */
    fun render(): String {
        val ordered = orderedValues()
        if (ordered.isEmpty() && sections.isEmpty()) return ""

        val builder = StringBuilder()
        if (ordered.isNotEmpty()) {
            builder.append("<runtime_values>\n")
            for ((key, value) in ordered) {
                builder.append("  <runtime_value name=\"")
                    .append(PromptReferencePolicy.escapeXmlAttribute(key))
                    .append("\">")
                    .append(escapeXmlText(value))
                    .append("</runtime_value>\n")
            }
            builder.append("</runtime_values>")
        }
        for (section in sections) {
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append("<runtime_context_section placement=\"")
                .append(PromptReferencePolicy.escapeXmlAttribute(section.placement.wireName))
                .append("\" origin=\"")
                .append(PromptReferencePolicy.escapeXmlAttribute(section.origin.wireName))
                .append("\">\n")
                .append(escapeRuntimeSectionBoundaries(section.content))
                .append("\n</runtime_context_section>")
        }
        return builder.toString()
    }

    /** Canonical order, then anything recorded that the canonical list does not name. */
    private fun orderedValues(): Map<String, String> {
        if (values.isEmpty()) return emptyMap()
        val ordered = LinkedHashMap<String, String>()
        for (key in PromptReferencePolicy.CANONICAL_KEY_ORDER) {
            values[key]?.let { ordered[key] = it }
        }
        // Unreachable while the canonical list covers every dynamic key, and written out anyway: a
        // key added to the registry without being added to the list would otherwise be silently
        // dropped from the prompt, which is the kind of omission that reads as "the value was never
        // needed".
        for ((key, value) in values) {
            if (!ordered.containsKey(key)) ordered[key] = value
        }
        return ordered
    }
}

/**
 * Stops a relocated section's own text from closing its envelope.
 *
 * The outer `<provider_runtime_context>` boundary is already defended by
 * `escapeProviderRuntimeContextBoundaries`. This is the same rule one level in: section content can
 * be authored content — a lorebook entry, a mode injection — so it must not be able to manufacture
 * a closing tag for the element it is being placed inside.
 */
internal fun escapeRuntimeSectionBoundaries(value: String): String =
    Regex("<(?=\\s*/?\\s*runtime_context_section\\b)", RegexOption.IGNORE_CASE)
        .replace(value) { "\\u003c" }
