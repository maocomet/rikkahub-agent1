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
 * The ONE causal-depth contract for every Cat Garden row.
 *
 * `origin_depth` answers a single question on every table that carries it — posts, comments and
 * notifications alike: *how many automation hops separate this row from the person's own action?*
 *
 *  - [USER_INITIATED] (0) — the row records the person's own action. Nothing stood between them
 *    and it.
 *  - [AUTOMATION_DRIVEN] (1) and above — the row was produced by an automation run
 *    (`WorkflowEngine`), which by construction is at least one hop from the person.
 *
 * The depth of a write is a property OF THAT WRITE, so it is stored verbatim. A notification
 * produced by the person's like is depth 0, exactly like the like itself — it is not "one deeper"
 * than the thing that caused it, because the notification IS the record of that action, not a
 * further consequence of it. Only a *new* write performed by an automation run raises the depth,
 * and that write's own depth is what it stores.
 *
 * [mayWakeWorkflow] is the anti-recursion rule built on top of it: only a notification that
 * records the person's own action may wake a workflow. An automation run writes at depth >= 1
 * ([me.rerere.rikkahub.space.createSpaceTools] stamps headless runs that way), so the notification
 * its write produces can never wake another run. That is what makes assistant-to-assistant
 * ping-pong terminate at one hop rather than merely become unlikely.
 */
object SpaceCausalDepth {
    /** The row is the person's own action: nothing ran on their behalf. */
    const val USER_INITIATED: Int = 0

    /**
     * The row was produced by an automation run. The exact value only ever grows if a chain is
     * extended by hand — the guard below stops every chain at this first hop, so depth 2 is
     * unreachable through the production paths and exists only as a defensive ceiling.
     */
    const val AUTOMATION_DRIVEN: Int = 1

    /**
     * Whether a notification stored at [depth] may wake a workflow. Fail closed: anything that is
     * not provably the person's own action is refused, including a negative or unexpected value.
     */
    fun mayWakeWorkflow(depth: Int): Boolean = depth <= USER_INITIATED
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
     * Automation hops between the person and this post — see [SpaceCausalDepth]. 0 when the person
     * published it, >= 1 when an automation run did.
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
    /** Automation hops between the person and this comment — see [SpaceCausalDepth]. */
    @ColumnInfo(name = "origin_depth")
    val originDepth: Int,
)

/**
 * A durable notification addressed to one identity.
 *
 * Durable rather than in-memory on purpose: the in-app event bus is a `SharedFlow` with no replay,
 * so an event emitted while the process is dead would be lost forever. A row survives.
 *
 * Three columns carry the delivery contract:
 *  - `recipient_kind`/`recipient_id` — who this notification is FOR. A workflow may only act on a
 *    notification addressed to its own authoring assistant, so an assistant that happens to share
 *    a trigger type with another one is never woken by that one's mail.
 *  - `origin_depth` — [SpaceCausalDepth] of the write that produced this row. Only depth
 *    [SpaceCausalDepth.USER_INITIATED] may wake a workflow, which is what makes
 *    assistant-to-assistant ping-pong provably terminate.
 *  - `consumed_at_ms` — the exactly-once claim. A consumer only acts when its conditional update
 *    flips this from NULL. That is exactly-once *claiming*: one notification drives at most one
 *    fire attempt, even across redelivery and restart replay. It is deliberately NOT a claim of
 *    exactly-once *execution* — the claim is taken before the run is handed off, so a crash in
 *    between loses that fire. Delivery is at-most-once.
 *
 * Rows are never deleted by consumption. An unconsumed row is replayed (bounded) when the trigger
 * family next binds, which is what makes the durable row — rather than the in-process event — the
 * thing that actually guarantees the event is not lost.
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
