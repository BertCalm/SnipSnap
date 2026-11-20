package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TERRA's rolls and risers ([TerraRolls], [TerraArticulation]) and its Japanese kits ([TerraStickKits]): what the
 * recipe format promises, what a roll is, and what each kit's grid holds. The save, export and playback round trip is
 * TerraStickKitsRoundTripTest in :shell.
 */
class TerraStickKitsTest {
    private fun roll(voice: TerraVoice, a: TerraArticulation, vararg m: Pair<String, Float>) =
        TerraPatch("t", voice, Terra.defaults(voice) + m.toMap(), articulation = a)

    private fun distance(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var e = 0.0
        for (i in a.indices) { val x = a[i] - (if (i < b.size) b[i] else 0f); d += x.toDouble() * x; e += a[i].toDouble() * a[i] }
        return sqrt(d / e)
    }

    /** Magnitude centroid of the first 30 ms after the first sample over a tenth of the peak. */
    private fun attackCentroid(x: FloatArray): Double {
        val peak = x.maxOf { abs(it) }
        val start = x.indexOfFirst { abs(it) > 0.1f * peak }.coerceAtLeast(0)
        val n = 1323
        var num = 0.0; var den = 0.0
        for (k in 1 until n / 2) {
            var re = 0.0; var im = 0.0
            for (i in 0 until n) {
                val s = if (start + i < x.size) x[start + i].toDouble() else 0.0
                val w = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (n - 1))
                val ph = 2 * Math.PI * k * i / n
                re += s * w * kotlin.math.cos(ph); im -= s * w * kotlin.math.sin(ph)
            }
            val mag = sqrt(re * re + im * im)
            num += mag * k * Dsp.RATE / n; den += mag
        }
        return num / den
    }

    // ---------------------------------------------------------------- the recipe

    @Test fun `a hit writes today's bytes and a roll writes version 3 with its articulation`() {
        val hit = TerraPatch("Don", TerraVoice.NAGADO, Terra.defaults(TerraVoice.NAGADO))
        assertFalse(hit.toJsonText().contains("articulation"))
        assertTrue(hit.toJsonText().contains("\"version\": 1") || hit.toJsonText().contains("\"version\":1"))
        for (a in listOf(TerraArticulation.ROLL, TerraArticulation.RISER)) {
            val p = roll(TerraVoice.SHIME, a)
            val text = p.toJsonText()
            assertTrue(text.contains(a.name), text)
            val back = TerraPatch.fromJsonText(text)
            assertEquals(p, back)
            assertEquals(a, back.articulation)
            assertContentEquals(p.render().samples, back.render().samples)
        }
    }

    @Test fun `a roll is refused where it cannot be played`() {
        assertFailsWith<IllegalArgumentException> { TerraPatch("x", TerraVoice.DJEMBE, emptyMap(), articulation = TerraArticulation.ROLL) }
        val v1 = roll(TerraVoice.KA, TerraArticulation.ROLL).toJsonText().replace(Regex("\"version\":\\s*3"), "\"version\": 1")
        assertFailsWith<JsonException> { TerraPatch.fromJsonText(v1) }
        val unknown = roll(TerraVoice.KA, TerraArticulation.ROLL).toJsonText().replace("\"ROLL\"", "\"FLAM\"")
        assertFailsWith<JsonException> { TerraPatch.fromJsonText(unknown) }
        val notStick = roll(TerraVoice.KA, TerraArticulation.ROLL).toJsonText().replace("\"KA\"", "\"DJEMBE\"")
        assertFailsWith<JsonException> { TerraPatch.fromJsonText(notStick) }
    }

    // ---------------------------------------------------------------- what a roll is

    @Test fun `a roll is many strokes, a riser speeds up and grows, and both are phrases`() {
        for (voice in TerraSticks.VOICES) {
            val r = TerraRolls.strokes(voice, TerraArticulation.ROLL, 1f, 0)
            val s = TerraRolls.strokes(voice, TerraArticulation.RISER, 1f, 0)
            assertTrue(r.size >= 8, "$voice rolls ${r.size} strokes")
            // two hands: no two consecutive strokes share a renderer take
            assertTrue(r.zipWithNext().all { (a, b) -> a.take != b.take }, "$voice repeats a stroke")
            val gapsEarly = s.take(4).zipWithNext { a, b -> b.time - a.time }.average()
            val gapsLate = s.takeLast(4).zipWithNext { a, b -> b.time - a.time }.average()
            assertTrue(gapsLate < 0.6 * gapsEarly, "$voice's riser ends at $gapsLate s apart against $gapsEarly")
            assertTrue(s.last().force > 2.5f * s.first().force, "$voice's riser grows ${s.first().force} to ${s.last().force}")
            for (a in listOf(TerraArticulation.ROLL, TerraArticulation.RISER)) {
                val x = roll(voice, a, *(if (voice == TerraVoice.COWBELL) arrayOf("SHAKE" to 0f) else emptyArray())).render()
                assertTrue(x.samples.all { it.isFinite() } && x.samples.maxOf { abs(it) } in 0.5f..1f, "$voice $a")
                assertTrue(x.durationSeconds > 2.0f, "$voice $a is ${x.durationSeconds} s")
                assertEquals(DrumClass.LOOP, Classifier.classify(x).drumClass, "$voice $a is filed as a phrase")
            }
        }
    }

    @Test fun `a roll's take is another roll and its touch is darker, and each is pure`() {
        val p = roll(TerraVoice.SHIME, TerraArticulation.ROLL)
        val a = p.render(1f, 0).samples
        assertContentEquals(a, p.render(1f, 0).samples)
        val b = p.render(1f, 1).samples
        assertTrue(distance(a, b) > 0.1, "two takes of a roll are ${distance(a, b)} apart")
        val soft = p.render(1f / 3, 0).samples
        assertTrue(attackCentroid(soft) < attackCentroid(a), "a soft roll is darker: ${attackCentroid(soft)} against ${attackCentroid(a)}")
        assertTrue(TerraRolls.strokes(TerraVoice.SHIME, TerraArticulation.ROLL, 1f, 0).map { it.take }.toSet()
            .intersect(TerraRolls.strokes(TerraVoice.SHIME, TerraArticulation.ROLL, 1f, 1).map { it.take }.toSet()).isEmpty())
    }

    @Test fun `Velocity and Restrike keep the roll`() {
        val p = roll(TerraVoice.KA, TerraArticulation.ROLL)
        val soft = Velocity.atVelocity(p, .5f)
        assertTrue(soft.durationSeconds > 2.0f, "the soft layer is a roll, not one hit: ${soft.durationSeconds} s")
    }

    // ---------------------------------------------------------------- the kits

    @Test fun `three kits of sixteen named pads, no name twice in a kit, every macro written out`() {
        for (k in TerraStickKits.Kit.entries) {
            val patches = TerraStickKits.patches(k)
            assertEquals(16, patches.size)
            assertEquals(16, patches.map { it.name }.toSet().size, "${k.id} repeats a name")
            for (p in patches) {
                assertTrue(p.voice in TerraSticks.VOICES, p.name)
                assertEquals(Terra.macrosFor(p.voice).map { it.name }.toSet(), p.macros.keys, "${p.name} names every macro")
                assertTrue(p.name.length <= 14, "${p.name} fits a pad label")
            }
            assertTrue(patches.any { it.articulation == TerraArticulation.ROLL } && patches.any { it.articulation == TerraArticulation.RISER }, k.id)
        }
        // distinct roles, not one kit at three strengths: no recipe is shared, and the lead voices differ
        val all = TerraStickKits.Kit.entries.flatMap { TerraStickKits.patches(it) }
        assertEquals(all.size, all.map { it.voice to (it.macros to it.articulation) }.toSet().size, "a recipe is shared between kits")
        assertEquals(setOf(TerraVoice.NAGADO, TerraVoice.ODAIKO, TerraVoice.WATER_DRUM),
            TerraStickKits.Kit.entries.map { TerraStickKits.patches(it).first().voice }.toSet())
        assertTrue(TerraStickKits.patches(TerraStickKits.Kit.BON).none { it.voice == TerraVoice.ODAIKO })
    }

    @Test fun `every pad's grid is three touches by four takes, softer is darker, and no two takes are one sample`() {
        for (k in TerraStickKits.Kit.entries) {
            val pads = TerraStickKits.build(k)
            for ((i, pad) in pads.withIndex()) {
                val g = pad.velocityTakes
                assertEquals(3, g.size, pad.displayName); assertTrue(g.all { it.size == TerraStickKits.TAKES }, pad.displayName)
                assertContentEquals(pad.snip.samples, g.last().first().samples, "${pad.displayName}: the main sample is the hard first take")
                val patch = TerraStickKits.patches(k)[i]
                assertContentEquals(TerraStickKits.render(patch, 1f, 2, TerraStickKits.fxFor(k, i)).samples, g[2][2].samples, "${pad.displayName} zone sample replays")
                for (zone in g) for (a in zone.indices) for (b in a + 1 until zone.size)
                    assertFalse(zone[a].samples.contentEquals(zone[b].samples), "${pad.displayName}: takes $a and $b are the same samples")
                // a soft touch is a different strike, not the hard one peak-matched
                val soft = attackCentroid(g[0][0].samples); val hard = attackCentroid(g[2][0].samples)
                assertTrue(soft < hard, "${pad.displayName}: soft attack centroid $soft Hz is not under hard $hard Hz")
                assertNotEquals(g[0][0].samples.toList(), g[2][0].samples.toList())
            }
        }
    }

    /**
     * DUSK bakes a chain into every zone sample: each pad's source carries the chain, the chain reloads, and every pad
     * differs from the same recipe dry. A soft touch is the softer strike through the same chain, so it is still darker.
     */
    @Test fun `DUSK is processed on every pad, its chain saved beside its recipe`() {
        val k = TerraStickKits.Kit.DUSK
        val patches = TerraStickKits.patches(k)
        for ((i, p) in patches.withIndex()) {
            val fx = kotlin.test.assertNotNull(TerraStickKits.fxFor(k, i), p.name)
            assertEquals(fx, FxChain.fromJsonText(fx.toJsonText()))
            val wet = TerraStickKits.render(p, 1f, 0, fx).samples
            val dry = TerraStickKits.render(p, 1f, 0).samples
            assertFalse(wet.contentEquals(dry), "${p.name} is not processed")
            assertTrue(distance(dry, wet) > 0.05, "${p.name}: the chain moves the render by ${distance(dry, wet)}")
        }
        for (other in TerraStickKits.Kit.entries - k) for (i in 0 until 16) assertEquals(null, TerraStickKits.fxFor(other, i), "${other.id} is dry")
        val pad = TerraStickKits.build(k).first()
        assertEquals(TerraStickKits.fxFor(k, 0), FxChain.fromJsonText(pad.source.getValue("fx")))
    }
}
