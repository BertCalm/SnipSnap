package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Knob movement should develop the same pool, and a collision belongs to both hulls. */
class FlotillaControlTest {
    @Test
    fun `population reveals stable indexed hulls instead of repacking existing vessels`() {
        for (voice in FlotillaVoice.entries) {
            val full = scene(voice, mapOf("FLOTILLA" to 1f))
            for (population in listOf(0f, 0.2499f, 0.25f, 0.2501f, 0.4999f, 0.5f, 0.5001f, 0.75f)) {
                val sparse = scene(voice, mapOf("FLOTILLA" to population))
                for ((name, values) in listOf(
                    "x" to (sparse.x0 to full.x0), "y" to (sparse.y0 to full.y0),
                    "radius" to (sparse.radii to full.radii), "mass" to (sparse.mass to full.mass),
                    "detune" to (sparse.detune to full.detune), "angle" to (sparse.angle to full.angle),
                )) {
                    assertContentEquals(values.second.copyOf(sparse.count), values.first, "$voice FLOTILLA=$population changed $name")
                }
            }
        }
    }

    @Test
    fun `vessel size changes the same hulls continuously`() {
        for (voice in FlotillaVoice.entries) {
            val small = scene(voice, mapOf("VESSEL" to 0f, "FLOTILLA" to 1f))
            for (size in listOf(0.25f, 0.4999f, 0.5f, 0.5001f, 0.75f, 1f)) {
                val larger = scene(voice, mapOf("VESSEL" to size, "FLOTILLA" to 1f))
                assertContentEquals(small.x0, larger.x0, "$voice VESSEL=$size moved x")
                assertContentEquals(small.y0, larger.y0, "$voice VESSEL=$size moved y")
                assertContentEquals(small.detune, larger.detune, "$voice VESSEL=$size changed material identity")
                for (i in small.radii.indices) {
                    assertTrue(larger.radii[i] > small.radii[i])
                    assertTrue(larger.cavityHz[i] < small.cavityHz[i])
                }
            }
        }
    }

    @Test
    fun `a new hull enters below one percent engagement without a participation floor`() {
        val before = Flotilla.population(0.5f)
        val after = Flotilla.population(0.5001f)
        assertEquals(12, before.first)
        assertEquals(1f, before.second)
        assertEquals(13, after.first)
        assertTrue(after.second in 0f..0.002f, "entry engagement ${after.second}")
    }

    @Test
    fun `tiny population steps preserve the waveform across an entering hull`() {
        val variants = listOf(0.4999f, 0.5f, 0.5001f).map { population ->
            Flotilla.render(FlotillaVoice.RIPPLE, mapOf("FLOTILLA" to population), 60, 1f).samples
        }
        for (i in 1 until variants.size) {
            val change = alignedDifference(variants[i - 1], variants[i])
            assertTrue(change < 0.02, "tiny population step $i changed the waveform by $change")
        }
        // Continuity must not make the complete population range a dead knob.
        val low = Flotilla.render(FlotillaVoice.RIPPLE, mapOf("FLOTILLA" to 0f), 60, 1f).samples
        val high = Flotilla.render(FlotillaVoice.RIPPLE, mapOf("FLOTILLA" to 1f), 60, 1f).samples
        assertTrue(alignedDifference(low, high) > 0.18)
    }

    @Test
    fun `both hulls radiate the same pair contact regardless of endpoint ordering`() {
        val pool = scene(FlotillaVoice.RIPPLE)
        val hit = Flotilla.Contact(8, 0, 1, 0.1f, 0.2f, -0.3f)
        val pair = response(withContacts(pool, listOf(hit)))
        val swapped = response(withContacts(pool, listOf(hit.copy(a = 1, b = 0))))
        assertContentEquals(pair, swapped)
        val onlyA = response(withContacts(pool, listOf(hit.copy(b = -1))))
        assertTrue(alignedDifference(pair, onlyA) > 0.1, "the second hull contributed no distinct resonance")
    }

    @Test
    fun `a pair shares one excitation budget and a ghost endpoint adds no sound`() {
        val pool = scene(FlotillaVoice.RIPPLE)
        val hit = Flotilla.Contact(8, 0, 1, 0.1f, 0.2f, -0.3f)
        val pair = response(withContacts(pool, listOf(hit)))
        val split = (hit.impulse / sqrt(2f))
        val expected = response(withContacts(pool, listOf(
            hit.copy(b = -1, impulse = split), hit.copy(a = 1, b = -1, impulse = split),
        )))
        val error = sqrt(pair.indices.sumOf { (pair[it] - expected[it]).toDouble().let { d -> d * d } } /
            expected.sumOf { it.toDouble() * it }.coerceAtLeast(1e-18))
        assertTrue(error < 0.002, "pair excitation was duplicated rather than divided: $error")

        val engagement = pool.engagement.copyOf().also { it[1] = 0f }
        val ghostPair = response(withContacts(pool, listOf(hit), engagement))
        val onlyA = response(withContacts(pool, listOf(hit.copy(b = -1)), engagement))
        assertContentEquals(onlyA, ghostPair)
        assertContentEquals(ghostPair, response(withContacts(pool, listOf(hit.copy(a = 1, b = 0)), engagement)))
    }

    @Test
    fun `corrected scenes remain deterministic bounded and close their held filters`() {
        for (voice in FlotillaVoice.entries) {
            val knobs = mapOf("FLOTILLA" to 0.5001f, "VESSEL" to 0.75f)
            val a = Flotilla.renderInternal(voice, knobs, 60, 1f)
            val b = Flotilla.renderInternal(voice, knobs, 60, 1f)
            assertContentEquals(a.snip.samples, b.snip.samples)
            assertTrue(a.snip.samples.all { it.isFinite() && abs(it) <= 0.991f })
            val held = Flotilla.renderInternal(voice, knobs + ("HOLD" to 1f), 60, 1f)
            assertTrue(requireNotNull(held.loop).seam < Keys.MAX_SEAM_ERROR, "$voice seam ${held.loop?.seam}")
            assertTrue(held.snip.samples.all { it.isFinite() && abs(it) <= 0.991f })
        }
    }

    private fun scene(voice: FlotillaVoice, macros: Map<String, Float> = emptyMap()) =
        Flotilla.simulate(voice, macros, 60, 1f, 0.4f, false)

    private fun response(scene: Flotilla.Scene) = Flotilla.contactResponse(FlotillaVoice.RIPPLE, scene, 0.2f, 1f, 0.35f)

    private fun withContacts(scene: Flotilla.Scene, contacts: List<Flotilla.Contact>, engagement: FloatArray = scene.engagement) =
        Flotilla.Scene(scene.count, scene.x0, scene.y0, scene.radii, engagement, scene.mass, scene.detune, scene.angle,
            scene.cavityHz, scene.woodHz, scene.routeWeights, contacts, emptyList(), scene.obstruction, 0f, 0f, 0f, 0f, 0f, 0f,
            false, scene.bytes, scene.steps)

    private fun alignedDifference(reference: FloatArray, changed: FloatArray): Double {
        assertEquals(reference.size, changed.size)
        val norm = changed.sumOf { it.toDouble() * it }.coerceAtLeast(1e-18)
        val gain = reference.indices.sumOf { reference[it].toDouble() * changed[it] } / norm
        val error = reference.indices.sumOf { (reference[it] - gain * changed[it]).let { d -> d * d } }
        return sqrt(error / reference.sumOf { it.toDouble() * it }.coerceAtLeast(1e-18))
    }
}
