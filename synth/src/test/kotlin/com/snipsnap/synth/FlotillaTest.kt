package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the pool actually does. Counts are bounds, not frozen snapshots: a
 * refractory change should not fail a test that only asked whether the knob
 * moved the scene.
 */
class FlotillaTest {

    @Test
    fun `a ripple locks to the note at C2, C4 and C6, and the other clear voices stay near C4`() {
        for (midi in intArrayOf(36, 60, 84)) {
            val rendered = Flotilla.renderInternal(FlotillaVoice.RIPPLE, emptyMap(), midi, 1f, seconds = 1.2f)
            val cents = centsOf(rendered.snip, Flotilla.frequencyFor(midi))
            assertNotNull(cents.first, "MIDI $midi had no pitch")
            assertTrue(abs(cents.second) < 12.0, "MIDI $midi is ${cents.second} cents off")
        }
        for (voice in listOf(FlotillaVoice.KNOCK, FlotillaVoice.DRIFT, FlotillaVoice.HOLLOW)) {
            val rendered = Flotilla.renderInternal(voice, emptyMap(), 60, 1f, seconds = 1.2f)
            val cents = centsOf(rendered.snip, Flotilla.frequencyFor(60))
            assertNotNull(cents.first, "$voice had no pitch")
            assertTrue(abs(cents.second) < 15.0, "$voice is ${cents.second} cents off (conf ${cents.first})")
        }
    }

    @Test
    fun `the default ripple is a mono loop-length note, quiet at DC, and not a drum`() {
        val rendered = Flotilla.renderInternal(FlotillaVoice.RIPPLE, emptyMap(), 60, 1f)
        val snip = rendered.snip
        assertEquals(1, snip.channels)
        assertEquals(44100, snip.sampleRate)
        assertTrue(snip.durationSeconds > 1.5f, "duration ${snip.durationSeconds}")
        assertTrue(snip.peak() <= 0.991f, "peak ${snip.peak()}")
        assertTrue(snip.samples.all { it.isFinite() })
        val mean = snip.samples.average()
        assertTrue(abs(mean) < 0.02, "DC $mean")
        val heard = Classifier.classify(snip).drumClass
        assertTrue(heard !in DRUMS, "classified as $heard")
        assertEquals(DrumClass.LOOP, Flotilla.drumClassFor(FlotillaVoice.RIPPLE))
        assertEquals(DrumClass.LOOP, heard)
        assertTrue(rendered.scene.contacts.isNotEmpty())
    }

    @Test
    fun `the same recipe renders the same samples, and placement ignores hold and velocity`() {
        val a = Flotilla.renderInternal(FlotillaVoice.RIPPLE, mapOf("SURFACE" to 0.55f), 60, 0.7f, seconds = 0.8f)
        val b = Flotilla.renderInternal(FlotillaVoice.RIPPLE, mapOf("SURFACE" to 0.55f), 60, 0.7f, seconds = 0.8f)
        assertTrue(a.snip.samples.contentEquals(b.snip.samples))
        val soft = Flotilla.simulate(FlotillaVoice.KNOCK, mapOf("HOLD" to 0f), 60, 0.25f, 0.6f, true)
        val hard = Flotilla.simulate(FlotillaVoice.KNOCK, mapOf("HOLD" to 0.4f), 60, 1f, 0.6f, true)
        assertTrue(soft.x0.contentEquals(hard.x0))
        assertTrue(soft.y0.contentEquals(hard.y0))
        assertTrue(soft.radii.contentEquals(hard.radii))
        assertTrue(hard.contacts.size > soft.contacts.size)
        assertTrue(hard.meanSpeed > soft.meanSpeed)
    }

    @Test
    fun `turning the drive off leaves the pool still`() {
        val off = Flotilla.renderInternal(FlotillaVoice.GATHER, emptyMap(), 60, 1f, seconds = 0.8f, driveSurface = false)
        assertEquals(0, off.scene.contacts.size)
        assertEquals(0, off.scene.splashes.size)
        assertEquals(0f, off.scene.surfacePeak)
    }

