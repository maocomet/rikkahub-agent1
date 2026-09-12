package me.rerere.rikkahub.space

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Who performed a Space action. Stored alongside a stable id so a `user` actor can never be
 * confused with an assistant whose id happens to look similar.
 *
 * The id is resolved by trusted runtime context at write time
 * ([SpaceActor.fromAssistant]) and is never accepted from model-supplied tool arguments.
 */
enum class SpaceActorKind {
    USER,
    ASSISTANT,
}

enum class SpaceNotificationType {
    LIKE,
    COMMENT,
}

/**
 * A Cat Garden post.
 *
 * `author_kind`/`author_id` are the acting identity. They are deliberately NOT a foreign key to
 * any assistant row: an assistant can be deleted while its posts, and the comments and likes
 * other identities left on them, must survive. Display name and avatar are resolved from the
 * live assistant list at render time, so there is exactly one source of truth for an assistant's
 * identity and no duplicated profile data.
 */
@Entity(
    tableName = "space_posts",
    indices = [
        Index(value = ["created_at_ms"]),
        Index(value = ["author_kind", "author_id", "created_at_ms"]),
    ],
)
data class SpacePostEntity(
    @PrimaryKey
    @ColumnInfo(name = "post_id")
    val postId: String,
    @ColumnInfo(name = "author_kind")
    val authorKind: String,
    @ColumnInfo(name = "author_id")
    val authorId: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    /**
     * Causal distance from the user action that started this content: 0 for user-initiated work,
     * parent + 1 when produced by an automation run. Consumed by the notification loop guard.
     */
    @ColumnInfo(name = "origin_depth")
    val originDepth: Int,
)

/**
 * A like, keyed by `(post_id, actor_kind, actor_id)`.
 *
 * The composite primary key IS the uniqueness guarantee: liking twice is an `INSERT OR IGNORE`
 * that changes nothing, and unlike is a delete. Idempotency therefore lives in the schema rather
 * than in UI behaviour or model discipline.
 */
@Entity(
    tableName = "space_likes",
    // A composite key must be declared here rather than with several @PrimaryKey annotations.
    primaryKeys = ["post_id", "actor_kind", "actor_id"],
    foreignKeys = [
        ForeignKey(
            entity = SpacePostEntity::class,
            parentColumns = ["post_id"],
            childColumns = ["post_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Required, not redundant: Room demands an explicit index covering a foreign-key child
        // column and will not accept the composite primary key's leftmost column as one. Without
        // it every parent delete scans this table.
        Index(value = ["post_id"]),
        Index(value = ["actor_kind", "actor_id", "created_at_ms"]),
    ],
)
data class SpaceLikeEntity(
    @ColumnInfo(name = "post_id")
    val postId: String,
    @ColumnInfo(name = "actor_kind")
    val actorKind: String,
    @ColumnInfo(name = "actor_id")
    val actorId: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

/** A first-level comment. Replies to comments are deliberately out of scope for v1. */
@Entity(
    tableName = "space_comments",
    foreignKeys = [
        ForeignKey(
            entity = SpacePostEntity::class,
            parentColumns = ["post_id"],
            childColumns = ["post_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["post_id", "created_at_ms"]),
        Index(value = ["author_kind", "author_id", "created_at_ms"]),
    ],
)
data class SpaceCommentEntity(
    @PrimaryKey
    @ColumnInfo(name = "comment_id")
    val commentId: String,
    @ColumnInfo(name = "post_id")
    val postId: String,
    @ColumnInfo(name = "author_kind")
    val authorKind: String,
    @ColumnInfo(name = "author_id")
    val authorId: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "origin_depth")
    val originDepth: Int,
)

/**
 * A durable notification addressed to one identity.
 *
 * Durable rather than in-memory on purpose: the in-app event bus is a `SharedFlow` with no replay,
 * so an event emitted while the process is dead would be lost forever. A row survives.
 *
 * Two columns carry the anti-recursion contract:
 *  - `origin_depth` — 0 for user-originated work. Notifications with depth >= 1 must not wake a
 *    workflow, which is what makes assistant-to-assistant ping-pong provably terminate.
 *  - `consumed_at_ms` — the exactly-once claim. A consumer only acts when its conditional update
 *    flips this from NULL, so one notification can never fire a workflow twice.
 */
@Entity(
    tableName = "space_notifications",
    foreignKeys = [
        ForeignKey(
            entity = SpacePostEntity::class,
            parentColumns = ["post_id"],
            childColumns = ["post_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recipient_kind", "recipient_id", "created_at_ms"]),
        Index(value = ["recipient_kind", "recipient_id", "read_at_ms"]),
        Index(value = ["post_id"]),
    ],
)
data class SpaceNotificationEntity(
    @PrimaryKey
    @ColumnInfo(name = "notification_id")
    val notificationId: String,
    @ColumnInfo(name = "recipient_kind")
    val recipientKind: String,
    @ColumnInfo(name = "recipient_id")
    val recipientId: String,
    @ColumnInfo(name = "actor_kind")
    val actorKind: String,
    @ColumnInfo(name = "actor_id")
    val actorId: String,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "post_id")
    val postId: String,
    @ColumnInfo(name = "comment_id")
    val commentId: String? = null,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "origin_depth")
    val originDepth: Int,
    @ColumnInfo(name = "read_at_ms")
    val readAtMs: Long? = null,
    @ColumnInfo(name = "consumed_at_ms")
    val consumedAtMs: Long? = null,
)
