package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spectral motion changes oscillator amplitudes while the original carrier and note model survive. */
class PrismMotionTest {
    private val voice = PrismVoice.VEIL
    private val macros = Prism.defaults(voice) + mapOf("DRIFT" to 0f, "GRAIN" to 0f, "HOLD" to .25f)
    private val template = Prism.templateFor(voice).copy(attackSeconds = .01f, decaySeconds = 4f)
    private val endpoint = List(Prism.PARTIALS) { i -> if (i == 0) 1f else if (i % 3 == 0) .9f else .015f }

    private fun spectralMass(samples: FloatArray, hz: Double, from: Double, seconds: Double = .16): DoubleArray {
        val start = (from * Dsp.RATE).roundToInt()
        val count = (seconds * Dsp.RATE).roundToInt().coerceAtMost(samples.size - start)
        require(count > 256)
        var n = 16384
        while (n < count) n *= 2
        val real = FloatArray(n)
        val imaginary = FloatArray(n)
        for (i in 0 until count) real[i] = samples[start + i] *
            (.5 - .5 * cos(2.0 * PI * i / (count - 1))).toFloat()
        Fft.forward(real, imaginary)
        val mass = DoubleArray(Prism.PARTIALS)
        for (k in 1 until n / 2) {
            val harmonic = (k * Dsp.RATE.toDouble() / n / hz).roundToInt() - 1
            if (harmonic in mass.indices) mass[harmonic] += real[k].toDouble() * real[k] + imaginary[k].toDouble() * imaginary[k]
        }
        val sum = mass.sum()
        require(sum > 1e-20)
        return DoubleArray(mass.size) { mass[it] / sum }
    }

