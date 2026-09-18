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
        if (!SpaceCausalDepth.isValid(originDepth)) return invalidOriginDepth(originDepth)
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
                originDepth = originDepth,
            ),
        )
        return SpaceWriteOutcome.Created(id)
    }

    suspend fun getPost(postId: String): SpacePostEntity? = dao.getPost(postId)

    /** The timeline page preceding [before], or the first page when [before] is null. */
    suspend fun listPostsPage(before: SpaceCursor?, limit: Int): SpacePage<SpacePostEntity> =
        pageOf(limit) { probe ->
            if (before == null) {
                dao.listPosts(probe)
            } else {
                dao.listPostsBefore(before.createdAtMs, before.id, probe)
            }
        }

    /** The same page shape, narrowed to one author's posts — what the "Mine" tab reads. */
    suspend fun listPostsByAuthorPage(
        actor: SpaceActor,
        before: SpaceCursor?,
        limit: Int,
    ): SpacePage<SpacePostEntity> = pageOf(limit) { probe ->
        if (before == null) {
            dao.listPostsByAuthor(actor.kindValue, actor.id, probe)
        } else {
            dao.listPostsByAuthorBefore(actor.kindValue, actor.id, before.createdAtMs, before.id, probe)
        }
    }

    /**
     * Removes a post the acting identity published, or refuses.
     *
     * Ownership is decided by comparing the stored author against the [actor] the RUNTIME supplied
     * — never against anything read out of the request. There is no parameter anywhere above this
     * that names an author, so an assistant can no more delete the user's post than it can publish
     * as them: the only way to delete as an identity is to already be that identity.
     *
     * Refusals are distinct codes on purpose. `POST_NOT_FOUND` and `NOT_POST_OWNER` are different
     * facts, and collapsing them into one "no" would make an ownership failure indistinguishable
     * from a stale id.
     *
     * Likes, comments and notifications on the post are removed by the `ON DELETE CASCADE` foreign
     * keys already declared in the schema, so this stays a single statement.
     */
    suspend fun deletePost(actor: SpaceActor, postId: String): SpaceWriteOutcome {
        val post = dao.getPost(postId)
            ?: return SpaceWriteOutcome.Rejected("POST_NOT_FOUND", "No post with that id.")
        if (post.authorKind != actor.kindValue || post.authorId != actor.id) {
            return SpaceWriteOutcome.Rejected(
                "NOT_POST_OWNER",
                "Only the identity that published a post may delete it.",
            )
        }
        dao.deletePost(postId)
        return SpaceWriteOutcome.Created(postId)
    }

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
        // Validated on BOTH like branches, including the unlike one that emits no notification: a
        // negative depth is an out-of-contract argument, and accepting it here but refusing it one
        // branch over is the kind of asymmetry that later reads as an oversight.
        if (!SpaceCausalDepth.isValid(originDepth)) return invalidOriginDepth(originDepth)
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
        if (!SpaceCausalDepth.isValid(originDepth)) return invalidOriginDepth(originDepth)
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
                originDepth = originDepth,
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

    suspend fun getComment(commentId: String): SpaceCommentEntity? = dao.getComment(commentId)

    /**
     * The page of a comment thread that follows [after], in the same oldest-first order the thread
     * reads in. The cursor is `(created_at_ms, comment_id)` — see [SpaceCursor]. A null [after]
     * starts at the oldest comment.
     */
    suspend fun listCommentsPage(
        postId: String,
        after: SpaceCursor?,
        limit: Int,
    ): SpacePage<SpaceCommentEntity> = pageOf(limit) { probe ->
        if (after == null) {
            dao.listComments(postId, probe)
        } else {
            dao.listCommentsAfter(postId, after.createdAtMs, after.id, probe)
        }
    }

    suspend fun commentCount(postId: String): Int = dao.commentCount(postId)

    // ── Notifications ────────────────────────────────────────────────────────────────────────

    /** The notification page preceding [before], newest first, or the first page when null. */
    suspend fun listNotificationsPage(
        actor: SpaceActor,
        before: SpaceCursor?,
        limit: Int,
    ): SpacePage<SpaceNotificationEntity> = pageOf(limit) { probe ->
        if (before == null) {
            dao.listNotifications(actor.kindValue, actor.id, probe)
        } else {
            dao.listNotificationsBefore(
                actor.kindValue,
                actor.id,
                before.createdAtMs,
                before.id,
                probe,
            )
        }
    }

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

    /**
     * Unconsumed notifications addressed to [recipient], oldest first.
     *
     * This is what a consumer reads back after binding, so a notification created while the process
     * was dead — or while no trigger family was registered — is still delivered when one returns.
     * The row, not the in-process signal, is the source of truth.
     */
    suspend fun listPendingNotifications(
        recipient: SpaceActor,
        sinceMs: Long,
        limit: Int,
    ): List<SpaceNotificationEntity> = dao.listPendingNotifications(
        recipient.kindValue,
        recipient.id,
        sinceMs,
        limit.coerceIn(1, MAX_PAGE_SIZE),
    )

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
     * [originDepth] is the depth of the action that produced this notification, stored verbatim —
     * see [SpaceCausalDepth] for the contract. It is deliberately NOT incremented here: the
     * notification is the record of the acting write, not a further hop beyond it, so a like by the
     * person yields a depth-0 notification exactly like the like row itself. Only a write performed
     * by an automation run carries depth >= 1, and only its producer can raise that.
     *
     * A consumer refuses to wake a workflow for anything but depth 0, which is what bounds
     * assistant-to-assistant recursion.
     *
     * An out-of-contract depth writes NO ROW rather than being clamped. This is the boundary that
     * matters most: this method is the only writer of the table the trigger reads, so a negative
     * depth repaired into 0 here would hand the trigger a wake-capable row that no legitimate
     * producer could have created. The public entry points already refuse such a depth, so reaching
     * this line means a caller bypassed them — and the answer to that is to write nothing.
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
        if (!SpaceCausalDepth.isValid(originDepth)) return

        val stableKey = listOf(type.name, postId, commentId.orEmpty(), actor.kindValue, actor.id)
            .joinToString("|")
        val entity = SpaceNotificationEntity(
            notificationId = "space:$stableKey",
            recipientKind = recipient.kindValue,
            recipientId = recipient.id,
            actorKind = actor.kindValue,
            actorId = actor.id,
            type = type.name,
            postId = postId,
            commentId = commentId,
            createdAtMs = nowMs(),
            originDepth = originDepth,
        )
        val inserted = dao.insertNotification(entity)
        // Announce only a row that was actually created: a repeat of the same action collapses
        // onto the existing id, and waking a workflow for it would be a duplicate event.
        if (inserted != -1L) {
            SpaceNotificationDispatcher.onCreated(entity)
        }
    }

    /**
     * Reads one row more than [limit] and trims it back.
     *
     * The extra row is the ONLY sound way to answer "is there another page?" here, and it is never
     * returned. The two tempting shortcuts are both wrong:
     *  - `items.size == limit` reports another page whenever a read ends exactly on a boundary, so
     *    a 20-post feed offers a Load More that yields nothing;
     *  - `items.size < total` compares a page against a table-wide count and ignores how far the
     *    cursor has already travelled, so it keeps answering "more" on the last page of any
     *    multi-page thread.
     *
     * A caller builds its next cursor from `items.last()`, never from the probe row: the probe is
     * the FIRST row of the next page, so treating it as the last of this one would skip it.
     */
    private suspend fun <T> pageOf(
        limit: Int,
        fetch: suspend (probeSize: Int) -> List<T>,
    ): SpacePage<T> {
        val size = limit.coerceIn(1, MAX_PAGE_SIZE)
        val rows = fetch(size + 1)
        return SpacePage(items = rows.take(size), hasMore = rows.size > size)
    }

    companion object {
        const val MAX_PAGE_SIZE: Int = 50
        const val MAX_POST_CHARS: Int = 4000
        const val MAX_COMMENT_CHARS: Int = 1000

        /**
         * An out-of-contract `origin_depth`, refused rather than repaired.
         *
         * The depth is quoted back so a caller can see what it sent, and the message names the
         * contract rather than only the symptom — the tempting "fix" for this rejection is to clamp
         * the value at the call site, which is the very thing the contract forbids.
         */
        fun invalidOriginDepth(depth: Int): SpaceWriteOutcome.Rejected = SpaceWriteOutcome.Rejected(
            "INVALID_ORIGIN_DEPTH",
            "origin_depth $depth is out of contract: it must be " +
                "${SpaceCausalDepth.USER_INITIATED} for the person's own action or >= " +
                "${SpaceCausalDepth.AUTOMATION_DRIVEN} for an automation run. It is never clamped.",
        )
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

/**
 * One cursor-bounded page, plus a verdict on whether another page exists that is PROVEN rather
 * than inferred.
 *
 * [hasMore] comes from a lookahead read — see `pageOf`. Every paged read in this repository returns
 * this type, so there is exactly one place where "is there more?" is decided and exactly one shape
 * a caller can consume. A raw `List` return would leave each caller to invent its own answer, which
 * is how the `size == limit` bug was able to appear in four separate surfaces at once.
 *
 * [items] is at most the requested limit, and its last element is the one a next-page cursor must
 * be built from.
 */
data class SpacePage<T>(
    val items: List<T>,
    val hasMore: Boolean,
)
