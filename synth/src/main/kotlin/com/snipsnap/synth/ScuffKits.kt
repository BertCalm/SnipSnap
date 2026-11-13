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
 * The SCUFF kits: two authored Michigan drum kits of sixteen pads each (fourteen hits, two bus phrases), in the
 * GATECRASH shape ([GatecrashKits]). SCUFF rounds 2 and 3 (docs/SCUFF.md).
 *
 * - [Kit.DETROIT], 118 BPM: the round-0b audition's kit, the engine's defaults on every pad, "Two Bars" and a clap riser.
 * - [Kit.FLINT], 132 BPM: faster and brighter, as the brief has Flint. A higher, shorter kick with more click; a
 *   tighter, shorter clap; brighter hats; shorter, harder-driven bass hits; three rims (the stank rim is the
 *   voice here) with more bite; a higher zap, tick, pluck and bell; a faster suck-back. The chain is set harder
 *   for the whole kit (more GRIT and BARK, less LOCKER, more SLAM), its two bars are more frantic, and its riser
 *   is the rim, not the clap. Different pads, tunings, roles, tempo and chain, not the same kit louder.
 *
 * A rendered kit family, not a Patch engine: the hits come from [Scuff] as it stands, each pad a grid of three
 * velocity zones of four takes, rendered by the engine itself ([Scuff.render] takes a velocity and a take, so a
 * zone is a different strike and a take another strike, not a control move). The engine's chain, SLAM included,
 * runs per pad on the hits.
 *
 * The two phrases are where the brief's bus is real: every note is rendered through GRIT, LOCKER and BARK only,
 * the notes are summed, and SLAM runs once on the sum ([Scuff.slamBus]), so the kick and the bass hit fuse into
 * one growl the way the brief describes and a one-shot pad cannot. "Two Bars" is the pattern the round-0b
 * audition played (118 BPM, a straight sixteenth grid, no swing); "Clap Riser" tightens from eighths to
 * thirty-seconds over one bar, the roll-or-riser the drum gate asks for. The round-0b pattern's tape-stopped hat
 * is left out: DROP is the rack's move (DIVE), not the engine's.
 *
 * Takes 1 to 4 of the engine are the dealt set (take 0 is the plain strike the audition pages play), so grid take
 * t is engine take t + 1. Level is the sampler's job: every zone's first take peaks at [PAD_PEAK], its takes share
 * that gain, and a softer zone is a different strike, not a quieter one.
 *
 * Every setting below is a shape value, authored unheard; the round-0b sound they rest on is accepted.
 */
object ScuffKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    /** [chain] is the kit's own setting of the engine's chain knobs, over the defaults, on every pad. "shape" */
    enum class Kit(val id: String, val title: String, val bpm: Int, val chain: Map<String, Float> = emptyMap()) {
        DETROIT("scuff-detroit", "Detroit", 118),
        FLINT("scuff-flint", "Flint", 132, mapOf("GRIT" to .7f, "BARK" to .65f, "LOCKER" to .3f, "SLAM" to .75f)),
    }

    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16
    /** Where every zone's first take peaks. */
    const val PAD_PEAK = .92f
    /** The bus clipper on a kit's phrases: the kit's SLAM, or the engine's own default. */
    fun busSlam(kit: Kit): Float = kit.chain["SLAM"] ?: Scuff.defaults(ScuffVoice.KICK).getValue("SLAM")

    /** One playable hit: what it is, which voice at which settings, its choke group, and its fader against the kit. */
    internal class Part(
        val name: String,
        val cls: DrumClass,
        val voice: ScuffVoice,
        val macros: Map<String, Float> = emptyMap(),
        val mute: Int = 0,
        /** The part's level in a phrase, as a producer would set the fader. "shape" */
        val gain: Float = 1f,
    )

    /** One note of a phrase: where (sixteenths from the start), which part, and how hard against the phrase's own velocity. */
    private class Note(val step: Double, val part: Int, val weight: Float)

    private class Phrase(val name: String, val bars: Int, val notes: List<Note>)

    /** A dry hit's level against a full one before the bus sees it, as [GatecrashKits.touch]. */
    fun touch(velocity: Float): Float = .35f + .65f * velocity.coerceIn(0f, 1f)

    fun roles(kit: Kit): List<String> = parts(kit).map { it.name } + phrases(kit).map { it.name }

    fun build(kit: Kit): List<ArrangedPad> {
        val parts = parts(kit)
        val names = roles(kit)
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> render(kit, role, v, t) } }
            val part = parts.getOrNull(role)
            ArrangedPad(
                grid.last().first(), part?.cls ?: DrumClass.LOOP, displayName = names[role], velocityTakes = grid,
                // The hats choke each other; the two phrases choke each other so the riser can cut the bars.
                muteGroup = part?.mute ?: 2,
                source = mapOf(
                    "engine" to "SCUFF", "kitVersion" to VERSION.toString(), "model" to Scuff.MODEL_VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to "finite",
                    "processing" to if (part != null) "chain" else "bus",
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
        if (role < HITS) struck(kit, parts(kit)[role], velocity, take, Scuff.ALL_STAGES) else phraseRaw(kit, role, velocity, take)

    private val PRE_BUS = setOf(Scuff.Stage.GRIT, Scuff.Stage.LOCKER, Scuff.Stage.BARK)

    /** A strike at [velocity] as grid take [take] (engine take + 1), peak at [touch] times the part's fader. */
    private fun struck(kit: Kit, part: Part, velocity: Float, take: Int, stages: Set<Scuff.Stage>): FloatArray {
        // The engine's own DC block first: a bass hit's asymmetric shaper leaves an offset the kit's 20 Hz
        // one-pole alone does not settle inside a 220 ms note.
        var x = Scuff.dcBlock(Scuff.renderPad(part.voice, kit.chain + part.macros, velocity, take + 1, stages))
        // The crash rises to its last sample, where the kit's finish fades the last 4 ms: 6 ms of silence after
        // the cut keeps the cut and keeps the zone's peak where the gain law measured it.
        if (part.voice == ScuffVoice.INHALE) x = x.copyOf(x.size + (0.006f * Dsp.RATE).toInt())
        val k = peakOf(x).let { if (it > 1e-9f) touch(velocity) * part.gain / it else 0f }
        return FloatArray(x.size) { x[it] * k }
    }

    /** The phrase's notes, each through the engine's chain short of SLAM, summed, then SLAM on the sum: the bus. */
    internal fun phraseRaw(kit: Kit, role: Int, velocity: Float, take: Int, bus: Boolean = true): FloatArray {
        val parts = parts(kit)
        val phrase = phrases(kit)[role - HITS]
        val step = Dsp.RATE * 60.0 / kit.bpm / 4
        val out = FloatArray((step * 16 * phrase.bars).roundToInt() + Dsp.RATE / 2)
        for ((i, note) in phrase.notes.withIndex()) {
            // Each note is its own strike: the take walks so neighbouring notes are never the same audio.
            val hit = struck(kit, parts[note.part], (velocity * note.weight).coerceIn(.05f, 1f), (take + i) % TAKES, PRE_BUS)
            val start = (note.step * step).roundToInt().coerceIn(0, out.size - 1)
            for (j in 0 until min(hit.size, out.size - start)) out[start + j] += hit[j]
        }
        if (!bus) return out
        return Scuff.slamBus(out, busSlam(kit)).copyOf(out.size)
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, Float>()

    /** One gain per velocity zone, set by the zone's first take and shared by its takes ([GatecrashKits.zoneGain]'s law). */
    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        val peak = peakOf(KitStrikes.finish(raw(kit, role, velocity, 0), 1f, ceiling = Float.MAX_VALUE).samples)
        if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }

    private fun peakOf(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }

    // ---------- the kit ----------

    internal fun parts(kit: Kit): List<Part> = when (kit) { Kit.DETROIT -> detroitParts; Kit.FLINT -> flintParts }

    private fun phrases(kit: Kit): List<Phrase> = when (kit) { Kit.DETROIT -> detroitPhrases; Kit.FLINT -> flintPhrases }

    /** Fourteen hits. The engine's own defaults carry the accepted sound; only TUNE and DECAY move between pads. "shape" */
    private val detroitParts = listOf(
        Part("Kick", DrumClass.KICK, ScuffVoice.KICK),
        Part("Clap", DrumClass.CLAP, ScuffVoice.CLAP, gain = .8f),
        Part("Hat", DrumClass.HAT_CLOSED, ScuffVoice.HAT, mute = 1, gain = .4f),
        Part("Hat Open", DrumClass.HAT_OPEN, ScuffVoice.HAT, mapOf("DECAY" to .95f), mute = 1, gain = .4f),
        Part("Boom Low", DrumClass.PERC, ScuffVoice.BOOM, mapOf("TUNE" to .3f), gain = .9f),
        Part("Boom", DrumClass.PERC, ScuffVoice.BOOM, gain = .9f),
        Part("Boom High", DrumClass.PERC, ScuffVoice.BOOM, mapOf("TUNE" to .9f), gain = .85f),
        Part("Rim", DrumClass.PERC, ScuffVoice.RIM, gain = .55f),
        Part("Rim High", DrumClass.PERC, ScuffVoice.RIM, mapOf("TUNE" to .75f), gain = .55f),
        Part("Pluck", DrumClass.PERC, ScuffVoice.PLUCK, gain = .5f),
        Part("Inhale", DrumClass.PERC, ScuffVoice.INHALE, gain = .5f),
        Part("Bell", DrumClass.PERC, ScuffVoice.BELL, gain = .5f),
        Part("Zap", DrumClass.PERC, ScuffVoice.ZAP, gain = .6f),
        Part("Tick", DrumClass.PERC, ScuffVoice.TICK, gain = .16f),
    )

    /**
     * The round-0b audition's two bars on a straight sixteenth grid: the clap on 2 and 4 with ghosts, the bass
     * as short notes that jump, rims on the e's and a's, straight-eighth hats with one 32nd burst, a tick under
     * it all, the bell on the one, the zap before bar 2's clap, the crash sucked back into bar 2. "shape"
     */
    private val twoBars: List<Note> = buildList {
        for (s in listOf(0, 7, 10, 16, 22, 25)) add(Note(s.toDouble(), 0, 1f))
        for ((s, w) in listOf(4 to 1f, 12 to 1f, 15 to .45f, 20 to 1f, 28 to 1f, 30 to .4f)) add(Note(s.toDouble(), 1, w))
        for ((s, p) in listOf(0 to 5, 3 to 5, 6 to 6, 10 to 5, 16 to 5, 19 to 6, 22 to 5, 26 to 4, 29 to 5)) add(Note(s.toDouble(), p, if (p == 6) .85f else 1f))
        for (s in listOf(1, 3, 9, 11, 14, 17, 19, 23, 27, 31)) add(Note(s.toDouble(), 7, if (s % 4 == 3) .9f else .7f))
        for (s in 0 until 32 step 2) if (s != 30) add(Note(s.toDouble(), 2, if (s % 4 == 0) .9f else .7f))
        for (j in 0 until 4) add(Note(14.0 + j * .5, 2, .6f + .1f * j))          // the 32nd burst
        for (s in 0 until 32) add(Note(s.toDouble(), 13, if (s % 2 == 1) .5f else .75f))
        add(Note(0.0, 11, 1f))                                                   // the bell on the one
        add(Note(19.0, 12, 1f))                                                  // the zap before bar 2's clap
        for ((s, w) in listOf(8 to .9f, 13 to .7f, 24 to .9f, 29 to .7f)) add(Note(s.toDouble(), 9, w))
        add(Note(13.5, 10, 1f))                                                  // the suck-back lands on bar 2's one
    }

    /** One bar that tightens from eighths to sixteenths to thirty-seconds and grows louder as it goes, on the clap. */
    private val clapRiser: List<Note> = run {
        val steps = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 9.0, 10.0, 11.0) + List(8) { 12.0 + it * .5 }
        steps.mapIndexed { i, s -> Note(s, 1, .35f + .65f * i / steps.lastIndex) }
    }

    private val detroitPhrases = listOf(Phrase("Two Bars", 2, twoBars), Phrase("Clap Riser", 1, clapRiser))

    // ---------- FLINT ----------

    /** Fourteen hits, faster and brighter: every pad moved up or shortened against Detroit's, three rims. "shape" */
    private val flintParts = listOf(
        Part("Kick", DrumClass.KICK, ScuffVoice.KICK, mapOf("TUNE" to .6f, "DECAY" to .3f, "CLICK" to .8f)),
        Part("Clap", DrumClass.CLAP, ScuffVoice.CLAP, mapOf("TUNE" to .6f, "DECAY" to .35f, "SNAP" to .8f), gain = .85f),
        // The hats keep the engine's own GRIT and SLAM. Measured on the closed hat: the kit's harder crush folds the
        // 9 kHz ring down into the mids, and the harder bus clip puts its 600 Hz growl under a hat (centroid 1.6 kHz
        // at SLAM .75 against 4.2 kHz at .65). The brief's hat is clipped clean; the kit's crush and slam are for
        // the claps, rims and bass hits.
        Part("Hat", DrumClass.HAT_CLOSED, ScuffVoice.HAT, mapOf("TUNE" to .7f, "DECAY" to .3f, "GRIT" to .55f, "SLAM" to .65f), mute = 1, gain = .5f),
        Part("Hat Open", DrumClass.HAT_OPEN, ScuffVoice.HAT, mapOf("TUNE" to .7f, "DECAY" to 1f, "GRIT" to .55f, "SLAM" to .65f), mute = 1, gain = .5f),
        Part("Boom", DrumClass.PERC, ScuffVoice.BOOM, mapOf("TUNE" to .6f, "DECAY" to .3f, "DRIVE" to .8f), gain = .9f),
        Part("Boom High", DrumClass.PERC, ScuffVoice.BOOM, mapOf("TUNE" to 1f, "DECAY" to .3f, "DRIVE" to .8f), gain = .85f),
        Part("Rim", DrumClass.PERC, ScuffVoice.RIM, mapOf("TUNE" to .55f, "DECAY" to .4f, "BITE" to .9f), gain = .65f),
        Part("Rim Low", DrumClass.PERC, ScuffVoice.RIM, mapOf("TUNE" to .25f, "DECAY" to .5f, "BITE" to .8f), gain = .65f),
        Part("Rim High", DrumClass.PERC, ScuffVoice.RIM, mapOf("TUNE" to .9f, "DECAY" to .3f, "BITE" to .9f), gain = .6f),
        Part("Zap", DrumClass.PERC, ScuffVoice.ZAP, mapOf("TUNE" to .7f, "DECAY" to .3f), gain = .6f),
        Part("Tick", DrumClass.PERC, ScuffVoice.TICK, mapOf("TUNE" to .7f), gain = .2f),
        Part("Pluck", DrumClass.PERC, ScuffVoice.PLUCK, mapOf("TUNE" to .8f, "DECAY" to .3f), gain = .5f),
        Part("Bell", DrumClass.PERC, ScuffVoice.BELL, mapOf("TUNE" to .7f, "DECAY" to .4f, "TONE" to .8f), gain = .5f),
        Part("Inhale", DrumClass.PERC, ScuffVoice.INHALE, mapOf("DECAY" to .2f), gain = .5f),
    )

    /**
     * Flint's two bars at 132: the kick on the one and the and-of-two, the clap on 2 and 4 with a ghost before
     * every one, the rims stumbling across the e's and a's with the low rim answering, two 32nd hat bursts, the
     * bass as short notes jumping up, the tick under it all, the zap before each clap of bar 2, the bell on bar
     * 2's one, the crash sucked back into it. "shape"
     */
    private val flintBars: List<Note> = buildList {
        for (s in listOf(0, 6, 10, 16, 19, 26)) add(Note(s.toDouble(), 0, 1f))
        for (s in listOf(4, 12, 20, 28)) { add(Note(s.toDouble(), 1, 1f)); add(Note(s - 1.0, 1, .4f)) }
        for ((s, p) in listOf(1 to 6, 2 to 7, 5 to 6, 9 to 8, 13 to 6, 14 to 7, 17 to 6, 21 to 8, 25 to 6, 29 to 7, 30 to 8)) add(Note(s.toDouble(), p, if (p == 8) .9f else .75f))
        for (s in 0 until 32 step 2) add(Note(s.toDouble(), 2, if (s % 4 == 0) .9f else .7f))
        for (b in listOf(6.0, 22.0)) for (j in 0 until 4) add(Note(b + j * .5, 2, .6f + .1f * j))
        for ((s, p) in listOf(0 to 4, 3 to 4, 8 to 5, 11 to 4, 16 to 4, 19 to 5, 24 to 5, 27 to 4)) add(Note(s.toDouble(), p, if (p == 5) .85f else 1f))
        for (s in 0 until 32) add(Note(s.toDouble(), 10, if (s % 2 == 1) .5f else .75f))
        for (s in listOf(11, 27)) add(Note(s.toDouble(), 9, 1f))
        add(Note(16.0, 12, 1f))
        for ((s, w) in listOf(7 to .8f, 23 to .8f)) add(Note(s.toDouble(), 11, w))
        add(Note(14.0, 13, 1f))                                                  // a faster suck-back lands on bar 2's one
    }

    /** One bar that tightens from eighths to thirty-seconds on the stank rim, rising, the low rim answering each eighth. */
    private val rimRiser: List<Note> = run {
        val steps = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 9.0, 10.0, 11.0) + List(8) { 12.0 + it * .5 }
        steps.mapIndexed { i, s -> Note(s, 6, .35f + .65f * i / steps.lastIndex) } +
            listOf(1.0, 3.0, 5.0, 7.0).map { Note(it, 7, .5f) }
    }

    private val flintPhrases = listOf(Phrase("Two Bars", 2, flintBars), Phrase("Rim Riser", 1, rimRiser))
}
