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
import me.rerere.rikkahub.space.SpaceCausalDepth
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
    /**
     * A short preview only — [commentCount] is the real total. The rest of the thread is read on
     * demand through [CatGardenVM.openComments], deliberately not by growing this list.
     */
    val comments: List<SpaceCommentView>,
)

/**
 * One post's full comment thread, paged.
 *
 * Separate from [SpaceFeedItem] because it has a different job: the card shows a teaser for the
 * timeline, this holds the whole conversation for one post and grows by explicit request.
 */
data class SpaceCommentThread(
    val post: SpacePostEntity,
    val author: SpaceProfile,
    /** The real number of comments on the post, not the number loaded so far. */
    val totalCount: Int,
    val comments: List<SpaceCommentView>,
    val hasMore: Boolean,
    val loadingMore: Boolean = false,
)

data class SpaceNotificationView(
    val notification: SpaceNotificationEntity,
    val actor: SpaceProfile,
    val postPreview: String?,
)

/**
 * True when the local user published [post].
 *
 * The UI asks this to decide whether to offer a delete, and the repository decides the same
 * question again from the runtime actor before performing one. The duplication is intentional and
 * one-directional: this only decides what to SHOW, and a stale answer here can offer a control
 * that the repository then refuses — never the other way round.
 */
fun isOwnedByLocalUser(post: SpacePostEntity): Boolean =
    post.authorKind == SpaceActor.USER.kindValue && post.authorId == SpaceActor.USER.id

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

    /**
     * Whether another page exists. The screen reads this instead of guessing from list size, so it
     * never offers a "load more" that would return nothing.
     */
    private val _feedHasMore = MutableStateFlow(false)
    val feedHasMore: StateFlow<Boolean> = _feedHasMore.asStateFlow()

    private val _mineHasMore = MutableStateFlow(false)
    val mineHasMore: StateFlow<Boolean> = _mineHasMore.asStateFlow()

    /** The open comment thread, or null when the full view is closed. */
    private val _commentThread = MutableStateFlow<SpaceCommentThread?>(null)
    val commentThread: StateFlow<SpaceCommentThread?> = _commentThread.asStateFlow()

    /** The person's own display identity, so their posts and likes render like anyone else's. */
    val userNickname: StateFlow<String> = identitySource.nickname
        .stateIn(workScope, SharingStarted.Eagerly, "")

    val userAvatar: StateFlow<Avatar> = identitySource.avatar
        .stateIn(workScope, SharingStarted.Eagerly, Avatar.Dummy)

    // Feed and mine keep separate cursors and separate exhausted flags. Sharing one would make the
    // Mine tab silently skip the person's older posts as soon as the Feed had been scrolled.
    private var feedCursor: SpaceCursor? = null
    private var feedExhausted = false
    private var mineCursor: SpaceCursor? = null
    private var mineExhausted = false

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

    fun loadMoreMine() = workScope.launch { appendMinePage() }

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
        _feedHasMore.value = !feedExhausted
        if (page.isEmpty()) return

        feedCursor = page.last().let { SpaceCursor(it.createdAtMs, it.postId) }
        val items = page.map { toFeedItem(it, assistants) }
        _feed.value = _feed.value + items
    }

    private suspend fun reloadMine() {
        mineCursor = null
        mineExhausted = false
        _mine.value = emptyList()
        appendMinePage()
    }

    private suspend fun appendMinePage() {
        if (mineExhausted) return
        val assistants = currentAssistants()
        val page = mineCursor
            ?.let { spaceRepository.listPostsByAuthorBefore(viewer, it, PAGE_SIZE) }
            ?: spaceRepository.listPostsByAuthor(viewer, PAGE_SIZE)
        if (page.size < PAGE_SIZE) mineExhausted = true
        _mineHasMore.value = !mineExhausted
        if (page.isEmpty()) return

        mineCursor = page.last().let { SpaceCursor(it.createdAtMs, it.postId) }
        val items = page.map { toFeedItem(it, assistants) }
        _mine.value = _mine.value + items
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
        val comments = spaceRepository.listComments(post.postId, COMMENT_PREVIEW)
            .map { toCommentView(it, assistants) }
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

    private fun toCommentView(
        comment: SpaceCommentEntity,
        assistants: List<Assistant>,
    ): SpaceCommentView {
        val commenter = SpaceActor.fromStored(comment.authorKind, comment.authorId) ?: SpaceActor.USER
        return SpaceCommentView(comment = comment, author = resolveSpaceProfile(commenter, assistants))
    }

    private suspend fun currentAssistants(): List<Assistant> = identitySource.assistants()

    // ── Comment thread ───────────────────────────────────────────────────────────────────────

    /**
     * Opens the full comment view for [postId] and loads its first page.
     *
     * This is what the feed card's comment count and its "N more comments" line both lead to, so
     * there is exactly one answer to "where do I read the rest?".
     */
    fun openComments(postId: String) = workScope.launch {
        val assistants = currentAssistants()
        val post = spaceRepository.getPost(postId) ?: return@launch
        val total = spaceRepository.commentCount(postId)
        val comments = spaceRepository.listComments(postId, COMMENT_PAGE_SIZE)
            .map { toCommentView(it, assistants) }
        _commentThread.value = SpaceCommentThread(
            post = post,
            author = resolveSpaceProfile(
                SpaceActor.fromStored(post.authorKind, post.authorId) ?: viewer,
                assistants,
            ),
            totalCount = total,
            comments = comments,
            hasMore = comments.size < total,
        )
    }

    fun closeComments() {
        _commentThread.value = null
    }

    /** Appends the next page of the open thread; a no-op when there is nothing left to read. */
    fun loadMoreComments() = workScope.launch {
        val thread = _commentThread.value ?: return@launch
        if (!thread.hasMore || thread.loadingMore) return@launch
        // The cursor is built from the last comment already on screen, so a page boundary can never
        // replay a comment the reader has seen or skip one they have not.
        val last = thread.comments.lastOrNull()?.comment ?: return@launch
        _commentThread.value = thread.copy(loadingMore = true)

        val assistants = currentAssistants()
        val page = spaceRepository.listCommentsAfter(
            postId = thread.post.postId,
            after = SpaceCursor(last.createdAtMs, last.commentId),
            limit = COMMENT_PAGE_SIZE,
        )
        val merged = thread.comments + page.map { toCommentView(it, assistants) }
        val total = spaceRepository.commentCount(thread.post.postId)
        // Re-read the thread first: a delete or a close may have landed while this page was loading,
        // and writing `thread` back would resurrect the view the reader already dismissed.
        val current = _commentThread.value ?: return@launch
        if (current.post.postId != thread.post.postId) return@launch
        _commentThread.value = current.copy(
            comments = merged,
            totalCount = total,
            hasMore = merged.size < total,
            loadingMore = false,
        )
    }

    /**
     * Brings the open thread in line with a comment that was just written.
     *
     * The new comment is appended rather than re-fetching the thread, so pages the reader already
     * scrolled through are not thrown away; the total is re-read because other identities may have
     * commented in the meantime and only the count needs to be exact.
     */
    private suspend fun refreshOpenThread(postId: String, newCommentId: String?) {
        val thread = _commentThread.value ?: return
        if (thread.post.postId != postId) return
        val total = spaceRepository.commentCount(postId)
        val added = newCommentId
            ?.let { spaceRepository.getComment(it) }
            ?.takeIf { comment -> thread.comments.none { it.comment.commentId == comment.commentId } }
            ?.let { toCommentView(it, currentAssistants()) }
        val comments = if (added != null) thread.comments + added else thread.comments
        _commentThread.value = thread.copy(
            comments = comments,
            totalCount = total,
            hasMore = comments.size < total,
        )
    }

    // ── Mutations (all as the local user) ────────────────────────────────────────────────────

    fun toggleLike(postId: String, liked: Boolean) = workScope.launch {
        spaceRepository.setLike(viewer, postId, liked, SpaceCausalDepth.USER_INITIATED)
        reloadFeed()
        reloadMine()
    }

    fun createPost(content: String, onResult: (Boolean) -> Unit = {}) = workScope.launch {
        _busy.value = true
        val outcome = spaceRepository.createPost(viewer, content, SpaceCausalDepth.USER_INITIATED)
        _busy.value = false
        reloadFeed()
        reloadMine()
        onResult(outcome is SpaceWriteOutcome.Created)
    }

    fun createComment(postId: String, content: String) = workScope.launch {
        _busy.value = true
        val outcome = spaceRepository.createComment(
            viewer,
            postId,
            content,
            SpaceCausalDepth.USER_INITIATED,
        )
        _busy.value = false
        refreshOpenThread(postId, (outcome as? SpaceWriteOutcome.Created)?.id)
        reloadFeed()
        reloadMine()
    }

    /**
     * Deletes one of the person's own posts, then refreshes every surface that could have shown it.
     *
     * The confirmation lives in the UI, not here: this is the action, and it is deliberately
     * unconditional about being called. Ownership is enforced in the repository against the
     * runtime actor — this method cannot delete an assistant's post even if it were asked to.
     * Notifications are refreshed too, because the delete cascades them away and the unread badge
     * would otherwise keep counting a post that no longer exists.
     */
    fun deletePost(postId: String) = workScope.launch {
        _busy.value = true
        spaceRepository.deletePost(viewer, postId)
        _busy.value = false
        if (_commentThread.value?.post?.postId == postId) _commentThread.value = null
        reloadFeed()
        reloadMine()
        reloadNotifications()
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

        /** How many comments a timeline card teases before the full view takes over. */
        const val COMMENT_PREVIEW = 2

        /** How many comments the full view reads at a time. */
        const val COMMENT_PAGE_SIZE = 20
    }
}

/** Tab identity for the Cat Garden screen. */
enum class CatGardenTab {
    FEED,
    MINE,
    NOTIFICATIONS,
}
