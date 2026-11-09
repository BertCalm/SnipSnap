package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.*

/** A listening arrangement, not a transcription of the reference or an engine clock. */
internal object BackwashGroove {
    private data class Hit(val beat: Double, val voice: BackwashVoice, val velocity: Float, val tune: Float? = null)
    fun write(root: File, overrides: Map<String, Float> = emptyMap(),
        renderWet: (BackwashVoice, Map<String, Float>, Float) -> Snip = { v, m, vel -> Backwash.render(v, m, vel) }) {
        val hits = mutableListOf<Hit>()
        for (bar in 0..3) {
            val start = bar * 4.0
            for (step in 0..7) {
                val open = bar == 1 && step == 7
                hits += Hit(start + step * .5, if (open) BackwashVoice.HAT_OPEN else BackwashVoice.HAT_CLOSED,
                    if (step % 2 == 0) .68f else .43f)
            }
            for (beat in listOf(0.0, 2.0, 2.75)) hits += Hit(start + beat, BackwashVoice.KICK, if (beat == 2.75) .65f else .9f)
            for (beat in listOf(1.0, 3.0)) hits += Hit(start + beat + .012, BackwashVoice.SNARE, .86f)
            if (bar % 2 == 0) hits += Hit(start + 2.5, BackwashVoice.SNARE, .28f)
        }
        hits += Hit(14.5, BackwashVoice.TOM, .8f, .58f)
        hits += Hit(15.0, BackwashVoice.TOM, .88f, .35f)
        hits += Hit(15.5, BackwashVoice.TOM, .92f, .15f)
        val events = hits.sortedBy { it.beat }
        val secondsPerBeat = 60.0 / 94.0
        File(root, "groove-events.csv").writeText("beat,seconds,voice,velocity,tune\n" + events.joinToString("\n") {
            "${it.beat},${it.beat * secondsPerBeat},${it.voice},${it.velocity},${it.tune ?: Backwash.defaults(it.voice).getValue("TUNE")}"
        })
        for (wet in listOf(false, true)) {
            val out = FloatArray(((16 * secondsPerBeat + 1.5) * Dsp.RATE).toInt())
            for ((index, hit) in events.withIndex()) {
                val m = Backwash.defaults(hit.voice) + overrides + (hit.tune?.let { mapOf("TUNE" to it) } ?: emptyMap())
                val s = if (wet) renderWet(hit.voice, m, hit.velocity) else Backwash.source(hit.voice, m, hit.velocity)
                val offset = (hit.beat * secondsPerBeat * Dsp.RATE).toInt()
                val hat = hit.voice == BackwashVoice.HAT_CLOSED || hit.voice == BackwashVoice.HAT_OPEN
                val nextHat = if (hat) events.drop(index + 1).firstOrNull {
                    it.voice == BackwashVoice.HAT_CLOSED || it.voice == BackwashVoice.HAT_OPEN
                } else null
                val count = min(s.samples.size, nextHat?.let { ((it.beat - hit.beat) * secondsPerBeat * Dsp.RATE).toInt() } ?: s.samples.size)
                val balance = when (hit.voice) { BackwashVoice.KICK -> .9f; BackwashVoice.SNARE -> 1f; BackwashVoice.TOM -> .85f; else -> .38f }
                val gain = balance * hit.velocity.pow(1.4f)
                for (i in 0 until count) {
                    val choke = if (nextHat != null) ((count - 1 - i) / 176f).coerceIn(0f, 1f) else 1f
                    out[offset + i] += s.samples[i] * gain * choke
                }
            }
            // Match complete performances, never individual hits: preserve accents and kit balance.
            Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(out)
            WavWriter.write(File(root, "groove-${if (wet) "processed" else "dry"}.wav"), Snip(out, 1, Dsp.RATE), WavWriter.BitDepth.PCM_24)
        }
        for (voice in BackwashVoice.entries) {
            val out = FloatArray(Dsp.RATE * 4)
            for ((k, velocity) in listOf(.3f, .6f, 1f).withIndex()) {
                val s = Backwash.source(voice, overrides, velocity)
                for (i in s.samples.indices) out[k * Dsp.RATE + i] += s.samples[i] * velocity.pow(1.4f)
            }
            WavWriter.write(File(root, "${voice.name.lowercase()}-touch.wav"), Snip(out, 1, Dsp.RATE), WavWriter.BitDepth.PCM_24)
        }
    }
}
