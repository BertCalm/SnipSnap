package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** SPLINTER: a breakcore kit whose instrument is the edit, not the drum.
 *
 * The characteristic gesture is: sped-up break hit -> hard chop -> retrigger that
 * shrinks into a pitch -> clipped kick lands.
 *
 * The drums are the owner-approved Spore kick and snare with SKIN, TINES and the Spore
 * ride. What is new is the stage after them, four things a sampler does to a break:
 *  - SPEED: a break played at [PLAYED_BPM] is run at [BPM] the way a sampler does it, by
 *    reading faster. Pitch rises with tempo ([SPEED_SEMITONES]); nothing is time-corrected.
 *  - CHOP: phrases are cut on the grid with a 1.5 ms fade, tail or no tail.
 *  - RETRIGGER: one voice. A new trigger cuts the hit before it, so a fast enough roll
 *    stops being a rhythm and becomes a tone at the retrigger rate.
 *  - CLIP and STRETCH: an oversampled clipper, and the cyclic stretch of an early
 *    sampler (fixed grains replayed), whose grain rate is audible on purpose.
 *
 * A source grid, not an engine: no Patch, no picker entry. Saved [Recipe]s rerender it.
 * Every constant is a shape awaiting the owner's listen.
 */
object Splinter {
    const val VERSION = 1
    const val ID = "splinter-breakcore"
    const val TITLE = "Splinter Breakcore"
    const val BPM = 200
    const val PLAYED_BPM = 137
    /** Tape-speed pitch rise from running a [PLAYED_BPM] performance at [BPM]: about +6.55. */
    val SPEED_SEMITONES: Double = 12 * ln(BPM.toDouble() / PLAYED_BPM) / ln(2.0)

    data class Part(val name: String, val kind: DrumClass, val level: Float, val muteGroup: Int = 0)

    val velocities: List<Double> get() = listOf(.3, .65, 1.0)

    val layout: List<Part> = listOf(
        Part("Clip Kick", DrumClass.KICK, 1f),
        Part("Gabber Kick", DrumClass.KICK, .9f),
        Part("Crack Snare", DrumClass.SNARE, .9f),
        Part("Ghost Snare", DrumClass.SNARE, .5f),
        Part("Ping Rim", DrumClass.SNARE, .6f),
        Part("Stretch Snare", DrumClass.PERC, .6f),
        Part("Tight Hat", DrumClass.HAT_CLOSED, .4f, 1),
        Part("Open Hat", DrumClass.HAT_OPEN, .4f, 1),
        Part("Speed Ride", DrumClass.PERC, .4f),
        Part("Crush Tom", DrumClass.TOM, .65f),
        Part("Slice One", DrumClass.PERC, .8f),
        Part("Slice Two", DrumClass.PERC, .8f),
        Part("Bounce Stutter", DrumClass.PERC, .7f),
        Part("Bit Zap", DrumClass.PERC, .45f),
        Part("Suck Back", DrumClass.PERC, .55f),
        Part("Half Drop", DrumClass.PERC, .85f),
        Part("Snare Rush", DrumClass.LOOP, .65f),
        Part("Mash Riser", DrumClass.LOOP, .6f),
    )

    data class Recipe(val pad: Int, val velocity: Double = 1.0, val take: Int = 0, val version: Int = VERSION) {
        init { require(pad in layout.indices && take >= 0 && velocity.isFinite() && velocity in 0.0..1.0 && version == VERSION) }
        fun render(): Snip = renderPad(pad, velocity, take)
        fun toJsonText(): String = Json.write(JsonValue.Obj(mapOf(
            "source" to JsonValue.Str("Splinter"), "version" to JsonValue.Num(version.toDouble()),
            "pad" to JsonValue.Num(pad.toDouble()), "velocity" to JsonValue.Num(velocity), "take" to JsonValue.Num(take.toDouble()))))
        companion object {
            fun fromJsonText(s: String): Recipe {
                val m = Json.parse(s).obj(); require(m.getValue("source").str() == "Splinter")
                fun number(k: String) = (m.getValue(k) as JsonValue.Num).value
                fun integer(k: String): Int { val n = number(k); require(n == n.toInt().toDouble()); return n.toInt() }
                return Recipe(integer("pad"), number("velocity"), integer("take"), integer("version"))
            }
        }
    }

    // ---- The edit stage. Each step maps silence to silence, so force 0 stays silent. ----

