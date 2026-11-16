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
 * The HICCUP kits: three authored genre kits of sixteen pads each (fourteen hits, a roll and a riser), in the
 * [ScuffKits] shape. HICCUP round 2 (docs/superpowers/specs/2026-11-14-hiccup-glitch-drum-engine-design.md, section 10).
 *
 * - [Kit.HOP], 98 BPM: glitch hop. A deep kick, long slices and a moderate fault, with the toy source kept low, so the
 *   stumble reads as a swing.
 * - [Kit.LAB], 150 BPM: IDM. Short slices that turn into pitches, a high fault rate on the snares and percussion, a deep crush.
 * - [Kit.SUGAR], 160 BPM: hyperpop. The toy source turned up, tuning raised, short decays, the blip and the squeak forward.
 *
 * A rendered kit family, not a Patch engine: every hit comes from [Hiccup.render], which takes a velocity and a take, so
 * a velocity zone is a different strike and a take another strike of the same drum, not a control move. Each hit pad is a
 * grid of three velocity zones of four takes. Grid take t is engine take t + 1, because engine take 0 is the plain render.
 * Level is the sampler's job: every zone's first take peaks at [PAD_PEAK], its takes share that gain, and a softer zone is
 * a different strike, not a quieter one.
 *
 * The two phrases are one bar at the kit's BPM of fresh strikes on a sixteenth grid with finer steps where the bar tightens.
 * ROLL strikes the kit's snare with FAULT stepping up from 0 to the pad's own value while the velocity rises; RISER strikes
 * the kit's riser voice with CHOP rising and TUNE climbing a fifth of the knob while the velocity rises, so the riser is
 * the engine's own gesture stretched over a bar. Each note is a new strike, with the take walking from note to note.
 * The phrases have no bus stage: HICCUP has no bus.
 *
 * Every setting below is an authored hypothesis ("shape"). Nothing has been heard.
 */
object HiccupKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 2

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        HOP("hiccup-hop", "HICCUP HOP", 98),
        LAB("hiccup-lab", "HICCUP LAB", 150),
        SUGAR("hiccup-sugar", "HICCUP SUGAR", 160),
    }

    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16
    /** Where every zone's first take peaks. */
    const val PAD_PEAK = .92f

    /** The roll and the riser step this many times, on a sixteenth grid with finer steps where the bar tightens. */
    private const val PHRASE_NOTES = 16

    /** One playable hit: its voice at its settings, its fader against the kit, and its choke group. */
    internal class Part(
        val name: String,
        val voice: HiccupVoice,
        /** The part's level in a phrase and the peak ratio between pads before the zone gain. "shape" */
        val level: Float,
        val macros: Map<String, Float>,
        val mute: Int = 0,
    ) {
        val cls: DrumClass get() = Hiccup.drumClassFor(voice)
    }

    private fun part(
        name: String, voice: HiccupVoice, level: Float,
        tune: Float, decay: Float, toy: Float, fault: Float, chop: Float, crush: Float, mute: Int = 0,
    ) = Part(name, voice, level, mapOf("TUNE" to tune, "DECAY" to decay, "TOY" to toy, "FAULT" to fault, "CHOP" to chop, "CRUSH" to crush), mute)

    /** A dry hit's level against a full one before the zone gain, as [ScuffKits.touch]. */
    fun touch(velocity: Float): Float = .35f + .65f * velocity.coerceIn(0f, 1f)

    /** One phrase: its name, which part it strikes and what it does to that part's macros across the bar. */
    internal class Phrase(val name: String, val part: Int, val steps: List<Double>, val velocityFrom: Float, val sweep: (Map<String, Float>, Float) -> Map<String, Float>)

    fun roles(kit: Kit): List<String> = parts(kit).map { it.name } + phrases(kit).map { it.name }

    fun build(kit: Kit): List<ArrangedPad> {
        val parts = parts(kit)
        val names = roles(kit)
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> render(kit, role, v, t) } }
            val part = parts.getOrNull(role)
            ArrangedPad(
                grid.last().first(), part?.cls ?: DrumClass.LOOP, displayName = names[role], velocityTakes = grid,
                // The closed and open hat of the front row choke each other; the two phrases choke each other so the riser can cut the roll.
                muteGroup = part?.mute ?: 2,
                source = mapOf(
                    "engine" to "HICCUP", "kitVersion" to VERSION.toString(), "model" to Hiccup.MODEL_VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to "finite",
                    "processing" to if (part != null) "hit" else "phrase",
                ),
            )
        }
    }

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return KitStrikes.finish(raw(kit, role, velocity, take), zoneGain(kit, role, velocity))
    }

    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray =
        if (role < HITS) struck(parts(kit)[role], parts(kit)[role].macros, velocity, take) else phraseRaw(kit, role, velocity, take)

    /** A strike at [velocity] as grid take [take] (engine take + 1), peak at [touch] times the part's level. */
    private fun struck(part: Part, macros: Map<String, Float>, velocity: Float, take: Int): FloatArray {
        val x = Hiccup.render(part.voice, macros, velocity, take + 1).samples
        val k = peakOf(x).let { if (it > 1e-9f) touch(velocity) * part.level / it else 0f }
        return FloatArray(x.size) { x[it] * k }
    }

    /** The phrase's notes, each a fresh strike, summed on a sixteenth grid at the kit's tempo. */
    internal fun phraseRaw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val phrase = phrases(kit)[role - HITS]
        val part = parts(kit)[phrase.part]
        val step = Dsp.RATE * 60.0 / kit.bpm / 4
        val out = FloatArray((step * 16).roundToInt() + Dsp.RATE / 2)
        for ((i, at) in phrase.steps.withIndex()) {
            val along = i.toFloat() / phrase.steps.lastIndex
            val weight = phrase.velocityFrom + (1f - phrase.velocityFrom) * along
            val macros = phrase.sweep(part.macros, along)
            // Each note is its own strike: the take walks so neighbouring notes are never the same audio.
            val hit = struck(part, macros, (velocity * weight).coerceIn(.05f, 1f), (take + i) % TAKES)
            val start = (at * step).roundToInt().coerceIn(0, out.size - 1)
            for (j in 0 until min(hit.size, out.size - start)) out[start + j] += hit[j]
        }
        return out
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, Float>()

    /** One gain per velocity zone, set by the zone's first take and shared by its takes ([ScuffKits]'s law). */
    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        val peak = peakOf(KitStrikes.finish(raw(kit, role, velocity, 0), 1f, ceiling = Float.MAX_VALUE).samples)
        if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }

    private fun peakOf(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }

    // ---------- the kits ----------

    internal fun parts(kit: Kit): List<Part> = when (kit) { Kit.HOP -> hopParts; Kit.LAB -> labParts; Kit.SUGAR -> sugarParts }

    internal fun phrases(kit: Kit): List<Phrase> = when (kit) { Kit.HOP -> hopPhrases; Kit.LAB -> labPhrases; Kit.SUGAR -> sugarPhrases }

    private val K = HiccupVoice.KICK
    private val S = HiccupVoice.SNARE
    private val C = HiccupVoice.CLAP
    private val HC = HiccupVoice.HAT_CLOSED
    private val HO = HiccupVoice.HAT_OPEN
    private val T = HiccupVoice.TOM
    private val B = HiccupVoice.BLIP
    private val Q = HiccupVoice.SQUEAK

    /**
     * CHOP as a note value: the slice that lasts one 1/[n] note (a whole note is four beats) at [kit]'s tempo. Every pad's
     * slice is a tempo subdivision, so the repeats and the grid unit of its fault script sit on the kit's own grid.
     * The engine's slices run from 110 ms to 1.6 ms, so a very fast note value at a slow tempo is as near as it reaches.
     */
    private fun div(kit: Kit, n: Int): Float = Hiccup.chopFor(240f / kit.bpm / n)

    // name, voice, level, TUNE, DECAY, TOY, FAULT, CHOP (a note value, see div), CRUSH
    // The backbone (kick, snare, closed hat, clap) plays nearly clean; the glitch lives on the accent pads.
    private val hopParts = listOf(
        part("Lurch Kick", K, .90f, .40f, .60f, .15f, .20f, div(Kit.HOP, 64), .20f),
        part("Trip Snare", S, .80f, .45f, .55f, .25f, .30f, div(Kit.HOP, 128), .35f),
        part("Tick Hat", HC, .40f, .50f, .15f, .30f, .10f, div(Kit.HOP, 512), .25f, mute = 1),
        part("Skid Hat", HO, .38f, .50f, .65f, .30f, .60f, div(Kit.HOP, 256), .40f, mute = 1),
        part("Stagger Clap", C, .70f, .45f, .55f, .20f, .25f, div(Kit.HOP, 128), .30f),
        part("Sag Tom", T, .75f, .35f, .60f, .20f, .45f, div(Kit.HOP, 64), .30f),
        part("Hop Tom", T, .72f, .65f, .45f, .20f, .40f, div(Kit.HOP, 128), .30f),
        part("Clean Kick", K, .90f, .42f, .50f, .10f, 0f, div(Kit.HOP, 256), 0f),
        part("Clean Snare", S, .80f, .50f, .45f, .20f, 0f, div(Kit.HOP, 256), .05f),
        part("Rewind Snare", S, .72f, .55f, .60f, .30f, .90f, div(Kit.HOP, 64), .50f),
        part("Zip", B, .50f, .50f, .40f, .60f, .70f, div(Kit.HOP, 512), .40f),
        part("Squeeze Toy", Q, .48f, .45f, .45f, .40f, .40f, div(Kit.HOP, 256), .25f),
        part("Buzz Kick", K, .82f, .50f, .50f, .30f, .80f, div(Kit.HOP, 512), .50f),
        part("Dust Hat", HC, .38f, .40f, .30f, .50f, .70f, div(Kit.HOP, 512), .70f),
    )

    private val labParts = listOf(
        part("Relay Kick", K, .90f, .55f, .35f, .30f, .30f, div(Kit.LAB, 512), .55f),
        part("Shard Snare", S, .78f, .65f, .30f, .45f, .40f, div(Kit.LAB, 512), .60f),
        part("Pin Hat", HC, .38f, .65f, .08f, .55f, .15f, div(Kit.LAB, 512), .50f, mute = 1),
        part("Spray Hat", HO, .36f, .60f, .50f, .50f, .85f, div(Kit.LAB, 512), .65f, mute = 1),
        part("Packet Clap", C, .68f, .60f, .30f, .50f, .35f, div(Kit.LAB, 512), .55f),
        part("Null Tom", T, .74f, .30f, .50f, .40f, .70f, div(Kit.LAB, 256), .60f),
        part("Bit Tom", T, .70f, .75f, .30f, .50f, .65f, div(Kit.LAB, 512), .70f),
        part("Sub Thud", K, .92f, .30f, .70f, .10f, .20f, div(Kit.LAB, 128), .15f),
        part("Comb Snare", S, .72f, .50f, .40f, .40f, 1f, div(Kit.LAB, 512), .40f),
        part("Morse", B, .48f, .70f, .20f, .70f, .90f, div(Kit.LAB, 512), .50f),
        part("Scan", B, .46f, .35f, .50f, .50f, .80f, div(Kit.LAB, 256), .80f),
        part("Chirp", Q, .44f, .70f, .25f, .60f, .80f, div(Kit.LAB, 512), .60f),
        part("Grain Kick", K, .80f, .70f, .20f, .50f, 1f, div(Kit.LAB, 512), .70f),
        part("Static", HO, .34f, .30f, .80f, .90f, .50f, div(Kit.LAB, 64), .95f),
    )

    private val sugarParts = listOf(
        part("Candy Kick", K, .92f, .65f, .45f, .85f, .15f, div(Kit.SUGAR, 256), .45f),
        part("Pop Snare", S, .80f, .75f, .30f, .80f, .30f, div(Kit.SUGAR, 256), .40f),
        part("Sprinkle Hat", HC, .38f, .80f, .10f, .90f, .10f, div(Kit.SUGAR, 512), .40f, mute = 1),
        part("Fizz Hat", HO, .36f, .75f, .45f, .90f, .55f, div(Kit.SUGAR, 512), .50f, mute = 1),
        part("Sticker Clap", C, .70f, .70f, .35f, .75f, .20f, div(Kit.SUGAR, 512), .35f),
        part("Bounce Tom", T, .74f, .55f, .40f, .80f, .50f, div(Kit.SUGAR, 256), .30f),
        part("Boing Tom", T, .70f, .85f, .35f, .90f, .60f, div(Kit.SUGAR, 512), .30f),
        part("Donk Kick", K, .86f, .85f, .30f, .70f, .20f, div(Kit.SUGAR, 128), .25f),
        part("Sparkle Snare", S, .72f, .90f, .20f, 1f, .85f, div(Kit.SUGAR, 512), .50f),
        part("Coin", B, .50f, .80f, .30f, 1f, .50f, div(Kit.SUGAR, 512), .30f),
        part("Laser", B, .48f, .60f, .45f, .90f, .90f, div(Kit.SUGAR, 512), .40f),
        part("Squeaky", Q, .48f, .60f, .35f, .50f, .30f, div(Kit.SUGAR, 256), .20f),
        part("Giggle", Q, .44f, .80f, .20f, .70f, .95f, div(Kit.SUGAR, 512), .40f),
        part("Glitter Clap", C, .62f, .90f, .20f, .90f, .90f, div(Kit.SUGAR, 512), .60f),
    )

    /** One bar that tightens from eighths to sixteenths to thirty-seconds. */
    private val rollSteps: List<Double> = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 9.0, 10.0, 11.0) + List(8) { 12.0 + it * .5 }

    /** One bar of sixteenths. */
    private val riserSteps: List<Double> = List(PHRASE_NOTES) { it.toDouble() }

    /** FAULT steps from 0 up to the pad's own value across the bar. */
    private val rollSweep: (Map<String, Float>, Float) -> Map<String, Float> =
        { base, along -> base + ("FAULT" to (base.getValue("FAULT") * along)) }

    /** CHOP rises from .15 to 1 and TUNE rises by .20 across the bar. */
    private val riserSweep: (Map<String, Float>, Float) -> Map<String, Float> =
        { base, along -> base + mapOf("CHOP" to (.15f + .85f * along), "TUNE" to (base.getValue("TUNE") + .20f * along).coerceIn(0f, 1f)) }

    private val hopPhrases = listOf(
        Phrase("Trip Roll", 1, rollSteps, .45f, rollSweep),
        Phrase("Stagger Riser", 4, riserSteps, .5f, riserSweep),
    )
    private val labPhrases = listOf(
        Phrase("Shard Roll", 1, rollSteps, .45f, rollSweep),
        Phrase("Morse Riser", 9, riserSteps, .5f, riserSweep),
    )
    private val sugarPhrases = listOf(
        Phrase("Pop Roll", 1, rollSteps, .45f, rollSweep),
        Phrase("Coin Riser", 9, riserSteps, .5f, riserSweep),
    )
}
