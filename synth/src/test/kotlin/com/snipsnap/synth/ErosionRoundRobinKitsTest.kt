package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.*

class ErosionRoundRobinKitsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** Hashes taken from the renderer before takes existed: (kind, name, touch or zone) to sha. */
    private val pins: Map<Triple<String, String, String>, String> by lazy {
        javaClass.getResourceAsStream("/erosion/contrast-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .associate { line -> line.split(' ').let { (k, n, t, s) -> Triple(k, n, t) to s } }
    }

    private val built by lazy { ErosionRoundRobinKits.build() }

    @Test fun `the accepted renders and kit zones still hash as they did before takes existed`() {
        assertEquals(48, pins.size)
        for (entry in ErosionBank.entries()) for (v in ErosionBank.velocities) {
            assertEquals(pins.getValue(Triple("render", entry.name, v.toString())),
                sha(entry.patch.copy(velocity = v).render().samples), "${entry.name} touch $v")
            assertContentEquals(entry.patch.copy(velocity = v).render().samples,
                Erosion.render(entry.patch.voice, entry.patch.macros, v, take = 0).samples)
        }
        for ((pad, entry) in ErosionKits.contrast().zip(ErosionBank.entries()))
            for ((z, snip) in (pad.softVariants + pad.snip).withIndex())
                assertEquals(pins.getValue(Triple("zone", entry.name, z.toString())), sha(snip.samples), "${entry.name} zone $z")
    }

    @Test fun `take zero of every zone is the accepted zone sample, byte for byte`() {
        val accepted = ErosionKits.contrast()
        assertEquals(8, built.size)
        for ((i, pad) in built.withIndex()) {
            val entry = ErosionBank.entries()[i]
            val zones = accepted[i].softVariants + accepted[i].snip
            assertEquals(entry.name, pad.displayName)
            assertEquals(accepted[i].drumClass, pad.drumClass)
            assertEquals(0, pad.muteGroup)
            assertTrue(pad.oneShot)
            assertNull(pad.recipe, "a single-hit recipe cannot regenerate the takes")
            assertEquals(entry.patch, ErosionPatch.fromJsonText(pad.source.getValue("recipe")))
            assertEquals(3, pad.velocityTakes.size)
            assertContentEquals(zones.last().samples, pad.snip.samples)
            for ((z, row) in pad.velocityTakes.withIndex()) {
                assertEquals(ErosionRoundRobinKits.TAKES, row.size)
                assertContentEquals(zones[z].samples, row[0].samples, "${entry.name} zone $z take 0")
                assertEquals(pins.getValue(Triple("zone", entry.name, z.toString())), sha(row[0].samples))
            }
        }
    }

    @Test fun `later takes re-render the same hit and differ from one another`() {
        for ((i, pad) in built.withIndex()) {
            val entry = ErosionBank.entries()[i]
            val first = Erosion.schedule(entry.patch.voice, entry.patch.macros).events.first()
            for ((z, row) in pad.velocityTakes.withIndex()) {
                for ((take, snip) in row.withIndex()) {
                    assertContentEquals(ErosionRoundRobinKits.render(entry.patch, ErosionBank.velocities[z], take).samples,
                        snip.samples, "${entry.name} zone $z take $take replays")
                    assertEquals(row[0].frameCount, snip.frameCount)
                    assertTrue(snip.samples.all { it.isFinite() && abs(it) <= .99001f })
                    if (z == 0) assertEquals(first, Erosion.schedule(entry.patch.voice, entry.patch.macros,
                        take = ErosionRoundRobinKits.STRIKES[take]).events.first(),
                        "${entry.name} take $take keeps the first discharge")
                }
                // Gain removed first: a louder or quieter copy of one take would read zero.
                for (a in row.indices) for (b in a + 1 until row.size) {
                    val r = ErosionRoundRobinMeasure.residual(row[a].samples, row[b].samples)
                    assertTrue(r > 1e-3, "${entry.name} zone $z takes $a/$b are a copy: $r")
                }
            }
        }
    }

    /**
     * Descriptive bounds, not a listening verdict: a hard take keeps the accepted onset, stays far
     * nearer the accepted hit's spectral shape than the nearest other pad, and keeps its 90%-energy
     * time within 0.7 to 1.4 times the accepted one. Measured: shape 0.2 to 1.9 dB from take 0
     * against 7.9 to 20.8 dB to the nearest other pad; energy time 0.76 to 1.11 times.
     */
    @Test fun `hard takes keep the accepted onset spectral shape and length`() {
        val m = ErosionRoundRobinMeasure
        val hard = built.associate { it.displayName!! to it.velocityTakes.last() }
        val shapes = hard.mapValues { m.shape(it.value[0].samples) }
        for ((name, row) in hard) {
            val nearest = shapes.filterKeys { it != name }.values.minOf { m.distance(shapes.getValue(name), it) }
            val accepted = row[0].samples
            for ((take, snip) in row.withIndex().drop(1)) {
                val x = snip.samples
                assertEquals(m.onsetMs(accepted), m.onsetMs(x), .1, "$name take $take onset")
                val d = m.distance(shapes.getValue(name), m.shape(x))
                assertTrue(d < .35 * nearest, "$name take $take shape $d dB from its own hit, nearest other pad $nearest dB")
                val ratio = m.t90Ms(x) / m.t90Ms(accepted)
                assertTrue(ratio in .7..1.4, "$name take $take energy time ratio $ratio")
            }
        }
    }
}
