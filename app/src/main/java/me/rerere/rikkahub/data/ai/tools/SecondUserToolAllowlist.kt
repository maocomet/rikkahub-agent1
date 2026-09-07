package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.owner.OwnerToolFamily
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Single source of truth for the SECOND-USER tool allowlist policy.
 *
 * Storage model is deliberately a tolerant string set on [Settings] (`secondUserEnabledLocalToolTokens`
 * and `secondUserEnabledOwnerFamilyNames`) with a THREE-state semantics:
 *
 * | stored value            | meaning                                | future tool families            |
 * |-------------------------|----------------------------------------|---------------------------------|
 * | `null` (key absent)     | follow the current full surface        | automatically enabled           |
 * | `[]`                    | user explicitly disabled everything    | disabled                        |
 * | non-empty set           | explicit allowlist (customised)        | disabled unless re-enabled      |
 *
 * `null` is NEVER collapsed into a snapshot at the storage/read layer. It is mapped to the
 * *current* canonical surface only at the use boundary (see [resolveLocalOptions] /
 * [resolveOwnerFamilies]). That way a user who never opened the Second-user tools page keeps
 * following the current `PRIVILEGED_IMPLEMENTED` / `OwnerToolFamily.entries` even after an
 * unrelated settings write, so newly added tool families appear automatically.
 *
 * Unknown tokens/names are tolerated at decode time (they are plain strings) and dropped here at
 * the use boundary. This file is pure JVM (no Android dependencies) so it is unit-testable.
 */
object SecondUserToolAllowlist {

    /** Current canonical local surface — always resolves to the live `PRIVILEGED_IMPLEMENTED`. */
    val canonicalLocalOptions: List<LocalToolOption> = LocalToolOption.PRIVILEGED_IMPLEMENTED

    /** Current canonical owner surface — always resolves to the live `OwnerToolFamily.entries`. */
    val canonicalOwnerFamilies: List<OwnerToolFamily> = OwnerToolFamily.entries

    private val tokenByOption: Map<LocalToolOption, String> = canonicalLocalOptions.associateWith(::wireToken)
    private val optionByToken: Map<String, LocalToolOption> =
        tokenByOption.entries.associate { (option, token) -> token to option }

    private fun wireToken(option: LocalToolOption): String =
        JsonInstant.encodeToJsonElement(LocalToolOption.serializer(), option)
            .jsonObject.getValue("type").jsonPrimitive.content

    /** Stable wire token of an implemented local option (its `@SerialName`, e.g. "termux"). */
    fun tokenOf(option: LocalToolOption): String = tokenByOption[option]
        ?: error("LocalToolOption $option is not part of the privileged second-user surface")

    /** Inverse lookup of [tokenOf]; null for an unknown/removed token. */
    fun localOptionOf(token: String): LocalToolOption? = optionByToken[token]

    fun ownerFamilyOf(name: String): OwnerToolFamily? =
        canonicalOwnerFamilies.firstOrNull { it.name == name }

    /** The full local default: tokens of every currently-implemented privileged option. */
    fun allLocalOptionTokens(): Set<String> = tokenByOption.values.toSet()

    /** The full owner default: names of every `OwnerToolFamily`. */
    fun allOwnerFamilyNames(): Set<String> = canonicalOwnerFamilies.mapTo(linkedSetOf()) { it.name }

    /**
     * DataStore read helper: returns the stored string set as-is, or `null` when the key is
     * absent / blank / corrupt. Unknown entries are intentionally NOT dropped here (we do not
     * "fix up" settings on read); they are filtered at the use boundary instead.
     */
    fun decodeOptionalStringSet(raw: String?): Set<String>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JsonInstant.decodeFromString<Set<String>>(raw) }.getOrNull()
    }

    /**
     * Resolve the local option surface. `null` → the current full surface (follow default);
     * otherwise the current canonical surface restricted to the explicitly-enabled tokens
     * (unknown tokens dropped, an explicit empty set stays empty = all disabled).
     */
    fun resolveLocalOptions(enabledTokens: Set<String>?): List<LocalToolOption> {
        if (enabledTokens == null) return canonicalLocalOptions
        return canonicalLocalOptions.filter { tokenByOption.getValue(it) in enabledTokens }
    }

    /** Resolve the owner family set. `null` → every family; otherwise only those named. */
    fun resolveOwnerFamilies(enabledNames: Set<String>?): Set<OwnerToolFamily> {
        if (enabledNames == null) return canonicalOwnerFamilies.toSet()
        return canonicalOwnerFamilies.filterTo(linkedSetOf()) { it.name in enabledNames }
    }

    /**
     * Seam used by [me.rerere.rikkahub.service.ChatService]: ordinary assistants keep using
     * their per-assistant [LocalToolOption] list untouched; a privileged second-user surface is
     * restricted to the allowlist (null = current full = unchanged legacy behaviour).
     */
    fun resolveLocalSurfaceTools(
        assistantLocalTools: List<LocalToolOption>,
        privileged: Boolean,
        privilegedEnabledTokens: Set<String>?,
    ): List<LocalToolOption> = if (privileged) {
        resolveLocalOptions(privilegedEnabledTokens)
    } else {
        assistantLocalTools
    }
}
