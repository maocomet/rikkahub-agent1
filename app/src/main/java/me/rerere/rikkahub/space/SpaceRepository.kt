package me.rerere.rikkahub.space

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** Result of a Space write that may or may not have produced a notification. */
sealed interface SpaceWriteOutcome {
    data class Created(val id: String) : SpaceWriteOutcome
    data class AlreadyApplied(val id: String) : SpaceWriteOutcome
    data class Rejected(val code: String, val message: String) : SpaceWriteOutcome
}

/**
 * Space data access with the identity and anti-recursion rules applied.
 *
 * Every mutating entry point takes the acting [SpaceActor] as a parameter and never reads one out
 * of caller-supplied data, so the only way to write as an assistant is to already hold a trusted
 * assistant identity.
 */
class SpaceRepository(
    private val dao: SpaceDao,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { Uuid.random().toString() },
) {

    // ── Posts ────────────────────────────────────────────────────────────────────────────────

    suspend fun createPost(actor: SpaceActor, content: String, originDepth: Int): SpaceWriteOutcome {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return SpaceWriteOutcome.Rejected("EMPTY_CONTENT", "Post content is empty.")
        if (trimmed.length > MAX_POST_CHARS) {
            return SpaceWriteOutcome.Rejected(
                "CONTENT_TOO_LONG",
                "Post content exceeds $MAX_POST_CHARS characters.",
            )
        }
        val id = newId()
        dao.insertPost(
            SpacePostEntity(
                postId = id,
                authorKind = actor.kindValue,
                authorId = actor.id,
                content = trimmed,
                createdAtMs = nowMs(),
                originDepth = originDepth.coerceAtLeast(0),
            ),
        )
        return SpaceWriteOutcome.Created(id)
    }

    suspend fun getPost(postId: String): SpacePostEntity? = dao.getPost(postId)

    suspend fun listPosts(limit: Int): List<SpacePostEntity> =
        dao.listPosts(limit.coerceIn(1, MAX_PAGE_SIZE))

    suspend fun listPostsBefore(before: SpaceCursor, limit: Int): List<SpacePostEntity> =
        dao.listPostsBefore(before.createdAtMs, before.id, limit.coerceIn(1, MAX_PAGE_SIZE))

    suspend fun listPostsByAuthor(actor: SpaceActor, limit: Int): List<SpacePostEntity> =
        dao.listPostsByAuthor(actor.kindValue, actor.id, limit.coerceIn(1, MAX_PAGE_SIZE))

    // ── Likes ────────────────────────────────────────────────────────────────────────────────

    /**
     * Idempotent set-like. Returns [SpaceWriteOutcome.AlreadyApplied] when the actor had already
     * liked the post, so a repeated call neither duplicates the row nor re-notifies the author.
     */
    suspend fun setLike(
        actor: SpaceActor,
        postId: String,
        liked: Boolean,
        originDepth: Int,
    ): SpaceWriteOutcome {
        val post = dao.getPost(postId)
            ?: return SpaceWriteOutcome.Rejected("POST_NOT_FOUND", "No post with that id.")

        if (!liked) {
            dao.deleteLike(postId, actor.kindValue, actor.id)
            return SpaceWriteOutcome.Created(postId)
        }

        val inserted = dao.insertLike(
            SpaceLikeEntity(
                postId = postId,
                actorKind = actor.kindValue,
                actorId = actor.id,
                createdAtMs = nowMs(),
            ),
        )
        if (inserted == -1L) return SpaceWriteOutcome.AlreadyApplied(postId)

        notify(
            recipient = SpaceActor.fromStored(post.authorKind, post.authorId),
            actor = actor,
            type = SpaceNotificationType.LIKE,
            postId = postId,
            commentId = null,
            originDepth = originDepth,
        )
        return SpaceWriteOutcome.Created(postId)
    }

    suspend fun hasLike(actor: SpaceActor, postId: String): Boolean =
        dao.hasLike(postId, actor.kindValue, actor.id)

    suspend fun likeCount(postId: String): Int = dao.likeCount(postId)

    suspend fun listLikers(postId: String, limit: Int): List<SpaceLikeEntity> =
        dao.listLikers(postId, limit.coerceIn(1, MAX_PAGE_SIZE))

    // ── Comments ─────────────────────────────────────────────────────────────────────────────

    suspend fun createComment(
        actor: SpaceActor,
        postId: String,
        content: String,
        originDepth: Int,
    ): SpaceWriteOutcome {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return SpaceWriteOutcome.Rejected("EMPTY_CONTENT", "Comment content is empty.")
        if (trimmed.length > MAX_COMMENT_CHARS) {
            return SpaceWriteOutcome.Rejected(
                "CONTENT_TOO_LONG",
                "Comment content exceeds $MAX_COMMENT_CHARS characters.",
            )
        }
        val post = dao.getPost(postId)
            ?: return SpaceWriteOutcome.Rejected("POST_NOT_FOUND", "No post with that id.")

        val id = newId()
        dao.insertComment(
            SpaceCommentEntity(
                commentId = id,
                postId = postId,
                authorKind = actor.kindValue,
                authorId = actor.id,
                content = trimmed,
                createdAtMs = nowMs(),
                originDepth = originDepth.coerceAtLeast(0),
            ),
        )
        notify(
            recipient = SpaceActor.fromStored(post.authorKind, post.authorId),
            actor = actor,
            type = SpaceNotificationType.COMMENT,
            postId = postId,
            commentId = id,
            originDepth = originDepth,
        )
        return SpaceWriteOutcome.Created(id)
    }

    suspend fun listComments(postId: String, limit: Int): List<SpaceCommentEntity> =
        dao.listComments(postId, limit.coerceIn(1, MAX_PAGE_SIZE))

    suspend fun commentCount(postId: String): Int = dao.commentCount(postId)

    // ── Notifications ────────────────────────────────────────────────────────────────────────

    suspend fun listNotifications(actor: SpaceActor, limit: Int): List<SpaceNotificationEntity> =
        dao.listNotifications(actor.kindValue, actor.id, limit.coerceIn(1, MAX_PAGE_SIZE))

    suspend fun listNotificationsBefore(
        actor: SpaceActor,
        before: SpaceCursor,
        limit: Int,
    ): List<SpaceNotificationEntity> = dao.listNotificationsBefore(
        actor.kindValue,
        actor.id,
        before.createdAtMs,
        before.id,
        limit.coerceIn(1, MAX_PAGE_SIZE),
    )

    fun observeUnreadCount(actor: SpaceActor): Flow<Int> =
        dao.observeUnreadCount(actor.kindValue, actor.id)

    /** Marks only the caller's own notifications, and only the ids it named. */
    suspend fun markNotificationsRead(actor: SpaceActor, notificationIds: List<String>): Int {
        val ids = notificationIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return 0
        return dao.markNotificationsRead(actor.kindValue, actor.id, ids, nowMs())
    }

    /**
     * Claims a notification for exactly-once consumption. Returns true only for the single caller
     * that flipped `consumed_at_ms` from NULL, so a redelivered event cannot double-fire.
     */
    suspend fun claimNotificationForConsumption(notificationId: String): Boolean =
        dao.claimNotificationForConsumption(notificationId, nowMs()) == 1

    suspend fun getNotification(notificationId: String): SpaceNotificationEntity? =
        dao.getNotification(notificationId)

    // ── Notification emission ────────────────────────────────────────────────────────────────

    /**
     * Records a notification for [recipient], unless the acting identity IS the recipient.
     *
     * Self-actions produce no row at all. That single rule removes the most direct feedback path
     * (an actor reacting to its own post) before any depth or cooldown logic is consulted.
     *
     * The id is derived from the action rather than generated, so a repeat of the same logical
     * action (re-liking after unliking) collapses onto the existing row instead of producing a
     * fresh notification to trigger on.
     *
     * [originDepth] records how far this was from a user action: 0 when the user started it, and
     * parent + 1 when an automation run produced it. Consumers refuse to wake a workflow for
     * depth >= 1, which is what bounds assistant-to-assistant recursion.
     */
    private suspend fun notify(
        recipient: SpaceActor?,
        actor: SpaceActor,
        type: SpaceNotificationType,
        postId: String,
        commentId: String?,
        originDepth: Int,
    ) {
        if (recipient == null) return
        if (recipient.kind == actor.kind && recipient.id == actor.id) return

        val stableKey = listOf(type.name, postId, commentId.orEmpty(), actor.kindValue, actor.id)
            .joinToString("|")
        dao.insertNotification(
            SpaceNotificationEntity(
                notificationId = "space:$stableKey",
                recipientKind = recipient.kindValue,
                recipientId = recipient.id,
                actorKind = actor.kindValue,
                actorId = actor.id,
                type = type.name,
                postId = postId,
                commentId = commentId,
                createdAtMs = nowMs(),
                originDepth = (originDepth + 1).coerceAtLeast(0),
            ),
        )
    }

    companion object {
        const val MAX_PAGE_SIZE: Int = 50
        const val MAX_POST_CHARS: Int = 4000
        const val MAX_COMMENT_CHARS: Int = 1000
    }
}

/**
 * A stable pagination cursor: `(created_at_ms, id)`.
 *
 * Both parts are required. Several rows routinely share a millisecond, and a time-only cursor
 * would either skip or repeat them at page boundaries.
 */
data class SpaceCursor(
    val createdAtMs: Long,
    val id: String,
)
