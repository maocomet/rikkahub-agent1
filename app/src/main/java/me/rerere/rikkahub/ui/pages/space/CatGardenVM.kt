package me.rerere.rikkahub.ui.pages.space

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.space.SpaceActor
import me.rerere.rikkahub.space.SpaceCommentEntity
import me.rerere.rikkahub.space.SpaceCursor
import me.rerere.rikkahub.space.SpaceIdentitySource
import me.rerere.rikkahub.space.SpaceNotificationEntity
import me.rerere.rikkahub.space.SpacePostEntity
import me.rerere.rikkahub.space.SpaceProfile
import me.rerere.rikkahub.space.SpaceRepository
import me.rerere.rikkahub.space.SpaceWriteOutcome
import me.rerere.rikkahub.space.resolveSpaceProfile

data class SpaceCommentView(
    val comment: SpaceCommentEntity,
    val author: SpaceProfile,
)

data class SpaceFeedItem(
    val post: SpacePostEntity,
    val author: SpaceProfile,
    val likeCount: Int,
    val commentCount: Int,
    val likedByViewer: Boolean,
    val likerNames: List<String>,
    val comments: List<SpaceCommentView>,
)

data class SpaceNotificationView(
    val notification: SpaceNotificationEntity,
    val actor: SpaceProfile,
    val postPreview: String?,
)

/**
 * Cat Garden's read model.
 *
 * The viewer is always the local user: assistants act through their own tools, and this screen is
 * the person's side of the space. Nothing here writes as an assistant.
 *
 * Pages are fetched explicitly rather than observed as Room Flows, because the listing DAO queries
 * are suspend and cursor-bounded by design — a flow that emitted the whole table would be as much
 * a memory problem as a token one.
 */
