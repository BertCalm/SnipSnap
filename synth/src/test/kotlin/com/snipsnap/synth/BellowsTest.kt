package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Audible source behavior, saved recipes and the exact samples listeners receive. */
class BellowsTest {
    @Test
    fun `finite voices are deterministic bounded and release to silence including zero velocity`() {
        for (voice in BellowsVoice.entries) {
            val macros = Bellows.defaults(voice) + mapOf("HOLD" to .35f, "TUNE" to .5f)
            val first = Bellows.render(voice, macros)
            assertContentEquals(first.samples, Bellows.render(voice, macros).samples, "$voice fresh state")
            assertEquals(1, first.channels)
            assertEquals(44_100, first.sampleRate)
            assertTrue(first.frameCount in 8_820..529_200, "$voice finite duration")
            assertTrue(first.samples.all { it.isFinite() && abs(it) <= 1f }, "$voice unsafe PCM")
            assertTrue(BellowsTestAudio.rms(first.samples) > .001, "$voice silent positive-velocity render")
            assertTrue(BellowsTestAudio.rms(first.samples, first.samples.size - 256) < 1e-4, "$voice did not release")
            for (hold in listOf(.35f, 1f)) {
                val silent = Bellows.render(voice, macros + ("HOLD" to hold), velocity = 0f)
                assertTrue(silent.samples.all { it == 0f }, "$voice HOLD $hold zero velocity excites reeds")
            }
        }
    }

    @Test
    fun `all source models land on C3 C4 and C5 with quiet motion and no drone`() {
        for (voice in BellowsVoice.entries) for (midi in listOf(48, 60, 72)) {
            val macros = Bellows.defaults(voice) + mapOf("TUNE" to 0f, "MOTION" to 0f,
                "HOLD" to .45f, "PRESSURE" to .55f) +
                Bellows.defaults(voice).keys.filter { it in setOf("DRONE", "BEAT") }.associateWith { 0f }
            val snip = Bellows.render(voice, macros, midi = midi, gesture = BellowsGesture.STEADY)
            val expected = Keys.midiHz(midi)
            val hz = FineTuning.measuredHz(snip, expected, .35f, .3f)
            val cents = FineTuning.cents(hz, expected.toDouble())
            println("BELLOWS $voice MIDI $midi: $cents cents")
            assertTrue(abs(cents) < 12, "$voice MIDI $midi root is $cents cents away")
        }
    }

    @Test
    fun `velocity changes reed spectrum instead of merely scaling the same recording`() {
        for (voice in BellowsVoice.entries) {
            val macros = Bellows.defaults(voice) + mapOf("TUNE" to .5f, "HOLD" to .45f, "MOTION" to 0f)
            val soft = Bellows.renderRaw(voice, macros, velocity = .25f, gesture = BellowsGesture.STEADY)
            val hard = Bellows.renderRaw(voice, macros, velocity = 1f, gesture = BellowsGesture.STEADY)
            val spectrumDistance = BellowsTestAudio.harmonicDistance(soft.samples, hard.samples, Keys.midiHz(60).toDouble())
            println("BELLOWS $voice velocity spectral distance: $spectrumDistance")
            assertTrue(spectrumDistance > .012, "$voice velocity only changed level ($spectrumDistance)")
            assertTrue(BellowsTestAudio.rms(soft.samples) < BellowsTestAudio.rms(hard.samples), "$voice soft excitation is louder")
        }
    }

    @Test
    fun `every timbral control changes the source after best-fit scalar gain is removed`() {
        for (voice in BellowsVoice.entries) {
            val base = Bellows.defaults(voice) + mapOf("HOLD" to .4f, "MOTION" to .7f)
            for (macro in Bellows.macrosFor(voice).map { it.name }.filter { it !in setOf("TUNE", "HOLD") }) {
                val low = Bellows.renderRaw(voice, base + (macro to .1f)).samples
                val high = Bellows.renderRaw(voice, base + (macro to .9f)).samples
                val residual = BellowsTestAudio.scalarResidual(low, high)
                println("BELLOWS $voice $macro residual: $residual")
                assertTrue(residual > .015, "$voice $macro is silent or only a gain control ($residual)")
            }
        }
    }

