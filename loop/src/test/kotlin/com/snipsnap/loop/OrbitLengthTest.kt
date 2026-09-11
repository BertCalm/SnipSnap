package com.snipsnap.loop

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How long a hit sounds.
 *
 * `OrbitHit` carried `(step, slot, velocity, offset)` and no duration, so
 * `OrbitClip.clip` took `Mpc3Note`'s 240-pulse default and **every**
 * exported note was exactly a 16th — verified before any of this was
 * written, the bass ring's twenty-eight notes included. Meanwhile the
 * engine started a voice and played the sample out, with nothing able to
 * stop it early but a choke. One pattern, two different ideas of when a
 * note ends.
 */
class OrbitLengthTest {

    private val rate = 48_000
    private val bpm = 120f
    private val step = 6_000 // frames per 16th at 120 BPM, 48 kHz
    private val s16 = Mpc3Clip.PULSES_PER_16TH

    /** A pad that holds a constant 0.5 for a full second, so "is it still sounding?" reads straight off the buffer. */
    private class SustainSource(private val frames: Int = 48_000) : SampleSource {
        override fun loop(sampleFile: String): Snip? = null
        override fun pad(kit: String, slot: Int): Snip? =
            if (slot in 1..9) Snip(FloatArray(frames) { 0.5f }, 1, 48_000) else null
    }

    private fun ring(vararg hits: OrbitHit) = Orbit("R", 16, PatternOrbit("kit", hits.toList()))
    private fun set(vararg orbits: Orbit) = OrbitSet(orbits.toList(), bpm, rate)
    private fun render(s: OrbitSet, frames: Int) =
        OrbitEngine.render(s, OrbitBank.prepare(s, SustainSource()), frames).samples

    private fun at(samples: FloatArray, frame: Int) = samples[frame * 2]

    // ---- the field ----

    @Test
    fun `a hit with no length plays its sample out, as every hit always did`() {
        val hit = OrbitHit(0, 1)
        assertEquals(OrbitHit.WHOLE_SAMPLE, hit.length)
        assertTrue(!hit.gated, "nothing stops it but the sample running out")

        // Half a second in, the pad is still sounding.
        val out = render(set(ring(hit)), 24_000)
        assertTrue(at(out, 20_000) > 0.4f, "a one-shot is still ringing at 20k: ${at(out, 20_000)}")
    }