    /** SPEED: read the buffer faster or slower. Length and pitch move together, as on tape. */
    internal fun repitch(x: FloatArray, semitones: Double): FloatArray =
        Resampler.resample(Snip(x, 1, Dsp.RATE), (Dsp.RATE / 2.0.pow(semitones / 12)).roundToInt()).samples

    /** CLIP: a near-hard clipper run at 4x, so the corners it makes fold away above the
     * band instead of back into it. Symmetric, so it adds odd harmonics and no offset.
     * The band limit rings on the flat tops: output can reach 1.32 (measured, `SplinterTest`),
     * so every clipped pad carries a gain under 1 / 1.32. */
    internal fun clip(x: FloatArray, drive: Double): FloatArray {
        val up = Valve.upsample(x, Dsp.RATE)
        for (i in up.indices) { val a = up[i] * drive; up[i] = (a / (1 + a.pow(6)).pow(1 / 6.0)).toFloat() }
        Tide.bandLimit(up, Dsp.RATE * Dsp.OVERSAMPLE)
        return Dsp.decimate(up, Dsp.RATE)
    }

    /** STRETCH: fixed grains replayed in place, each faded at its edges. The grain rate
     * (1000 / [grainMs] Hz) is heard as a buzz; that buzz is the effect. */
    internal fun stretch(x: FloatArray, factor: Double, grainMs: Double): FloatArray {
        val g = (grainMs / 1000 * Dsp.RATE).roundToInt(); val edge = g / 8.0
        return FloatArray((x.size * factor).roundToInt()) { i ->
            val j = i % g; val src = (i / g * g / factor).roundToInt() + j
            if (src < x.size) (x[src] * min(1.0, min(j, g - 1 - j) / edge)).toFloat() else 0f
        }
    }

    /** CHOP: keep [frames], ending in a 1.5 ms fade. Shorter input is returned padded. */
    internal fun chop(x: FloatArray, frames: Int): FloatArray {
        val out = x.copyOf(frames); Dsp.fadeTail(out, 1.5f); return out
    }

    /** A hit at a time with its force and the snare/kick TUNE it is struck at. */
    data class Event(val beat: Double, val force: Double, val tune: Double)

    /** RETRIGGER on one voice: each hit is cut where the next begins. [bpm] is the tempo
     * the events are placed at; [tail] frames ring after the last one. */
    private fun retrigger(events: List<Event>, bpm: Int, tail: Int, hit: (Int, Event) -> FloatArray): FloatArray {
        fun at(beat: Double) = (beat * 60 / bpm * Dsp.RATE).roundToInt()
        val out = FloatArray(at(events.last().beat) + tail)
        for ((i, e) in events.withIndex()) {
            val start = at(e.beat)
            val room = if (i < events.lastIndex) at(events[i + 1].beat) - start else tail
            val h = chop(hit(i, e), room)
            for (j in h.indices) out[start + j] += h[j]
        }
        return out
    }

    /** The snare rush: four beats, the gap halving each beat (16ths, 32nds, 64ths, 128ths)
     * while force and TUNE climb. At 200 BPM the last beat retriggers at 107 Hz, so the
     * rhythm has become a rising buzz by the time it lands.
     *
     * This is the kit's signature and the most personal curve in it: where the roll
     * turns from rhythm into tone, and how hard it pushes on the way. */
    fun rushEvents(): List<Event> {
        val beats = mutableListOf<Double>()
        for (beat in 0..3) { val n = 4 shl beat; for (i in 0 until n) beats += beat + i.toDouble() / n }
        return beats.map { b -> Event(b, .35 + .65 * (b / 4).pow(1.5), .35 + .6 * b / 4) }
    }

    /** The riser: kicks from 8ths to 64ths, low to high, into the clipper. */
    fun riserEvents(): List<Event> {
        val beats = (0..3).map { it * .5 } + (0..3).map { 2 + it * .25 } + (0..3).map { 3 + it * .125 } + (0..7).map { 3.5 + it * .0625 }
        return beats.map { b -> Event(b, .35 + .65 * b / 4, .15 + .8 * b / 4) }
    }

    // ---- Sources: the approved Spore pair, SKIN, TINES and the Spore ride. ----

    private fun kick(v: Double, t: Int, tune: Double = .3, strike: Double = .7, body: Double = .55, click: Double = .6) =
        SporeKick.render(SporeKick.Controls(tune, strike, body, click), v, t).samples

