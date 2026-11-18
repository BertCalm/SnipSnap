package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** SHATTER: the breakcore family's break. One original two-bar break, played once on SKIN
 * drums in one room, then cut at its own hits into sixteen slices.
 *
 * The characteristic gesture is: one take -> cut at the hits -> each slice drags its
 * neighbours' room.
 *
 * Nothing is rendered per pad. The whole performance is rendered at a force (velocity) and
 * a take (a different performance: timing, strike noise, force), through a shared [Room] and
 * a light [Tape], and every pad is a window of it. So a kick slice carries the ride wash and
 * the snare tail that were sounding when it was cut, which is what a sampled break is and
 * an isolated hit is not. SPLINTER, the family's edit kit, is the opposite: isolated hits.
 *
 * Softer is a softer performance: darker strikes, a quieter room, less tape drive.
 * Every constant is a shape awaiting the owner's listen. */
object Shatter : BreakcoreKit {
    const val VERSION = 1
    override val id = "shatter-breakcore"
    override val title = "Shatter Breakcore"
    override val bpm = 180

    enum class Drum { KICK, SNARE, GHOST, RIDE, OPEN }
    data class Hit(val step: Int, val drum: Drum, val force: Double)

    /** The break, in sixteenths over two bars. Ride on every eighth; ghosts between. */
    val performance: List<Hit> = buildList {
        for (s in 0 until 32 step 2) if (s != 28) add(Hit(s, Drum.RIDE, if (s % 4 == 0) .75 else .55))
        listOf(0 to 1.0, 6 to .8, 7 to .55, 10 to .9, 16 to 1.0, 18 to .7, 24 to .85, 30 to .75).forEach { (s, f) -> add(Hit(s, Drum.KICK, f)) }
        listOf(4 to 1.0, 12 to .95, 20 to 1.0, 23 to .7, 26 to .8, 28 to 1.0, 31 to .6).forEach { (s, f) -> add(Hit(s, Drum.SNARE, f)) }
        listOf(3 to .35, 9 to .4, 13 to .3, 15 to .45, 22 to .4, 27 to .35, 30 to .4).forEach { (s, f) -> add(Hit(s, Drum.GHOST, f)) }
        add(Hit(28, Drum.OPEN, .7))
    }.sortedBy { it.step }

    /** Where the chopper cut: the step each pad starts on. Each pad ends where the next begins. */
    val cuts = listOf(0, 3, 4, 6, 7, 9, 10, 12, 16, 20, 22, 23, 24, 26, 28, 30)

    override val layout: List<Splinter.Part> = listOf(
        Splinter.Part("Kick Ride", DrumClass.KICK, 1f),
        Splinter.Part("Ghost", DrumClass.SNARE, .6f),
        Splinter.Part("Snare Ride", DrumClass.SNARE, .9f),
        Splinter.Part("Kick Push", DrumClass.KICK, .9f),
        Splinter.Part("Kick Drag", DrumClass.KICK, .85f),
        Splinter.Part("Ghost Two", DrumClass.SNARE, .6f),
        Splinter.Part("Kick Late", DrumClass.KICK, .9f),
        Splinter.Part("Snare Tail", DrumClass.SNARE, .85f),
        Splinter.Part("Downbeat", DrumClass.KICK, 1f),
        Splinter.Part("Crack", DrumClass.SNARE, .9f),
        Splinter.Part("Ghost Ride", DrumClass.PERC, .6f),
        Splinter.Part("Flam", DrumClass.SNARE, .75f),
        Splinter.Part("Kick Answer", DrumClass.KICK, .9f),
        Splinter.Part("Snare Drop", DrumClass.SNARE, .85f),
        Splinter.Part("Open Snare", DrumClass.SNARE, .85f),
        Splinter.Part("Turnaround", DrumClass.PERC, .85f),
        Splinter.Part("Slice Repeat", DrumClass.LOOP, .7f),
        Splinter.Part("Break Rush", DrumClass.LOOP, .7f),
    )

    /** A rendered performance and where each step's hits actually landed. */
    private class Take(val audio: FloatArray, val onset: IntArray)
    private val takes = ConcurrentHashMap<Pair<Double, Int>, Take>()

    private val step: Double get() = Breakcore.beat(bpm) / 4

