package me.rerere.rikkahub.space

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Space persistence.
 *
 * Every listing query is cursor-bounded and capped. Nothing here returns an unbounded history:
 * these rows are read into model context by `space_list_posts` / `space_list_notifications`, so
 * an unbounded query would be a token-budget bug as much as a latency one.
 *
 * Ordering is `(created_at_ms DESC, <id> DESC)` and the cursor carries both parts, so pages are
 * stable when several rows share a millisecond.
 */
@Dao
interface SpaceDao {

    // ── Posts ────────────────────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPost(entity: SpacePostEntity)

    @Query("SELECT * FROM space_posts WHERE post_id = :postId LIMIT 1")
    suspend fun getPost(postId: String): SpacePostEntity?

    @Query(
        "SELECT * FROM space_posts " +
            "ORDER BY created_at_ms DESC, post_id DESC LIMIT :limit",
    )
    suspend fun listPosts(limit: Int): List<SpacePostEntity>

    @Query(
        "SELECT * FROM space_posts " +
            "WHERE created_at_ms < :beforeCreatedAtMs " +
            "OR (created_at_ms = :beforeCreatedAtMs AND post_id < :beforePostId) " +
            "ORDER BY created_at_ms DESC, post_id DESC LIMIT :limit",
    )
    suspend fun listPostsBefore(
        beforeCreatedAtMs: Long,
        beforePostId: String,
        limit: Int,
    ): List<SpacePostEntity>

    @Query(
        "SELECT * FROM space_posts WHERE author_kind = :authorKind AND author_id = :authorId " +
            "ORDER BY created_at_ms DESC, post_id DESC LIMIT :limit",
    )
    suspend fun listPostsByAuthor(
        authorKind: String,
        authorId: String,
        limit: Int,
    ): List<SpacePostEntity>

    @Query(
        "SELECT * FROM space_posts WHERE author_kind = :authorKind AND author_id = :authorId " +
            "AND (created_at_ms < :beforeCreatedAtMs " +
            "OR (created_at_ms = :beforeCreatedAtMs AND post_id < :beforePostId)) " +
            "ORDER BY created_at_ms DESC, post_id DESC LIMIT :limit",
    )
    suspend fun listPostsByAuthorBefore(
        authorKind: String,
        authorId: String,
        beforeCreatedAtMs: Long,
        beforePostId: String,
        limit: Int,
    ): List<SpacePostEntity>

    /**
     * Deletes the post row. Dependent likes, comments and notifications go with it through the
     * declared `ON DELETE CASCADE` foreign keys — the schema already expresses that a post's
     * reactions have no meaning without it, and Room enables constraint enforcement on every
     * connection it opens, so no explicit child sweep is needed here.
     *
     * Returns the number of parent rows removed, so a caller can tell a real delete from a no-op.
     */
    @Query("DELETE FROM space_posts WHERE post_id = :postId")
    suspend fun deletePost(postId: String): Int

    /**
     * Every post one author published — the assistant-footprint sweep.
     *
     * `ON DELETE CASCADE` fires per row for a multi-row delete exactly as it does for a single-row
     * one, so the likes, comments and notifications on these posts go too. No index is added for
     * this: the declared `(author_kind, author_id, created_at_ms)` index already covers both
     * predicate columns, and this runs once per assistant deletion rather than per render.
     */
    @Query("DELETE FROM space_posts WHERE author_kind = :authorKind AND author_id = :authorId")
    suspend fun deletePostsByAuthor(authorKind: String, authorId: String): Int

    // ── Likes ────────────────────────────────────────────────────────────────────────────────

