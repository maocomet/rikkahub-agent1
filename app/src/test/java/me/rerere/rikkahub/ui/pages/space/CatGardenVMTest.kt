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
    fun `mine exhausts even when the last page lands exactly on the page size`() = runBlocking {
        repeat(20) { repository.createPost(SpaceActor.USER, "post $it", originDepth = 0) }

        val vm = viewModel()
        assertEquals(20, vm.mine.value.size)
        // Twenty rows came back, so a page may still exist — asking is legitimate here.
        assertTrue(vm.mineHasMore.value)

        vm.loadMoreMine()

        assertEquals(20, vm.mine.value.size)
        assertFalse("the empty page proved the end", vm.mineHasMore.value)
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

    @Test
    fun `the viewer cannot delete an assistant's post`() = runBlocking {
        val assistantPost = repository.createPost(assistantActor, "not mine", originDepth = 0).let {
            (it as SpaceWriteOutcome.Created).id
        }

        val vm = viewModel()
        vm.deletePost(assistantPost)

        // Refused by the repository's ownership check, so nothing disappears from the screen.
        assertEquals(1, vm.feed.value.size)
        assertTrue(repository.getPost(assistantPost) != null)
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
