package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoPatch
import com.snipsnap.synth.ArcoPresets
import com.snipsnap.synth.ArcoVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ArcoPadMaker]: ARCO's held keygroup as the shop exports it. The zones are absolute (TUNE does not move them), every zone has two
 * layers soft first in their velocity windows, each with a stroke and a loop start past it; the package survives the export and the
 * phone's sidecar; and the preview is a held key through the instrument player that crosses the loop's wrap and then releases.
 * ArcoKeysTest (`:synth`) holds every zone of both voices to the seam bar; this renders ERHU's eight zones once, both layers.
 */
class ArcoPadMakerTest {

    private val dest: File = java.nio.file.Files.createTempDirectory("arcopad").toFile()

    @AfterTest
    fun cleanUp() {
        dest.deleteRecursively()
    }

    private companion object {
        val erhu = ArcoPatch("Erhu Held", ArcoVoice.ERHU, Arco.defaults(ArcoVoice.ERHU))
        val spec = ArcoPadMaker.spec(erhu, ArcoPadMaker.RELEASE.fraction(.6f))
        val notes: List<List<KeyNote>> by lazy {
            ArcoPadMaker.zoneMidis(spec).parallelStream().map { ArcoPadMaker.renderZone(spec, it) }.toList()
        }
    }

    @Test
    fun `the zones are absolute, two voices' plans, and TUNE does not move them`() {
        for (voice in ArcoVoice.entries) {
            val at = listOf(0f, 0.5f, 1f).map { tune -> ArcoPadMaker.zoneMidis(ArcoPadMaker.Spec(ArcoPatch("T", voice, Arco.defaults(voice) + ("TUNE" to tune)))) }
            assertTrue(at.all { it == at.first() }, "$voice: TUNE moved the zones: $at")
        }
        assertEquals(listOf(36, 39, 42, 45, 48, 51, 54, 57, 60), ArcoPadMaker.zoneMidis(ArcoPadMaker.Spec(ArcoPatch("C", ArcoVoice.CELLO, Arco.defaults(ArcoVoice.CELLO)))))
        assertEquals(listOf(62, 65, 68, 71, 74, 77, 80, 81), ArcoPadMaker.zoneMidis(spec))
        for (preset in ArcoPresets.all()) assertTrue(ArcoPadMaker.supports(preset), preset.name)
    }

