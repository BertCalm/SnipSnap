package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GRAIN ([Grain]): the granular scatter's own claims. */
class GrainTest {
    private val ka = Terra.render(TerraVoice.KA)

    private fun rms(s: Snip, a: Double, b: Double): Double {
        val from = (a * s.sampleRate).toInt() * s.channels; val to = minOf(s.samples.size, (b * s.sampleRate).toInt() * s.channels)
        var e = 0.0; for (i in from until to) e += s.samples[i].toDouble() * s.samples[i]; return sqrt(e / (to - from).coerceAtLeast(1))
    }

    @Test fun `MIX 0 is the pad untouched`() {
        assertContentEquals(ka.samples, Grain.process(ka, mapOf("MIX" to 0f)).samples)
    }

    @Test fun `the cloud lands after the hit, inside the scatter window, and is the same every time`() {
        val out = Grain.process(ka, mapOf("MIX" to 1f, "SCATTER" to .6f))
        assertContentEquals(out.samples, Grain.process(ka, mapOf("MIX" to 1f, "SCATTER" to .6f)).samples)
        assertTrue(out.samples.all { it.isFinite() })
        assertTrue(out.frameCount >= ka.frameCount)
        // the rim click is 0.5 s and nearly silent after 0.2 s; the cloud fills 0.2 to 0.35 s
        assertTrue(rms(out, 0.2, 0.35) > 3 * rms(ka, 0.2, 0.35), "the cloud adds sound after the hit: ${rms(out, 0.2, 0.35)} against ${rms(ka, 0.2, 0.35)}")
        val window = Grain.scatterSeconds(.6f) + Grain.grainSeconds(Grain.defaults().getValue("SIZE"))
        assertTrue(out.durationSeconds <= maxOf(ka.durationSeconds.toDouble(), window + 0.05) + 0.01, "the cloud ends inside its window: ${out.durationSeconds} s")
    }

    @Test fun `the sum keeps the pad's peak`() {
        val peak = ka.samples.maxOf { abs(it) }
        for (mix in listOf(.3f, .7f, 1f)) assertEquals(peak, Grain.process(ka, mapOf("MIX" to mix)).samples.maxOf { abs(it) }, 1e-4f, "MIX $mix")
    }

    @Test fun `every control changes the cloud`() {
        val base = Grain.process(ka, mapOf("MIX" to .8f)).samples
        for (spec in Grain.MACROS.filter { it.name != "MIX" }) for (v in listOf(0f, 1f)) {
            val moved = Grain.process(ka, mapOf("MIX" to .8f, spec.name to v)).samples
            assertTrue(!moved.contentEquals(base) || v == spec.default, "${spec.name} $v")
        }
        assertTrue(Grain.grainCount(1f) > Grain.grainCount(0f))
        assertTrue(Grain.grainSeconds(1f) > 10 * Grain.grainSeconds(0f))
    }

    @Test fun `mono stays mono, a stereo pad's channels share one cloud`() {
        assertEquals(1, Grain.process(ka).channels)
        val st = Snip(FloatArray(ka.samples.size * 2) { ka.samples[it / 2] * (if (it % 2 == 0) 1f else .5f) }, 2, ka.sampleRate)
        val out = Grain.process(st, mapOf("MIX" to 1f))
        assertEquals(2, out.channels)
        for (i in 0 until out.frameCount) assertEquals(out.samples[2 * i] * .5f, out.samples[2 * i + 1], 1e-5f)
    }

    @Test fun `a chain with the GRAIN processor saves and reloads as SPECKLE, after REWIND and before SPRING`() {
        val chain = FxChain(speckle = mapOf("MIX" to .6f, "SIZE" to .3f, "DENSITY" to .7f, "SCATTER" to .5f))
        assertEquals(chain, FxChain.fromJsonText(chain.toJsonText()))
        val names = FxChain.SECTIONS.map { it.name }
        assertEquals(names.indexOf("rewind") + 1, names.indexOf("speckle"))
        assertTrue(names.indexOf("speckle") < names.indexOf("spring"))
        assertTrue(chain.process(ka).samples.all { it.isFinite() })
    }

    @Test fun `a chain saved with the earlier key grain still loads its section as SPECKLE`() {
        val chain = FxChain(speckle = mapOf("MIX" to .6f, "SIZE" to .3f, "DENSITY" to .7f, "SCATTER" to .5f))
        val saved = chain.toJsonText()
        assertTrue(saved.contains("\"speckle\""), "the section is saved as speckle: $saved")
        val earlier = saved.replace("\"speckle\"", "\"grain\"")
        assertEquals(chain, FxChain.fromJsonText(earlier))
    }
}
