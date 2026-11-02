package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.synth.Dsp.RATE
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BALLAST held as a keygroup zone ([Keys.ballastPad], [Ballast.renderHeldNote]): the note's onset and the
 * structure settling, then the held loop the engine already renders, with the loop marker where the settling ends.
 */
class BallastHeldTest {

    private fun f(x: Double) = "%.2f".format(java.util.Locale.ROOT, x)
    private fun cents(a: Double, b: Double) = 1200.0 * ln(a / b) / ln(2.0)
    private fun macrosAt(voice: BallastVoice, midi: Int) =
        Ballast.defaults(voice) + ("TUNE" to (midi - Ballast.ROOT_MIDI) / Ballast.TUNE_SEMITONES.toFloat())

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return sqrt(e / (to - from).coerceAtLeast(1))
    }

    /** The magnitude of DFT bin [k] over [x] from [from], [n] frames long: one exact bin, no window, for a periodic stretch. */
    private fun bin(x: FloatArray, from: Int, n: Int, k: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val a = 2.0 * PI * k * i / n
            re += x[from + i] * cos(a)
            im -= x[from + i] * sin(a)
        }
        return hypot(re, im) / n
    }

    @Test
    fun `thirteen zones, every minor third from C1 to C4, the same for every voice`() {
        assertEquals((24..60 step 3).toList(), Keys.ballastPadMidis())
    }

    @Test
    fun `a zone outside C1 to C4 is refused`() {
        assertFailsWith<IllegalArgumentException> { Keys.ballastPad(BallastVoice.ROOT, emptyMap(), 23) }
        assertFailsWith<IllegalArgumentException> { Keys.ballastPad(BallastVoice.ROOT, emptyMap(), 61) }
    }

    /**
     * The gate for the default sound: every one of ROOT's thirteen zones keeps an onset in front of the loop, closes
     * on the plain structure (no calmer last-resort pass, which would also quieten the onset's rattle), is whole
     * periods of the zone's own note, and holds that note rather than one of the wires below it.
     */
    @Test
    fun `every default ROOT zone keeps its onset, closes on the plain structure and holds its own note`() {
        val voice = BallastVoice.ROOT
        val zones = Keys.ballastPadMidis().parallelStream().map { midi -> midi to Ballast.renderHeldNote(voice, macrosAt(voice, midi)) }.toList()
        for ((midi, held) in zones) {
            val x = held.audio
            val plan = Ballast.planLoop(voice, Keys.midiHz(midi).toDouble())
            val loopFrames = x.size - held.loopStart
            val loopLoud = Loudness.of(x.copyOfRange(held.loopStart, x.size), 1, RATE)
            val struck = Loudness.of(x.copyOfRange(0, Math.round(Ballast.HELD_LEVEL_SECONDS * RATE).toInt()), 1, RATE)
            val onset = rms(x, 0, (0.5 * RATE).toInt())
            val loop = rms(x, held.loopStart, x.size)
            val fundamental = bin(x, held.loopStart, loopFrames, plan.periods)
            val half = bin(x, held.loopStart, loopFrames, plan.periods / 2)
            val quarter = bin(x, held.loopStart, loopFrames, plan.periods / 4)
            println(
                "BALLAST ROOT zone ${Scales.nameOf(midi)}: attack ${f(held.loopStart.toDouble() / RATE)} s, loop ${f(loopFrames.toDouble() / RATE)} s, " +
                    "seam ${"%.2e".format(held.seam)}, calm ${held.calm}, struck ${f(20 * log10(struck / Dsp.MELODIC_LOUDNESS_TARGET.toDouble()))} dB and loop ${f(20 * log10(loopLoud / Dsp.MELODIC_LOUDNESS_TARGET.toDouble()))} dB re target, " +
                    "onset/loop ${f(20 * log10(onset / loop))} dB, note over 1/2 ${f(20 * log10(fundamental / half))} dB, over 1/4 ${f(20 * log10(fundamental / quarter))} dB"
            )
            assertTrue(held.seam < Keys.MAX_SEAM_ERROR, "$midi: the loop does not close (seam ${held.seam})")
            assertEquals(0, held.calm, "$midi needed a calmer pass, which changes the onset's rattle")
            assertEquals(plan.frames, loopFrames, "$midi: the loop is not the planned whole periods")
            assertTrue(abs(cents(plan.hz, Keys.midiHz(midi).toDouble())) < 1.0, "$midi: the loop moved the pitch ${f(cents(plan.hz, Keys.midiHz(midi).toDouble()))} cents")
            assertTrue(held.loopStart >= (Ballast.PREROLL_MIN_SECONDS * RATE).toInt() - 1, "$midi: the attack is shorter than the shortest preroll")
            assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } <= 0.99f + 1e-6f)
            assertTrue(onset > 0.1 * loop, "$midi: no onset in front of the loop")
            // Levelled where it is struck; a peak limit may only pull a zone down.
            assertTrue(struck <= Dsp.MELODIC_LOUDNESS_TARGET * 1.01f && struck >= Dsp.MELODIC_LOUDNESS_TARGET * 0.5f, "$midi: struck loudness $struck")
            assertTrue(loopLoud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.4f && loopLoud <= Dsp.MELODIC_LOUDNESS_TARGET * 2f, "$midi: loop loudness $loopLoud")
            assertTrue(fundamental > 10 * half && fundamental > 10 * quarter, "$midi: the loop holds a wire below the note, not the note")
        }
        // Short notes are most of a bass line: neighbouring zones' struck levels may not step audibly.
        val struck = zones.map { (_, h) -> Loudness.of(h.audio.copyOfRange(0, Math.round(Ballast.HELD_LEVEL_SECONDS * RATE).toInt()), 1, RATE) }
        for (i in 1 until struck.size) {
            val step = 20 * log10(struck[i] / struck[i - 1].toDouble())
            assertTrue(abs(step) < 1.5, "zones ${zones[i - 1].first} to ${zones[i].first}: the struck level steps ${f(step)} dB")
        }
    }

    /** Every voice's zones close at the bottom, the middle and the top, through the public zone render that refuses a click. */
    @Test
    fun `every voice's zones at C1, F#2 and C4 close`() {
        val cases = BallastVoice.entries.flatMap { v -> listOf(24, 42, 60).map { v to it } }
        val notes = cases.parallelStream().map { (v, midi) -> Triple(v, midi, Keys.ballastPad(v, emptyMap(), midi)) }.toList()
        for ((v, midi, note) in notes) {
            val x = note.snip.samples
            val start = note.loopStartFrame.toInt()
            val seam = Keys.seamError(x, start)
            println("BALLAST $v zone $midi: attack ${f(start.toDouble() / RATE)} s, total ${f(x.size.toDouble() / RATE)} s, seam ${"%.2e".format(seam)}")
            assertTrue(start > 0 && start < x.size)
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "$v $midi: seam $seam")
            assertTrue(x.all { it.isFinite() })
        }
    }

    /**
     * The zone's loop is the engine's own held loop, with nothing new in it: rotated to the same zero crossing and
     * levelled the same way, it matches what HOLD at the top already exports. Only the onset in front is added.
     */
    @Test
    fun `the zone's loop is the held loop HOLD already exports`() {
        for (voice in listOf(BallastVoice.ROOT, BallastVoice.GLINT)) {
            val m = macrosAt(voice, 36)
            val existing = Ballast.renderLoopMeasured(voice, m)
            val held = Ballast.renderHeldNote(voice, m)
            assertEquals(existing.calm, held.calm)
            assertEquals(existing.prerollSeconds, held.prerollSeconds)
            val one = held.audio.copyOfRange(held.loopStart, held.audio.size)
            val cut = Siren.bestCut(one, one.size)
            val rotated = one.copyOfRange(cut, one.size) + one.copyOfRange(0, cut)
            Dsp.levelTo(rotated, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            assertEquals(existing.loop.size, rotated.size)
            var worst = 0f
            for (i in rotated.indices) worst = max(worst, abs(rotated[i] - existing.loop[i]))
            println("BALLAST $voice C2: zone loop against HOLD's loop, worst difference ${"%.2e".format(worst)}")
            assertTrue(worst < 1e-4f, "$voice: the zone's loop is not HOLD's loop (worst difference $worst)")
        }
    }

    @Test
    fun `a zone is reproducible`() {
        val a = Keys.ballastPad(BallastVoice.DEEP, mapOf("GLASS" to 0.6f), 33)
        val b = Keys.ballastPad(BallastVoice.DEEP, mapOf("GLASS" to 0.6f), 33)
        assertEquals(a.loopStartFrame, b.loopStartFrame)
        assertContentEquals(a.snip.samples, b.snip.samples)
    }

    @Test
    fun `a cancelled zone stops before it renders`() {
        assertFailsWith<CancellationException> { Keys.ballastPad(BallastVoice.ROOT, emptyMap(), 36) { true } }
    }
}
