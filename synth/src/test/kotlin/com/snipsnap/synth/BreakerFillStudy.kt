package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * BREAKER's surf tom fill, round 0: four figures down the BREAKER kit's own three toms (A11 High, A10 Rack,
 * A09 Floor), for the owner to pick one by ear before anything ships.
 *
 * Each figure is fresh strikes of the toms' own sources, summed and sent through one pass of the rig, the way
 * the snare riser is built (`Breaker.riserRoll`): the spring fills across the run and the crush pumps with it,
 * which three separate tom pads, each with its own rig, cannot do. Figure A is also played on those three pads,
 * so the page can let the owner hear what one pass adds.
 *
 * Every clip is at 92 BPM, the tempo every BREAKER beat was heard at, and is heard alone and in two bars of the
 * BREAKER kit landing on a crash. The rig is the rack tom's as it sits on the kit (the middle of the three toms'
 * springs). All of the figures, their accents and the rig choice are shape; the owner has heard none of it.
 *
 * Run: `./gradlew :synth:generateBreakerFillStudy` (writes testkit/breaker-fill-study/).
 */
object BreakerFillStudy {
    private const val BPM = 92.0
    private const val BEAT = 60.0 / BPM
    private const val PRE = .05
    private const val TAIL = 1.2
    private val rate = Dsp.RATE
    private val kit = BreakerKits.patches(BreakerKits.Kit.BREAKER)
    private val levels = BreakerKits.levels(BreakerKits.Kit.BREAKER)
    private const val FLOOR = 9
    private const val RACK = 10
    private const val HIGH = 11

    private fun pad(slot: Int) = kit[slot - 1]

    /** One stroke: when (in beats from the figure's start), which tom pad, how hard. */
    data class Stroke(val beat: Double, val slot: Int, val velocity: Float)

    data class Figure(val id: String, val label: String, val beats: Double, val strokes: List<Stroke>)

    /** A grace note this far ahead of its stroke: a flam. */
    private const val FLAM_SECONDS = .03

    val FIGURES: List<Figure> = listOf(
        // A: two beats of sixteenths down the toms, accents on the beats.
        Figure("a", "Sixteenths down", 2.0, listOf(HIGH, HIGH, RACK, RACK, FLOOR, FLOOR, FLOOR, FLOOR)
            .zip(listOf(.85f, .6f, .75f, .6f, .9f, .65f, .8f, .95f))
            .mapIndexed { i, (slot, v) -> Stroke(i * .25, slot, v) }),
        // B: two beats of sixteenth-note triplets, four on each tom, accents every third.
        Figure("b", "Triplets down", 2.0, (0 until 12).map { i ->
            Stroke(i / 6.0, listOf(HIGH, RACK, FLOOR)[i / 4], if (i == 11) 1f else if (i % 3 == 0) .9f else .6f)
        }),
        // C: a bar on the floor tom, long-short-short each beat, and the last beat down the toms.
        Figure("c", "Floor tom gallop", 4.0, (0..2).flatMap { b ->
            listOf(Stroke(b + 0.0, FLOOR, .9f), Stroke(b + .5, FLOOR, .6f), Stroke(b + .75, FLOOR, .7f))
        } + listOf(Stroke(3.0, HIGH, .8f), Stroke(3.25, HIGH, .7f), Stroke(3.5, RACK, .85f), Stroke(3.75, FLOOR, 1f))),
        // D: four flams on the eighths, high, rack, floor, floor: slower and heavier.
        Figure("d", "Flams down", 2.0, listOf(HIGH, RACK, FLOOR, FLOOR).zip(listOf(.9f, .9f, .95f, 1f)).flatMapIndexed { i, (slot, v) ->
            listOf(Stroke(i * .5 - FLAM_SECONDS / BEAT, slot, .4f), Stroke(i * .5, slot, v))
        }),
    )

