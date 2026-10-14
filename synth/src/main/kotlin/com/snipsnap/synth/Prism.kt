package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import java.util.concurrent.CancellationException
import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Original harmonic recipes; these IDs are part of a saved patch, not imported instrument data. */
enum class PrismSpectrum { ROUNDED, PLUCKED, DRAWBAR, ROOTED, BELL, VEILED }
enum class PrismContour { STRUCK, PLUCKED, HELD, LOW, RINGING, BLOOM }
enum class PrismVoice { KEY, PLUCK, ORGAN, BASS, GLASS, VEIL }

/** A group's full-DRIFT behavior. At DRIFT zero its timing rejoins the other groups. */
data class PrismGroupProfile(
    val attackScale: Float = 1f,
    val decayScale: Float = 1f,
    val delaySeconds: Float = 0f,
    val motionCycles: Int = 1,
    val motionDepth: Float = 1f,
) {
    init {
        require(attackScale.isFinite() && attackScale in .2f..5f) { "PRISM group attackScale must be .2..5" }
        require(decayScale.isFinite() && decayScale in .15f..3f) { "PRISM group decayScale must be .15..3" }
        require(delaySeconds.isFinite() && delaySeconds in 0f..1f) { "PRISM group delaySeconds must be 0..1" }
        require(motionCycles in 1..6) { "PRISM group motionCycles must be 1..6" }
        require(motionDepth.isFinite() && motionDepth in 0f..1f) { "PRISM group motionDepth must be 0..1" }
    }
}

/**
 * Bounded additive model carried by a patch. Empty overrides retain the selected template;
 * nonempty partial lists override their leading entries, leaving the remaining entries intact.
 * Five optional group profiles cover partials 1–2, 3–5, 6–10, 11–18 and 19–32.
 */
class PrismTemplate(
    val spectralId: PrismSpectrum,
    val envelopeId: PrismContour,
    ratioOverrides: List<Float> = emptyList(),
    amplitudeOverrides: List<Float> = emptyList(),
    groupProfiles: List<PrismGroupProfile> = emptyList(),
    val attackSeconds: Float? = null,
    val decaySeconds: Float? = null,
    val loopSeconds: Float = 2f,
) {
    // Saved model data is immutable even when a caller supplies mutable builder lists.
    val ratioOverrides: List<Float> = Collections.unmodifiableList(ratioOverrides.toList())
    val amplitudeOverrides: List<Float> = Collections.unmodifiableList(amplitudeOverrides.toList())
    val groupProfiles: List<PrismGroupProfile> = Collections.unmodifiableList(groupProfiles.toList())

    init {
        require(ratioOverrides.size <= Prism.PARTIALS && ratioOverrides.all { it.isFinite() && it in 1f..64f }) {
            "PRISM ratios must contain at most 32 finite ratios in 1..64"
        }
        require(ratioOverrides.isEmpty() || ratioOverrides.first() == 1f) { "PRISM's first partial must preserve the root" }
        require(spectralId == PrismSpectrum.BELL || ratioOverrides.all { it == it.roundToInt().toFloat() }) {
            "PRISM's ordinary spectra use integer harmonics; an inharmonic map requires BELL"
        }
        require(amplitudeOverrides.size <= Prism.PARTIALS && amplitudeOverrides.all { it.isFinite() && it in 0f..2f }) {
            "PRISM amplitudes must contain at most 32 finite weights in 0..2"
        }
        require(amplitudeOverrides.isEmpty() || amplitudeOverrides.first() >= .05f) { "PRISM's root amplitude must be at least .05" }
        require(groupProfiles.isEmpty() || groupProfiles.size == Prism.GROUPS) { "PRISM requires five group profiles" }
        require(attackSeconds == null || attackSeconds.isFinite() && attackSeconds in .002f..1f) { "PRISM attack must be .002..1 seconds" }
        require(decaySeconds == null || decaySeconds.isFinite() && decaySeconds in .3f..6f) { "PRISM decay must be .3..6 seconds" }
        require(loopSeconds.isFinite() && loopSeconds in 1f..4f) { "PRISM loop must be 1..4 seconds" }
    }

    fun copy(
        spectralId: PrismSpectrum = this.spectralId,
        envelopeId: PrismContour = this.envelopeId,
        ratioOverrides: List<Float> = this.ratioOverrides,
        amplitudeOverrides: List<Float> = this.amplitudeOverrides,
        groupProfiles: List<PrismGroupProfile> = this.groupProfiles,
        attackSeconds: Float? = this.attackSeconds,
        decaySeconds: Float? = this.decaySeconds,
        loopSeconds: Float = this.loopSeconds,
    ): PrismTemplate = PrismTemplate(spectralId, envelopeId, ratioOverrides, amplitudeOverrides, groupProfiles, attackSeconds, decaySeconds, loopSeconds)

    override fun equals(other: Any?): Boolean = other is PrismTemplate && spectralId == other.spectralId &&
        envelopeId == other.envelopeId && ratioOverrides == other.ratioOverrides &&
        amplitudeOverrides == other.amplitudeOverrides && groupProfiles == other.groupProfiles &&
        attackSeconds == other.attackSeconds && decaySeconds == other.decaySeconds && loopSeconds == other.loopSeconds

    override fun hashCode(): Int = listOf(spectralId, envelopeId, ratioOverrides, amplitudeOverrides,
        groupProfiles, attackSeconds, decaySeconds, loopSeconds).hashCode()

    override fun toString(): String = "PrismTemplate($spectralId,$envelopeId,$ratioOverrides,$amplitudeOverrides,$groupProfiles,$attackSeconds,$decaySeconds,$loopSeconds)"
}

