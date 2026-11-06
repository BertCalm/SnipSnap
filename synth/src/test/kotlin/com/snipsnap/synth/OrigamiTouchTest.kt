package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** TOUCH must distinguish actual factory strikes while the earlier expression remains replayable. */
class OrigamiTouchTest {
    @Test
    fun `touch curve validates strikes and reaches the old hard response at the factory ceiling`() {
        // Independent values for .85 * .3, .85 * .6 and .85 * 1, not absolute .3/.6/1 strikes.
        for ((velocity, expected) in listOf(0f to .1f, .255f to .181f, .51f to .424f, .85f to 1f, 1f to 1f)) {
            assertEquals(expected, OrigamiExpressions.upperScale(velocity), 1e-6f)
        }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.01f, 1.01f)) {
            assertFailsWith<IllegalArgumentException> { OrigamiExpressions.upperScale(invalid) }
        }
        assertFalse(OrigamiExpressions.colorsWithVelocity(OrigamiExpression.LEVEL))
        assertTrue(OrigamiExpressions.colorsWithVelocity(OrigamiExpression.FOLD))
        assertTrue(OrigamiExpressions.colorsWithVelocity(OrigamiExpression.TOUCH))
    }

    @Test
    fun `old LEVEL and FOLD recipes keep canonical JSON and archived factory waveforms`() {
        val common = """
            {
              "engine": "ORIGAMI",
              "version": 1,
              "name": "Old folded paper",
              "voice": "FAN",
              "macros": {
                "FOLD": 0.5
              },
              "model": 4,
              "midi": 60,
              "velocity": 0.5,
              "seed": "9007199254740993"@EXPRESSION@
            }
        """.trimIndent()
        for (expression in listOf(OrigamiExpression.LEVEL, OrigamiExpression.FOLD)) {
            val field = if (expression == OrigamiExpression.LEVEL) "" else
                ",\n  \"expression\": {\n    \"model\": 1,\n    \"mode\": \"FOLD\"\n  }"
            val text = common.replace("@EXPRESSION@", field)
            val saved = OrigamiPatch.fromJsonText(text)
            assertEquals(expression, saved.expression)
            assertEquals(text, saved.toJsonText(), "adding TOUCH rewrote the old $expression JSON")
        }
        // Archived performance-bank manifest 135823c797124856...: native float hashes,
        // before PCM quantization, of the six unchanged FOLD factories at their authored .85.
        val hashes = mapOf(
            "Paper Bloom" to "9232c8d8637698e28fee044914342ae1771ec1d6b41c4cb8b95a9b0cfbf18e99",
            "Paper Unfold" to "eaa1988701d50b730a9a4c8316cf81361d7765072a78c0cd013f714f702c0e58",
            "Fifth Bloom" to "9b146016adcb8e42849422b2eb38136fccd93a6319cfbfdf583b4b0c5a6aa890",
            "Fifth Unfold" to "e6b34a11620ddfec704e393d4c240ee95230d60612989aa72628a8f7a7d579b2",
            "Soft Bloom" to "b8db66d96ae130cc5366ea062f5a3aa6235632272ed6711f5c20391a8e71d99c",
            "Soft Unfold" to "61af345fab5c03ad90fa0067cc29de633337772df0f1255c375b2b2eb28d5c1a",
        )
        assertEquals(hashes.keys, OrigamiPresets.foldPerformance().map { it.name }.toSet())
        for (factory in OrigamiPresets.foldPerformance()) {
            assertEquals(OrigamiExpression.FOLD, factory.expression, "old factory silently opted into TOUCH")
            assertEquals(hashes.getValue(factory.name), hash(factory.render().samples), factory.name)
        }
    }

    @Test
    fun `touch metadata survives complete recipe editing and cold replay without upgrading old models`() {
        assertEquals(OrigamiPresets.foldPerformance().map { it.copy(expression = OrigamiExpression.TOUCH) },
            OrigamiPresets.performance(), "TOUCH factories changed more than the selected expression mode")
        val source = OrigamiPresets.performance().first { it.voice == OrigamiVoice.FAN }.copy(
            name = "Touch source", expression = OrigamiExpression.TOUCH, midi = 67,
            velocity = .51f, seed = Long.MIN_VALUE + 17,
        )
        val fields = source.toJsonValue().entries
        assertEquals(JsonValue.Obj(linkedMapOf("model" to JsonValue.Num(1.0), "mode" to JsonValue.Str("TOUCH"))),
            fields.getValue("expression"))
        assertEquals(JsonValue.Str(source.seed.toString()), fields.getValue("seed"))
        val reopened = assertIs<OrigamiPatch>(Patches.fromJsonText(source.toJsonText()))
        assertEquals(source, reopened)
        val edited = assertIs<OrigamiPatch>(Patches.edited(reopened, "Edited touch", source.macros + ("FOLD" to .4f)))
        assertEquals(source.copy(name = "Edited touch", macros = source.macros + ("FOLD" to .4f)), edited)
        OrigamiGestureRenderer.clearCache()
        val expected = source.render().samples
        OrigamiGestureRenderer.clearCache()
        val recipe = PadRecipe.fromJsonText(PadRecipe(source).toJsonText())
        assertEquals(source, recipe.patch)
        assertContentEquals(expected, recipe.render().samples)
        for (model in 1..3) {
            assertFailsWith<IllegalArgumentException> { source.copy(model = model, gesture = OrigamiGesture.STEADY) }
        }
        assertFailsWith<JsonException> {
            OrigamiPatch.fromJsonValue(JsonValue.Obj(fields + ("expression" to JsonValue.Obj(mapOf(
                "model" to JsonValue.Num(2.0), "mode" to JsonValue.Str("TOUCH"),
            )))))
        }
    }

    @Test
    fun `touch scales both upper branches including SOURCE and zero fold while keeping the root exact`() {
        for (voice in OrigamiVoice.entries) {
            val factory = OrigamiPresets.foldPerformance().first { it.voice == voice }
            val touch = factory.copy(expression = OrigamiExpression.TOUCH, velocity = factory.velocity * .3f)
            val old = touch.copy(expression = OrigamiExpression.FOLD)
            val root = raw(old, OrigamiDiagnostic.ROOT_ONLY)
            assertContentEquals(root, raw(touch, OrigamiDiagnostic.ROOT_ONLY), "$voice TOUCH moved the raw root")
            for (diagnostic in listOf(OrigamiDiagnostic.COMBINED, OrigamiDiagnostic.SOURCE, OrigamiDiagnostic.FOLDED_ONLY)) {
                val before = raw(old, diagnostic)
                val expected = FloatArray(before.size) { i ->
                    val anchor = if (diagnostic == OrigamiDiagnostic.FOLDED_ONLY) 0f else root[i]
                    (anchor + .181 * (before[i] - anchor)).toFloat()
                }
                assertTrue(relativeError(expected, raw(touch, diagnostic)) < 3e-6,
                    "$voice $diagnostic did not receive exactly one upper excitation gain")
            }
            val zero = touch.copy(macros = touch.macros + ("FOLD" to 0f))
            assertContentEquals(raw(zero, OrigamiDiagnostic.SOURCE), raw(zero),
                "$voice TOUCH zero fold must retain the same touch-colored source")
            assertTrue(normalizedDistance(raw(old, OrigamiDiagnostic.SOURCE), raw(touch, OrigamiDiagnostic.SOURCE), .8, .2) > .1,
                "$voice SOURCE diagnostic concealed TOUCH's deliberate upper-body change")
        }
    }

    @Test
    fun `touch is exact FOLD at hard strikes and velocity zero stays silent for every voice and gesture`() {
        for (voice in OrigamiVoice.entries) for (gesture in OrigamiGesture.entries) {
            val factory = OrigamiPresets.foldPerformance().first { it.voice == voice }.copy(gesture = gesture)
            for (velocity in listOf(.85f, 1f)) {
                val old = factory.copy(velocity = velocity, expression = OrigamiExpression.FOLD)
                val touch = old.copy(expression = OrigamiExpression.TOUCH)
                assertContentEquals(raw(old), raw(touch), "$voice $gesture velocity$velocity raw hard identity")
                assertContentEquals(old.render().samples, touch.render().samples,
                    "$voice $gesture velocity$velocity conditioned hard identity")
            }
            val silent = factory.copy(velocity = 0f, expression = OrigamiExpression.TOUCH).render()
            assertEquals(1, silent.channels)
            assertEquals(Dsp.RATE, silent.sampleRate)
            assertEquals((Origami.durationSeconds(voice) * Dsp.RATE).roundToInt(), silent.frameCount)
            assertTrue(silent.samples.all { it == 0f }, "$voice $gesture TOUCH created a zero-velocity note")
        }
    }

    @Test
    fun `actual factory touches separate adjacent conditioned body colors including settled UNFOLD`() {
        for (factory in OrigamiPresets.performance()) {
            assertEquals(.85f, factory.velocity)
            // The independent PCM prototype measured [gesture end + .12, gesture end + .32).
            val center = OrigamiGestures.endSeconds(factory) + .22
            val touch = listOf(.3f, .6f, 1f).map { relative ->
                factory.copy(expression = OrigamiExpression.TOUCH, velocity = factory.velocity * relative).render().samples
            }
            val differences = touch.zipWithNext().map { (soft, hard) -> normalizedDistance(soft, hard, center, .20) }
            val full = normalizedDistance(touch.first(), touch.last(), center, .20)
            println("ORIGAMI TOUCH ${factory.name} body distances at .255/.51/.85: adjacent $differences, soft-to-hard $full")
            assertTrue(differences.all { it > MINIMUM_BODY_DISTANCE },
                "${factory.name} adjacent TOUCH layers do not meet the independently matched body-contrast bar: $differences")
            assertTrue(full > MINIMUM_FULL_DISTANCE, "${factory.name} TOUCH soft-to-hard body contrast is only $full")
            val oldFactory = OrigamiPresets.foldPerformance().single { it.name == factory.name }
            val previous = listOf(.3f, .6f, 1f).map { relative ->
                oldFactory.copy(velocity = oldFactory.velocity * relative).render().samples
            }
            val oldDistances = previous.zipWithNext().map { (soft, hard) -> normalizedDistance(soft, hard, center, .20) }
            val oldFull = normalizedDistance(previous.first(), previous.last(), center, .20)
            println("ORIGAMI old FOLD ${factory.name} body distances: adjacent $oldDistances, soft-to-hard $oldFull")
            assertTrue(oldDistances.none { it > MINIMUM_BODY_DISTANCE }, "the rejected old FOLD passes the adjacent-layer bar")
            assertTrue(oldFull < MINIMUM_FULL_DISTANCE, "the rejected old FOLD passes the soft-to-hard bar")
        }
    }

    @Test
    fun `held touch layers retain their finite prefix and independently close the continued oscillator field`() {
        for ((voice, gesture) in listOf(OrigamiVoice.FAN to OrigamiGesture.BLOOM, OrigamiVoice.STILL to OrigamiGesture.UNFOLD)) {
            val factory = OrigamiPresets.performance().single { it.voice == voice && it.gesture == gesture }
            for (relative in listOf(.3f, .6f, 1f)) {
                val patch = factory.copy(expression = OrigamiExpression.TOUCH, velocity = factory.velocity * relative)
                val savedText = patch.toJsonText()
                val probe = OrigamiKeys.probe(patch, 60)
                val note = probe.note
                assertContentEquals(patch.render().samples.copyOfRange(0, probe.finitePrefixFrames),
                    note.snip.samples.copyOfRange(0, probe.finitePrefixFrames), "$voice touch$relative finite prefix changed at the held door")
                val loop = note.snip.samples.copyOfRange(probe.loopStartFrame, note.snip.frameCount)
                assertTrue(relativeError(probe.rawLoop, probe.rawNextCycle) < 1e-7, "$voice touch$relative raw next cycle does not close")
                assertTrue(relativeError(loop, probe.nextCycle) < 1e-7, "$voice touch$relative conditioned next cycle does not close")
                assertTrue(probe.maxPhaseClosureCycles < 1e-7, "$voice touch$relative phase integral did not close")
                assertTrue(probe.cycleError < 1e-7)
                assertTrue(abs(probe.rootCents) < .1, "$voice touch$relative retuned the held root")
                assertEquals(note.snip.frameCount - probe.loopStartFrame, probe.loopFrames)
                assertTrue(probe.loopStartFrame.toDouble() / Dsp.RATE >= OrigamiGestures.endSeconds(patch) + .04)
                val step = abs(loop.first() - loop.last())
                val ordinary = (1 until loop.size).maxOf { abs(loop[it] - loop[it - 1]) }
                assertTrue(step <= ordinary * 1.03 + 1e-7, "$voice touch$relative introduced a wrap step")
                assertEquals(savedText, patch.toJsonText(), "held rendering mutated the saved touch recipe")
            }
        }
    }

    @Test
    fun `touch reuses the bounded geometry cache and cancels without publishing partial work`() {
        OrigamiGestureRenderer.clearCache()
        val source = OrigamiPresets.performance().single { it.voice == OrigamiVoice.STILL && it.gesture == OrigamiGesture.BLOOM }
            .copy(expression = OrigamiExpression.TOUCH, velocity = .255f)
        val expected = raw(source)
        val saved = source.toJsonText()
        val stats = OrigamiGestureRenderer.cacheStats()
        assertEquals(1, stats.entryCount)
        for (velocity in listOf(.51f, .85f, 1f)) {
            raw(source.copy(velocity = velocity))
            assertEquals(stats, OrigamiGestureRenderer.cacheStats(), "touch strength duplicated fixed geometry")
        }
        expected[expected.size / 2] = 8f
        val independent = raw(source)
        assertTrue(independent.all { it.isFinite() && abs(it) < 1f }, "caller mutation entered the cache")
        for (seed in listOf(17L, 23L)) raw(source.copy(seed = seed))
        val bounded = OrigamiGestureRenderer.cacheStats()
        assertEquals(2, bounded.entryCount)
        assertTrue(bounded.bytes <= 16_934_400L, "TOUCH exceeded the two-STILL-bank payload budget: $bounded")
        assertEquals(saved, source.toJsonText())
        OrigamiGestureRenderer.clearCache()
        var callbacks = 0
        assertFailsWith<CancellationException> { OrigamiGestureRenderer.renderRaw(source) { ++callbacks >= 20 } }
        assertEquals(0, OrigamiGestureRenderer.cacheStats().entryCount, "cancelled TOUCH published a partial basis")
        assertContentEquals(independent, raw(source), "cancelled work changed the later complete render")
        assertFailsWith<CancellationException> { OrigamiGestureRenderer.render(source) { true } }
        assertFailsWith<CancellationException> { OrigamiKeys.note(source, 60) { true } }
        OrigamiGestureRenderer.clearCache()
    }

    private fun raw(patch: OrigamiPatch, diagnostic: OrigamiDiagnostic = OrigamiDiagnostic.COMBINED) =
        OrigamiGestureRenderer.renderRaw(patch, diagnostic = diagnostic)

    private fun normalizedDistance(a: FloatArray, b: FloatArray, center: Double, seconds: Double): Double {
        val count = (seconds * Dsp.RATE).roundToInt()
        val first = ((center * Dsp.RATE).roundToInt() - count / 2).coerceIn(0, minOf(a.size, b.size) - count)
        val ar = sqrt((first until first + count).sumOf { a[it].toDouble() * a[it] } / count)
        val br = sqrt((first until first + count).sumOf { b[it].toDouble() * b[it] } / count)
        assertTrue(ar > 1e-6 && br > 1e-6, "body contrast window is silent")
        return sqrt((first until first + count).sumOf { val delta = a[it] / ar - b[it] / br; delta * delta } / count)
    }

    private fun relativeError(expected: FloatArray, actual: FloatArray): Double {
        assertEquals(expected.size, actual.size)
        val energy = expected.sumOf { it.toDouble() * it }
        val error = expected.indices.sumOf { val delta = actual[it] - expected[it].toDouble(); delta * delta }
        return sqrt(error / energy.coerceAtLeast(1e-30))
    }

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        // Numerical regression bars, not audibility thresholds. The independent PCM prototype
        // measured minima .179 adjacent and .516 soft-to-hard; the old six factories fail both.
        private const val MINIMUM_BODY_DISTANCE = .12
        private const val MINIMUM_FULL_DISTANCE = .30
    }
}
