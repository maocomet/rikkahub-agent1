package me.rerere.rikkahub.data.ai.tools.local

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeToolTest {

    // get_volume / set_volume success paths require AudioManager — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `set_volume throws when stream is missing`() {
        // Tool calls error("stream is required") -> IllegalStateException
        val tool = setVolumeTool(NULL_CONTEXT)
        execTool(tool, """{"percent":50}""")
    }

    @Test(expected = IllegalStateException::class)
    fun `set_volume throws when percent is missing`() {
        // Tool calls error("percent is required") -> IllegalStateException
        val tool = setVolumeTool(NULL_CONTEXT)
        execTool(tool, """{"stream":"media"}""")
    }

    @Test
    fun `set_volume returns error envelope for unknown stream`() {
        // Unknown-stream validation runs before any Context call.
        val tool = setVolumeTool(NULL_CONTEXT)
        val result = execTool(tool, """{"stream":"foo","percent":50}""")
        assertTrue(
            "expected unknown-stream error, got: $result",
            result.contains("\"error\"") && result.contains("unknown stream")
        )
    }

    // ---- pure stream-name -> AudioManager constant mapping ----

    @Test
    fun `stream names map to the expected AudioManager constants`() {
        assertEquals(AudioManager.STREAM_MUSIC, streamFor("media"))
        assertEquals(AudioManager.STREAM_RING, streamFor("ring"))
        assertEquals(AudioManager.STREAM_NOTIFICATION, streamFor("notification"))
        assertEquals(AudioManager.STREAM_ALARM, streamFor("alarm"))
        assertEquals(AudioManager.STREAM_VOICE_CALL, streamFor("voice_call"))
        assertEquals(AudioManager.STREAM_SYSTEM, streamFor("system"))
    }

    @Test
    fun `media stream maps to the music stream and is not the ringer`() {
        // "media" must target the real media stream (STREAM_MUSIC). A wrong mapping here
        // (e.g. hitting ringer/system) would make set_volume look successful while the
        // audible media volume never moves.
        assertEquals(AudioManager.STREAM_MUSIC, streamFor("media"))
        assertFalse(streamFor("media") == AudioManager.STREAM_RING)
        assertFalse(streamFor("media") == AudioManager.STREAM_SYSTEM)
    }

    @Test
    fun `unknown stream name resolves to null`() {
        assertNull(streamFor("bogus"))
        assertNull(streamFor("MEDIA")) // case-sensitive by design
    }

    // ---- pure percent -> index rounding (percentToStep) ----

    @Test
    fun `percent to step uses max steps and rounds to nearest`() {
        val max = 15
        assertEquals(0, percentToStep(0, max))
        assertEquals(3, percentToStep(20, max))  // (300 + 50) / 100
        assertEquals(8, percentToStep(50, max))  // (750 + 50) / 100
        assertEquals(11, percentToStep(70, max)) // (1050 + 50) / 100
        assertEquals(12, percentToStep(80, max)) // (1200 + 50) / 100
        assertEquals(15, percentToStep(100, max))
    }

    @Test
    fun `percent to step clamps out-of-range input`() {
        val max = 15
        assertEquals(0, percentToStep(-10, max))
        assertEquals(15, percentToStep(200, max))
    }

    @Test
    fun `percent to step is degenerate-safe when max is zero`() {
        assertEquals(0, percentToStep(50, 0))
        assertEquals(0, percentToStep(100, 0))
    }

    // ---- index -> percent (indexToPercent), mirrors get_volume scale ----

    @Test
    fun `index to percent matches get_volume rounding`() {
        val max = 15
        assertEquals(0, indexToPercent(0, max))
        assertEquals(100, indexToPercent(15, max))
        assertEquals(53, indexToPercent(8, max)) // (800 + 7) / 15 — same formula get_volume uses
    }

    @Test
    fun `index to percent is zero when max is zero`() {
        assertEquals(0, indexToPercent(7, 0))
    }

    // ---- write verification classification (checkVolumeWrite) ----

    @Test
    fun `exact write is verified applied`() {
        val check = checkVolumeWrite(requestedPercent = 50, targetStep = 8, preIndex = 0, postIndex = 8)
        assertEquals(VolumeWriteStatus.APPLIED, check.status)
        assertTrue(check.verified)
    }

    @Test
    fun `write already at target is trivially verified`() {
        // Stream was already where we want it; desired state holds even though nothing moved.
        val check = checkVolumeWrite(requestedPercent = 50, targetStep = 8, preIndex = 8, postIndex = 8)
        assertEquals(VolumeWriteStatus.APPLIED, check.status)
        assertTrue(check.verified)
    }

    @Test
    fun `silent no-op is not verified`() {
        // The false-success bug: API returned normally but nothing changed.
        val check = checkVolumeWrite(requestedPercent = 20, targetStep = 3, preIndex = 0, postIndex = 0)
        assertEquals(VolumeWriteStatus.UNCHANGED, check.status)
        assertFalse(check.verified)
    }

    @Test
    fun `partial move is not verified`() {
        val check = checkVolumeWrite(requestedPercent = 20, targetStep = 3, preIndex = 0, postIndex = 2)
        assertEquals(VolumeWriteStatus.PARTIAL, check.status)
        assertFalse(check.verified)
    }

    @Test
    fun `requested percent is stored coerced into range`() {
        val check = checkVolumeWrite(requestedPercent = 500, targetStep = 15, preIndex = 0, postIndex = 15)
        assertEquals(100, check.requestedPercent)
        assertTrue(check.verified)
    }

    @Test
    fun `downward exact write is verified`() {
        val check = checkVolumeWrite(requestedPercent = 20, targetStep = 3, preIndex = 12, postIndex = 3)
        assertEquals(VolumeWriteStatus.APPLIED, check.status)
        assertTrue(check.verified)
    }
}
