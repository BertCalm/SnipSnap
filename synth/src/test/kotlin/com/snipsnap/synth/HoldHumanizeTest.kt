package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player on a held ARCO and BORE note ([Humanize], the owner's verdict of 2026-11-20: "Holds are unnatural, they need more
 * humanization"), read off the held key as an instrument plays it: the zone's file, then its loop round and round.
 *
 * What was wrong was measured on the judged clips: a held note's level stayed inside 0.03 to 0.3 dB and BORE's vibrato was one sine.
 * So each held key here must show a level that moves, a pitch that leans under its vibrato, and swings that differ, while its mean
 * pitch stays on the key and its loop closes without a step. The same reads at [Humanize.OFF] are the control: they must come out
 * static, or the measures could not have seen the defect.
 */
class HoldHumanizeTest {

    /** A held key played for [seconds]: the file, then its loop repeated. */
    private fun held(n: KeyNote, seconds: Double): FloatArray {
        val x = n.snip.samples
        val ls = n.loopStartFrame.toInt()
        val out = FloatArray((seconds * RATE).toInt())
        var pos = 0
        for (i in out.indices) {
            if (pos >= x.size) pos = ls
            out[i] = x[pos++]
        }
        return out
    }

    /** The pitch every [HOP] seconds over [WINDOW], in cents against [hz]: the autocorrelation's peak near the note's period, refined between samples. */
    private fun centsTrack(x: FloatArray, hz: Double, from: Double, to: Double): DoubleArray {
        val period = RATE / hz
        val lo = (period * 0.96).toInt()
        val hi = (period * 1.04).toInt() + 1
        val n = (WINDOW * RATE).toInt()
        val out = ArrayList<Double>()
        var at = (from * RATE).toInt()
        val end = (to * RATE).toInt() - n - hi
        while (at < end) {
            val r = DoubleArray(hi - lo + 1)
            for (lag in lo..hi) {
                var s = 0.0
                for (i in at until at + n) s += x[i].toDouble() * x[i + lag]
                r[lag - lo] = s
            }
            var best = 1
            for (i in 1 until r.size - 1) if (r[i] > r[best]) best = i
            val d = (r[best - 1] - r[best + 1]) / (2 * (r[best - 1] - 2 * r[best] + r[best + 1]))
            out += 1200.0 * log2(period / (lo + best + d))
            at += (HOP * RATE).toInt()
        }
        return out.toDoubleArray()
    }

    /** The level every [HOP] seconds over [LEVEL_WINDOW] (two vibrato swings, so the vibrato's own pulse is averaged out), in dB. */
    private fun levelTrack(x: FloatArray, from: Double, to: Double): DoubleArray {
        val n = (LEVEL_WINDOW * RATE).toInt()
        val out = ArrayList<Double>()
        var at = (from * RATE).toInt()
        while (at + n < (to * RATE).toInt()) {
            var s = 0.0
            for (i in at until at + n) s += x[i].toDouble() * x[i]
            out += 10.0 * log10(s / n + 1e-20)
            at += (HOP * RATE).toInt()
        }
        return out.toDoubleArray()
    }

    private fun smooth(v: DoubleArray, width: Int): DoubleArray = DoubleArray(v.size - width + 1) { k -> (k until k + width).sumOf { v[it] } / width }
    private fun range(v: DoubleArray): Double = v.max() - v.min()

    /** The depth of each vibrato swing: the pitch's peak to peak in consecutive windows one swing long. */
    private fun swingDepths(cents: DoubleArray, vibratoHz: Double): DoubleArray {
        val w = (1.0 / vibratoHz / HOP).toInt().coerceAtLeast(3)
        return DoubleArray(cents.size / w) { k -> val s = cents.copyOfRange(k * w, (k + 1) * w); s.max() - s.min() }
    }

    private fun spread(v: DoubleArray): Double {
        val mean = v.average()
        return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size) / mean
    }

    private class Read(
        val meanCents: Double, val leanCents: Double, val levelDb: Double, val swingSpread: Double, val halfLoopRepeat: Double, val wrapStep: Double,
        /** The furthest the pitch goes from its mean, vibrato and lean together, in cents. */
        val furthestCents: Double,
    )

    /** What a held key shows over one pass of its loop. [vibratoHz] sets the windows; [loopSeconds] is the loop's own length. */
    private fun read(n: KeyNote, hz: Double, vibratoHz: Double): Read {
        val ls = n.loopStartFrame.toDouble() / RATE
        val loopSeconds = (n.snip.frameCount - n.loopStartFrame).toDouble() / RATE
        val x = held(n, ls + 2 * loopSeconds + 1.0)
        val cents = centsTrack(x, hz, ls, ls + loopSeconds + WINDOW + 0.05)
        val level = levelTrack(x, ls, ls + loopSeconds + LEVEL_WINDOW)
        val swing = (1.0 / vibratoHz / HOP).toInt()
        val lean = smooth(cents, 2 * swing)
        // The same level track half a loop on: a movement that repeats inside the loop would correlate near 1.
        val half = levelTrack(x, ls + loopSeconds / 2, ls + 1.5 * loopSeconds + LEVEL_WINDOW)
        val m = minOf(level.size, half.size)
        val a = level.copyOf(m); val b = half.copyOf(m)
        val ma = a.average(); val mb = b.average()
        var dot = 0.0; var ea = 0.0; var eb = 0.0
        for (i in 0 until m) { dot += (a[i] - ma) * (b[i] - mb); ea += (a[i] - ma) * (a[i] - ma); eb += (b[i] - mb) * (b[i] - mb) }
        // The step across the wrap against the steepest ordinary step in the loop.
        val s = n.snip.samples
        val start = n.loopStartFrame.toInt()
        val steps = FloatArray(s.size - start - 1) { abs(s[start + it + 1] - s[start + it]) }.also { it.sort() }
        val wrap = abs(s[start] - s[s.size - 1]) / steps[((steps.size - 1) * 0.999).toInt()]
        val mean = cents.average()
        return Read(mean, range(lean), range(level), spread(swingDepths(cents, vibratoHz)), dot / sqrt(ea * eb + 1e-30), wrap.toDouble(), cents.maxOf { abs(it - mean) })
    }

    private fun line(label: String, r: Read) = println(
        "$label: mean %+.2f cents, lean %.2f cents, furthest %.1f cents, level %.2f dB, swing spread %.2f, half-loop repeat %+.2f, wrap step %.2f of the steepest".format(
            Locale.ROOT, r.meanCents, r.leanCents, r.furthestCents, r.levelDb, r.swingSpread, r.halfLoopRepeat, r.wrapStep))

    private fun assertPlayed(label: String, r: Read) {
        assertTrue(abs(r.meanCents) < IN_TUNE_CENTS, "$label: the held note is ${r.meanCents} cents from its key")
        assertTrue(r.levelDb > MOVING_LEVEL_DB, "$label: the level moves only ${r.levelDb} dB over the loop")
        assertTrue(r.leanCents > MOVING_LEAN_CENTS, "$label: the pitch leans only ${r.leanCents} cents under the vibrato")
        assertTrue(r.swingSpread > MOVING_SWING_SPREAD, "$label: the vibrato's swings differ by only ${r.swingSpread} of their depth")
        assertTrue(r.halfLoopRepeat < HALF_LOOP_REPEAT, "$label: the level's movement repeats inside the loop (${r.halfLoopRepeat})")
        assertTrue(r.wrapStep < WRAP_STEP, "$label: the wrap steps ${r.wrapStep} times the loop's steepest ordinary step")
    }

    private val arcoKeys = listOf(ArcoVoice.CELLO to 48, ArcoVoice.ERHU to 71, ArcoVoice.SARANGI to 62)
    private val boreKeys = listOf(BoreVoice.FLUTE to 72, BoreVoice.SAX to 60)

    @Test
    fun `a held ARCO key moves like a player, stays on its key and closes, and with the player off it is static`() {
        for ((voice, midi) in arcoKeys) {
            val hz = Keys.midiHz(midi).toDouble()
            val zone = ArcoKeys.padMeasured(voice, Arco.defaults(voice), midi, ArcoKeys.Layer.HARD)
            assertTrue(zone.render.seam < Keys.MAX_SEAM_ERROR, "$voice: seam ${zone.render.seam}")
            assertEquals(Humanize.HELD_LOOP_SECONDS.toDouble(), zone.render.loopFrames.toDouble() / RATE, 0.02, "$voice: the loop's length")
            val played = read(HeldStart.shape(zone.note, HeldStart.SHORT), hz, Arco.VIBRATO_HZ)
            line("ARCO $voice held, played", played)
            assertPlayed("ARCO $voice", played)
            // The design's ceiling for a pad's vibrato (ArcoProductTest holds the LOOP to it): the player's lean and its uneven swings stay under it.
            assertTrue(played.furthestCents < ARCO_PITCH_CEILING_CENTS, "ARCO $voice: the held pitch goes ${played.furthestCents} cents from its mean")
            val plain = read(HeldStart.shape(ArcoKeys.padMeasured(voice, Arco.defaults(voice), midi, ArcoKeys.Layer.HARD, human = Humanize.OFF).note, HeldStart.SHORT), hz, Arco.VIBRATO_HZ)
            line("ARCO $voice held, player off", plain)
            assertTrue(plain.levelDb < STATIC_LEVEL_DB, "control, ARCO $voice: with the player off the level moves ${plain.levelDb} dB")
            assertTrue(plain.levelDb < played.levelDb / 2, "control, ARCO $voice: the player did not move the level")
        }
    }

    @Test
    fun `a held BORE key moves like a player, stays on its key and closes, and with the player off it is static`() {
        for ((voice, midi) in boreKeys) {
            val hz = Keys.midiHz(midi).toDouble()
            val breath = BoreKeys.layerBreaths(voice, Bore.defaults(voice)).last()
            val zone = BoreKeys.padMeasured(voice, Bore.defaults(voice), midi, breath)
            assertEquals(Humanize.HELD_LOOP_SECONDS.toDouble(), zone.join.lengthFrames.toDouble() / RATE, Bore.LOOP_SLACK_SECONDS + 0.01, "$voice: the loop's length")
            assertTrue(zone.join.correlation >= Bore.LOOP_MIN_CORRELATION, "$voice: the join correlates ${zone.join.correlation}")
            val played = read(HeldStart.shape(zone.note, HeldStart.SHORT), hz, Bore.VIBRATO_HZ)
            line("BORE $voice held, played", played)
            assertPlayed("BORE $voice", played)
            val off = BoreKeys.padMeasured(voice, Bore.defaults(voice), midi, breath, human = Humanize.OFF, opening = Bore.OPENING_AS_HEARD)
            val plain = read(HeldStart.shape(off.note, HeldStart.SHORT), hz, Bore.VIBRATO_HZ)
            line("BORE $voice held, player off", plain)
            assertTrue(plain.levelDb < STATIC_LEVEL_DB, "control, BORE $voice: with the player off the level moves ${plain.levelDb} dB")
            assertTrue(plain.swingSpread < MOVING_SWING_SPREAD / 2, "control, BORE $voice: with the player off the swings differ by ${plain.swingSpread}")
        }
    }

    @Test
    fun `the player is seeded - a render is the same twice, and two keys do not move alike`() {
        val d = Arco.defaults(ArcoVoice.CELLO) + ("HOLD" to Arco.holdFor(2.5f))
        assertContentEquals(Arco.render(ArcoVoice.CELLO, d).samples, Arco.render(ArcoVoice.CELLO, d).samples, "ARCO twice")
        val b = Bore.defaults(BoreVoice.SAX) + ("HOLD" to 0.8f)
        assertContentEquals(Bore.render(BoreVoice.SAX, b).samples, Bore.render(BoreVoice.SAX, b).samples, "BORE twice")
        val period = Humanize.HELD_LOOP_SECONDS.toDouble()
        val one = Humanize.Wander(Arco.playerSeed(ArcoVoice.CELLO, 48), period, Humanize.SLOW)
        val two = Humanize.Wander(Arco.playerSeed(ArcoVoice.CELLO, 51), period, Humanize.SLOW)
        val apart = (0 until 200).maxOf { abs(one.at(it * 0.02) - two.at(it * 0.02)) }
        assertTrue(apart > 0.3, "two keys' leans are the same curve (furthest apart $apart)")
    }

    @Test
    fun `a wander stays within one, repeats with its period, and so does its integral`() {
        for (period in listOf(2.0, 2.003, 4.0, 6.0)) for (cycles in listOf(Humanize.SLOW, Humanize.SLOW_ALT, Humanize.quick(period))) {
            val w = Humanize.Wander(12345, period, cycles)
            for (k in 0 until 400) {
                val t = k * 0.013
                assertTrue(abs(w.at(t)) <= 1.0 + 1e-12, "a wander of $period s left -1..1 at $t")
                assertEquals(w.at(t), w.at(t + period), 1e-9, "period $period: the wander does not repeat")
                assertEquals(w.integral(t), w.integral(t + period), 1e-9, "period $period: the integral does not repeat")
            }
            assertEquals(0.0, w.integral(0.0), 1e-12)
        }
    }

    @Test
    fun `a stab carries no player - short notes are the render they were`() {
        val cello = Arco.defaults(ArcoVoice.CELLO) + ("HOLD" to Arco.holdFor(0.5f))
        assertContentEquals(Arco.render(ArcoVoice.CELLO, cello, Arco.BUZZ_LAW, human = Humanize.OFF).samples, Arco.render(ArcoVoice.CELLO, cello).samples, "ARCO stab")
        val flute = Bore.defaults(BoreVoice.FLUTE) + ("HOLD" to 0f)
        assertContentEquals(Bore.render(BoreVoice.FLUTE, flute, Humanize.OFF).samples, Bore.render(BoreVoice.FLUTE, flute).samples, "BORE stab")
    }

    /** The loudest 20 ms of [x]'s first [seconds], and its level over [from]..[to] seconds, as RMS. */
    private fun rmsOver(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * RATE).toInt()
        val b = minOf(x.size, (to * RATE).toInt())
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }

    private fun db(x: Double) = 20.0 * log10(x + 1e-12)

    private class Start(val firstDb: Double, val overDb: Double, val halfSeconds: Double)

    /**
     * How a note starts against the note it becomes (its level from 0.3 to 0.5 s): its first 10 ms, then, from 30 ms on (past the click,
     * which on a pad stood over half the note's level by itself), the loudest 20 ms of its first quarter second and when it first reaches half.
     */
    private fun start(x: FloatArray): Start {
        val steady = rmsOver(x, 0.3, 0.5)
        var loudest = 0.0
        var half = Double.NaN
        var t = 0.03
        while (t < 0.25) {
            val e = rmsOver(x, t, t + 0.02)
            loudest = maxOf(loudest, e)
            if (half.isNaN() && e >= 0.5 * steady) half = t
            t += 0.005
        }
        return Start(db(rmsOver(x, 0.0, 0.01) / steady), db(loudest / steady), half)
    }

    @Test
    fun `SAX opens without its click and with less of a bark, and no later - on a pad and dry`() {
        // The owner on SAX's presets (2026-11-20): "Opening is a bit harsh for most." Read on the presets long enough to have a held part
        // to read against, as they land on a pad (through the light tape) and dry, with the player off on both sides so only the opening differs.
        val presets = BorePresets.forVoice(BoreVoice.SAX).filter { it.name in setOf("LOW HONK", "SMOOTH", "AIRY REED", "PAD REED", "GROWL") }
        assertEquals(5, presets.size, "the slow SAX presets")
        for (p in presets) {
            fun dry(o: Bore.Opening) = Bore.render(BoreVoice.SAX, p.macros + ("HOLD" to 0.5f), Humanize.OFF, o)
            fun pad(o: Bore.Opening) = (Bore.landingChain(p.macros)?.process(dry(o)) ?: dry(o)).samples
            val heardDry = start(dry(Bore.OPENING_AS_HEARD).samples)
            val softDry = start(dry(Bore.OPENING_SOFTENED).samples)
            val heardPad = start(pad(Bore.OPENING_AS_HEARD))
            val softPad = start(pad(Bore.OPENING_SOFTENED))
            println(
                "SAX opening ${p.name}: first 10 ms heard %+.1f dB, now %+.1f (pad %+.1f, now %+.1f); loudest 20 ms on a pad heard %+.1f dB, now %+.1f (dry %+.1f, now %+.1f); half level at %.0f ms, now %.0f (pad %.0f, now %.0f)".format(
                    Locale.ROOT, heardDry.firstDb, softDry.firstDb, heardPad.firstDb, softPad.firstDb, heardPad.overDb, softPad.overDb, heardDry.overDb, softDry.overDb,
                    1000 * heardDry.halfSeconds, 1000 * softDry.halfSeconds, 1000 * heardPad.halfSeconds, 1000 * softPad.halfSeconds))
            // The click is the lopsided wave's mean, so it is largest on the loose lips that rasp most: those two must lose most of it.
            val click = if (p.name in setOf("LOW HONK", "GROWL")) OPENING_CLICK_DB else OPENING_QUIETER_DB
            assertTrue(softDry.firstDb < heardDry.firstDb - click, "${p.name}: the first 10 ms are ${softDry.firstDb} dB against the note, ${heardDry.firstDb} before")
            assertTrue(softPad.overDb < heardPad.overDb - OPENING_BARK_DB, "${p.name}: on a pad the start stands ${softPad.overDb} dB over the note, ${heardPad.overDb} before")
            assertTrue(softDry.halfSeconds <= heardDry.halfSeconds + OPENING_LATE_SECONDS, "${p.name}: the note reaches half its level at ${softDry.halfSeconds} s, ${heardDry.halfSeconds} before")
            assertTrue(softPad.halfSeconds <= heardPad.halfSeconds + OPENING_LATE_SECONDS, "${p.name}: on a pad the note reaches half its level at ${softPad.halfSeconds} s, ${heardPad.halfSeconds} before")
        }
        // FLUTE's opening is the one it had.
        val flute = Bore.defaults(BoreVoice.FLUTE) + ("HOLD" to 0f)
        assertContentEquals(Bore.render(BoreVoice.FLUTE, flute, Humanize.OFF, Bore.OPENING_AS_HEARD).samples, Bore.render(BoreVoice.FLUTE, flute).samples, "FLUTE's opening moved")
    }

    @Test
    fun `a played ARCO note above the knee keeps the finished-peak bar, and every played note is level, finite and its own length`() {
        for (voice in ArcoMeasure.FIRST_VOICES) for (tune in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) for (body in listOf(0.5f, 0.75f, 1f)) {
            val m = Arco.defaults(voice) + mapOf("TUNE" to tune, Arco.boxKnob(voice) to body, "HOLD" to Arco.holdFor(3f))
            val x = Arco.render(voice, m).samples
            val label = "ARCO $voice TUNE $tune ${Arco.boxKnob(voice)} $body"
            assertEquals(Arco.renderFrames(voice, m), x.size, "$label: the player moved the note's length")
            assertTrue(x.all { it.isFinite() }, "$label: not finite")
            val peak = x.maxOf { abs(it) }
            assertTrue(peak <= 0.99f + 1e-6f, "$label: peaks at $peak")
            if (Arco.hasBody(voice) && body > Arco.BODY_KNEE) assertTrue(peak < Arco.FINISHED_PEAK_BAR, "$label: peaks at $peak, not under the finished-peak bar")
        }
    }

    private companion object {
        const val ARCO_PITCH_CEILING_CENTS = 15.0
        const val OPENING_CLICK_DB = 6.0
        const val OPENING_QUIETER_DB = 2.0
        const val OPENING_BARK_DB = 0.5
        const val OPENING_LATE_SECONDS = 0.02
        const val HOP = 0.01
        const val WINDOW = 0.05
        const val LEVEL_WINDOW = 0.4
        const val IN_TUNE_CENTS = 10.0
        const val MOVING_LEVEL_DB = 0.4
        const val MOVING_LEAN_CENTS = 1.5
        const val MOVING_SWING_SPREAD = 0.1
        const val STATIC_LEVEL_DB = 0.2
        const val HALF_LOOP_REPEAT = 0.8
        const val WRAP_STEP = 1.5
    }
}