    @Test
    fun `crossing, surface, vessel size and a packed pool each move the scene`() {
        val cross0 = scene(FlotillaVoice.CROSSWAVE, mapOf("CROSSING" to 0f, "SURFACE" to 0.7f))
        val cross1 = scene(FlotillaVoice.CROSSWAVE, mapOf("CROSSING" to 1f, "SURFACE" to 0.7f))
        assertTrue(cross0.contacts.isNotEmpty() && cross1.contacts.isNotEmpty())
        assertFalse(cross0.contacts.map { it.a to it.b to it.step } == cross1.contacts.map { it.a to it.b to it.step })
        val weights0 = Flotilla.routeWeights(0f)
        val weights1 = Flotilla.routeWeights(1f)
        assertEquals(1f, weights0[0])
        assertTrue(weights0.drop(1).all { it == 0f })
        assertTrue(weights1.all { abs(it - 1f) < 1e-5f })

        val surf0 = scene(FlotillaVoice.CROSSWAVE, mapOf("SURFACE" to 0f))
        val surf1 = scene(FlotillaVoice.CROSSWAVE, mapOf("SURFACE" to 1f))
        assertTrue(surf0.contacts.isNotEmpty() || surf0.meanHeave > 0.005f, "a quiet surface was dead: heave ${surf0.meanHeave}")
        assertTrue(surf1.meanSpeed > surf0.meanSpeed * 1.5f, "${surf1.meanSpeed} vs ${surf0.meanSpeed}")

        val small = scene(FlotillaVoice.HOLLOW, mapOf("VESSEL" to 0f, "SURFACE" to 0.6f))
        val large = scene(FlotillaVoice.HOLLOW, mapOf("VESSEL" to 1f, "SURFACE" to 0.6f))
        assertTrue(large.radii.average() > small.radii.average() * 1.5f)
        assertTrue(large.cavityHz.average() < small.cavityHz.average())
        assertTrue(large.meanSpeed < small.meanSpeed)

        val packed = Flotilla.simulate(FlotillaVoice.GATHER, mapOf("FLOTILLA" to 1f, "VESSEL" to 1f), 60, 1f, 0.4f, true)
        assertEquals(18, packed.count)
        var area = 0.0
        for (i in packed.radii.indices) {
            area += PI * packed.radii[i] * packed.radii[i]
            for (j in i + 1 until packed.count) {
                val d = hypot((packed.x0[j] - packed.x0[i]).toDouble(), (packed.y0[j] - packed.y0[i]).toDouble())
                assertTrue(d + 1e-3 >= packed.radii[i] + packed.radii[j], "hulls $i and $j overlap")
            }
        }
        assertTrue(area / PI < 0.75, "area fraction ${area / PI}")
    }

    @Test
    fun `a brighter pulse is brighter, and each knob changes the audio`() {
        val lows = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { pulse ->
            brightness(FlotillaVoice.RIPPLE, mapOf("PULSE" to pulse, "FLOTILLA" to 0.2f, "SURFACE" to 0.1f))
        }
        assertTrue(lows.last() > lows.first() * 2f, "brightness ${lows.first()} -> ${lows.last()}")
        for (i in 1 until lows.size) {
            assertTrue(lows[i] >= lows[i - 1] * 0.95f, "brightness dipped at step $i: $lows")
        }
        val base = Flotilla.renderInternal(FlotillaVoice.RIPPLE, emptyMap(), 60, 1f, seconds = 0.7f)
        for (name in Flotilla.macrosFor(FlotillaVoice.RIPPLE).map { it.name }) {
            val end = if (Flotilla.defaults(FlotillaVoice.RIPPLE).getValue(name) < 0.5f) 1f else 0f
            val other = Flotilla.renderInternal(FlotillaVoice.RIPPLE, mapOf(name to end), 60, 1f, seconds = 0.7f)
            assertFalse(base.snip.samples.contentEquals(other.snip.samples), "$name did not change the audio")
        }
        val quietHits = Flotilla.renderInternal(FlotillaVoice.RIPPLE, emptyMap(), 60, 1f, seconds = 0.7f, collisionSound = false)
        assertEquals(base.scene.contacts.size, quietHits.scene.contacts.size)
        assertFalse(base.snip.samples.contentEquals(quietHits.snip.samples))
    }

