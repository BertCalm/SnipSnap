package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Velocity can choose fold depth without changing the authored source, note or one-time gesture clock. */
class OrigamiExpressionTest {
    private val rate = Dsp.RATE

    private fun patch(voice: OrigamiVoice, gesture: OrigamiGesture = OrigamiGesture.STEADY,
                      velocity: Float = .6f, expression: OrigamiExpression = OrigamiExpression.FOLD): OrigamiPatch =
        OrigamiPatch("Touch paper", voice,
            linkedMapOf("TUNE" to .5f, "CREASE" to .5f, "FOLD" to .8f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to .55f),
            midi = 60, velocity = velocity, seed = -9007199254740993L, model = 4,
            gesture = gesture, expression = expression)

    private fun raw(patch: OrigamiPatch, diagnostic: OrigamiDiagnostic = OrigamiDiagnostic.COMBINED): FloatArray =
        renders.computeIfAbsent(patch to diagnostic) { OrigamiGestureRenderer.renderRaw(patch, diagnostic = diagnostic) }

    @Test
    fun `old LEVEL recipes retain canonical JSON and every finite model's original raw audio`() {
        for (model in 1..4) {
            val old = """
                {
                  "engine": "ORIGAMI",
                  "version": 1,
                  "name": "Old paper",
                  "voice": "FAN",
                  "macros": {
                    "FOLD": 0.5
                  },
                  "model": $model,
                  "midi": 60,
                  "velocity": 0.5,
                  "seed": "9007199254740993"
                }
            """.trimIndent()
            val restored = OrigamiPatch.fromJsonText(old)
            assertEquals(OrigamiExpression.LEVEL, restored.expression)
            assertEquals(old, restored.toJsonText())
            assertFalse("expression" in restored.toJsonValue().entries)
            for (voice in OrigamiVoice.entries) {
                val saved = restored.copy(voice = voice)
                assertContentEquals(Origami.renderRaw(voice, saved.macros, saved.velocity, saved.midi, saved.seed, model = model),
                    raw(saved), "$voice model$model LEVEL changed its original renderer")
            }
        }
        // Existing R4 listening manifest, with the accepted reference source and fixed axis.
        for ((voice, expected) in listOf(
            OrigamiVoice.FAN to "d880b25b2c333b23ff7ff0bc2d373eb1879bcd0d8961facd5222d5ebd66ee563",
            OrigamiVoice.STILL to "7066140ba23b4361dc06465d6de62dec6d7ac40ab77f0faabfb188bd5a163cf3",
        )) {
            val saved = patch(voice, expression = OrigamiExpression.LEVEL, velocity = .85f).copy(
                seed = 20261012L, macros = patch(voice).macros + mapOf("FOLD" to .75f, "MOTION" to 0f))
            assertEquals(expected, hash(raw(saved)), "$voice LEVEL changed its archived R4 waveform")
        }
    }

    @Test
    fun `full velocity bypasses fold expression exactly for every voice and gesture`() {
        for (voice in OrigamiVoice.entries) for (gesture in OrigamiGesture.entries) {
            val touch = patch(voice, gesture, 1f)
            val level = touch.copy(expression = OrigamiExpression.LEVEL)
            assertContentEquals(raw(level), raw(touch), "$voice $gesture full velocity changed the authored fold")
            assertContentEquals(level.render().samples, touch.render().samples,
                "$voice $gesture full velocity lost the frozen conditioned path")
            assertEquals(level, OrigamiExpressions.appliedPatch(touch), "full touch must avoid rounding authored controls")
        }
    }

