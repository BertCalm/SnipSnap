package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Two kinds of fill: a snare roll that doubles its rate as it nears the downbeat, and a tom run that
 * walks from the high tom down to the floor tom.
 */
enum class FillVoice { SNARE, TOMS }

/**
 * FILL: a drum fill as one pad. The characteristic gesture is sparse hits, then the run subdivides and
 * grows louder, then it lands on the downbeat. Hit the pad LENGTH before a downbeat and the landing falls
 * on it, to the frame, as SCOUR's slam does.
 *
 * Every hit is one of SKIN's own drums (its snare, toms and kick), so a fill sounds like the kit it sits
 * in. FILL adds no drum synthesis: it is a schedule (which drum, when, how hard) and a mix. The toms and
 * the kick take SKIN's strike velocity, which changes the head's sound; the snare, which SKIN does not
 * shape by velocity, takes [Velocity.soften], the house's darker soft hit, and a level.
 *
 * [render] receives no tempo (the engine contract), so TEMPO is a control: 60 to 180 BPM in whole beats
 * per minute. LENGTH steps through one beat, two beats, one bar and two bars of 4/4.
 *
 * Deterministic: the schedule has no randomness, and SKIN's takes are chosen by the hit's place in the run.
 * Shape constants (the crescendo law, the stage split, the tom intervals, the landing tail) are listening
 * hypotheses for the first audition.
 */
object Fill {
    /** Nothing is seeded yet: kept for the patch format, and so a future change can say what it moved. */
    const val MODEL_VERSION = 1
    const val BPM_MIN = 60
    const val BPM_MAX = 180
    /** LENGTH's four steps, in beats of 4/4: one beat, two beats, one bar, two bars. */
    val LENGTH_BEATS = intArrayOf(1, 2, 4, 8)
    /** The classifier files anything longer than this as LOOP (`Classifier`'s own constant is private). */
    const val LOOP_MIN_SECONDS = 1.5f
    /**
     * No render is shorter than this, so a short snare fill is never filed as a lone snare and choked like
     * one: like SCOUR, every fill is a LOOP-class one-shot that plays once, whole.
     */
    const val MIN_RENDER_SECONDS = 1.6
    /** How long the landing rings after the downbeat before the render ends (shape). */
    const val TAIL_SECONDS = 1.0
    /** The quietest first hit at RISE 1, as a fraction of the last (shape). */
    private const val RISE_FLOOR = .2f
    /** Velocities are rendered on this many steps, so a long run reuses its hits (shape: 1/12 is under 1 dB). */
    private const val VELOCITY_STEPS = 12
    /** HUMANIZE 1's largest timing drift, in seconds (shape; R1.1 had 12 ms, heard "too subtle at 1"). */
    private const val DRIFT_SECONDS = .030
    /** The largest share of the gap to a neighbour a hit may drift: under half, so two hits never swap. */
    private const val GAP_SHARE = .4
    /** HUMANIZE 1's largest share of a hit's strength it can gain or lose, and the on-beat accent (shape). */
    private const val LEVEL_SPREAD = .30f
    private const val BEAT_ACCENT = .25f
    /** The fixed seed of HUMANIZE's feel: the same patch always plays the same performance. */
    private const val FEEL_SEED = 0x46494C4C
    /** SKIN's struck voices re-seed their click per take; two takes alternate so neighbours never share one. */
    private const val TAKES = 2

    /** The drums a fill strikes. Toms run high to floor. */
    enum class Drum { SNARE, TOM_HIGH, TOM_MID, TOM_LOW, TOM_FLOOR, KICK }

    /** One hit: when (seconds from the start of the sample), which drum, how hard, and which take. */
    class Hit(val seconds: Double, val drum: Drum, val velocity: Float, val take: Int)

