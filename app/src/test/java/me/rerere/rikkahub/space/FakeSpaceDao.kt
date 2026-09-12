package me.rerere.rikkahub.space

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [SpaceDao] that reproduces the constraints the real schema enforces.
 *
 * The behaviours that matter for correctness are enforced HERE as well as in Room, because the
 * tests must fail if the repository starts relying on something the database does not actually
 * guarantee:
 *  - the likes table is keyed by `(post_id, actor_kind, actor_id)`, so a repeat insert returns -1
 *    (which is how the repository detects a duplicate);
 *  - the notifications table is keyed by `notification_id`, likewise.
 *
 * Without those, a test would pass against a fake that silently allowed duplicates the real
 * database rejects.
 */
class FakeSpaceDao : SpaceDao {
    val posts = LinkedHashMap<String, SpacePostEntity>()
    /** Keyed exactly like the composite primary key of `space_likes`. */
    val likes = LinkedHashMap<Triple<String, String, String>, SpaceLikeEntity>()
    val comments = LinkedHashMap<String, SpaceCommentEntity>()
    val notifications = LinkedHashMap<String, SpaceNotificationEntity>()

    private val unreadSignals = MutableStateFlow(0)

    override suspend fun insertPost(entity: SpacePostEntity) {
        check(posts.put(entity.postId, entity) == null) { "duplicate post_id" }
    }

    override suspend fun getPost(postId: String): SpacePostEntity? = posts[postId]

    override suspend fun listPosts(limit: Int): List<SpacePostEntity> =
        posts.values.sortedWith(POST_ORDER).take(limit)

    override suspend fun listPostsBefore(
        beforeCreatedAtMs: Long,
        beforePostId: String,
        limit: Int,
    ): List<SpacePostEntity> = posts.values
        .filter { it.createdAtMs < beforeCreatedAtMs || (it.createdAtMs == beforeCreatedAtMs && it.postId < beforePostId) }
        .sortedWith(POST_ORDER)
        .take(limit)

    override suspend fun listPostsByAuthor(
        authorKind: String,
        authorId: String,
        limit: Int,
    ): List<SpacePostEntity> = posts.values
        .filter { it.authorKind == authorKind && it.authorId == authorId }
        .sortedWith(POST_ORDER)
        .take(limit)

    override suspend fun insertLike(entity: SpaceLikeEntity): Long {
        val key = Triple(entity.postId, entity.actorKind, entity.actorId)
        if (likes.containsKey(key)) return -1L
        likes[key] = entity
        return 1L
    }

    override suspend fun deleteLike(postId: String, actorKind: String, actorId: String) {
        likes.remove(Triple(postId, actorKind, actorId))
    }

    override suspend fun hasLike(postId: String, actorKind: String, actorId: String): Boolean =
        likes.containsKey(Triple(postId, actorKind, actorId))

    override suspend fun likeCount(postId: String): Int =
        likes.values.count { it.postId == postId }

    override suspend fun listLikers(postId: String, limit: Int): List<SpaceLikeEntity> =
        likes.values.filter { it.postId == postId }
            .sortedWith(compareByDescending<SpaceLikeEntity> { it.createdAtMs }.thenByDescending { it.actorId })
            .take(limit)

    override suspend fun insertComment(entity: SpaceCommentEntity) {
        check(comments.put(entity.commentId, entity) == null) { "duplicate comment_id" }
    }

    override suspend fun getComment(commentId: String): SpaceCommentEntity? = comments[commentId]

    override suspend fun listComments(postId: String, limit: Int): List<SpaceCommentEntity> =
        comments.values.filter { it.postId == postId }
            .sortedWith(compareBy<SpaceCommentEntity> { it.createdAtMs }.thenBy { it.commentId })
            .take(limit)

    override suspend fun commentCount(postId: String): Int =
        comments.values.count { it.postId == postId }

    override suspend fun insertNotification(entity: SpaceNotificationEntity): Long {
        if (notifications.containsKey(entity.notificationId)) return -1L
        notifications[entity.notificationId] = entity
        unreadSignals.value = notifications.values.count { it.readAtMs == null }
        return 1L
    }

    override suspend fun listNotifications(
        recipientKind: String,
        recipientId: String,
        limit: Int,
    ): List<SpaceNotificationEntity> = notifications.values
        .filter { it.recipientKind == recipientKind && it.recipientId == recipientId }
        .sortedWith(NOTIFICATION_ORDER)
        .take(limit)

    override suspend fun listNotificationsBefore(
        recipientKind: String,
        recipientId: String,
        beforeCreatedAtMs: Long,
        beforeNotificationId: String,
        limit: Int,
    ): List<SpaceNotificationEntity> = notifications.values
        .filter { it.recipientKind == recipientKind && it.recipientId == recipientId }
        .filter {
            it.createdAtMs < beforeCreatedAtMs ||
                (it.createdAtMs == beforeCreatedAtMs && it.notificationId < beforeNotificationId)
        }
        .sortedWith(NOTIFICATION_ORDER)
        .take(limit)

    override fun observeUnreadCount(recipientKind: String, recipientId: String): Flow<Int> =
        unreadSignals.map { _ ->
            notifications.values.count {
                it.recipientKind == recipientKind && it.recipientId == recipientId && it.readAtMs == null
            }
        }

    override suspend fun markNotificationsRead(
        recipientKind: String,
        recipientId: String,
        notificationIds: List<String>,
        readAtMs: Long,
    ): Int {
        var updated = 0
        notificationIds.forEach { id ->
            val existing = notifications[id] ?: return@forEach
            if (existing.recipientKind != recipientKind || existing.recipientId != recipientId) return@forEach
            if (existing.readAtMs != null) return@forEach
            notifications[id] = existing.copy(readAtMs = readAtMs)
            updated++
        }
        unreadSignals.value = notifications.values.count { it.readAtMs == null }
        return updated
    }

    override suspend fun claimNotificationForConsumption(
        notificationId: String,
        consumedAtMs: Long,
    ): Int {
        val existing = notifications[notificationId] ?: return 0
        if (existing.consumedAtMs != null) return 0
        notifications[notificationId] = existing.copy(consumedAtMs = consumedAtMs)
        return 1
    }

    override suspend fun getNotification(notificationId: String): SpaceNotificationEntity? =
        notifications[notificationId]

    private companion object {
        val POST_ORDER =
            compareByDescending<SpacePostEntity> { it.createdAtMs }.thenByDescending { it.postId }
        val NOTIFICATION_ORDER =
            compareByDescending<SpaceNotificationEntity> { it.createdAtMs }
                .thenByDescending { it.notificationId }
    }
}
