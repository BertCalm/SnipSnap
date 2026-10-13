package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
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
    val electronicModel: String = "THUMP raw oscillator/envelope source",
    val physicalModel: String = "SKIN raw modal/resonant-noise source",
)

/**
 * ONSET blends two unfinished sources on the same clock, followed by one transient and finish.
 * Kicks share an actual fundamental, pitch-drop trajectory and decay. Hats have no single fundamental: both natural
 * source tunings receive the same semitone shift and share the envelope's T60. Stock THUMP and
 * SKIN patches keep their original voicing, normalization and PUNCH behavior.
 *
 * Source gains and output headroom are fixed per voice, independent of BLEND and the rendered
 * patch's peak. Equal-power blending is a starting curve, not a claim of constant loudness:
 * correlated fundamentals can reinforce and different spectra can sound unequally loud.
 */
object Onset {
    const val MODEL_VERSION = 1
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

    fun settingsFor(voice: OnsetVoice, macros: Map<String, Float> = emptyMap()): OnsetSettings {
        val m = defaults(voice).toMutableMap()
        for ((name, value) in macros) if (name in m && value.isFinite()) m[name] = value.coerceIn(0f, 1f)
        val tune = m.getValue("TUNE")
        val kick = voice == OnsetVoice.KICK
        val root = if (kick) Dsp.expMap(tune, 45f, 62f) else null
        val semitones = if (kick) 0f else Dsp.lin(tune, -4f, 4f)
        val transpose = 2f.pow(semitones / 12f)
        return OnsetSettings(
            MODEL_VERSION, voice, m.toMap(), root, semitones,
            root ?: 445f * transpose, root ?: 6500f * transpose,
            if (kick) Dsp.expMap(m.getValue("DECAY"), 0.12f, 0.85f)
                else Dsp.expMap(m.getValue("DECAY"), 0.04f, 0.16f),
            // Frozen from the MODEL_VERSION 1 default-source reference: at 44.1 kHz
            // kick X is about 1.4x the physical source; hat X=.01136/O=.05670. Rounded gains
            // leave kick peak room and bring the much quieter square hat into balance.
            // These are never recomputed from an individual patch or BLEND position.
            electronicGain = if (kick) 1f else 5f,
            physicalGain = if (kick) 1.25f else 1f,
            outputGain = if (kick) 0.32f else 0.52f,
        )
    }

    internal data class Probe(
        val settings: OnsetSettings,
        val electronic: FloatArray,
        val physical: FloatArray,
        val transient: FloatArray,
        val mixed: FloatArray,
    )

    /** Calibrated source taps are deliberately before shelf, PUNCH and decimation. */
    internal fun probe(voice: OnsetVoice, macros: Map<String, Float> = emptyMap()): Probe {
        val s = settingsFor(voice, macros)
        val m = s.macros
        val electronic = when (voice) {
            OnsetVoice.KICK -> Thump.onsetKick(s.electronicRootHz, s.t60Seconds,
                m.getValue("SNAP"), m.getValue("EDGE"), RENDER_RATE)
            OnsetVoice.HAT_CLOSED -> Thump.onsetHat(s.electronicRootHz, s.t60Seconds,
                m.getValue("EDGE"), RENDER_RATE)
        }
        val physical = when (voice) {
            OnsetVoice.KICK -> Skin.onsetKick(s.physicalRootHz, s.t60Seconds,
                m.getValue("SNAP"), m.getValue("BODY"), m.getValue("EDGE"), RENDER_RATE)
            OnsetVoice.HAT_CLOSED -> Skin.onsetHat(s.physicalRootHz, s.t60Seconds,
                m.getValue("BODY"), m.getValue("EDGE"), RENDER_RATE)
        }
        for (i in electronic.indices) electronic[i] *= s.electronicGain
        for (i in physical.indices) physical[i] *= s.physicalGain
        val n = maxOf(electronic.size, physical.size)
        val transient = FloatArray(n)
        val noise = Dsp.Noise(Dsp.seedFor("ONSET", MODEL_VERSION, voice.name, "TRANSIENT"))
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
        return Probe(s, electronic, physical, transient, mixed)
    }

    fun render(voice: OnsetVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val p = probe(voice, macros)
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
