package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

private val STREAM_MAP: Map<String, Int> = mapOf(
    "media" to AudioManager.STREAM_MUSIC,
    "ring" to AudioManager.STREAM_RING,
    "notification" to AudioManager.STREAM_NOTIFICATION,
    "alarm" to AudioManager.STREAM_ALARM,
    "voice_call" to AudioManager.STREAM_VOICE_CALL,
    "system" to AudioManager.STREAM_SYSTEM,
)

internal fun streamFor(name: String): Int? = STREAM_MAP[name]

/** How a verified write ended up — only [APPLIED] may be reported as success. */
internal enum class VolumeWriteStatus { APPLIED, PARTIAL, UNCHANGED }

/**
 * Result of comparing the index a stream reports after a write against the index we asked
 * for. `verified` is true ONLY when the stream reports exactly the requested index; a write
 * that silently no-ops (UNCHANGED) or lands somewhere else (PARTIAL) must not pass.
 */
internal data class VolumeWriteCheck(
    val status: VolumeWriteStatus,
    val requestedPercent: Int,
    val targetStep: Int,
    val actualIndex: Int,
) {
    val verified: Boolean get() = status == VolumeWriteStatus.APPLIED
}

/** Round a 0-100 percent into an AudioManager index for a stream with [max] steps. */
internal fun percentToStep(percent: Int, max: Int): Int =
    (percent.coerceIn(0, 100) * max + 50) / 100

/** Report an index as a 0-100 percent, mirroring get_volume's own scale. */
internal fun indexToPercent(index: Int, max: Int): Int =
    if (max > 0) (index * 100 + max / 2) / max else 0

/**
 * Classify a write as applied only when the read-back index equals the requested step.
 * When the requested step already equals the pre-write index (the stream is already where we
 * want it), the write is trivially verified — the desired state holds.
 */
internal fun checkVolumeWrite(
    requestedPercent: Int,
    targetStep: Int,
    preIndex: Int,
    postIndex: Int,
): VolumeWriteCheck {
    val status = when {
        postIndex == targetStep -> VolumeWriteStatus.APPLIED
        postIndex == preIndex -> VolumeWriteStatus.UNCHANGED
        else -> VolumeWriteStatus.PARTIAL
    }
    return VolumeWriteCheck(status, requestedPercent.coerceIn(0, 100), targetStep, postIndex)
}

private const val READBACK_MAX_ATTEMPTS = 5
private const val READBACK_RETRY_MS = 50L

/**
 * Read the stream index back after a write instead of trusting the API's return value.
 * AudioManager.setStreamVolume does not report whether a write actually landed, so give the
 * framework a short window to settle and stop as soon as [targetStep] is observed. Returns
 * the last observed index (== [targetStep] on success).
 */
private fun readbackIndex(am: AudioManager, stream: Int, targetStep: Int): Int {
    var observed = am.getStreamVolume(stream)
    var attempts = 1
    while (observed != targetStep && attempts < READBACK_MAX_ATTEMPTS) {
        SystemClock.sleep(READBACK_RETRY_MS)
        observed = am.getStreamVolume(stream)
        attempts++
    }
    return observed
}

fun getVolumeTool(context: Context): Tool = Tool(
    name = "get_volume",
    description = """
        Get the current volume of an audio stream (media, ring, notification, alarm,
        voice_call, system).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", "Stream name: media, ring, notification, alarm, voice_call, system (default media)")
                })
            }
        )
    },
    execute = {
        val params = it.jsonObject
        val name = params["stream"]?.jsonPrimitive?.contentOrNull ?: "media"
        val streamInt = streamFor(name)
        if (streamInt == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "unknown stream: $name") }.toString()
                )
            )
        }
        val am = context.getSystemService(AudioManager::class.java)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "AudioManager unavailable") }.toString()
                )
            )
        val volume = am.getStreamVolume(streamInt)
        val max = am.getStreamMaxVolume(streamInt)
        val percent = if (max > 0) (volume * 100 + max / 2) / max else 0
        val payload = buildJsonObject {
            put("stream", name)
            put("volume", volume)
            put("max", max)
            put("percent", percent)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun setVolumeTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "set_volume",
    description = """
        Set the volume of an audio stream (0-100 percent). Setting ring/notification
        streams requires DND access.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", "Stream name: media, ring, notification, alarm, voice_call, system")
                })
                put("percent", buildJsonObject {
                    put("type", "integer")
                    put("description", "Target volume as a percentage (0-100)")
                })
            },
            required = listOf("stream", "percent")
        )
    },
    execute = {
        wakeScreenIfNeeded(context)
        val params = it.jsonObject
        val name = params["stream"]?.jsonPrimitive?.contentOrNull
            ?: error("stream is required")
        val percentRaw = params["percent"]?.jsonPrimitive?.intOrNull
            ?: error("percent is required")
        val streamInt = streamFor(name)
        if (streamInt == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "unknown stream: $name") }.toString()
                )
            )
        }
        if ((name == "ring" || name == "notification") && !PermissionHelper.hasDndAccess(context)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "DND access not granted; cannot modify ring/notification volume")
                    }.toString()
                )
            )
        }
        val am = context.getSystemService(AudioManager::class.java)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "AudioManager unavailable") }.toString()
                )
            )
        val percent = percentRaw.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(streamInt)
        val targetStep = percentToStep(percent, max)
        val preIndex = am.getStreamVolume(streamInt)
        val observedIndex = try {
            am.setStreamVolume(streamInt, targetStep, 0)
            readbackIndex(am, streamInt, targetStep)
        } catch (_: SecurityException) {
            // The ring/notification DND pre-check above already ran, so a SecurityException
            // here is the framework refusing the write (e.g. missing MODIFY_AUDIO_SETTINGS) —
            // NOT a DND grant problem. Report it as such instead of mislabeling it as DND.
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("stream", name)
                        put("error", "not permitted to change $name volume")
                    }.toString()
                )
            )
        }
        val check = checkVolumeWrite(percent, targetStep, preIndex, observedIndex)
        val actualPercent = indexToPercent(observedIndex, max)
        val statusNote =
            if (check.verified) "(${observedIndex}/$max, verified)" else "NOT verified"
        streamer.streamIfHeadless(
            invocationContext,
            "SetVolume $name ${percent}% -> read back $actualPercent% $statusNote"
        )
        val payload = if (check.verified) {
            buildJsonObject {
                put("success", true)
                put("stream", name)
                put("percent", percent)
                put("index", observedIndex)
                put("max", max)
                put("actual_percent", actualPercent)
                put("verified", true)
            }
        } else {
            val reason = when (check.status) {
                VolumeWriteStatus.UNCHANGED ->
                    "volume did not change: still index $observedIndex/$max (requested $targetStep)"
                VolumeWriteStatus.PARTIAL ->
                    "volume only reached index $observedIndex/$max (requested $targetStep)"
                VolumeWriteStatus.APPLIED -> "volume applied" // unreachable in this branch
            }
            buildJsonObject {
                put("success", false)
                put("stream", name)
                put("error", reason)
                put("requested_percent", percent)
                put("target_index", targetStep)
                put("index", observedIndex)
                put("max", max)
                put("actual_percent", actualPercent)
                put("verified", false)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
