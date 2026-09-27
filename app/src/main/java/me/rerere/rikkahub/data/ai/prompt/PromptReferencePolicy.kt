package me.rerere.rikkahub.data.ai.prompt

import me.rerere.rikkahub.data.ai.transformers.escapeXmlText

/**
 * The registry of placeholder keys the app itself writes into a system prompt.
 *
 * ## Why a closed registry rather than "whatever the resolver happens to know"
 *
 * A Claude P session is continued later by a remote transport, and the system instruction is part
 * of the identity it continues under. So every placeholder the app puts in a system prompt has to
 * be answerable for: either it is *provably stable* for a given assistant, in which case it can be
 * substituted in place and the system bytes stay fixed, or it is *not*, in which case its value
 * must travel elsewhere and the system keeps only a reference to it.
 *
 * What is **not** allowed is the third option — treating an unknown key as stable by default. A key
 * nobody has classified is a key nobody has proved anything about, and the failure it causes is
 * invisible at the time: the system instruction drifts, the session stops being continuable, and
 * nothing reports it. So the table is closed and an unregistered key is refused rather than passed
 * through.
 *
 * ## Where this may be applied
 *
 * The classification is only meaningful for text the **app** composes: the assistant system prompt,
 * the app-generated stable system and tool instructions, and injections the app relocates. It is
 * never applied to user-authored message content, where `{{foo}}` is a literal the user typed and
 * reinterpreting it — or refusing the turn over it — would be the app reading meaning into someone
 * else's text.
 */
enum class PromptReferenceClass {
    /**
     * Determined by assistant configuration alone, so it cannot change between two turns that were
     * served by the same assistant. Substituted in place: the system bytes stay stable because the
     * value itself is stable, and rewriting it to a reference would only make the prompt harder to
     * read for no gain.
     */
    STABLE,

    /**
     * Determined by the current turn, the device, or the wall clock. The system prompt keeps a
     * fixed reference and the value travels in this turn's runtime context, because any literal
     * value here would change the system bytes and break continuation.
     */
    DYNAMIC,
}

/** Why a placeholder reference could not be turned into a stable system prompt. A closed set. */
enum class PromptReferenceRejection {
    /** A `{{key}}`-shaped token naming a key this build does not classify. */
    UNCLASSIFIED,

    /** A registered key has no value available at this point in the request. */
    UNRESOLVED,

    /** The same key was resolved to two different values in one request. */
    CONFLICT,
}

/**
 * Thrown before a request is dispatched. Carries a closed reason and the offending **key**, never
 * the surrounding text: the text is a system prompt, and an exception message travels.
 *
 * The key is safe to carry because it is a token from a closed vocabulary — a name like `cur_date`,
 * never anything a user wrote.
 */
class PromptReferenceException(
    val reason: PromptReferenceRejection,
    val key: String,
) : IllegalArgumentException("prompt_reference_${reason.name.lowercase()}:$key")

/** The result of neutralising one app-controlled template. */
data class NeutralizedPromptTemplate(
    /**
     * The text with every dynamic placeholder replaced by its fixed reference.
     *
     * This is what the system message carries: byte-identical across turns for a given assistant,
     * because nothing turn-dependent survives into it.
     */
    val text: String,
    /**
     * The dynamic keys the template referred to, in the registry's fixed order.
     *
     * Returned rather than re-scanned later so that "which values does this turn owe" is answered by
     * the same pass that rewrote the text, and cannot disagree with it. The order is the registry's
     * rather than the text's, so the list is a function of the key *set* and two templates that use
     * the same keys report them identically.
     */
    val referencedKeys: List<String>,
)

/**
 * The one definition of what the stable-system reference syntax is.
 *
 * A marker is deliberately **not** the original `{{key}}` token. Two reasons, and both matter:
 * the raw token is what the substitution pass recognises, so leaving it would invite a later pass to
 * resolve it back into the system message; and the brief this implements requires the model to be
 * handed a resolved value rather than a template it is being asked to interpret. So the system
 * prompt says "a value belongs here, under this name", and the runtime context says what the value
 * is.
 */
object PromptReferencePolicy {

    /** `<runtime_value_ref name="cur_date"/>` — attribute-quoted, self-closing, fixed shape. */
    const val MARKER_PREFIX: String = "<runtime_value_ref name=\""
    const val MARKER_SUFFIX: String = "\"/>"

