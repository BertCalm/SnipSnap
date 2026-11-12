package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.random.Random

/**
 * SOOT's round-1 listening pack (docs/SOOT.md): the Kotlin renders of the owner's round-0 picks, for a
 * confirming listen. Writes section folders of 16-bit WAVs and `manifest.json` to the output folder
 * (default `testkit/soot-audition`); `scripts/soot_r0/build_page.py --round 1` turns it into the page.
 *
 * Sections:
 * - **same**: each picked drum dry in Kotlin, to set beside the Python render the owner picked, and
 *   model 1's closed hat, which the owner heard as different;
 * - **pads**: every voice as its pad prints it (model 2: GRIME .45, the hats dry of the coil);
 * - **alternates**: the approved layout's other pads (the presets after the first);
 * - **touch**: soft, medium and hard, three takes each, every strike levelled to one peak;
 * - **kit**: four bars at 84 BPM played from the pads the way a sampler plays them: each hit its own
 *   render at its velocity, takes dealt in turn, the open hat choked by the next closed one, the hats
 *   dragged behind the beat. No shared bus: that is the phrase pad, a later round.
 */
object SootAuditionGenerator {
    private val rate = Dsp.RATE
    private data class Clip(val section: String, val id: String, val label: String, val file: String, val seconds: Double)

    private val clips = mutableListOf<Clip>()

    private fun emit(out: File, section: String, id: String, label: String, x: FloatArray) {
        val f = File(out, "$section/$id.wav")
        var p = 0f; for (s in x) p = maxOf(p, kotlin.math.abs(s))
        val y = if (p > 0) FloatArray(x.size) { x[it] * .89f / p } else x
        WavWriter.write(f, Snip(y, 1, rate), WavWriter.BitDepth.PCM_16)
        clips += Clip(section, id, label, "$section/$id.wav", x.size.toDouble() / rate)
    }

