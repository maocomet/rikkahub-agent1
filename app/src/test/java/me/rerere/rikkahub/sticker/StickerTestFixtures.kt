package me.rerere.rikkahub.sticker

import java.io.File

/**
 * Byte fixtures that satisfy [me.rerere.rikkahub.data.files.ImageFormatDetector].
 *
 * Only the headers are real — detection is header-first, so a file that starts correctly is
 * detected correctly whatever follows. [tag] makes two files of the same format differ in content,
 * which is what the de-duplication tests need.
 */
object StickerFixtures {

    fun png(tag: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            payload(tag)

    fun jpeg(tag: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + payload(tag)

    fun gif(tag: Int): ByteArray =
        "GIF89a".toByteArray(Charsets.US_ASCII) + payload(tag)

    fun webp(tag: Int): ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) +
            ByteArray(4) +
            "WEBP".toByteArray(Charsets.US_ASCII) +
            payload(tag)

    /** Detects as BMP, which is a real image format this phase does not accept. */
    fun bmp(tag: Int): ByteArray =
        byteArrayOf(0x42, 0x4D) + payload(tag)

    /** Not an image at all, whatever it is named. */
    fun text(tag: Int): ByteArray = "this is not an image $tag".toByteArray(Charsets.UTF_8)

    /** A supported header with nothing behind it, for the truncated-file paths. */
    fun truncatedPng(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun payload(tag: Int): ByteArray = ByteArray(32) { (it + tag).toByte() }
}

/** Answers with one fixed size, or null to stand in for a file whose header cannot be decoded. */
class FakeDimensionReader(
    private val dimensions: StickerDimensions? = StickerDimensions(width = 64, height = 48),
) : StickerDimensionReader {
    var calls: Int = 0
        private set

    override fun read(file: File): StickerDimensions? {
        calls++
        return dimensions
    }
}

/** Returns one canned outcome, and records what it was asked about. */
class FakeVisionClient(
    private var outcome: StickerVisionOutcome,
) : StickerVisionClient {
    val described = mutableListOf<File>()

    /**
     * Set to make the next call throw — a client violating the interface's no-throw contract.
     * The importer is expected to survive it.
     */
    var throwOnDescribe: Throwable? = null

    /** Set the outcome the next call should return. */
    fun respondWith(next: StickerVisionOutcome) {
        outcome = next
    }

    override suspend fun describe(imageFile: File): StickerVisionOutcome {
        described += imageFile
        throwOnDescribe?.let {
            throwOnDescribe = null
            throw it
        }
        return outcome
    }

    companion object {
        fun success(
            description: String = "一只猫缩成一团，看起来委屈又有点生气",
            tags: List<String> = listOf("委屈", "生气", "猫猫"),
        ): StickerVisionOutcome = StickerVisionOutcome.Success(
            metadata = StickerVisionMetadata(description = description, tags = tags),
            providerLabel = "test-provider",
        )

        fun failure(reason: StickerVisionFailure): StickerVisionOutcome =
            StickerVisionOutcome.Failure(reason)
    }
}
