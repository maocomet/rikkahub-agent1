package me.rerere.rikkahub.ui.pages.sticker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.sticker.Sticker
import me.rerere.rikkahub.sticker.StickerImportCoordinator
import me.rerere.rikkahub.sticker.StickerImportRejection
import me.rerere.rikkahub.sticker.StickerImportStart
import me.rerere.rikkahub.sticker.StickerRecognition
import me.rerere.rikkahub.sticker.StickerRepository
import me.rerere.rikkahub.sticker.StickerRetryOutcome
import me.rerere.rikkahub.sticker.StickerTags
import me.rerere.rikkahub.sticker.StickerVisionFailure
import me.rerere.rikkahub.sticker.StickerVisionState

/** One library entry, with the URI Coil needs to draw it. */
data class StickerItem(
    val sticker: Sticker,
    /**
     * `file://` URI of the image, or null when the file is missing.
     *
     * Null is reachable — a restore can bring back rows without their images in this phase — and
     * the grid renders a placeholder for it rather than failing to draw the cell.
     */
    val imageUri: String?,
)

/**
 * The import sheet's editing state.
 *
 * Presentation only. The staged file and the recognition outcome live on the ViewModel, because
 * everything the sheet does is edit *around* them and neither should be reconstructible from a
 * screen state.
 */
data class StickerDraftUi(
    val imageUri: String,
    val description: String,
    val tagsText: String,
    val enabled: Boolean,
    val recognizing: Boolean,
    val saving: Boolean,
    val visionState: StickerVisionState,
    val visionFailure: StickerVisionFailure?,
)

sealed interface StickerImportUiState {
    data object Idle : StickerImportUiState

    /** Copying, validating and de-duplicating. Recognition has not started yet. */
    data object Preparing : StickerImportUiState

    data class Editing(val draft: StickerDraftUi) : StickerImportUiState

    /** The bytes are already in the library. Nothing was copied a second time. */
    data class Duplicate(val existing: Sticker) : StickerImportUiState

    data class Rejected(val reason: StickerImportRejection) : StickerImportUiState
}

/**
 * One-shot messages for the page to surface.
 *
 * Modelled as ids rather than strings so the wording stays in the string resources, where the
 * translations already are.
 */
enum class StickerMessage {
    SAVED,

    /**
     * The row could not be written. Distinct from [RECOGNITION_FAILED]: recognition failing is a
     * cosmetic outcome the person can work around, whereas this means the sticker was not saved at
     * all, and saying the wrong one would send them looking in the wrong place.
     */
    SAVE_FAILED,
    RECOGNITION_FAILED,
    RECOGNITION_FAILED_KEPT_METADATA,
    DELETED,
}

/**
 * The shared sticker library's read model and actions.
 *
 * The sweep runs once here rather than on every render: it exists to reclaim what a crash or a
 * failed file delete left behind, and both of those are rare enough that doing it per frame would
 * be a directory listing nobody asked for. Opening the screen is the natural moment — it is when
 * somebody would notice the library disagreeing with itself.
 */