    @Test
    fun `raw audio reveals the intended depth scaling without changing source root geometry or timing`() {
        // Independently evaluated values of the model-1 touch curve; the audio projection is the evidence.
        val scales = listOf(.2f to .3503110457464633, .6f to .646050281557027)
        for (voice in OrigamiVoice.entries) for (gesture in OrigamiGesture.entries) for ((velocity, expected) in scales) {
            val touch = patch(voice, gesture, velocity)
            val level = touch.copy(expression = OrigamiExpression.LEVEL)
            val source = level.copy(gesture = OrigamiGesture.STEADY, macros = level.macros + ("FOLD" to 0f))
            val dry = raw(source)
            val maximum = raw(level)
            val heard = raw(touch)
            val measured = project(heard, dry, maximum)
            val reconstruction = FloatArray(heard.size) { (dry[it] + expected * (maximum[it] - dry[it])).toFloat() }
            println("ORIGAMI expression $voice $gesture velocity$velocity: measured depth scale $measured, expected $expected")
            assertEquals(expected, measured, 1e-5, "$voice $gesture expression depth was missed or applied twice")
            assertTrue(relativeError(reconstruction, heard) < 2e-6,
                "$voice $gesture expression changed the source, oscillator geometry or gesture timing")
            assertContentEquals(raw(level, OrigamiDiagnostic.ROOT_ONLY), raw(touch, OrigamiDiagnostic.ROOT_ONLY),
                "$voice $gesture touch changed the anchor at the same note velocity")
            val effective = OrigamiExpressions.appliedPatch(touch)
            assertEquals(OrigamiExpression.LEVEL, effective.expression)
            assertEquals(effective, OrigamiExpressions.appliedPatch(effective), "effective fold was applied twice")
        }
    }

