package me.rerere.rikkahub.space

import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * The acting identity for a Space write.
 *
 * This type is the ONLY way an author/actor reaches the database. It is constructed either from
 * trusted runtime context ([fromAssistant]) or from the local UI ([USER]) — never from
 * model-supplied tool arguments. No `space_*` tool declares an `author_id`/`actor_id` parameter,
 * so there is nothing for a model to forge even if it tried.
 */
data class SpaceActor(
    val kind: SpaceActorKind,
    val id: String,
) {
    val kindValue: String get() = kind.name

    companion object {
        /**
         * Stable key for the local user. A sentinel, not the display nickname: the nickname is
         * user-editable, so keying history on it would rewrite the past when it changes.
         */
        const val LOCAL_USER_ID: String = "local_user"

        val USER: SpaceActor = SpaceActor(SpaceActorKind.USER, LOCAL_USER_ID)

        /**
         * Resolve the acting assistant from trusted runtime state.
         *
         * `callerAssistantId` is supplied by the tool invocation context, which the runtime owns.
         * Returns null when no assistant identity is available, so callers fail closed rather
         * than attributing content to the user or to an arbitrary assistant.
         */
        fun fromAssistant(callerAssistantId: String?): SpaceActor? =
            callerAssistantId
                ?.takeIf { it.isNotBlank() }
                ?.let { SpaceActor(SpaceActorKind.ASSISTANT, it) }

        /** Rebuild an actor from stored columns. Unknown kinds fail closed to null. */
        fun fromStored(kind: String?, id: String?): SpaceActor? {
            val resolvedKind = SpaceActorKind.entries.firstOrNull { it.name == kind } ?: return null
            val resolvedId = id?.takeIf { it.isNotBlank() } ?: return null
            return SpaceActor(resolvedKind, resolvedId)
        }
    }
}

/**
 * Display information for a Space actor, resolved at render time.
 *
 * Assistant name and avatar are read from the live [Assistant] list rather than stored per post,
 * so an assistant's identity has exactly one source of truth and renaming it does not fork its
 * Space profile. Deleted assistants keep their posts; they simply render with the fallback.
 */
data class SpaceProfile(
    val actor: SpaceActor,
    val displayName: String,
    val avatar: me.rerere.rikkahub.data.model.Avatar,
    val isDeleted: Boolean,
)

/**
 * Resolve display data for an actor.
 *
 * No localised fallback name is supplied here on purpose: this is called from ViewModels, which
 * have no resources. A deleted assistant comes back as a blank name with [SpaceProfile.isDeleted]
 * set, and the UI decides what to show — the same way a user actor comes back blank for the UI to
 * fill in from the current nickname.
 */
fun resolveSpaceProfile(
    actor: SpaceActor,
    assistants: List<Assistant>,
): SpaceProfile = when (actor.kind) {
    SpaceActorKind.USER -> SpaceProfile(
        actor = actor,
        displayName = "",
        avatar = me.rerere.rikkahub.data.model.Avatar.Dummy,
        isDeleted = false,
    )

    SpaceActorKind.ASSISTANT -> {
        val assistantId = runCatching { Uuid.parse(actor.id) }.getOrNull()
        val assistant = assistantId?.let { id -> assistants.firstOrNull { it.id == id } }
        if (assistant == null) {
            SpaceProfile(
                actor = actor,
                displayName = "",
                avatar = me.rerere.rikkahub.data.model.Avatar.Dummy,
                isDeleted = true,
            )
        } else {
            SpaceProfile(
                actor = actor,
                displayName = assistant.name,
                avatar = assistant.avatar,
                isDeleted = false,
            )
        }
    }
}