    @Test
    fun `RELEASE is the held makers' knob and the spec refuses a release outside it`() {
        assertEquals(.6f, ArcoPadMaker.RELEASE.default)
        assertEquals(.6f, spec.releaseSeconds, 1e-4f)
        assertEquals(ArcoPadMaker.RELEASE_MIN_SECONDS, ArcoPadMaker.spec(erhu, 0f).releaseSeconds)
        assertEquals(ArcoPadMaker.RELEASE_MAX_SECONDS, ArcoPadMaker.spec(erhu, 1f).releaseSeconds)
        assertFailsWith<IllegalArgumentException> { ArcoPadMaker.Spec(erhu, 2f) }
        assertFailsWith<IllegalArgumentException> { ArcoPadMaker.Spec(erhu, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ArcoPadMaker.renderZone(spec, 63) }
    }

    @Test
    fun `every zone is two layers soft first, each a stroke then a loop that closes`() {
        assertEquals(ArcoPadMaker.zoneMidis(spec).size, notes.size)
        for ((i, zone) in notes.withIndex()) {
            assertEquals(2, zone.size)
            for (note in zone) {
                val s = note.snip.samples
                assertEquals(1, note.snip.channels)
                // The owner's pick (HeldStart.SHORT): the loop starts once the stroke's 80 ms have handed over by 300 ms, never inside it.
                val handedOver = (com.snipsnap.synth.HeldStart.SHORT.handover * 44_100).toLong() + com.snipsnap.synth.HeldStart.SETTLED_FRAMES
                assertTrue(note.loopStartFrame >= handedOver, "zone $i: the loop starts at ${note.loopStartFrame}, inside the stroke's handover")
                assertTrue(note.loopStartFrame < 44_100, "zone $i: the loop starts at ${note.loopStartFrame}, a second or more into the key")
                assertTrue(Keys.seamError(s, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                assertTrue(s.all { it.isFinite() && it in -0.99f..0.99f })
            }
            assertTrue(!zone[0].snip.samples.contentEquals(zone[1].snip.samples), "zone $i: the layers are the same samples")
        }
    }

    @Test
    fun `the program has the zones, the two velocity layers and the loop starts, and survives the export and the sidecar`() {
        val name = "ERHU HELD"
        val (program, samples) = ArcoPadMaker.assemble(name, spec, notes)
        val midis = ArcoPadMaker.zoneMidis(spec)
        assertEquals(midis, program.keygroups.map { it.rootNote })
        assertEquals(midis.first() - ArcoPadMaker.EDGE_REACH, program.keygroups.first().lowNote)
        assertEquals(midis.last() + ArcoPadMaker.EDGE_REACH, program.keygroups.last().highNote)
        for (k in 1 until program.keygroups.size) assertEquals(program.keygroups[k - 1].highNote + 1, program.keygroups[k].lowNote, "zones tile without gaps")
        assertEquals(80, program.keygroups[6].highNote, "G#5 stops below A5")
        assertEquals(81, program.keygroups[7].lowNote, "A5 starts on itself")
        for ((i, kg) in program.keygroups.withIndex()) {
            assertEquals(ArcoPadMaker.VELOCITY_WINDOWS.map { it.first to it.last }, kg.layers.map { it.velStart to it.velEnd }, "zone $i windows")
            assertEquals(notes[i].map { it.loopStartFrame }, kg.layers.map { it.loopStartFrame }, "zone $i loop starts")
        }
        assertEquals(2 * midis.size, samples.size)

        ArcoPadMaker.export(name, spec, notes, dest)
        val instrument = InstrumentStore.read(InstrumentStore.sidecar(dest, name))
        assertEquals(midis, instrument.zones.map { it.rootNote })
        assertEquals(spec.releaseSeconds, instrument.release, 1e-6f)
        for ((i, z) in instrument.zones.withIndex()) {
            assertEquals(2, z.layers.size)
            for ((l, layer) in z.layers.withIndex()) {
                assertEquals(ArcoPadMaker.VELOCITY_WINDOWS[l], layer.velStart..layer.velEnd)
                assertEquals(notes[i][l].loopStartFrame, layer.loopStartFrame)
                assertEquals(notes[i][l].snip.frameCount, WavReader.read(File(dest, layer.sample)).frameCount)
            }
        }
        val bytes = notes.flatten().sumOf { it.snip.frameCount.toLong() } * 3
        println("ARCO held ERHU package: ${instrument.zones.size} zones, ${samples.size} samples, %.1f MB of 24-bit audio".format(java.util.Locale.ROOT, bytes / 1e6))
    }

    @Test
    fun `assemble refuses a zone with the wrong layers or no loop`() {
        val flat = KeyNote(Snip(FloatArray(1000), 1, 44_100))
        val looped = KeyNote(Snip(FloatArray(1000), 1, 44_100), loopStartFrame = 500)
        val zones = ArcoPadMaker.zoneMidis(spec).size
        assertFailsWith<IllegalArgumentException> { ArcoPadMaker.assemble("X", spec, List(zones - 1) { listOf(looped, looped) }) }
        assertFailsWith<IllegalArgumentException> { ArcoPadMaker.assemble("X", spec, List(zones) { listOf(looped) }) }
        assertFailsWith<IllegalArgumentException> { ArcoPadMaker.assemble("X", spec, List(zones) { listOf(looped, flat) }) }
    }

    @Test
    fun `the preview is a held key through the player that crosses the wrap, then releases`() {
        val midis = ArcoPadMaker.zoneMidis(spec)
        val middle = midis.size / 2
        for (layer in listOf(ArcoPadMaker.HARD, 0)) {
            val preview = ArcoPadMaker.preview(spec, layer = layer).samples
            val note = notes[middle][layer]
            val s = note.snip.samples
            val start = note.loopStartFrame.toInt()
            val loop = s.size - start
            val hold = s.size + loop
            val release = Math.round(spec.releaseSeconds * 44_100)
            assertEquals(hold + release + Math.round(44_100 * .01f), preview.size)
            val gain = ArcoPadMaker.VELOCITY_WINDOWS[layer].last / 127f * InstrumentEngine.VOICE_LEVEL
            // At the zone's own root the player reads each frame exactly, so the held key is the file, then its loop again.
            for (i in 0 until hold) {
                val want = s[if (i < s.size) i else start + (i - s.size) % loop] * gain
                if (preview[i] != want) error("layer $layer frame $i: the preview reads ${preview[i]}, the file times the touch ${want}")
            }
            // The player's per-frame fade is a float sum and ends a few frames past the release (3 at 0.6 s); the 10 ms guard holds them,
            // so the last 5 ms are silent, BellowsPreviewTest's own reading.
            assertTrue(preview.takeLast(220).all { it == 0f }, "layer $layer: the release does not reach silence")
            assertTrue(preview.copyOfRange(hold, hold + release / 4).any { it != 0f }, "layer $layer: nothing sounds after the key lifts")
        }
    }
}
