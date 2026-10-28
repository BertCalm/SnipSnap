package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Production persistence and rendering contracts; the audited experiment stays unchanged. */
class ScannedChainPatchTest {
    private fun custom(voice: ScannedChainVoice = ScannedChainVoice.BLOOM): ScannedChainPatch = ScannedChainPatch(
        name = "Saved ${voice.name}", voice = voice,
        macros = mapOf("MOTION" to .65f, "DAMP" to .3f, "BEND" to .5f, "BLOOM" to .4f),
        shape = ScannedChainShape.drawn(FloatArray(128) { i ->
            (.17 * sin(2 * PI * i / 128) + .06 * sin(10 * PI * i / 128 + .3)).toFloat()
        }),
        config = ScannedChainConfig.forVoice(voice).copy(restFraction = .13f, seconds = .4f,
            attackSeconds = .01f, releaseSeconds = .08f, seed = 2718),
        midi = 65, velocity = .6f,
    )

    @Test
    fun `saved shapes combined controls and excitation round trip to the exact same sound`() {
        for (voice in ScannedChainVoice.entries) {
            val patch = custom(voice)
            val text = patch.toJsonText()
            val restored = assertIs<ScannedChainPatch>(Patches.fromJsonText(text))
            assertEquals(patch, restored)
            assertEquals(patch, ScannedChainPatch.fromJsonText(text))
            assertEquals(text, restored.toJsonText())
            assertEquals(ScannedChain.MODEL_VERSION.toDouble(), patch.toJsonValue().entries.getValue("model").num())
            assertEquals(Patches.VERSION.toDouble(), patch.toJsonValue().entries.getValue("version").num())
            assertContentEquals(patch.shape.points, restored.shape.points)
            assertContentEquals(patch.render().samples, restored.render().samples, voice.name)
        }
    }

    @Test
    fun `saved shape arrays and macro maps cannot be changed through caller aliases`() {
        val input = FloatArray(128) { (.2 * sin(2 * PI * it / 128)).toFloat() }
        val shape = ScannedChainShape.drawn(input)
        val expected = shape.points
        input.fill(0f)
        assertContentEquals(expected, shape.points)
        shape.points.fill(0f)
        assertContentEquals(expected, shape.points, "the shape getter returns a defensive copy")
        val supplied = shape.points
        val direct = ScannedChainShape(supplied, ScannedChainShapeSource.DRAWN, "Owned coordinates")
        supplied.fill(0f)
        assertContentEquals(expected, direct.points, "the raw-coordinate constructor also owns its input")

        val macros = ScannedChain.defaults(ScannedChainVoice.PLUCK).toMutableMap()
        val patch = ScannedChainPatch("Owned", ScannedChainVoice.PLUCK, macros, shape)
        val before = patch.toJsonText()
        macros["BEND"] = .99f
        macros.clear()
        assertEquals(before, patch.toJsonText(), "a saved patch snapshots its macro values")

        val signedZeros = FloatArray(128) { if (it % 2 == 0) 0f else -0f }
        val signed = patch.copy(shape = ScannedChainShape(signedZeros, ScannedChainShapeSource.DRAWN, "Signed zero"))
        val restored = ScannedChainPatch.fromJsonText(signed.toJsonText())
        assertContentEquals(signedZeros.map { it.toRawBits() }.toIntArray(),
            restored.shape.points.map { it.toRawBits() }.toIntArray(), "JSON retains exact coordinate bits")
    }

    @Test
    fun `editing and native velocity preserve the complete authored source and configuration`() {
        val patch = custom()
        val macros = patch.macros + mapOf("MOTION" to .25f, "BLOOM" to .7f)
        val edited = assertIs<ScannedChainPatch>(Patches.edited(patch, "Edited", macros))
        assertEquals(patch.copy(name = "Edited", macros = macros), edited)
        assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
        assertEquals(patch.shape, edited.shape)
        assertEquals(patch.config, edited.config)
        assertEquals(patch.midi, edited.midi)
        assertEquals(patch.velocity, edited.velocity)
        assertContentEquals(patch.copy(velocity = .35f).render().samples,
            Velocity.atVelocity(patch, .35f).samples,
            "velocity regeneration must use the saved shape, seed and physical settings")
    }