    /**
     * Idempotent by construction: the composite primary key makes a repeat like a no-op rather
     * than a duplicate row, so `likeCount` cannot be inflated by repeated calls.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLike(entity: SpaceLikeEntity): Long

    @Query(
        "DELETE FROM space_likes WHERE post_id = :postId " +
            "AND actor_kind = :actorKind AND actor_id = :actorId",
    )
    suspend fun deleteLike(postId: String, actorKind: String, actorId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM space_likes WHERE post_id = :postId AND actor_kind = :actorKind AND actor_id = :actorId)")
    suspend fun hasLike(postId: String, actorKind: String, actorId: String): Boolean

    @Query("SELECT COUNT(*) FROM space_likes WHERE post_id = :postId")
    suspend fun likeCount(postId: String): Int

    @Query(
        "SELECT * FROM space_likes WHERE post_id = :postId " +
            "ORDER BY created_at_ms DESC, actor_id DESC LIMIT :limit",
    )
    suspend fun listLikers(postId: String, limit: Int): List<SpaceLikeEntity>

    /**
     * Every like one actor left, on any post — the assistant-footprint sweep. Same reasoning as
     * [deleteCommentsByAuthor]: a like on somebody else's post outlives the liker's own posts.
     */
    @Query("DELETE FROM space_likes WHERE actor_kind = :actorKind AND actor_id = :actorId")
    suspend fun deleteLikesByActor(actorKind: String, actorId: String): Int

    // ── Comments ─────────────────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertComment(entity: SpaceCommentEntity)

    @Query("SELECT * FROM space_comments WHERE comment_id = :commentId LIMIT 1")
    suspend fun getComment(commentId: String): SpaceCommentEntity?

    @Query(
        "SELECT * FROM space_comments WHERE post_id = :postId " +
            "ORDER BY created_at_ms ASC, comment_id ASC LIMIT :limit",
    )
    suspend fun listComments(postId: String, limit: Int): List<SpaceCommentEntity>

    /**
     * The next page of a comment thread, in the same oldest-first order as [listComments].
     *
     * The cursor is `(created_at_ms, comment_id)` and is applied as a strict "after", because the
     * thread reads forwards. Both halves are required: comments routinely share a millisecond, and
     * a time-only cursor would skip or repeat every one of them at a page boundary.
     */
    @Query(
        "SELECT * FROM space_comments WHERE post_id = :postId " +
            "AND (created_at_ms > :afterCreatedAtMs " +
            "OR (created_at_ms = :afterCreatedAtMs AND comment_id > :afterCommentId)) " +
            "ORDER BY created_at_ms ASC, comment_id ASC LIMIT :limit",
    )
    suspend fun listCommentsAfter(
        postId: String,
        afterCreatedAtMs: Long,
        afterCommentId: String,
        limit: Int,
    ): List<SpaceCommentEntity>

    @Query("SELECT COUNT(*) FROM space_comments WHERE post_id = :postId")
    suspend fun commentCount(postId: String): Int

    /**
     * Every comment one author left, on any post — the assistant-footprint sweep.
     *
     * A comment on somebody ELSE's post does not go with the author's own posts, so the cascade
     * cannot reach it and it has to be deleted in its own right.
     */
    @Query("DELETE FROM space_comments WHERE author_kind = :authorKind AND author_id = :authorId")
    suspend fun deleteCommentsByAuthor(authorKind: String, authorId: String): Int

    // ── Notifications ────────────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotification(entity: SpaceNotificationEntity): Long

    @Query(
        "SELECT * FROM space_notifications " +
            "WHERE recipient_kind = :recipientKind AND recipient_id = :recipientId " +
            "ORDER BY created_at_ms DESC, notification_id DESC LIMIT :limit",
    )
    suspend fun listNotifications(
        recipientKind: String,
        recipientId: String,
        limit: Int,
    ): List<SpaceNotificationEntity>

    @Query(
        "SELECT * FROM space_notifications " +
            "WHERE recipient_kind = :recipientKind AND recipient_id = :recipientId " +
            "AND (created_at_ms < :beforeCreatedAtMs " +
            "OR (created_at_ms = :beforeCreatedAtMs AND notification_id < :beforeNotificationId)) " +
            "ORDER BY created_at_ms DESC, notification_id DESC LIMIT :limit",
    )
    suspend fun listNotificationsBefore(
        recipientKind: String,
        recipientId: String,
        beforeCreatedAtMs: Long,
        beforeNotificationId: String,
        limit: Int,
    ): List<SpaceNotificationEntity>