    private fun snare(v: Double, t: Int, tune: Double = .55, strike: Double = .7, body: Double = .45, wires: Double = .6) =
        SporeSnare.render(SporeSnare.Controls(tune, strike, body, wires), v, t).samples

    private fun skin(voice: SkinVoice, v: Double, t: Int, overrides: Map<String, Float>): FloatArray {
        val m = (Skin.defaults(voice) + overrides).toMutableMap()
        // A softer hit is darker and, for cymbals, shorter; each take nudges the tone.
        val tone = listOf("TONE", "CLICK", "BODY").first { it in m }
        m[tone] = (m.getValue(tone) - .25f * (1 - v.toFloat()) + listOf(0f, -.02f, .02f)[t % 3]).coerceIn(0f, 1f)
        if (voice !in Skin.STRUCK) m["DECAY"] = (m.getValue("DECAY") * (.65f + .35f * v.toFloat())).coerceIn(0f, 1f)
        val raw = Skin.render(voice, m, v.toFloat(), take = t, reseedUnstruck = true).samples
        val gain = (.65 * v.pow(1.1)).toFloat()
        return FloatArray(raw.size) { raw[it] * gain }
    }

    private fun mix(length: Int, vararg parts: Pair<Int, FloatArray>): FloatArray {
        val out = FloatArray(length)
        for ((at, p) in parts) for (j in p.indices) if (at + j < length) out[at + j] += p[j]
        return out
    }

    /** Mean removal, a 20 Hz high-pass, a fixed gain and the tail fade. No per-hit levelling:
     * a softer strike stays softer. */
    private fun finish(x: FloatArray, gain: Double, seconds: Double = 1.35): Snip {
        val out = x.copyOf(min(x.size, (seconds * Dsp.RATE).roundToInt()))
        val mean = out.average().toFloat(); val dc = Dsp.FixedOnePole(20f)
        for (i in out.indices) { val s = out[i] - mean; out[i] = ((s - dc.lp(s)) * gain).toFloat() }
        Dsp.fadeTail(out)
        check(out.all { it.isFinite() } && out.maxOf { abs(it) } < 1f) { "Splinter pad out of range" }
        return Snip(out, 1, Dsp.RATE)
    }

    /** One beat of the break as played, at [PLAYED_BPM]; SPEED then lands it on one beat at [BPM]. */
    private fun slice(v: Double, t: Int, second: Boolean): FloatArray {
        val sixteenth = (60.0 / PLAYED_BPM / 4 * Dsp.RATE).roundToInt()
        val hat = { n: Int, f: Double -> skin(SkinVoice.HAT_CLOSED, v * f, t * 4 + n, mapOf("DECAY" to .05f, "TONE" to .6f)) }
        val played = if (!second) mix(sixteenth * 4,
            0 to kick(v, t), 0 to hat(0, .8), sixteenth * 2 to hat(1, .6),
            sixteenth * 3 to snare(v * .4, t, strike = .2, body = .25, wires = .35))
        else mix(sixteenth * 4,
            0 to snare(v, t), 0 to hat(0, .7), sixteenth to snare(v * .3, t + 3, strike = .2, body = .25, wires = .35),
            sixteenth * 2 to hat(1, .8), sixteenth * 3 to kick(v * .7, t + 3))
        return repitch(played, SPEED_SEMITONES)
    }

