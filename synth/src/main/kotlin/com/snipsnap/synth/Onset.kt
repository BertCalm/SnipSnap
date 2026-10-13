package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** The bounded first listening experiment, independent of the Android engine picker. */
enum class OnsetVoice { KICK, HAT_CLOSED }

/** Resolved physical settings accompany every audition render for reproducibility. */
data class OnsetSettings(
    val modelVersion: Int,
    val voice: OnsetVoice,
    val macros: Map<String, Float>,
    val rootHz: Float?,
    val transpositionSemitones: Float,
    val electronicRootHz: Float,
    val physicalRootHz: Float,
    val t60Seconds: Float,
    val electronicGain: Float,
    val physicalGain: Float,
    val outputGain: Float,
    val electronicSource: String = "THUMP",
    val physicalSource: String = "SKIN",
    val electronicModel: String = "THUMP raw oscillator/envelope source",
    val physicalModel: String = "SKIN raw modal/resonant-noise source",
)

/**
 * ONSET blends two unfinished sources on the same clock, followed by one transient and finish.
 * Kicks share an actual fundamental, pitch-drop trajectory and decay. Hats have no single
 * fundamental: both natural source tunings receive the same semitone shift and share the envelope's T60. Stock THUMP and
 * SKIN patches keep their original voicing, normalization and PUNCH behavior.
 *
 * Source gains and output headroom are fixed per voice, independent of BLEND and the rendered
 * patch's peak. Equal-power blending is a starting curve, not a claim of constant loudness:
 * correlated fundamentals can reinforce and different spectra can sound unequally loud.
 */
object Onset {
    const val MODEL_VERSION = 2
    // A model revision must not recolor the electronic endpoint or either hat source.
    private const val TRANSIENT_SEED_VERSION = 1
    const val RATE = Dsp.RATE
    private const val RENDER_RATE = RATE * Dsp.OVERSAMPLE

    fun macrosFor(voice: OnsetVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", if (voice == OnsetVoice.KICK) 0.35f else 0.5f, 0.5f),
        MacroSpec("DECAY", if (voice == OnsetVoice.KICK) 0.45f else 0.3f),
        MacroSpec("BLEND", 0.5f),
        MacroSpec("SNAP", if (voice == OnsetVoice.KICK) 0.5f else 0.35f),
        MacroSpec("BODY", if (voice == OnsetVoice.KICK) 0.6f else 0.45f),
        MacroSpec("EDGE", 0.5f),
    )