/**
 * PRISM — 32 phase-continuous sinusoids whose five spectral groups develop independently.
 * GRAIN reorganizes the same harmonics; SHAPE applies an interpolated log-frequency mask;
 * BODY changes their balance and loss. DRIFT moves group contours, never the note's pitch.
 * Everything is synthesized afresh at 4×, including the held region; no audio is crossfaded
 * to manufacture a seam and no random process runs between notes.
 */
object Prism {
    const val MODEL_VERSION = 1
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val PARTIALS = 32
    const val GROUPS = 5
    const val LOOP_THRESHOLD = .975f
    const val HOLD_LOOP = LOOP_THRESHOLD
    const val MAX_FINITE_SECONDS = 8f
    const val USABLE_BANDWIDTH_HZ = 18_000f
    const val RAW_PEAK_CEILING = .82f
    private const val BLOCK = 64
    private const val LOG_1000 = 6.907755278982137
    private const val TWO_PI = 2.0 * PI

    fun rootMidi(voice: PrismVoice): Int = when (voice) {
        PrismVoice.KEY, PrismVoice.PLUCK, PrismVoice.VEIL -> ROOT_MIDI
        PrismVoice.ORGAN -> 36
        PrismVoice.BASS -> 24
        PrismVoice.GLASS -> 60
    }