    @Query(
        "SELECT COUNT(*) FROM space_notifications " +
            "WHERE recipient_kind = :recipientKind AND recipient_id = :recipientId " +
            "AND read_at_ms IS NULL",
    )
    fun observeUnreadCount(recipientKind: String, recipientId: String): Flow<Int>

    /**
     * Marks exactly the named rows read and leaves every other row untouched — there is no
     * "mark everything read" query, so a mis-scoped call cannot silently clear a real inbox.
     */
    @Query(
        "UPDATE space_notifications SET read_at_ms = :readAtMs " +
            "WHERE recipient_kind = :recipientKind AND recipient_id = :recipientId " +
            "AND notification_id IN (:notificationIds) AND read_at_ms IS NULL",
    )
    suspend fun markNotificationsRead(
        recipientKind: String,
        recipientId: String,
        notificationIds: List<String>,
        readAtMs: Long,
    ): Int

    /**
     * Exactly-once claim. Returns 1 only for the caller that observed `consumed_at_ms` as NULL, so
     * a notification can never drive two fire attempts even if the event is redelivered or replayed
     * after a restart.
     *
     * This is exactly-once *claiming*, not exactly-once execution: the flip happens before the run
     * is handed off, so a process that dies in between loses that fire rather than repeating it.
     */
    @Query(
        "UPDATE space_notifications SET consumed_at_ms = :consumedAtMs " +
            "WHERE notification_id = :notificationId AND consumed_at_ms IS NULL",
    )
    suspend fun claimNotificationForConsumption(
        notificationId: String,
        consumedAtMs: Long,
    ): Int

    @Query("SELECT * FROM space_notifications WHERE notification_id = :notificationId LIMIT 1")
    suspend fun getNotification(notificationId: String): SpaceNotificationEntity?

    /**
     * Every notification naming one identity, in either direction — the assistant-footprint sweep.
     *
     * Both halves are load-bearing, and they cover different rows:
     *  - as ACTOR, the notifications an identity's vanished like or comment produced on OTHER
     *    identities' posts. Those posts are still there, so the cascade never reaches these rows,
     *    and a surviving one would both announce a deleted author and point its `comment_id` at a
     *    comment that no longer exists.
     *  - as RECIPIENT, the identity's own inbox. Usually already empty by the time this runs — a
     *    notification is addressed to the post's author, and those posts have just been deleted —
     *    but swept anyway so the guarantee stays true without resting on that invariant.
     *
     * The two `OR` arms are parenthesised, so the `kind`/`id` pair cannot be mixed across
     * directions (an actor match on one arm and a recipient match on the other would otherwise be
     * satisfiable by two different identities).
     */
    @Query(
        "DELETE FROM space_notifications " +
            "WHERE (recipient_kind = :kind AND recipient_id = :id) " +
            "OR (actor_kind = :kind AND actor_id = :id)",
    )
    suspend fun deleteNotificationsInvolving(kind: String, id: String): Int

    /**
     * Unconsumed notifications for one recipient, oldest first — the restart/replay scan.
     *
     * `consumed_at_ms IS NULL` is the whole point: a row in this result was never handed to a
     * workflow, whether because the process was dead, no family was bound, or the recipient had no
     * matching workflow at the time. Rows already claimed never come back, so replay can only ever
     * re-present work that was genuinely missed.
     *
     * Bounded by both `sinceMs` (a caller-chosen window) and `limit`; there is deliberately no
     * unbounded form, for the same token/latency reason as every other listing here. Served by the
     * existing `(recipient_kind, recipient_id, created_at_ms)` index.
     */
    @Query(
        "SELECT * FROM space_notifications " +
            "WHERE recipient_kind = :recipientKind AND recipient_id = :recipientId " +
            "AND consumed_at_ms IS NULL AND created_at_ms >= :sinceMs " +
            "ORDER BY created_at_ms ASC, notification_id ASC LIMIT :limit",
    )
    suspend fun listPendingNotifications(
        recipientKind: String,
        recipientId: String,
        sinceMs: Long,
        limit: Int,
    ): List<SpaceNotificationEntity>
}