    /**
     * The classification table. Closed: see the type's documentation for why an unlisted key is a
     * refusal rather than a default.
     */
    private val CLASSIFICATIONS: Map<String, PromptReferenceClass> = linkedMapOf(
        // Assistant configuration. `char` is the assistant's own name, which is the same on every
        // turn served by that assistant.
        "char" to PromptReferenceClass.STABLE,

        // Wall clock.
        "cur_date" to PromptReferenceClass.DYNAMIC,
        "cur_time" to PromptReferenceClass.DYNAMIC,
        "cur_datetime" to PromptReferenceClass.DYNAMIC,

        // Per-request model selection. Dynamic even though it rarely changes: the M3 continuation
        // identity deliberately excludes the model alias, so a user switching models mid-thread must
        // not invalidate the session, and a literal model name in the system prompt would do exactly
        // that.
        "model_id" to PromptReferenceClass.DYNAMIC,
        "model_name" to PromptReferenceClass.DYNAMIC,

        // Device state. Stable for long stretches, but not *provably* stable between two turns, and
        // the rule is about proof rather than about how often a value happens to change.
        "locale" to PromptReferenceClass.DYNAMIC,
        "timezone" to PromptReferenceClass.DYNAMIC,
        "system_version" to PromptReferenceClass.DYNAMIC,
        "device_info" to PromptReferenceClass.DYNAMIC,
        "battery_level" to PromptReferenceClass.DYNAMIC,

        // The user's own nickname, which is editable at any moment.
        "nickname" to PromptReferenceClass.DYNAMIC,
        "user" to PromptReferenceClass.DYNAMIC,
    )

    /**
     * The order the runtime-values block is emitted in.
     *
     * A fixed list rather than the map's iteration order, so the block is a function of *which*
     * values are present and not of how the map happened to be built. Two requests that resolve the
     * same values produce the same bytes even if one of them recorded them in a different order.
     */
    val CANONICAL_KEY_ORDER: List<String> = listOf(
        "cur_date",
        "cur_time",
        "cur_datetime",
        "model_id",
        "model_name",
        "locale",
        "timezone",
        "system_version",
        "device_info",
        "battery_level",
        "nickname",
        "user",
    )

    /** Every key this build classifies. A key outside it is refused where the app composes text. */
    val REGISTERED_KEYS: Set<String> = CLASSIFICATIONS.keys

    fun classificationOf(key: String): PromptReferenceClass? = CLASSIFICATIONS[key]

    fun isDynamic(key: String): Boolean = CLASSIFICATIONS[key] == PromptReferenceClass.DYNAMIC

    /** The fixed reference the system prompt keeps in place of a dynamic value. */
    fun markerFor(key: String): String =
        MARKER_PREFIX + escapeXmlAttribute(key) + MARKER_SUFFIX

    /**
     * The value of a [PromptReferenceClass.STABLE] key, resolved from assistant configuration alone.
     *
     * This is the **single** definition of what a stable key resolves to. The placeholder registry
     * delegates to it rather than restating it, because two definitions of one key is exactly how
     * the frozen expectation and the message the app builds start disagreeing — and that
     * disagreement would surface as a refused turn rather than as the duplicate it is.
     *
     * Returns `null` for a key that is not stable, so a caller cannot accidentally resolve a dynamic
     * key into the system prompt.
     */
    fun stableValueOf(key: String, assistantName: String): String? = when (key) {
        "char" -> assistantName.ifBlank { "assistant" }
        else -> null
    }

    /**
     * A `{{key}}`-shaped token, which is the only shape that can be *refused*.
     *
     * Bounded deliberately. The single-brace form is also understood by the placeholder engine, but
     * a lone `{...}` is ordinary prose and JSON, so scanning for unknown single-brace tokens would
     * refuse perfectly good system prompts. An unknown key is therefore only a refusal when it is
     * written in the unambiguous double-brace form; single-brace text is left exactly as it was.
     */
    private val DOUBLE_BRACE_TOKEN = Regex("\\{\\{([A-Za-z0-9_]{1,64})\\}\\}")

    /**
     * Rewrites an app-controlled template into its **stable** form: dynamic keys become fixed
     * references, stable keys are substituted in place.
     *
     * @param stableValueOf the resolved value of a [PromptReferenceClass.STABLE] key, or `null` when
     *   this request has none. A stable key with no value is a refusal rather than a hole: the system
     *   prompt would otherwise keep a token the model cannot resolve, which is the failure the
     *   reference syntax exists to avoid.
     * @throws PromptReferenceException when a token is unclassified or a stable key is unresolved.
     */
    fun neutralizeAppControlledTemplate(
        text: String,
        stableValueOf: (String) -> String?,
    ): NeutralizedPromptTemplate {
        val referenced = LinkedHashSet<String>()
        val rewritten = rewrite(text) { key, classification ->
            when (classification) {
                PromptReferenceClass.DYNAMIC -> {
                    referenced.add(key)
                    markerFor(key)
                }

                PromptReferenceClass.STABLE -> stableValueOf(key)
                    ?: throw PromptReferenceException(PromptReferenceRejection.UNRESOLVED, key)
            }
        }
        return NeutralizedPromptTemplate(text = rewritten, referencedKeys = referenced.toList())
    }

