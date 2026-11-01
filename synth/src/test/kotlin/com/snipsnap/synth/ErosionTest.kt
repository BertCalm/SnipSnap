package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class ErosionTest {
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
    @Test fun `all macro corners retain finite bounded energy and delivered one shots`() {
        var rawPeak = 0.0
        var dc = 0.0
        var conservation = 0.0
        for (voice in ErosionVoice.entries) for (bits in 0 until 32) {
            val macros = Erosion.defaults(voice).keys.mapIndexed { i, k -> k to ((bits shr i) and 1).toFloat() }.toMap()
            val trace = Erosion.schedule(voice, macros)
            conservation = max(conservation, trace.conservationError)
            assertTrue(trace.conservationError < 1e-12)
            assertTrue(trace.reservoir >= 0 && trace.charge >= 0 && trace.emitted in 0.0..1.0)
            assertTrue(trace.events.zipWithNext().all { (a, b) -> b.seconds > a.seconds })
            val raw = Erosion.source(voice, macros)
            assertTrue(raw.all { it.isFinite() })
            rawPeak = max(rawPeak, raw.maxOf { abs(it).toDouble() })
            assertTrue(rawPeak < 2.0, "raw source ceiling, before conditioning: $rawPeak")
            val out = Erosion.finish(raw)
            assertTrue(out.all { it.isFinite() && abs(it) <= .99001f })
            dc = max(dc, abs(out.sumOf { it.toDouble() } / out.size))
            assertTrue(dc < .005, "DC: $dc")
            assertTrue(out.size.toDouble()/Dsp.RATE < 1.5)
            assertTrue(out.maxOf { abs(it) } > .01f)
        }
        println("EROSION 64 corners rawPeak=$rawPeak maxAbsDC=$dc conservation=$conservation")
    }
    @Test fun `invalid direct controls fall back and audio controls preserve events`() {
        for (voice in ErosionVoice.entries) {
            val default = Erosion.render(voice).samples
            assertContentEquals(default, Erosion.render(voice, mapOf("FLUID" to Float.NaN, "UNKNOWN" to 1f)).samples)
            assertEquals(Erosion.schedule(voice, emptyMap()), Erosion.schedule(voice, mapOf("TUNE" to 1f,"FLUID" to 0f,"SLAG" to 1f)))
            val scrambled = Erosion.scramble(voice, Random(7))
            assertTrue(scrambled.values.all { it in 0f..1f })
            assertFailsWith<IllegalArgumentException> { ErosionPatch("Loop", voice, mapOf("HOLD" to 1f)) }
        }
    }
    @Test fun `every adjacent macro step changes the delivered signal`() {
        for (voice in ErosionVoice.entries) for (name in Erosion.defaults(voice).keys) {
            var previous = Erosion.render(voice, mapOf(name to 0f)).samples
            for (step in 1..4) {
                val next = Erosion.render(voice, mapOf(name to step/4f)).samples
                val n = min(previous.size,next.size)
                val difference = sqrt((0 until n).sumOf { (previous[it]-next[it]).toDouble().pow(2) }/n)
                assertTrue(difference > .001, "$voice $name step=$step rmsDifference=$difference")
                previous = next
            }
        }
    }
    @Test fun `velocity changes timbre after a fitted gain while zero stays silent`() {
        for (voice in ErosionVoice.entries) {
            val loud = Erosion.render(voice).samples
            val soft = Erosion.render(voice,velocity=.35f).samples
            val gain = loud.indices.sumOf { loud[it].toDouble()*soft[it] } / soft.sumOf { it.toDouble()*it }
            val residual = sqrt(loud.indices.sumOf { (loud[it]-gain*soft[it]).pow(2) }/loud.size)/rms(loud)
            assertTrue(residual > .03, "$voice gain-fit residual=$residual")
            assertTrue(Erosion.render(voice,velocity=0f).samples.all { it == 0f })
            println("EROSION $voice soft/full gain-fit residual=$residual")
        }
    }
    @Test fun `velocity dispatch preserves saved soft touch and macro state`() {
        for (voice in ErosionVoice.entries) {
            val patch = ErosionPatch("Soft", voice, velocity = .6f)
            assertContentEquals(patch.copy(velocity=.3f).render().samples, Velocity.atVelocity(patch,.5f).samples)
        }
    }
    @Test fun `saved velocity and model round trip and unsupported models refuse`() {
        for (voice in ErosionVoice.entries) {
            val p = ErosionPatch("Saved",voice,velocity=.47f)
            assertEquals(p,Patches.fromJsonText(p.toJsonText()))
            val unsupported = JsonValue.Obj(p.toJsonValue().entries + ("model" to JsonValue.Num(99.0)))
            val failure = assertFailsWith<JsonException> { ErosionPatch.fromJsonValue(unsupported) }
            assertEquals("unsupported erosion model 99", failure.message)
        }
    }
    @Test fun `matched output filters converge at four and eight times rate`() {
        for (voice in ErosionVoice.entries) {
            for (slag in listOf(0f,1f)) {
                val m=mapOf("TUNE" to 1f,"FLUID" to 1f,"VOLTAGE" to 1f,"SLAG" to slag)
                val four=Erosion.source(voice,m)
                val eight=Erosion.source(voice,m,rate=Dsp.RATE*8)
                // Independently evaluate the source at 8x, resample to 4x,
                // then apply the IDENTICAL house delivery chain to both.
                val down=Resampler.resample(Snip(eight,1,Dsp.RATE*8),Dsp.RATE*4).samples
                val a=Erosion.finish(four);val b=Erosion.finish(down)
                assertEquals(a.size,b.size)
                val residual=sqrt(a.indices.sumOf { (a[it]-b[it]).toDouble().pow(2) }/a.size)/rms(a)
                println("EROSION $voice SLAG=$slag 4x/8x relativeRMS=$residual")
                assertTrue(residual < .05, "matched-chain convergence: $voice SLAG=$slag residual=$residual")
            }
        }
    }
}
