package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
import kotlin.random.Random

/**
 * VOX — formant synthesis, maximum kitsch.
 *
 * Three bandpass formants over a buzzing source is how every
 * shopping-mall-keyboard "choir" ever worked, and that is exactly the shelf
 * this engine aims for. The VOWEL knob morphs continuously through
 * A → E → I → O → U by interpolating the classic three-formant tables — one
 * knob, and the pad goes from "aah" to "ooh" under your finger.
 *
 * Voices are characters of the same throat: CHOIR (detuned saw pair, lush),
 * ROBOT (square source, tight formants), GHOST (breathy, noise-forward).
 * TUNE snaps to semitones like every melodic engine here.
 */
enum class VoxVoice { CHOIR, ROBOT, GHOST }

object Vox {

    const val TUNE_SEMITONES = 24

    /** F1/F2/F3 per vowel, the classic tables: A, E, I, O, U. */
    private val VOWELS = arrayOf(
        floatArrayOf(800f, 1150f, 2900f),  // A
        floatArrayOf(400f, 1600f, 2700f),  // E
        floatArrayOf(250f, 1750f, 3800f),  // I
        floatArrayOf(400f, 800f, 2830f),   // O
        floatArrayOf(350f, 600f, 2700f),   // U
    )
    private val FORMANT_GAINS = floatArrayOf(1f, 0.63f, 0.32f)
    private const val FORMANT_Q = 9f

    fun macrosFor(voice: VoxVoice): List<MacroSpec> = when (voice) {
        VoxVoice.CHOIR -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("VOWEL", 0.1f), MacroSpec("BREATH", 0.15f),
            MacroSpec("DECAY", 0.6f),
        )
        VoxVoice.ROBOT -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("VOWEL", 0.6f), MacroSpec("BREATH", 0.05f),
            MacroSpec("DECAY", 0.4f),
        )
        VoxVoice.GHOST -> listOf(
            MacroSpec("TUNE", 0.45f), MacroSpec("VOWEL", 0.85f), MacroSpec("BREATH", 0.6f),
            MacroSpec("DECAY", 0.7f),
        )
    }

    fun defaults(voice: VoxVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun scramble(voice: VoxVoice, random: Random): Map<String, Float> =
        macrosFor(voice).associate { it.name to random.nextFloat() }

    fun frequencyFor(voice: VoxVoice, tune: Float): Float {
        val root = when (voice) {
            VoxVoice.CHOIR -> 110f
            VoxVoice.ROBOT -> 82.4f
            VoxVoice.GHOST -> 147f
        }
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return root * 2f.pow(semis / 12f)
    }

    /** The morphed formant set for a VOWEL position 0..1 across A→E→I→O→U. */
    internal fun formantsAt(vowel: Float): FloatArray {
        val pos = vowel.coerceIn(0f, 1f) * (VOWELS.size - 1)
        val i = pos.toInt().coerceAtMost(VOWELS.size - 2)
        val frac = pos - i
        return FloatArray(3) { f -> VOWELS[i][f] + (VOWELS[i + 1][f] - VOWELS[i][f]) * frac }
    }

    fun render(voice: VoxVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(voice, m.getValue("TUNE"))
        val formants = formantsAt(m.getValue("VOWEL"))
        val breath = m.getValue("BREATH")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.25f, 1.1f)

        val out = FloatArray((t60 * 1.3f * RATE).toInt().coerceAtLeast(64))
        val filters = Array(3) { f ->
            Dsp.Biquad().apply { bandpass(formants[f], FORMANT_Q) }
        }
        val noise = Dsp.Noise(17)
        val noiseLp = Dsp.OnePole()

        var p1 = 0.0
        var p2 = 0.0
        val detune = if (voice == VoxVoice.CHOIR) 1.007f else 1.0f
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            p1 += base / RATE
            p2 += base * detune / RATE

            // The throat: a buzzing source with the character per voice.
            val buzz = when (voice) {
                VoxVoice.CHOIR -> 0.5f * (saw(p1) + saw(p2))
                VoxVoice.ROBOT -> Dsp.square(p1)
                VoxVoice.GHOST -> 0.7f * saw(p1)
            }
            val air = noiseLp.lp(noise.next(), 3_000f) * 2f
            val source = (1f - breath) * buzz + breath * air

            // The mouth: three formant resonances in parallel.
            var s = 0f
            for (f in 0 until 3) s += FORMANT_GAINS[f] * filters[f].process(source)

            val attack = (t / 0.02f).coerceAtMost(1f) // vocal onsets are soft
            out[i] = s * attack * Dsp.envAt(t, t60)
        }

        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()
}
