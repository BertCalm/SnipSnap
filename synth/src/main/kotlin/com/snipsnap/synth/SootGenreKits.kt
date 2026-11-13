package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * SOOT's four sibling genre kits (docs/SOOT.md): the candidates the owner chose to build on 2026-11-13,
 * each an authored sixteen-pad kit from the SOOT engine, with its own tempo, tuning, pad roles, groove
 * and processing. They are not SOOT at other settings, and SOOT's own kit ([SootKits]) is untouched.
 *
 * - [Genre.DRUMLESS], 80 BPM: no kick. Rods, cups and plates carry the groove over muted snares.
 * - [Genre.DUB], 72 BPM: a one-drop. A heavy kick and a high rimshot thrown deep into the coil.
 * - [Genre.NOIR], 76 BPM: soft and close. A damp kick, brush-weight snares, pure hats, a light
 *   dictaphone.
 * - [Genre.INDUSTRIAL], 96 BPM: straight and hard. A driven kick, bench snares, struck plates, the
 *   dictaphone pushed.
 *
 * Every pad is a grid of three velocity zones and four takes, as SOOT's:
 * - **A01 to A14:** single hits, each a SOOT voice with this genre's settings, struck by [Soot].
 * - **A15:** a roll of the genre's lead strike into one coil.
 * - **A16:** four bars through one shared coil and one keyed dictaphone, wrapped to loop.
 *
 * The four bars are built from exactly the parts and groove of the sketch the owner chose. Take 0 at
 * full velocity reproduces the sketch: the sketch generator renders through [phrase].
 *
 * Bump [VERSION] when any pad's audio changes; saved kits keep the version they were printed with.
 */
object SootGenreKits {
    const val VERSION = 1
    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16
    const val HITS = 14

    /**
     * A hit pad: its name, the voice and settings it plays, its choke group, and how the four bars
     * hear it (its level in the mix, its send into the shared coil).
     */
    class Pad internal constructor(
        val name: String,
        val voice: SootVoice,
        val macros: Map<String, Float>,
        val mute: Int = 0,
        val gain: Double = 0.0,
        val send: Double = 0.0,
        /** The pad's own dictaphone, or null for the genre's. */
        val grime: Float? = null,
    )

    /** One groove note: its sixteenth step in the four bars, the pad (A01 is 0), its weight. */
    class Note internal constructor(val step: Int, val pad: Int, val weight: Float)

    enum class Genre(
        val id: String, val title: String, val bpm: Int,
        /** The dictaphone: a Double, as the sketch the owner heard used (.6f and .9f are not .6 and .9). */
        val grime: Double, val wet: Double, val swing: Double, val hatDrag: Double,
        /** The pad the roll strikes, and how deep its coil is. */
        val rollPad: Int, val rollCoil: Double,
    ) {
        DRUMLESS("soot-drumless", "Drumless", 80, grime = .6, wet = .45, swing = .32, hatDrag = .012, rollPad = 0, rollCoil = .55),
        DUB("soot-dub", "Basement Dub", 72, grime = .5, wet = .7, swing = .2, hatDrag = .008, rollPad = 1, rollCoil = .9),
        NOIR("soot-noir", "Brushed Noir", 76, grime = .25, wet = .25, swing = .36, hatDrag = .016, rollPad = 1, rollCoil = .3),
        INDUSTRIAL("soot-industrial", "Warehouse Industrial", 96, grime = .9, wet = .4, swing = 0.0, hatDrag = 0.0, rollPad = 1, rollCoil = .5),
    }

    private fun p(name: String, voice: SootVoice, vararg m: Pair<String, Float>, mute: Int = 0, gain: Double = 0.0, send: Double = 0.0) =
        Pad(name, voice, Soot.defaults(voice) + m.filter { it.first != "GRIME" }, mute, gain, send, m.firstOrNull { it.first == "GRIME" }?.second)

