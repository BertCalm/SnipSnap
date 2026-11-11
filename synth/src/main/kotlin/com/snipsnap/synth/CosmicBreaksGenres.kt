package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** Authored arrangements of the frozen source bank. The original bank A is never reprocessed. */
object CosmicBreaksGenres {
    const val VERSION = 1
    enum class Genre(val id: String, val title: String, val bpm: Float, val description: String) {
        ORBIT("cosmic-breaks", "Cosmic Breaks", 170f, "Cosmic DnB: the original Low Orbit hits, plus a roll and riser."),
        JUNGLE("cosmic-jungle", "Cosmic Jungle", 174f, "Jungle: pitched crack snares, tight gates and bright breakbeat chatter."),
        DUBSTEP("cosmic-dubstep", "Cosmic Dubstep", 140f, "Dubstep: deeper kicks, dark snares and spaced glitch echoes."),
    }

    fun build(genre: Genre): List<ArrangedPad> {
        val source = CosmicBreaksKits.build().toMutableList<ArrangedPad?>()
        val order = when (genre) {
            Genre.ORBIT -> (0..15).toList()
            Genre.JUNGLE -> listOf(0, 15, 2, 3, 5, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 1)
            Genre.DUBSTEP -> listOf(14, 1, 2, 3, 4, 5, 6, 7, 10, 9, 8, 11, 12, 13, 0, 15)
        }
        val pads = order.mapIndexed { slot, from ->
            val p = requireNotNull(source[from])
            source[from] = null // Release each source grid as its arrangement replaces it.
            if (genre == Genre.ORBIT) p else {
                val grid = p.velocityTakes.map { row -> row.map { treat(it, genre, slot) } }
                p.copy(snip = grid[2][0], velocityTakes = grid,
                    source = p.source + mapOf("genre" to genre.name, "arrangementVersion" to "$VERSION", "sourceSlot" to "${from + 1}"))
            }
        }
        return pads + (0..1).map { articulation ->
            val grid = (0..2).map { layer -> (0..3).map { take -> gesture(pads, genre, articulation == 1, layer, take) } }
            ArrangedPad(grid[2][0], DrumClass.LOOP, displayName = if (articulation == 0) "Orbit Roll" else "Relay Ascent",
                velocityTakes = grid, oneShot = true, muteGroup = 0,
                source = mapOf("factory" to genre.id, "genre" to genre.name, "arrangementVersion" to "$VERSION",
                    "bpm" to "${genre.bpm}", "articulation" to if (articulation == 0) "roll" else "riser", "rendered" to "true"))
        }
    }

    private fun treat(input: Snip, genre: Genre, slot: Int): Snip {
        val semitones = when (genre) {
            Genre.JUNGLE -> doubleArrayOf(1.5, 2.5, 1.0, 1.0, 3.0, 4.0, 2.0, 0.0, 1.0, 2.0, 4.0, 0.0, 0.0, 0.0, -1.0, 1.0)[slot]
            Genre.DUBSTEP -> doubleArrayOf(-2.5, -2.0, -1.0, -1.0, -3.0, -2.0, -1.0, -3.0, -4.0, -2.0, -3.0, -1.0, -2.0, -2.0, -1.0, -3.0)[slot]
            Genre.ORBIT -> 0.0
        }
        val pitched = pitchedRead(input, semitones)
        val out = FloatArray(Dsp.RATE * 4)
        val kick = slot == 0 || slot == 14
        val hat = slot == 2 || slot == 3
        val rate = if (genre == Genre.JUNGLE) when { kick -> 2.0; hat -> 5.0; slot >= 12 -> .6; else -> 4.5 }
                   else when { kick -> .4; hat -> 5.0; else -> .7 }
        val cutoff = if (genre == Genre.JUNGLE) 9500.0 else if (kick) 3500.0 else 5200.0
        val a = 1 - exp(-2 * PI * cutoff / Dsp.RATE)
        var lp = 0.0
        for (i in out.indices) {
            lp += a * ((pitched.getOrNull(i) ?: 0f) - lp)
            out[i] = (lp * exp(-max(0.0, i.toDouble() / Dsp.RATE - .03) * rate)).toFloat()
        }
        if (genre == Genre.DUBSTEP && !kick && !hat) {
            val dry = out.copyOf()
            val delay = (60 / genre.bpm * .75 * Dsp.RATE).roundToInt()
            for ((repeat, gain) in listOf(.32f, .18f, .10f).withIndex()) {
                val offset = delay * (repeat + 1)
                for (i in offset until out.size) out[i] += dry[i - offset] * gain
            }
        }
        finish(out)
        return Snip(out, 1, Dsp.RATE)
    }

    private fun gesture(pads: List<ArrangedPad>, genre: Genre, rising: Boolean, layer: Int, take: Int): Snip {
        val beat = 60.0 / genre.bpm
        val out = FloatArray(((4 * beat + .4) * Dsp.RATE).roundToInt())
        val events = if (rising) (0..7).map { it * .25 } + (0..15).map { 2 + it * .125 }
                     else (0..15).map { it * .25 }
        for ((n, at) in events.withIndex()) {
            val hit = pads[if (rising) 11 else 1].velocityTakes[layer][(take + n) % 4].samples
            val start = (at * beat * Dsp.RATE).roundToInt()
            val progress = n.toDouble() / (events.size - 1)
            val gain = if (rising) .12 + .46 * progress else if (n % 4 == 0) .48 else .28
            val cutoff = if (rising) 700 + 8500 * progress else 9000.0
            val a = 1 - exp(-2 * PI * cutoff / Dsp.RATE)
            var lp = 0.0
            val count = min(hit.size, min(out.size - start, (Dsp.RATE * .3).toInt()))
            for (i in 0 until count) {
                lp += a * (hit[i] - lp)
                val tail = min(1.0, (count - 1 - i) / 220.0)
                out[start + i] += (lp * gain * tail).toFloat()
            }
        }
        finish(out)
        return Snip(out, 1, Dsp.RATE)
    }

    /** Fixed source bandwidth and cubic sampler reads keep the small transpositions inexpensive.
     * The eighth-order 9 kHz prefilter protects the highest +4-semitone read from folding the top octave. */
    internal fun pitchedRead(input: Snip, semitones: Double): FloatArray {
        if (semitones == 0.0) return input.samples
        val filtered = input.samples.copyOf()
        for (k in floatArrayOf(1.9616f, 1.6629f, 1.1111f, .3902f))
            Dsp.TptSvf(Dsp.RATE).lowPassInPlace(filtered, 9000f, k)
        val speed = 2.0.pow(semitones / 12)
        return Dsp.readAt(Snip(filtered, 1, Dsp.RATE), (filtered.size / speed).toInt(), cubic = true) {
            it * speed to 1f
        }.samples
    }

    private fun finish(out: FloatArray) {
        // Changing envelopes can expose DC. Remove it after the articulation, without a second amp pass.
        val a = 1 - exp(-2 * PI * 20 / Dsp.RATE)
        var dc = 0.0
        for (i in out.indices) { dc += a * (out[i] - dc); out[i] = (out[i] - dc).toFloat() }
        val peak = out.maxOf { abs(it) }
        if (peak > .88f) for (i in out.indices) out[i] *= .88f / peak
        val fade = min(441, out.size)
        for (i in 0 until fade) out[out.lastIndex - i] *= i.toFloat() / fade
        out[out.lastIndex] = 0f
    }
}