    @Test
    fun `a negative length is refused rather than read as a direction`() {
        val e = assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, length = -1L) }
        assertTrue("length" in (e.message ?: ""), e.message ?: "")
    }

    // ---- the engine stops where it is told ----

    @Test
    fun `a gated hit ends at its own length, and ramps rather than cutting`() {
        // Two 16ths: 480 pulses, 12000 frames at this tempo and rate.
        val out = render(set(ring(OrbitHit(0, 1, length = 2 * s16))), 24_000)

        assertTrue(at(out, 6_000) > 0.4f, "still sounding a 16th in: ${at(out, 6_000)}")
        // Past the gate, silent — the pad itself runs a full second, so
        // anything here is the gate working rather than the sample ending.
        assertTrue(abs(at(out, 14_000)) < 1e-4f, "gated off by 14k: ${at(out, 14_000)}")

        // And it got there on a ramp. A sample cut mid-cycle is a click, so
        // the fade's worth AFTER the gate point must be strictly falling —
        // the stop moves to `end + fade` and the slope runs across it, so
        // sampling before `end` only reads full gain and proves nothing.
        val end = 12_000
        val early = at(out, end + 16)
        val late = at(out, end + AutoPlace.CHOKE_FADE - 16)
        assertTrue(early > late && late > 0f, "the tail falls across the ramp: $early then $late")
        assertTrue(early < 0.5f, "and it is already below full gain at $early")
    }

    @Test
    fun `a length longer than the sample leaves the voice alone`() {
        // The pad is one second; the length is four bars. There is nothing
        // to cut, and the gate must not invent an early stop.
        val out = render(set(ring(OrbitHit(0, 1, length = 4 * Mpc3Clip.PULSES_PER_BAR))), 24_000)
        assertTrue(at(out, 20_000) > 0.4f, "still ringing at 20k: ${at(out, 20_000)}")
    }

    @Test
    fun `a gate measures sample played, not where in the block the hit landed`() {
        // Step 1 starts at frame 6000, so a one-16th gate must end it at
        // 12000 — a full 16th of SAMPLE, not a 16th measured from wherever
        // in the 2048-frame block the hit happened to land.
        //
        // The assertion has to sit hard against 12000 to say that. An
        // earlier draft checked 8000 and 14000, which a gate that stopped
        // at 10096 also passes — and 10096 is exactly what the first cut
        // of this did, by adding the voice's negative block position to a
        // sample index. The revert that should have failed did not, which
        // is the only reason the bug was found.
        val out = render(set(ring(OrbitHit(1, 1, length = s16))), 24_000)
        assertTrue(at(out, 11_900) > 0.4f, "still at full gain just before its gate: ${at(out, 11_900)}")
        assertTrue(abs(at(out, 12_400)) < 1e-4f, "and silent just after it: ${at(out, 12_400)}")
    }

    // ---- the export says the same number ----

    @Test
    fun `a gated hit exports its own length and an ungated one exports a 16th`() {
        val s = set(ring(OrbitHit(0, 1, length = 3 * s16), OrbitHit(4, 2)))
        val notes = OrbitClip.clip(s).notes.sortedBy { it.timePulses }
        assertEquals(listOf(3 * s16, s16), notes.map { it.lengthPulses })
    }

    @Test
    fun `a set with no lengths exports exactly the bytes it did before`() {
        // The whole point of WHOLE_SAMPLE being the default: every set made
        // before this field existed must export unchanged.
        val s = set(ring(OrbitHit(0, 1), OrbitHit(4, 2, 0.8f), OrbitHit(11, 3, offset = 37)))
        assertTrue(OrbitClip.clip(s).notes.all { it.lengthPulses == s16 }, "all still a 16th")
    }

    // ---- it survives the file ----

    @Test
    fun `a length round-trips through orbits json, and a version 4 file still loads`() {
        val dir = Files.createTempDirectory("orbit-length").toFile().also { it.deleteOnExit() }
        val s = set(ring(OrbitHit(0, 1, length = 5 * s16), OrbitHit(4, 2)))
        OrbitStore.save(s, dir)
        val back = OrbitStore.load(dir)
        assertEquals(listOf(5 * s16, OrbitHit.WHOLE_SAMPLE), (back.orbits[0].content as PatternOrbit).hits.map { it.length })

        // An ungated hit writes no `length` key, so its shape is the one
        // every previous build wrote.
        val plain = Files.createTempDirectory("orbit-plain").toFile().also { it.deleteOnExit() }
        OrbitStore.save(set(ring(OrbitHit(0, 1))), plain)
        assertTrue("length" !in java.io.File(plain, OrbitStore.FILE_NAME).readText())

        // And a version 4 file — written before lengths existed — loads
        // with every hit playing its sample out.
        val older = Files.createTempDirectory("orbit-v4").toFile().also { it.deleteOnExit() }
        java.io.File(older, OrbitStore.FILE_NAME).writeText(
            """{"version": 4, "bpm": 92, "sampleRate": 48000, "lapSteps": 16, "orbits": [
               {"name": "R", "steps": 16, "span": "ONE", "voice": [1], "engaged": true, "level": 1, "pan": 0,
                "content": {"type": "pattern", "kit": "Break Kit", "hits": [
                  {"step": 0, "slot": 1, "velocity": 1, "offset": 12}]}}
            ]}""",
        )
        val migrated = (OrbitStore.load(older).orbits.single().content as PatternOrbit).hits.single()
        assertEquals(OrbitHit.WHOLE_SAMPLE, migrated.length, "a hit written before lengths plays out")
        assertEquals(12L, migrated.offset, "and keeps everything it did carry")
    }

    // ---- and the import carries it ----

    @Test
    fun `a clip's note length reaches the ring, completing the round trip`() {
        val kit = Kit("Break Kit", (1..4).map { KitPad(slot = it, sampleFile = "p$it.wav") })
        val clip = Mpc3Clip(
            "B",
            1,
            listOf(
                Mpc3Note(36, 0, 1f, lengthPulses = 4 * s16),
                Mpc3Note(38, 4 * s16, 0.8f), // the 240 default
            ),
        )
        val imported = OrbitImport.rings(clip, "break", kit, bpm, rate)
        val hits = imported.set.orbits.flatMap { (it.content as PatternOrbit).hits }
        assertEquals(
            listOf(4 * s16, OrbitHit.WHOLE_SAMPLE),
            hits.map { it.length },
            "a chosen length comes through; a bare 16th is what a note says when it says nothing",
        )
        assertEquals(
            clip.notes.map { it.lengthPulses },
            OrbitClip.clip(imported.set).notes.sortedBy { it.timePulses }.map { it.lengthPulses },
            "and goes back out as it came in",
        )
    }

    // ---- one conversion, not two ----

    @Test
    fun `a lean and a length are the same question, and one function answers it`() {
        val s = set(ring(OrbitHit(0, 1)))
        for (pulses in listOf(0L, 1L, 37L, 120L, 240L, 3 * s16)) {
            assertEquals(
                OrbitClock.framesForPulses(s, pulses),
                OrbitClock.offsetFrames(s, OrbitHit(0, 1, offset = if (pulses <= OrbitHit.MAX_OFFSET) pulses else 0L))
                    .let { if (pulses <= OrbitHit.MAX_OFFSET) it else OrbitClock.framesForPulses(s, pulses) },
                "at $pulses pulses",
            )
        }
        // A 16th is a step, whatever the tempo asks of it.
        assertEquals(step.toLong(), OrbitClock.framesForPulses(s, s16))
        // And it stays sign-symmetric, which is the fix that made a
        // humanised take stop drifting late.
        assertEquals(-OrbitClock.framesForPulses(s, 37L), OrbitClock.framesForPulses(s, -37L))
    }
}