    @Test
    fun `malformed models shapes and physical settings are refused by the typed JSON dispatcher`() {
        val patch = custom()
        val entries = patch.toJsonValue().entries
        val shape = entries.getValue("shape").obj()
        val config = entries.getValue("config").obj()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(entries + (key to value))
        val bad = listOf(
            changed("version", JsonValue.Num(99.0)),
            changed("model", JsonValue.Num(99.0)),
            changed("voice", JsonValue.Str("UNKNOWN")),
            changed("midi", JsonValue.Num(35.0)),
            changed("midi", JsonValue.Num(60.5)),
            changed("velocity", JsonValue.Num(1.01)),
            changed("macros", JsonValue.Obj(mapOf("UNKNOWN" to JsonValue.Num(.5)))),
            changed("shape", JsonValue.Obj(shape + ("points" to JsonValue.Arr(List(127) { JsonValue.Num(0.0) })))),
            changed("shape", JsonValue.Obj(shape + ("points" to JsonValue.Arr(List(128) { JsonValue.Str("not a displacement") })))),
            changed("shape", JsonValue.Obj(shape + ("points" to JsonValue.Arr(List(128) { JsonValue.Num(1000.0) })))),
            changed("shape", JsonValue.Obj(shape + ("source" to JsonValue.Str("UNKNOWN")))),
            changed("config", JsonValue.Obj(config + ("seconds" to JsonValue.Num(4.01)))),
            changed("config", JsonValue.Obj(config + ("restFraction" to JsonValue.Num(.36)))),
            changed("config", JsonValue.Obj(config + ("attackSeconds" to JsonValue.Num(-.1)))),
            changed("config", JsonValue.Obj(config + ("mechanicalHz" to JsonValue.Num(patch.config.mechanicalHz + .01)))),
            changed("config", JsonValue.Obj(config + ("driveHz" to JsonValue.Num(patch.config.driveHz + .01)))),
        )
        for ((index, value) in bad.withIndex()) {
            assertFailsWith<JsonException>("malformed production patch $index must fail before synthesis") {
                Patches.fromJsonValue(value)
            }
        }
    }