    fun midiFor(voice: PrismVoice, tune: Float): Int =
        rootMidi(voice) + ((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()

    fun frequencyFor(voice: PrismVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD

    fun macrosFor(voice: PrismVoice): List<MacroSpec> {
        val values = when (voice) {
            PrismVoice.KEY -> floatArrayOf(.30f, .30f, .40f, .45f, .60f)
            PrismVoice.PLUCK -> floatArrayOf(.40f, .25f, .45f, .65f, .45f)
            PrismVoice.ORGAN -> floatArrayOf(.25f, .20f, .35f, .30f, .65f)
            PrismVoice.BASS -> floatArrayOf(.20f, .20f, .30f, .40f, .80f)
            PrismVoice.GLASS -> floatArrayOf(.65f, .45f, .60f, .50f, .30f)
            PrismVoice.VEIL -> floatArrayOf(.45f, .65f, .50f, .20f, .55f)
        }
        return listOf(
            MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("GRAIN", values[0], neutral = .30f),
            MacroSpec("DRIFT", values[1], neutral = .20f),
            MacroSpec("SHAPE", values[2], neutral = .40f),
            MacroSpec("STRIKE", values[3], neutral = .40f),
            MacroSpec("BODY", values[4], neutral = .50f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    fun defaults(voice: PrismVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: PrismVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (k, default) -> (macros[k]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    fun templateFor(voice: PrismVoice): PrismTemplate = when (voice) {
        PrismVoice.KEY -> PrismTemplate(PrismSpectrum.ROUNDED, PrismContour.STRUCK)
        PrismVoice.PLUCK -> PrismTemplate(PrismSpectrum.PLUCKED, PrismContour.PLUCKED)
        PrismVoice.ORGAN -> PrismTemplate(PrismSpectrum.DRAWBAR, PrismContour.HELD)
        PrismVoice.BASS -> PrismTemplate(PrismSpectrum.ROOTED, PrismContour.LOW)
        PrismVoice.GLASS -> softGlassTemplate
        PrismVoice.VEIL -> PrismTemplate(PrismSpectrum.VEILED, PrismContour.BLOOM)
    }

    /**
     * The factory glass carries a harmonic lower register and a quiet upper shimmer. The complete
     * arrays are intentional: an empty BELL template keeps its original ratios and amplitudes, so
     * saved/custom models and the first audition remain exactly reproducible after calibration.
     */
    private val softGlassTemplate: PrismTemplate by lazy {
        val ratios = List(PARTIALS) { i ->
            val harmonic = i + 1f
            if (i < 8) harmonic else harmonic + when (i % 4) {
                0 -> .25f
                1 -> .50f
                2 -> .75f
                else -> 0f
            }
        }
        val amplitudes = List(PARTIALS) { i ->
            val harmonic = i + 1.0
            val taper = when (i) {
                0 -> 1.0
                in 1..3 -> .92
                in 4..7 -> .68
                else -> .30 * exp(-.085 * (harmonic - 9.0))
            }
            (templateAmplitude(PrismSpectrum.BELL, i) * taper).toFloat()
        }
        PrismTemplate(PrismSpectrum.BELL, PrismContour.RINGING,
            ratioOverrides = ratios, amplitudeOverrides = amplitudes)
    }

    fun drumClassFor(voice: PrismVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    /** Scrambling stays finite. A sustained export is a deliberate HOLD choice. */
    fun scramble(voice: PrismVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = base + (near?.macros ?: emptyMap()).filterKeys { it in base }
        return Dsp.scrambleNear(seed, temperature, random) + ("HOLD" to 0f)
    }

    private fun groupFor(index: Int): Int = when (index) {
        in 0..1 -> 0
        in 2..4 -> 1
        in 5..9 -> 2
        in 10..17 -> 3
        else -> 4
    }

    private fun profiles(contour: PrismContour): List<PrismGroupProfile> {
        val decay = when (contour) {
            PrismContour.STRUCK -> floatArrayOf(1f, .91f, .76f, .59f, .38f)
            PrismContour.PLUCKED -> floatArrayOf(1f, .83f, .64f, .43f, .24f)
            PrismContour.HELD -> floatArrayOf(1f, 1.03f, .92f, .83f, .67f)
            PrismContour.LOW -> floatArrayOf(1f, .87f, .70f, .54f, .36f)
            PrismContour.RINGING -> floatArrayOf(1f, 1.13f, .88f, 1.02f, .59f)
            PrismContour.BLOOM -> floatArrayOf(1f, 1.10f, 1.24f, .90f, .72f)
        }
        val attack = floatArrayOf(1f, 1.45f, 2.7f, 3.7f, .55f)
        val delay = floatArrayOf(0f, .022f, .075f, .15f, .006f)
        // The low anchor gains a little lifetime as the groups separate. Keeping its decay
        // unchanged made DRIFT too weak on root-heavy keys, organs and bass in the first note.
        decay[0] = 1.17f
        return List(GROUPS) { g ->
            PrismGroupProfile(attack[g], decay[g], delay[g], 1 + g % 3, if (g == 0) .12f else 1f)
        }
    }

    private fun baseAttack(contour: PrismContour): Float = when (contour) {
        PrismContour.STRUCK -> .018f
        PrismContour.PLUCKED -> .009f
        PrismContour.HELD -> .026f
        PrismContour.LOW -> .018f
        PrismContour.RINGING -> .014f
        PrismContour.BLOOM -> .105f
    }

    private fun baseDecay(contour: PrismContour): Float = when (contour) {
        PrismContour.STRUCK -> 1.85f
        PrismContour.PLUCKED -> 1.15f
        PrismContour.HELD -> 3.1f
        PrismContour.LOW -> 2.05f
        PrismContour.RINGING -> 2.9f
        PrismContour.BLOOM -> 3.7f
    }

    /** Explicit rational bell map: quarter-harmonic spacing, root intact, maximum ratio 45. */
    private val BELL_RATIOS = floatArrayOf(
        1f, 2f, 2.75f, 4f, 5.5f, 6.75f, 8f, 9.5f,
        11f, 12.75f, 14f, 15.5f, 17f, 18.25f, 19.75f, 21f,
        22.5f, 24f, 25.25f, 26.75f, 28f, 29.5f, 31f, 32.25f,
        33.75f, 35f, 36.5f, 38f, 39.25f, 40.75f, 42f, 43.5f,
    )

    private fun templateAmplitude(id: PrismSpectrum, index: Int): Double {
        val n = index + 1.0
        if (index == 0) return if (id == PrismSpectrum.ROOTED) 1.3 else 1.0
        return when (id) {
            PrismSpectrum.ROUNDED -> n.pow(-1.18) * (1.0 + .36 * gaussian(ln(n / 4.5), .48))
            PrismSpectrum.PLUCKED -> n.pow(-.93) * (.48 + .52 * sin(n * .83).pow(2))
            PrismSpectrum.DRAWBAR -> if (index < 9) doubleArrayOf(1.0, .74, .55, .45, .28, .36, .17, .21, .12)[index] else .50 * n.pow(-1.28)
            PrismSpectrum.ROOTED -> 1.35 * n.pow(-1.28) * if (index % 2 == 0) 1.18 else .88
            PrismSpectrum.BELL -> n.pow(-1.02) * (.45 + .55 * cos(n * 1.12).pow(2))
            PrismSpectrum.VEILED -> n.pow(-1.04) * (.75 + .90 * gaussian(ln(n / 6.0), .64))
        }
    }

    private fun gaussian(distance: Double, width: Double): Double = exp(-.5 * (distance / width).pow(2))
    private fun mix(a: Double, b: Double, x: Double): Double = a + (b - a) * x
    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    /** Four original broad envelopes, sampled in log frequency and blended with harmonic-relative features. */
    private fun shapeMask(hz: Double, ratio: Double, shape: Double): Double {
        val absolute = ln(hz / 440.0) / ln(2.0)
        val relative = ln(ratio) / ln(2.0)
        // Register-adaptive width prevents a narrow absolute peak becoming an on/off switch on high keys.
        val width = 1.20 + .24 * (absolute - 1.0).coerceAtLeast(0.0)
        val rounded = .28 + .88 * gaussian(absolute + .25, width)
        val hollow = .98 - .73 * gaussian(absolute - 1.10, .72 * width)
        val nasal = .25 + 1.06 * gaussian(absolute - 1.68, .49 * width)
        val open = .36 + .80 / (1.0 + exp(-(absolute - .20) * 1.5))
        val masks = doubleArrayOf(rounded, hollow, nasal, open)
        val position = shape * 3.0
        val k = position.toInt().coerceAtMost(2)
        val absoluteMask = mix(masks[k], masks[k + 1], smooth(position - k))
        val relativeMasks = doubleArrayOf(
            .34 + .81 * gaussian(relative - .55, 1.8),
            .98 - .71 * gaussian(relative - 2.0, .68),
            .30 + 1.0 * gaussian(relative - 2.65, .73),
            .40 + .82 / (1.0 + exp(-(relative - 1.65) * 1.7)),
        )
        val relativeMask = mix(relativeMasks[k], relativeMasks[k + 1], smooth(position - k))
        return mix(relativeMask, absoluteMask, .42).coerceIn(.18, 1.35)
    }

    private data class PartialBank(val hz: DoubleArray, val weights: FloatArray, val groups: IntArray)

    private fun bank(template: PrismTemplate, hz: Double, m: Map<String, Float>, velocity: Float, loop: LoopPlan?): PartialBank {
        val grain = m.getValue("GRAIN").toDouble()
        val body = m.getValue("BODY").toDouble()
        val shape = m.getValue("SHAPE").toDouble()
        val strike = m.getValue("STRIKE").toDouble()
        val frequencies = ArrayList<Double>()
        val original = ArrayList<Double>()
        val masked = ArrayList<Double>()
        val groups = ArrayList<Int>()
        for (i in 0 until PARTIALS) {
            val ratio = template.ratioOverrides.getOrNull(i)?.toDouble()
                ?: if (template.spectralId == PrismSpectrum.BELL) BELL_RATIOS[i].toDouble() else i + 1.0
            val requested = hz * ratio
            // Envelope sidebands are only a few Hz wide; keep an explicit margin below output Nyquist.
            if (requested >= USABLE_BANDWIDTH_HZ) continue
            val f = if (loop == null) requested else (ratio * loop.periods).roundToInt() * RATE.toDouble() / loop.frames
            if (f >= USABLE_BANDWIDTH_HZ) continue
            val group = groupFor(i)
            val bodyWeight = when (group) {
                0 -> mix(.54, 1.38, body)
                1 -> mix(.65, 1.35, body)
                2 -> mix(.90, 1.15, body)
                3 -> mix(1.12, .68, body)
                else -> mix(1.26, .47, body)
            }
            val excitation = if (group == 0) 1.0 else mix(.45, 1.55, strike) * mix(.45, 1.0, velocity.toDouble())
            val a = (template.amplitudeOverrides.getOrNull(i)?.toDouble() ?: templateAmplitude(template.spectralId, i)) *
                bodyWeight * excitation * shapeMask(f, ratio, shape)
            // Separated broad clusters and alternating emphasis, both continuous in GRAIN.
            val logN = ln(i + 1.0)
            val clusters = max(gaussian(logN - ln(3.0), .18), max(gaussian(logN - ln(8.0), .20), gaussian(logN - ln(20.0), .17)))
            val alternating = .5 + .5 * cos(PI * i + shape * 1.1)
            val organization = (.62 * clusters + .38 * alternating).coerceIn(0.0, 1.0)
            val floor = if (i == 0) .82 else if (group <= 1) .16 + .30 * body else .10 + .08 * body
            val grainMask = mix(1.0, floor + (1.0 - floor) * organization, smooth(grain))
            frequencies += f
            original += a
            masked += a * grainMask
            groups += group
        }
        // The zero-GRAIN recipe is the energy and headroom reference, including its anchor floor.
        // A current-spectrum L1-only trim would undo the energy correction and make sparse masks louder.
        if (original.isNotEmpty()) {
            val oldRoot = original[0]
            original[0] = max(oldRoot, (original.drop(1).maxOrNull() ?: 0.0) * 1.12)
            masked[0] *= original[0] / oldRoot.coerceAtLeast(1e-20)
        }
        // Normalize with the actual spectrum rather than the number of active bands. Limit the
        // correction so a sparse patch cannot collect the discarded energy into one hot partial.
        val energy = original.sumOf { it * it }
        val sparseEnergy = masked.sumOf { it * it }
        val correction = sqrt(energy / sparseEnergy.coerceAtLeast(1e-20)).coerceAtMost(1.35)
        for (i in masked.indices) masked[i] *= correction
        // The anchor remains largest, and never exceeds the zero-GRAIN anchor. Protecting pitch
        // cannot be allowed to silently add energy after the mask's energy measurement.
        if (masked.isNotEmpty()) {
            masked[0] = max(masked[0], (masked.drop(1).maxOrNull() ?: 0.0) * 1.12).coerceAtMost(original[0])
            for (i in 1 until masked.size) masked[i] = masked[i].coerceAtMost(masked[0] / 1.12)
        }
        val anchoredEnergy = masked.sumOf { it * it }
        val energyBound = sqrt(energy / anchoredEnergy.coerceAtLeast(1e-20)).coerceAtMost(1.0)
        for (i in masked.indices) masked[i] *= energyBound
        // An L1 bound includes the largest periodic contour (1.35), including coherent phases.
        val trim = RAW_PEAK_CEILING / (1.35 * max(original.sum(), masked.sum()).coerceAtLeast(1e-20))
        val excitationLevel = velocity.toDouble().pow(1.10)
        return PartialBank(frequencies.toDoubleArray(), FloatArray(masked.size) { (masked[it] * trim * excitationLevel).toFloat() }, groups.toIntArray())
    }

    private data class Contours(val attack: DoubleArray, val decay: DoubleArray, val delay: DoubleArray, val hold: Double, val profiles: List<PrismGroupProfile>, val seconds: Double)

    private fun contours(template: PrismTemplate, m: Map<String, Float>): Contours {
        val p = template.groupProfiles.ifEmpty { profiles(template.envelopeId) }
        val drift = m.getValue("DRIFT").toDouble()
        val strike = m.getValue("STRIKE").toDouble()
        val body = m.getValue("BODY").toDouble()
        val shape = m.getValue("SHAPE").toDouble()
        val hold = m.getValue("HOLD").toDouble().coerceAtMost(LOOP_THRESHOLD.toDouble()) * 1.35
        val attackBase = (template.attackSeconds ?: baseAttack(template.envelopeId)) * mix(2.8, .28, strike)
        val decayBase = (template.decaySeconds ?: baseDecay(template.envelopeId)) * mix(.70, 1.27, body)
        val attack = DoubleArray(GROUPS) { g ->
            (attackBase * mix(1.0, p[g].attackScale.toDouble(), drift) * (1.0 + drift * shape * g * .08)).coerceIn(.0015, 1.25)
        }
        val delay = DoubleArray(GROUPS) { g -> p[g].delaySeconds * drift * (1.0 - .55 * strike) * (1.0 + .45 * shape) }
        val decay = DoubleArray(GROUPS) { g ->
            val upperLoss = if (g >= 2) mix(.77, 1.09, body) else 1.0
            (decayBase * mix(1.0, p[g].decayScale.toDouble(), drift) * upperLoss)
                .coerceIn(.25, (MAX_FINITE_SECONDS - hold - attack[g] - delay[g] - .12) / 1.12)
        }
        val seconds = (0 until GROUPS).maxOf { delay[it] + attack[it] + hold + decay[it] * 1.12 } + .06
        return Contours(attack, decay, delay, hold, p, seconds.coerceAtMost(MAX_FINITE_SECONDS.toDouble()))
    }

    private fun envelope(t: Double, group: Int, c: Contours, m: Map<String, Float>, loop: LoopPlan?): Double {
        val drift = m.getValue("DRIFT").toDouble()
        val shape = m.getValue("SHAPE").toDouble()
        if (loop != null) {
            val profile = c.profiles[group]
            val phase = TWO_PI * profile.motionCycles * t * RATE / loop.frames + group * 1.31 + drift * group * .70
            val depth = .29 * drift * profile.motionDepth * (.72 + .28 * shape)
            return 1.0 + depth * sin(phase) + .035 * drift * profile.motionDepth * cos(2.0 * phase + shape)
        }
        val local = t - c.delay[group]
        if (local <= 0.0) return 0.0
        val onset = smooth(local / c.attack[group])
        val decayAt = (local - c.attack[group] * .65 - c.hold).coerceAtLeast(0.0)
        val loss = exp(-LOG_1000 * decayAt / c.decay[group])
        // Middle and upper body collect a delayed bloom, already moving during a normal short note.
        val gather = if (group in 1..3) 1.0 - .33 * drift * (1.0 - smooth(local / (.10 + group * .105 + shape * .14))) else 1.0
        // STRIKE's bright contact recedes before the body, independently of group decay.
        val transient = if (group >= 3) 1.0 - .50 * m.getValue("STRIKE") * (1.0 - exp(-local / .075)) else 1.0
        return onset * loss * gather * transient
    }

    internal data class LoopPlan(val frames: Int, val periods: Int, val baseHz: Double)

    // SynthScreen previews run in runInterruptible and keygroup exports pass a cancel
    // callback; either stops a long render instead of letting it finish unwanted.
    private fun stopIfUnwanted(cancelled: () -> Boolean = { false }) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("PRISM render no longer wanted")
    }

    /** Search whole note periods near the patch duration; fit frames to the pitch with <.1 cent movement. */
    internal fun planLoop(hz: Double, template: PrismTemplate): LoopPlan {
        val step = if (template.spectralId == PrismSpectrum.BELL && template.ratioOverrides.isEmpty()) 4 else 1
        val center = (template.loopSeconds * hz / step).roundToInt().coerceAtLeast(1)
        var best: LoopPlan? = null
        var bestError = Double.POSITIVE_INFINITY
        for (j in (center - 32).coerceAtLeast(1)..center + 32) {
            val periods = j * step
            // 16 output frames = one internal envelope block; the control interpolation wraps exactly.
            val frames = ((periods * RATE / hz / (BLOCK / Dsp.OVERSAMPLE)).roundToInt() * (BLOCK / Dsp.OVERSAMPLE)).coerceAtLeast(1024)
            val fitted = periods * RATE.toDouble() / frames
            val cents = abs(1200.0 * ln(fitted / hz) / ln(2.0))
            val durationDistance = abs(frames.toDouble() / RATE - template.loopSeconds)
            val score = cents + .0002 * durationDistance
            if (score < bestError) {
                bestError = score
                best = LoopPlan(frames, periods, fitted)
            }
        }
        return requireNotNull(best)
    }

    private fun synthesize(
        voice: PrismVoice,
        m: Map<String, Float>,
        template: PrismTemplate,
        partials: PartialBank,
        c: Contours,
        frames: Int,
        loop: LoopPlan?,
        cancelled: () -> Boolean,
    ): FloatArray {
        val rate = RATE * Dsp.OVERSAMPLE
        val raw = FloatArray(frames * Dsp.OVERSAMPLE)
        val seed = Dsp.seedFor("PRISM", MODEL_VERSION, voice.name, midiFor(voice, m.getValue("TUNE")), template)
        val phases = Dsp.phases(partials.hz.size, seed)
        val oscillatorSin = DoubleArray(phases.size) { sin(TWO_PI * phases[it]) }
        val oscillatorCos = DoubleArray(phases.size) { cos(TWO_PI * phases[it]) }
        val stepSin = DoubleArray(phases.size) { sin(TWO_PI * partials.hz[it] / rate) }
        val stepCos = DoubleArray(phases.size) { cos(TWO_PI * partials.hz[it] / rate) }
        val current = DoubleArray(GROUPS)
        val increment = DoubleArray(GROUPS)
        for (i in raw.indices) {
            if (i % BLOCK == 0) {
                stopIfUnwanted(cancelled)
                val t = i.toDouble() / rate
                for (g in 0 until GROUPS) {
                    current[g] = envelope(t, g, c, m, loop)
                    increment[g] = (envelope((i + BLOCK).toDouble() / rate, g, c, m, loop) - current[g]) / BLOCK
                }
                // Exact rephasing bounds recursive oscillator roundoff without resetting any envelope.
                for (p in phases.indices) {
                    val phase = TWO_PI * (phases[p] + partials.hz[p] * t)
                    oscillatorSin[p] = sin(phase)
                    oscillatorCos[p] = cos(phase)
                }
            }
            var sample = 0.0
            for (p in phases.indices) {
                sample += partials.weights[p] * current[partials.groups[p]] * oscillatorSin[p]
                val s = oscillatorSin[p] * stepCos[p] + oscillatorCos[p] * stepSin[p]
                oscillatorCos[p] = oscillatorCos[p] * stepCos[p] - oscillatorSin[p] * stepSin[p]
                oscillatorSin[p] = s
            }
            raw[i] = sample.toFloat()
            for (g in 0 until GROUPS) current[g] += increment[g]
        }
        return raw
    }

    /** Pre-level evidence: genuine adjacent held cycles, precondition 4× samples, and the actual partial bank.
     * [raw] is empty when [probe] was asked not to keep it. */
    internal data class Probe(
        val samples: FloatArray,
        val raw: FloatArray,
        val partialHz: DoubleArray,
        val partialWeights: FloatArray,
        val groupIndices: IntArray,
        val effectiveHz: Double,
        val loopFrames: Int = 0,
        val previousCycle: FloatArray? = null,
        val seamError: Double = 0.0,
    )

    internal fun probe(
        voice: PrismVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        template: PrismTemplate = templateFor(voice),
        normalize: Boolean = false,
        cancelled: () -> Boolean = { false },
        seconds: Float? = null,
        keepRaw: Boolean = true,
    ): Probe {
        val m = settled(voice, macros)
        val v = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val loop = if (isLoop(m.getValue("HOLD"))) planLoop(hz, template) else null
        val bank = bank(template, loop?.baseHz ?: hz, m, v, loop)
        val contours = contours(template, m)
        val finiteSeconds = seconds?.takeIf { it.isFinite() && it > 0f }?.toDouble()?.coerceAtMost(contours.seconds) ?: contours.seconds
        // Three genuine cycles: a bounded preroll, an independent evidence cycle, and the exported cycle.
        // A small postroll keeps the decimator's edge outside the exported loop.
        val frames = loop?.let { 3 * it.frames + 512 } ?: (finiteSeconds * RATE).roundToInt().coerceAtLeast(64)
        val raw = synthesize(voice, m, template, bank, contours, frames, loop, cancelled)
        stopIfUnwanted(cancelled)
        // Only a test reads the unconditioned 4x sum back; a render conditions it in place.
        val conditioned = if (keepRaw) raw.copyOf() else raw
        Tide.bandLimit(conditioned, RATE * Dsp.OVERSAMPLE)
        stopIfUnwanted(cancelled)
        val down = Dsp.decimate(conditioned, RATE)
        val out: FloatArray
        var previous: FloatArray? = null
        var seam = 0.0
        if (loop != null) {
            previous = down.copyOfRange(loop.frames, 2 * loop.frames)
            out = down.copyOfRange(2 * loop.frames, 3 * loop.frames)
            // One common DC offset preserves the relationship of the two independently generated cycles.
            val mean = out.sumOf { it.toDouble() } / out.size
            for (i in out.indices) { out[i] = (out[i] - mean).toFloat(); previous[i] = (previous[i] - mean).toFloat() }
            seam = if (v == 0f) 0.0 else Keys.seamError(previous + out, loop.frames)
            require(v == 0f || seam < Keys.MAX_SEAM_ERROR) { "PRISM $voice: held carrier/envelope seam $seam exceeds ${Keys.MAX_SEAM_ERROR}" }
        } else {
            out = down
            val mean = out.sumOf { it.toDouble() } / out.size
            val hp = Dsp.OnePole(RATE)
            for (i in out.indices) {
                val x = (out[i] - mean).toFloat()
                out[i] = x - hp.lp(x, 8f)
            }
            // Only full notes receive a release fade; an early-window evidence probe is not a truncated note.
            if (seconds == null || finiteSeconds >= contours.seconds) Dsp.fadeTail(out)
        }
        if (normalize && v > 0f) {
            // Match timbres to the shared melodic reference while preserving performance dynamics.
            // Use the same gain on the preceding cycle so the measured seam remains meaningful.
            val unlevel = if (previous != null) out.copyOf() else null
            stopIfUnwanted(cancelled)
            Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET * v.pow(.65f))
            if (previous != null && unlevel != null) {
                val reference = unlevel.indices.maxByOrNull { abs(unlevel[it]) } ?: 0
                val gain = if (abs(unlevel[reference]) > 1e-20f) out[reference] / unlevel[reference] else 1f
                for (i in previous.indices) previous[i] *= gain
            }
        }
        return Probe(out, if (keepRaw) raw else FloatArray(0), bank.hz, bank.weights, bank.groups, loop?.baseHz ?: hz, loop?.frames ?: 0, previous, seam)
    }

    fun render(
        voice: PrismVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        template: PrismTemplate = templateFor(voice),
    ): Snip = Snip(probe(voice, macros, velocity, template, normalize = true, keepRaw = false).samples, channels = 1, sampleRate = RATE)

    fun renderAtMidi(
        voice: PrismVoice,
        macros: Map<String, Float>,
        midi: Int,
        velocity: Float = 1f,
        template: PrismTemplate = templateFor(voice),
    ): Snip {
        require(midi - rootMidi(voice) in 0..TUNE_SEMITONES) { "PRISM $voice supports MIDI ${rootMidi(voice)}..${rootMidi(voice) + TUNE_SEMITONES}, got $midi" }
        return render(voice, macros + ("TUNE" to (midi - rootMidi(voice)) / TUNE_SEMITONES.toFloat()), velocity, template)
    }

    /** Loop-only export: the settled harmonic body and periodic group development, without a strike. */
    fun renderLoop(
        voice: PrismVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        template: PrismTemplate = templateFor(voice),
        cancelled: () -> Boolean = { false },
    ): FloatArray = probe(voice, macros + ("HOLD" to 1f), velocity, template, normalize = true, cancelled = cancelled, keepRaw = false).samples
}
