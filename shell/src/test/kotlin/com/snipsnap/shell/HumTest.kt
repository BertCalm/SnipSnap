package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HumTest {

    private val rate = 44_100

    /** Kick, closed hat, snare, open hat on half-second steps, three seconds long. */
    private fun breakSnip(): Snip {
        val step = rate / 2
        val hits = listOf(1 to DrumSynth.kick(), 2 to DrumSynth.closedHat(), 3 to DrumSynth.snare(), 4 to DrumSynth.openHat())
        val total = FloatArray(step * 6)
        for ((s, hit) in hits) {
            val at = s * step
            for (i in hit.samples.indices) if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
        }
        return Snip(total, 1, rate)
    }

    /** Mouth sounds (stand-ins from the synth) at the given seconds, three seconds of hum. */
    private fun hum(vararg sounds: Pair<Double, Snip>, seconds: Double = 3.0): Snip {
        val total = FloatArray((rate * seconds).toInt())
        for ((sec, s) in sounds) {
            val at = (sec * rate).toInt()
            for (i in s.samples.indices) if (at + i < total.size) total[at + i] += s.samples[i] * 0.6f
        }
        return Snip(total, 1, rate)
    }

    @Test
    fun `the mouth's sounds pick the hits they land on, late by one lag, and name them`() {
        val tape = breakSnip()
        val hits = CatchModel.hitsOf(tape)
        assertEquals(4, hits.size)
        // Boom on the kick, tss on the snare, both 70 ms late; a boom between the hat and the snare that meets nothing.
        val reading = Hum.read(tape, hum(0.57 to DrumSynth.kick(), 1.57 to DrumSynth.closedHat(), 1.25 to DrumSynth.kick()))
        assertEquals(listOf(0, 2), reading.cuts.map { it.hit }, "the kick and the snare, in tape order")
        assertEquals(1, reading.missed, "the boom between the hits found none")
        assertEquals(4, reading.hitsHeard)
        assertTrue(abs(reading.lagFrames - (0.07 * rate).toInt()) < (0.012 * rate).toInt(), "the lag is the 70 ms the hum was late, got ${reading.lagFrames}")
        assertEquals(hits[0].range, reading.cuts[0].range, "INSTANT KIT's own cut of the kick")
        assertEquals(DrumClass.KICK, reading.cuts[0].mouth, "the mouth said boom")
        assertEquals(DrumClass.HAT_CLOSED, reading.cuts[1].mouth, "the mouth said tss over the snare")
        assertEquals(3, reading.pattern.size, "the beat you sang, every sound of it")
        assertTrue(abs(reading.cuts[0].at - hits[0].range.first) <= (Hum.MATCH_SEC * rate).toInt())

        val model = ChopReviewModel.chop(tape, reading.mode())
        assertEquals(2, model.sliceCount)
        assertEquals(listOf(hits[0].range.first, hits[2].range.first), model.cutFrames())
        assertEquals("HUMMED", model.modeLabel())
        assertNull(ChopReviewModel.hitsOf(model.mode), "the bench's hit controls don't reach a hum")
        assertEquals(DrumClass.KICK, model.rows[0].effectiveClass)
        assertEquals(DrumClass.HAT_CLOSED, model.rows[1].effectiveClass, "the chip is the mouth's word")
        assertTrue(model.rows[1].overridden, "and it reads YOU ✓, since the tape's own word was SNARE")
        assertEquals(DrumClass.SNARE, model.rows[1].classification.drumClass)
        assertTrue(model.sendToGrid().arranged.count { it != null } == 2, "SEND lands the two")
        val again = model.rechop()
        assertEquals(listOf(DrumClass.KICK, DrumClass.HAT_CLOSED), again.rows.map { it.effectiveClass }, "RE-CHOP of a hum keeps the mouth's words")
    }

    @Test
    fun `two mouth sounds on one hit keep the nearer, an unsure mouth leaves the tape's word, and a hum too late lands nothing`() {
        val tape = breakSnip()
        val two = Hum.read(tape, hum(0.52 to DrumSynth.kick(), 0.46 to DrumSynth.closedHat()))
        assertEquals(listOf(0), two.cuts.map { it.hit })
        assertEquals(1, two.missed, "the further one is a miss")
        assertEquals(DrumClass.KICK, two.cuts[0].mouth, "the nearer sound's word")

        val unsure = Hum.read(tape, hum(0.57 to DrumSynth.kick(), 1.57 to DrumSynth.closedHat()), sure = 1.1f)
        assertEquals(listOf(0, 2), unsure.cuts.map { it.hit })
        assertTrue(unsure.cuts.all { it.mouth == null }, "nothing clears a bar of 1.1")
        val model = ChopReviewModel.chop(tape, unsure.mode())
        assertEquals(DrumClass.SNARE, model.rows[1].effectiveClass, "the tape's own word stands")
        assertFalse(model.rows[1].overridden)

        // On a half-second grid a hum 300 ms late is also 200 ms early for
        // the next hit, and reads as that; 250 ms from every hit is the
        // honest "too far" — no offset within reach, so no lag, no match.
        val late = Hum.read(tape, hum(0.75 to DrumSynth.kick(), 1.75 to DrumSynth.closedHat()))
        assertTrue(late.cuts.isEmpty(), "250 ms from every hit is past the lag a hum can carry")
        assertEquals(2, late.missed)
        assertEquals(0, late.lagFrames)
    }

    @Test
    fun `a hum whose window starts later on the tape is read from there, and a held vowel names nothing`() {
        val tape = breakSnip()
        val hits = CatchModel.hitsOf(tape)
        // The ring kept only the hum's last part: its first frame sits a second in.
        val later = Hum.read(tape, hum(0.55 to DrumSynth.kick()), offsetFrames = rate)
        assertEquals(listOf(2), later.cuts.map { it.hit }, "0.55 s into a window that starts at 1.0 s is the snare")
        assertEquals(hits[2].range, later.cuts[0].range)
        // A hummed note over the kick: a note is not a drum, so the kick's own word stands.
        val vowel = Snip(FloatArray((0.25 * rate).toInt()) { 0.4f * kotlin.math.sin(2 * Math.PI * 220 * it / rate).toFloat() }, 1, rate)
        val sung = Hum.read(tape, hum(0.53 to vowel))
        assertEquals(listOf(0), sung.cuts.map { it.hit })
        assertTrue(sung.cuts[0].mouth != DrumClass.TONAL, "a held vowel never becomes a TONAL chip")
    }

    /** Eight hits on half-second steps over five seconds: enough of a pulse for a confident tempo. */
    private fun longBreak(): Snip {
        val step = rate / 2
        val kit = listOf(DrumSynth.kick(), DrumSynth.closedHat(), DrumSynth.snare(), DrumSynth.closedHat())
        val total = FloatArray(step * 10)
        for (s in 1..8) {
            val hit = kit[(s - 1) % 4]
            val at = s * step
            for (i in hit.samples.indices) if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
        }
        return Snip(total, 1, rate)
    }

    @Test
    fun `the beat you sang goes on as the kit's groove, where and as loud as the mouth made it`() {
        val tape = longBreak()
        val hits = CatchModel.hitsOf(tape)
        assertEquals(8, hits.size)
        // Boom, tss, boom, tss on the kick, the snare, the kick, the snare; the second boom quieter.
        val quiet = Snip(FloatArray(DrumSynth.kick().frameCount) { DrumSynth.kick().samples[it] * 0.35f }, 1, rate)
        val reading = Hum.read(tape, hum(0.56 to DrumSynth.kick(), 1.56 to DrumSynth.closedHat(), 2.56 to quiet, 3.56 to DrumSynth.closedHat(), seconds = 5.0))
        assertEquals(listOf(0, 2, 4, 6), reading.cuts.map { it.hit })
        assertTrue(reading.cuts[2].loud < reading.cuts[0].loud, "the quieter boom is quieter")
        assertTrue(reading.cuts.all { it.loud in Hum.VELOCITY_FLOOR..1f })

        val model = ChopReviewModel.chop(tape, reading.mode())
        val tempo = assertNotNull(model.tempo, "the long break has a pulse")
        val clip = assertNotNull(Hum.groove(model, "break"))
        assertEquals("break Sung", clip.name)
        assertEquals(4, clip.notes.size, "one note per sound the mouth made")
        val framesPerPulse = 60.0 / tempo.bpm * rate / 960.0
        val placed = model.placementPreview()
        for ((i, note) in clip.notes.withIndex()) {
            val beat = (model.mode as ChopReviewModel.ChopMode.Hummed).beat[i]
            assertEquals(Math.round(beat.at / framesPerPulse), note.timePulses, "note $i sits where the mouth put it")
            val slot = placed.indexOfFirst { it === model.rows[i] } + 1
            assertEquals(com.snipsnap.mpc3.Mpc3Note.noteFor(slot), note.note, "note $i plays the pad its cut landed on")
            assertEquals(beat.velocity, note.velocity)
        }
        assertTrue(clip.notes[2].velocity < clip.notes[0].velocity)
        assertTrue(clip.bars >= 1)

        assertNull(Hum.groove(model.merged(0)!!, "break"), "a merge moved the cuts off the beat: no groove")
        assertNull(Hum.groove(ChopReviewModel.chop(tape), "break"), "a chop by hits sang nothing")

        val dir = java.nio.file.Files.createTempDirectory("sung").toFile()
        try {
            KitBuilderModel.fromChop("Sung", model.sendToGrid().arranged, dir)
            Hum.landGroove(dir, clip)
            val kept = com.snipsnap.kit.GrooveStore.load(dir)
            assertTrue(kept.any { it.name == clip.name }, "the kit's grooves carry the sung clip: ${kept.map { it.name }}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a hum at its own rate is read in the tape's frames`() {
        val tape = breakSnip()
        val full = hum(0.55 to DrumSynth.kick(), 1.55 to DrumSynth.snare())
        // The mic's rate is not the tape's: every other sample, 22.05 kHz.
        val half = Snip(FloatArray(full.frameCount / 2) { full.samples[it * 2] }, 1, rate / 2)
        val reading = Hum.read(tape, half)
        assertEquals(listOf(0, 2), reading.cuts.map { it.hit })
        assertTrue(abs(reading.lagFrames - (0.05 * rate).toInt()) < (0.015 * rate).toInt(), "the lag is in tape frames, got ${reading.lagFrames}")
    }
}