    @Test
    fun `programmatic patches reject invalid excitation macros and unsupported model settings`() {
        val patch = custom()
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("MOTION" to value)) }
        }
        for (midi in listOf(35, 97)) assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = ScannedChain.MODEL_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("UNREGISTERED" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(config = patch.config.copy(mechanicalHz = 4f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(config = patch.config.copy(driveHz = 8f)) }
        for (seconds in listOf(.249f, 4.01f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException> { patch.config.copy(seconds = seconds) }
        }
        assertFailsWith<IllegalArgumentException> { ScannedChainShape.drawn(FloatArray(127)) }
        assertFailsWith<IllegalArgumentException> { ScannedChainShape.drawn(FloatArray(128) { Float.NaN }) }
    }

    @Test
    fun `production model one raw physics matches the immutable audited renderer`() {
        val voice = ScannedChainVoice.BLOOM
        val shape = custom(voice).shape
        val config = ScannedChainConfig.forVoice(voice).copy(restFraction = .125f, seconds = .25f,
            attackSeconds = .015625f, releaseSeconds = .125f, seed = 137)
        val macros = mapOf("MOTION" to .5f, "DAMP" to .25f, "BEND" to .5f, "BLOOM" to .5f)
        val actual = ScannedChain.probe(voice, macros, midi = 65, velocity = .5f, shape = shape, config = config, model = 1)
        val audited = ScannedChainPrototype.render(ScannedChainPrototype.Config(
            name = "Port proof", voice = ScannedChainPrototype.Voice.BLOOM, midi = 65, velocity = .5f,
            seconds = config.seconds, motion = .5f, mechanicalHz = config.mechanicalHz,
            dampingPerSecond = 1.5f, nonlinear = 4f, restFraction = config.restFraction,
            drive = .5f, driveHz = config.driveHz, seed = config.seed,
            attackSeconds = config.attackSeconds, releaseSeconds = config.releaseSeconds,
        ), shape.points)
        assertContentEquals(audited.raw.samples, actual.raw.samples)
        assertContentEquals(audited.finalShape, actual.finalShape)
    }

    @Test
    fun `factory presets are registered as finite dry tonal sounds`() {
        val presets = ScannedChainPresets.all()
        assertEquals(9, presets.size)
        assertEquals(ScannedChainVoice.entries.toSet(), presets.map { it.voice }.toSet())
        assertEquals(presets, Presets.all().filter { it.engine == ScannedChainPatch.ENGINE })
        assertEquals(9, presets.map { it.name }.toSet().size)
        for (patch in presets) {
            assertEquals(ScannedChainPresets.forVoice(patch.voice), Presets.forVoice(patch.engine, patch.voiceName))
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(setOf("MOTION", "DAMP", "BEND", "BLOOM"), patch.macros.keys)
            assertEquals(DrumClass.TONAL, ScannedChain.drumClassFor(patch.voice, patch.macros))
            val output = patch.render()
            assertEquals(44_100, output.sampleRate)
            assertEquals(1, output.channels)
            assertTrue(output.samples.all { it.isFinite() })
            assertTrue(output.peak() in 1e-4f..0.900001f)
        }
    }

    @Test
    fun `product finishing preserves excitation level and zero velocity stays silent`() {
        val patch = custom(ScannedChainVoice.PLUCK).copy(
            macros = mapOf("MOTION" to 0f, "DAMP" to .25f, "BEND" to 0f, "BLOOM" to 0f), velocity = .8f,
        )
        val hard = patch.render().samples
        val soft = patch.copy(velocity = .4f).render().samples
        assertTrue(hard.any { abs(it) > 1e-3f })
        assertTrue(hard.indices.maxOf { abs(soft[it] - .5f * hard[it]) } < 1e-6f,
            "the constant finishing gain must not normalize away strike level")
        val silent = custom().copy(velocity = 0f).render()
        assertTrue(silent.samples.all { it == 0f }, "zero energy includes a disabled force")
    }

    @Test
    fun `rough source conditioning uses one cyclic strain gain and keeps undamped driven voices bounded`() {
        val random = Random(90321)
        val input = FloatArray(128) { random.nextFloat() * 2f - 1f }
        val mean = input.average()
        val centered = DoubleArray(128) { input[it] - mean }
        val shape = ScannedChainShape.drawn(input, "Rough regression")
        val conditioned = shape.points
        fun maximumDelta(points: FloatArray): Float = points.indices.maxOf {
            abs(points[(it + 1) % points.size] - points[it])
        }
        assertTrue(maximumDelta(conditioned) <= .250001f)
        assertTrue(conditioned.all { abs(it) <= .350001f })
        val gain = centered.indices.sumOf { centered[it] * conditioned[it] } / centered.sumOf { it * it }
        val residual = sqrt(centered.indices.sumOf {
            val difference = conditioned[it] - gain * centered[it]
            difference * difference
        } / centered.size)
        assertTrue(gain > 0 && gain < 1)
        assertTrue(residual < 1e-7, "conditioning preserves the contour with one initial scalar, without point clipping")

        // The wraparound edge is the largest edge in this shape. Omitting
        // that edge from conditioning would leave a 0.5 displacement jump.
        val wrap = FloatArray(128).also { it[0] = .35f; it[127] = -.35f }
        val wrapped = ScannedChainShape.drawn(wrap).points
        assertTrue(abs(wrapped.first() - wrapped.last()) <= .250001f)
        assertTrue(abs(wrapped.first()) > .1f, "the wrap guard attenuates instead of discarding the shape")

        val peakGain = .35 / centered.maxOf { abs(it) }
        val uncapped = FloatArray(128) { (centered[it] * peakGain).toFloat() }
        assertTrue(maximumDelta(uncapped) > .25f, "the regression input passes the old peak bound but exceeds the strain bound")
        assertFailsWith<IllegalArgumentException> {
            ScannedChainShape(uncapped, ScannedChainShapeSource.DRAWN, "Unconditioned rough")
        }
        val patch = ScannedChainPatch("Rough regression", ScannedChainVoice.PLUCK, shape = shape)
        val entries = patch.toJsonValue().entries
        val invalidShape = JsonValue.Obj((entries.getValue("shape").obj() - "pointBits") +
            ("points" to JsonValue.Arr(uncapped.map { JsonValue.Num(it.toDouble()) })))
        assertFailsWith<JsonException> {
            Patches.fromJsonValue(JsonValue.Obj(entries + ("shape" to invalidShape)))
        }

        val macros = mapOf("MOTION" to 1f, "DAMP" to 0f, "BEND" to 1f, "BLOOM" to 1f)
        for (voice in listOf(ScannedChainVoice.PLUCK, ScannedChainVoice.METAL)) {
            val driven = ScannedChainPatch("Driven rough", voice, macros, shape,
                ScannedChainConfig.forVoice(voice).copy(seconds = 4f), velocity = 1f)
            val audio = driven.render()
            assertEquals(176_400, audio.frameCount)
            assertTrue(audio.samples.all { it.isFinite() }, voice.name)
            assertTrue(audio.peak() in 1e-4f..0.900001f, voice.name)
            assertContentEquals(conditioned, shape.points, "the saved initial shape never evolves in place")
            if (voice == ScannedChainVoice.PLUCK) {
                assertContentEquals(audio.samples, driven.render().samples, "the driven rough source remains deterministic")
            }
        }
    }
}
