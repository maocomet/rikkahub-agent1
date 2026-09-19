package me.rerere.rikkahub.sticker

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StickerRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = FakeStickerDao()
    private var clock = 1_000L
    private var ids = 0

    // `by lazy`, not a plain initialiser: a JUnit rule has not created the temporary folder when
    // the test class is constructed, so reading `temporaryFolder.root` from a field initialiser
    // throws "the temporary folder has not yet been created" before the first test even runs.
    // First access happens inside a test method, by which point the rule has run.
    private val fileStore by lazy { StickerFileStore(temporaryFolder.root) }

    private val repository by lazy {
        StickerRepository(
            dao = dao,
            fileStore = fileStore,
            nowMs = { clock },
            newId = { "sticker-${ids++}" },
        )
    }

    private fun stageAndCommit(tag: Int, id: String, extension: String = "png"): String =
        fileStore.commit(
            fileStore.stage(ByteArrayInputStream(StickerFixtures.png(tag = tag))),
            stickerId = id,
            extension = extension,
        )!!

    /** Computed independently of the file store, so the two are not cross-checking each other. */
    private fun checksumOf(tag: Int): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(StickerFixtures.png(tag = tag))
            .joinToString("") { "%02x".format(it) }

    private suspend fun insert(
        id: String = "s1",
        tag: Int = 1,
        description: String = "",
        tags: List<String> = emptyList(),
        enabled: Boolean = true,
        visionState: StickerVisionState = StickerVisionState.NONE,
        visionFailure: StickerVisionFailure? = null,
    ): Sticker {
        return repository.insertSticker(
            stickerId = id,
            relativePath = stageAndCommit(tag = tag, id = id),
            mimeType = "image/png",
            width = 64,
            height = 48,
            description = description,
            tags = tags,
            checksum = checksumOf(tag),
            enabled = enabled,
            visionState = visionState,
            visionFailure = visionFailure,
        )
    }


    @Test
    fun `an inserted sticker is listed with its stored metadata`() = runBlocking {
        insert(id = "s1", description = "一只委屈的猫", tags = listOf("委屈", "猫猫"))

        val listed = repository.observeLibrary().first()

        assertEquals(1, listed.size)
        assertEquals("s1", listed.single().id)
        assertEquals("一只委屈的猫", listed.single().description)
        assertEquals(listOf("委屈", "猫猫"), listed.single().tags)
        assertTrue(listed.single().enabled)
    }

    @Test
    fun `description and tags are updated together and the timestamp moves`() = runBlocking {
        insert(id = "s1")
        val createdAt = repository.getSticker("s1")!!.createdAtMs
        clock = 5_000L

        val updated = repository.updateMetadata(
            stickerId = "s1",
            description = "改过的描述",
            tags = listOf("改过", "标签"),
            visionState = StickerVisionState.OK,
        )

        val sticker = repository.getSticker("s1")!!
        assertTrue(updated)
        assertEquals("改过的描述", sticker.description)
        assertEquals(listOf("改过", "标签"), sticker.tags)
        assertEquals(StickerVisionState.OK, sticker.visionState)
        assertEquals(createdAt, sticker.createdAtMs)
        assertEquals(5_000L, sticker.updatedAtMs)
    }

    @Test
    fun `a failed recognition is recorded with its short code and no metadata`() = runBlocking {
        insert(id = "s1")

        repository.updateMetadata(
            stickerId = "s1",
            description = "",
            tags = emptyList(),
            visionState = StickerVisionState.FAILED,
            visionFailure = StickerVisionFailure.MODEL_NOT_FOUND,
        )

        val sticker = repository.getSticker("s1")!!
        assertEquals(StickerVisionState.FAILED, sticker.visionState)
        assertEquals(StickerVisionFailure.MODEL_NOT_FOUND, sticker.visionFailure)
        assertEquals("", sticker.description)
    }

    @Test
    fun `a hand-written description keeps the failed recognition state`() = runBlocking {
        insert(id = "s1")

        // What the import editor does when recognition failed and the person typed their own text.
        repository.updateMetadata(
            stickerId = "s1",
            description = "我自己写的",
            tags = listOf("手写"),
            visionState = StickerVisionState.FAILED,
            visionFailure = StickerVisionFailure.REQUEST_FAILED,
        )

        val sticker = repository.getSticker("s1")!!
        assertEquals("我自己写的", sticker.description)
        assertEquals(
            "clearing the failure would erase the only evidence the model is misconfigured",
            StickerVisionState.FAILED,
            sticker.visionState,
        )
    }

    @Test
    fun `a sticker can be disabled and re-enabled`() = runBlocking {
        insert(id = "s1")

        assertTrue(repository.setEnabled("s1", false))
        assertFalse(repository.getSticker("s1")!!.enabled)

        assertTrue(repository.setEnabled("s1", true))
        assertTrue(repository.getSticker("s1")!!.enabled)
    }

    @Test
    fun `updating a sticker that does not exist reports failure instead of inventing success`() =
        runBlocking {
            assertFalse(
                repository.updateMetadata(
                    stickerId = "missing",
                    description = "x",
                    tags = emptyList(),
                    visionState = StickerVisionState.OK,
                ),
            )
            assertFalse(repository.setEnabled("missing", false))
        }

    @Test
    fun `deleting a sticker removes the row and its image`() = runBlocking {
        insert(id = "s1")
        val relativePath = repository.getSticker("s1")!!.relativePath
        assertNotNull(fileStore.resolve(relativePath))

        val deleted = repository.deleteSticker("s1")

        assertTrue(deleted)
        assertNull(repository.getSticker("s1"))
        assertTrue(repository.observeLibrary().first().isEmpty())
        assertNull("the row is gone, so its image must be too", fileStore.resolve(relativePath))
    }

    @Test
    fun `deleting a sticker that does not exist is a no-op that reports nothing deleted`() =
        runBlocking {
            assertFalse(repository.deleteSticker("missing"))
        }

    @Test
    fun `deleting one sticker leaves another sticker's image alone`() = runBlocking {
        insert(id = "s1", tag = 1)
        insert(id = "s2", tag = 2)
        val survivor = repository.getSticker("s2")!!.relativePath

        repository.deleteSticker("s1")

        assertNotNull(fileStore.resolve(survivor))
    }

    @Test
    fun `an image left behind by a failed file delete is reclaimed by the sweep`() = runBlocking {
        insert(id = "s1")
        val relativePath = repository.getSticker("s1")!!.relativePath
        val image = fileStore.resolve(relativePath)!!
        // The crash case: the row is gone but the delete of the file never ran.
        dao.delete("s1")
        assertTrue(image.exists())

        val removed = repository.sweepOrphans()

        assertEquals(1, removed)
        assertFalse(image.exists())
    }

    @Test
    fun `the sweep does not touch images that still have a row`() = runBlocking {
        insert(id = "s1")
        val image = fileStore.resolve(repository.getSticker("s1")!!.relativePath)!!

        assertEquals(0, repository.sweepOrphans())

        assertTrue(image.exists())
    }

    @Test
    fun `the same checksum is found as a duplicate`() = runBlocking {
        insert(id = "s1", tag = 1)
        val checksum = repository.getSticker("s1")!!.checksum

        val duplicate = repository.findDuplicate(checksum)

        assertEquals("s1", duplicate?.id)
    }

    @Test
    fun `an unknown checksum is not a duplicate`() = runBlocking {
        insert(id = "s1", tag = 1)

        assertNull(repository.findDuplicate("0".repeat(64)))
    }

    @Test
    fun `a second row for the same bytes is refused by the storage layer`() {
        val failure = runBlocking {
            insert(id = "s1", tag = 1)
            // The race the pre-check cannot close: two imports of the same picture, both past the
            // lookup, arriving here with identical checksums under different ids.
            runCatching { insert(id = "s2", tag = 1) }.exceptionOrNull()
        }
        val listed = runBlocking { repository.observeLibrary().first() }

        assertNotNull(
            "the unique index, not the pre-check, is what makes one copy the invariant",
            failure,
        )
        assertEquals(1, listed.size)
    }

    @Test
    fun `tags are normalised on the way in`() = runBlocking {
        insert(id = "s1", tags = listOf(" 委屈 ", "委屈", "", "猫猫"))

        assertEquals(
            listOf("委屈", "猫猫"),
            repository.getSticker("s1")!!.tags,
        )
    }

    @Test
    fun `an absolute path resolves back to the stored image`() = runBlocking {
        insert(id = "s1")
        val relativePath = repository.getSticker("s1")!!.relativePath

        val file = repository.absolutePathOf(relativePath)

        assertNotNull(file)
        assertTrue(file!!.isFile)
        assertEquals(
            File(temporaryFolder.root, "stickers").canonicalPath,
            file.parentFile?.canonicalPath,
        )
    }
}