    fun renderPad(pad: Int, velocity: Double = 1.0, take: Int = 0): Snip {
        require(pad in layout.indices && velocity.isFinite() && velocity in 0.0..1.0 && take >= 0)
        val v = velocity; val t = take
        // A softer strike reaches the clipper lower, so it clips less: touch changes the
        // edge, not only the level. The v.pow after it restores the dynamics clipping flattens.
        fun driven(x: FloatArray, drive: Double) = clip(x, drive).also { for (i in it.indices) it[i] *= v.pow(.5).toFloat() }
        val beat = (60.0 / BPM * Dsp.RATE).roundToInt()
        return when (pad) {
            0 -> finish(driven(repitch(kick(v, t), 3.0), 3.0), .8)
            1 -> finish(driven(kick(v, t, tune = .45, strike = .6, body = 1.0, click = .4), 12.0), .6)
            2 -> finish(driven(repitch(snare(v, t), SPEED_SEMITONES), 1.6), 1.0)
            3 -> finish(repitch(snare(v * .5, t, strike = .15, body = .2, wires = .3), SPEED_SEMITONES), 1.3)
            4 -> finish(repitch(snare(v, t, tune = .8, strike = .85, body = .25, wires = .2), SPEED_SEMITONES + 2), .9)
            5 -> finish(stretch(repitch(snare(v, t), SPEED_SEMITONES), 3.0, 11.0), .9, 1.0)
            6 -> finish(skin(SkinVoice.HAT_CLOSED, v, t, mapOf("DECAY" to .03f, "TONE" to .72f, "RING" to .2f)), 1.0)
            7 -> finish(skin(SkinVoice.HAT_OPEN, v, t, mapOf("DECAY" to .14f, "TONE" to .7f, "RING" to .25f)), 1.0)
            8 -> finish(repitch(SporeRide.render(v, t).samples, SPEED_SEMITONES), .9, .6)
            9 -> finish(Crunch.process(Snip(driven(skin(SkinVoice.TOM, v, t, mapOf("TUNE" to .35f, "DECAY" to .25f, "PUNCH" to .3f)), 2.5), 1, Dsp.RATE),
                mapOf("BITS" to .75f, "RATE" to .55f, "TONE" to .7f, "GRIT" to .2f)).samples, .8)
            10, 11 -> finish(chop(driven(slice(v, t, pad == 11), 2.2), beat), .75)
            12 -> {
                // One buffer replayed from its start, the cut halving: half, quarter, eighth beats,
                // each repeat read a semitone faster. Machine repeats, on purpose.
                val buffer = slice(v, t, true)
                val cuts = List(2) { .5 } + List(4) { .25 } + List(8) { .125 }
                val parts = cuts.mapIndexed { i, c -> chop(repitch(buffer, i.toDouble()), (c * beat).roundToInt()) }
                val out = FloatArray(parts.sumOf { it.size }); var at = 0
                for (p in parts) { p.copyInto(out, at); at += p.size }
                finish(driven(out, 2.2), .75)
            }
            13 -> {
                val m = Tines.defaults(TinesVoice.ZAP) + mapOf("DECAY" to .15f, "DROP" to (.7f + listOf(0f, -.04f, .04f)[t % 3]), "TUNE" to .7f)
                val zap = Velocity.atVelocity(TinesPatch("Splinter Zap", TinesVoice.ZAP, m), v.toFloat())
                val gain = (.6 * v.pow(1.1)).toFloat()
                finish(Crunch.process(zap.copy(samples = FloatArray(zap.samples.size) { zap.samples[it] * gain }),
                    mapOf("BITS" to .9f, "RATE" to .85f, "TONE" to .8f, "GRIT" to .3f)).samples, 1.0, .5)
            }
            14 -> finish(stretch(repitch(snare(v, t, wires = .8), SPEED_SEMITONES), 2.0, 7.0).also { it.reverse() }, .9, .75)
            15 -> finish(driven(repitch(mix(SporeKick.FRAMES, 0 to kick(v, t, body = .8), 0 to snare(v, t, tune = .4, body = .6)), SPEED_SEMITONES - 12), 2.6), .7)
            16 -> finish(driven(repitch(retrigger(rushEvents(), PLAYED_BPM, SporeSnare.FRAMES) { i, e ->
                snare(v * e.force, t * 64 + i, tune = e.tune, body = .35, wires = .65) }, SPEED_SEMITONES), 1.8), .75, 2.0)
            17 -> finish(driven(repitch(retrigger(riserEvents(), PLAYED_BPM, SporeKick.FRAMES) { i, e ->
                kick(v * e.force, t * 64 + i, tune = e.tune, body = .6, click = .3 + .5 * e.force) }, SPEED_SEMITONES), 5.0), .68, 2.0)
            else -> error("pad")
        }
    }

    fun arranged(): List<ArrangedPad> = layout.mapIndexed { i, p ->
        val grid = velocities.map { v -> (0..2).map { t -> Recipe(i, v, t).render() } }
        ArrangedPad(grid[2][0], p.kind, level = p.level, oneShot = true, displayName = p.name, muteGroup = p.muteGroup, velocityTakes = grid,
            source = mapOf("sourceBank" to "Splinter/v$VERSION", "sourceRecipe" to Recipe(i).toJsonText(),
                "velocities" to ".3,.65,1.0", "takes" to "3", "kickModelSha256" to SporeKick.MODEL_SHA256,
                "snareModelSha256" to SporeSnare.MODEL_SHA256, "status" to "approved kick/snare sources; edits and arrangement awaiting listen"))
    }
}
