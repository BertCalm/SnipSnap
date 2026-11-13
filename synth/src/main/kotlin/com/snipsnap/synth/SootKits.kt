package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * The SOOT kit (docs/SOOT.md): sixteen pads in the layout the owner approved on 2026-11-12, every pad a
 * grid of three velocity zones and four takes.
 *
 * - **A01 to A14:** the approved hits, each a [SootPresets] patch, struck at the zone's velocity and
 *   re-struck per take by [Soot] itself (velocity moves each drum's contact law; a take is fresh noise,
 *   a moved strike position, fresh metal phases).
 * - **A15, the coil roll** ([roll]): sixteen fresh snare strikes speeding up into one coil, so the
 *   spring fills as the roll builds. The percussion gate's riser.
 * - **A16, four bars** ([phrase]): the round-0 groove through one shared coil the kick drives and one
 *   dictaphone the kick pumps. In a sampler each pad plays alone, so this is the only pad where the
 *   whole kit breathes through one spring. It is exactly four bars at [BPM], its tail wrapped into
 *   its start, so it loops without a seam.
 *
 * A rendered bank like [GatecrashKits]: the pads carry their grids, not editable recipes. Bump
 * [VERSION] when any pad's audio changes; saved kits keep the version they were printed with.
 */
object SootKits {
    /**
     * 3: the four bars' dictaphone at .9 (the owner's round-2 pick). 2: A11 and A12 re-voiced (the owner
     * heard A02, A11 and A12 as the same snare).
     */
    const val VERSION = 3
    const val BPM = 84
    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16
    const val HITS = 14

    /** One hit pad: its name, class, mute group and the preset it plays. */
    private class Hit(val name: String, val voice: SootVoice, val preset: String, val mute: Int = 0)

    private val hits = listOf(
        Hit("Kick", SootVoice.KICK, "Sag"),
        Hit("Hot Snare", SootVoice.SNARE, "Hot"),
        Hit("Closed Hat", SootVoice.HAT_CLOSED, "Wide", mute = 1),
        Hit("Open Hat", SootVoice.HAT_OPEN, "Wide", mute = 1),
        Hit("Heavy Kick", SootVoice.KICK, "Heavy"),
        Hit("Dry Crack", SootVoice.SNARE, "Dry crack"),
        Hit("Tension Rod", SootVoice.ROD, "Printed"),
        Hit("Metal Cup", SootVoice.CUP, "Printed"),
        Hit("Steel Plate", SootVoice.PLATE, "Printed"),
        Hit("Coil", SootVoice.COIL, "Printed"),
        Hit("Bench Slap", SootVoice.SNARE, "Bench"),
        Hit("Deep Coil Snare", SootVoice.SNARE, "Coil"),
        Hit("Narrow Closed Hat", SootVoice.HAT_CLOSED, "Narrow", mute = 3),
        Hit("Narrow Open Hat", SootVoice.HAT_OPEN, "Narrow", mute = 3),
    )

    fun names(): List<String> = hits.map { it.name } + listOf("Coil Roll", "Four Bars")

    private fun patchOf(hit: Hit): SootPatch = SootPresets.forVoice(hit.voice).single { it.name == hit.preset }