    /** The fourteen hits, A01 first. The first few carry the sketch's parts exactly, with its levels and sends. */
    fun pads(g: Genre): List<Pad> = when (g) {
        Genre.DRUMLESS -> listOf(
            p("Lead Rod", SootVoice.ROD, "TUNE" to .45f, "DECAY" to .4f, gain = .55, send = .35),
            p("Muted Snare", SootVoice.SNARE, "COIL" to .25f, "EDGE" to .5f, "TUNE" to .4f, "BENCH" to .2f, gain = .55, send = .2),
            p("Closed Hat", SootVoice.HAT_CLOSED, "DETUNE" to .225f, mute = 1, gain = .22),
            p("Open Hat", SootVoice.HAT_OPEN, "DETUNE" to .225f, mute = 1, gain = .25),
            p("High Rod", SootVoice.ROD, "TUNE" to .8f, "DECAY" to .25f),
            p("Low Rod", SootVoice.ROD, "TUNE" to .1f, "DECAY" to .7f),
            p("Cup", SootVoice.CUP, "TUNE" to .62f, gain = .35, send = .3),
            p("High Cup", SootVoice.CUP, "TUNE" to .95f, "DECAY" to .3f),
            p("Low Cup", SootVoice.CUP, "TUNE" to .25f, "DECAY" to .7f),
            p("Plate", SootVoice.PLATE, "DECAY" to .6f, "TUNE" to .4f, gain = .4, send = .4),
            p("Dark Plate", SootVoice.PLATE, "TUNE" to .1f, "DECAY" to .9f, "COIL" to .6f),
            p("Sprung Snare", SootVoice.SNARE, "TUNE" to .1f, "COIL" to .95f, "EDGE" to 0f, "BENCH" to 0f),
            p("Coil", SootVoice.COIL, "TUNE" to .4f, gain = .45),
            p("Bench Tap", SootVoice.SNARE, "BENCH" to 1f, "TUNE" to 1f, "COIL" to 0f, "EDGE" to 0f, "GRIME" to .2f),
        )
        Genre.DUB -> listOf(
            p("Drop Kick", SootVoice.KICK, "SUB" to .9f, "TUNE" to .38f, "SAG" to .5f, gain = 1.0, send = .5),
            p("Thrown Rim", SootVoice.SNARE, "BENCH" to 1f, "TUNE" to .85f, "COIL" to .8f, "EDGE" to .3f, gain = .7, send = .85),
            p("Closed Hat", SootVoice.HAT_CLOSED, "DETUNE" to .4f, "DECAY" to .7f, mute = 1, gain = .25),
            p("Open Hat", SootVoice.HAT_OPEN, "DETUNE" to .4f, "DECAY" to .7f, mute = 1),
            p("Deep Kick", SootVoice.KICK, "SUB" to 1f, "TUNE" to .2f, "SAG" to .8f, "COIL" to .8f),
            p("Dry Rim", SootVoice.SNARE, "BENCH" to 1f, "TUNE" to .85f, "COIL" to 0f, "EDGE" to .3f, "GRIME" to .2f),
            p("Thrown Snare", SootVoice.SNARE, "COIL" to 1f, "TUNE" to .35f, "EDGE" to .4f, "BENCH" to 0f),
            p("Low Snare", SootVoice.SNARE, "TUNE" to .05f, "COIL" to .5f, "EDGE" to .8f, "BENCH" to 0f, "GRIME" to .8f),
            p("Plate", SootVoice.PLATE, "DECAY" to .8f, gain = .3, send = .9),
            p("Bright Plate", SootVoice.PLATE, "TUNE" to .9f, "DECAY" to .5f, "COIL" to .8f),
            p("Coil", SootVoice.COIL, "COIL" to 1f, "TUNE" to .3f, gain = .5),
            p("Low Coil", SootVoice.COIL, "COIL" to 1f, "TUNE" to 0f),
            p("Rod in the Spring", SootVoice.ROD, "TUNE" to .3f, "DECAY" to .8f, "COIL" to .9f),
            p("Cup in the Spring", SootVoice.CUP, "TUNE" to .4f, "COIL" to .8f),
        )
        Genre.NOIR -> listOf(
            p("Damp Kick", SootVoice.KICK, "SAG" to .15f, "SUB" to .25f, "TUNE" to .45f, "COIL" to .2f, gain = .8, send = .2),
            p("Brush Snare", SootVoice.SNARE, "COIL" to .3f, "EDGE" to 0f, "BENCH" to 0f, "TUNE" to .35f, gain = .7, send = .15),
            p("Pure Hat", SootVoice.HAT_CLOSED, "DETUNE" to 0f, "DECAY" to .3f, mute = 1, gain = .25),
            p("Pure Open Hat", SootVoice.HAT_OPEN, "DETUNE" to 0f, "DECAY" to .4f, mute = 1),
            p("Knock", SootVoice.KICK, "SAG" to .3f, "SUB" to 0f, "TUNE" to .95f, "COIL" to .6f, "GRIME" to .6f),
            p("Dark Brush", SootVoice.SNARE, "TUNE" to .1f, "COIL" to .1f, "EDGE" to 0f, "BENCH" to 0f),
            p("Rim", SootVoice.SNARE, "BENCH" to .9f, "TUNE" to .75f, "COIL" to 0f, "EDGE" to 0f),
            p("Soft Cup", SootVoice.CUP, "DECAY" to .7f, "TUNE" to .4f, gain = .3, send = .2),
            p("Low Cup", SootVoice.CUP, "TUNE" to .1f, "DECAY" to .9f),
            p("Soft Rod", SootVoice.ROD, "TUNE" to .35f, "DECAY" to .5f),
            p("Shimmer Hat", SootVoice.HAT_CLOSED, "DETUNE" to 1f, "DECAY" to .75f, "TUNE" to .9f, mute = 3),
            p("Plate Swell", SootVoice.PLATE, "TUNE" to .3f, "DECAY" to 1f, "COIL" to .5f),
            p("Coil", SootVoice.COIL, "TUNE" to .2f, "COIL" to .5f),
            p("Sprung Snare", SootVoice.SNARE, "COIL" to .95f, "TUNE" to .5f, "EDGE" to .6f, "BENCH" to .2f),
        )
        Genre.INDUSTRIAL -> listOf(
            p("Driven Kick", SootVoice.KICK, "SAG" to 1f, "SUB" to .15f, "TUNE" to .55f, "COIL" to .7f, gain = 1.0, send = .5),
            p("Bench Snare", SootVoice.SNARE, "BENCH" to .9f, "EDGE" to 1f, "TUNE" to .6f, gain = .85, send = .3),
            p("Wide Hat", SootVoice.HAT_CLOSED, "DETUNE" to 1f, "DECAY" to .35f, mute = 1, gain = .25),
            p("Wide Open Hat", SootVoice.HAT_OPEN, "DETUNE" to 1f, "DECAY" to .5f, mute = 1),
            p("Low Kick", SootVoice.KICK, "SAG" to .6f, "SUB" to .5f, "TUNE" to .2f, "COIL" to .2f),
            p("Clang Snare", SootVoice.SNARE, "BENCH" to 1f, "TUNE" to 1f, "EDGE" to 1f, "COIL" to .5f),
            p("Struck Plate", SootVoice.PLATE, "TUNE" to .55f, "DECAY" to .45f, gain = .5, send = .35),
            p("Low Plate", SootVoice.PLATE, "TUNE" to .1f, "DECAY" to .8f, "COIL" to .6f),
            p("Rod", SootVoice.ROD, "TUNE" to .6f, "DECAY" to .3f, gain = .35, send = .3),
            p("High Rod", SootVoice.ROD, "TUNE" to 1f, "DECAY" to .2f),
            p("Cup", SootVoice.CUP, "TUNE" to .8f),
            p("Coil", SootVoice.COIL, "TUNE" to .7f, gain = .5),
            p("Low Coil", SootVoice.COIL, "TUNE" to .2f, "COIL" to 1f),
            p("Dry Snare", SootVoice.SNARE, "COIL" to 0f, "EDGE" to .6f, "TUNE" to .35f, "BENCH" to 0f, "GRIME" to .4f),
        )
    }