    /**
     * Rewrites an app-controlled template into its **concrete** form: every registered key becomes
     * its value, dynamic ones included.
     *
     * This is the form the runtime context uses. It is the same rewrite as
     * [neutralizeAppControlledTemplate] with a different replacement — which is the point: a
     * relocated injection carries real values into the turn, and leaving a `{{cur_date}}` in text
     * the app composed would hand the model a template nothing will ever resolve.
     *
     * @throws PromptReferenceException when a token is unclassified or a key has no value.
     */
    fun resolveAppControlledTemplate(
        text: String,
        valueOf: (String) -> String?,
    ): String = rewrite(text) { key, _ ->
        valueOf(key) ?: throw PromptReferenceException(PromptReferenceRejection.UNRESOLVED, key)
    }

    /**
     * Resolves the **values** a runtime context owes for the dynamic keys a template referred to.
     *
     * Separate from the rewrites on purpose: the system prompt is rewritten once, from the app's
     * layout, while the values are resolved where the platform inputs actually live. A key
     * resolves through `resolve`, and a key that resolves to nothing is a refusal — a reference in
     * the system prompt whose value never arrives is a hole in the prompt.
     *
     * @throws PromptReferenceException when a key is unclassified or has no value.
     */
    fun resolveDynamicValues(
        keys: Collection<String>,
        resolve: (String) -> String?,
    ): Map<String, String> {
        val resolved = LinkedHashMap<String, String>()
        for (key in keys) {
            val classification = CLASSIFICATIONS[key]
                ?: throw PromptReferenceException(PromptReferenceRejection.UNCLASSIFIED, key)
            if (classification != PromptReferenceClass.DYNAMIC) continue
            resolved[key] = resolve(key)
                ?: throw PromptReferenceException(PromptReferenceRejection.UNRESOLVED, key)
        }
        return resolved
    }

    /**
     * The shared rewrite. Refuses first, then replaces, then re-checks the result.
     *
     * The order is the argument. Refusing *first* — before any replacement runs — means the refusal
     * never depends on which key happened to be rewritten before the offending token was reached.
     * Re-checking the *result* closes the other hole: a substituted **value** can itself contain a
     * placeholder-shaped token (an assistant named `{{cur_date}}`, a memory that quotes one), and a
     * value substituted after its own key was already passed would leave that token in the output
     * with nothing left to resolve it.
     */
    private fun rewrite(
        text: String,
        replacementFor: (String, PromptReferenceClass) -> String,
    ): String {
        for (match in DOUBLE_BRACE_TOKEN.findAll(text)) {
            val key = match.groupValues[1]
            if (!REGISTERED_KEYS.contains(key)) {
                throw PromptReferenceException(PromptReferenceRejection.UNCLASSIFIED, key)
            }
        }

        var result = text
        for (key in REGISTERED_KEYS) {
            val token = "{{$key}}"
            if (!result.contains(token) && !result.contains("{$key}")) continue

            val replacement = replacementFor(key, CLASSIFICATIONS.getValue(key))

            // A replacement must not carry a token of its own. Checked here rather than left to the
            // end-of-rewrite check for a reason that is easy to miss: whether a value's token
            // survives depends on whether its own key was already passed, which is the registry's
            // declaration order. Refusing here makes the outcome a property of the value instead of
            // a property of how the table happens to be written down.
            val introduced = DOUBLE_BRACE_TOKEN.find(replacement)
            if (introduced != null) {
                val inner = introduced.groupValues[1]
                throw PromptReferenceException(
                    if (REGISTERED_KEYS.contains(inner)) {
                        PromptReferenceRejection.UNRESOLVED
                    } else {
                        PromptReferenceRejection.UNCLASSIFIED
                    },
                    inner,
                )
            }

            // Double-brace first: `{{key}}` contains `{key}` as a substring, and replacing the
            // longer form first is what stops the second pass from rewriting the replacement's own
            // text if it happens to contain braces. The placeholder engine relies on the same
            // ordering.
            result = result.replace(token, replacement).replace("{$key}", replacement)
        }

        val survivor = DOUBLE_BRACE_TOKEN.find(result)
        if (survivor != null) {
            val key = survivor.groupValues[1]
            throw PromptReferenceException(
                if (REGISTERED_KEYS.contains(key)) {
                    PromptReferenceRejection.UNRESOLVED
                } else {
                    PromptReferenceRejection.UNCLASSIFIED
                },
                key,
            )
        }

        return result
    }

    /**
     * Escapes a value for an attribute or element body.
     *
     * Built on the project's existing text escaper and widened for quoting, so a value containing
     * `"` cannot terminate the `name=` attribute and a value containing a closing tag cannot end the
     * block early.
     */
    fun escapeXmlAttribute(value: String): String = escapeXmlText(value)
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