    /**
     * BREAKER's rig on a dry buffer, as `Breaker.render` runs it for a tom on [model]: DUST, the tube, the first crush,
     * the tank (tank B), the slap, the second crush, the combo on the open rig (no speaker from model 4), the DC
     * cut, the tail fade and the level. [check] proves this copy matches the engine.
     */
    private fun rig(dry: FloatArray, m: Map<String, Float>, model: Int, frames: Int): FloatArray {
        val crush = m.getValue("CRUSH").toDouble()
        val raw = Snip(dry, 1, rate)
        val dusted = Breaker.dustChain(m.getValue("DUST"), BreakerVoice.TOM_RACK, model)?.process(raw) ?: raw
        var x = DoubleArray(dusted.samples.size) { dusted.samples[it].toDouble() }
        x = BreakerChain.saturate(x, rate)
        x = BreakerChain.crush(x, minOf(1.0, crush / .5) * .7, rate)
        x = BreakerChain.tankMix(x, m.getValue("DRIP").toDouble(), frames, rate, BreakerChain.TANK_B)
        x = BreakerChain.slap(x, m.getValue("SLAP").toDouble(), rate, BreakerChain.SLAP_SECONDS)
        x = BreakerChain.crush(x, maxOf(0.0, (crush - .5) / .5), rate)
        val amp = m.getValue("AMP")
        val pre = FloatArray(frames) { x.getOrElse(it) { 0.0 }.toFloat() }
        val cab = if (model >= 4) 0f else .25f
        val out = Valve.process(Snip(pre, 1, rate), mapOf("DRIVE" to .3f + .45f * amp, "SAG" to .35f, "TONE" to .6f, "CAB" to cab)).samples.copyOf(frames)
        val mean = out.average().toFloat()
        val dc = Dsp.FixedOnePole(20f, rate)
        for (i in out.indices) { val s = out[i] - mean; out[i] = s - dc.lp(s) }
        val fade = (.25 * rate).toInt()
        for (k in 0 until fade) out[frames - fade + k] *= (0.5 + 0.5 * cos(PI * k / fade)).toFloat()
        Dsp.levelTo(out, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    /** The copy of the rig is the engine's: one rack tom strike through [rig] against `Breaker.render`. */
    fun check(): Float {
        val p = pad(RACK)
        val m = Breaker.settled(p.voice, p.macros, p.model)
        val src = Breaker.source(p.voice, m, .8f, 2, p.model).samples
        val mine = rig(src, m, p.model, (Breaker.SECONDS * rate).toInt())
        val engine = Breaker.render(p.voice, p.macros, .8f, 2, p.model).samples
        require(mine.size == engine.size) { "lengths ${mine.size} and ${engine.size}" }
        return mine.indices.maxOf { abs(mine[it] - engine[it]) }
    }

    /** [f] as one pad: every stroke a fresh strike of its tom, through one pass of the rack tom's rig. */
    fun fill(f: Figure, take: Int = 0): Snip {
        val frames = ((PRE + f.beats * BEAT + TAIL) * rate).toInt()
        val dry = FloatArray(frames)
        f.strokes.forEachIndexed { i, s ->
            val p = pad(s.slot)
            val m = Breaker.settled(p.voice, p.macros, p.model)
            val hit = Breaker.source(p.voice, m, s.velocity, take * 100 + i + 1, p.model).samples
            val o = ((PRE + s.beat * BEAT) * rate).roundToInt()
            // Velocity is the strike's character (the source) and its level (shape: linear in velocity).
            for (k in hit.indices) if (o + k in 0 until frames) dry[o + k] += s.velocity * hit[k]
        }
        val peak = dry.maxOf { abs(it) }
        if (peak > 0f) for (i in dry.indices) dry[i] /= peak
        val rack = pad(RACK)
        return Snip(rig(dry, Breaker.settled(rack.voice, rack.macros, rack.model), rack.model, frames), 1, rate)
    }

    /** [f] played on the kit's own three tom pads, each stroke that pad's own render and rig. */
    fun onPads(f: Figure): Snip {
        val frames = ((PRE + f.beats * BEAT + TAIL) * rate).toInt() + (Breaker.SECONDS * rate).toInt()
        val out = FloatArray(frames)
        f.strokes.forEachIndexed { i, s ->
            val hit = pad(s.slot).render(s.velocity, i % BreakerKits.TAKES).samples
            mixAt(out, hit, PRE + s.beat * BEAT, levels[s.slot - 1])
        }
        return Snip(out, 1, rate)
    }

    private fun mixAt(out: FloatArray, hit: FloatArray, seconds: Double, gain: Float) {
        val o = (seconds * rate).roundToInt()
        for (k in hit.indices) if (o + k in out.indices) out[o + k] += gain * hit[k]
    }

    /**
     * Two bars of the BREAKER kit with [fill] (already rendered, [fillBeats] long) closing bar 2, landing on
     * bar 3's crash and kick: kick on one and the and of three, snare on two and four, the closed hat on the
     * eighths. A two-beat fill takes bar 2's last two beats; a one-bar fill takes bar 2.
     */
    fun groove(fill: Snip, fillBeats: Double): Snip {
        val total = 8 * BEAT + 3.2
        val out = FloatArray((total * rate).toInt())
        var n = 0
        fun hit(slot: Int, beat: Double, v: Float) = mixAt(out, pad(slot).render(v, n++ % BreakerKits.TAKES).samples, beat * BEAT, levels[slot - 1])
        val fillAt = 8 - fillBeats
        for (bar in 0..1) {
            val b = bar * 4.0
            for (beat in listOf(0.0, 2.5)) if (b + beat < fillAt) hit(1, b + beat, if (beat == 0.0) 1f else .8f)
            for (beat in listOf(1.0, 3.0)) if (b + beat < fillAt) hit(2, b + beat, .95f)
            for (e in 0..7) if (b + e * .5 < fillAt) hit(3, b + e * .5, if (e % 2 == 0) .6f else .42f)
        }
        // The fill pad sits at the toms' level on the kit.
        mixAt(out, fill.samples, fillAt * BEAT - PRE, levels[RACK - 1])
        hit(14, 8.0, 1f); hit(1, 8.0, 1f)
        // Under full scale as a whole (the crash and kick land together); the page levels every clip anyway.
        val peak = out.maxOf { abs(it) }
        if (peak > .9f) for (i in out.indices) out[i] *= .9f / peak
        return Snip(out, 1, rate)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "testkit/breaker-fill-study").apply { deleteRecursively(); mkdirs() }
        val drift = check()
        println(String.format(java.util.Locale.ROOT, "rig copy against Breaker.render: max difference %.2e", drift))
        require(drift < 1e-6f) { "the study's rig is not the engine's (max difference $drift)" }
        fun write(name: String, s: Snip) = WavWriter.write(File(dir, "$name.wav"), s)
        for (f in FIGURES) {
            val one = fill(f)
            write("${f.id}_alone", one)
            write("${f.id}_groove", groove(one, f.beats))
            val again = fill(f, take = 1)
            write("${f.id}_again", again)
            println(String.format(java.util.Locale.ROOT, "%s %-18s %2d strokes, %.2f s", f.id, f.label, f.strokes.size, one.frameCount.toDouble() / rate))
        }
        val a = FIGURES.first()
        write("a_pads_alone", onPads(a))
        write("a_pads_groove", groove(onPads(a), a.beats))
    }
}
