package me.rerere.rikkahub.space

/**
 * Why an identity is allowed to delete a Cat Garden post.
 *
 * Two authorities, and no third. Naming them separately is the point: the local user's power is
 * a moderator power, not an ownership one, and collapsing the two into a single "is allowed"
 * boolean is exactly how a moderator grant would later get reused as a general write permission.
 */
sealed interface SpacePostDeletionAuthority {
    /** The actor published the post. Assistants only ever get this one. */
    data object Author : SpacePostDeletionAuthority

    /** The local user, who moderates the space and may delete any post. */
    data object Moderator : SpacePostDeletionAuthority
}

/**
 * Who may delete which Cat Garden post.
 *
 *  - the local user may delete ANY post, as the space's moderator;
 *  - an assistant may delete ONLY a post it published itself;
 *  - every other combination is refused.
 *
 * This is deliberately NOT a relaxed ownership check. Ownership still decides the assistant case
 * exactly as strictly as before (`kind` AND `id`, never "close enough"), and the moderator case is
 * a separate branch that only the canonical local user can reach. An assistant holding an id that
 * merely looks like the user's — or a `USER` actor that is not the local user sentinel — matches
 * neither branch and fails closed.
 *
 * The repository consults this before every delete, and the Cat Garden screen consults it to decide
 * whether to offer the delete entry. One rule, so what the screen shows and what the database
 * enforces cannot drift apart.
 */
object SpacePostDeletionPolicy {

    fun authorityFor(actor: SpaceActor, post: SpacePostEntity): SpacePostDeletionAuthority? = when {
        actor.isLocalUser -> SpacePostDeletionAuthority.Moderator
        actor.kind == SpaceActorKind.ASSISTANT &&
            post.authorKind == actor.kindValue &&
            post.authorId == actor.id -> SpacePostDeletionAuthority.Author

        else -> null
    }
}

/**
 * True only for the local user sentinel.
 *
 * Compared as the whole `(kind, id)` pair: a `USER` actor carrying some other id is not the local
 * user and gets no moderator power.
 */
val SpaceActor.isLocalUser: Boolean
    get() = kind == SpaceActorKind.USER && id == LOCAL_USER_ID