class StickerLibraryVM(
    private val repository: StickerRepository,
    private val importCoordinator: StickerImportCoordinator,
    /**
     * Injectable so the read model can be exercised on the JVM. Defaults to the ViewModel's own
     * scope; tests pass an unconfined scope, which also keeps `viewModelScope` (and therefore
     * `Dispatchers.Main`, unavailable in a plain unit test) untouched.
     */
    private val injectedScope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = injectedScope ?: viewModelScope

    val stickers: StateFlow<List<StickerItem>> = repository.observeLibrary()
        .map { list -> list.map { it.toItem() } }
        .stateIn(workScope, SharingStarted.Eagerly, emptyList())

    private val _import = MutableStateFlow<StickerImportUiState>(StickerImportUiState.Idle)
    val import: StateFlow<StickerImportUiState> = _import.asStateFlow()

    private val messageChannel = Channel<StickerMessage>(Channel.BUFFERED)
    val messages: Flow<StickerMessage> = messageChannel.receiveAsFlow()

    /** The staged file behind the current editor, if one is open. */
    private var staged: StickerImportStart.Staged? = null

    /** The outcome to persist at save time, edited around but never reconstructed by the UI. */
    private var recognition: StickerRecognition = EMPTY_RECOGNITION

    init {
        workScope.launch {
            // Best-effort: a failure to sweep is not a failure to show the library.
            runCatching { repository.sweepStaging() }
            runCatching { repository.sweepOrphans() }
        }
    }

    // ── Import ───────────────────────────────────────────────────────────────────────────────

    /**
     * Starts an import from a picked image.
     *
     * [openSource] rather than a `Uri` so this class holds no Android type and the whole import
     * state machine — preparing, editing, duplicate, rejected — is drivable on the JVM. The page
     * supplies the resolver call; returning null from it is how "the picker handed us something
     * unreadable" reaches [StickerImportRejection.UNREADABLE].
     *
     * Nothing is written to Room on this path: at the end of it the person is looking at an
     * editor, and the only durable artefact so far is a file in staging that cancelling deletes.
     */
    fun startImport(openSource: () -> InputStream?) {
        if (_import.value is StickerImportUiState.Preparing) return
        _import.value = StickerImportUiState.Preparing
        workScope.launch {
            val stream = runCatching { openSource() }.getOrNull()
            if (stream == null) {
                _import.value = StickerImportUiState.Rejected(StickerImportRejection.UNREADABLE)
                return@launch
            }

            when (val start = importCoordinator.beginImport(stream)) {
                is StickerImportStart.Rejected -> {
                    _import.value = StickerImportUiState.Rejected(start.reason)
                }
                is StickerImportStart.Duplicate -> {
                    // The notice itself carries the message — it names the sticker the bytes
                    // already belong to. A toast on top of it would say the same thing twice.
                    _import.value = StickerImportUiState.Duplicate(start.existing)
                }
                is StickerImportStart.Staged -> {
                    staged = start
                    _import.value = StickerImportUiState.Editing(
                        StickerDraftUi(
                            imageUri = "file://${start.staged.file.absolutePath}",
                            description = "",
                            tagsText = "",
                            enabled = true,
                            recognizing = true,
                            saving = false,
                            visionState = StickerVisionState.NONE,
                            visionFailure = null,
                        ),
                    )
                    // Recognition is a second step so the editor is already on screen and usable
                    // while it runs. The person can start typing a description without waiting for
                    // a provider round trip, and never has to watch a blank page.
                    val result = importCoordinator.recognize(start)
                    // A provider call takes seconds, and the person is free to cancel or pick
                    // another image while it runs. Both replace `staged`, and applying this result
                    // anyway would type one picture's description into another's editor.
                    if (staged?.stickerId != start.stickerId) return@launch
                    recognition = result
                    updateDraft { draft ->
                        draft.copy(
                            description = result.description,
                            tagsText = result.tags.joinToString(TAG_SEPARATOR),
                            recognizing = false,
                            visionState = result.state,
                            visionFailure = result.failure,
                        )
                    }
                }
            }
        }
    }

    fun onDescriptionChange(value: String) = updateDraft { it.copy(description = value) }

    fun onTagsChange(value: String) = updateDraft { it.copy(tagsText = value) }

    fun onEnabledChange(value: Boolean) = updateDraft { it.copy(enabled = value) }

    /**
     * Recognises the staged image again, from inside the editor.
     *
     * Distinct from the retry on a saved sticker: here nothing is persisted yet, so overwriting the
     * fields is exactly what was asked for and there is no earlier work to protect.
     */
    fun recognizeDraft() {
        val start = staged ?: return
        val current = _import.value as? StickerImportUiState.Editing ?: return
        if (current.draft.recognizing) return
        updateDraft { it.copy(recognizing = true) }
        workScope.launch {
            val result = importCoordinator.recognize(start)
            if (staged?.stickerId != start.stickerId) return@launch
            recognition = result
            updateDraft { draft ->
                draft.copy(
                    description = result.description,
                    tagsText = result.tags.joinToString(TAG_SEPARATOR),
                    recognizing = false,
                    visionState = result.state,
                    visionFailure = result.failure,
                )
            }
            if (result.state != StickerVisionState.OK) {
                messageChannel.trySend(StickerMessage.RECOGNITION_FAILED)
            }
        }
    }

    fun save() {
        val start = staged ?: return
        val current = _import.value as? StickerImportUiState.Editing ?: return
        if (current.draft.saving) return
        updateDraft { it.copy(saving = true) }
        workScope.launch {
            val outcome = importCoordinator.commit(
                start = start,
                description = current.draft.description.trim(),
                tags = StickerTags.splitDelimited(current.draft.tagsText),
                enabled = current.draft.enabled,
                recognition = recognition,
            )
            // Only this save's own editor is closed. A cancel or a fresh pick during the write
            // has already replaced it, and clearing the state unconditionally would close an
            // editor the person is currently looking at.
            if (staged?.stickerId == start.stickerId) {
                staged = null
                recognition = EMPTY_RECOGNITION
                _import.value = StickerImportUiState.Idle
            }
            if (outcome.isSuccess) {
                messageChannel.trySend(StickerMessage.SAVED)
            } else {
                // The coordinator has already removed the placed image, so the library is exactly
                // as it was and there is nothing for the person to clean up.
                messageChannel.trySend(StickerMessage.SAVE_FAILED)
            }
        }
    }

    /** Abandons the editor. No row was ever written, so this only deletes staged bytes. */
    fun cancelImport() {
        val start = staged
        staged = null
        recognition = EMPTY_RECOGNITION
        _import.value = StickerImportUiState.Idle
        if (start != null) {
            workScope.launch { importCoordinator.cancel(start) }
        }
    }

    /** Closes a duplicate or rejection notice. */
    fun dismissImportNotice() {
        _import.value = StickerImportUiState.Idle
    }

    // ── Library actions ──────────────────────────────────────────────────────────────────────

    fun setEnabled(stickerId: String, enabled: Boolean) {
        workScope.launch { repository.setEnabled(stickerId, enabled) }
    }

    fun updateMetadata(stickerId: String, description: String, tagsText: String) {
        workScope.launch {
            val existing = repository.getSticker(stickerId) ?: return@launch
            repository.updateMetadata(
                stickerId = stickerId,
                description = description.trim(),
                tags = StickerTags.splitDelimited(tagsText),
                visionState = existing.visionState,
                visionFailure = existing.visionFailure,
            )
        }
    }

    fun delete(stickerId: String) {
        workScope.launch {
            if (repository.deleteSticker(stickerId)) {
                messageChannel.trySend(StickerMessage.DELETED)
            }
        }
    }

    fun retryRecognition(stickerId: String) {
        workScope.launch {
            when (val outcome = importCoordinator.retryRecognition(stickerId)) {
                is StickerRetryOutcome.Updated -> Unit
                is StickerRetryOutcome.Failed ->
                    messageChannel.trySend(
                        if (outcome.preservedMetadata) {
                            StickerMessage.RECOGNITION_FAILED_KEPT_METADATA
                        } else {
                            StickerMessage.RECOGNITION_FAILED
                        },
                    )
                StickerRetryOutcome.NotFound -> Unit
            }
        }
    }

    private fun updateDraft(transform: (StickerDraftUi) -> StickerDraftUi) {
        val current = _import.value as? StickerImportUiState.Editing ?: return
        _import.value = StickerImportUiState.Editing(transform(current.draft))
    }

    private fun Sticker.toItem(): StickerItem = StickerItem(
        sticker = this,
        imageUri = repository.absolutePathOf(relativePath)?.let { "file://${it.absolutePath}" },
    )

    private companion object {
        /** The separator the editor writes back out; [StickerTags.DELIMITERS] accepts it back. */
        const val TAG_SEPARATOR = "、"

        val EMPTY_RECOGNITION = StickerRecognition(
            description = "",
            tags = emptyList(),
            state = StickerVisionState.NONE,
            failure = null,
        )
    }
}