    fun defaults(voice: OnsetVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun settingsFor(voice: OnsetVoice, macros: Map<String, Float> = emptyMap(), modelVersion: Int = MODEL_VERSION): OnsetSettings {
        require(modelVersion in 1..MODEL_VERSION) { "unsupported ONSET model version: $modelVersion" }
        val m = defaults(voice).toMutableMap()
        for ((name, value) in macros) if (name in m && value.isFinite()) m[name] = value.coerceIn(0f, 1f)
        val tune = m.getValue("TUNE")
        val kick = voice == OnsetVoice.KICK
        val root = if (kick) Dsp.expMap(tune, 45f, 62f) else null
        val semitones = if (kick) 0f else Dsp.lin(tune, -4f, 4f)
        val transpose = 2f.pow(semitones / 12f)
        return OnsetSettings(
            modelVersion, voice, m.toMap(), root, semitones,
            root ?: 445f * transpose, root ?: 6500f * transpose,
            if (kick) Dsp.expMap(m.getValue("DECAY"), 0.12f, 0.85f)
                else Dsp.expMap(m.getValue("DECAY"), 0.04f, 0.16f),
            // V1 calibration for X and hats stays frozen: at 44.1 kHz, hat source RMS
            // X=.01136/O=.05670. V2's stronger membrane gets its own fixed gain, checked
            // for endpoint level and headroom at every five-control corner/all five blends.
            // These are never recomputed from an individual patch or BLEND position.
            electronicGain = if (kick) 1f else 5f,
            physicalGain = if (kick) { if (modelVersion == 1) 1.25f else 0.6f } else 1f,
            outputGain = if (kick) 0.32f else 0.52f,
            physicalSource = if (kick && modelVersion >= 2) "ONSET" else "SKIN",
            physicalModel = if (kick && modelVersion >= 2)
                "ONSET contact-projected circular membrane with independent modal losses"
                else "SKIN raw modal/resonant-noise source",
        )
    }

    internal data class Probe(
        val settings: OnsetSettings,
        val electronic: FloatArray,
        val physical: FloatArray,
        val transient: FloatArray,
        val mixed: FloatArray,
        /** Isolated modal fundamental, for phase checks that do not forbid material structure. */
        val physicalFundamental: FloatArray? = null,
    )

    /** Calibrated source taps are deliberately before shelf, PUNCH and decimation. */
    internal fun probe(voice: OnsetVoice, macros: Map<String, Float> = emptyMap(), modelVersion: Int = MODEL_VERSION): Probe {
        val s = settingsFor(voice, macros, modelVersion)
        val m = s.macros
        val electronic = when (voice) {
            OnsetVoice.KICK -> Thump.onsetKick(s.electronicRootHz, s.t60Seconds,
                m.getValue("SNAP"), m.getValue("EDGE"), RENDER_RATE)
            OnsetVoice.HAT_CLOSED -> Thump.onsetHat(s.electronicRootHz, s.t60Seconds,
                m.getValue("EDGE"), RENDER_RATE)
        }
        val material = if (voice == OnsetVoice.KICK) kickMaterial(s) else null
        val physical = when (voice) {
            OnsetVoice.KICK -> if (modelVersion == 1) Skin.onsetKick(s.physicalRootHz, s.t60Seconds,
                m.getValue("SNAP"), m.getValue("BODY"), m.getValue("EDGE"), RENDER_RATE)
                else material!!.samples
            OnsetVoice.HAT_CLOSED -> Skin.onsetHat(s.physicalRootHz, s.t60Seconds,
                m.getValue("BODY"), m.getValue("EDGE"), RENDER_RATE)
        }
        for (i in electronic.indices) electronic[i] *= s.electronicGain
        for (i in physical.indices) physical[i] *= s.physicalGain
        val physicalFundamental = material?.fundamental?.also { source ->
            for (i in source.indices) source[i] *= s.physicalGain
        }
        val n = maxOf(electronic.size, physical.size)
        val transient = FloatArray(n)
        val noise = Dsp.Noise(Dsp.seedFor("ONSET", TRANSIENT_SEED_VERSION, voice.name, "TRANSIENT"))
        val filter = Dsp.OnePole(RENDER_RATE)
        val snap = m.getValue("SNAP")
        val duration = if (voice == OnsetVoice.KICK) 0.004f else 0.002f
        for (i in 0 until minOf(n, (duration * RENDER_RATE).toInt())) {
            val t = i.toFloat() / RENDER_RATE
            val envelope = (t / 0.0003f).coerceAtMost(1f) * (1f - t / duration)
            val white = noise.next()
            val shaped = if (voice == OnsetVoice.KICK) filter.lp(white, 1500f)
                else white - filter.lp(white, 2000f)
            transient[i] = shaped * envelope * snap * if (voice == OnsetVoice.KICK) 0.15f else 0.035f
        }
        val blend = m.getValue("BLEND")
        // Exact endpoints also avoid a tiny residual from cos(PI/2)'s rounding.
        val x = if (blend == 1f) 0f else cos(blend * PI * 0.5).toFloat()
        val o = if (blend == 0f) 0f else sin(blend * PI * 0.5).toFloat()
        val mixed = FloatArray(n) { i ->
            (electronic.getOrElse(i) { 0f } * x + physical.getOrElse(i) { 0f } * o + transient[i])
        }
        return Probe(s, electronic, physical, transient, mixed, physicalFundamental)
    }

    private data class KickMode(val zero: Double, val order: Int, val strength: Float, val t60Scale: Float)
    private data class KickMaterial(val samples: FloatArray, val fundamental: FloatArray)

    // Circular membrane zeros J_m(z)=0. Frequencies are z/2.4048255577 times f0.
    // Gains and losses are deliberate first-listen voicing, not a measured acoustic drum.
    // Unlike R1's .35/.18 T60 ratios, these allow the struck material to remain audible
    // after the attack. Higher modes still lose energy faster than the fundamental.
    private val kickModes = listOf(
        KickMode(2.4048255577, 0, 0.95f, 1f),
        KickMode(3.8317059702, 1, 0.68f, 0.95f),
        KickMode(5.1356223018, 2, 0.62f, 0.87f),
        KickMode(5.5200781103, 0, 0.42f, 0.83f),
        KickMode(7.0155866698, 1, 0.40f, 0.75f),
        KickMode(8.4172441404, 2, 0.32f, 0.68f),
    )

    /** Modal displacement at the strike, averaged over a finite contact patch. */
    private fun contactProjection(mode: KickMode, position: Float, radius: Float): Double =
        besselJ(mode.order, mode.zero * position) * cos(mode.order * 0.18) *
            exp(-0.5 * mode.zero * mode.zero * radius * radius)

    private fun besselJ(order: Int, x: Double): Double {
        var term = (x / 2.0).pow(order)
        for (n in 2..order) term /= n
        var sum = term
        for (k in 1..40) {
            term *= -(x * x / 4.0) / (k * (k + order))
            sum += term
            if (abs(term) < 1e-14) break
        }
        return sum
    }

    private fun kickMaterial(s: OnsetSettings): KickMaterial {
        val m = s.macros
        val frames = (s.t60Seconds * 1.4f * RENDER_RATE).toInt().coerceAtLeast(64)
        val out = FloatArray(frames)
        val fundamental = FloatArray(frames)
        val position = Dsp.lin(m.getValue("BODY"), 0.52f, 0.28f)
        val radius = Dsp.lin(m.getValue("EDGE"), 0.10f, 0.03f)
        val levels = kickModes.mapIndexed { index, mode ->
            if (index == 0) { if (s.modelVersion == 1) 1f else mode.strength } else {
                val reference = contactProjection(mode, 0.376f, 0.065f)
                val participation = (contactProjection(mode, position, radius) / reference).coerceIn(-1.4, 1.4)
                mode.strength * participation.toFloat() * Dsp.lin(m.getValue("EDGE"), 0.6f, 1.4f)
            }
        }
        val envs = kickModes.map {
            Dsp.Env(attackSeconds = 0.001f, decay2T60 = s.t60Seconds * it.t60Scale)
        }
        val phases = DoubleArray(kickModes.size)
        val snap = m.getValue("SNAP")
        for (i in out.indices) {
            val t = i.toFloat() / RENDER_RATE
            // The fundamental uses the identical pitch trajectory and clock as THUMP.
            // All modes share head tension; their individual losses and contact projection
            // supply the material difference rather than a different root or stereo trick.
            val bend = 1f + snap * 1.5f * exp(-90.0 * t).toFloat()
            var sum = 0f
            for (k in kickModes.indices) {
                val ratio = (kickModes[k].zero / kickModes[0].zero).toFloat()
                phases[k] += s.physicalRootHz * ratio * bend / RENDER_RATE
                val sample = sin(2.0 * PI * phases[k]).toFloat() * levels[k] * envs[k].at(t)
                sum += sample
                if (k == 0) fundamental[i] = sample
            }
            out[i] = sum
        }
        return KickMaterial(out, fundamental)
    }

    fun render(voice: OnsetVoice, macros: Map<String, Float> = emptyMap(), modelVersion: Int = MODEL_VERSION): Snip {
        val p = probe(voice, macros, modelVersion)
        val raw = p.mixed
        val body = p.settings.macros.getValue("BODY")
        val shelf = Dsp.OnePole(RENDER_RATE)
        val cutoff = if (voice == OnsetVoice.KICK) 150f else 6500f
        for (i in raw.indices) {
            raw[i] = (raw[i] + shelf.lp(raw[i], cutoff) * body * 0.35f) * p.settings.outputGain
        }
        // applyOversampled intentionally peak-normalizes its output for stock drums.
        // Use its same oversampled shaping stages directly so this experiment preserves
        // the blend's calibrated amplitude and measures its actual loudness separately.
        Punch.saturate(raw, 0.4f, RENDER_RATE)
        Punch.boostEnvelope(raw, 0.4f, RENDER_RATE)
        val out = Dsp.decimate(raw, RATE)
        Dsp.limitPeak(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
