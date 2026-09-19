package me.rerere.rikkahub.sticker

import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.files.ImageFormat
import me.rerere.rikkahub.data.files.ImageFormatDetector

/** Pixel dimensions read from an image header. */
data class StickerDimensions(val width: Int, val height: Int)

/**
 * Reads an image's dimensions without decoding it.
 *
 * A seam because the real implementation is `BitmapFactory`, which needs an Android runtime, while
 * everything that *acts* on the answer — reject a corrupt file, store the size — is logic worth
 * testing on the JVM.
 */
fun interface StickerDimensionReader {
    fun read(file: File): StickerDimensions?
}

class BitmapStickerDimensionReader : StickerDimensionReader {
    override fun read(file: File): StickerDimensions? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return StickerDimensions(width = options.outWidth, height = options.outHeight)
    }
}

/** Why a picked file never became a staged import. */
enum class StickerImportRejection {
    /** The stream could not be read or copied. */
    UNREADABLE,

    /** Not one of the supported still-image formats — including a non-image with an image name. */
    UNSUPPORTED_FORMAT,

    /** Recognisable as a supported format, but its header does not carry usable dimensions. */
    CORRUPT_IMAGE,
}

/**
 * The result of picking a file: bytes are staged, or nothing is.
 *
 * [Duplicate] is a success-shaped outcome rather than a rejection, because from the person's point
 * of view nothing went wrong — what they picked is already in the library, and the useful response
 * is to say so and point at it.
 */
sealed interface StickerImportStart {
    data class Staged(
        val staged: StagedStickerFile,
        /** Generated up front so the committed file and the row that names it share one id. */
        val stickerId: String,
        val mimeType: String,
        val extension: String,
        val width: Int,
        val height: Int,
    ) : StickerImportStart

    data class Duplicate(val existing: Sticker) : StickerImportStart

    data class Rejected(val reason: StickerImportRejection) : StickerImportStart
}

/**
 * The recognition outcome carried into the editor and then into the row.
 *
 * [state] and [failure] are stored as-is at save time, including when the person hand-edits the
 * text: a sticker whose recognition failed and whose description the person then wrote themselves
 * is still a sticker whose recognition failed, and recording it as a success would erase the only
 * evidence that the model is misconfigured.
 */
data class StickerRecognition(
    val description: String,
    val tags: List<String>,
    val state: StickerVisionState,
    val failure: StickerVisionFailure?,
) {
    companion object {
        /**
         * Maps a vision outcome to what gets stored.
         *
         * "No model configured" is [StickerVisionState.NONE] rather than FAILED: nothing was
         * attempted, the feature is simply switched off, and the library should say 未识别 and
         * point at the setting instead of reporting a failure the person never caused. Every other
         * reason is a real attempt that really failed.
         */
        fun from(outcome: StickerVisionOutcome): StickerRecognition = when (outcome) {
            is StickerVisionOutcome.Success -> StickerRecognition(
                description = outcome.metadata.description,
                tags = outcome.metadata.tags,
                state = StickerVisionState.OK,
                failure = null,
            )
            is StickerVisionOutcome.Failure -> StickerRecognition(
                description = "",
                tags = emptyList(),
                state = if (outcome.reason == StickerVisionFailure.MODEL_NOT_CONFIGURED) {
                    StickerVisionState.NONE
                } else {
                    StickerVisionState.FAILED
                },
                failure = outcome.reason,
            )
        }
    }
}

/** What a re-run against an already-saved sticker did. */
sealed interface StickerRetryOutcome {
    data class Updated(val sticker: Sticker) : StickerRetryOutcome

    /**
     * The attempt failed. [preservedMetadata] says whether the row was left exactly as it was —
     * see [StickerImportCoordinator.retryRecognition] for when a failure is allowed to write.
     */
    data class Failed(
        val reason: StickerVisionFailure,
        val sticker: Sticker,
        val preservedMetadata: Boolean,
    ) : StickerRetryOutcome

    data object NotFound : StickerRetryOutcome
}