    /** What [schedule] decided, before any audio: the grid and every hit, the landing included. */
    class Schedule(val bpm: Int, val beats: Int, val lengthSeconds: Double, val stepSeconds: Double, val hits: List<Hit>)

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: FillVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", .5f, neutral = .5f),
        MacroSpec("TEMPO", .5f, neutral = .5f),
        MacroSpec("LENGTH", lengthFor(4), neutral = lengthFor(4)),
        MacroSpec("DENSITY", .5f, neutral = .5f),
        MacroSpec("RISE", .6f, neutral = .6f),
        MacroSpec("LAND", .7f, neutral = .7f),
        MacroSpec("HUMANIZE", 0f, neutral = 0f),
    )

    fun defaults(voice: FillVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** Every known macro, in 0..1; a missing or non-finite one takes its default, an unknown one is dropped. */
    internal fun settled(voice: FillVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (k, default) -> (macros[k]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    /** TEMPO as whole beats per minute. */
    fun bpm(tempo: Float): Int = (BPM_MIN + tempo.coerceIn(0f, 1f) * (BPM_MAX - BPM_MIN)).roundToInt()

    /** The TEMPO setting that plays [bpm] (clamped to the range): how a kit cuts a fill for its tempo. */
    fun tempoFor(bpm: Int): Float = (bpm.coerceIn(BPM_MIN, BPM_MAX) - BPM_MIN).toFloat() / (BPM_MAX - BPM_MIN)

    /** LENGTH in beats: the step [length] falls in. */
    fun beats(length: Float): Int = LENGTH_BEATS[(length.coerceIn(0f, 1f) * LENGTH_BEATS.size).toInt().coerceAtMost(LENGTH_BEATS.size - 1)]

    /** The LENGTH setting at the middle of [beats]'s step, so a saved value never sits on a boundary. */
    fun lengthFor(beats: Int): Float {
        val i = LENGTH_BEATS.indexOf(beats)
        require(i >= 0) { "a fill is ${LENGTH_BEATS.joinToString()} beats long, not $beats" }
        return (i + .5f) / LENGTH_BEATS.size
    }

    /** DENSITY in doublings of the finest note: 0, 1 or 2. */
    private fun doublings(density: Float): Int = (density.coerceIn(0f, 1f) * 2).roundToInt()

    /**
     * The fill's hits. Every hit sits on the grid of its finest note, counted from the start of the sample,
     * and the landing (when LAND is above 0) falls exactly LENGTH in, on the downbeat.
     *
     * SNARE splits LENGTH into three stages, 1/2, 1/4 and 1/4, and doubles its rate at each: with DENSITY 0
     * a bar is quarters, then eighths, then sixteenths; each step of DENSITY halves every note again, so the
     * last stage of DENSITY 1 is a sixty-fourth-note buzz. TOMS plays one note value throughout (eighths,
     * sixteenths or thirty-seconds by DENSITY) and walks the four toms in equal quarters of LENGTH. Every
     * stage boundary is a multiple of its notes, so no hit falls off the grid.
     *
     * HUMANIZE then loosens the run (see [humanize]); at 0, its default, the grid above is exact. The
     * landing is never moved.
     */
    fun schedule(voice: FillVoice, macros: Map<String, Float>, velocity: Float = 1f): Schedule {
        val m = settled(voice, macros)
        val vel = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
        val bpm = bpm(m.getValue("TEMPO"))
        val beats = beats(m.getValue("LENGTH"))
        val beat = 60.0 / bpm
        val length = beats * beat
        val d = doublings(m.getValue("DENSITY"))
        val floor = 1f - (1f - RISE_FLOOR) * m.getValue("RISE")
        // The crescendo: the first hit at [floor], the last at full, rising faster near the end (shape).
        fun level(t: Double) = vel * (floor + (1f - floor) * (t / length).toFloat().pow(1.5f))
        val hits = ArrayList<Hit>()
        val finest: Double
        when (voice) {
            FillVoice.SNARE -> {
                finest = beat / 2.0.pow(2 + d)
                val stages = listOf(0.0 to .5, .5 to .75, .75 to 1.0)
                for ((i, stage) in stages.withIndex()) {
                    val step = finest * 2.0.pow(2 - i)
                    var t = stage.first * length
                    while (t < stage.second * length - 1e-9) {
                        hits += Hit(t, Drum.SNARE, level(t), hits.size % TAKES)
                        t += step
                    }
                }
            }
            FillVoice.TOMS -> {
                finest = beat / 2.0.pow(1 + d)
                val toms = listOf(Drum.TOM_HIGH, Drum.TOM_MID, Drum.TOM_LOW, Drum.TOM_FLOOR)
                var t = 0.0
                while (t < length - 1e-9) {
                    val drum = toms[(4 * t / length).toInt().coerceAtMost(3)]
                    hits += Hit(t, drum, level(t), hits.size % TAKES)
                    t += finest
                }
            }
        }
        val run = humanize(voice, hits, m.getValue("HUMANIZE"), beat, length)
        hits.clear(); hits += run
        val land = m.getValue("LAND")
        if (land > 0f) {
            val accent = if (voice == FillVoice.SNARE) Drum.SNARE else Drum.TOM_FLOOR
            hits += Hit(length, Drum.KICK, vel * land, 0)
            hits += Hit(length, accent, vel * land, 0)
        }
        return Schedule(bpm, beats, length, finest, hits)
    }

    /**
     * HUMANIZE [h] applied to the run [hits]: from 0 (the exact grid, unchanged) to 1 (a human feel). Each
     * hit drifts early or late by up to [DRIFT_SECONDS] × [h], and never more than [GAP_SHARE] of the gap to
     * either neighbour, so the order holds and a roll never smears into itself; no hit moves before the
     * start. Each hit's strength moves by up to [LEVEL_SPREAD] × [h], and hits on a beat gain [BEAT_ACCENT] × [h],
     * as a drummer leans on the count. The draws come from [FEEL_SEED] and the voice, one pair per hit in
     * order, so the feel is fixed for a patch and turning HUMANIZE only scales it.
     */
    private fun humanize(voice: FillVoice, hits: List<Hit>, h: Float, beat: Double, length: Double): List<Hit> {
        if (h <= 0f) return hits.toList()
        val random = Random(FEEL_SEED + voice.ordinal)
        return hits.mapIndexed { i, hit ->
            // A triangular draw in -1..1: small drifts are common, large ones rare.
            val drift = random.nextDouble() + random.nextDouble() - 1.0
            val spread = random.nextFloat() + random.nextFloat() - 1f
            val before = if (i == 0) Double.MAX_VALUE else hit.seconds - hits[i - 1].seconds
            val after = (if (i == hits.lastIndex) length else hits[i + 1].seconds) - hit.seconds
            val cap = minOf(DRIFT_SECONDS, before * GAP_SHARE, after * GAP_SHARE)
            val t = (hit.seconds + h * cap * drift).coerceAtLeast(0.0)
            val beats = hit.seconds / beat
            val onBeat = kotlin.math.abs(beats - kotlin.math.round(beats)) < 1e-6
            val level = hit.velocity * (1f + h * LEVEL_SPREAD * spread) * (if (onBeat) 1f + h * BEAT_ACCENT else 1f)
            Hit(t, hit.drum, level.coerceIn(0f, 1f), hit.take)
        }
    }

    /** The render's length: the fill, then the landing's ring, and never under [MIN_RENDER_SECONDS]. */
    fun lengthSeconds(voice: FillVoice, macros: Map<String, Float> = emptyMap()): Double {
        val m = settled(voice, macros)
        return max(beats(m.getValue("LENGTH")) * 60.0 / bpm(m.getValue("TEMPO")) + TAIL_SECONDS, MIN_RENDER_SECONDS)
    }

    fun renderFrames(voice: FillVoice, macros: Map<String, Float> = emptyMap()): Int =
        (lengthSeconds(voice, macros) * RATE).roundToInt()

    /** LOOP when the render passes the classifier's 1.5 s, else PERC; never a drum class. Always LOOP today. */
    fun drumClassFor(voice: FillVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (renderFrames(voice, macros) > RATE * LOOP_MIN_SECONDS) DrumClass.LOOP else DrumClass.PERC

    /** Every control is fair game: every fill ends. */
    fun scramble(voice: FillVoice, random: Random = Random.Default): Map<String, Float> =
        defaults(voice).mapValues { random.nextFloat() }

    /**
     * The four toms as SKIN TUNE values. SKIN's tom fundamental is `expMap(TUNE, 90, 220)` Hz. The floor
     * tom runs 90 to 130 Hz across TUNE and each tom above it sits three semitones higher (shape: a run of
     * minor thirds), so the high tom stays inside SKIN's 220 Hz top.
     */
    internal fun tomTune(drum: Drum, tune: Float): Float {
        val floorHz = 90.0 * (130.0 / 90.0).pow(tune.toDouble())
        val above = when (drum) { Drum.TOM_FLOOR -> 0; Drum.TOM_LOW -> 3; Drum.TOM_MID -> 6; else -> 9 }
        val hz = floorHz * 2.0.pow(above / 12.0)
        return (ln(hz / 90.0) / ln(220.0 / 90.0)).toFloat().coerceIn(0f, 1f)
    }

    /** SKIN's snare TUNE: its own default (.4) at FILL's TUNE .5, moving .6 of FILL's travel (shape). */
    private fun snareTune(tune: Float) = (.4f + .6f * (tune - .5f)).coerceIn(0f, 1f)

    private fun hit(drum: Drum, tune: Float, velocity: Float, take: Int): FloatArray = when (drum) {
        Drum.SNARE -> {
            val struck = Skin.render(SkinVoice.SNARE, mapOf("TUNE" to snareTune(tune)))
            // The house soft snare: darker as it gets softer ([Velocity.soften] is peak-matched), then quieter.
            val soft = Velocity.soften(struck, 1f - velocity).samples
            for (i in soft.indices) soft[i] *= velocity
            soft
        }
        Drum.KICK -> Skin.render(SkinVoice.KICK, emptyMap(), velocity = velocity, take = take).samples
            .also { for (i in it.indices) it[i] *= velocity }
        else -> Skin.render(SkinVoice.TOM, mapOf("TUNE" to tomTune(drum, tune)), velocity = velocity, take = take).samples
            .also { for (i in it.indices) it[i] *= velocity }
    }

    fun render(voice: FillVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val m = settled(voice, macros)
        val plan = schedule(voice, m, velocity)
        val tune = m.getValue("TUNE")
        val out = FloatArray(renderFrames(voice, m))
        // A long run repeats the same drum at the same strength: render each (drum, step, take) once.
        val cache = HashMap<Triple<Drum, Int, Int>, FloatArray>()
        for (h in plan.hits) {
            val step = (h.velocity * VELOCITY_STEPS).roundToInt().coerceIn(1, VELOCITY_STEPS)
            val buf = cache.getOrPut(Triple(h.drum, step, h.take)) { hit(h.drum, tune, step.toFloat() / VELOCITY_STEPS, h.take) }
            val at = (h.seconds * RATE).roundToInt()
            for (i in buf.indices) {
                val j = at + i
                if (j >= out.size) break
                out[j] += buf[i]
            }
        }
        // One gain for the whole fill keeps the crescendo; the MPC's velocity curve owns the pad's level.
        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