    private fun distance(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test
    fun `legacy motion without authored A retains captured recipe bytes and exact null A replay`() {
        // Captured before amplitudesA existed: source palette at 648b7a1, SOFT KEYS motion.
        // The fixture keeps the measured B, rather than rerunning its donor analyzer.
        val b = listOf(
            .9516389966011047f, .039345335215330124f, .011772933416068554f,
            .006825566291809082f, .008232474327087402f, .008877857588231564f,
            .00529437605291605f, .002574586309492588f, .001752364682033658f, .0012218364281579852f,
        ) + List(22) { .001f }
        val original = PrismPatch("SOFT KEYS moving through PRISM", PrismVoice.KEY,
            mapOf("TUNE" to 10f / 24f, "GRAIN" to 0f, "DRIFT" to 0f, "SHAPE" to .4f,
                "STRIKE" to .4f, "BODY" to .5f, "HOLD" to .8f),
            velocity = .8f,
            template = PrismTemplate(PrismSpectrum.ROUNDED, PrismContour.HELD,
                ratioOverrides = List(32) { it + 1f }, amplitudeOverrides = List(32) { 1f / (it + 1f) },
                groupProfiles = List(5) { PrismGroupProfile() }, attackSeconds = .04f, decaySeconds = 2.6f),
            motion = PrismMotion(b, evolve = 1f, seconds = .8f))
        val recipe = PadRecipe(original)
        val text = recipe.toJsonText()
        assertEquals("3c7c8398a825c551c701168fec5143678c6c2de6cc69183803de02a5faa41903", digest(text.toByteArray()))
        val reopened = PadRecipe.fromJsonText(text)
        val savedMotion = assertIs<PrismPatch>(reopened.patch).motion!!
        assertNull(savedMotion.amplitudesA)
        assertFalse("amplitudesA" in savedMotion.toJsonValue().entries)
        assertEquals(text, PadRecipe(original.copy(motion = savedMotion.copy(amplitudesA = null))).toJsonText())
        val explicitNull = PrismMotion.fromJsonValue(JsonValue.Obj(savedMotion.toJsonValue().entries +
            ("amplitudesA" to JsonValue.Null)))
        assertEquals(Json.write(savedMotion.toJsonValue()), Json.write(explicitNull.toJsonValue()))
        val samples = reopened.render().samples
        assertContentEquals(samples, original.copy(motion = explicitNull).render().samples)
    }

    @Test
    fun `absent motion leaves legacy serialization and explicit null replay unchanged`() {
        val original = PrismPatch("Legacy note", PrismVoice.KEY, Prism.defaults(PrismVoice.KEY), velocity = .7f)
        assertFalse("motion" in original.toJsonValue().entries)
        assertEquals(original.toJsonText(), original.copy(motion = null).toJsonText())
        val restored = PrismPatch.fromJsonText(original.toJsonText())
        assertNull(restored.motion)
        val implicit = Prism.probe(PrismVoice.KEY, velocity = .7f, seconds = .3f)
        val explicit = Prism.probe(PrismVoice.KEY, velocity = .7f, seconds = .3f, motion = null)
        assertContentEquals(implicit.raw, explicit.raw)
        assertContentEquals(implicit.samples, explicit.samples)
        assertContentEquals(original.render().samples, restored.render().samples)
    }

    @Test
    fun `static endpoints and finite spectral travel differ without changing carrier frequencies`() {
        val base = PrismMotion(endpoint)
        val a = Prism.probe(voice, macros, template = template, seconds = 1.1f, motion = base)
        val b = Prism.probe(voice, macros, template = template, seconds = 1.1f, motion = base.copy(morph = 1f))
        val moving = Prism.probe(voice, macros, template = template, seconds = 1.1f,
            motion = base.copy(evolve = 1f, seconds = .45f))
        assertContentEquals(a.partialHz, b.partialHz)
        assertContentEquals(a.partialHz, moving.partialHz)
        val hz = a.effectiveHz
        val earlyA = spectralMass(a.samples, hz, .025)
        val earlyMoving = spectralMass(moving.samples, hz, .025)
        val lateA = spectralMass(a.samples, hz, .7)
        val lateB = spectralMass(b.samples, hz, .7)
        val lateMoving = spectralMass(moving.samples, hz, .7)
        assertTrue(distance(lateA, lateB) > .15, "B must redistribute audible harmonic power")
        assertTrue(distance(lateMoving, lateB) < .001, "completed evolution reaches B on the same envelopes")
        assertTrue(distance(earlyMoving, earlyA) < distance(lateMoving, lateA), "travel begins near A and develops toward B")
        val staggered = Prism.probe(voice, macros, template = template, seconds = 1.1f,
            motion = base.copy(evolve = 1f, seconds = .6f, groupLag = 1f))
        assertTrue(distance(spectralMass(staggered.samples, hz, .5), spectralMass(moving.samples, hz, .5)) > .025,
            "lag makes spectral groups travel at different times")
    }

    @Test
    fun `authored warm A changes the opening while retaining the destination and phase realization`() {
        val originalMotion = PrismMotion(endpoint, evolve = 1f, seconds = .6f)
        val warm = List(Prism.PARTIALS) { i -> if (i == 0) 1f else .08f / ((i + 1f) * (i + 1f)) }
        val original = Prism.probe(voice, macros, template = template, seconds = 1.15f, motion = originalMotion)
        val changed = Prism.probe(voice, macros, template = template, seconds = 1.15f,
            motion = originalMotion.copy(amplitudesA = warm))
        assertContentEquals(original.partialHz, changed.partialHz)
        assertEquals(original.effectiveHz, changed.effectiveHz)
        assertTrue(distance(spectralMass(original.samples, original.effectiveHz, .025),
            spectralMass(changed.samples, changed.effectiveHz, .025)) > .1, "A must change audible opening harmonics")
        assertTrue(distance(spectralMass(original.samples, original.effectiveHz, .9),
            spectralMass(changed.samples, changed.effectiveHz, .9)) < .001, "both openings arrive at the same B colour")
        // Once the trajectory settles, the raw signals must agree up to fixed headroom gain.
        // Changing the template used for phase seeding would destroy this agreement.
        val start = (.9 * Dsp.RATE * Dsp.OVERSAMPLE).roundToInt()
        val end = (1.1 * Dsp.RATE * Dsp.OVERSAMPLE).roundToInt()
        var cross = 0.0
        var energy = 0.0
        var changedEnergy = 0.0
        for (i in start until end) {
            cross += original.raw[i].toDouble() * changed.raw[i]
            energy += original.raw[i].toDouble() * original.raw[i]
            changedEnergy += changed.raw[i].toDouble() * changed.raw[i]
        }
        val gain = cross / energy
        val error = (start until end).sumOf { i ->
            val difference = changed.raw[i] - gain * original.raw[i]
            difference * difference
        }
        assertTrue(error / changedEnergy < 4e-12, "A leaves B's carrier phases and envelopes intact")
        assertTrue(changed.partialWeights.drop(1).all { it <= changed.partialWeights.first() / 1.119f })
        assertTrue(changed.raw.all { it.isFinite() && abs(it) <= Prism.RAW_PEAK_CEILING + 1e-6f })
    }

    @Test
    fun `motion recipes retain every coefficient and replay through pad saving and edits`() {
        val motion = PrismMotion(Prism.motionEndpoint(voice, PrismMotionColor.VOWEL, macros, template),
            morph = .15f, evolve = .85f, seconds = .8f, groupLag = .4f,
            amplitudesA = List(Prism.PARTIALS) { 1f / ((it + 1f) * (it + 1f)) })
        val original = PrismPatch("Travelling vowel", voice, macros, velocity = .6f, template = template, motion = motion)
        val recipe = PadRecipe(original)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        val patch = assertIs<PrismPatch>(restored.patch)
        assertEquals(original, patch)
        assertEquals(motion, patch.motion)
        assertContentEquals(recipe.render().samples, restored.render().samples)
        val edited = assertIs<PrismPatch>(Patches.edited(patch, "Edited travel", macros + ("BODY" to .7f)))
        assertEquals(motion, edited.motion)
        assertEquals(template, edited.template)
        assertEquals(original.velocity, edited.velocity)
    }

    @Test
    fun `moving spectra preserve root and bound raw gain across registers`() {
        for (selected in PrismVoice.entries) for (tune in listOf(0f, 1f)) {
            val m = Prism.defaults(selected) + mapOf("TUNE" to tune, "GRAIN" to 1f, "STRIKE" to 1f, "DRIFT" to 1f)
            val endpointB = Prism.motionEndpoint(selected, PrismMotionColor.REED, m)
            val endpointA = endpointB.mapIndexed { i, b -> b / (1f + i * i) }
            val motion = PrismMotion(endpointB, morph = .4f, evolve = 1f, seconds = .1f, groupLag = 1f, amplitudesA = endpointA)
            val p = Prism.probe(selected, m, seconds = .4f, motion = motion)
            val baseline = Prism.probe(selected, m, seconds = .05f)
            assertContentEquals(baseline.partialHz, p.partialHz, "$selected retains its tuning map")
            assertEquals(Prism.frequencyFor(selected, tune).toDouble(), p.effectiveHz)
            assertTrue(p.partialHz.all { it < Prism.USABLE_BANDWIDTH_HZ })
            assertTrue(p.partialWeights.drop(1).all { it <= p.partialWeights.first() / 1.119f }, "$selected retains a pitch anchor")
            assertTrue(p.raw.all { it.isFinite() })
            assertTrue(p.raw.maxOf { abs(it) } <= Prism.RAW_PEAK_CEILING + 1e-6f, "$selected raw headroom before normalization")
        }
        val silent = Prism.probe(voice, macros, velocity = 0f, seconds = .2f,
            motion = PrismMotion(endpoint, evolve = 1f, seconds = .1f))
        assertTrue(silent.raw.all { it == 0f })
        assertTrue(silent.samples.all { it == 0f })
    }

    @Test
    fun `spectral endpoint helpers preserve actual ratios and use fixed Hz colour`() {
        val custom = Prism.templateFor(PrismVoice.GLASS).copy(ratioOverrides = listOf(1f, 2.7f, 4.3f))
        val frequencies = Prism.partialFrequencies(PrismVoice.GLASS, mapOf("TUNE" to 0f), custom)
        val hz = Prism.frequencyFor(PrismVoice.GLASS, 0f)
        assertEquals(32, frequencies.size)
        assertEquals(hz * 2.7f, frequencies[1])
        assertEquals(hz * 4.3f, frequencies[2])
        assertEquals(32, Prism.amplitudeEndpoint(custom).size)
        for (color in PrismMotionColor.entries) {
            val low = Prism.motionEndpoint(PrismVoice.KEY, color, mapOf("TUNE" to 0f))
            val high = Prism.motionEndpoint(PrismVoice.KEY, color, mapOf("TUNE" to 1f))
            assertEquals(32, low.size)
            assertTrue(low.all { it.isFinite() && it in 0f..2f })
            assertTrue(low.first() >= .05f)
            assertTrue(low != high, "$color samples the actual register")
        }
    }

    @Test
    fun `invalid motion and held motion are refused instead of silently falling back`() {
        assertFailsWith<IllegalArgumentException> { PrismMotion(endpoint.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { PrismMotion(endpoint, amplitudesA = endpoint.dropLast(1)) }
        for (bad in listOf(-.1f, 2.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { PrismMotion(endpoint.toMutableList().also { it[3] = bad }) }
            assertFailsWith<IllegalArgumentException> { PrismMotion(endpoint, amplitudesA = endpoint.toMutableList().also { it[3] = bad }) }
        }
        assertFailsWith<IllegalArgumentException> { PrismMotion(endpoint.toMutableList().also { it[0] = 0f }) }
        assertFailsWith<IllegalArgumentException> { PrismMotion(endpoint, amplitudesA = endpoint.toMutableList().also { it[0] = 0f }) }
        val valid = PrismMotion(endpoint, amplitudesA = endpoint)
        for (bad in listOf(-.1f, 1.1f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException> { valid.copy(morph = bad) }
            assertFailsWith<IllegalArgumentException> { valid.copy(evolve = bad) }
            assertFailsWith<IllegalArgumentException> { valid.copy(groupLag = bad) }
        }
        for (bad in listOf(0f, .09f, 6.1f, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { valid.copy(seconds = bad) }
        }
        assertFailsWith<IllegalArgumentException> { Prism.renderLoop(voice, motion = valid) }
        assertFailsWith<IllegalArgumentException> { Prism.render(voice, mapOf("HOLD" to 1f), motion = valid) }
        assertFailsWith<IllegalArgumentException> { PrismPatch("Invalid loop", voice, mapOf("HOLD" to 1f), motion = valid) }
        val patch = PrismPatch("Finite", voice, macros, motion = valid)
        assertFailsWith<IllegalArgumentException> { patch.withMacros(macros + ("HOLD" to 1f)) }
        val invalidJson = JsonValue.Obj(patch.toJsonValue().entries + ("motion" to JsonValue.Obj(valid.toJsonValue().entries +
            ("seconds" to JsonValue.Num(0.0)))))
        assertFailsWith<IllegalArgumentException> { PrismPatch.fromJsonValue(invalidJson) }
        val builder = endpoint.toMutableList()
        val frozen = PrismMotion(builder)
        val frozenA = valid.copy(amplitudesA = builder)
        builder[4] = 0f
        assertEquals(endpoint, frozen.amplitudesB, "caller mutation must not change a saved sound")
        assertEquals(endpoint, frozenA.amplitudesA, "caller mutation must not change saved A")
    }
}
