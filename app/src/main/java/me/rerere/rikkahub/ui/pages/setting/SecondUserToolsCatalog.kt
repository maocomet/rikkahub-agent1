package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import java.util.Locale
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.SecondUserToolAllowlist
import me.rerere.rikkahub.owner.OwnerToolFamily

/**
 * Central row catalogue for the "Second-user tools" page.
 *
 * It is the single source of truth that maps every LocalToolOption / OwnerToolFamily to a
 * localized (English + Chinese) title/description resource, plus an optional "recommended"
 * badge. New resource names follow the stable wire identity:
 *   local: `second_user_tools_local_<wire token>_title|_desc`
 *   owner: `second_user_tools_owner_<name_lowercase>_title|_desc`
 * Unknown/newly-added families fall back to a generic label instead of crashing the page.
 */
object SecondUserToolsCatalog {

    data class LocalEntry(
        val option: LocalToolOption,
        val token: String,
    )

    data class OwnerEntry(
        val family: OwnerToolFamily,
        val name: String,
        val recommended: Boolean,
    )

    /** "建议保持开启" but never hard-locked. */
    val RECOMMENDED_FAMILIES: Set<OwnerToolFamily> = setOf(
        OwnerToolFamily.SAFETY,
        OwnerToolFamily.RUN,
        OwnerToolFamily.DOCTOR,
    )

    /** Every implemented privileged LocalToolOption, in canonical (surface) order. */
    val localEntries: List<LocalEntry> =
        SecondUserToolAllowlist.canonicalLocalOptions.map { option ->
            LocalEntry(option = option, token = SecondUserToolAllowlist.tokenOf(option))
        }

    /** Every OwnerToolFamily, in registry order. */
    val ownerEntries: List<OwnerEntry> =
        SecondUserToolAllowlist.canonicalOwnerFamilies.map { family ->
            OwnerEntry(family = family, name = family.name, recommended = family in RECOMMENDED_FAMILIES)
        }

    fun localTitle(context: Context, entry: LocalEntry): String =
        resolveString(context, "second_user_tools_local_${entry.token}_title", R.string.second_user_tools_fallback_title)

    fun localDescription(context: Context, entry: LocalEntry): String =
        resolveString(context, "second_user_tools_local_${entry.token}_desc", R.string.second_user_tools_fallback_desc)

    fun ownerTitle(context: Context, entry: OwnerEntry): String =
        resolveString(context, "second_user_tools_owner_${entry.name.lowercase(Locale.ROOT)}_title", R.string.second_user_tools_fallback_title)

    fun ownerDescription(context: Context, entry: OwnerEntry): String =
        resolveString(context, "second_user_tools_owner_${entry.name.lowercase(Locale.ROOT)}_desc", R.string.second_user_tools_fallback_desc)

    /** Resolve a resource by its generated name; fall back if the id is unknown. */
    private fun resolveString(context: Context, resourceName: String, fallbackRes: Int): String {
        val id = context.resources.getIdentifier(resourceName, "string", context.packageName)
        return context.getString(if (id != 0) id else fallbackRes)
    }
}
