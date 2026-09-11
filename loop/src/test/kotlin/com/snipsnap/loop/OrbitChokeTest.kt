package com.snipsnap.loop

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Choke in ORBIT — the kit's own mute groups, honoured by the live engine.
 *
 * `KitPreview.render` has always ended a ringing voice when another in its
 * mute group starts, and the hardware does the same from the program's
 * `whichMuteGroup`. `OrbitEngine` had no reference to `muteGroup` anywhere,
 * and `OrbitBank` handed back a bare `Snip` with no pad metadata, so an
 * open hat rang straight through a closed hat in ORBIT while choking
 * correctly in the preview, in the mixdown and on the MPC — one pattern,
 * three different sounds.
 */
class OrbitChokeTest {

    private val rate = 48_000
    private val bpm = 120f
    private val step = 6_000 // frames per 16th at 120 BPM, 48 kHz

    /**
     * Pads that hold a constant value for a full second, so "is it still
     * ringing?" is a question the samples answer directly: the left channel
     * reads slot/10 for as long as that pad sounds, and two pads sounding
     * together read as their sum.
     */
    private class SustainSource(
        private val groups: Map<Int, Int> = emptyMap(),
        private val frames: Int = 48_000,
    ) : SampleSource {
        override fun loop(sampleFile: String): Snip? = null
        override fun pad(kit: String, slot: Int): Snip? =
            if (kit == "kit" && slot in 1..9) Snip(FloatArray(frames) { slot / 10f }, 1, rateOf) else null
        override fun muteGroup(kit: String, slot: Int): Int = groups[slot] ?: 0
        private companion object { const val rateOf = 48_000 }
    }

    private fun ring(name: String, steps: Int, vararg hits: Pair<Int, Int>) =
        Orbit(name, steps, PatternOrbit("kit", hits.map { (s, slot) -> OrbitHit(s, slot) }))

    private fun set(vararg orbits: Orbit) = OrbitSet(orbits.toList(), bpm, rate)

    private fun render(s: OrbitSet, frames: Int, source: SampleSource, blockFrames: Int = OrbitEngine.DEFAULT_BLOCK_FRAMES) =
        OrbitEngine.render(s, OrbitBank.prepare(s, source), frames, blockFrames).samples