    @Test
    fun `wheel bridge is silent below its pressure threshold and chatter follows crossings`() {
        val voice = BellowsVoice.WHEEL
        val base = Bellows.defaults(voice) + mapOf("TUNE" to .5f, "HOLD" to .5f, "MOTION" to 0f, "DRONE" to 0f)
        fun raw(pressure: Float, buzz: Float) = Bellows.renderRaw(voice,
            base + mapOf("PRESSURE" to pressure, "BUZZ" to buzz), gesture = BellowsGesture.STEADY).samples
        assertContentEquals(raw(.2f, 0f), raw(.2f, 1f), "below threshold must not add buzz")
        val residual = BellowsTestAudio.scalarResidual(raw(.95f, 0f), raw(.95f, 1f))
        assertTrue(residual > .02, "above threshold has no source change: $residual")
        val crossing = base + mapOf("PRESSURE" to .84f, "MOTION" to .66f, "BUZZ" to .88f)
        val pressure = (0..1000).map { Bellows.pressureAt(voice, crossing, it / 1000.0, gesture = BellowsGesture.PULSE) }
        assertTrue(pressure.min() < .49f && pressure.max() > .55f,
            "pulse should cross the bridge threshold, observed ${pressure.min()}..${pressure.max()}")
    }

    @Test
    fun `held voices close across their played wrap after sixteen bit export`() {
        for (voice in BellowsVoice.entries) for (tune in listOf(0f, .5f, 1f)) {
            val macros = Bellows.defaults(voice) + mapOf("HOLD" to 1f, "TUNE" to tune)
            val loop = Bellows.renderLoop(voice, macros)
            assertContentEquals(loop, Bellows.render(voice, macros).samples, "$voice public HOLD source")
            assertTrue(loop.size >= 256 && BellowsTestAudio.rms(loop) > .001, "$voice absent loop")
            val bytes = ByteArrayOutputStream().also {
                WavWriter.write(it, Snip(loop, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16,
                    SmplChunk(48 + (tune * 24).toInt(), SmplChunk.Loop(0, loop.size.toLong())))
            }.toByteArray()
            val quantized = WavReader.read(bytes).samples
            val wrap = BellowsTestAudio.wrap(quantized)
            println("BELLOWS $voice TUNE $tune PCM16 wrap: step=${wrap.first}, kink=${wrap.second}")
            assertTrue(wrap.first <= 1.5, "$voice $tune wrap step is ${wrap.first} times the interior maximum")
            assertTrue(wrap.second <= 1.5, "$voice $tune wrap slope kinks ${wrap.second} times the interior maximum")
            assertEquals(SmplChunk(48 + (tune * 24).toInt(), SmplChunk.Loop(0, loop.size.toLong())), WavReader.readSmpl(bytes))
            assertEquals(DrumClass.LOOP, Bellows.drumClassFor(voice, macros))
        }
    }

    @Test
    fun `saved patch state survives editing recipes and native velocity rendering`() {
        for (voice in BellowsVoice.entries) {
            val patch = BellowsPatch("Saved $voice", voice, Bellows.defaults(voice) + ("HOLD" to .3f),
                midi = 55, velocity = .65f, gesture = BellowsGesture.ACCENT)
            assertEquals(patch, BellowsPatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val recipe = PadRecipe(patch)
            val back = PadRecipe.fromJsonText(recipe.toJsonText())
            assertContentEquals(recipe.render().samples, back.render().samples, "$voice saved performance")
            val editedMacros = patch.macros + ("BODY" to .75f)
            assertEquals(patch.copy(name = "Edited", macros = editedMacros), Patches.edited(patch, "Edited", editedMacros))
            assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
            assertFalse(recipe.alias)
        }
    }

    @Test
    fun `eight dry presets are discoverable and complete with versioned player gestures`() {
        val presets = BellowsPresets.all()
        assertEquals(8, presets.size)
        assertEquals(BellowsVoice.entries.toSet(), presets.map { it.voice }.toSet())
        assertEquals(8, presets.map { it.voice to it.name }.toSet().size)
        assertTrue(Presets.all().containsAll(presets))
        for (patch in presets) {
            assertEquals(Bellows.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys, patch.name)
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(Bellows.MODEL_VERSION, patch.model)
            assertEquals(null, Presets.landingFor(patch.engine, patch.voiceName, patch.macros), "${patch.name} lands dry")
        }
    }

    @Test
    fun `foreign versions invalid controls and unsupported models cannot masquerade as this recipe`() {
        val patch = BellowsPatch("Valid", BellowsVoice.CHAMBER, Bellows.defaults(BellowsVoice.CHAMBER))
        val foreign = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { it["model"] = JsonValue.Num(99.0) })
        assertFailsWith<JsonException> { Patches.fromJsonValue(foreign) }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 99) }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = bad) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("PRESSURE" to bad)) }
        }
    }

    @Test
    fun `the standard kit export retains the exact regenerable dry recipe and velocity files`() {
        val root = Files.createTempDirectory("bellows-export").toFile()
        try {
            val patch = BellowsPatch("Export", BellowsVoice.COUNTER,
                Bellows.defaults(BellowsVoice.COUNTER) + ("HOLD" to .2f), midi = 48, gesture = BellowsGesture.ACCENT)
            val recipe = PadRecipe(patch)
            val rendered = patch.render()
            val kitDir = root.resolve("kit")
            val kit = KitAssembler.assembleArranged("Bellows Export", listOf(ArrangedPad(rendered, DrumClass.TONAL,
                recipe = recipe.toJsonValue(), softVariants = listOf(Velocity.atVelocity(patch, .35f)))), kitDir)
            val saved = KitStore.load(kitDir)
            val restored = PadRecipe.fromJsonValue(requireNotNull(saved.pads.single().recipe))
            assertContentEquals(rendered.samples, restored.render().samples)
            val export = KitExporter.exportProgramFolder(kit, kitDir, root.resolve("export"))
            assertEquals(2, export.samples.size)
            assertTrue(export.program.readText().contains("<VelStart>"))
            for (file in export.samples) {
                val wav = WavReader.read(file)
                assertEquals(Dsp.RATE, wav.sampleRate)
                assertTrue(wav.samples.all { it.isFinite() && abs(it) <= 1f })
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

/** Measures audio rather than comparing a duplicated loop with itself. Shared with the audition manifest. */
internal object BellowsTestAudio {
    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        val a = from.coerceIn(0, x.size)
        val b = to.coerceIn(a, x.size)
        var energy = 0.0
        for (i in a until b) energy += x[i].toDouble() * x[i]
        return if (b > a) sqrt(energy / (b - a)) else 0.0
    }

    fun scalarResidual(a: FloatArray, b: FloatArray): Double {
        val count = minOf(a.size, b.size)
        var aa = 0.0
        var ab = 0.0
        var bb = 0.0
        for (i in 0 until count) {
            aa += a[i].toDouble() * a[i]
            ab += a[i].toDouble() * b[i]
            bb += b[i].toDouble() * b[i]
        }
        if (aa < 1e-18 || bb < 1e-18) return 0.0
        return sqrt((bb - ab * ab / aa).coerceAtLeast(0.0) / bb)
    }

    fun harmonicDistance(a: FloatArray, b: FloatArray, hz: Double): Double {
        fun profile(x: FloatArray): DoubleArray {
            val from = minOf((.35 * Dsp.RATE).toInt(), x.size / 4)
            val n = minOf((.3 * Dsp.RATE).toInt(), x.size - from)
            val harmonics = DoubleArray(12) { k ->
                var real = 0.0
                var imaginary = 0.0
                for (i in 0 until n) {
                    val window = .5 - .5 * cos(2 * PI * i / (n - 1))
                    val phase = 2 * PI * hz * (k + 1) * i / Dsp.RATE
                    val y = x[from + i] * window
                    real += y * cos(phase)
                    imaginary += y * sin(phase)
                }
                sqrt(real * real + imaginary * imaginary)
            }
            val sum = harmonics.sum().coerceAtLeast(1e-15)
            for (i in harmonics.indices) harmonics[i] /= sum
            return harmonics
        }
        val pa = profile(a)
        val pb = profile(b)
        return pa.indices.sumOf { abs(pa[it] - pb[it]) }
    }

    /** Relative to the source's own interior steps and changes of slope; PCM quantization has a two-LSB allowance. */
    fun wrap(x: FloatArray): Pair<Double, Double> {
        require(x.size >= 3)
        var step = 0.0
        var kink = 0.0
        for (i in 1 until x.size) step = maxOf(step, abs(x[i] - x[i - 1]).toDouble())
        for (i in 2 until x.size) kink = maxOf(kink, abs((x[i] - x[i - 1]) - (x[i - 1] - x[i - 2])).toDouble())
        val across = (x.first() - x.last()).toDouble()
        val before = (x.last() - x[x.lastIndex - 1]).toDouble()
        val after = (x[1] - x[0]).toDouble()
        val allowance = 2.0 / 32768
        return abs(across) / (step + allowance) to maxOf(abs(across - before), abs(after - across)) / (kink + allowance)
    }
}