    @Test
    fun `soft touch changes folded spectral power after matching level instead of acting as another volume control`() {
        val scaleSquared = .4519046489197829.pow(2)
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) for (gesture in OrigamiGesture.entries) {
            val touch = patch(voice, gesture, .35f)
            val level = touch.copy(expression = OrigamiExpression.LEVEL)
            val root = 440.0 * 2.0.pow((60 - 69) / 12.0)
            val target = root * if (voice == OrigamiVoice.FAN) 1.5 else 2.5
            val center = when (gesture) {
                OrigamiGesture.STEADY -> .8
                OrigamiGesture.BLOOM -> OrigamiGestures.endSeconds(level) + .2
                OrigamiGesture.UNFOLD -> OrigamiGestures.startSeconds(voice) +
                    OrigamiGestures.durationSeconds(voice, level.macros.getValue("MOTION")) * .25
            }
            val plain = spectrum(raw(level), center, .16)
            val expressed = spectrum(raw(touch), center, .16)
            val referenceRatio = band(plain, target * .96, target * 1.04) / band(plain, root * .96, root * 1.04)
            val touchRatio = band(expressed, target * .96, target * 1.04) / band(expressed, root * .96, root * 1.04)
            println("ORIGAMI expression $voice $gesture: target/root power $referenceRatio -> $touchRatio, relative ${touchRatio / referenceRatio}")
            assertTrue(referenceRatio > .005, "$voice $gesture fixture has no audible folded destination")
            assertEquals(scaleSquared, touchRatio / referenceRatio, .015,
                "$voice $gesture normalized spectral power does not follow touch depth")
            assertTrue(matchedDifference(raw(level), raw(touch), center, .16) > .05,
                "$voice $gesture soft touch only changed overall gain")
        }
    }

    @Test
    fun `zero fold remains the exact source and zero velocity remains silence across voices and modes`() {
        for (voice in OrigamiVoice.entries) for (gesture in OrigamiGesture.entries) {
            val zeroFold = patch(voice, gesture, .35f).copy(macros = patch(voice).macros +
                mapOf("FOLD" to 0f, "CREASE" to 1f, "TURN" to 0f, "WARP" to 1f))
            assertContentEquals(raw(zeroFold.copy(expression = OrigamiExpression.LEVEL)), raw(zeroFold),
                "$voice $gesture expression colored a zero fold")
            val silent = patch(voice, gesture, 0f)
            val samples = silent.render().samples
            assertEquals((Origami.durationSeconds(voice) * rate).roundToInt(), samples.size)
            assertTrue(samples.all { it == 0f }, "$voice $gesture expression made velocity zero audible")
        }
    }

    @Test
    fun `native relative layers honor the saved velocity ceiling and the absolute adapter keeps its existing contract`() {
        val saved = patch(OrigamiVoice.FAN, OrigamiGesture.BLOOM, .4f)
        val reference = saved.render()
        val layer = Velocity.layerAt(reference, saved, null, .5f, null, useAtVelocity = true)
        val relative = saved.copy(velocity = .2f).render()
        assertContentEquals(Velocity.peakMatch(reference, relative).samples, layer.samples,
            "a relative layer exceeded or ignored the saved expression velocity ceiling")
        assertContentEquals(reference.samples,
            Velocity.layerAt(reference, saved, null, 1f, null, useAtVelocity = true).samples,
            "the highest relative layer must retain the exact saved main articulation")
        assertContentEquals(saved.copy(velocity = .75f).render().samples, Velocity.atVelocity(saved, .75f).samples,
            "the absolute adapter unexpectedly multiplied by the saved ceiling")
        val off = saved.copy(expression = OrigamiExpression.LEVEL)
        val offReference = off.render()
        assertContentEquals(Velocity.peakMatch(offReference, Velocity.atVelocity(off, .5f)).samples,
            Velocity.layerAt(offReference, off, null, .5f, null, useAtVelocity = true).samples,
            "LEVEL changed the existing layerAt contract")
    }

    @Test
    fun `expression metadata survives full signed seeds and rejects unknown versions modes and legacy models`() {
        for (seed in listOf(Long.MIN_VALUE, -9007199254740993L, 9007199254740993L, Long.MAX_VALUE)) {
            val saved = patch(OrigamiVoice.STILL, OrigamiGesture.UNFOLD).copy(seed = seed)
            assertEquals(saved, Patches.fromJsonText(saved.toJsonText()))
            assertEquals(JsonValue.Str(seed.toString()), saved.toJsonValue().entries["seed"])
            assertEquals(mapOf("model" to JsonValue.Num(1.0), "mode" to JsonValue.Str("FOLD")),
                saved.toJsonValue().entries.getValue("expression").obj())
            val edited = assertIs<OrigamiPatch>(Patches.edited(saved, "Edited touch", saved.macros + ("FOLD" to .4f)))
            assertEquals(saved.copy(name = "Edited touch", macros = saved.macros + ("FOLD" to .4f)), edited)
        }
        val saved = patch(OrigamiVoice.FAN)
        val fields = saved.toJsonValue().entries
        for (model in 1..3) {
            assertFailsWith<IllegalArgumentException> { saved.copy(model = model) }
            assertFailsWith<IllegalArgumentException> {
                OrigamiPatch.fromJsonValue(JsonValue.Obj(fields + ("model" to JsonValue.Num(model.toDouble()))))
            }
        }
        for (bad in listOf(
            JsonValue.Str("FOLD"), JsonValue.Obj(emptyMap()),
            JsonValue.Obj(mapOf("mode" to JsonValue.Str("FOLD"))),
            JsonValue.Obj(mapOf("model" to JsonValue.Num(1.0))),
            JsonValue.Obj(mapOf("model" to JsonValue.Num(99.0), "mode" to JsonValue.Str("FOLD"))),
            JsonValue.Obj(mapOf("model" to JsonValue.Num(1.5), "mode" to JsonValue.Str("FOLD"))),
            JsonValue.Obj(mapOf("model" to JsonValue.Num(1.0), "mode" to JsonValue.Str("FUTURE"))),
        )) assertFailsWith<JsonException> {
            OrigamiPatch.fromJsonValue(JsonValue.Obj(fields + ("expression" to bad)))
        }
    }

    @Test
    fun `rendering preserves authored macros and shares immutable geometry across expression velocities`() {
        OrigamiGestureRenderer.clearCache()
        val authored = patch(OrigamiVoice.FAN, OrigamiGesture.BLOOM, .35f)
        val savedText = authored.toJsonText()
        val controls = authored.macros.toMap()
        val first = OrigamiGestureRenderer.renderRaw(authored)
        val expected = first.copyOf()
        val stats = OrigamiGestureRenderer.cacheStats()
        assertEquals(1, stats.entryCount)
        assertTrue(stats.bytes > 0)
        for (velocity in listOf(.2f, .6f, 1f)) {
            OrigamiGestureRenderer.renderRaw(authored.copy(velocity = velocity))
            assertEquals(stats, OrigamiGestureRenderer.cacheStats(), "touch rebuilt or duplicated fixed oscillator geometry")
        }
        first[first.size / 3] = 8f
        assertContentEquals(expected, OrigamiGestureRenderer.renderRaw(authored), "output mutation reached the shared basis")
        val rendered = authored.render()
        assertTrue(rendered.samples.all { it.isFinite() && abs(it) <= .99001f })
        assertEquals(controls, authored.macros, "rendering overwrote the authored FOLD knob with an effective value")
        assertEquals(savedText, authored.toJsonText(), "a render changed the saved expression recipe")
        OrigamiGestureRenderer.clearCache()
        var callbacks = 0
        assertFailsWith<CancellationException> {
            OrigamiGestureRenderer.renderRaw(authored) { ++callbacks >= 3 }
        }
        assertEquals(3, callbacks)
        assertEquals(0, OrigamiGestureRenderer.cacheStats().entryCount, "cancelled work published a partial geometry bank")
        assertContentEquals(expected, OrigamiGestureRenderer.renderRaw(authored))
        assertFailsWith<CancellationException> { OrigamiGestureRenderer.render(authored) { true } }
    }

    @Test
    fun `held attack and settled body use the same effective depth and never apply touch again at the wrap`() {
        val cases = listOf(
            OrigamiVoice.FAN to OrigamiGesture.STEADY,
            OrigamiVoice.STILL to OrigamiGesture.BLOOM,
            OrigamiVoice.FAN to OrigamiGesture.UNFOLD,
        )
        val scale = .4519046489197829
        for ((voice, gesture) in cases) {
            val touch = patch(voice, gesture, .35f)
            val originalText = touch.toJsonText()
            val level = touch.copy(expression = OrigamiExpression.LEVEL)
            val effective = level.copy(macros = level.macros + ("FOLD" to (.8 * scale).toFloat()))
            val held = OrigamiKeys.probe(touch, 60)
            val off = OrigamiKeys.probe(level, 60)
            val prefix = held.finitePrefixFrames
            assertContentEquals(effective.render().samples.copyOfRange(0, prefix),
                held.note.snip.samples.copyOfRange(0, prefix), "$voice $gesture held prefix applied expression twice or skipped it")
            assertEquals(touch.toJsonText(), originalText)
            assertTrue(relativeError(held.rawLoop, held.rawNextCycle) < 1e-7)
            val loop = held.note.snip.samples.copyOfRange(held.loopStartFrame, held.note.snip.frameCount)
            assertTrue(relativeError(loop, held.nextCycle) < 1e-7)
            assertTrue(held.maxPhaseClosureCycles < 1e-7)
            assertTrue(abs(held.rootCents) < .1)
            val root = held.effectiveHz
            val measured = FineTuning.measuredHz(loop, rate, root.toFloat(), .1f, .7f)
            assertTrue(abs(FineTuning.cents(measured, root)) < .5, "$voice $gesture expression retuned held pitch")
            val step = abs(loop.first() - loop.last())
            val ordinary = (1 until loop.size).maxOf { abs(loop[it] - loop[it - 1]) }
            assertTrue(step <= ordinary * 1.03 + 1e-7, "$voice $gesture touch introduced a loop-boundary step")
            if (gesture == OrigamiGesture.UNFOLD) {
                assertContentEquals(off.rawLoop, held.rawLoop, "UNFOLD must settle on the identical source, regardless of touch")
            } else {
                val target = root * if (voice == OrigamiVoice.FAN) 1.5 else 2.5
                for (position in listOf(.125, .375, .625, .875)) {
                    val center = held.loopFrames.toDouble() / rate * position
                    val expressed = spectrum(held.rawLoop, center, .35)
                    val unexpressed = spectrum(off.rawLoop, center, .35)
                    fun ratio(s: Spectrum) = band(s, target * .96, target * 1.04) / band(s, root * .96, root * 1.04)
                    assertEquals(scale * scale, ratio(expressed) / ratio(unexpressed), .015,
                        "$voice $gesture held touch changed or retriggered at loop position $position")
                }
            }
            if (gesture != OrigamiGesture.STEADY) assertTrue(
                held.loopStartFrame.toDouble() / rate >= OrigamiGestures.endSeconds(touch) + .04,
                "$voice $gesture loop began before its one-time color movement finished")
        }
    }

    @Test
    fun `held full velocity is exact and LEVEL keeps the accepted model one WAV bytes`() {
        val full = patch(OrigamiVoice.FAN, velocity = 1f)
        val held = OrigamiKeys.probe(full, 60)
        val off = OrigamiKeys.probe(full.copy(expression = OrigamiExpression.LEVEL), 60)
        assertEquals(off.note.loopStartFrame, held.note.loopStartFrame)
        assertContentEquals(off.note.snip.samples, held.note.snip.samples)
        val accepted = OrigamiPresets.forVoice(OrigamiVoice.FAN).first()
        assertEquals(OrigamiExpression.LEVEL, accepted.expression)
        val note = OrigamiKeys.note(accepted, 60)
        val wav = ByteArrayOutputStream().also { WavWriter.write(it, note.snip,
            smpl = SmplChunk(60, SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong()))) }
        val expected = "c23c15629b09fb6002d74e9c0cbf7edb439e5f67a6864c5d76da8cbb75f8ce07"
        assertEquals(expected, hex(MessageDigest.getInstance("SHA-256").digest(wav.toByteArray())),
            "LEVEL changed the accepted PR612 FAN C4 sampler-sheet WAV")
    }

    private data class Spectrum(val powers: DoubleArray, val binHz: Double)

    private fun spectrum(samples: FloatArray, center: Double, seconds: Double): Spectrum {
        val count = (seconds * rate).roundToInt()
        val first = ((center * rate).roundToInt() - count / 2).coerceIn(0, samples.size - count)
        val real = FloatArray(65536)
        val imaginary = FloatArray(real.size)
        for (i in 0 until count) real[i] = (samples[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(real, imaginary)
        return Spectrum(DoubleArray(real.size / 2) { real[it].toDouble() * real[it] + imaginary[it].toDouble() * imaginary[it] },
            rate.toDouble() / real.size)
    }

    private fun band(s: Spectrum, low: Double, high: Double): Double =
        s.powers.indices.filter { it * s.binHz in low..high }.sumOf { s.powers[it] }

    private fun project(heard: FloatArray, dry: FloatArray, folded: FloatArray): Double {
        var signal = 0.0
        var contrast = 0.0
        for (i in heard.indices) {
            val d = folded[i] - dry[i].toDouble()
            signal += (heard[i] - dry[i]) * d
            contrast += d * d
        }
        assertTrue(contrast > 1e-8, "fixture has no actual fold contrast")
        return signal / contrast
    }

    private fun relativeError(expected: FloatArray, actual: FloatArray): Double {
        assertEquals(expected.size, actual.size)
        val error = expected.indices.sumOf { val d = actual[it] - expected[it].toDouble(); d * d }
        return sqrt(error / expected.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30))
    }

    private fun matchedDifference(a: FloatArray, b: FloatArray, center: Double, seconds: Double): Double {
        val count = (seconds * rate).roundToInt()
        val first = ((center * rate).roundToInt() - count / 2).coerceIn(0, a.size - count)
        val ar = sqrt((first until first + count).sumOf { a[it].toDouble() * a[it] } / count)
        val br = sqrt((first until first + count).sumOf { b[it].toDouble() * b[it] } / count)
        return sqrt((first until first + count).sumOf { val d = a[it] / ar - b[it] / br; d * d } / count)
    }

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private val renders = ConcurrentHashMap<Pair<OrigamiPatch, OrigamiDiagnostic>, FloatArray>()
    }
}
