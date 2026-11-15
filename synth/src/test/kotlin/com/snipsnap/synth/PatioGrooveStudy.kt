package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Authored 100 BPM audition arrangements, not transcriptions of traditional rhythms. */
internal object PatioGrooveStudy {
    const val VERSION = 1
    const val BPM = 100
    const val BEATS = 16
    const val TAIL_SECONDS = 1.0
    val VARIATIONS = listOf("sparse", "full", "fill")
    data class Hit(val pad: String, val beat: Double, val gain: Float)

    fun events(variation: String): List<Hit> {
        require(variation in VARIATIONS)
        val out = mutableListOf<Hit>()
        fun hit(id: String, beat: Double, gain: Float) { out += Hit(id, beat, gain) }
        for (bar in 0 until 4) {
            val b = bar * 4.0
            val filled = variation == "fill" && bar >= 2
            hit("kick", b, .90f); hit("kick", b + 2.5, .65f)
            hit("llamador", b + 1, .65f); hit("llamador", b + 3, .78f)
            // Alternate guacharaca gestures; leave the last fill bar open for hands.
            if (!(filled && bar == 3)) {
                hit("scrape-long", b, .40f); hit("scrape-short", b + 1, .28f)
                hit("scrape-long", b + 2, .35f); hit("scrape-short", b + 3, .25f)
            }
            for (offbeat in listOf(.5, 1.5, 2.5, 3.5))
                if (!(filled && offbeat >= 2.5)) hit("closed", b + offbeat, .42f)
            if (variation != "sparse") {
                hit("caixa", b + 1, .48f); if (!filled) hit("caixa", b + 3, .55f)
                for (step in 0 until 8) if (!filled) hit("guache", b + step * .5, if (step % 2 == 0) .15f else .11f)
                hit("alegre-open", b + .75, .56f); hit("alegre-slap", b + 2, .60f)
                hit("repicador", b + 1.75, .48f)
                if (!filled) {
                    hit("alegre-open", b + 3.25, .40f)
                    hit("repicador", b + 3.75, .44f)
                    hit(if (bar % 2 == 0) "agogo-low" else "agogo-high", b + .25, .45f)
                    hit(if (bar % 2 == 0) "agogo-high" else "agogo-low", b + 2.75, .35f)
                } else {
                    // Finger replies lead to an open-hat lift and its next closed choke.
                    hit("alegre-open", b + 2.5, .72f)
                    hit("repicador", b + 2.75, .52f)
                    hit("alegre-slap", b + 3, .65f)
                    hit("repicador", b + 3.25, .68f)
                    hit("open", b + 3.5, .45f)
                    hit("closed", b + 3.875, .30f)
                    if (bar == 3) hit("agogo-low", b + 3.5, .42f)
                }
            }
        }
        return out.sortedBy { it.beat }
    }

    fun runThrough(): List<Hit> = VARIATIONS.flatMapIndexed { i, v ->
        events(v).map { it.copy(beat = it.beat + i * BEATS) }
    }

    fun render(events: List<Hit>, hits: Map<String, Snip>, levels: Map<String, Float>, beats: Int): Snip {
        require(beats > 0 && events.all { it.pad in hits && it.pad in levels && it.beat >= 0 && it.beat < beats && it.gain.isFinite() && it.gain in 0f..1f })
        require(hits.values.all { it.channels == 1 && it.sampleRate == Dsp.RATE })
        val ordered = events.sortedBy { it.beat }
        val secondsPerBeat = 60.0 / BPM
        val out = FloatArray(((beats * secondsPerBeat + TAIL_SECONDS) * Dsp.RATE).roundToInt())
        val hats = setOf("closed", "open")
        val chokeFade = (.002 * Dsp.RATE).toInt()
        ordered.forEachIndexed { index, event ->
            val start = (event.beat * secondsPerBeat * Dsp.RATE).roundToInt()
            val nextHat = if (event.pad in hats) ordered.drop(index + 1).firstOrNull { it.pad in hats } else null
            val chokeAt = nextHat?.let { (it.beat * secondsPerBeat * Dsp.RATE).roundToInt() - start }
            val source = hits.getValue(event.pad).samples
            val count = min(source.size, min(out.size - start, chokeAt ?: Int.MAX_VALUE))
            for (i in 0 until count) {
                val taper = if (chokeAt != null) ((chokeAt - i).toFloat() / chokeFade).coerceIn(0f, 1f) else 1f
                out[start + i] += source[i] * levels.getValue(event.pad) * event.gain * taper * .65f
            }
        }
        // Uniform downward headroom correction; no new bus effect or altered pad timbre.
        Dsp.limitPeak(out, .90f)
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }
}
