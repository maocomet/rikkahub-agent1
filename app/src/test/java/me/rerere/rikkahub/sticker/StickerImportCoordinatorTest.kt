package me.rerere.rikkahub.sticker

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The import flow end to end, on the JVM.
 *
 * The file store and the repository are the real ones over a temp directory — only the two seams
 * that need an Android runtime (bitmap dimensions) or a network (the vision model) are faked. That
 * means these tests exercise the actual two-phase ordering, the actual rollback, and the actual
 * orphan/de-duplication behaviour rather than a description of it.
 */
class StickerImportCoordinatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = FakeStickerDao()
    private var ids = 0
    private val vision = FakeVisionClient(FakeVisionClient.success())
    private val dimensions = FakeDimensionReader()

    // `by lazy`, not plain initialisers: a JUnit rule has not created the temporary folder when
    // the test class is constructed, so reading `temporaryFolder.root` from a field initialiser
    // throws "the temporary folder has not yet been created" before the first test even runs.
    // First access happens inside a test method, by which point the rule has run.
    private val fileStore by lazy { StickerFileStore(temporaryFolder.root) }

    private val repository by lazy {
        StickerRepository(
            dao = dao,
            fileStore = fileStore,
            nowMs = { 1_000L },
            newId = { "generated-${ids++}" },
        )
    }

    private val coordinator by lazy {
        StickerImportCoordinator(
            repository = repository,
            visionClient = vision,
            dimensionReader = dimensions,
            newId = { "sticker-${ids++}" },
        )
    }

    private fun checksumOf(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private suspend fun insertExisting(tag: Int): Sticker = repository.insertSticker(
        relativePath = fileStore.commit(
            fileStore.stage(ByteArrayInputStream(StickerFixtures.png(tag = tag))),
            stickerId = "existing-$tag",
            extension = "png",
        )!!,
        mimeType = "image/png",
        width = 64,
        height = 48,
        description = "已经在库里的猫",
        tags = listOf("猫猫"),
        checksum = checksumOf(StickerFixtures.png(tag = tag)),
    )

    private fun stagedFiles(): List<File> =
        fileStore.stagingRoot.listFiles()?.toList().orEmpty()

    private fun committedFiles(): List<File> =
        fileStore.root.listFiles()?.filter { it.isFile }.orEmpty()

    // ── beginImport ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a png is staged with its detected format and dimensions`() = runBlocking {
        val start = coordinator.beginImport(ByteArrayInputStream(StickerFixtures.png(tag = 1)))

        val staged = start as StickerImportStart.Staged
        assertEquals("image/png", staged.mimeType)
        assertEquals("png", staged.extension)
        assertEquals(64, staged.width)
        assertEquals(48, staged.height)
        assertTrue("the bytes must be staged, not yet committed", staged.staged.file.isFile)
        assertEquals(
            "nothing may reach the library before the person saves",
            emptyList<File>(),
            committedFiles(),
        )
        assertNull(
            "a staged import has no row — backing out must not have to delete one",
            repository.getSticker(staged.stickerId),
        )
    }

    @Test
    fun `gif is accepted as a first-class format`() = runBlocking {
        val start = coordinator.beginImport(ByteArrayInputStream(StickerFixtures.gif(tag = 1)))

        val staged = start as StickerImportStart.Staged
        assertEquals("image/gif", staged.mimeType)
        assertEquals("gif", staged.extension)
    }

    @Test
    fun `webp and jpeg are accepted`() = runBlocking {
        val webp = coordinator.beginImport(ByteArrayInputStream(StickerFixtures.webp(tag = 1)))
        val jpeg = coordinator.beginImport(ByteArrayInputStream(StickerFixtures.jpeg(tag = 1)))

        assertEquals("image/webp", (webp as StickerImportStart.Staged).mimeType)
        assertEquals("image/jpeg", (jpeg as StickerImportStart.Staged).mimeType)
    }

    @Test
    fun `a format outside the supported set is refused by its bytes, not its name`() = runBlocking {
        val start = coordinator.beginImport(ByteArrayInputStream(StickerFixtures.bmp(tag = 1)))

        assertEquals(
            StickerImportStart.Rejected(StickerImportRejection.UNSUPPORTED_FORMAT),
            start,
        )
        assertEquals("a refused file must leave nothing staged", emptyList<File>(), stagedFiles())
    }

    @Test
    fun `a non-image is refused even when it is named like one`() = runBlocking {
        val start = coordinator.beginImport(ByteArrayInputStream(StickerFixtures.text(tag = 1)))

        assertEquals(
            StickerImportStart.Rejected(StickerImportRejection.UNSUPPORTED_FORMAT),
            start,
        )
    }

    @Test
    fun `a supported header with unreadable dimensions is refused as damaged`() = runBlocking {
        val broken = StickerImportCoordinator(
            repository = repository,
            visionClient = vision,
            dimensionReader = FakeDimensionReader(dimensions = null),
        )

        val start = broken.beginImport(ByteArrayInputStream(StickerFixtures.png(tag = 1)))

        assertEquals(
            StickerImportStart.Rejected(StickerImportRejection.CORRUPT_IMAGE),
            start,
        )
        assertEquals(emptyList<File>(), stagedFiles())
    }

    @Test
    fun `a stream that cannot be read is refused without leaving anything behind`() = runBlocking {
        val start = coordinator.beginImport(UnreadableStream())

        assertEquals(
            StickerImportStart.Rejected(StickerImportRejection.UNREADABLE),
            start,
        )
        assertEquals(emptyList<File>(), stagedFiles())
    }

    @Test
    fun `re-importing the same picture reports the existing sticker and copies nothing`() =
        runBlocking {
            val existing = insertExisting(tag = 4)
            val stagedBefore = stagedFiles().size

            val start = coordinator.beginImport(
                ByteArrayInputStream(StickerFixtures.png(tag = 4)),
            )

            val duplicate = start as StickerImportStart.Duplicate
            assertEquals(existing.id, duplicate.existing.id)
            assertEquals("no second copy may be left staged", stagedBefore, stagedFiles().size)
            assertEquals(1, committedFiles().size)
        }

    @Test
    fun `different pictures of the same format are not duplicates`() = runBlocking {
        insertExisting(tag = 4)

        val start = coordinator.beginImport(ByteArrayInputStream(StickerFixtures.png(tag = 5)))

        assertTrue(start is StickerImportStart.Staged)
    }

    // ── recognize ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a successful recognition carries the description and tags`() = runBlocking {
        val start = stagedPng(tag = 1)
        vision.respondWith(
            FakeVisionClient.success(
                description = "一只猫缩成一团，委屈又有点生气",
                tags = listOf("委屈", "生气", "猫猫"),
            ),
        )

        val recognition = coordinator.recognize(start)

        assertEquals(StickerVisionState.OK, recognition.state)
        assertEquals("一只猫缩成一团，委屈又有点生气", recognition.description)
        assertEquals(listOf("委屈", "生气", "猫猫"), recognition.tags)
        assertNull(recognition.failure)
    }

    @Test
    fun `an unconfigured model leaves the sticker importable and unrecognised`() = runBlocking {
        val start = stagedPng(tag = 1)
        vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.MODEL_NOT_CONFIGURED))

        val recognition = coordinator.recognize(start)

        assertEquals(
            "nothing was attempted, so this is 未识别 rather than 识图失败",
            StickerVisionState.NONE,
            recognition.state,
        )
        assertEquals(StickerVisionFailure.MODEL_NOT_CONFIGURED, recognition.failure)
        assertEquals("", recognition.description)
        assertEquals(emptyList<String>(), recognition.tags)
    }

    @Test
    fun `a model that cannot see images fails without touching the image`() = runBlocking {
        val start = stagedPng(tag = 1)
        vision.respondWith(
            FakeVisionClient.failure(StickerVisionFailure.IMAGE_INPUT_UNSUPPORTED),
        )

        val recognition = coordinator.recognize(start)

        assertEquals(StickerVisionState.FAILED, recognition.state)
        assertEquals(StickerVisionFailure.IMAGE_INPUT_UNSUPPORTED, recognition.failure)
        assertTrue("the staged image must survive", start.staged.file.isFile)
    }

    @Test
    fun `a failed request leaves the image importable`() = runBlocking {
        val start = stagedPng(tag = 1)
        vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.REQUEST_FAILED))

        val saved = save(start, coordinator.recognize(start))

        assertTrue("vision failing must never cost the person the image", saved.isSuccess)
        assertEquals(StickerVisionState.FAILED, saved.getOrNull()?.visionState)
    }

    @Test
    fun `a client that throws is contained rather than losing the import`() = runBlocking {
        val start = stagedPng(tag = 1)
        vision.throwOnDescribe = IllegalStateException("client violated its no-throw contract")

        val recognition = coordinator.recognize(start)

        assertEquals(StickerVisionState.FAILED, recognition.state)
        assertEquals(StickerVisionFailure.REQUEST_FAILED, recognition.failure)
        assertTrue("the image must still be there", start.staged.file.isFile)
        assertTrue(save(start, recognition).isSuccess)
    }

    @Test
    fun `an unparseable reply is a failure, not a description`() = runBlocking {
        val start = stagedPng(tag = 1)
        // What ProviderStickerVisionClient returns when the parser answers null.
        vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.MALFORMED_RESPONSE))

        val recognition = coordinator.recognize(start)

        assertEquals(StickerVisionState.FAILED, recognition.state)
        assertEquals("", recognition.description)
    }

    // ── commit / cancel ──────────────────────────────────────────────────────────────────────

    @Test
    fun `saving places the image and writes the row`() = runBlocking {
        val start = stagedPng(tag = 1)
        val recognition = coordinator.recognize(start)

        val saved = coordinator.commit(
            start = start,
            description = recognition.description,
            tags = recognition.tags,
            enabled = true,
            recognition = recognition,
        )

        val sticker = saved.getOrNull()
        assertNotNull(sticker)
        assertEquals("stickers/${start.stickerId}.png", sticker!!.relativePath)
        assertNotNull(fileStore.resolve(sticker.relativePath))
        assertEquals(sticker.id, repository.getSticker(sticker.id)?.id)
        assertEquals(StickerVisionState.OK, sticker.visionState)
        assertEquals(emptyList<File>(), stagedFiles())
    }

    @Test
    fun `a failed row insert removes the placed image instead of stranding it`() = runBlocking {
        val start = stagedPng(tag = 1)
        val recognition = coordinator.recognize(start)
        dao.failNextInsert = true

        val saved = coordinator.commit(
            start = start,
            description = recognition.description,
            tags = recognition.tags,
            enabled = true,
            recognition = recognition,
        )

        assertTrue(saved.isFailure)
        assertNull(repository.getSticker(start.stickerId))
        assertEquals(
            "a placed file with no row is exactly the orphan the sweep would have to reclaim",
            emptyList<File>(),
            committedFiles(),
        )
    }

    @Test
    fun `cancelling leaves neither a row nor any bytes`() = runBlocking {
        val start = stagedPng(tag = 1)

        coordinator.cancel(start)

        assertNull(repository.getSticker(start.stickerId))
        assertFalse(start.staged.file.exists())
        assertEquals(emptyList<File>(), stagedFiles())
        assertEquals(emptyList<File>(), committedFiles())
    }

    @Test
    fun `a cancelled import is never visible in the library`() = runBlocking {
        val start = stagedPng(tag = 1)
        coordinator.recognize(start)

        coordinator.cancel(start)

        assertTrue(repository.getSticker(start.stickerId) == null)
    }

    @Test
    fun `saving with a hand-written description after a failed recognition keeps the failure`() =
        runBlocking {
            val start = stagedPng(tag = 1)
            vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.REQUEST_FAILED))
            val recognition = coordinator.recognize(start)

            val saved = coordinator.commit(
                start = start,
                description = "我自己写的描述",
                tags = listOf("手写"),
                enabled = false,
                recognition = recognition,
            )

            val sticker = saved.getOrNull()!!
            assertEquals("我自己写的描述", sticker.description)
            assertEquals(listOf("手写"), sticker.tags)
            assertFalse(sticker.enabled)
            assertEquals(
                "the person writing a description does not make the model work",
                StickerVisionState.FAILED,
                sticker.visionState,
            )
            assertEquals(StickerVisionFailure.REQUEST_FAILED, sticker.visionFailure)
        }

    // ── retryRecognition ─────────────────────────────────────────────────────────────────────

    @Test
    fun `re-recognising a saved sticker replaces its metadata`() = runBlocking {
        val sticker = savedSticker(tag = 1, description = "旧的描述", tags = listOf("旧"))
        vision.respondWith(
            FakeVisionClient.success(description = "新的描述", tags = listOf("新", "标签")),
        )

        val outcome = coordinator.retryRecognition(sticker.id)

        val updated = (outcome as StickerRetryOutcome.Updated).sticker
        assertEquals("新的描述", updated.description)
        assertEquals(listOf("新", "标签"), updated.tags)
        assertEquals(StickerVisionState.OK, updated.visionState)
        assertEquals(updated.id, repository.getSticker(sticker.id)?.id)
    }

    @Test
    fun `a failed re-run keeps a description the person may have written`() = runBlocking {
        val sticker = savedSticker(tag = 1, description = "我自己写的", tags = listOf("手写"))
        vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.REQUEST_FAILED))

        val outcome = coordinator.retryRecognition(sticker.id)

        val failed = outcome as StickerRetryOutcome.Failed
        assertTrue(failed.preservedMetadata)
        val after = repository.getSticker(sticker.id)!!
        assertEquals(
            "destroying the person's text in response to an error that cost them nothing",
            "我自己写的",
            after.description,
        )
        assertEquals(listOf("手写"), after.tags)
        assertEquals(StickerVisionState.OK, after.visionState)
    }

    @Test
    fun `a failed re-run on a sticker with no description records the failure`() = runBlocking {
        val start = stagedPng(tag = 1)
        vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.MODEL_NOT_CONFIGURED))
        val saved = coordinator.commit(
            start = start,
            description = "",
            tags = emptyList(),
            enabled = true,
            recognition = coordinator.recognize(start),
        ).getOrNull()!!
        vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.MODEL_NOT_FOUND))

        val outcome = coordinator.retryRecognition(saved.id)

        val failed = outcome as StickerRetryOutcome.Failed
        assertFalse(failed.preservedMetadata)
        val after = repository.getSticker(saved.id)!!
        assertEquals(StickerVisionState.FAILED, after.visionState)
        assertEquals(StickerVisionFailure.MODEL_NOT_FOUND, after.visionFailure)
    }

    @Test
    fun `re-recognising a sticker that no longer exists reports it rather than throwing`() =
        runBlocking {
            assertEquals(StickerRetryOutcome.NotFound, coordinator.retryRecognition("missing"))
        }

    @Test
    fun `re-recognition reads the committed file, not the staging copy`() = runBlocking {
        val sticker = savedSticker(tag = 1)
        vision.described.clear()

        coordinator.retryRecognition(sticker.id)

        val described = vision.described.single()
        assertTrue(described.isFile)
        assertEquals(fileStore.resolve(sticker.relativePath), described)
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private suspend fun stagedPng(tag: Int): StickerImportStart.Staged =
        coordinator.beginImport(ByteArrayInputStream(StickerFixtures.png(tag = tag)))
            as StickerImportStart.Staged

    private suspend fun save(
        start: StickerImportStart.Staged,
        recognition: StickerRecognition,
    ) = coordinator.commit(
        start = start,
        description = recognition.description,
        tags = recognition.tags,
        enabled = true,
        recognition = recognition,
    )

    private suspend fun savedSticker(
        tag: Int,
        description: String = "描述",
        tags: List<String> = listOf("标签"),
    ): Sticker {
        val start = stagedPng(tag = tag)
        return coordinator.commit(
            start = start,
            description = description,
            tags = tags,
            enabled = true,
            recognition = StickerRecognition(
                description = description,
                tags = tags,
                state = StickerVisionState.OK,
                failure = null,
            ),
        ).getOrNull()!!
    }

    /** Fails on the first read, the way an unopenable content URI does. */
    private class UnreadableStream : InputStream() {
        override fun read(): Int = throw IOException("unreadable")
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("unreadable")
    }
}
