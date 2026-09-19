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
import me.rerere.rikkahub.space.SpacePostDeletionPolicy
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
 * Whether the screen offers a delete entry for [post].
 *
 * Every post, including one an assistant published: this screen acts as the local user, who is Cat
 * Garden's moderator and may delete any post. The rule is asked of [SpacePostDeletionPolicy]
 * rather than answered here, so what the screen offers and what the repository enforces stay the
 * same rule — and asking it is still only a decision about what to SHOW. The repository decides
 * again from the runtime actor before anything is deleted, so a wrong answer here could offer a
 * control that is then refused, never authorise one.
 */
fun localUserMayDelete(post: SpacePostEntity): Boolean =
    SpacePostDeletionPolicy.authorityFor(SpaceActor.USER, post) != null

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
        val page = spaceRepository.listPostsPage(feedCursor, PAGE_SIZE)
        // `hasMore` is a lookahead verdict from the repository, not an inference from the page
        // length: a timeline that ends exactly on a page boundary reports "no more" here rather
        // than offering a Load More that would return nothing.
        feedExhausted = !page.hasMore
        _feedHasMore.value = page.hasMore
        if (page.items.isEmpty()) return

        // The cursor is the last RENDERED post. The lookahead row that produced `hasMore` is the
        // first post of the next page; using it here would skip that post entirely.
        feedCursor = page.items.last().let { SpaceCursor(it.createdAtMs, it.postId) }
        val items = page.items.map { toFeedItem(it, assistants) }
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
        val page = spaceRepository.listPostsByAuthorPage(viewer, mineCursor, PAGE_SIZE)
        mineExhausted = !page.hasMore
        _mineHasMore.value = page.hasMore
        if (page.items.isEmpty()) return

        mineCursor = page.items.last().let { SpaceCursor(it.createdAtMs, it.postId) }
        val items = page.items.map { toFeedItem(it, assistants) }
        _mine.value = _mine.value + items
    }

    private suspend fun reloadNotifications() {
        val assistants = currentAssistants()
        val page = spaceRepository.listNotificationsPage(viewer, before = null, limit = PAGE_SIZE).items
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
        val comments = spaceRepository
            .listCommentsPage(post.postId, after = null, limit = COMMENT_PREVIEW)
            .items
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
        val page = spaceRepository.listCommentsPage(postId, after = null, limit = COMMENT_PAGE_SIZE)
        _commentThread.value = SpaceCommentThread(
            post = post,
            author = resolveSpaceProfile(
                SpaceActor.fromStored(post.authorKind, post.authorId) ?: viewer,
                assistants,
            ),
            totalCount = spaceRepository.commentCount(postId),
            comments = page.items.map { toCommentView(it, assistants) },
            hasMore = page.hasMore,
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
        val page = spaceRepository.listCommentsPage(
            postId = thread.post.postId,
            after = SpaceCursor(last.createdAtMs, last.commentId),
            limit = COMMENT_PAGE_SIZE,
        )
        // Re-read the thread before writing: a close or a delete may have landed while this page was
        // loading, and writing the snapshot back would resurrect a view the reader already
        // dismissed. Merging onto `current` rather than onto `thread` also keeps anything that
        // arrived in the meantime.
        val current = _commentThread.value ?: return@launch
        if (current.post.postId != thread.post.postId) return@launch
        val known = current.comments.mapTo(mutableSetOf()) { it.comment.commentId }
        val merged = current.comments + page.items
            .filter { it.commentId !in known }
            .map { toCommentView(it, assistants) }
        _commentThread.value = current.copy(
            comments = merged,
            totalCount = spaceRepository.commentCount(thread.post.postId),
            // The page's own lookahead verdict, not `merged.size < totalCount`: the latter is the
            // same cursor-blind guess that misreports the last page of a multi-page thread.
            hasMore = page.hasMore,
            loadingMore = false,
        )
    }

    /**
     * Brings the open thread in line with a comment that was just written.
     *
     * The loaded list is a cursor PREFIX of the thread's database order, and every page read
     * resumes from its last row. That invariant is what makes appending the new comment unsafe
     * whenever the prefix is incomplete: with c1..c20 on screen and c21..c25 unread, appending a
     * brand-new c26 would move the prefix end to c26, and the next page would ask for everything
     * after c26 — skipping c21..c25 permanently.
     *
     * So the two cases are genuinely different:
     *  - **incomplete** ([SpaceCommentThread.hasMore]) — only the total is updated. The new comment
     *    is past the prefix end, so the ordinary Load More reaches it in its proper place, in order,
     *    with nothing skipped.
     *  - **complete** — the loaded list IS the whole thread, so the new comment belongs at its tail
     *    and appending keeps the prefix contiguous.
     *
     * `hasMore` is deliberately left untouched in both branches: incomplete stays incomplete,
     * complete stays complete. It is never recomputed from `size < total`, which is the cursor-blind
     * guess this whole change exists to remove.
     */
    private suspend fun refreshOpenThread(postId: String, newCommentId: String?) {
        if (_commentThread.value?.post?.postId != postId) return
        val total = spaceRepository.commentCount(postId)
        val added = newCommentId?.let { spaceRepository.getComment(it) }

        // Re-read before deciding, and decide from THIS state: the thread may have been closed,
        // reopened, or paged while the reads above were in flight, and only the list actually on
        // screen has a prefix whose end is a valid cursor.
        val current = _commentThread.value ?: return
        if (current.post.postId != postId) return
        val view = if (current.hasMore || added == null) {
            null
        } else {
            toCommentView(added, currentAssistants())
        }
        val comments = if (view != null &&
            current.comments.none { it.comment.commentId == view.comment.commentId }
        ) {
            current.comments + view
        } else {
            current.comments
        }
        _commentThread.value = current.copy(comments = comments, totalCount = total)
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
     * Deletes a post as the local user, then refreshes every surface that could have shown it.
     *
     * Any post, including an assistant's: the local user moderates Cat Garden. That is a
     * moderator's power rather than an ownership claim, and it is the repository's to grant — this
     * method asks as [viewer] and reports whatever comes back, exactly like every other write here.
     *
     * The confirmation lives in the UI, not here: this is the action, and it is deliberately
     * unconditional about being called.
     *
     * Notifications are refreshed too, because the delete cascades them away and the unread badge
     * would otherwise keep counting a post that no longer exists. An open comment thread on the
     * post is closed for the same reason — it cannot outlive what it was reading.
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