    private fun bars(vararg perBar: (Int) -> List<Note>): List<Note> = (0..3).flatMap { bar -> perBar.flatMap { it(bar) } }
    private fun at(bar: Int, pad: Int, weight: Float, vararg steps: Int) = steps.map { Note(bar * 16 + it, pad, weight) }

    /** The four bars' groove: the sketch the owner chose, pad for pad. */
    fun notes(g: Genre): List<Note> = when (g) {
        Genre.DRUMLESS -> bars(
            { b -> at(b, 0, .8f, 0, 10) + at(b, 0, .55f, 3, 6, 13) },
            { b -> at(b, 6, .5f, 2, 6, 10, 14) },
            { b -> if (b % 2 == 0) at(b, 9, .6f, 0) else emptyList() },
            { b -> at(b, 1, .45f, 4, 12) },
            { b -> at(b, 2, .4f, 1, 5, 9, 15) },
            { b -> if (b == 3) at(b, 12, .9f, 14) else emptyList() },
        )
        Genre.DUB -> bars(
            { b -> at(b, 0, 1f, 8) },
            { b -> at(b, 1, .9f, 8) + (if (b == 1 || b == 3) at(b, 1, .5f, 14) else emptyList()) },
            { b -> at(b, 2, .5f, 2, 6, 10, 14) },
            { b -> if (b == 1) at(b, 8, .6f, 15) else emptyList() },
            { b -> if (b == 3) at(b, 10, 1f, 12) else emptyList() },
        )
        Genre.NOIR -> bars(
            { b -> at(b, 0, .55f, 0) + at(b, 0, .4f, 10) },
            { b -> at(b, 1, .4f, 4, 12) + at(b, 1, .14f, 1, 3, 7, 9, 11, 15) },
            { b -> at(b, 2, .35f, 0, 4, 8, 12) },
            { b -> if (b % 2 == 1) at(b, 7, .4f, 14) else emptyList() },
        )
        Genre.INDUSTRIAL -> bars(
            { b -> at(b, 0, 1f, 0, 8) + at(b, 0, .8f, 6, 10) },
            { b -> at(b, 1, 1f, 4, 12) + (if (b == 3) at(b, 1, .6f, 13, 14, 15) else emptyList()) },
            { b -> at(b, 6, .8f, 2) + at(b, 6, .6f, 14) },
            { b -> at(b, 8, .6f, 7, 11) },
            { b -> (0 until 16).flatMap { s -> at(b, 2, if (s % 4 == 0) .7f else .4f, s) } },
            { b -> if (b == 1) at(b, 11, .9f, 15) else emptyList() },
        )
    }