    private fun strip(voice: SootVoice): FloatArray {
        val parts = mutableListOf<FloatArray>()
        for (v in listOf(.3f, .6f, 1f)) {
            for (t in 0..2) {
                val x = Soot.render(voice, emptyMap(), v, t).samples.copyOf((.7 * rate).toInt().coerceAtMost(Soot.render(voice).samples.size))
                var p = 0f; for (s in x) p = maxOf(p, kotlin.math.abs(s))
                parts += FloatArray(x.size) { x[it] * .89f / p }
                parts += FloatArray((.12 * rate).toInt())
            }
            parts += FloatArray((.35 * rate).toInt())
        }
        val out = FloatArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    /** Four bars, the round-0 groove's events, played from pads as a sampler would. */
    private fun kit(grime: Float): FloatArray {
        val step = 60.0 / 84 / 4
        val r = Random(Dsp.seedFor("soot-groove"))
        data class Ev(val step: Int, val voice: SootVoice, val vel: Float)
        val ev = mutableListOf<Ev>()
        val kicks = listOf(listOf(0, 6, 10), listOf(0, 3, 10, 11), listOf(0, 6, 10), listOf(0, 7, 9, 14))
        for (bar in 0..3) {
            val b0 = bar * 16
            for (s in kicks[bar]) ev += Ev(b0 + s, SootVoice.KICK, if (s == 0) 1f else .85f)
            for (s in listOf(4, 12)) ev += Ev(b0 + s, SootVoice.SNARE, 1f)
            for (s in 0 until 16 step 2) ev += if ((bar == 1 || bar == 3) && s == 14) Ev(b0 + s, SootVoice.HAT_OPEN, .8f)
            else Ev(b0 + s, SootVoice.HAT_CLOSED, if (s % 4 == 0) .85f else .55f)
            if (bar == 1) ev += Ev(b0 + 15, SootVoice.SNARE, .3f)
            if (bar == 2) ev += Ev(b0 + 7, SootVoice.SNARE, .3f)
        }
        ev += listOf(Ev(11, SootVoice.ROD, .7f), Ev(29, SootVoice.CUP, .6f), Ev(45, SootVoice.CUP, .5f), Ev(51, SootVoice.PLATE, .65f), Ev(59, SootVoice.ROD, .55f))
        ev.sortBy { it.step }
        val gains = mapOf(SootVoice.KICK to 1f, SootVoice.SNARE to .8f, SootVoice.HAT_CLOSED to .28f, SootVoice.HAT_OPEN to .3f,
            SootVoice.ROD to .3f, SootVoice.PLATE to .3f, SootVoice.CUP to .3f)
        val total = ((16 * 4 * step + 1.5) * rate).toInt()
        val mix = FloatArray(total)
        val takes = mutableMapOf<SootVoice, Int>()
        var openAt = -1; var openHit: FloatArray? = null
        for (e in ev) {
            var t = e.step * step + .05
            if (e.step % 2 == 1) t += .32 * step
            t += when (e.voice) {
                SootVoice.HAT_CLOSED, SootVoice.HAT_OPEN -> .014 + (r.nextDouble() * 2 - 1) * .003
                SootVoice.SNARE -> .006 + (r.nextDouble() * 2 - 1) * .002
                else -> (r.nextDouble() * 2 - 1) * .002
            }
            val take = takes.getOrDefault(e.voice, 0); takes[e.voice] = take + 1
            val hit = Soot.render(e.voice, mapOf("GRIME" to grime), e.vel, take % 3).samples
            val i = (t * rate).toInt()
            val g = gains.getValue(e.voice) * e.vel
            if (e.voice == SootVoice.HAT_CLOSED && openHit != null) {
                // the closed hat chokes the open one: a 12 ms fade, then nothing
                val oh = openHit!!; val fade = (.012 * rate).toInt()
                for (k in i until minOf(total, openAt + oh.size)) {
                    val idx = k - openAt; val keep = if (k - i < fade) 1f - (k - i).toFloat() / fade else 0f
                    mix[k] -= (1f - keep) * gains.getValue(SootVoice.HAT_OPEN) * .8f * oh[idx]
                }
                openHit = null
            }
            for (k in hit.indices) if (i + k < total) mix[i + k] += g * hit[k]
            if (e.voice == SootVoice.HAT_OPEN) { openAt = i; openHit = hit }
        }
        return mix
    }

    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "testkit/soot-audition" })
        out.deleteRecursively(); out.mkdirs()
        val dry = mapOf("GRIME" to 0f)
        emit(out, "same", "kick", "Kick, Kotlin, dry", Soot.render(SootVoice.KICK, dry + ("COIL" to 0f)).samples)
        emit(out, "same", "snare", "Snare, Kotlin, dry of the print", Soot.render(SootVoice.SNARE, dry).samples)
        emit(out, "same", "hat", "Closed hat, Kotlin, dry (model ${Soot.MODEL_VERSION})", Soot.render(SootVoice.HAT_CLOSED, dry).samples)
        emit(out, "same", "hat_open", "Open hat, Kotlin, dry (model ${Soot.MODEL_VERSION})", Soot.render(SootVoice.HAT_OPEN, dry).samples)
        emit(out, "same", "hat_m1", "Closed hat, Kotlin, dry, model 1 (what you heard as different)", Soot.render(SootVoice.HAT_CLOSED, dry, model = 1).samples)
        for (voice in SootVoice.entries) {
            emit(out, "pads", voice.name.lowercase(), "${voice.name.lowercase().replace('_', ' ')}: as its pad prints it (GRIME .45)", Soot.render(voice).samples)
        }
        for (voice in SootVoice.entries) for (p in SootPresets.forVoice(voice).drop(1)) {
            emit(out, "alternates", "${voice.name.lowercase()}_${p.name.lowercase().replace(' ', '_')}", "${voice.name.lowercase().replace('_', ' ')}: ${p.name}", p.render().samples)
        }
        for (voice in listOf(SootVoice.KICK, SootVoice.SNARE, SootVoice.HAT_CLOSED, SootVoice.ROD)) {
            emit(out, "touch", voice.name.lowercase(), "${voice.name.lowercase().replace('_', ' ')}: soft, mid, hard, three takes each", strip(voice))
        }
        val timing = mutableMapOf<String, Long>()
        for (voice in SootVoice.entries) {
            repeat(2) { Soot.render(voice) }
            val t0 = System.nanoTime(); repeat(5) { Soot.render(voice) }
            timing[voice.name] = (System.nanoTime() - t0) / 5 / 1_000_000
        }
        emit(out, "kit", "grime45", "Four bars from the pads, GRIME .45 (your pick), the new hats", kit(.45f))
        val json = buildString {
            append("{\n \"engine\": \"SOOT\", \"round\": 1, \"model\": ${Soot.MODEL_VERSION}, \"harness\": \"Kotlin\",\n")
            append(" \"render_ms_warm_median5\": {"); append(timing.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }); append("},\n")
            append(" \"clips\": [\n")
            append(clips.joinToString(",\n") { "  {\"section\": \"${it.section}\", \"id\": \"${it.id}\", \"label\": \"${it.label}\", \"file\": \"${it.file}\", \"seconds\": ${"%.3f".format(it.seconds)}}" })
            append("\n ]\n}\n")
        }
        File(out, "manifest.json").writeText(json)
        println("SOOT audition: ${clips.size} clips in $out; warm render ms: $timing")
    }
}
