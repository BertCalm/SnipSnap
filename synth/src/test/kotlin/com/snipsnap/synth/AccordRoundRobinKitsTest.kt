package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ACCORD TAKES ([AccordRoundRobinKits]): the kept ACCORD starter's sixteen performances, three
 * touches of two engine takes each (engine takes 0 and 2: four made the kit too heavy). The kept pad is its own touch's first take, sample for sample,
 * and every C3 pad of a heard preset deals only renders kept in accord-native-1 and accord-rr-1.
 */
class AccordRoundRobinKitsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private fun pins(resource: String) = javaClass.getResourceAsStream(resource)!!.bufferedReader().readLines()
        .filterNot { it.startsWith("#") || it.isBlank() }.map { it.split(' ') }
        .associate { f -> f[0] to f[2] }

    @Test fun `sixteen pads of three touches and two takes, the kept pad first in its own touch, every take the engine's`() {
        val pads = AccordRoundRobinKits.build()
        val kept = AccordKits.arranged()
        val patches = AccordKits.patches()
        assertEquals(16, pads.size)
        assertEquals(listOf(0, 2), AccordRoundRobinKits.TAKES, "the plain render and the take half a pulse spacing away")
        assertEquals(4, Accord.TAKES, "the engine, ROBIN and the pins keep all four takes")
        val pinned = pins("/accord/round-robin-v1.sha256") + pins("/accord/native-kept-v1.sha256")
        val touchNames = mapOf(.3f to "soft", .75f to "normal", 1f to "hard")
        var heard = 0
        for ((i, pad) in pads.withIndex()) {
            val patch = patches[i]
            assertEquals(kept[i].displayName, pad.displayName)
            assertEquals(kept[i].drumClass, pad.drumClass)
            assertEquals(kept[i].colorHex, pad.colorHex)
            assertTrue(pad.oneShot)
            assertEquals(null, pad.recipe, "a single performance's recipe cannot regenerate the grid; it rides in the source")
            assertEquals(patch, Patches.fromJsonText(pad.source.getValue("recipe")))
            assertEquals(listOf(2, 2, 2), pad.velocityTakes.map { it.size })
            val zone = AccordRoundRobinKits.TOUCHES.indexOf(patch.velocity)
            assertContentEquals(kept[i].snip.samples, pad.velocityTakes[zone][0].samples, "${pad.displayName}: take 1 is the kept pad")
            for ((z, v) in AccordRoundRobinKits.TOUCHES.withIndex()) for ((n, k) in AccordRoundRobinKits.TAKES.withIndex()) {
                val x = pad.velocityTakes[z][n].samples
                assertContentEquals(Accord.renderTake(patch.copy(velocity = v), k).samples, x, "${pad.displayName} touch $v take $k")
                assertTrue(x.maxOf { kotlin.math.abs(it) } <= .85f, "${pad.displayName} touch $v take $k peak")
            }
            // A pad of a heard C3 preset (BRUSH C3, WOOD, TINE REFERENCE and the soft and hard brush and wood)
            // deals the pinned renders: take 0 from accord-native-1, take 2 from accord-rr-1.
            val preset = AccordPresets.forVoice(patch.voice).first()
            if (patch.copy(name = preset.name, velocity = preset.velocity) == preset) {
                val voice = patch.voice.name.lowercase()
                for ((z, v) in AccordRoundRobinKits.TOUCHES.withIndex()) for ((n, k) in AccordRoundRobinKits.TAKES.withIndex()) {
                    val id = when {
                        k > 0 -> "$voice-${touchNames.getValue(v)}-take-$k"
                        v == .75f -> if (patch.voice == AccordVoice.TINE) "tine-reference" else "$voice-base"
                        else -> "$voice-touch-${touchNames.getValue(v)}"
                    }
                    assertEquals(pinned.getValue(id), sha(pad.velocityTakes[z][n].samples), "${pad.displayName} is $id")
                    heard++
                }
            }
        }
        // Size: four takes a touch was about 31 minutes (245 MB of PCM24), too heavy for a phone. Keep it under 17.
        val frames = pads.sumOf { pad -> pad.velocityTakes.sumOf { zone -> zone.sumOf { it.samples.size.toLong() } } }
        println("ACCORD TAKES size: $frames frames, ${"%.1f".format(frames / 44_100.0)} s, ${"%.1f".format(frames * 3 / 1e6)} MB PCM24")
        assertTrue(frames < 17L * 60 * 44_100, "the kit is ${frames / 44_100} s of audio")
        assertEquals(6 * 6, heard, "BRUSH C3, WOOD, REFERENCE, SOFT BRUSH, HARD BRUSH and SOFT WOOD are heard renders")
    }
}