    private fun play(v: Double, t: Int): Take = takes.getOrPut(v to t) {
        val r = Random(Dsp.seedFor("shatter", t).toLong())
        // A take is a human performance: each step lands up to about 3 ms off the grid, each hit
        // a few percent harder or softer. Hits on one step share their timing (one limb set).
        val onset = IntArray(33) { s -> (s * step + if (s in 1..31) r.nextGaussian() * .0012 * Dsp.RATE else 0.0).roundToInt() }
        val tail = Breakcore.frames(.5)
        val dry = FloatArray(onset[32] + tail)
        for ((i, h) in performance.withIndex()) {
            val f = (v * h.force * (1 + .05 * r.nextGaussian())).coerceIn(0.0, 1.0)
            val strike = t * 64 + i
            val x = when (h.drum) {
                Drum.KICK -> Splinter.skin(SkinVoice.KICK, f, strike, mapOf("TUNE" to .3f, "DECAY" to .4f, "PUNCH" to .6f, "CLICK" to .55f))
                Drum.SNARE -> snare(f, strike, mapOf("TUNE" to .45f, "SNAP" to .6f, "DECAY" to .35f))
                Drum.GHOST -> snare(f, strike, mapOf("TUNE" to .45f, "SNAP" to .35f, "DECAY" to .25f))
                Drum.RIDE -> Splinter.skin(SkinVoice.RIDE, f, strike, mapOf("TONE" to .55f, "DECAY" to .45f))
                Drum.OPEN -> Splinter.skin(SkinVoice.HAT_OPEN, f, strike, mapOf("DECAY" to .35f, "TONE" to .6f))
            }
            for (j in x.indices) if (onset[h.step] + j < dry.size) dry[onset[h.step] + j] += x[j]
        }
        // One room for the whole take, then the tape it was sampled from. Both are peak-matched,
        // so a softer take stays softer; the tape drives harder when the take is hotter.
        val roomed = Room.process(Snip(dry, 1, Dsp.RATE), mapOf("SIZE" to .35f, "WALLS" to .55f, "DISTANCE" to .45f)).samples.copyOf(dry.size)
        val taped = Tape.process(Snip(roomed, 1, Dsp.RATE), mapOf("WOBBLE" to .08f, "DRIVE" to (.15 + .35 * v).toFloat(), "AGE" to .35f)).samples
        Take(taped, onset)
    }

    /** SKIN's snare reads velocity itself (wire bandwidth, level, decay); the gain matches
     * [Splinter.skin]'s, so it sits with the kick and ride. */
    private fun snare(f: Double, strike: Int, overrides: Map<String, Float>): FloatArray {
        val raw = Skin.render(SkinVoice.SNARE, Skin.defaults(SkinVoice.SNARE) + overrides, f.toFloat(), take = strike).samples
        return Breakcore.scale(raw, .65 * f.pow(1.1))
    }

    /** Slice [i]: from its cut to the next, the last one to the end of the take. */
    private fun slice(v: Double, t: Int, i: Int): FloatArray {
        val take = play(v, t)
        val from = take.onset[cuts[i]]
        val to = if (i < cuts.lastIndex) take.onset[cuts[i + 1]] else take.audio.size
        return Breakcore.fadeIn(Splinter.chop(take.audio.copyOfRange(from, min(to, take.audio.size)), to - from))
    }

    override fun renderPad(pad: Int, velocity: Double, take: Int): Snip {
        require(pad in layout.indices && velocity.isFinite() && velocity in 0.0..1.0 && take >= 0)
        val v = velocity; val t = take; val beat = Breakcore.beat(bpm)
        if (v == 0.0) return Snip(FloatArray(Breakcore.frames(.1)), 1, Dsp.RATE)
        return when (pad) {
            in 0..15 -> Breakcore.finish(slice(v, t, pad), GAINS[pad], 1.0)
            16 -> {
                // The sampler's repeat: one slice (Crack) replayed from its start, the cut halving each
                // beat, an eighth down to a sixty-fourth, louder as it goes. The last repeat rings out.
                val buffer = slice(v, t, 9)
                val parts = (0..3).flatMap { b -> List(2 shl b) { (beat / (2 shl b)).roundToInt() } }
                val out = FloatArray(parts.sum() + buffer.size); var at = 0
                for ((k, len) in parts.withIndex()) {
                    val g = .45 + .55 * k / parts.lastIndex
                    val cut = if (k == parts.lastIndex) buffer.copyOf() else Splinter.chop(buffer, len)
                    for (j in cut.indices) out[at + j] += (cut[j] * g).toFloat()
                    at += len
                }
                Breakcore.finish(out, GAINS[16], 2.2)
            }
            17 -> {
                // The break re-sequenced into a rush: every slice in order, each shorter than the
                // last by .85 and half a semitone higher, the sixteen together filling four beats.
                val r = .85; val first = 4 * beat * (1 - r) / (1 - r.pow(16))
                val out = FloatArray((4 * beat).roundToInt() + Breakcore.frames(.4)); var at = 0.0
                for (k in 0..15) {
                    val len = (first * r.pow(k)).roundToInt()
                    val s = Splinter.repitch(slice(v, t, k), k * .5)
                    val cut = if (k == 15) s else Splinter.chop(s, len)
                    for (j in cut.indices) if (at.roundToInt() + j < out.size) out[at.roundToInt() + j] += cut[j]
                    at += first * r.pow(k)
                }
                Breakcore.finish(out, GAINS[17], 2.2)
            }
            else -> error("pad")
        }
    }

    /** Fixed per-pad gain, set once from the full-force take 0 peak. */
    private val GAINS = doubleArrayOf(0.716, 1.209, 0.893, 0.794, 0.983, 1.099, 0.75, 0.912, 0.742, 0.884, 1.038, 1.032, 0.782, 0.9, 0.935, 0.722, 0.881, 0.716)
}
