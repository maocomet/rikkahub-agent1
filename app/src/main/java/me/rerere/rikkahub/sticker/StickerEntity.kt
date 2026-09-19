package me.rerere.rikkahub.sticker

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of the shared sticker library.
 *
 * The image bytes are NOT here — see [relativePath]. Two unique indices carry the storage
 * contracts, and both are enforced by the schema rather than by caller discipline:
 *
 *  - `checksum` unique — "the same picture is only ever stored once" is an INSERT-level guarantee,
 *    not a pre-check that a race can slip past. The importer still looks the checksum up first so
 *    it can report *which* sticker the bytes already belong to; the index is what makes that an
 *    optimisation rather than the correctness argument.
 *  - `relative_path` unique — one row per file. The orphan sweep deletes files with no row, so a
 *    duplicate path would mean one of the two rows could never be deleted without taking the
 *    other's image with it.
 *
 * [visionState] and [visionFailure] are stored as their enum names. Decoding is deliberately
 * tolerant (see [toSticker]) because these are read on every library render and a value this build
 * does not recognise must not be able to break the page.
 */
@Entity(
    tableName = "stickers",
    indices = [
        Index(value = ["checksum"], unique = true),
        Index(value = ["relative_path"], unique = true),
        Index(value = ["created_at_ms"]),
    ],
)
data class StickerEntity(
    @PrimaryKey
    @ColumnInfo(name = "sticker_id")
    val stickerId: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "width")
    val width: Int,
    @ColumnInfo(name = "height")
    val height: Int,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "tags_json")
    val tagsJson: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "checksum")
    val checksum: String,
    @ColumnInfo(name = "vision_state")
    val visionState: String,
    @ColumnInfo(name = "vision_error_code")
    val visionErrorCode: String? = null,
)

fun StickerEntity.toSticker(): Sticker = Sticker(
    id = stickerId,
    relativePath = relativePath,
    mimeType = mimeType,
    width = width,
    height = height,
    description = description,
    tags = StickerTags.decode(tagsJson),
    enabled = enabled,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    checksum = checksum,
    // An unrecognised state name degrades to NONE rather than throwing. NONE is the safe reading:
    // it claims nothing happened, so the UI offers 重新识别 instead of asserting a result it
    // cannot substantiate.
    visionState = StickerVisionState.entries.firstOrNull { it.name == visionState }
        ?: StickerVisionState.NONE,
    visionFailure = StickerVisionFailure.fromCode(visionErrorCode),
)

fun Sticker.toEntity(): StickerEntity = StickerEntity(
    stickerId = id,
    relativePath = relativePath,
    mimeType = mimeType,
    width = width,
    height = height,
    description = description,
    tagsJson = StickerTags.encode(tags),
    enabled = enabled,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    checksum = checksum,
    visionState = visionState.name,
    visionErrorCode = visionFailure?.name,
)
