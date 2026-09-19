package me.rerere.rikkahub.ui.pages.space

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.space.FakeSpaceDao
import me.rerere.rikkahub.space.SpaceActor
import me.rerere.rikkahub.space.SpaceActorKind
import me.rerere.rikkahub.space.SpaceCursor
import me.rerere.rikkahub.space.SpaceIdentitySource
import me.rerere.rikkahub.space.SpaceRepository
import me.rerere.rikkahub.space.SpaceWriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cat Garden read model.
 *
 * Runs on an injected unconfined scope so the ViewModel never touches `viewModelScope` — and
 * therefore never needs `Dispatchers.Main`, which a plain unit test does not have.
 */
class CatGardenVMTest {

    private var idSeq = 0
    private var clock = 1_000L

    /** Ids for the throwaway stores [vmWithPosts] builds, kept unique across all of them. */
    private var generatedIds = 0
    private val dao = FakeSpaceDao()
    private val repository = SpaceRepository(
        dao = dao,
        nowMs = { clock++ },
        newId = { "id-${++idSeq}" },
    )

    private val assistantId = "aaaaaaaa-0000-0000-0000-000000000001"
    private val assistant = Assistant(
        id = Uuid.parse(assistantId),
        name = "Mimi",
        avatar = Avatar.Emoji("🐱"),
    )

    private val assistantActor = SpaceActor(SpaceActorKind.ASSISTANT, assistantId)

