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
