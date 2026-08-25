package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KitPreviewTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("preview").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun tone(seconds: Float, hz: Double = 220.0, amp: Float = 0.5f): Snip {
        val n = (seconds * KitPreview.RATE).toInt()
        return Snip(
            FloatArray(n) { i -> (amp * Math.sin(2.0 * Math.PI * hz * i / KitPreview.RATE)).toFloat() },
            1, KitPreview.RATE,
        )
    }

    /** Pad 1 rings long, pad 2 is a blip; classes make the kit "known". */
    private fun buildKit(dir: File, muteGroup: Int = 0): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(1.5f, 110.0))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(0.05f, 900.0))
        WavWriter.write(File(dir, "A03_HatClosed_01.wav"), tone(0.05f, 4000.0))
        val kit = Kit(
            "Preview Kit",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK, muteGroup = muteGroup),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE, muteGroup = muteGroup),
                KitPad(slot = 3, sampleFile = "A03_HatClosed_01.wav", drumClass = DrumClass.HAT_CLOSED),
            ),
            tempoBpm = 120f,
        )
        KitStore.save(kit, dir)
        return kit
    }

    /** RMS over a frame window of an interleaved stereo render. */
    private fun rms(snip: Snip, fromFrame: Int, toFrame: Int): Float {
        var sum = 0.0
        var n = 0
        for (f in fromFrame until minOf(toFrame, snip.frameCount)) {
            for (ch in 0 until snip.channels) {
                val s = snip.samples[f * snip.channels + ch]
                sum += s.toDouble() * s
                n++
            }
        }
        return if (n == 0) 0f else sqrt(sum / n).toFloat()
    }

    private val oneBar = Mpc3Clip(
        "Test Groove", 1,
        listOf(
            Mpc3Note(36, 0, 0.9f),                       // pad 1, downbeat
            Mpc3Note(37, 4 * Mpc3Clip.PULSES_PER_16TH, 0.8f),  // pad 2, beat two
            Mpc3Note(38, 8 * Mpc3Clip.PULSES_PER_16TH, 0.6f),  // pad 3, beat three
        ),
    )

    @Test
    fun `renders the groove non-silent, stereo, deterministic, and the right length`() {
        val dir = File(temp, "kit")
        val kit = buildKit(dir)

        val a = KitPreview.render(kit, dir, clip = oneBar, tempoBpm = 120f)
        val b = KitPreview.render(kit, dir, clip = oneBar, tempoBpm = 120f)

        assertEquals(2, a.channels, "previews are stereo")
        assertEquals(KitPreview.RATE, a.sampleRate)
        assertTrue(a.peak() > 0.05f, "a kit playing its own beat is audible")
        assertTrue(a.peak() <= 0.95f + 1e-4f, "hard ceiling holds")
        assertTrue(a.samples.contentEquals(b.samples), "same kit, same groove, same bytes")

        // One 4/4 bar at 120 BPM is exactly two seconds, plus the ring-out.
        val expected = 2 * KitPreview.RATE + (0.6f * KitPreview.RATE).toInt()
        assertEquals(expected, a.frameCount)

        // The downbeat lands at frame zero, not somewhere vague.
        assertTrue(rms(a, 0, 4410) > 0.05f, "first 100ms carries the downbeat")
    }

    @Test
    fun `a mute group chokes the long pad when its sibling fires`() {
        val loose = buildKit(File(temp, "loose"), muteGroup = 0)
        val tight = buildKit(File(temp, "tight"), muteGroup = 1)

        // Pad 1's 1.5s tone starts the bar; pad 2 fires at beat two (0.5s
        // into the bar at 120 BPM). Ungrouped, the tone is still singing at
        // 0.8s; grouped, pad 2 cut it off at half a second.
        val clip = Mpc3Clip(
            "Choke", 1,
            listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(37, 4 * Mpc3Clip.PULSES_PER_16TH, 0.9f)),
        )
        val open = KitPreview.render(loose, File(temp, "loose"), clip = clip, tempoBpm = 120f)
        val choked = KitPreview.render(tight, File(temp, "tight"), clip = clip, tempoBpm = 120f)

        val late = { s: Snip -> rms(s, (0.8f * KitPreview.RATE).toInt(), (1.2f * KitPreview.RATE).toInt()) }
        assertTrue(late(open) > 0.05f, "unchoked long pad still rings late in the bar")
        assertTrue(late(choked) < late(open) * 0.1f, "choked render is near-silent there")
    }

    @Test
    fun `no clip anywhere falls back to the class backbone pattern`() {
        val dir = File(temp, "fallback")
        val kit = buildKit(dir)
        // No groove.json, no explicit clip: defaultPattern carries it.
        val snip = KitPreview.render(kit, dir)
        assertTrue(snip.peak() > 0.05f)

        val pattern = KitPreview.defaultPattern(kit)
        assertEquals(2, pattern.bars, "the backbone is two bars")
        assertTrue(pattern.notes.any { it.note == 36 && it.timePulses == 0L }, "kick on the one")
        assertTrue(
            pattern.notes.any { it.note == 37 && it.timePulses == 4 * Mpc3Clip.PULSES_PER_16TH },
            "snare on the two",
        )
        assertEquals(16, pattern.notes.count { it.note == 38 }, "hats on the eighths, both bars")
    }

    @Test
    fun `a class-less kit gets the pad walk`() {
        val dir = File(temp, "unknown").apply { mkdirs() }
        WavWriter.write(File(dir, "A01_Snip_01.wav"), tone(0.1f, 300.0))
        WavWriter.write(File(dir, "A02_Snip_01.wav"), tone(0.1f, 500.0))
        val kit = Kit(
            "Imported",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Snip_01.wav"),
                KitPad(slot = 2, sampleFile = "A02_Snip_01.wav"),
            ),
        )
        KitStore.save(kit, dir)

        val pattern = KitPreview.defaultPattern(kit)
        assertEquals(listOf(36, 37), pattern.notes.map { it.note }, "every pad, slot order")
        assertEquals(
            listOf(0L, Mpc3Clip.PULSES_PER_16TH),
            pattern.notes.map { it.timePulses },
            "a sixteenth each",
        )
        assertTrue(KitPreview.render(kit, dir).peak() > 0.05f)
    }

    @Test
    fun `the saved groove drives the render when no clip is passed`() {
        val dir = File(temp, "stored")
        val kit = buildKit(dir)
        GrooveStore.save(dir, listOf(oneBar))

        val snip = KitPreview.render(kit, dir)
        // kit.tempoBpm (120) times one stored bar: the same two seconds
        // plus tail as the explicit-clip render.
        assertEquals(2 * KitPreview.RATE + (0.6f * KitPreview.RATE).toInt(), snip.frameCount)
        assertTrue(snip.peak() > 0.05f)
    }
}