    fun names(g: Genre): List<String> = pads(g).map { it.name } + listOf("${pads(g)[g.rollPad].name} Roll", "Four Bars")

    private fun classOf(g: Genre, role: Int): DrumClass = when (role) {
        in 0 until HITS -> pads(g)[role].voice.drumClass
        HITS -> DrumClass.PERC // the roll rings past 1.5 s; declared, never classified
        else -> DrumClass.LOOP
    }

    /** A hit pad's settings, with the genre's dictaphone unless the pad sets its own. */
    internal fun macrosOf(g: Genre, pad: Pad): Map<String, Float> =
        Soot.settled(pad.voice, pad.macros + ("GRIME" to (pad.grime ?: g.grime.toFloat())))

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(g: Genre, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return when (role) {
            in 0 until HITS -> pads(g)[role].let { Soot.render(it.voice, macrosOf(g, it), velocity, take) }
            HITS -> roll(g, velocity, take)
            else -> phrase(g, velocity, take)
        }
    }

    /** One kit's sixteen pads, each with its full grid, rendered in parallel. */
    fun build(g: Genre): List<ArrangedPad> {
        val cells = (0 until PADS).flatMap { role -> velocities.indices.flatMap { z -> (0 until TAKES).map { t -> Triple(role, z, t) } } }
        val rendered = cells.parallelStream().map { (role, z, t) -> Triple(role, z, t) to render(g, role, velocities[z], t) }
            .collect(java.util.stream.Collectors.toList()).toMap()
        val names = names(g)
        val pads = pads(g)
        return List(PADS) { role ->
            val grid = velocities.indices.map { z -> List(TAKES) { t -> rendered.getValue(Triple(role, z, t)) } }
            ArrangedPad(
                grid.last().first(), classOf(g, role), displayName = names[role], velocityTakes = grid,
                muteGroup = if (role < HITS) pads[role].mute else 2,
                source = mapOf(
                    "engine" to "SOOT", "kit" to g.id, "kitVersion" to VERSION.toString(), "model" to Soot.MODEL_VERSION.toString(),
                    "role" to role.toString(), "bpm" to g.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to (if (role == HITS) "roll" else if (role == HITS + 1) "phrase" else "finite"),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ A15, the roll

    const val ROLL_BUILD = 2.0
    private const val ROLL_TAIL = 1.4
    private const val ROLL_STRIKES = 16

    /**
     * Sixteen fresh strikes of the genre's lead pad, speeding up and rising (SOOT's coil roll), all
     * into one coil as deep as the genre's [Genre.rollCoil], then the pad's print and a fader push over
     * the build (the dictaphone evens a crescendo out; BREAKER's riser, as the owner approved it).
     */
    fun roll(g: Genre, velocity: Float, take: Int): Snip {
        val rate = Dsp.RATE
        val pad = pads(g)[g.rollPad]
        val m = macrosOf(g, pad)
        val frames = ((ROLL_BUILD + ROLL_TAIL) * rate).toInt()
        val dry = DoubleArray(frames)
        for (i in 0 until ROLL_STRIKES) {
            val p = i / (ROLL_STRIKES - 1.0)
            val at = (ROLL_BUILD * .92 * (1 - (1 - p).pow(1.6)) * rate).toInt()
            val hit = Soot.dry(pad.voice, m, velocity * (.3f + .7f * p.toFloat()), (take * ROLL_STRIKES + i) * 7 + 1000)
            val gain = .35 + .65 * p
            for (k in hit.indices) if (at + k < frames) dry[at + k] += gain * hit[k]
        }
        val coiled = BreakerChain.tankMix(dry, g.rollCoil, frames, rate, Soot.COIL_TANK)
        val printed = Soot.print(pad.voice, m, Soot.centre(coiled))
        for (i in printed.indices) {
            val q = minOf(1.0, i / (ROLL_BUILD * rate))
            printed[i] *= 10.0.pow((-12 + 12 * q.pow(1.5)) / 20)
        }
        return Soot.output(printed, cap = frames)
    }

    // ------------------------------------------------------------------ A16, four bars

    /** The four bars' exact length at the genre's tempo. */
    fun phraseFrames(g: Genre): Int = (16 * 60.0 / g.bpm * Dsp.RATE).toInt()

    /**
     * Four bars through one bus (SOOT's A16): every hit dry, the genre's swing and hat drag, the sends
     * and the kick (opened down to 90 Hz) into one coil at the genre's [Genre.wet], then the dictaphone
     * at the genre's [Genre.grime], keyed by the kick. Wrapped onto its start to loop at tempo. The pad's
     * velocity scales every strike; a take re-strikes every note and re-draws the timing.
     */
    fun phrase(g: Genre, velocity: Float, take: Int): Snip {
        val rate = Dsp.RATE
        val pads = pads(g)
        val step = 60.0 / g.bpm / 4
        val loop = phraseFrames(g)
        val total = loop + (1.5 * rate).toInt()
        val human = Random(if (take == 0) Dsp.seedFor("soot-genre", g.id.removePrefix("soot-")) else Dsp.seedFor("soot-genre", g.id.removePrefix("soot-"), take))
        val touch = .35f + .65f * velocity.coerceIn(0f, 1f)
        val mix = DoubleArray(total); val kick = DoubleArray(total); val send = DoubleArray(total)
        val counts = IntArray(HITS)
        for (n in notes(g).sortedBy { it.step }) {
            val pad = pads[n.pad]
            var t = n.step * step
            if (n.step % 2 == 1) t += g.swing * step
            t += if (pad.voice == SootVoice.HAT_CLOSED) g.hatDrag else 0.0
            t += (human.nextDouble() * 2 - 1) * .002
            val strike = take * TAKES * 64 + counts[n.pad]++
            val x = Soot.dry(pad.voice, Soot.settled(pad.voice, pad.macros), n.weight * touch, strike)
            val at = Math.floorMod((t * rate).toInt(), loop)
            for (k in x.indices) {
                val i = at + k
                if (i >= total) break
                val v = pad.gain * n.weight * touch * x[k]
                mix[i] += v
                if (pad.voice == SootVoice.KICK) kick[i] += v else send[i] += pad.send * v
            }
        }
        val kickSend = Soot.highpass(kick, 90.0)
        for (i in send.indices) send[i] += .45 * kickSend[i]
        val ret = Soot.lowpass(BreakerChain.spring(send, .5, total, rate, Soot.COIL_TANK, sendHp = 60.0), Soot.COIL_TANK.tone)
        val pm = mix.maxOf { abs(it) }; val pr = ret.maxOf { abs(it) }
        if (pr > 0) for (i in mix.indices) mix[i] += g.wet * pm / pr * ret[i]
        val key = DoubleArray(total) { kick[it] + .3 * mix[it] }
        val bus = Soot.dictaphone(mix, g.grime, key)
        val out = FloatArray(loop)
        for (i in bus.indices) out[i % loop] += bus[i].toFloat()
        val mean = out.average().toFloat()
        for (i in out.indices) out[i] -= mean
        Dsp.levelTo(out, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        return Snip(out, 1, rate)
    }
}
