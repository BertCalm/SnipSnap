package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.pow

enum class FerryVoice { PASSAGE }

/**
 * A finite wood-to-wire exchange: strike -> handoff -> sympathetic bloom -> return.
 *
 * Model 1 ports the owner-approved 22-state reference. Only A (including its
 * isolated quiet root) is initially displaced. B/support/nine receivers start
 * silent. HANDOFF changes reciprocal links, not independently excited layers.
 * The saved MIDI note is explicit; this first prototype exposes HANDOFF only.
 * MATCH/SPAN/LOSS/BODY and seamless HOLD are later listening rounds, not inert
 * knobs in the first port. Finite gestures retain TONAL metadata like NACRE.
 */
object Ferry {
    const val MODEL_VERSION = 1
    const val DEFAULT_MIDI = 48
    const val MIN_MIDI = 36
    const val MAX_MIDI = 72
    const val DEFAULT_SEED = 7L
    const val DURATION_SECONDS = 4
    const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CACHE_SIZE = 32
    private data class Key(val midi: Int, val handoffBits: Int)
    private val compiled = object : LinkedHashMap<Key, FerryNetwork.Compiled>(CACHE_SIZE, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FerryNetwork.Compiled>?) = size > CACHE_SIZE
    }

    @Suppress("UNUSED_PARAMETER")
    fun macrosFor(voice: FerryVoice = FerryVoice.PASSAGE) = listOf(
        MacroSpec("HANDOFF", .5f, neutral = .5f), MacroSpec("HOLD", 0f),
    )
    fun defaults(voice: FerryVoice = FerryVoice.PASSAGE): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(macros: Map<String, Float>): Map<String, Float> = defaults().mapValues { (name, default) ->
        (macros[name]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f)
    }.also { require(it.getValue("HOLD") == 0f) { "FERRY model 1 is finite; HOLD must be 0" } }

    /** .25/.5/1 preserve the approved slow/normal/fast rates .35/1/3.5. */
    internal fun pace(handoff: Float): Double = when {
        handoff <= .25f -> 1.4 * handoff
        handoff <= .5f -> .35 * (1 / .35).pow((handoff - .25) / .25)
        else -> 3.5.pow((handoff - .5) / .5)
    }

    @Suppress("UNUSED_PARAMETER")
    fun drumClassFor(voice: FerryVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        settled(macros)
        return DrumClass.TONAL
    }

    internal fun plan(midi: Int, handoff: Float): FerryNetwork.Compiled {
        val key = Key(midi, handoff.toRawBits())
        synchronized(compiled) { compiled[key]?.let { return it } }
        val result = FerryNetwork.Compiled(FerryNetwork.build(midi, pace(handoff)), INTERNAL_RATE)
        synchronized(compiled) { return compiled.getOrPut(key) { result } }
    }
    internal fun clearCache() = synchronized(compiled) { compiled.clear() }

    /** Seed variation changes the finite A displacement, never graph or transfer timing. */
    internal fun initial(velocity: Float = 1f, seed: Long = DEFAULT_SEED): DoubleArray {
        val values = DoubleArray(FerryNetwork.SIZE)
        val base = doubleArrayOf(1.0, .65, .42, .28)
        for (i in 0..3) {
            val variation = if (seed == DEFAULT_SEED) 0.0 else {
                val hash = Dsp.seedFor("FERRY", MODEL_VERSION, seed, i, "displacement").toLong() and 0xffffffffL
                .015 * (2 * hash / 4294967295.0 - 1)
            }
            values[i] = base[i] * velocity * (.2 + .8 * velocity.toDouble().pow(i * .3)) * (1 + variation)
        }
        values[FerryNetwork.ROOT] = .22 * velocity
        return values
    }

    fun render(
        voice: FerryVoice = FerryVoice.PASSAGE,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        seed: Long = DEFAULT_SEED,
    ): Snip {
        require(midi in MIN_MIDI..MAX_MIDI) { "FERRY MIDI must be $MIN_MIDI..$MAX_MIDI" }
        require(velocity.isFinite() && velocity in 0f..1f) { "FERRY velocity must be finite and in 0..1" }
        require(voice == FerryVoice.PASSAGE)
        val m = settled(macros)
        if (velocity == 0f) return Snip(FloatArray(DURATION_SECONDS * Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
        val raw = plan(midi, m.getValue("HANDOFF")).render(initial(velocity, seed), DURATION_SECONDS * INTERNAL_RATE)
        return Snip(finish(raw), channels = 1, sampleRate = Dsp.RATE)
    }

    /** Normal SnipSnap output stage; no limiter is used to hide internal growth. */
    internal fun finish(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw, INTERNAL_RATE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val mean = out.sumOf { it.toDouble() } / out.size.coerceAtLeast(1)
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) {
            val x = out[i] - mean.toFloat()
            out[i] = x - hp.lp(x, 20f)
        }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }
}