    private fun classOf(role: Int): DrumClass = when (role) {
        in 0 until HITS -> hits[role].voice.drumClass
        HITS -> DrumClass.PERC // the roll rings past 1.5 s; declared, never classified (BREAKER's riser too)
        else -> DrumClass.LOOP
    }

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return when (role) {
            in 0 until HITS -> patchOf(hits[role]).render(velocity, take)
            HITS -> roll(velocity, take)
            else -> phrase(velocity, take)
        }
    }

    /** The sixteen pads, each with its full grid. The renders are independent, so they run in parallel. */
    fun build(): List<ArrangedPad> {
        val cells = (0 until PADS).flatMap { role -> velocities.indices.flatMap { z -> (0 until TAKES).map { t -> Triple(role, z, t) } } }
        val rendered = cells.parallelStream().map { (role, z, t) -> Triple(role, z, t) to render(role, velocities[z], t) }
            .collect(java.util.stream.Collectors.toList()).toMap()
        val names = names()
        return List(PADS) { role ->
            val grid = velocities.indices.map { z -> List(TAKES) { t -> rendered.getValue(Triple(role, z, t)) } }
            ArrangedPad(
                grid.last().first(), classOf(role), displayName = names[role], velocityTakes = grid,
                // The wide hats choke each other, the narrow hats each other, the roll and the phrase each other.
                muteGroup = if (role < HITS) hits[role].mute else 2,
                source = mapOf(
                    "engine" to "SOOT", "kitVersion" to VERSION.toString(), "model" to Soot.MODEL_VERSION.toString(),
                    "role" to role.toString(), "bpm" to BPM.toString(),
                    "preset" to (if (role < HITS) "${hits[role].voice.name}/${hits[role].preset}" else names[role]),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to (if (role == HITS) "roll" else if (role == HITS + 1) "phrase" else "finite"),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ A15, the coil roll

    /** The roll's build: sixteen strikes over this long, then the coil rings out. */
    const val ROLL_BUILD = 2.0
    private const val ROLL_TAIL = 1.4
    private const val ROLL_STRIKES = 16

    /**
     * Sixteen strikes of the hot snare, speeding up (the gap shrinks along a 1.6 power curve, from about
     * 4 to 25 a second) and rising in strength, each a fresh strike (its own seed), all into one coil:
     * the spring fills as the roll builds and rings out after the last one. Then the snare's cassette and
     * print, and a fader push over the build. The pad's velocity scales every strike; a take re-strikes all
     * sixteen.
     */
    fun roll(velocity: Float, take: Int): Snip {
        val rate = Dsp.RATE
        val m = Soot.settled(SootVoice.SNARE, patchOf(hits[1]).macros)
        val frames = ((ROLL_BUILD + ROLL_TAIL) * rate).toInt()
        val dry = DoubleArray(frames)
        for (i in 0 until ROLL_STRIKES) {
            val p = i / (ROLL_STRIKES - 1.0)
            val at = (ROLL_BUILD * .92 * (1 - (1 - p).pow(1.6)) * rate).toInt()
            val v = velocity * (.3f + .7f * p.toFloat())
            val r = Random(Dsp.seedFor("soot-roll", take, i))
            val noise = Dsp.Noise(Dsp.seedFor("soot-roll-noise", take, i))
            val hit = Soot.snareStrike(m, Soot.strength(v), r, noise)
            val g = .35 + .65 * p
            for (k in hit.indices) if (at + k < frames) dry[at + k] += g * hit[k]
        }
        val tail = Soot.snareTail(dry, m, frames)
        val printed = Soot.print(SootVoice.SNARE, m, Soot.centre(tail))
        // The dictaphone's pump evens a crescendo out (measured: the build rose 1.9 dB), so the rise is a
        // fader push after it, -12 dB to 0 over the build: BREAKER's riser, as the owner approved it there.
        for (i in printed.indices) {
            val q = minOf(1.0, i / (ROLL_BUILD * rate))
            printed[i] *= 10.0.pow((-12 + 12 * q.pow(1.5)) / 20)
        }
        return Soot.output(printed, cap = frames)
    }

    // ------------------------------------------------------------------ A16, four bars through one coil

    /** One groove note: its sixteenth step in the four bars, the pad role it plays, its weight. */
    private class Note(val step: Int, val role: Int, val weight: Float)

    /** The round-0 groove the owner picked (docs/SOOT.md), as pad roles: kick 0, snare 1, hats 2 and 3, rod 6, cup 7, plate 8. */
    private val notes: List<Note> = buildList {
        val kicks = listOf(listOf(0, 6, 10), listOf(0, 3, 10, 11), listOf(0, 6, 10), listOf(0, 7, 9, 14))
        for (bar in 0..3) {
            val b0 = bar * 16
            for (s in kicks[bar]) add(Note(b0 + s, 0, if (s == 0) 1f else .85f))
            for (s in listOf(4, 12)) add(Note(b0 + s, 1, 1f))
            for (s in 0 until 16 step 2) add(if ((bar == 1 || bar == 3) && s == 14) Note(b0 + s, 3, .8f) else Note(b0 + s, 2, if (s % 4 == 0) .85f else .55f))
            if (bar == 1) add(Note(b0 + 15, 1, .3f))
            if (bar == 2) add(Note(b0 + 7, 1, .3f))
        }
        addAll(listOf(Note(11, 6, .7f), Note(29, 7, .6f), Note(45, 7, .5f), Note(51, 8, .65f), Note(59, 6, .55f)))
    }.sortedBy { it.step }

    private val mixGain = mapOf(0 to 1.0, 1 to .8, 2 to .28, 3 to .3, 6 to .3, 7 to .3, 8 to .3)

    /** Each part's send into the shared coil (round 0's bus). The hats send nothing: the owner picked them dry. */
    private val busSend = mapOf(1 to .25, 6 to .3, 7 to .2, 8 to .25)
    private const val KICK_SEND = .45

    /**
     * The four bars' dictaphone: .9, the owner's pick for this pad at round 2 (the single pads stay at
     * the .45 they picked at round 1).
     */
    const val PHRASE_GRIME = .9

    /** The phrase's exact length: four bars of four beats at [BPM]. */
    val phraseFrames: Int get() = (16 * 60.0 / BPM * Dsp.RATE).toInt()

    /**
     * Four bars through one bus: every hit dry ([Soot.dry]), the 58% swing and the dragged hats of
     * round 0, the hits' sends and the kick (opened down to 90 Hz) into one coil, then the dictaphone
     * keyed by the kick, so the kit pumps with it. The pad's velocity scales every note's strike; a take
     * re-strikes every note and re-draws the human timing. The overhang past four bars is wrapped onto
     * the start, so the file loops at [BPM] without a seam.
     */
    fun phrase(velocity: Float, take: Int, grime: Double = PHRASE_GRIME): Snip {
        val rate = Dsp.RATE
        val step = 60.0 / BPM / 4
        val loop = phraseFrames
        val total = loop + (1.5 * rate).toInt()
        val human = Random(Dsp.seedFor("soot-phrase", take))
        val touch = .35f + .65f * velocity.coerceIn(0f, 1f)
        val mix = DoubleArray(total)
        val kick = DoubleArray(total)
        val send = DoubleArray(total)
        val counts = IntArray(PADS)
        var openAt = -1
        var openHit: DoubleArray? = null
        for (n in notes) {
            var t = n.step * step
            if (n.step % 2 == 1) t += .32 * step
            t += when (n.role) {
                2, 3 -> .014 + (human.nextDouble() * 2 - 1) * .003
                1 -> .006 + (human.nextDouble() * 2 - 1) * .002
                else -> (human.nextDouble() * 2 - 1) * .002
            }
            val hit = hits[n.role]
            val strike = take * TAKES + counts[n.role]++
            val x = Soot.dry(hit.voice, Soot.settled(hit.voice, patchOf(hit).macros), n.weight * touch, strike)
            val at = Math.floorMod((t * rate).toInt(), loop)
            val g = mixGain.getValue(n.role) * n.weight
            if (n.role == 2 && openHit != null) {
                // the closed hat chokes the open one: a 12 ms fade, then nothing
                val oh = openHit; val fade = (.012 * rate).toInt()
                for (k in at until minOf(total, openAt + oh.size)) {
                    val keep = if (k - at < fade) 1.0 - (k - at).toDouble() / fade else 0.0
                    mix[k] -= (1 - keep) * mixGain.getValue(3) * .8 * oh[k - openAt]
                }
                openHit = null
            }
            for (k in x.indices) {
                val i = at + k
                if (i >= total) break
                mix[i] += g * x[k]
                if (n.role == 0) kick[i] += g * x[k] else send[i] += g * (busSend[n.role] ?: 0.0) * x[k]
            }
            if (n.role == 3) { openAt = at; openHit = x }
        }
        // The kick drives the coil: its send opened to 90 Hz, the rest of the kit's sends beside it.
        val kickSend = Soot.highpass(kick, 90.0)
        for (i in send.indices) send[i] += KICK_SEND * kickSend[i]
        val ret = Soot.lowpass(BreakerChain.spring(send, .5, total, rate, Soot.COIL_TANK, sendHp = 60.0), Soot.COIL_TANK.tone)
        val pm = mix.maxOf { abs(it) }; val pr = ret.maxOf { abs(it) }
        if (pr > 0) for (i in mix.indices) mix[i] += .35 * pm / pr * ret[i]
        val key = DoubleArray(total) { kick[it] + .3 * mix[it] }
        val bus = Soot.dictaphone(mix, grime, key)
        // Wrap the overhang onto the start: the loop's own tail, as a performance looped at tempo plays it.
        val out = FloatArray(loop)
        for (i in bus.indices) out[i % loop] += bus[i].toFloat()
        val mean = out.average().toFloat()
        for (i in out.indices) out[i] -= mean
        Dsp.levelTo(out, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        return Snip(out, 1, rate)
    }
}