    private fun viewModel(assistants: List<Assistant> = listOf(assistant)) = CatGardenVM(
        spaceRepository = repository,
        identitySource = FakeIdentitySource(assistants, nickname = "Owner", avatar = Avatar.Dummy),
        injectedScope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `an assistant author resolves to its live name and avatar`() = runBlocking {
        repository.createPost(assistantActor, "hello garden", originDepth = 0)

        val item = viewModel().feed.value.single()
        assertEquals("hello garden", item.post.content)
        assertEquals("Mimi", item.author.displayName)
        assertEquals(assistant.avatar, item.author.avatar)
        assertFalse(item.author.isDeleted)
    }

    @Test
    fun `an assistant that no longer exists renders as deleted rather than nameless`() = runBlocking {
        repository.createPost(assistantActor, "orphan", originDepth = 0)

        // The assistant list no longer contains the author: its posts stay readable.
        val item = viewModel(assistants = emptyList()).feed.value.single()
        assertTrue(item.author.isDeleted)
        assertEquals("", item.author.displayName)
    }

    @Test
    fun `the person's own posts appear under mine and are not attributed to an assistant`() = runBlocking {
        repository.createPost(assistantActor, "from an assistant", originDepth = 0)
        repository.createPost(SpaceActor.USER, "from the person", originDepth = 0)

        val vm = viewModel()
        assertEquals(2, vm.feed.value.size)
        val mine = vm.mine.value.single()
        assertEquals("from the person", mine.post.content)
        assertEquals(SpaceActorKind.USER, mine.author.actor.kind)
    }

    @Test
    fun `liking and unliking from the screen is reflected in the feed item`() = runBlocking {
        val postId = repository.createPost(assistantActor, "like me", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }

        val vm = viewModel()
        assertFalse(vm.feed.value.single().likedByViewer)

        vm.toggleLike(postId, liked = true)
        assertTrue(vm.feed.value.single().likedByViewer)
        assertEquals(1, vm.feed.value.single().likeCount)

        vm.toggleLike(postId, liked = false)
        assertFalse(vm.feed.value.single().likedByViewer)
        assertEquals(0, vm.feed.value.single().likeCount)
    }

    @Test
    fun `an assistant liking the person's post notifies the person, not the assistant`() = runBlocking {
        val postId = repository.createPost(SpaceActor.USER, "my post", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repository.setLike(assistantActor, postId, liked = true, originDepth = 0)

        val vm = viewModel()
        val item = vm.notifications.value.single()
        assertEquals("Mimi", item.actor.displayName)
        assertEquals("my post", item.postPreview)
        assertEquals(1, vm.unreadCount.value)
    }

    @Test
    fun `marking the visible notifications read clears the unread count`() = runBlocking {
        val postId = repository.createPost(SpaceActor.USER, "my post", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repository.setLike(assistantActor, postId, liked = true, originDepth = 0)
        repository.createComment(assistantActor, postId, "nice", originDepth = 0)

        val vm = viewModel()
        assertEquals(2, vm.notifications.value.size)
        assertEquals(2, vm.unreadCount.value)

        vm.markNotificationsRead(vm.notifications.value.map { it.notification.notificationId })

        assertEquals(0, vm.unreadCount.value)
        assertTrue(vm.notifications.value.all { it.notification.readAtMs != null })
    }

    @Test
    fun `the person commenting on their own post does not notify them`() = runBlocking {
        val postId = repository.createPost(SpaceActor.USER, "my post", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }

        val vm = viewModel()
        vm.createComment(postId, "talking to myself")

        assertTrue(vm.notifications.value.isEmpty())
        assertEquals(0, vm.unreadCount.value)
    }

    // ── Mine tab pagination ──────────────────────────────────────────────────────────────────

    @Test
    fun `mine pages past the first twenty posts`() = runBlocking {
        repeat(25) { repository.createPost(SpaceActor.USER, "post $it", originDepth = 0) }

        val vm = viewModel()
        assertEquals(20, vm.mine.value.size)
        assertTrue("a 21st post exists, so more must be offered", vm.mineHasMore.value)

        vm.loadMoreMine()

        assertEquals(25, vm.mine.value.size)
        assertEquals("every post must appear exactly once", 25, vm.mine.value.map { it.post.postId }.toSet().size)
        assertFalse("nothing is left, so no more may be offered", vm.mineHasMore.value)
    }

    @Test
    fun `mine does not offer more when the last page lands exactly on the page size`() = runBlocking {
        repeat(20) { repository.createPost(SpaceActor.USER, "post $it", originDepth = 0) }

        val vm = viewModel()
        assertEquals(20, vm.mine.value.size)
        // Twenty rows is exactly the whole list. The old rule (`size < PAGE_SIZE`) guessed "maybe
        // more" from the row count alone and made the reader tap Load More to learn there is
        // nothing; the lookahead proves it up front.
        assertFalse("exactly one page is not 'more'", vm.mineHasMore.value)
    }

    @Test
    fun `mine and feed paginate independently`() = runBlocking {
        repeat(25) { repository.createPost(SpaceActor.USER, "post $it", originDepth = 0) }

        val vm = viewModel()
        assertEquals(20, vm.feed.value.size)
        assertEquals(20, vm.mine.value.size)

        // Sharing one cursor between the tabs would make the second call resume from the first
        // tab's position, silently skipping this author's older posts.
        vm.loadMoreFeed()
        assertEquals(25, vm.feed.value.size)
        assertEquals(20, vm.mine.value.size)

        vm.loadMoreMine()
        assertEquals(25, vm.mine.value.size)
        assertEquals(25, vm.mine.value.map { it.post.postId }.toSet().size)
    }

    @Test
    fun `mine pages correctly when every post shares a millisecond`() = runBlocking {
        // The VM's clock is frozen for this test, so a time-only cursor would loop here.
        val frozenClock = SpaceRepository(
            dao = dao,
            nowMs = { 5_000L },
            newId = { "frozen-${++idSeq}" },
        )
        repeat(25) { frozenClock.createPost(SpaceActor.USER, "post $it", originDepth = 0) }

        val vm = CatGardenVM(
            spaceRepository = frozenClock,
            identitySource = FakeIdentitySource(listOf(assistant), "Owner", Avatar.Dummy),
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )
        vm.loadMoreMine()

        assertEquals(25, vm.mine.value.size)
        assertEquals(25, vm.mine.value.map { it.post.postId }.toSet().size)
        assertFalse(vm.mineHasMore.value)
    }

    @Test
    fun `an empty mine offers nothing to load`() = runBlocking {
        val vm = viewModel()
        assertTrue(vm.mine.value.isEmpty())
        assertFalse(vm.mineHasMore.value)
        assertFalse(vm.feedHasMore.value)
    }

    // ── The load-more control is honest at every boundary ────────────────────────────────────

    /**
     * Builds a ViewModel over its own store holding [postCount] posts by the person.
     *
     * A fresh store per count because the assertion is about what the FIRST load offers, which
     * only a clean read can show.
     */
    private suspend fun vmWithPosts(postCount: Int, frozenClockMs: Long? = null): CatGardenVM {
        val localDao = FakeSpaceDao()
        var tick = 1_000L
        val localRepo = SpaceRepository(
            dao = localDao,
            nowMs = { frozenClockMs ?: tick++ },
            newId = { "generated-${++generatedIds}" },
        )
        repeat(postCount) { localRepo.createPost(SpaceActor.USER, "post $it", originDepth = 0) }
        return CatGardenVM(
            spaceRepository = localRepo,
            identitySource = FakeIdentitySource(listOf(assistant), "Owner", Avatar.Dummy),
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Test
    fun `neither tab offers a load more that would return nothing`() = runBlocking {
        // Every boundary the UI can land on: empty, short of a page, exactly one page, one over,
        // exactly two pages, one over two pages.
        val cases = listOf(
            Triple(0, 0, 0),   // posts, size after first load, further loads needed
            Triple(19, 19, 0),
            Triple(20, 20, 0),
            Triple(21, 20, 1),
            Triple(40, 20, 1),
            Triple(41, 20, 2),
        )

        for ((count, firstPage, further) in cases) {
            val vm = vmWithPosts(count)

            assertEquals("feed size at $count", firstPage, vm.feed.value.size)
            assertEquals("mine size at $count", firstPage, vm.mine.value.size)
            assertEquals("feed offers more at $count", count > 20, vm.feedHasMore.value)
            assertEquals("mine offers more at $count", count > 20, vm.mineHasMore.value)

            repeat(further) {
                vm.loadMoreFeed()
                vm.loadMoreMine()
            }

            assertEquals("feed total at $count", count, vm.feed.value.size)
            assertEquals("mine total at $count", count, vm.mine.value.size)
            assertFalse("feed must stop offering at $count", vm.feedHasMore.value)
            assertFalse("mine must stop offering at $count", vm.mineHasMore.value)
            assertEquals(
                "feed must not repeat a post at $count",
                count,
                vm.feed.value.map { it.post.postId }.toSet().size,
            )
            assertEquals(
                "mine must not repeat a post at $count",
                count,
                vm.mine.value.map { it.post.postId }.toSet().size,
            )
        }
    }

    @Test
    fun `the lookahead row is never rendered as part of the page`() = runBlocking {
        // 21 posts: the probe row is real and must be trimmed out of this page, then returned by
        // the NEXT page rather than skipped or shown twice.
        val vm = vmWithPosts(21)

        assertEquals(20, vm.feed.value.size)
        assertTrue(vm.feedHasMore.value)
        val firstPageIds = vm.feed.value.map { it.post.postId }

        vm.loadMoreFeed()

        assertEquals(21, vm.feed.value.size)
        assertEquals(1, vm.feed.value.drop(20).size)
        assertTrue(
            "the probe row must arrive on the next page, not appear twice",
            vm.feed.value.map { it.post.postId }.toSet().size == 21 &&
                vm.feed.value.drop(20).none { it.post.postId in firstPageIds },
        )
    }

    @Test
    fun `same-millisecond posts page without repeating or skipping in either tab`() = runBlocking {
        // A frozen clock: every post shares a timestamp, so only the cursor's id half keeps the
        // pages disjoint.
        val vm = vmWithPosts(25, frozenClockMs = 5_000L)

        assertEquals(20, vm.feed.value.size)
        assertEquals(20, vm.mine.value.size)
        assertTrue(vm.feedHasMore.value)
        assertTrue(vm.mineHasMore.value)

        vm.loadMoreFeed()
        vm.loadMoreMine()

        assertEquals(25, vm.feed.value.size)
        assertEquals(25, vm.mine.value.size)
        assertEquals(25, vm.feed.value.map { it.post.postId }.toSet().size)
        assertEquals(25, vm.mine.value.map { it.post.postId }.toSet().size)
        assertFalse(vm.feedHasMore.value)
        assertFalse(vm.mineHasMore.value)
    }

    // ── The full comment view ────────────────────────────────────────────────────────────────

    @Test
    fun `opening comments shows the post and the real total, not the preview`() = runBlocking {
        val postId = repository.createPost(assistantActor, "hello", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repeat(5) { repository.createComment(SpaceActor.USER, postId, "c$it", originDepth = 0) }

        val vm = viewModel()
        // The card keeps a short preview so the timeline does not grow without bound.
        assertEquals(2, vm.feed.value.single().comments.size)
        // ... while still reporting the true count.
        assertEquals(5, vm.feed.value.single().commentCount)

        vm.openComments(postId)

        val thread = requireNotNull(vm.commentThread.value) { "the full view did not open" }
        assertEquals(postId, thread.post.postId)
        assertEquals("Mimi", thread.author.displayName)
        assertEquals(5, thread.totalCount)
        assertEquals(5, thread.comments.size)
        assertFalse(thread.hasMore)
        assertEquals(listOf("c0", "c1", "c2", "c3", "c4"), thread.comments.map { it.comment.content })
    }

    @Test
    fun `the full comment view pages without repeating or skipping a comment`() = runBlocking {
        val postId = repository.createPost(assistantActor, "hello", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        // A frozen clock: every comment shares a millisecond, so the cursor's id half is the only
        // thing keeping pages disjoint.
        val frozenClock = SpaceRepository(dao = dao, nowMs = { 7_000L }, newId = { "c-${++idSeq}" })
        repeat(25) { frozenClock.createComment(SpaceActor.USER, postId, "c$it", originDepth = 0) }

        val vm = CatGardenVM(
            spaceRepository = frozenClock,
            identitySource = FakeIdentitySource(listOf(assistant), "Owner", Avatar.Dummy),
            injectedScope = CoroutineScope(Dispatchers.Unconfined),
        )
        vm.openComments(postId)

        assertEquals(20, vm.commentThread.value!!.comments.size)
        assertEquals(25, vm.commentThread.value!!.totalCount)
        assertTrue(vm.commentThread.value!!.hasMore)

        vm.loadMoreComments()

        val thread = vm.commentThread.value!!
        assertEquals(25, thread.comments.size)
        assertEquals(25, thread.comments.map { it.comment.commentId }.toSet().size)
        assertEquals(25, thread.totalCount)
        assertFalse(thread.hasMore)
    }

    @Test
    fun `a comment written in the full view is appended to it`() = runBlocking {
        val postId = repository.createPost(assistantActor, "hello", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        val vm = viewModel()
        vm.openComments(postId)
        assertEquals(0, vm.commentThread.value!!.comments.size)

        vm.createComment(postId, "from the person")

        val thread = requireNotNull(vm.commentThread.value)
        assertEquals(listOf("from the person"), thread.comments.map { it.comment.content })
        assertEquals(1, thread.totalCount)
        // The card behind the view refreshes too.
        assertEquals(1, vm.feed.value.single().commentCount)
    }

    @Test
    fun `a comment written while the thread is incomplete cannot swallow the unread gap`() =
        runBlocking {
            val postId = repository.createPost(assistantActor, "hello", originDepth = 0).let {
                (it as SpaceWriteOutcome.Created).id
            }
            // c0..c24.
            repeat(25) { repository.createComment(SpaceActor.USER, postId, "c$it", originDepth = 0) }

            val vm = viewModel()
            vm.openComments(postId)
            val opened = requireNotNull(vm.commentThread.value)
            assertEquals(20, opened.comments.size)
            assertEquals("c0", opened.comments.first().comment.content)
            assertEquals("c19", opened.comments.last().comment.content)
            assertTrue(opened.hasMore)

            vm.createComment(postId, "newest")

            // Appending here would move the loaded prefix's end from c19 to "newest", and the next
            // page would then ask for everything AFTER it — losing c20..c24 for good. The prefix
            // stays put and only the count moves; the new comment is past the unread gap.
            val afterWrite = requireNotNull(vm.commentThread.value)
            assertEquals("the loaded prefix must not move", 20, afterWrite.comments.size)
            assertEquals("c19", afterWrite.comments.last().comment.content)
            assertTrue(
                "the new comment must not appear above the unread gap",
                afterWrite.comments.none { it.comment.content == "newest" },
            )
            assertEquals("but the count is exact", 26, afterWrite.totalCount)
            assertTrue(afterWrite.hasMore)

            val firstCursor = afterWrite.comments.last().comment.let {
                SpaceCursor(it.createdAtMs, it.commentId)
            }
            val stillC19 = repository.listCommentsPage(postId, firstCursor, 20).items.first()
            assertEquals(
                "the next page must resume from c19, not from the new comment",
                "c20",
                stillC19.content,
            )

            vm.loadMoreComments()

            val loaded = requireNotNull(vm.commentThread.value)
            val contents = loaded.comments.map { it.comment.content }
            assertEquals("c20..c24 plus the new comment", 26, contents.size)
            assertEquals(26, contents.toSet().size)
            assertEquals(listOf("c20", "c21", "c22", "c23", "c24", "newest"), contents.drop(20))
            assertEquals(26, loaded.totalCount)
            assertFalse("nothing is left, so nothing may be offered", loaded.hasMore)
        }

    @Test
    fun `a comment written into a complete thread is appended at its tail`() = runBlocking {
        val postId = repository.createPost(assistantActor, "hello", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repeat(3) { repository.createComment(SpaceActor.USER, postId, "c$it", originDepth = 0) }

        val vm = viewModel()
        vm.openComments(postId)
        val opened = requireNotNull(vm.commentThread.value)
        assertEquals(3, opened.comments.size)
        assertFalse("three comments fit in one page", opened.hasMore)

        vm.createComment(postId, "newest")

        val loaded = requireNotNull(vm.commentThread.value)
        // Complete thread: the loaded list IS the whole thread, so its tail is where the new
        // comment belongs and appending keeps the prefix contiguous.
        assertEquals(listOf("c0", "c1", "c2", "newest"), loaded.comments.map { it.comment.content })
        assertEquals(4, loaded.totalCount)
        assertFalse(loaded.hasMore)
    }

    @Test
    fun `closing the full view clears it`() = runBlocking {
        val postId = repository.createPost(assistantActor, "hello", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        val vm = viewModel()
        vm.openComments(postId)
        assertTrue(vm.commentThread.value != null)

        vm.closeComments()

        assertTrue(vm.commentThread.value == null)
    }

    @Test
    fun `opening a post that no longer exists leaves the view closed`() = runBlocking {
        val vm = viewModel()
        vm.openComments("no-such-post")
        assertTrue(vm.commentThread.value == null)
    }

    // ── Deleting the person's own posts ──────────────────────────────────────────────────────

    @Test
    fun `deleting the person's own post drops it from the feed and from mine`() = runBlocking {
        val postId = repository.createPost(SpaceActor.USER, "mine to delete", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repository.createPost(assistantActor, "not mine", originDepth = 0)

        val vm = viewModel()
        assertEquals(2, vm.feed.value.size)
        assertEquals(1, vm.mine.value.size)

        vm.deletePost(postId)

        assertEquals(listOf("not mine"), vm.feed.value.map { it.post.content })
        assertTrue(vm.mine.value.isEmpty())
        assertTrue(vm.mineHasMore.value == false)
    }

    // ── The person as Cat Garden's moderator ─────────────────────────────────────────────────

    @Test
    fun `the person's screen offers a delete entry on an assistant's post`() = runBlocking {
        val assistantPost = repository.createPost(assistantActor, "not mine", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        val ownPost = repository.createPost(SpaceActor.USER, "mine", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }

        // The screen's entry point, asked directly. The person moderates the space, so neither
        // post is excluded — the own/other distinction does not gate the control any more.
        assertTrue(localUserMayDelete(requireNotNull(repository.getPost(assistantPost))))
        assertTrue(localUserMayDelete(requireNotNull(repository.getPost(ownPost))))
    }

    @Test
    fun `deleting an assistant's post drops it from the feed`() = runBlocking {
        val assistantPost = repository.createPost(assistantActor, "not mine", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repository.createPost(SpaceActor.USER, "mine", originDepth = 0)

        val vm = viewModel()
        assertEquals(2, vm.feed.value.size)

        vm.deletePost(assistantPost)

        // Allowed as the local user's moderator power, so the post really goes and the feed is
        // re-read rather than left showing a card whose post no longer exists.
        assertEquals(listOf("mine"), vm.feed.value.map { it.post.content })
        assertTrue(repository.getPost(assistantPost) == null)
    }

    @Test
    fun `deleting an assistant's post closes its open thread and leaves the person's inbox alone`() =
        runBlocking {
            val assistantPost = repository.createPost(assistantActor, "doomed", originDepth = 0).let {
                (it as SpaceWriteOutcome.Created).id
            }
            val ownPost = repository.createPost(SpaceActor.USER, "mine", originDepth = 0).let {
                (it as SpaceWriteOutcome.Created).id
            }
            // The assistant likes the person's post, so the person has an inbox row that has nothing
            // to do with the post being deleted.
            repository.setLike(assistantActor, ownPost, liked = true, originDepth = 0)
            repository.createComment(SpaceActor.USER, assistantPost, "a comment", originDepth = 0)

            val vm = viewModel()
            vm.openComments(assistantPost)
            assertTrue(vm.commentThread.value != null)
            assertEquals(1, vm.notifications.value.size)

            vm.deletePost(assistantPost)

            assertTrue("the thread cannot outlive the post it was reading", vm.commentThread.value == null)
            assertEquals(listOf("mine"), vm.feed.value.map { it.post.content })
            assertTrue(repository.getPost(assistantPost) == null)
            // The person's inbox is about their own post and is untouched by this delete — the
            // moderator path must not sweep anything beyond the post it was pointed at.
            assertEquals(1, vm.notifications.value.size)
            assertEquals(1, vm.unreadCount.value)
        }

    @Test
    fun `deleting an assistant's post leaves another assistant's data alone`() = runBlocking {
        val otherAssistant = SpaceActor(SpaceActorKind.ASSISTANT, "bbbbbbbb-0000-0000-0000-000000000002")
        val doomed = repository.createPost(assistantActor, "doomed", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repository.createPost(otherAssistant, "survivor", originDepth = 0)

        val vm = viewModel()
        vm.deletePost(doomed)

        assertEquals(listOf("survivor"), vm.feed.value.map { it.post.content })
    }

    @Test
    fun `deleting a post closes its open comment view`() = runBlocking {
        val postId = repository.createPost(SpaceActor.USER, "doomed", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repository.createComment(assistantActor, postId, "a comment", originDepth = 0)

        val vm = viewModel()
        vm.openComments(postId)
        assertTrue(vm.commentThread.value != null)

        vm.deletePost(postId)

        assertTrue("the thread cannot outlive the post it was reading", vm.commentThread.value == null)
        assertTrue(vm.feed.value.isEmpty())
    }

    @Test
    fun `deleting a post clears the notifications it cascaded away`() = runBlocking {
        val postId = repository.createPost(SpaceActor.USER, "my post", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }
        repository.setLike(assistantActor, postId, liked = true, originDepth = 0)

        val vm = viewModel()
        assertEquals(1, vm.notifications.value.size)

        vm.deletePost(postId)

        assertTrue("the badge cannot keep counting a post that is gone", vm.notifications.value.isEmpty())
        assertEquals(0, vm.unreadCount.value)
    }

    private class FakeIdentitySource(
        private val assistants: List<Assistant>,
        nickname: String,
        avatar: Avatar,
    ) : SpaceIdentitySource {
        private val nicknameFlow = MutableStateFlow(nickname)
        private val avatarFlow = MutableStateFlow(avatar)
        override suspend fun assistants(): List<Assistant> = assistants
        override val nickname: Flow<String> = nicknameFlow
        override val avatar: Flow<Avatar> = avatarFlow
    }
}