    @Test
    fun `skin and pulse interact in the audio, and the three-by-three scenes are not one scene`() {
        val corners = listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (pulse, skin) ->
            Flotilla.renderInternal(
                FlotillaVoice.RIPPLE,
                mapOf("PULSE" to pulse, "SKIN" to skin, "FLOTILLA" to 0.2f),
                60, 1f, seconds = 0.6f,
            ).snip.samples
        }
        for (i in corners.indices) for (j in i + 1 until corners.size) {
            assertFalse(corners[i].contentEquals(corners[j]), "PULSE/SKIN corner $i matched $j")
        }
        val counts = listOf(0f, 0.5f, 1f).flatMap { crossing ->
            listOf(0f, 0.5f, 1f).map { surface ->
                scene(FlotillaVoice.CROSSWAVE, mapOf("CROSSING" to crossing, "SURFACE" to surface)).contacts.size
            }
        }
        assertTrue(counts.max() > counts.min() * 2, "contact counts barely moved: $counts")
    }

    @Test
    fun `hold closes on its period, and a long pool settles`() {
        val held = Flotilla.renderInternal(FlotillaVoice.DRIFT, mapOf("HOLD" to 1f, "FLOTILLA" to 0.25f), 60, 1f)
        val loop = requireNotNull(held.loop)
        assertTrue(loop.seam < Keys.MAX_SEAM_ERROR, "seam ${loop.seam}")
        assertEquals(0, loop.crossfadeSamples)
        assertTrue(loop.crossfadeGain in 0.2f..2f, "overlap gain ${loop.crossfadeGain}")
        assertTrue(loop.iterations <= 8)
        val period = loop.period
        val snip = held.snip.samples
        assertEquals(period.size * 2, snip.size)
        // levelTo runs on the duplicated period, so the snip is not the raw
        // period. The two halves stay copies of each other.
        assertTrue(snip.copyOfRange(0, period.size).contentEquals(snip.copyOfRange(period.size, snip.size)))
        val eight = FloatArray(period.size * 8)
        for (k in 0 until 8) period.copyInto(eight, k * period.size)
        for (k in 1 until 8) {
            val seam = Keys.seamError(eight, k * period.size)
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "wrap $k seam $seam")
        }
        val wrap = abs(period[0] - period[period.lastIndex])
        val natural = abs(period[0] - loop.previous[loop.previous.lastIndex])
        val peak = period.maxOf { abs(it) }
        assertTrue(abs(wrap - natural) < 0.05f * peak, "wrap $wrap natural $natural peak $peak")
        val dense = Flotilla.renderInternal(FlotillaVoice.GATHER, mapOf("HOLD" to 1f), 60, 1f)
        assertTrue(requireNotNull(dense.loop).seam < Keys.MAX_SEAM_ERROR)

        val long = Flotilla.simulate(FlotillaVoice.RIPPLE, emptyMap(), 60, 1f, 4.5f, true)
        assertTrue(long.surfaceEnd < long.surfacePeak * 0.05f, "end ${long.surfaceEnd} peak ${long.surfacePeak}")
    }

    @Test
    fun `eight seconds stays inside the time and memory budget`() {
        val t0 = System.nanoTime()
        val rendered = Flotilla.renderInternal(FlotillaVoice.RIPPLE, emptyMap(), 60, 1f, seconds = 8f)
        val seconds = (System.nanoTime() - t0) / 1e9
        assertTrue(seconds < 20.0, "render took ${seconds}s")
        assertTrue(rendered.bytes < 192L * 1024 * 1024, "accounted ${rendered.bytes} bytes")
        assertTrue(rendered.snip.durationSeconds > 7f)
    }

    private fun scene(voice: FlotillaVoice, macros: Map<String, Float>) =
        Flotilla.simulate(voice, macros, 60, 1f, 1.5f, true)

    private fun brightness(voice: FlotillaVoice, macros: Map<String, Float>): Float {
        val s = Flotilla.renderInternal(voice, macros, 60, 1f, seconds = 0.5f).snip.samples
        val n = minOf(s.size, (0.12f * 44100).toInt())
        var energy = 0.0
        var diff = 0.0
        var prev = 0f
        for (i in 0 until n) {
            energy += s[i] * s[i]
            val d = s[i] - prev
            diff += d * d
            prev = s[i]
        }
        return (diff / energy.coerceAtLeast(1e-12)).toFloat()
    }

    private fun centsOf(snip: com.snipsnap.audio.Snip, wantHz: Float): Pair<Float?, Double> {
        val pitch = Pitch.detect(snip, 0.05f, 0.28f) ?: return null to Double.NaN
        val cents = 1200.0 * ln((pitch.hz / wantHz).toDouble()) / ln(2.0)
        return pitch.confidence to cents
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
