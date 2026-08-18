package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempoTest {

    private val rate = 44_100

    /** Kick on every beat, hats on 8ths, snare on 2 and 4 — for [bars] bars. */
    private fun groove(bpm: Double, bars: Int = 4): Snip {
        val beat = 60.0 / bpm
        val frames = (bars * 4 * beat * rate).toInt()
        val out = FloatArray(frames)

        fun kick(at: Double) {
            val s = (at * rate).toInt()
            for (i in 0 until (0.25 * rate).toInt()) {
                if (s + i >= frames) break
                val t = i.toDouble() / rate
                out[s + i] += (0.9 * exp(-22.0 * t) * sin(2.0 * PI * (110.0 * exp(-40.0 * t) + 45.0) * t)).toFloat()
            }
        }
        fun hat(at: Double, seed: Int) {
            val s = (at * rate).toInt()
            var r = seed
            var pv = 0f
            for (i in 0 until (0.06 * rate).toInt()) {
                if (s + i >= frames) break
                r = (r * 1103515245 + 12345) and 0x7fffffff
                val w = (r.toFloat() / 0x3fffffff) - 1f
                out[s + i] += (0.3 * exp(-70.0 * i / rate) * (w - pv)).toFloat()
                pv = w
            }
        }
        fun snare(at: Double, seed: Int) {
            val s = (at * rate).toInt()
            var r = seed
            var lp = 0f
            for (i in 0 until (0.18 * rate).toInt()) {
                if (s + i >= frames) break
                val t = i.toDouble() / rate
                r = (r * 1103515245 + 12345) and 0x7fffffff
                val w = (r.toFloat() / 0x3fffffff) - 1f
                lp += 0.34f * (w - lp)
                out[s + i] += (0.7 * exp(-26.0 * t) * (0.4 * sin(2.0 * PI * 190.0 * t) + 1.2 * lp)).toFloat()
            }
        }

        for (bar in 0 until bars) {
            for (b in 0 until 4) {
                val at = (bar * 4 + b) * beat
                kick(at)
                if (b == 1 || b == 3) snare(at, bar * 7 + b)
                hat(at, bar + b)
                hat(at + beat / 2, bar + b + 40)
            }
        }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return Snip(out, 1, rate)
    }

    private fun assertBpm(expected: Double) {
        val e = Tempo.estimate(groove(expected))
        assertNotNull(e, "no estimate at $expected BPM")
        assertTrue(
            Tempo.agree(e.bpm, expected.toFloat(), toleranceBpm = 2.5f),
            "expected ~$expected BPM (or a half/double fold), got ${e.bpm} (conf ${e.confidence})",
        )
        assertTrue(e.confidence > 0.3f, "rock-steady groove should be confident, got ${e.confidence}")
    }

    @Test fun `finds 90 bpm`() = assertBpm(90.0)
    @Test fun `finds 120 bpm`() = assertBpm(120.0)
    @Test fun `finds 140 bpm`() = assertBpm(140.0)
    @Test fun `finds 174 bpm`() = assertBpm(174.0)

    @Test
    fun `estimate is folded into the conventional range`() {
        val e = Tempo.estimate(groove(120.0))!!
        assertTrue(e.bpm in 70f..180f)
    }

    @Test
    fun `silence has no tempo`() {
        assertNull(Tempo.estimate(Snip(FloatArray(rate * 4), 1, rate)))
    }

    @Test
    fun `too short to know is null, not a guess`() {
        assertNull(Tempo.estimate(groove(120.0).let {
            Snip(it.samples.copyOfRange(0, rate), 1, rate)
        }))
    }

    @Test
    fun `a sustained tone is not confidently rhythmic`() {
        val drone = Snip(
            FloatArray(rate * 4) { (0.5 * sin(2.0 * PI * 110.0 * it / rate)).toFloat() },
            1, rate,
        )
        val e = Tempo.estimate(drone)
        assertTrue(e == null || e.confidence < 0.5f, "a drone should not be confidently rhythmic: $e")
    }

    @Test
    fun `label is filename ready`() {
        val e = Tempo.estimate(groove(120.0))!!
        assertTrue(Regex("^\\d+bpm$").matches(e.label), e.label)
    }

    @Test
    fun `agree folds half and double time`() {
        assertTrue(Tempo.agree(90f, 180f))
        assertTrue(Tempo.agree(180f, 90f))
        assertTrue(Tempo.agree(120f, 120.9f))
        assertTrue(!Tempo.agree(100f, 133f))
    }
}
