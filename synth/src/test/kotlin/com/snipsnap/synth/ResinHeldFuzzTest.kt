package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Held zones against patches nobody chose by hand: a seeded sweep (so a
 * failure reproduces) over voice, every macro including its exact ends,
 * junk keys, any attack in range including its ends, and any note the
 * voice can hold, not just the nine zone roots. Whatever comes in, the
 * zone is finite, under the ceiling, loud where it is held, and its loop
 * closes at floating-point noise.
 */
class ResinHeldFuzzTest {

    private fun knob(r: Random): Float = when (r.nextInt(6)) {
        0 -> 0f
        1 -> 1f
        else -> r.nextFloat()
    }

    @Test
    fun `any patch, attack and note holds a zone that closes`() {
        val r = Random(20260926)
        val cases = List(48) { n ->
            val voice = ResinVoice.entries[r.nextInt(ResinVoice.entries.size)]
            val macros = buildMap {
                for (k in listOf("STACK", "CUTOFF", "CREAM", "CONTOUR", "DECAY", "TUNE")) if (r.nextInt(5) > 0) put(k, knob(r))
                if (r.nextInt(8) == 0) put("WOBBLE", r.nextFloat())
            }
            val attack = when (r.nextInt(4)) {
                0 -> Resin.ATTACK_MIN_SECONDS
                1 -> Resin.ATTACK_MAX_SECONDS
                else -> Resin.ATTACK_MIN_SECONDS + r.nextFloat() * (Resin.ATTACK_MAX_SECONDS - Resin.ATTACK_MIN_SECONDS)
            }
            val low = Keys.resinPadMidis(voice).first()
            val midi = low + r.nextInt(Resin.TUNE_SEMITONES + 1)
            Triple(n, Triple(voice, macros, attack), midi)
        }
        val results = cases.parallelStream().map { (n, patch, midi) ->
            val (voice, macros, attack) = patch
            val label = "#$n $voice midi $midi attack $attack $macros"
            val note = Keys.resinPad(voice, macros, midi, attack)
            val s = note.snip.samples
            val start = note.loopStartFrame.toInt()
            val seam = Keys.seamError(s, start)
            val loud = Loudness.of(Snip(s.copyOfRange(start, s.size), 1, RATE))
            val db = 20 * log10(loud.toDouble() / Dsp.MELODIC_LOUDNESS_TARGET)
            val expectedStart = listOf(1f, 2f).map { ((attack + Keys.RESIN_PAD_SETTLE_SECONDS * it) * RATE).toInt() }
            val problems = buildList {
                if (!s.all { it.isFinite() }) add("not finite")
                if (s.maxOf { abs(it) } > 0.99f + 1e-6f) add("peak ${s.maxOf { abs(it) }}")
                // 24 cases measured a worst seam of 1.0e-13 when this was written; the Organ ships at 1e-3.
                if (!(seam < 1e-10)) add("seam $seam")
                if (s.size - start < 16) add("a loop of ${s.size - start} frames")
                if (expectedStart.none { abs(it - start) <= 1 }) add("loop starts at $start, not after attack + settle")
                // Levelled on the loop to the target; the limiter only ever pulls a resonant zone
                // down. 24 cases measured -4.44..0.00 dB; -6 dB is that plus margin.
                if (db > 0.1 || db < -6.0) add("loudness ${"%.2f".format(db)} dB")
            }
            Triple(label, seam to db, problems)
        }.toList()
        println("HELD-FUZZ worst seam %.1e, loudness %.2f..%.2f dB".format(
            results.maxOf { it.second.first }, results.minOf { it.second.second }, results.maxOf { it.second.second },
        ))
        val failures = results.filter { it.third.isNotEmpty() }.map { "${it.first}: ${it.third}" }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
