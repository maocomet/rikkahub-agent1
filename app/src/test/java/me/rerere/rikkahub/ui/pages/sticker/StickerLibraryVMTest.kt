package me.rerere.rikkahub.ui.pages.sticker

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.sticker.FakeDimensionReader
import me.rerere.rikkahub.sticker.FakeStickerDao
import me.rerere.rikkahub.sticker.FakeVisionClient
import me.rerere.rikkahub.sticker.StickerFixtures
import me.rerere.rikkahub.sticker.StickerImportCoordinator
import me.rerere.rikkahub.sticker.StickerImportRejection
import me.rerere.rikkahub.sticker.StickerRepository
import me.rerere.rikkahub.sticker.StickerFileStore
import me.rerere.rikkahub.sticker.StickerVisionFailure
import me.rerere.rikkahub.sticker.StickerVisionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The library's state machine, driven on the JVM.
 *
 * No Compose or Robolectric: this repo has no UI test harness, so what is verified here is the
 * logic behind the screen — which state each outcome produces, what gets persisted, and what is
 * left on disk. The rendering itself is not automatically verified.
 */
class StickerLibraryVMTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = FakeStickerDao()
    private val fileStore = StickerFileStore(temporaryFolder.root)
    private var ids = 0
    private val repository = StickerRepository(
        dao = dao,
        fileStore = fileStore,
        nowMs = { 1_000L },
        newId = { "sticker-${ids++}" },
    )
    private val vision = FakeVisionClient(FakeVisionClient.success())
    private val coordinator = StickerImportCoordinator(
        repository = repository,
        visionClient = vision,
        dimensionReader = FakeDimensionReader(),
        newId = { "sticker-${ids++}" },
    )

    private fun viewModel() = StickerLibraryVM(
        repository = repository,
        importCoordinator = coordinator,
        injectedScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private suspend fun StickerLibraryVM.importBytes(bytes: ByteArray) {
        // Unconfined scope, so the whole chain runs before startImport returns.
        startImport { ByteArrayInputStream(bytes) }
    }

    private fun stagedFiles(): List<File> =
        fileStore.stagingRoot.listFiles()?.toList().orEmpty()

    private fun committedFiles(): List<File> =
        fileStore.root.listFiles()?.filter { it.isFile }.orEmpty()

    @Test
    fun `a picked image opens the editor with the recognised metadata`() = runBlocking {
        val vm = viewModel()
        vision.respondWith(
            FakeVisionClient.success(description = "一只委屈的猫", tags = listOf("委屈", "猫猫")),
        )

        vm.importBytes(StickerFixtures.png(tag = 1))

        val draft = (vm.import.value as StickerImportUiState.Editing).draft
        assertEquals("一只委屈的猫", draft.description)
        assertEquals("委屈、猫猫", draft.tagsText)
        assertEquals(StickerVisionState.OK, draft.visionState)
        assertFalse("recognition must not still be reported as running", draft.recognizing)
        assertTrue(draft.enabled)
    }

    @Test
    fun `a recognition that lands after a cancel does not reopen the editor`() = runBlocking {
        val vm = viewModel()
        val gate = CompletableDeferred<Unit>()
        vision.gate = gate
        // Parks inside the provider call, which is exactly where a real one spends its seconds.
        vm.startImport { ByteArrayInputStream(StickerFixtures.png(tag = 1)) }
        assertTrue(vm.import.value is StickerImportUiState.Editing)

        vm.cancelImport()
        gate.complete(Unit)

        assertEquals(
            "a result for an abandoned import must not resurrect its editor",
            StickerImportUiState.Idle,
            vm.import.value,
        )
        assertEquals(emptyList<File>(), stagedFiles())
    }

    @Test
    fun `a recognition that lands after a newer pick does not overwrite the newer draft`() =
        runBlocking {
            val vm = viewModel()
            val gate = CompletableDeferred<Unit>()
            vision.gate = gate
            vision.respondWith(FakeVisionClient.success(description = "第一张", tags = listOf("一")))
            vm.startImport { ByteArrayInputStream(StickerFixtures.png(tag = 1)) }

            // The person changes their mind and picks a different picture while the first is
            // still being recognised.
            vision.gate = null
            vision.respondWith(FakeVisionClient.success(description = "第二张", tags = listOf("二")))
            vm.cancelImport()
            vm.importBytes(StickerFixtures.png(tag = 2))

            gate.complete(Unit)

            assertEquals(
                "one picture's description must never be typed into another's editor",
                "第二张",
                (vm.import.value as StickerImportUiState.Editing).draft.description,
            )
        }

    @Test
    fun `a vision client that throws still leaves an editable import`() = runBlocking {
        val vm = viewModel()
        vision.throwOnDescribe = IllegalStateException("client violated its contract")

        vm.importBytes(StickerFixtures.png(tag = 1))

        val draft = (vm.import.value as StickerImportUiState.Editing).draft
        assertEquals("", draft.description)
        assertEquals(StickerVisionState.FAILED, draft.visionState)
        assertEquals(StickerVisionFailure.REQUEST_FAILED, draft.visionFailure)

        // And it is still savable, which is the property that actually matters.
        vm.save()
        assertEquals(1, vm.stickers.value.size)
    }

    @Test
    fun `an unsupported image is rejected and leaves nothing behind`() = runBlocking {
        val vm = viewModel()

        vm.importBytes(StickerFixtures.bmp(tag = 1))

        assertEquals(
            StickerImportUiState.Rejected(StickerImportRejection.UNSUPPORTED_FORMAT),
            vm.import.value,
        )
        assertEquals(emptyList<File>(), stagedFiles())
    }

    @Test
    fun `an unreadable pick is rejected`() = runBlocking {
        val vm = viewModel()

        vm.startImport { null }

        assertEquals(
            StickerImportUiState.Rejected(StickerImportRejection.UNREADABLE),
            vm.import.value,
        )
    }

    @Test
    fun `a picker that throws is treated as unreadable rather than crashing the screen`() =
        runBlocking {
            val vm = viewModel()

            vm.startImport { throw IllegalStateException("resolver blew up") }

            assertEquals(
                StickerImportUiState.Rejected(StickerImportRejection.UNREADABLE),
                vm.import.value,
            )
        }

    @Test
    fun `re-importing a stored image reports the duplicate and copies nothing`() = runBlocking {
        val vm = viewModel()
        vm.importBytes(StickerFixtures.png(tag = 1))
        vm.save()
        val committedBefore = committedFiles().size

        vm.importBytes(StickerFixtures.png(tag = 1))

        assertTrue(vm.import.value is StickerImportUiState.Duplicate)
        assertEquals(committedBefore, committedFiles().size)
    }

    @Test
    fun `a failed recognition still opens an editable editor`() = runBlocking {
        val vm = viewModel()
        vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.MODEL_NOT_CONFIGURED))

        vm.importBytes(StickerFixtures.png(tag = 1))

        val draft = (vm.import.value as StickerImportUiState.Editing).draft
        assertEquals("", draft.description)
        assertEquals(StickerVisionState.NONE, draft.visionState)
        assertEquals(StickerVisionFailure.MODEL_NOT_CONFIGURED, draft.visionFailure)
    }

    @Test
    fun `saving a failed import stores the failure alongside the hand-written text`() =
        runBlocking {
            val vm = viewModel()
            vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.REQUEST_FAILED))
            vm.importBytes(StickerFixtures.png(tag = 1))

            vm.onDescriptionChange("我自己写的")
            vm.onTagsChange("手写、猫猫")
            vm.save()

            assertEquals(StickerImportUiState.Idle, vm.import.value)
            val saved = vm.stickers.value.single().sticker
            assertEquals("我自己写的", saved.description)
            assertEquals(listOf("手写", "猫猫"), saved.tags)
            assertEquals(StickerVisionState.FAILED, saved.visionState)
        }

    @Test
    fun `saving writes the image into the library and clears staging`() = runBlocking {
        val vm = viewModel()
        vm.importBytes(StickerFixtures.png(tag = 1))

        vm.save()

        assertEquals(StickerImportUiState.Idle, vm.import.value)
        assertEquals(1, committedFiles().size)
        assertEquals(emptyList<File>(), stagedFiles())
        assertEquals(1, vm.stickers.value.size)
        assertTrue(vm.stickers.value.single().imageUri!!.startsWith("file://"))
    }

    @Test
    fun `cancelling leaves no row and no bytes`() = runBlocking {
        val vm = viewModel()
        vm.importBytes(StickerFixtures.png(tag = 1))

        vm.cancelImport()

        assertEquals(StickerImportUiState.Idle, vm.import.value)
        assertTrue(vm.stickers.value.isEmpty())
        assertEquals(emptyList<File>(), stagedFiles())
        assertEquals(emptyList<File>(), committedFiles())
    }

    @Test
    fun `editing and re-saving an existing sticker updates only its metadata`() = runBlocking {
        val vm = viewModel()
        vm.importBytes(StickerFixtures.png(tag = 1))
        vm.save()
        val saved = vm.stickers.value.single().sticker

        vm.updateMetadata(saved.id, "改过的描述", "改过、标签")

        val updated = vm.stickers.value.single().sticker
        assertEquals("改过的描述", updated.description)
        assertEquals(listOf("改过", "标签"), updated.tags)
        assertEquals(saved.relativePath, updated.relativePath)
        assertEquals(1, committedFiles().size)
    }

    @Test
    fun `enabling and disabling is reflected in the library`() = runBlocking {
        val vm = viewModel()
        vm.importBytes(StickerFixtures.png(tag = 1))
        vm.save()
        val saved = vm.stickers.value.single().sticker

        vm.setEnabled(saved.id, false)
        assertFalse(vm.stickers.value.single().sticker.enabled)

        vm.setEnabled(saved.id, true)
        assertTrue(vm.stickers.value.single().sticker.enabled)
    }

    @Test
    fun `deleting removes the sticker and its image`() = runBlocking {
        val vm = viewModel()
        vm.importBytes(StickerFixtures.png(tag = 1))
        vm.save()
        val saved = vm.stickers.value.single().sticker

        vm.delete(saved.id)

        assertTrue(vm.stickers.value.isEmpty())
        assertEquals(emptyList<File>(), committedFiles())
        assertNull(repository.getSticker(saved.id))
    }

    @Test
    fun `re-recognising a sticker with a description reports that the text was kept`() =
        runBlocking {
            val vm = viewModel()
            vm.importBytes(StickerFixtures.png(tag = 1))
            vm.save()
            val saved = vm.stickers.value.single().sticker
            vision.respondWith(FakeVisionClient.failure(StickerVisionFailure.REQUEST_FAILED))

            vm.retryRecognition(saved.id)

            assertEquals(
                StickerMessage.RECOGNITION_FAILED_KEPT_METADATA,
                vm.messages.first(),
            )
            assertEquals(
                "the person's text must survive a failed re-run",
                saved.description,
                vm.stickers.value.single().sticker.description,
            )
        }

    @Test
    fun `re-recognising successfully replaces the metadata`() = runBlocking {
        val vm = viewModel()
        vm.importBytes(StickerFixtures.png(tag = 1))
        vm.save()
        val saved = vm.stickers.value.single().sticker
        vision.respondWith(
            FakeVisionClient.success(description = "新描述", tags = listOf("新")),
        )

        vm.retryRecognition(saved.id)

        assertEquals("新描述", vm.stickers.value.single().sticker.description)
    }

    @Test
    fun `opening the library sweeps images that no row names`() = runBlocking {
        val orphan = fileStore.stage(ByteArrayInputStream(StickerFixtures.png(tag = 2)))
            .let { fileStore.commit(it, stickerId = "orphan", extension = "png")!! }
        assertTrue(fileStore.resolve(orphan)!!.exists())

        viewModel()

        assertFalse(
            "an image left by a failed delete must be reclaimed when the screen opens",
            fileStore.resolve(orphan)!!.exists(),
        )
    }

    @Test
    fun `opening the library reclaims an abandoned staging file without touching a fresh one`() =
        runBlocking {
            val abandoned = fileStore.stage(ByteArrayInputStream(StickerFixtures.png(tag = 2)))
            abandoned.file.setLastModified(0L)
            val fresh = fileStore.stage(ByteArrayInputStream(StickerFixtures.png(tag = 3)))

            viewModel()

            assertFalse(abandoned.file.exists())
            assertTrue(fresh.file.exists())
        }

    @Test
    fun `a missing image file renders as an unresolved URI rather than failing the library`() =
        runBlocking {
            val vm = viewModel()
            vm.importBytes(StickerFixtures.png(tag = 1))
            vm.save()
            val saved = vm.stickers.value.single().sticker
            // What a restore without the images looks like.
            fileStore.delete(saved.relativePath)

            val item = viewModel().stickers.value.single()

            assertNull(item.imageUri)
            assertEquals(saved.id, item.sticker.id)
        }
}
