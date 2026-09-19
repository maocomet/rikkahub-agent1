package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.space.SpaceActor
import me.rerere.rikkahub.space.SpaceActorKind
import me.rerere.rikkahub.space.SpaceCausalDepth
import me.rerere.rikkahub.space.SpaceDao
import me.rerere.rikkahub.space.SpaceFootprintRemoval
import me.rerere.rikkahub.space.SpaceNotificationType
import me.rerere.rikkahub.space.SpaceRepository
import me.rerere.rikkahub.space.SpaceWriteOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator/disposable-device only. Never run this instrumentation test on the primary phone.
 *
 * Deleting a Cat Garden post relies on the `ON DELETE CASCADE` foreign keys already declared in the
 * v50 schema rather than on explicit child deletes, so the JVM contract tests — which run against a
 * fake that merely *reproduces* the constraint — cannot prove it. This opens a real Room database,
 * where the constraint is enforced by SQLite under the configuration Room applies at open time, and
 * asserts that the children really do go with the parent.
 *
 * It also pins the assumption the decision rests on: if foreign-key enforcement were ever turned
 * off, this test fails rather than leaving orphaned rows behind on user devices.
 */
@RunWith(AndroidJUnit4::class)
class SpacePostDeletionCascadeTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SpaceDao
    private lateinit var repository: SpaceRepository

    private var clock = 1_000L
    private var idSeq = 0

    private val author = SpaceActor(SpaceActorKind.ASSISTANT, "aaaaaaaa-0000-0000-0000-000000000001")
    private val commenter = SpaceActor(SpaceActorKind.ASSISTANT, "bbbbbbbb-0000-0000-0000-000000000002")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The app's own factory, not the framework default: AppDatabase's FTS tables are created
        // with the `simple` tokenizer, which only exists as the bundled native extension. Opening
        // it through the default factory would fail on the schema rather than on anything this
        // test is about.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(createAppSQLiteOpenHelperFactory(context))
            .build()
        dao = db.spaceDao()
        repository = SpaceRepository(
            dao = dao,
            nowMs = { clock++ },
            newId = { "id-${++idSeq}" },
            // The production wiring shape, exercised here for real: the footprint sweep is the one
            // entry point that depends on it, and a nested-transaction mistake would only ever show
            // up against an actual Room database.
            inTransaction = { block -> db.withTransaction { block() } },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun foreignKeyEnforcementIsOnForTheConnectionRoomOpens() {
        // The cascade below is only real because of this. Asserted explicitly so that a future
        // configuration change fails here, at the cause, rather than as unexplained orphan rows.
        db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("foreign key enforcement must be enabled", 1, cursor.getInt(0))
        }
    }

    @Test
    fun deletingAPostRemovesItsLikesCommentsAndNotifications() = runBlocking {
        val postId = post(author)
        repository.setLike(commenter, postId, liked = true, SpaceCausalDepth.USER_INITIATED)
        repository.setLike(SpaceActor.USER, postId, liked = true, SpaceCausalDepth.USER_INITIATED)
        repository.createComment(commenter, postId, "nice one", SpaceCausalDepth.USER_INITIATED)
        repository.createComment(SpaceActor.USER, postId, "agreed", SpaceCausalDepth.USER_INITIATED)

        // Four actions by identities other than the author, so four notifications — all addressed
        // to the author, because that is the only person any of them acted on.
        assertEquals(4, notificationsOf(author).size)
        assertTrue(notificationsOf(SpaceActor.USER).isEmpty())
        assertEquals(2, repository.likeCount(postId))
        assertEquals(2, repository.commentCount(postId))

        assertTrue(repository.deletePost(author, postId) is SpaceWriteOutcome.Created)

        assertNull(repository.getPost(postId))
        assertEquals(0, repository.likeCount(postId))
        assertEquals(0, repository.commentCount(postId))
        assertTrue(notificationsOf(author).isEmpty())
        assertTrue(notificationsOf(SpaceActor.USER).isEmpty())
        assertTrue(
            dao.listPendingNotifications(SpaceActorKind.ASSISTANT.name, author.id, 0L, 10).isEmpty(),
        )
    }

    @Test
    fun deletingOnePostLeavesAnotherPostsChildrenIntact() = runBlocking {
        val doomed = post(author)
        val survivor = post(author)
        repository.setLike(commenter, doomed, true, SpaceCausalDepth.USER_INITIATED)
        repository.setLike(commenter, survivor, true, SpaceCausalDepth.USER_INITIATED)
        repository.createComment(commenter, doomed, "on the doomed one", SpaceCausalDepth.USER_INITIATED)
        repository.createComment(commenter, survivor, "on the survivor", SpaceCausalDepth.USER_INITIATED)

        repository.deletePost(author, doomed)

        assertEquals(1, repository.likeCount(survivor))
        assertEquals(1, repository.commentCount(survivor))
        // The survivor's own like and comment notifications, and nothing from the doomed post.
        assertEquals(2, notificationsOf(author).size)
        assertEquals("on the survivor", commentsOf(survivor).single().content)
    }

    @Test
    fun removingAnAssistantsFootprintGoesThroughTheRealCascade() = runBlocking {
        val authorPost = post(author)
        val survivorPost = post(commenter)

        // The author's traces on a post that will survive, so the cascade cannot reach them.
        repository.createComment(author, survivorPost, "in the author's own right", SpaceCausalDepth.USER_INITIATED)
        repository.setLike(author, survivorPost, liked = true, SpaceCausalDepth.USER_INITIATED)
        // Other identities' traces on the post about to be deleted, so the cascade must reach them.
        repository.createComment(commenter, authorPost, "on the author's post", SpaceCausalDepth.USER_INITIATED)
        repository.setLike(SpaceActor.USER, authorPost, liked = true, SpaceCausalDepth.USER_INITIATED)

        val removal = repository.deleteAssistantFootprint(author.id)

        assertEquals(SpaceFootprintRemoval(posts = 1, comments = 1, likes = 1, notifications = 2), removal)
        // The cascade has to fire for a MULTI-row parent delete exactly as it does for the
        // single-row one the tests above cover — the sweep is one statement over all of the
        // assistant's posts, and an assumption that only the single-row form cascades would leave
        // orphans on exactly the devices this test exists to catch.
        assertNull(repository.getPost(authorPost))
        assertEquals(0, repository.commentCount(authorPost))
        assertEquals(0, repository.likeCount(authorPost))
        assertTrue(notificationsOf(author).isEmpty())

        // What the cascade could not reach is gone too, and nothing of the survivor's was taken.
        assertTrue(repository.getPost(survivorPost) != null)
        assertEquals(0, repository.commentCount(survivorPost))
        assertEquals(0, repository.likeCount(survivorPost))
        assertTrue("nothing may announce an author that no longer exists", notificationsOf(commenter).isEmpty())
    }

    @Test
    fun ownershipIsEnforcedAgainstTheStoredAuthorInTheRealDatabase() = runBlocking {
        val postId = post(author)

        val refused = repository.deletePost(commenter, postId)
        assertEquals("NOT_POST_OWNER", (refused as SpaceWriteOutcome.Rejected).code)
        assertTrue(repository.getPost(postId) != null)

        val missing = repository.deletePost(author, "no-such-post")
        assertEquals("POST_NOT_FOUND", (missing as SpaceWriteOutcome.Rejected).code)
    }

    @Test
    fun commentPagingIsStableWhenEveryRowSharesAMillisecond() = runBlocking {
        val postId = post(author)
        val frozen = SpaceRepository(dao = dao, nowMs = { 42L }, newId = { "f-${++idSeq}" })
        repeat(5) { frozen.createComment(commenter, postId, "c$it", SpaceCausalDepth.USER_INITIATED) }

        // The SQL cursor is what the JVM fake stands in for; here it runs against real SQLite, so a
        // wrong comparison in the query would show up as a repeated or skipped row.
        val first = dao.listComments(postId, 2)
        val second = dao.listCommentsAfter(postId, first.last().createdAtMs, first.last().commentId, 2)
        val third = dao.listCommentsAfter(postId, second.last().createdAtMs, second.last().commentId, 2)

        val seen = (first + second + third).map { it.commentId }
        assertEquals(5, seen.size)
        assertEquals(5, seen.toSet().size)
    }

    @Test
    fun onlyTheRecipientsOwnUnconsumedNotificationsAreScanned() = runBlocking {
        val postId = post(author)
        repository.setLike(commenter, postId, liked = true, SpaceCausalDepth.USER_INITIATED)

        val pending = dao.listPendingNotifications(
            recipientKind = SpaceActorKind.ASSISTANT.name,
            recipientId = author.id,
            sinceMs = 0L,
            limit = 10,
        )
        assertEquals(1, pending.size)
        assertEquals(SpaceNotificationType.LIKE.name, pending.single().type)
        assertNull("replay must find rows that no consumer has claimed yet", pending.single().consumedAtMs)

        assertEquals(
            0,
            dao.listPendingNotifications(
                recipientKind = SpaceActorKind.ASSISTANT.name,
                recipientId = commenter.id,
                sinceMs = 0L,
                limit = 10,
            ).size,
        )
    }

    private suspend fun post(who: SpaceActor): String =
        (repository.createPost(who, "content ${++idSeq}", SpaceCausalDepth.USER_INITIATED)
            as SpaceWriteOutcome.Created).id

    // Read helpers for tests that are about the cascade, not about paging. They go through the same
    // page API production uses — the repository deliberately has no raw list reader.
    private suspend fun notificationsOf(actor: SpaceActor, limit: Int = 10) =
        repository.listNotificationsPage(actor, before = null, limit = limit).items

    private suspend fun commentsOf(postId: String, limit: Int = 10) =
        repository.listCommentsPage(postId, after = null, limit = limit).items
}
