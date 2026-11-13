package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Sketches of four candidate SOOT genre kits (docs/SOOT.md), for the owner to choose which become
 * authored sixteen-pad kits beside SOOT. A sketch, not a kit: test-side, like the round-0 harness, and
 * nothing here changes the approved kit (`SootKits.VERSION` 3).
 *
 * Each genre is its own tempo, tuning, pad roles, groove and processing, all through the SOOT engine:
 * - **Drumless (80 BPM):** no kick. Rods, a plate and cups carry the groove over a muted snare.
 * - **Basement dub (72 BPM):** a one-drop. A heavy kick and a high rimshot thrown deep into the coil.
 * - **Brushed noir (76 BPM):** soft and close. Brush-weight snare taps, a damp kick, pure hats, a light
 *   dictaphone.
 * - **Warehouse industrial (96 BPM):** straight and hard. A driven kick, struck plates up front, the
 *   dictaphone pushed.
 *
 * For each: four bars through one shared coil and dictaphone (SOOT's A16 bus), and four signature pads
 * as their pads would print them. Writes WAVs and `manifest.json` to `testkit/soot-genres`.
 */
object SootGenreSketchGenerator {
    private val rate = Dsp.RATE

    private class Part(val voice: SootVoice, val macros: Map<String, Float>, val gain: Double, val send: Double)
    private class Note(val step: Int, val part: String, val weight: Float)
    private class Genre(
        val id: String, val title: String, val bpm: Int, val swing: Double, val hatDrag: Double,
        val grime: Double, val wet: Double, val parts: Map<String, Part>, val notes: List<Note>,
        val signature: List<Pair<String, String>>,
    )

    private fun part(voice: SootVoice, gain: Double, send: Double, vararg m: Pair<String, Float>) =
        Part(voice, Soot.defaults(voice) + m, gain, send)

    private fun bars(vararg perBar: (Int) -> List<Note>): List<Note> = (0..3).flatMap { bar -> perBar.flatMap { it(bar) } }

    private fun at(bar: Int, part: String, weight: Float, vararg steps: Int) = steps.map { Note(bar * 16 + it, part, weight) }

    private val genres = listOf(
        Genre(
            "drumless", "Drumless", 80, swing = .32, hatDrag = .012, grime = .6, wet = .45,
            parts = mapOf(
                "rod" to part(SootVoice.ROD, .55, .35, "TUNE" to .45f, "DECAY" to .4f),
                "cup" to part(SootVoice.CUP, .35, .3, "TUNE" to .62f),
                "plate" to part(SootVoice.PLATE, .4, .4, "DECAY" to .6f, "TUNE" to .4f),
                "snare" to part(SootVoice.SNARE, .55, .2, "COIL" to .25f, "EDGE" to .5f, "TUNE" to .4f, "BENCH" to .2f),
                "hat" to part(SootVoice.HAT_CLOSED, .22, 0.0, "DETUNE" to .225f),
                "coil" to part(SootVoice.COIL, .45, 0.0, "TUNE" to .4f),
            ),
            notes = bars(
                { b -> at(b, "rod", .8f, 0, 10) + at(b, "rod", .55f, 3, 6, 13) },
                { b -> at(b, "cup", .5f, 2, 6, 10, 14) },
                { b -> if (b % 2 == 0) at(b, "plate", .6f, 0) else emptyList() },
                { b -> at(b, "snare", .45f, 4, 12) },
                { b -> at(b, "hat", .4f, 1, 5, 9, 15) },
                { b -> if (b == 3) at(b, "coil", .9f, 14) else emptyList() },
            ),
            signature = listOf("rod" to "Lead rod", "plate" to "Plate", "snare" to "Muted snare", "coil" to "Coil"),
        ),
        Genre(
            "dub", "Basement dub", 72, swing = .2, hatDrag = .008, grime = .5, wet = .7,
            parts = mapOf(
                "kick" to part(SootVoice.KICK, 1.0, .5, "SUB" to .9f, "TUNE" to .38f, "SAG" to .5f),
                "rim" to part(SootVoice.SNARE, .7, .85, "BENCH" to 1f, "TUNE" to .85f, "COIL" to .8f, "EDGE" to .3f),
                "hat" to part(SootVoice.HAT_CLOSED, .25, 0.0, "DETUNE" to .4f, "DECAY" to .7f),
                "plate" to part(SootVoice.PLATE, .3, .9, "DECAY" to .8f),
                "coil" to part(SootVoice.COIL, .5, 0.0, "COIL" to 1f, "TUNE" to .3f),
            ),
            notes = bars(
                { b -> at(b, "kick", 1f, 8) },
                { b -> at(b, "rim", .9f, 8) + (if (b == 1 || b == 3) at(b, "rim", .5f, 14) else emptyList()) },
                { b -> at(b, "hat", .5f, 2, 6, 10, 14) },
                { b -> if (b == 1) at(b, "plate", .6f, 15) else emptyList() },
                { b -> if (b == 3) at(b, "coil", 1f, 12) else emptyList() },
            ),
            signature = listOf("kick" to "Drop kick", "rim" to "Thrown rimshot", "plate" to "Plate into the spring", "coil" to "Coil"),
        ),
        Genre(
            "noir", "Brushed noir", 76, swing = .36, hatDrag = .016, grime = .25, wet = .25,
            parts = mapOf(
                "kick" to part(SootVoice.KICK, .8, .2, "SAG" to .15f, "SUB" to .25f, "TUNE" to .45f, "COIL" to .2f),
                "brush" to part(SootVoice.SNARE, .7, .15, "COIL" to .3f, "EDGE" to 0f, "BENCH" to 0f, "TUNE" to .35f),
                "hat" to part(SootVoice.HAT_CLOSED, .25, 0.0, "DETUNE" to 0f, "DECAY" to .3f),
                "cup" to part(SootVoice.CUP, .3, .2, "DECAY" to .7f, "TUNE" to .4f),
            ),
            notes = bars(
                { b -> at(b, "kick", .55f, 0) + at(b, "kick", .4f, 10) },
                { b -> at(b, "brush", .4f, 4, 12) + at(b, "brush", .14f, 1, 3, 7, 9, 11, 15) },
                { b -> at(b, "hat", .35f, 0, 4, 8, 12) },
                { b -> if (b % 2 == 1) at(b, "cup", .4f, 14) else emptyList() },
            ),
            signature = listOf("kick" to "Damp kick", "brush" to "Brush snare", "hat" to "Pure hat", "cup" to "Soft cup"),
        ),
        Genre(
            "industrial", "Warehouse industrial", 96, swing = 0.0, hatDrag = 0.0, grime = .9, wet = .4,
            parts = mapOf(
                "kick" to part(SootVoice.KICK, 1.0, .5, "SAG" to 1f, "SUB" to .15f, "TUNE" to .55f, "COIL" to .7f),
                "snare" to part(SootVoice.SNARE, .85, .3, "BENCH" to .9f, "EDGE" to 1f, "TUNE" to .6f),
                "plate" to part(SootVoice.PLATE, .5, .35, "TUNE" to .55f, "DECAY" to .45f),
                "rod" to part(SootVoice.ROD, .35, .3, "TUNE" to .6f, "DECAY" to .3f),
                "hat" to part(SootVoice.HAT_CLOSED, .25, 0.0, "DETUNE" to 1f, "DECAY" to .35f),
                "coil" to part(SootVoice.COIL, .5, 0.0, "TUNE" to .7f),
            ),
            notes = bars(
                { b -> at(b, "kick", 1f, 0, 8) + at(b, "kick", .8f, 6, 10) },
                { b -> at(b, "snare", 1f, 4, 12) + (if (b == 3) at(b, "snare", .6f, 13, 14, 15) else emptyList()) },
                { b -> at(b, "plate", .8f, 2) + at(b, "plate", .6f, 14) },
                { b -> at(b, "rod", .6f, 7, 11) },
                { b -> (0 until 16).flatMap { s -> at(b, "hat", if (s % 4 == 0) .7f else .4f, s) } },
                { b -> if (b == 1) at(b, "coil", .9f, 15) else emptyList() },
            ),
            signature = listOf("kick" to "Driven kick", "snare" to "Bench snare", "plate" to "Struck plate", "rod" to "Rod"),
        ),
    )

    /** Four bars through one bus: SOOT's A16 recipe (dry hits, sends and the kick into one coil, a keyed dictaphone, wrapped). */
    private fun sketch(g: Genre): FloatArray {
        val step = 60.0 / g.bpm / 4
        val loop = (16 * 60.0 / g.bpm * rate).toInt()
        val total = loop + (1.5 * rate).toInt()
        val human = Random(Dsp.seedFor("soot-genre", g.id))
        val mix = DoubleArray(total); val kick = DoubleArray(total); val send = DoubleArray(total)
        val counts = mutableMapOf<String, Int>()
        for (n in g.notes.sortedBy { it.step }) {
            val p = g.parts.getValue(n.part)
            var t = n.step * step
            if (n.step % 2 == 1) t += g.swing * step
            t += if (p.voice == SootVoice.HAT_CLOSED) g.hatDrag else 0.0
            t += (human.nextDouble() * 2 - 1) * .002
            val take = counts.getOrDefault(n.part, 0); counts[n.part] = take + 1
            val x = Soot.dry(p.voice, Soot.settled(p.voice, p.macros), n.weight, take)
            val at = Math.floorMod((t * rate).toInt(), loop)
            for (k in x.indices) {
                val i = at + k
                if (i >= total) break
                val v = p.gain * n.weight * x[k]
                mix[i] += v
                if (p.voice == SootVoice.KICK) kick[i] += v else send[i] += p.send * v
            }
        }
        val kickSend = Soot.highpass(kick, 90.0)
        for (i in send.indices) send[i] += .45 * kickSend[i]
        val ret = Soot.lowpass(BreakerChain.spring(send, .5, total, rate, Soot.COIL_TANK, sendHp = 60.0), Soot.COIL_TANK.tone)
        val pm = mix.maxOf { abs(it) }; val pr = ret.maxOf { abs(it) }
        if (pr > 0) for (i in mix.indices) mix[i] += g.wet * pm / pr * ret[i]
        val key = DoubleArray(total) { kick[it] + .3 * mix[it] }
        val bus = Soot.dictaphone(mix, g.grime, key)
        val out = FloatArray(loop)
        for (i in bus.indices) out[i % loop] += bus[i].toFloat()
        val mean = out.average().toFloat()
        for (i in out.indices) out[i] -= mean
        Dsp.levelTo(out, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        return out
    }

    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "testkit/soot-genres" }).apply { deleteRecursively(); mkdirs() }
        val clips = mutableListOf<String>()
        fun emit(section: String, id: String, label: String, x: FloatArray) {
            var p = 0f; for (s in x) p = maxOf(p, abs(s))
            WavWriter.write(File(out, "$section/$id.wav"), Snip(FloatArray(x.size) { x[it] * .89f / p }, 1, rate), WavWriter.BitDepth.PCM_16)
            clips += "  {\"section\": \"$section\", \"id\": \"$id\", \"label\": \"$label\", \"file\": \"$section/$id.wav\", \"seconds\": ${"%.3f".format(x.size.toDouble() / rate)}}"
        }
        for (g in genres) {
            emit(g.id, "sketch", "${g.title}: four bars at ${g.bpm} BPM through one coil and the dictaphone", sketch(g))
            for ((key, name) in g.signature) {
                val p = g.parts.getValue(key)
                emit(g.id, key, "${g.title}: $name", Soot.render(p.voice, p.macros + ("GRIME" to g.grime.toFloat())).samples)
            }
        }
        File(out, "manifest.json").writeText("{\n \"clips\": [\n" + clips.joinToString(",\n") + "\n ]\n}\n")
        println("SOOT genre sketches: ${clips.size} clips in $out")
    }
}
