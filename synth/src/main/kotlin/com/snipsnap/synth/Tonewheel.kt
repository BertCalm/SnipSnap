package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * TONEWHEEL — additive synthesis made playable.
 *
 * Pure additive (draw 64 partials) is synthesis as data entry; additive with
 * the handle humans have loved for 90 years is drawbars. Eight harmonic bars
 * (BAR1..BAR8), a PERC click register, WARBLE for the wobble, DIRT for the
 * drive — and every voice is a registration, so the panel starts on a sound,
 * not on silence.
 *
 * Renders a gated stab (the organ chord you'd sample), ~0.7 s: long enough
 * to read as tonal, short enough to be a pad hit. TUNE snaps to semitones
 * like PLUCK, so stab kits are in a key.
 */
enum class TonewheelVoice { FULL, SOUL, STAB }

object Tonewheel {

    /** Harmonic ratios of the eight bars — the classic footages, minus one. */
    val BAR_RATIOS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f)

    const val TUNE_SEMITONES = 24
    private const val GATE_SECONDS = 0.55f
    private const val RELEASE_SECONDS = 0.1f

    private fun registration(voice: TonewheelVoice): FloatArray = when (voice) {
        // All bars out: the everything drawbar handful.
        TonewheelVoice.FULL -> floatArrayOf(0.9f, 1f, 0.8f, 0.75f, 0.6f, 0.55f, 0.4f, 0.5f)
        // Sub plus fundamental plus a whisper of quint: the ballad bed. The
        // fundamental leads clearly — three near-equal low bars beat hard
        // enough that the envelope dips read as clap bursts downstream.
        TonewheelVoice.SOUL -> floatArrayOf(0.7f, 1f, 0.4f, 0.3f, 0.15f, 0f, 0f, 0f)
        // Bright top-heavy bars that cut: the one you stab with.
        TonewheelVoice.STAB -> floatArrayOf(0.3f, 0.9f, 0.2f, 0.85f, 0.55f, 0.8f, 0.3f, 0.7f)
    }

    fun macrosFor(voice: TonewheelVoice): List<MacroSpec> {
        val bars = registration(voice)
        return listOf(
            MacroSpec("TUNE", 0.5f),
            MacroSpec("PERC", if (voice == TonewheelVoice.STAB) 0.6f else 0.25f),
            MacroSpec("WARBLE", 0.35f),
            MacroSpec("DIRT", if (voice == TonewheelVoice.FULL) 0.3f else 0.1f),
        ) + bars.mapIndexed { i, level -> MacroSpec("BAR${i + 1}", level) }
    }

    fun defaults(voice: TonewheelVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun scramble(voice: TonewheelVoice, random: Random): Map<String, Float> =
        macrosFor(voice).associate { it.name to random.nextFloat() }

    fun frequencyFor(tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return 110f * 2f.pow(semis / 12f)
    }

    fun render(
        voice: TonewheelVoice,
        macros: Map<String, Float> = emptyMap(),
        /**
         * How long the key is held. The default is the one-shot stab the
         * drum kits use; the key-patch instruments hold it long enough to
         * cut a sustain loop from the steady region.
         */
        gateSeconds: Float = GATE_SECONDS,
    ): Snip {
        require(gateSeconds in 0.1f..8f) { "gateSeconds out of range: $gateSeconds" }
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(m.getValue("TUNE"))
        val perc = m.getValue("PERC")
        val warble = m.getValue("WARBLE")
        val dirt = m.getValue("DIRT")
        // Organ taper: drawbar throw is roughly logarithmic in level.
        val amps = FloatArray(8) { m.getValue("BAR${it + 1}").pow(1.6f) }

        val total = gateSeconds + RELEASE_SECONDS
        val out = FloatArray((total * RATE).toInt())
        val phases = DoubleArray(8)
        var percPhase = 0.0
        val vibHz = 6.4f
        val vibDepth = Dsp.lin(warble, 0f, 0.011f)

        for (i in out.indices) {
            val t = i.toFloat() / RATE
            // The whole instrument breathes together: one vibrato on the lot.
            val bend = 1f + vibDepth * sin(2.0 * PI * vibHz * t).toFloat()
            var s = 0f
            for (k in 0 until 8) {
                phases[k] += base * BAR_RATIOS[k] * bend / RATE
                s += amps[k] * sin(2.0 * PI * phases[k]).toFloat()
            }
            s /= 4f
            // The percussion register: a 2'-wheel ping that decays while the
            // bars sustain — the click that makes a stab bite.
            percPhase += base * 4f / RATE
            s += perc * 0.8f * Dsp.envAt(t, 0.2f) * sin(2.0 * PI * percPhase).toFloat()

            val gate = when {
                t < 0.004f -> t / 0.004f
                t < gateSeconds -> 1f
                else -> (1f - (t - gateSeconds) / RELEASE_SECONDS).coerceAtLeast(0f)
            }
            // DIRT pushes *into* the drive: without the level boost a
            // quarter-scale bar sum barely tickles the tanh and "full dirt"
            // is polite. Normalize below eats the loudness change.
            out[i] = Dsp.drive(s * gate * Dsp.lin(dirt, 1f, 2.4f), dirt)
        }

        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
