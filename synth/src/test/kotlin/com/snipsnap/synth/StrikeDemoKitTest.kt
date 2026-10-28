package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrikeDemoKitTest {
    private fun energy(snip: Snip, from: Double, to: Double): Double {
        val start = (from * snip.sampleRate).toInt() * snip.channels
        val end = minOf((to * snip.sampleRate).toInt() * snip.channels, snip.samples.size)
        return (start until end).sumOf { snip.samples[it].toDouble() * snip.samples[it] }
    }

    @Test
    fun `all demo layers are deterministic bounded and keep the same attack source`() {
        val pads = StrikeDemoKit.build()
        val again = StrikeDemoKit.build()
        assertEquals(4, pads.size)
        for ((index, pad) in pads.withIndex()) {
            assertEquals(listOf("DRY", "REVERB", "ECHO"), pad.layerNames)
            val layers = pad.softVariants + pad.snip
            val other = again[index].softVariants + again[index].snip
            val dry = layers.first()
            assertTrue(dry.frameCount < dry.sampleRate * 0.25, "${pad.displayName}: a short source leaves gaps for repeats")
            for ((i, layer) in layers.withIndex()) {
                assertContentEquals(layer.samples, other[i].samples)
                assertTrue(layer.samples.all { it.isFinite() && abs(it) <= 1f })
                assertTrue(layer.samples.maxOf { abs(it) } > 0.1f)
                assertTrue(layer.frameCount <= dry.frameCount + dry.sampleRate)
                assertEquals(dry.sampleRate, layer.sampleRate)
                assertEquals(dry.channels, layer.channels)
            }
            // Both racks retain the dry attack; effects begin later. Compare
            // shape after removing one gain ratio, not exact normalized samples.
            val attack = minOf(400, dry.samples.size)
            for (wet in layers.drop(1)) {
                val dot = (0 until attack).sumOf { dry.samples[it].toDouble() * wet.samples[it] }
                val norm = (0 until attack).sumOf { dry.samples[it].toDouble() * dry.samples[it] }
                val gain = dot / norm
                val error = (0 until attack).sumOf {
                    val delta = wet.samples[it] - dry.samples[it] * gain
                    delta * delta
                }
                assertTrue(error / norm < 1e-5, "${pad.displayName}: the attack remains the same source")
            }
            assertContentEquals(pad.snip.samples, PadRecipe.fromJsonValue(pad.recipe!!).render().samples)
        }
    }

    @Test
    fun `rim room has an early tail and echo has separated late repeats`() {
        val pad = StrikeDemoKit.build().first()
        val dry = pad.softVariants[0]
        val room = pad.softVariants[1]
        val echo = pad.snip
        val dryEnergy = energy(dry, 0.0, 0.1)
        val roomEarly = energy(room, 0.12, 0.24) / dryEnergy
        val echoGap = energy(echo, 0.12, 0.24) / dryEnergy
        val repeatOne = energy(echo, 0.29, 0.39) / dryEnergy
        val repeatTwo = energy(echo, 0.58, 0.68) / dryEnergy
        println("STRIKE FX rim: room tail=$roomEarly, echo gap=$echoGap, first repeat=$repeatOne, second repeat=$repeatTwo (energy / dry attack)")
        assertTrue(roomEarly > 0.002, "room needs a measurable diffuse tail")
        assertTrue(echoGap < 0.0001, "echo leaves a gap before its first repeat")
        assertTrue(repeatOne > 0.15, "the first echo must be a strong listening cue")
        assertTrue(repeatTwo > 0.02 && repeatTwo < repeatOne, "the second repeat remains distinct and decays")
    }
}
