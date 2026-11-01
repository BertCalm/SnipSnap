package com.snipsnap.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MyVoiceImportTest {
    @Test
    fun a_high_rate_take_over_the_limit_is_refused_instead_of_shortened() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "high-rate-voice-import-${System.nanoTime()}.wav")
        try {
            val rate = 192_000
            WavWriter.write(file, Snip(FloatArray(rate * 31), 1, rate),
                WavWriter.BitDepth.PCM_16, allowNonMpcRate = true)
            assertTrue(file.length() < 32L * 1024 * 1024)
            val refused = runCatching {
                MediaDecode.decode(context, Uri.fromFile(file), maxDurationSeconds = 30)
            }.exceptionOrNull()
            assertTrue(refused is IllegalArgumentException)
            assertEquals(rate * 31, MediaDecode.decode(context, Uri.fromFile(file)).frameCount)
        } finally { file.delete() }
    }

    @Test
    fun the_short_import_budget_refuses_long_audio_without_changing_default_decode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "short-voice-import-${System.nanoTime()}.wav")
        try {
            val rate = WavWriter.MPC_SAMPLE_RATE
            WavWriter.write(file, Snip(FloatArray(rate * 2) { if (it == 0) 1f else .001f }, 1, rate))
            val uri = Uri.fromFile(file)
            val normal = MediaDecode.decode(context, uri)
            val bounded = MediaDecode.decode(context, uri, maxDurationSeconds = 2)
            assertEquals(rate * 2, normal.frameCount)
            assertArrayEquals(normal.samples, bounded.samples, 0f)
            val refused = runCatching { MediaDecode.decode(context, uri, maxDurationSeconds = 1) }.exceptionOrNull()
            assertTrue(refused?.message.orEmpty().contains("1 seconds or less"))
        } finally { file.delete() }
    }
}