/**
 * The import flow: stage → validate → de-duplicate → recognise → commit.
 *
 * ## Why the row is written last
 *
 * Nothing reaches the database until the person presses save. An earlier design wrote the row at
 * pick time and treated the editor as a post-hoc edit, which means backing out of the editor
 * either leaves a sticker nobody asked for or has to delete one — and a delete is a destructive
 * operation to perform on the person's behalf in response to *cancelling*. Staging keeps cancel
 * non-destructive: it deletes bytes that no row ever referenced, and the library never shows a
 * sticker the person did not confirm.
 *
 * ## Why recognition cannot lose the image
 *
 * Recognition runs against the staged file and can only ever produce a [StickerRecognition]. Every
 * failure it can report — no model, wrong model, dead provider, bad network, unparseable reply —
 * leaves the bytes exactly where they are and the save still possible. The one thing that may
 * delete an image is the commit itself failing, because a placed file with no row is exactly the
 * orphan the sweep would otherwise have to clean up later.
 *
 * ## Ordering of the two checks that must not be reordered
 *
 * The duplicate check happens before recognition, not after. Recognising first would spend a
 * provider call producing metadata for bytes the library is about to discard, and the person would
 * wait for it.
 */
class StickerImportCoordinator(
    private val repository: StickerRepository,
    private val visionClient: StickerVisionClient,
    private val dimensionReader: StickerDimensionReader,
    private val newId: () -> String = { Uuid.random().toString() },
) {

    /**
     * Copies [source] into staging and decides whether it can become a sticker.
     *
     * Every rejection path removes the staged file before returning, so a caller never holds a
     * [StickerImportStart.Staged] it did not get told about and never has to clean up after a
     * rejection itself.
     */
    suspend fun beginImport(source: InputStream): StickerImportStart {
        val staged = runCatching { repository.stageImport(source) }
            .getOrElse { return StickerImportStart.Rejected(StickerImportRejection.UNREADABLE) }

        // Header detection, not the file's name or the picker's declared MIME: both are supplied
        // by whatever is on the other side of the picker, and the name is the one field that can
        // carry a path separator.
        val detected = ImageFormatDetector.detect(staged.file)
        if (detected == null || detected.format !in SUPPORTED_FORMATS) {
            repository.discardStaged(staged)
            return StickerImportStart.Rejected(StickerImportRejection.UNSUPPORTED_FORMAT)
        }

        val dimensions = dimensionReader.read(staged.file)
        if (dimensions == null) {
            // A supported header with unreadable dimensions is a truncated or damaged file. Saving
            // it would put a thumbnail in the library that cannot be rendered, so it is refused
            // here rather than stored and discovered later.
            repository.discardStaged(staged)
            return StickerImportStart.Rejected(StickerImportRejection.CORRUPT_IMAGE)
        }

        val duplicate = repository.findDuplicate(staged.checksum)
        if (duplicate != null) {
            repository.discardStaged(staged)
            return StickerImportStart.Duplicate(duplicate)
        }

        return StickerImportStart.Staged(
            staged = staged,
            stickerId = newId(),
            mimeType = detected.format.mimeType,
            extension = detected.format.defaultExtension,
            width = dimensions.width,
            height = dimensions.height,
        )
    }

    /**
     * Recognises a staged import. Never throws; a failure comes back as a [StickerRecognition].
     *
     * [StickerVisionClient] documents that implementations must not throw, and
     * [ProviderStickerVisionClient] honours that. This still guards the call, because the contract
     * is worth *enforcing* rather than trusting: the cost of a client that breaks it is the
     * person's import, and the whole point of this phase is that recognition cannot take an import
     * down. A throwing client degrades to the same outcome as a failed request.
     */
    suspend fun recognize(start: StickerImportStart.Staged): StickerRecognition =
        StickerRecognition.from(describeSafely(start.staged.file))

    private suspend fun describeSafely(file: File): StickerVisionOutcome = try {
        visionClient.describe(file)
    } catch (cancellation: CancellationException) {
        // Cancellation is not a recognition failure. Swallowing it would leave a cancelled scope
        // running, which is a worse bug than the one this guard exists to prevent.
        throw cancellation
    } catch (_: Throwable) {
        StickerVisionOutcome.Failure(StickerVisionFailure.REQUEST_FAILED)
    }

    /**
     * Saves the sticker: places the image under its final name, then writes the row.
     *
     * If the row insert throws — losing a race to a concurrent import of the same bytes is the
     * realistic case, since the unique `checksum` index is the actual guarantee and
     * [beginImport]'s lookup is only an optimisation — the placed image is removed on the way out.
     * The alternative is a file no row names, which is invisible until the next library open.
     */
    suspend fun commit(
        start: StickerImportStart.Staged,
        description: String,
        tags: List<String>,
        enabled: Boolean,
        recognition: StickerRecognition,
    ): Result<Sticker> {
        val relativePath = repository.commitStaged(start.staged, start.stickerId, start.extension)
            ?: return Result.failure(IOException("sticker_image_commit_failed"))

        return runCatching {
            repository.insertSticker(
                stickerId = start.stickerId,
                relativePath = relativePath,
                mimeType = start.mimeType,
                width = start.width,
                height = start.height,
                description = description,
                tags = tags,
                checksum = start.checksum,
                enabled = enabled,
                visionState = recognition.state,
                visionFailure = recognition.failure,
            )
        }.onFailure {
            repository.deleteFileFor(relativePath)
        }
    }

    /** Abandons a staged import. No row was ever written, so this only removes bytes. */
    suspend fun cancel(start: StickerImportStart.Staged) {
        repository.discardStaged(start.staged)
    }

    /**
     * Re-runs recognition against an already-saved sticker.
     *
     * A **successful** re-run replaces the description and tags — that is what the person asked
     * for, and refusing to overwrite their earlier text would make the button do nothing.
     *
     * A **failed** re-run is asymmetric on purpose. If the sticker has a description, the row is
     * left completely untouched: the person may have typed that text themselves, and wiping it
     * because a provider call failed would destroy work in response to an error that cost them
     * nothing. Only a sticker with no description anywhere is allowed to record the failure, which
     * is the case where 识图失败 is new and useful information rather than a downgrade.
     */
    suspend fun retryRecognition(stickerId: String): StickerRetryOutcome {
        val sticker = repository.getSticker(stickerId) ?: return StickerRetryOutcome.NotFound
        val file = repository.absolutePathOf(sticker.relativePath) ?: return StickerRetryOutcome.NotFound

        val recognition = StickerRecognition.from(visionClient.describe(file))

        if (recognition.state != StickerVisionState.OK) {
            val reason = recognition.failure ?: StickerVisionFailure.UNKNOWN
            if (sticker.description.isNotBlank()) {
                return StickerRetryOutcome.Failed(
                    reason = reason,
                    sticker = sticker,
                    preservedMetadata = true,
                )
            }
            repository.updateMetadata(
                stickerId = stickerId,
                description = sticker.description,
                tags = sticker.tags,
                visionState = recognition.state,
                visionFailure = reason,
            )
            return StickerRetryOutcome.Failed(
                reason = reason,
                sticker = repository.getSticker(stickerId) ?: sticker,
                preservedMetadata = false,
            )
        }

        repository.updateMetadata(
            stickerId = stickerId,
            description = recognition.description,
            tags = recognition.tags,
            visionState = StickerVisionState.OK,
            visionFailure = null,
        )
        return StickerRetryOutcome.Updated(
            sticker = repository.getSticker(stickerId) ?: sticker,
        )
    }

    companion object {
        /**
         * The formats this phase accepts.
         *
         * Animated GIFs are stored and rendered as-is — the app's Coil setup already decodes them —
         * so GIF is not treated as a degraded case here. Everything outside this set is refused
         * with an explicit message rather than saved as an opaque blob.
         */
        val SUPPORTED_FORMATS: Set<ImageFormat> = setOf(
            ImageFormat.PNG,
            ImageFormat.JPEG,
            ImageFormat.WEBP,
            ImageFormat.GIF,
        )
    }
}
