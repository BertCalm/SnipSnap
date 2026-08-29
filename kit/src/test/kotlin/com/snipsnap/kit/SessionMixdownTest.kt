package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionMixdownTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mixdown").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun buildKit(dir: File, name: String, hz: Double, bars: Int): File {
        dir.mkdirs()
        val tone = Snip(
            FloatArray(4_000) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() },
            1, 44_100,
        )
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone)
        val kit = Kit(
            name,
            listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)),
            tempoBpm = 120f,
        )
        KitStore.save(kit, dir)
        GrooveStore.save(
            dir,
            listOf(
                Mpc3Clip(
                    "$name Groove", bars,
                    (0 until bars * 4).map { Mpc3Note(36, it * 4L * Mpc3Clip.PULSES_PER_16TH, 0.9f) },
                ),
            ),
        )
        return dir
    }

    @Test
    fun `two kits mix as long as the longer, deterministic, under the ceiling`() {
        val a = buildKit(File(temp, "a"), "Kit A", 90.0, bars = 1)
        val b = buildKit(File(temp, "b"), "Kit B", 400.0, bars = 2)

        val mix = SessionMixdown.render(listOf(a, b), tempoBpm = 120f)
        val longer = KitPreview.render(KitStore.load(b), b, tempoBpm = 120f)
        assertEquals(longer.frameCount, mix.frameCount, "the mix runs as long as the longest kit")
        assertEquals(2, mix.channels)
        assertTrue(mix.peak() > 0.05f && mix.peak() <= SessionMixdown.PEAK + 1e-4f)
        assertTrue(
            mix.samples.contentEquals(SessionMixdown.render(listOf(a, b), tempoBpm = 120f).samples),
            "same session, same bytes",
        )
    }

    @Test
    fun `a solo session is exactly that kit's own preview`() {
        val a = buildKit(File(temp, "solo"), "Solo Kit", 200.0, bars = 1)
        val mix = SessionMixdown.render(listOf(a), tempoBpm = 120f)
        val preview = KitPreview.render(KitStore.load(a), a, tempoBpm = 120f)
        assertTrue(mix.samples.contentEquals(preview.samples))
    }
}
