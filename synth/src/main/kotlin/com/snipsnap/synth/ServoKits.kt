package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The SERVO kits: three authored machine-drum kits of sixteen pads each (fourteen hits, a roll and a riser), in
 * the SCUFF and GATECRASH shape. docs/SERVO.md.
 *
 * - [Kit.ELECTRO], 126 BPM: leads with the dive. ZAP high, a cool damped rail, low HEAT; a long low kick, a
 *   cowbell, toms that fall like lasers.
 * - [Kit.JIT], 156 BPM (ghetto tech; named for the Detroit dance): leads with the flat-top. Everything short,
 *   tuned up and run hot against the rail.
 * - [Kit.NEURO], 174 BPM: leads with the snarl. A rail that rings (GROWL .5 to 1), a snare body near 200 Hz,
 *   two bass stabs through a moving filter.
 *
 * Different pads, tunings, lengths, roles, tempo and rail, not one kit at three effect strengths. A rendered
 * kit family over [Servo] as it stands: each pad is a grid of three velocity zones of four takes rendered by
 * the engine itself, so a softer zone is a hit that pulls the rail less (it dives less and clips less), and a
 * take is the same drum caught at another moment of the rail's recovery ([Servo.renderRaw]).
 *
 * The two phrases are where the shared rail is real. Every note of a roll or riser loads ONE rail
 * ([Servo.phraseRails]), so the pile-up of hits drags it down and the roll sinks as it tightens, and the
 * landing kick makes the crash dive. Sixteen one-shot pads cannot do that to each other on an MPC, so only
 * these two pads carry it. Each note is a fresh strike: the take walks from note to note.
 *
 * Level is the sampler's job: a zone's four takes are matched in loudness and its highest-peaking take peaks at
 * [PAD_PEAK] ([levels], CLUTTER's law). The hats choke in pairs (A03 with A04, A07 with A08) and the two phrases choke
 * each other.
 *
 * Every setting below is a shape value from `prototype/servo/kits.py`, authored unheard.
 */
object ServoKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    /** [railGrowl] is the kit's shared rail in its phrases: how much the one supply rings. "shape" */
    enum class Kit(val id: String, val title: String, val bpm: Int, val railGrowl: Float) {
        ELECTRO("servo-electro", "Electro", 126, .2f),
        JIT("servo-jit", "Jit", 156, .35f),
        NEURO("servo-neuro", "Neuro", 174, .85f),
    }

    val velocities = listOf(.35f, .65f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16
    /** Where each zone's highest-peaking take peaks. */
    const val PAD_PEAK = .92f
    /** The span over which a zone's takes are matched in loudness. "shape" */
    const val LEVEL_WINDOW_S = .15f
    /** The ring-out after a phrase's last step. */
    private const val PHRASE_TAIL_S = 1.2

    /** One playable hit: what it is, which voice at which settings, its choke group, and its fader in a phrase. */
    internal class Part(
        val name: String, val cls: DrumClass, val voice: ServoVoice, val macros: Map<String, Float>,
        val mute: Int = 0, val gain: Float = 1f,
    )

    /** One note of a phrase: where (sixteenths from the start), which part, how hard against the phrase's velocity. */
    private class Note(val step: Double, val part: Int, val weight: Float)

    private class Phrase(val name: String, val steps: Int, val notes: List<Note>)

    fun roles(kit: Kit): List<String> = parts(kit).take(HITS).map { it.name } + phrases(kit).map { it.name }

    fun build(kit: Kit): List<ArrangedPad> {
        val parts = parts(kit)
        val names = roles(kit)
        return List(PADS) { role ->
            // Each take is rendered once: the zone's level is set from all four, then each is finished.
            val grid = velocities.map { v -> zone(kit, role, v) }
            val part = parts[role].takeIf { role < HITS }
            ArrangedPad(
                grid.last().first(), part?.cls ?: DrumClass.LOOP, displayName = names[role], velocityTakes = grid,
                muteGroup = part?.mute ?: 3,
                source = mapOf(
                    "engine" to "SERVO", "kitVersion" to VERSION.toString(), "model" to Servo.MODEL_VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to "finite",
                    "rail" to if (part != null) "own" else "shared",
                ),
            )
        }
    }

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return KitStrikes.finish(raw(kit, role, velocity, take), gains(kit, role, velocity)[take])
    }

    /** A zone's four finished takes, each rendered once. The same bytes as [render] take by take. */
    private fun zone(kit: Kit, role: Int, velocity: Float): List<Snip> {
        val raws = List(TAKES) { raw(kit, role, velocity, it) }
        val g = gains.getOrPut(Triple(kit, role, velocity)) { levels(raws.map { it.copyOf() }) }
        return raws.mapIndexed { t, x -> KitStrikes.finish(x, g[t]) }
    }

    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray =
        if (role < HITS) parts(kit)[role].let { Servo.renderRaw(it.voice, it.macros, velocity, take) }
        else phraseRaw(kit, role, velocity, take)

    /**
     * The phrase's notes on one rail. Every note is levelled by its part's own full-velocity peak and fader, so
     * the model's velocity law and the shared rail's ducking both survive. [shared] false gives every note its
     * own rail: what the same notes sound like played from one-shot pads, for the test that the rail matters.
     */
    internal fun phraseRaw(kit: Kit, role: Int, velocity: Float, take: Int, shared: Boolean = true): FloatArray {
        val parts = parts(kit)
        val phrase = phrases(kit)[role - HITS]
        val stepS = 60.0 / kit.bpm / 4
        val seconds = phrase.steps * stepS + PHRASE_TAIL_S
        val events = phrase.notes.map { n ->
            val p = parts[n.part]
            Servo.Event(n.step * stepS, p.voice, p.macros, (velocity * n.weight).coerceIn(.05f, 1f))
        }
        val rails = if (shared) Servo.phraseRails(events, seconds, kit.railGrowl) else null
        val out = FloatArray((seconds * Dsp.RATE).roundToInt())
        for ((i, e) in events.withIndex()) {
            val part = parts[phrase.notes[i].part]
            // Each note is its own strike: the take walks so neighbouring notes are never the same audio.
            val hit = Servo.renderRaw(e.voice, e.macros, e.velocity, (take + i) % TAKES, rails?.get(i))
            val k = part.gain / referencePeak(kit, phrase.notes[i].part)
            val start = (e.at * Dsp.RATE).roundToInt().coerceIn(0, out.size - 1)
            for (j in 0 until min(hit.size, out.size - start)) out[start + j] += hit[j] * k
        }
        return out
    }

    private val references = ConcurrentHashMap<Pair<Kit, Int>, Float>()

    /** A part's peak alone, hard, on a rested rail: the one level a phrase scales that part by. */
    private fun referencePeak(kit: Kit, part: Int): Float = references.getOrPut(kit to part) {
        val p = parts(kit)[part]
        peakOf(Servo.renderRaw(p.voice, p.macros, 1f, 0)).coerceAtLeast(1e-9f)
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, FloatArray>()

    private fun gains(kit: Kit, role: Int, velocity: Float): FloatArray =
        gains.getOrPut(Triple(kit, role, velocity)) { levels(List(TAKES) { raw(kit, role, velocity, it) }) }

    /**
     * One gain per take: equal loudness over [LEVEL_WINDOW_S] across the zone, the highest resulting peak at
     * [PAD_PEAK] (CLUTTER's law). The first-take law SCUFF uses left SERVO's takes up to 5.3 dB apart in peak,
     * measured: takes 2 to 4 fire on a rail that is already down, and the rail sets the output swing, so they
     * came out quieter as well as duller. A pad whose takes step in level reads as an uneven pad, so the level
     * is matched and the character is kept. Measured on the takes as the finish leaves them at unit gain.
     */
    private fun levels(raws: List<FloatArray>): FloatArray {
        val n = (LEVEL_WINDOW_S * Dsp.RATE).toInt()
        val finished = raws.map { KitStrikes.finish(it, 1f, ceiling = Float.MAX_VALUE).samples }
        val rms = finished.map { x ->
            val m = min(n, x.size)
            kotlin.math.sqrt((0 until m).sumOf { x[it].toDouble() * x[it] } / m.coerceAtLeast(1)).toFloat().coerceAtLeast(1e-9f)
        }
        val crest = finished.indices.maxOf { peakOf(finished[it]) / rms[it] }
        val target = PAD_PEAK / crest
        return FloatArray(raws.size) { target / rms[it] }
    }

    private fun peakOf(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }

    // ---------- the kits ----------

    internal fun parts(kit: Kit): List<Part> = when (kit) { Kit.ELECTRO -> electroParts; Kit.JIT -> jitParts; Kit.NEURO -> neuroParts }

    private fun phrases(kit: Kit): List<Phrase> = when (kit) { Kit.ELECTRO -> electroPhrases; Kit.JIT -> jitPhrases; Kit.NEURO -> neuroPhrases }

    private fun m(tune: Float, decay: Float, zap: Float, sag: Float, growl: Float, heat: Float) =
        mapOf("TUNE" to tune, "DECAY" to decay, "ZAP" to zap, "SAG" to sag, "GROWL" to growl, "HEAT" to heat)

    // A part's share of a phrase mix, by role. "shape"
    private const val KICK = 1f
    private const val SNARE = .85f
    private const val CLAP = .75f
    private const val HAT = .4f
    private const val TOM = .7f
    private const val PERC = .55f
    private const val STAB = .8f
    private const val LIGHT = .45f

    /** Fourteen pads, then two parts only the phrases play (indices 14 and 15). */
    private val electroParts = listOf(
        Part("Boom Kick", DrumClass.KICK, ServoVoice.KICK, m(.25f, .78f, .5f, .3f, .15f, .25f), gain = KICK),
        Part("Snap Snare", DrumClass.SNARE, ServoVoice.SNARE, m(.45f, .45f, .35f, .4f, .2f, .25f), gain = SNARE),
        Part("Tick Hat", DrumClass.HAT_CLOSED, ServoVoice.HAT, m(.5f, .12f, .3f, .4f, .2f, .2f), mute = 1, gain = HAT),
        Part("Open Hat", DrumClass.HAT_OPEN, ServoVoice.HAT, m(.5f, .7f, .3f, .4f, .2f, .2f), mute = 1, gain = HAT),
        Part("Short Kick", DrumClass.KICK, ServoVoice.KICK, m(.45f, .35f, .65f, .35f, .15f, .3f), gain = KICK),
        Part("Clap", DrumClass.CLAP, ServoVoice.CLAP, m(.5f, .55f, .3f, .4f, .2f, .25f), gain = CLAP),
        Part("Zap Hat", DrumClass.HAT_CLOSED, ServoVoice.HAT, m(.75f, .2f, .9f, .5f, .2f, .25f), mute = 2, gain = HAT),
        Part("Sizzle Hat", DrumClass.HAT_OPEN, ServoVoice.HAT, m(.3f, .9f, .5f, .45f, .25f, .25f), mute = 2, gain = HAT),
        Part("Low Zap Tom", DrumClass.TOM, ServoVoice.TOM, m(.3f, .5f, .7f, .4f, .15f, .25f), gain = TOM),
        Part("High Zap Tom", DrumClass.TOM, ServoVoice.TOM, m(.65f, .4f, .75f, .4f, .15f, .25f), gain = TOM),
        Part("Cowbell", DrumClass.PERC, ServoVoice.BELL, m(.55f, .5f, .2f, .4f, .2f, .3f), gain = LIGHT),
        Part("Rim", DrumClass.PERC, ServoVoice.RIM, m(.5f, .5f, .3f, .4f, .2f, .3f), gain = PERC),
        Part("Laser", DrumClass.PERC, ServoVoice.LASER, m(.55f, .5f, .7f, .45f, .2f, .2f), gain = LIGHT),
        Part("Bass Hit", DrumClass.PERC, ServoVoice.STAB, m(.4f, .4f, .3f, .45f, .2f, .3f), gain = STAB),
        Part("Robot Tom", DrumClass.TOM, ServoVoice.TOM, m(.48f, .3f, .95f, .8f, .3f, .35f), gain = TOM),
        Part("Crash", DrumClass.PERC, ServoVoice.CRASH, m(.5f, .6f, .4f, .4f, .2f, .2f), gain = LIGHT),
    )

    private val jitParts = listOf(
        Part("Hard Kick", DrumClass.KICK, ServoVoice.KICK, m(.7f, .3f, .35f, .55f, .3f, .8f), gain = KICK),
        Part("Crack Snare", DrumClass.SNARE, ServoVoice.SNARE, m(.7f, .3f, .5f, .5f, .3f, .7f), gain = SNARE),
        Part("Tight Hat", DrumClass.HAT_CLOSED, ServoVoice.HAT, m(.65f, .08f, .3f, .5f, .3f, .6f), mute = 1, gain = HAT),
        Part("Off Hat", DrumClass.HAT_OPEN, ServoVoice.HAT, m(.65f, .5f, .3f, .5f, .3f, .65f), mute = 1, gain = HAT),
        Part("Driven Boom", DrumClass.KICK, ServoVoice.KICK, m(.5f, .6f, .3f, .7f, .35f, .85f), gain = KICK),
        Part("Hot Clap", DrumClass.CLAP, ServoVoice.CLAP, m(.7f, .35f, .4f, .5f, .3f, .7f), gain = CLAP),
        Part("Shaker Tick", DrumClass.HAT_CLOSED, ServoVoice.HAT, m(.9f, .03f, .2f, .4f, .3f, .5f), mute = 2, gain = HAT),
        Part("Ride", DrumClass.HAT_OPEN, ServoVoice.HAT, m(.8f, .85f, .2f, .45f, .35f, .55f), mute = 2, gain = HAT),
        Part("Low Tom", DrumClass.TOM, ServoVoice.TOM, m(.5f, .3f, .5f, .55f, .3f, .7f), gain = TOM),
        Part("High Tom", DrumClass.TOM, ServoVoice.TOM, m(.8f, .25f, .5f, .55f, .3f, .7f), gain = TOM),
        Part("High Bell", DrumClass.PERC, ServoVoice.BELL, m(.8f, .3f, .3f, .5f, .3f, .6f), gain = LIGHT),
        Part("Rim Shot", DrumClass.PERC, ServoVoice.RIM, m(.6f, .45f, .4f, .5f, .3f, .75f), gain = PERC),
        Part("Short Laser", DrumClass.PERC, ServoVoice.LASER, m(.7f, .25f, .5f, .5f, .3f, .5f), gain = LIGHT),
        Part("Jack Bass", DrumClass.PERC, ServoVoice.STAB, m(.75f, .25f, .4f, .6f, .35f, .8f), gain = STAB),
        Part("Clave", DrumClass.PERC, ServoVoice.RIM, m(.95f, .2f, .1f, .4f, .3f, .5f), gain = PERC),
        Part("Short Crash", DrumClass.PERC, ServoVoice.CRASH, m(.6f, .35f, .3f, .5f, .3f, .6f), gain = LIGHT),
    )

    private val neuroParts = listOf(
        Part("Punch Kick", DrumClass.KICK, ServoVoice.KICK, m(.62f, .22f, .55f, .6f, .5f, .55f), gain = KICK),
        Part("Steel Snare", DrumClass.SNARE, ServoVoice.SNARE, m(.5f, .5f, .45f, .65f, .85f, .55f), gain = SNARE),
        Part("Tech Hat", DrumClass.HAT_CLOSED, ServoVoice.HAT, m(.8f, .1f, .4f, .6f, .8f, .45f), mute = 1, gain = HAT),
        Part("Open Tech Hat", DrumClass.HAT_OPEN, ServoVoice.HAT, m(.8f, .45f, .4f, .6f, .9f, .45f), mute = 1, gain = HAT),
        Part("Sub Kick", DrumClass.KICK, ServoVoice.KICK, m(.3f, .45f, .3f, .55f, .7f, .45f), gain = KICK),
        Part("Ghost Snare", DrumClass.SNARE, ServoVoice.SNARE, m(.65f, .2f, .2f, .5f, .6f, .3f), gain = SNARE),
        Part("Tick", DrumClass.HAT_CLOSED, ServoVoice.HAT, m(1f, .02f, .7f, .5f, .7f, .4f), mute = 2, gain = HAT),
        Part("Dark Ride", DrumClass.HAT_OPEN, ServoVoice.HAT, m(.35f, .9f, .3f, .55f, .6f, .45f), mute = 2, gain = HAT),
        Part("Growl Tom", DrumClass.TOM, ServoVoice.TOM, m(.25f, .5f, .5f, .8f, .95f, .55f), gain = TOM),
        Part("High Growl Tom", DrumClass.TOM, ServoVoice.TOM, m(.55f, .4f, .5f, .8f, .95f, .55f), gain = TOM),
        Part("Metal", DrumClass.PERC, ServoVoice.BELL, m(.3f, .6f, .4f, .8f, .9f, .6f), gain = LIGHT),
        Part("Snarl Stab", DrumClass.PERC, ServoVoice.STAB, m(.9f, .35f, .6f, 1f, 1f, .75f), gain = STAB),
        Part("Scanner", DrumClass.PERC, ServoVoice.LASER, m(.3f, .6f, .8f, .8f, 1f, .5f), gain = LIGHT),
        Part("Reese Stab", DrumClass.PERC, ServoVoice.STAB, m(.5f, .6f, .3f, .8f, .9f, .6f), gain = STAB),
        Part("Low Rim", DrumClass.PERC, ServoVoice.RIM, m(.35f, .6f, .4f, .65f, .8f, .5f), gain = PERC),
        Part("Crash", DrumClass.PERC, ServoVoice.CRASH, m(.45f, .7f, .4f, .6f, .7f, .45f), gain = LIGHT),
    )

    /** [count] notes of [part] from [from], [every] sixteenths apart, the weight rising from [lo] to [hi]. */
    private fun run(part: Int, from: Double, every: Double, count: Int, lo: Float, hi: Float): List<Note> =
        List(count) { Note(from + it * every, part, lo + (hi - lo) * it / max(1, count - 1)) }

    /**
     * One bar that tightens from eighths to sixteenths to thirty-seconds over a kick: the roll. On the shared
     * rail every hit lands before the last has let go, so the roll sinks as it tightens.
     */
    private fun roll(kick: Int, eighths: Int, sixteenths: Int, thirtySeconds: Int) = Phrase(
        "Roll", 16,
        listOf(Note(0.0, kick, 1f)) + run(eighths, 0.0, 2.0, 4, .45f, .6f) + run(sixteenths, 8.0, 1.0, 4, .6f, .75f) +
            run(thirtySeconds, 12.0, 0.5, 8, .7f, 1f),
    )

    /** Two bars of gathering hits and a landing on the third downbeat: the riser. */
    private fun riser(opener: Int?, eighths: Int, sixteenths: Int, thirtySeconds: Int, landing: List<Int>) = Phrase(
        "Riser", 32,
        listOfNotNull(opener?.let { Note(0.0, it, .55f) }) + run(eighths, 0.0, 2.0, 8, .35f, .6f) +
            run(sixteenths, 16.0, 1.0, 8, .5f, .8f) + run(thirtySeconds, 24.0, 0.5, 16, .6f, 1f) +
            landing.map { Note(32.0, it, 1f) },
    )

    private val electroPhrases = listOf(roll(0, 9, 8, 14), riser(12, 6, 9, 14, listOf(0, 15)))
    private val jitPhrases = listOf(roll(0, 5, 11, 14), riser(null, 2, 1, 1, listOf(4, 15)))
    private val neuroPhrases = listOf(roll(0, 5, 5, 1), riser(12, 6, 5, 9, listOf(0, 15, 11)))
}