    /** The left channel at [frame] — the sum of everything sounding there. */
    private fun at(samples: FloatArray, frame: Int) = samples[frame * 2]

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) < 1e-4f, "$what: expected $expected, got $actual")
    }

    /** Slot 1 on the downbeat, slot 2 four steps later — the hat pair. */
    private fun hatPair() = set(ring("hats", 16, 0 to 1, 4 to 2))

    @Test
    fun `a hit ends the voice it shares a mute group with`() {
        val out = render(hatPair(), 8 * step, SustainSource(groups = mapOf(1 to 1, 2 to 1)))

        assertClose(0.1f, at(out, 4 * step - 1), "before the second hit, only the first is ringing")
        // CHOKE_FADE frames after the second hit the first is gone, and
        // what is left is the second pad alone rather than the two summed.
        assertClose(0.2f, at(out, 4 * step + AutoPlace.CHOKE_FADE), "the first voice should be choked")
        assertClose(0.2f, at(out, 6 * step), "and stay gone")
    }

    @Test
    fun `without a mute group both pads ring on, exactly as before`() {
        val out = render(hatPair(), 8 * step, SustainSource())

        assertClose(0.1f, at(out, 4 * step - 1), "only the first, before the second lands")
        assertClose(0.3f, at(out, 4 * step + AutoPlace.CHOKE_FADE), "both, summed - nothing chokes anything")
        assertClose(0.3f, at(out, 6 * step), "and they keep ringing together")
    }

    @Test
    fun `pads in different groups leave each other alone`() {
        val out = render(hatPair(), 8 * step, SustainSource(groups = mapOf(1 to 1, 2 to 2)))
        assertClose(0.3f, at(out, 4 * step + AutoPlace.CHOKE_FADE), "different groups do not choke")
    }

    @Test
    fun `the choked voice ramps out rather than cutting`() {
        val out = render(hatPair(), 8 * step, SustainSource(groups = mapOf(1 to 1, 2 to 1)))
        val start = 4 * step

        // Through the fade the first pad is still audible and falling: the
        // sum sits strictly between "both at full" and "the second alone".
        val through = (1 until AutoPlace.CHOKE_FADE).map { at(out, start + it) }
        assertTrue(through.all { it > 0.2f && it < 0.3f }, "the fade should be strictly between: ${through.take(4)}")
        assertTrue(
            through.zipWithNext().all { (a, b) -> b <= a },
            "the ramp should never rise",
        )
        // A cut would step straight from 0.3 to 0.2 in one frame.
        assertTrue(through.first() > 0.29f, "the ramp starts at full, not part-way: ${through.first()}")
    }

    @Test
    fun `a voice that simply ends keeps the tail it was recorded with`() {
        // The ramp belongs to the choke, not to every voice. Fading on the
        // way out of the sample itself would put a 128-frame taper on the
        // end of every hit ORBIT has ever played - inaudible one at a time,
        // and wrong in every render.
        val pad = 48_000
        val s = set(ring("one", 16, 0 to 1))
        val out = render(s, pad + 2 * step, SustainSource(groups = mapOf(1 to 1), frames = pad))

        assertClose(0.1f, at(out, pad - 1), "the last frame of the sample is at full level")
        assertClose(0.1f, at(out, pad - AutoPlace.CHOKE_FADE), "and so is everything before it")
        assertClose(0f, at(out, pad), "the sample is over, so silence - not a tail it never had")
    }

    @Test
    fun `a choke landing near the end of a pad still ramps it out`() {
        // The first voice has only 64 frames left when the second hit
        // lands - fewer than a full fade. It must still ramp to silence
        // across what remains, rather than running out at full level
        // because "was it choked?" was inferred from the stop point.
        val padFrames = step + 64
        val s = set(ring("hats", 16, 0 to 1, 1 to 2))
        val out = render(s, 3 * step, SustainSource(groups = mapOf(1 to 1, 2 to 1), frames = padFrames), blockFrames = 3 * step)

        assertClose(0.3f, at(out, step), "both sounding at the moment of the choke")
        // Across the 64 frames it has left, pad 1 must fall away.
        val tail = (1 until 64).map { at(out, step + it) - 0.2f }
        assertTrue(tail.first() > 0.09f, "the ramp starts at full: ${tail.first()}")
        assertTrue(tail.last() < 0.01f, "and reaches silence by the sample's end: ${tail.last()}")
        assertTrue(tail.zipWithNext().all { (a, b) -> b <= a }, "monotonically down")
    }

    @Test
    fun `a pad in a mute group chokes its own previous hit`() {
        // The same pad struck twice: the hardware's rule chokes the earlier
        // voice, so the two strikes do not sum into a pad playing twice as
        // loud as it can.
        val s = set(ring("one pad", 16, 0 to 1, 4 to 1))
        val out = render(s, 8 * step, SustainSource(groups = mapOf(1 to 1)))
        assertClose(0.1f, at(out, 4 * step + AutoPlace.CHOKE_FADE), "one voice, not two summed")
    }

    @Test
    fun `the newest hit wins even when an earlier one belongs to a later ring`() {
        // Both hits inside ONE block, and the ring that fires LATER is
        // listed FIRST. Rings are sorted within themselves but not against
        // each other, so without a merged time order the engine would start
        // the later hit first and then let the earlier one choke it - which
        // reads as no choke at all, and both pads ringing.
        val s = set(
            ring("late", 16, 4 to 2),
            ring("early", 16, 0 to 1),
        )
        val out = render(s, 8 * step, SustainSource(groups = mapOf(1 to 1, 2 to 1)), blockFrames = 8 * step)

        assertClose(0.1f, at(out, 4 * step - 1), "the early ring is sounding on its own first")
        assertClose(
            0.2f,
            at(out, 4 * step + AutoPlace.CHOKE_FADE),
            "the hit at step 4 is the newest and should win, whatever order its ring sits in",
        )
    }

    @Test
    fun `a muted ring's hits choke nothing, because they never sound`() {
        val s = set(
            ring("hats", 16, 0 to 1),
            Orbit("silent", 16, PatternOrbit("kit", listOf(OrbitHit(4, 2))), engaged = false),
        )
        val out = render(s, 8 * step, SustainSource(groups = mapOf(1 to 1, 2 to 1)))
        assertClose(0.1f, at(out, 6 * step), "a ring that is not playing cannot choke one that is")
    }

    @Test
    fun `the mute group is read off the kit on disk, not invented`() {
        // The whole path: kit.json -> KitSampleSource's index -> OrbitBank.
        // KitSampleSource already loaded the Kit to find the filenames and
        // dropped everything else on the floor, which is why the engine had
        // no way to know about choke.
        val dir = File.createTempFile("snipsnap-choke", "").let { it.delete(); it.mkdirs(); it.deleteOnExit(); it }
        val kitDir = File(dir, "hats").also { it.mkdirs() }
        for ((name, value) in listOf("open.wav" to 0.4f, "closed.wav" to -0.4f)) {
            val samples = FloatArray(200 * 2) { if (it % 2 == 0) value else value }
            WavWriter.write(File(kitDir, name), Snip(samples, 2, 44_100), WavWriter.BitDepth.PCM_24)
        }
        KitStore.save(
            Kit(
                "hats",
                listOf(
                    KitPad(slot = 1, sampleFile = "open.wav", muteGroup = 3),
                    KitPad(slot = 2, sampleFile = "closed.wav", muteGroup = 3),
                    KitPad(slot = 3, sampleFile = "open.wav"),
                ),
            ),
            kitDir,
        )

        val source = KitSampleSource(dir)
        assertEquals(3, source.muteGroup("hats", 1))
        assertEquals(3, source.muteGroup("hats", 2))
        assertEquals(0, source.muteGroup("hats", 3), "a pad with no group is 0, not a group of its own")
        assertEquals(0, source.muteGroup("hats", 9), "a slot the kit lacks")
        assertEquals(0, source.muteGroup("no such kit", 1), "a kit that isn't there")

        val s = OrbitSet(listOf(Orbit("r", 16, PatternOrbit("hats", listOf(OrbitHit(0, 1), OrbitHit(4, 2))))), bpm, rate)
        val bank = OrbitBank.prepare(s, source)
        assertEquals(3, bank.muteGroup("hats", 1), "the bank carries what the kit said")
        assertEquals(3, bank.muteGroup("hats", 2))
        assertEquals(0, bank.muteGroup("hats", 3), "a pad no ring plays has no entry, and answers 0")
    }

    @Test
    fun `retuning a kit's choke is heard without restarting the source`() {
        // The same live KitSampleSource, across an edit. Its filename index
        // and decoded audio are cached for the life of the source - so a
        // choke group cached beside them would have meant toggling choke in
        // the pad sheet did nothing in ORBIT until the app was restarted.
        val dir = File.createTempFile("snipsnap-retune", "").let { it.delete(); it.mkdirs(); it.deleteOnExit(); it }
        val kitDir = File(dir, "hats").also { it.mkdirs() }
        WavWriter.write(File(kitDir, "a.wav"), Snip(FloatArray(400) { 0.3f }, 2, 44_100), WavWriter.BitDepth.PCM_24)
        fun save(group: Int) = KitStore.save(
            Kit("hats", listOf(KitPad(slot = 1, sampleFile = "a.wav", muteGroup = group))),
            kitDir,
        )

        save(0)
        val source = KitSampleSource(dir)
        assertEquals(0, source.muteGroup("hats", 1), "no choke to begin with")
        source.pad("hats", 1) // warm every cache the source keeps

        // The stamp has one-second resolution on some filesystems, so make
        // the edit unambiguously later rather than racing it.
        val json = File(kitDir, KitStore.FILE_NAME)
        save(2)
        json.setLastModified(json.lastModified() + 2_000)

        assertEquals(2, source.muteGroup("hats", 1), "the edit should be heard by the same source")
    }

    @Test
    fun `a source that says nothing about choke plays exactly as it always did`() {
        // Every faked SampleSource in this repo - and both bakers - predate
        // muteGroup. The default has to mean "as before", or adding the
        // method would have changed behaviour everywhere it wasn't wanted.
        val bare = object : SampleSource {
            override fun loop(sampleFile: String): Snip? = null
            override fun pad(kit: String, slot: Int): Snip? = Snip(FloatArray(48_000) { slot / 10f }, 1, rate)
        }
        val out = render(hatPair(), 8 * step, bare)
        assertClose(0.3f, at(out, 6 * step), "no choke, both ringing")
    }
}