class CatGardenVM(
    private val spaceRepository: SpaceRepository,
    private val identitySource: SpaceIdentitySource,
    /**
     * Injectable so the read model can be exercised on the JVM. Defaults to the ViewModel's own
     * scope; tests pass an unconfined scope, which also keeps `viewModelScope` (and therefore
     * `Dispatchers.Main`, unavailable in a plain unit test) untouched.
     */
    private val injectedScope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = injectedScope ?: viewModelScope

    private val viewer: SpaceActor = SpaceActor.USER

    private val _feed = MutableStateFlow<List<SpaceFeedItem>>(emptyList())
    val feed: StateFlow<List<SpaceFeedItem>> = _feed.asStateFlow()

    private val _mine = MutableStateFlow<List<SpaceFeedItem>>(emptyList())
    val mine: StateFlow<List<SpaceFeedItem>> = _mine.asStateFlow()

    private val _notifications = MutableStateFlow<List<SpaceNotificationView>>(emptyList())
    val notifications: StateFlow<List<SpaceNotificationView>> = _notifications.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The person's own display identity, so their posts and likes render like anyone else's. */
    val userNickname: StateFlow<String> = identitySource.nickname
        .stateIn(workScope, SharingStarted.Eagerly, "")

    val userAvatar: StateFlow<Avatar> = identitySource.avatar
        .stateIn(workScope, SharingStarted.Eagerly, Avatar.Dummy)

    private var feedCursor: SpaceCursor? = null
    private var feedExhausted = false

    init {
        workScope.launch {
            spaceRepository.observeUnreadCount(viewer).collect { _unreadCount.value = it }
        }
        refreshAll()
    }

    fun refreshAll() {
        workScope.launch {
            reloadFeed()
            reloadMine()
            reloadNotifications()
        }
    }

    fun loadMoreFeed() = workScope.launch { appendFeedPage() }

    // ── Feed / mine ──────────────────────────────────────────────────────────────────────────

    private suspend fun reloadFeed() {
        feedCursor = null
        feedExhausted = false
        _feed.value = emptyList()
        appendFeedPage()
    }

    private suspend fun appendFeedPage() {
        if (feedExhausted) return
        val assistants = currentAssistants()
        val page = feedCursor
            ?.let { spaceRepository.listPostsBefore(it, PAGE_SIZE) }
            ?: spaceRepository.listPosts(PAGE_SIZE)
        if (page.size < PAGE_SIZE) feedExhausted = true
        if (page.isEmpty()) return

        feedCursor = page.last().let { SpaceCursor(it.createdAtMs, it.postId) }
        val items = page.map { toFeedItem(it, assistants) }
        _feed.value = _feed.value + items
    }

    private suspend fun reloadMine() {
        val assistants = currentAssistants()
        val page = spaceRepository.listPostsByAuthor(viewer, PAGE_SIZE)
        _mine.value = page.map { toFeedItem(it, assistants) }
    }

    private suspend fun reloadNotifications() {
        val assistants = currentAssistants()
        val page = spaceRepository.listNotifications(viewer, PAGE_SIZE)
        _notifications.value = page.map { notification ->
            SpaceNotificationView(
                notification = notification,
                actor = SpaceActor.fromStored(notification.actorKind, notification.actorId)
                    ?.let { resolveSpaceProfile(it, assistants) }
                    ?: resolveSpaceProfile(SpaceActor.USER, assistants),
                postPreview = spaceRepository.getPost(notification.postId)?.content,
            )
        }
        _unreadCount.value = page.count { it.readAtMs == null }
    }

    private suspend fun toFeedItem(post: SpacePostEntity, assistants: List<Assistant>): SpaceFeedItem {
        val authorActor = SpaceActor.fromStored(post.authorKind, post.authorId) ?: SpaceActor.USER
        val likerNames = spaceRepository.listLikers(post.postId, LIKER_PREVIEW)
            .mapNotNull { like -> SpaceActor.fromStored(like.actorKind, like.actorId) }
            .map { actor -> resolveSpaceProfile(actor, assistants).displayName }
            .filter { it.isNotBlank() }
        val comments = spaceRepository.listComments(post.postId, COMMENT_PREVIEW).map { comment ->
            val commenter = SpaceActor.fromStored(comment.authorKind, comment.authorId)
                ?: SpaceActor.USER
            SpaceCommentView(comment = comment, author = resolveSpaceProfile(commenter, assistants))
        }
        return SpaceFeedItem(
            post = post,
            author = resolveSpaceProfile(authorActor, assistants),
            likeCount = spaceRepository.likeCount(post.postId),
            commentCount = spaceRepository.commentCount(post.postId),
            likedByViewer = spaceRepository.hasLike(viewer, post.postId),
            likerNames = likerNames,
            comments = comments,
        )
    }

    private suspend fun currentAssistants(): List<Assistant> = identitySource.assistants()

    // ── Mutations (all as the local user) ────────────────────────────────────────────────────

    fun toggleLike(postId: String, liked: Boolean) = workScope.launch {
        spaceRepository.setLike(viewer, postId, liked, originDepth = 0)
        reloadFeed()
        reloadMine()
    }

    fun createPost(content: String, onResult: (Boolean) -> Unit = {}) = workScope.launch {
        _busy.value = true
        val outcome = spaceRepository.createPost(viewer, content, originDepth = 0)
        _busy.value = false
        reloadFeed()
        reloadMine()
        onResult(outcome is SpaceWriteOutcome.Created)
    }

    fun createComment(postId: String, content: String) = workScope.launch {
        _busy.value = true
        spaceRepository.createComment(viewer, postId, content, originDepth = 0)
        _busy.value = false
        reloadFeed()
        reloadMine()
    }

    fun markNotificationsRead(ids: List<String>) = workScope.launch {
        spaceRepository.markNotificationsRead(viewer, ids)
        reloadNotifications()
    }

    fun markAllVisibleRead() = workScope.launch {
        val unread = _notifications.value
            .filter { it.notification.readAtMs == null }
            .map { it.notification.notificationId }
        if (unread.isEmpty()) return@launch
        spaceRepository.markNotificationsRead(viewer, unread)
        reloadNotifications()
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val LIKER_PREVIEW = 3
        const val COMMENT_PREVIEW = 2
    }
}

/** Tab identity for the Cat Garden screen. */
enum class CatGardenTab {
    FEED,
    MINE,
    NOTIFICATIONS,
}
