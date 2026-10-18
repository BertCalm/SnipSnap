package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
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
import kotlin.test.assertTrue

/** Dry, audible contracts for the finite-note prototype; no assertion claims a successful audition. */
class OrigamiTest {
    private val controls = listOf("TUNE", "CREASE", "FOLD", "TURN", "WARP", "MOTION")

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double {
        var energy = 0.0
        for (i in from until until) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (until - from).coerceAtLeast(1))
    }

    /** Remove overall gain: a level-only control cannot pass as a different timbre. */
    private fun matchedDifference(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size, "geometry controls must not change the finite envelope's duration")
        val ar = rms(a).coerceAtLeast(1e-12)
        val br = rms(b).coerceAtLeast(1e-12)
        var energy = 0.0
        for (i in a.indices) {
            val d = a[i] / ar - b[i] / br
            energy += d * d
        }
        return sqrt(energy / a.size)
    }

    private data class Spectrum(val powers: DoubleArray, val binHz: Double)

    private fun spectrum(samples: FloatArray, fromSeconds: Double = .12, seconds: Double = .5): Spectrum {
        val first = (fromSeconds * Dsp.RATE).roundToInt()
        val count = minOf((seconds * Dsp.RATE).roundToInt(), samples.size - first)
        require(count > 512) { "spectral evidence requires an audible note body" }
        val n = 65536
        val real = FloatArray(n)
        val imaginary = FloatArray(n)
        for (i in 0 until count) real[i] = (samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(real, imaginary)
        return Spectrum(DoubleArray(n / 2) { real[it].toDouble() * real[it] +
            imaginary[it].toDouble() * imaginary[it] }, Dsp.RATE.toDouble() / n)
    }

    /** Exclude the Hann lobes around integer harmonics; gain cannot create new inharmonic power. */
    private fun inharmonicFraction(s: Spectrum, rootHz: Double): Double {
        var total = 0.0
        var offHarmonic = 0.0
        val widthHz = maxOf(8.0, rootHz * .04)
        for (bin in 1 until s.powers.size) {
            val hz = bin * s.binHz
            if (hz < rootHz * .5) continue
            total += s.powers[bin]
            if (abs(hz - (hz / rootHz).roundToInt() * rootHz) > widthHz) offHarmonic += s.powers[bin]
        }
        return offHarmonic / total.coerceAtLeast(1e-20)
    }

    private fun rootHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun waveformHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }

    private fun bandFraction(s: Spectrum, fromHz: Double, untilHz: Double): Double =
        s.powers.indices.filter { it * s.binHz in fromHz..untilHz }.sumOf { s.powers[it] } /
            s.powers.drop(1).sum().coerceAtLeast(1e-20)

    private fun spectralDifference(a: Spectrum, b: Spectrum): Double {
        assertEquals(a.powers.size, b.powers.size)
        val first = a.powers.drop(1).sum().coerceAtLeast(1e-20)
        val second = b.powers.drop(1).sum().coerceAtLeast(1e-20)
        return (1 until a.powers.size).sumOf { abs(a.powers[it] / first - b.powers[it] / second) }
    }

    private fun halfHarmonicResidual(s: Spectrum, rootHz: Double): Double {
        var total = 0.0
        var outside = 0.0
        val spacing = rootHz / 2.0
        val width = maxOf(8.0, rootHz * .04)
        for (bin in 1 until s.powers.size) {
            val hz = bin * s.binHz
            if (hz < rootHz * .5) continue
            total += s.powers[bin]
            if (abs(hz - (hz / spacing).roundToInt() * spacing) > width) outside += s.powers[bin]
        }
        return outside / total.coerceAtLeast(1e-20)
    }

    /** The first three models share these defaults; R4's useful center is deliberately different. */
    private fun previousDefaults(voice: OrigamiVoice): Map<String, Float> {
        val timbre = when (voice) {
            OrigamiVoice.CRANE -> listOf(.55f, .72f, .18f)
            OrigamiVoice.FAN -> listOf(.63f, .62f, .36f)
            OrigamiVoice.STILL -> listOf(.68f, .68f, .32f)
        }
        return mapOf("TUNE" to .5f, "CREASE" to timbre[0], "FOLD" to timbre[1],
            "TURN" to .5f, "WARP" to .5f, "MOTION" to timbre[2])
    }

    @Test
    fun `model one replays the frozen first audition waveform fixtures`() {
        // Little-endian Float32 hashes copied from the original listening manifest. These guard
        // the audio itself without requiring generated WAV assets to be present in a checkout.
        val fixtures = listOf(
            Triple(OrigamiVoice.CRANE, 48, "1d79a1702eb4cc74d6a60e25f5cf34a9a0cbdf9e7fc265ba7441bf4671e1c22e"),
            Triple(OrigamiVoice.CRANE, 60, "f5772c46fff3cc8d283dd45d60eb479ba4e42221d0fb267379a92c9f3496a469"),
            Triple(OrigamiVoice.CRANE, 72, "dbbd36cb4e19a29e321f55da37936cb9610bbe13e0488b11255906f0c16c7de0"),
            Triple(OrigamiVoice.FAN, 60, "28c7d50baa21d57290823579dc6ad0518d0b95329820b0d47b577cbf9de0bbcd"),
            Triple(OrigamiVoice.STILL, 60, "049981daa8cd434a0b9915881e9e8ca818ec9e961529afd0cba2030374be86a9"),
        )
        val macros = mapOf("TUNE" to .5f, "CREASE" to .5f, "FOLD" to .75f,
            "TURN" to .5f, "WARP" to .5f, "MOTION" to 0f)
        for ((voice, midi, expectedHash) in fixtures) {
            val first = Origami.renderRaw(voice, macros, velocity = .85f, midi = midi,
                seed = 20261012L, model = 1)
            assertEquals(expectedHash, waveformHash(first), "$voice MIDI$midi changed its model-1 audio")
            if (voice == OrigamiVoice.CRANE) for (model in listOf(2, 3, 4)) assertEquals(expectedHash, waveformHash(
                Origami.renderRaw(voice, macros, velocity = .85f, midi = midi, seed = 20261012L, model = model)),
                "CRANE model$model MIDI$midi changed from the audition the owner liked")
        }
    }

    @Test
    fun `model two replays its archived source fold and moving waveform fixtures`() {
        data class Fixture(val voice: OrigamiVoice, val diagnostic: OrigamiDiagnostic,
            val motion: Float, val hash: String)
        val fixtures = listOf(
            Fixture(OrigamiVoice.FAN, OrigamiDiagnostic.SOURCE, 0f,
                "7e02891c50eed758cde644ba4bd466de96b4abbde0d473aa863800994c1778df"),
            Fixture(OrigamiVoice.FAN, OrigamiDiagnostic.COMBINED, 0f,
                "e1e975f66b856010ae5ba12f8ead74992f63e79f650ceb7186fefe5371f51117"),
            Fixture(OrigamiVoice.FAN, OrigamiDiagnostic.COMBINED, .75f,
                "192af08a8b6fb4d1fba1a5636aa7e52ca273ce422c8cdd198af0b167c95c02b9"),
            Fixture(OrigamiVoice.STILL, OrigamiDiagnostic.SOURCE, 0f,
                "f447314b427917d68b530848165017f56313b655ddef856188cc81b1abc6edf3"),
            Fixture(OrigamiVoice.STILL, OrigamiDiagnostic.COMBINED, 0f,
                "ba48539fbda910b182ff058b5b19f9cf51f81079dc2a3d61a1c39e73edb8c306"),
            Fixture(OrigamiVoice.STILL, OrigamiDiagnostic.COMBINED, .75f,
                "2d25f4d35974e292ecafc2a9e27251b988a72e5f1ad5c69e12d251e914c3eba0"),
        )
        for (fixture in fixtures) {
            val macros = mapOf("TUNE" to .5f, "CREASE" to .5f, "FOLD" to .75f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to fixture.motion)
            val samples = Origami.renderRaw(fixture.voice, macros, velocity = .85f, midi = 60,
                seed = 20261012L, diagnostic = fixture.diagnostic, model = 2)
            assertEquals(fixture.hash, waveformHash(samples),
                "model-2 ${fixture.voice} ${fixture.diagnostic} MOTION${fixture.motion} changed its saved audio")
        }
    }

    @Test
    fun `model three replays its accepted source fold and moving waveform fixtures`() {
        data class Fixture(val voice: OrigamiVoice, val diagnostic: OrigamiDiagnostic,
            val motion: Float, val hash: String)
        val fixtures = listOf(
            Fixture(OrigamiVoice.FAN, OrigamiDiagnostic.SOURCE, 0f,
                "0ebe10db3ba19f3a6f8da32e0eaef12a5e42ce1aa3f32fbbf76ae392de79b5cc"),
            Fixture(OrigamiVoice.FAN, OrigamiDiagnostic.COMBINED, 0f,
                "1dd7488d84b90396b9668cffb91d3730e8962b086af6c8e837b1e4379c1748fe"),
            Fixture(OrigamiVoice.FAN, OrigamiDiagnostic.COMBINED, .75f,
                "2d1448b0035d8aa5848ad4051b274968f98a6b706c527203a8c4bd18348e4959"),
            Fixture(OrigamiVoice.STILL, OrigamiDiagnostic.SOURCE, 0f,
                "f6917e9f725daebe49e46d19e5c4f303ca400ee80cf14d26e55c9ca2fb18837e"),
            Fixture(OrigamiVoice.STILL, OrigamiDiagnostic.COMBINED, 0f,
                "eb283a865c6c426ed9b573454ef184498a6b7538478d175613eac347c6e0e848"),
            Fixture(OrigamiVoice.STILL, OrigamiDiagnostic.COMBINED, .75f,
                "ecf39f91c510117c2e1eb703c3877dfee25724aa1107e307eca1e70a55508901"),
        )
        for (fixture in fixtures) {
            val macros = mapOf("TUNE" to .5f, "CREASE" to .5f, "FOLD" to .75f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to fixture.motion)
            val samples = Origami.renderRaw(fixture.voice, macros, velocity = .85f, midi = 60,
                seed = 20261012L, diagnostic = fixture.diagnostic, model = 3)
            assertEquals(fixture.hash, waveformHash(samples),
                "model-3 ${fixture.voice} ${fixture.diagnostic} MOTION${fixture.motion} changed its accepted audio")
        }
    }

    @Test
    fun `fourth model preserves accepted raw sources and conditioned zero folds across register and recipes`() {
        for (voice in OrigamiVoice.entries) for (midi in listOf(48, 60, 72)) {
            data class Recipe(val macros: Map<String, Float>, val velocity: Float, val seed: Long)
            val recipes = listOf(Recipe(Origami.defaults(voice), 1f, 0L),
                Recipe(Origami.defaults(voice) + mapOf("CREASE" to .13f, "FOLD" to .91f,
                    "TURN" to .86f, "WARP" to .23f, "MOTION" to .89f), .37f, Long.MAX_VALUE - 11L))
            for (recipe in recipes) {
                val accepted = Origami.renderRaw(voice, recipe.macros, recipe.velocity, midi,
                    recipe.seed, OrigamiDiagnostic.SOURCE, model = 3)
                val currentSource = Origami.renderRaw(voice, recipe.macros, recipe.velocity, midi,
                    recipe.seed, OrigamiDiagnostic.SOURCE, model = 4)
                val zeroMacros = recipe.macros + ("FOLD" to 0f)
                val currentZero = Origami.renderRaw(voice, zeroMacros, recipe.velocity, midi, recipe.seed, model = 4)
                assertContentEquals(accepted, currentSource,
                    "$voice MIDI$midi R4 altered the accepted generated source")
                assertContentEquals(accepted, currentZero,
                    "$voice MIDI$midi R4 zero fold altered the accepted generated source")
                assertContentEquals(Origami.render(voice, zeroMacros, recipe.velocity, midi, recipe.seed, model = 3).samples,
                    Origami.render(voice, zeroMacros, recipe.velocity, midi, recipe.seed, model = 4).samples,
                    "$voice MIDI$midi R4 zero fold altered the accepted conditioned pad")
            }
        }
    }

    @Test
    fun `crane stays byte identical to the first model across register geometry and motion`() {
        val voice = OrigamiVoice.CRANE
        for (midi in listOf(48, 60, 72)) for (macros in listOf(Origami.defaults(voice),
            Origami.defaults(voice) + mapOf("CREASE" to .39f, "FOLD" to .8f,
                "TURN" to .25f, "WARP" to .72f, "MOTION" to .75f))) {
            val first = Origami.render(voice, macros, velocity = .72f, midi = midi,
                seed = Long.MIN_VALUE + 31L, model = 1)
            for (model in listOf(2, 3, 4)) {
                val revised = Origami.render(voice, macros, velocity = .72f, midi = midi,
                    seed = Long.MIN_VALUE + 31L, model = model)
                assertContentEquals(first.samples, revised.samples,
                    "CRANE model$model MIDI$midi geometry $macros must retain the original sound")
            }
        }
    }

    @Test
    fun `third model softens the unfolded sources at matched level rather than only reducing their gain`() {
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) for (midi in listOf(60, 72)) {
            val macros = previousDefaults(voice) + mapOf("FOLD" to 0f, "MOTION" to 0f)
            val first = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.SOURCE, model = 2)
            val revised = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.SOURCE, model = 3)
            val firstUpper = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 2)
            val revisedUpper = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 3)
            val firstSpectrum = spectrum(first, .45, .6)
            val revisedSpectrum = spectrum(revised, .45, .6)
            val firstUpperSpectrum = spectrum(firstUpper, .45, .6)
            val revisedUpperSpectrum = spectrum(revisedUpper, .45, .6)
            val higherFromHz = 3.5 * rootHz(midi)
            val higherFirst = bandFraction(firstUpperSpectrum, higherFromHz, 18_000.0)
            val higherRevised = bandFraction(revisedUpperSpectrum, higherFromHz, 18_000.0)
            val hotFirst = bandFraction(firstUpperSpectrum, 4_000.0, 12_000.0)
            val hotRevised = bandFraction(revisedUpperSpectrum, 4_000.0, 12_000.0)
            val fullHigherFirst = bandFraction(firstSpectrum, higherFromHz, 18_000.0)
            val fullHigherRevised = bandFraction(revisedSpectrum, higherFromHz, 18_000.0)
            println("ORIGAMI R3 SOURCE $voice MIDI$midi: normalized upper h4+ $higherFirst -> $higherRevised, " +
                "upper 4–12k $hotFirst -> $hotRevised, full-source h4+ $fullHigherFirst -> $fullHigherRevised")
            assertTrue(higherRevised < higherFirst * .35,
                "$voice MIDI$midi unfurled upper spectrum still has its old higher-harmonic balance")
            assertTrue(hotRevised < hotFirst * .25,
                "$voice MIDI$midi unfurled upper spectrum retains its old 4–12k emphasis")
            assertTrue(fullHigherRevised < fullHigherFirst * .2,
                "$voice MIDI$midi complete unfurled source remains too bright at matched level")
            assertTrue(rms(revisedUpper) > .01, "$voice MIDI$midi source upper layer was removed")
            assertTrue(matchedDifference(first, revised) > .1,
                "$voice MIDI$midi original-source revision changes only gain")
        }
        val fan = spectrum(Origami.renderRaw(OrigamiVoice.FAN, midi = 60, seed = 20261012L,
            diagnostic = OrigamiDiagnostic.SOURCE, model = 3), .45, .6)
        val still = spectrum(Origami.renderRaw(OrigamiVoice.STILL, midi = 60, seed = 20261012L,
            diagnostic = OrigamiDiagnostic.SOURCE, model = 3), .45, .6)
        val fanTotal = fan.powers.drop(1).sum()
        val stillTotal = still.powers.drop(1).sum()
        val spectralDifference = (1 until fan.powers.size).sumOf {
            abs(fan.powers[it] / fanTotal - still.powers[it] / stillTotal)
        }
        assertTrue(spectralDifference > .04, "FAN and STILL have collapsed to the same gain-matched source")
    }

    @Test
    fun `revised fan and still reduce upper harshness indicators without muting their spectra`() {
        // Upper-layer fractions exclude the root and remove overall gain. Boosting the anchor or
        // attenuating the entire voice cannot reduce these measured spectral fractions.
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) for (midi in listOf(60, 72)) {
            val macros = previousDefaults(voice) + ("MOTION" to 0f)
            val first = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 1)
            val second = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 2)
            val a = spectrum(first, .45, .6)
            val b = spectrum(second, .45, .6)
            val hotFirst = bandFraction(a, 4_000.0, 12_000.0)
            val hotSecond = bandFraction(b, 4_000.0, 12_000.0)
            val inharmonicFirst = inharmonicFraction(a, rootHz(midi))
            val inharmonicSecond = inharmonicFraction(b, rootHz(midi))
            println("ORIGAMI R2 $voice MIDI$midi: upper 4–12k $hotFirst -> $hotSecond, " +
                "off-harmonic $inharmonicFirst -> $inharmonicSecond, RMS ${rms(first)} -> ${rms(second)}")
            assertTrue(hotSecond < hotFirst * .5,
                "$voice MIDI$midi retains too much normalized upper-band power: $hotFirst -> $hotSecond")
            assertTrue(inharmonicSecond < inharmonicFirst * .65,
                "$voice MIDI$midi retains too much normalized off-harmonic power: $inharmonicFirst -> $inharmonicSecond")
            assertTrue(rms(second) >= rms(first) * .6,
                "$voice MIDI$midi softened by muting its upper layer")
            assertTrue(matchedDifference(first, second) > .1,
                "$voice MIDI$midi revision changes only gain")
        }
    }

    @Test
    fun `prototype exposes six normalized controls and neutral turn and warp`() {
        for (voice in OrigamiVoice.entries) {
            val specs = Origami.macrosFor(voice)
            assertEquals(controls, specs.map { it.name }, voice.name)
            assertEquals(controls.toSet(), Origami.defaults(voice).keys)
            assertTrue(specs.all { it.default.isFinite() && it.default in 0f..1f && it.neutral in 0f..1f })
            assertEquals(.5f, specs.single { it.name == "TURN" }.neutral)
            assertEquals(.5f, specs.single { it.name == "WARP" }.neutral)
            assertEquals(48, Origami.midiFor(voice, 0f))
            assertEquals(60, Origami.midiFor(voice, .5f))
            assertEquals(72, Origami.midiFor(voice, 1f))
        }
    }

    @Test
    fun `every finished voice is finite mono 44k audio and ends quietly`() {
        for (voice in OrigamiVoice.entries) {
            val snip = Origami.render(voice)
            assertEquals(1, snip.channels)
            assertEquals(44100, snip.sampleRate)
            assertTrue(snip.durationSeconds in 1f..5f, "$voice length ${snip.durationSeconds}")
            assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice finished samples")
            assertTrue(snip.peak() > .05f, "$voice is effectively silent")
            assertTrue(abs(snip.samples.average()) < .005, "$voice has DC")
            assertTrue(abs(snip.samples.first()) < .001f, "$voice abrupt attack")
            assertTrue(abs(snip.samples.last()) < .001f, "$voice abrupt release")
            val tail = rms(snip.samples, snip.samples.size - 441, snip.samples.size)
            assertTrue(tail < rms(snip.samples) * .02, "$voice unfinished finite tail: $tail")
        }
    }

    @Test
    fun `saved seeds replay exactly and velocity zero is silent`() {
        for (voice in OrigamiVoice.entries) {
            val macros = Origami.defaults(voice) + mapOf("CREASE" to .43f, "MOTION" to .72f)
            val a = Origami.render(voice, macros, velocity = .65f, midi = 60, seed = 8234567890123456789L)
            val b = Origami.render(voice, macros, velocity = .65f, midi = 60, seed = 8234567890123456789L)
            assertContentEquals(a.samples, b.samples, "$voice conditioned seed replay")
            assertTrue(a.samples.any { abs(it) > .001f }, "$voice determinism case is silent")
            val zero = Origami.render(voice, macros, velocity = 0f, midi = 60, seed = 1L)
            assertEquals(a.frameCount, zero.frameCount, "$voice velocity changed duration")
            assertTrue(zero.samples.all { it == 0f }, "$voice velocity zero is audible")
        }
        val first = Origami.renderRaw(OrigamiVoice.STILL, seed = 12L)
        val second = Origami.renderRaw(OrigamiVoice.STILL, seed = 13L)
        assertTrue(matchedDifference(first, second) > .05, "STILL's phase-cloud seed is inaudible")
    }

    @Test
    fun `fold zero is the exact generated source under every other geometry control`() {
        for (model in listOf(1, 2, 3, 4)) for (voice in OrigamiVoice.entries) for (midi in listOf(48, 60, 72)) {
            val dryMacros = Origami.defaults(voice) + ("FOLD" to 0f)
            val source = Origami.renderRaw(voice, dryMacros, midi = midi, seed = 17L,
                diagnostic = OrigamiDiagnostic.SOURCE, model = model)
            for (geometry in listOf(
                mapOf("CREASE" to 0f, "TURN" to 0f, "WARP" to 1f, "MOTION" to 1f),
                mapOf("CREASE" to 1f, "TURN" to 1f, "WARP" to 0f, "MOTION" to 0f),
            )) {
                val actual = Origami.renderRaw(voice, dryMacros + geometry, midi = midi, seed = 17L, model = model)
                assertContentEquals(source, actual, "$voice model$model MIDI$midi FOLD zero changed its source at $geometry")
                val partials = Origami.partials(voice, dryMacros + geometry, midi = midi, seed = 17L, model = model)
                assertTrue(partials.any { it.sourceAmplitude > 0.0 }, "$voice source probe is silent")
                for (partial in partials) {
                    assertEquals(partial.sourceAmplitude, partial.dryAmplitude,
                        "$voice MIDI$midi target-band rejection altered the unfurled source")
                    assertEquals(0.0, partial.wetAmplitude, "$voice MIDI$midi FOLD zero leaks folded material")
                }
            }
        }
    }

    @Test
    fun `half fold retains half the source and half the relocated signal at their original frequencies`() {
        // Frequency interpolation makes reflected partials collide at half depth. The original
        // spectral fold is an amplitude blend: its complete raw audio must obey this identity.
        for (voice in OrigamiVoice.entries) for ((midi, motion) in listOf(60 to 0f, 72 to .75f)) {
            val geometry = Origami.defaults(voice) + mapOf("CREASE" to .39f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to motion)
            val dry = Origami.renderRaw(voice, geometry + ("FOLD" to 0f), midi = midi, seed = 42L)
            val wet = Origami.renderRaw(voice, geometry + ("FOLD" to 1f), midi = midi, seed = 42L)
            val half = Origami.renderRaw(voice, geometry + ("FOLD" to .5f), midi = midi, seed = 42L)
            var maximumError = 0.0
            for (i in half.indices) maximumError = maxOf(maximumError,
                abs(half[i].toDouble() - .5 * (dry[i].toDouble() + wet[i])))
            assertTrue(maximumError < 2e-6,
                "$voice MIDI$midi MOTION$motion half-depth changed partial frequencies or gain: $maximumError")
            assertTrue(matchedDifference(dry, half) > .05, "$voice MIDI$midi half-depth fold is inaudible")
        }
    }

    @Test
    fun `root and transformed bank add to the combined raw signal without independent leveling`() {
        for (model in listOf(1, 2, 3, 4)) for (voice in OrigamiVoice.entries) {
            val combined = defaultRaw(voice, 60, model)
            val root = Origami.renderRaw(voice, midi = 60, diagnostic = OrigamiDiagnostic.ROOT_ONLY, model = model)
            val bank = Origami.renderRaw(voice, midi = 60, diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = model)
            assertEquals(combined.size, root.size)
            assertEquals(combined.size, bank.size)
            var maximumError = 0.0
            for (i in combined.indices) maximumError = maxOf(maximumError,
                abs(combined[i].toDouble() - root[i] - bank[i]))
            assertTrue(maximumError < 2e-6, "$voice separate diagnostics changed gain: $maximumError")
            val anchorRatio = rms(root) / rms(combined)
            println("ORIGAMI model$model $voice default root RMS / combined RMS: $anchorRatio")
            if (model <= 2 || voice == OrigamiVoice.CRANE) assertTrue(anchorRatio in .05.. .8,
                "$voice model$model anchor should support the geometry without dominating it: $anchorRatio")
            else {
                // R3 deliberately gives the root more authority after the owner found even the
                // original sources harsh. Require an audible upper bank rather than restoring it.
                assertTrue(anchorRatio >= .05, "$voice root anchor is missing")
                assertTrue(rms(bank) / rms(root) > .15, "$voice revised upper bank is effectively muted")
            }
            assertTrue(rms(bank) > .01, "$voice transformed upper spectrum is inaudible")
        }
    }

    @Test
    fun `raw defaults and all high geometry remain bounded throughout C3 to C5`() {
        var worstPeak = 0.0
        for (voice in OrigamiVoice.entries) for (midi in listOf(48, 60, 72)) {
            for (raw in listOf(defaultRaw(voice, midi), Origami.renderRaw(voice,
                controls.associateWith { 1f }, midi = midi, seed = Long.MAX_VALUE))) {
                assertTrue(raw.all { it.isFinite() }, "$voice MIDI $midi raw not finite")
                val peak = raw.maxOf { abs(it.toDouble()) }
                worstPeak = maxOf(worstPeak, peak)
                assertTrue(peak < 1.1, "$voice MIDI $midi pre-level explosion: $peak")
                assertTrue(rms(raw) > .002, "$voice MIDI $midi collapsed to silence")
            }
        }
        println("ORIGAMI raw bounds: worst default/all-high peak $worstPeak")
    }

    @Test
    fun `default geometry retains substantial correctly tuned roots across the audition register`() {
        // Inharmonic voices need not have a clean autocorrelation period. Measure actual root
        // support independently and print the unrestricted detector's result for the audition.
        for (voice in OrigamiVoice.entries) for (midi in listOf(48, 60, 72)) {
            val raw = defaultRaw(voice, midi)
            val want = rootHz(midi)
            val measured = FineTuning.measuredHz(raw, Dsp.RATE, want.toFloat(), .12f, .5f)
            val cents = FineTuning.cents(measured, want)
            assertTrue(abs(cents) < 5.0, "$voice MIDI $midi root ${cents} cents from its note")
            val s = spectrum(raw)
            val center = (want / s.binHz).roundToInt()
            val support = (center - 8..center + 8).sumOf { s.powers[it] } / s.powers.drop(1).sum()
            assertTrue(support > .04, "$voice MIDI $midi has only $support root power")
            val detected = Pitch.detect(Snip(raw, 1, Dsp.RATE), .12f, .3f)
            val detectorCents = detected?.let { FineTuning.cents(it.hz.toDouble(), want) }
            println("ORIGAMI root $voice MIDI$midi: peak ${cents}c, power $support, " +
                "detector ${detectorCents}c confidence ${detected?.confidence}")
        }
    }

    @Test
    fun `folding moves source power away from harmonic frequencies in actual audio`() {
        // CREASE .39 puts the reflection axis around 5.62 times the root; reflected integer
        // harmonics are then roughly a quarter harmonic away from the source grid. A frequency
        // mask cannot meet this bar even if it changes the source's spectral centroid.
        for (model in listOf(1, 2)) for (voice in listOf(OrigamiVoice.CRANE, OrigamiVoice.FAN)) {
            val geometry = Origami.defaults(voice) + mapOf("CREASE" to .39f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to 0f)
            val dry = Origami.renderRaw(voice, geometry + ("FOLD" to 0f), midi = 60, model = model)
            val folded = Origami.renderRaw(voice, geometry + ("FOLD" to 1f), midi = 60, model = model)
            val dryFraction = inharmonicFraction(spectrum(dry), rootHz(60))
            val foldedFraction = inharmonicFraction(spectrum(folded), rootHz(60))
            println("ORIGAMI relocated model$model $voice: off-harmonic power $dryFraction -> $foldedFraction")
            assertTrue(dryFraction < .01, "$voice source is not the proposed harmonic reference")
            assertTrue(foldedFraction > dryFraction + .04,
                "$voice fold did not move substantial power to new frequencies")
        }
    }

    @Test
    fun `third model retains audible folding on the gentle lower harmonic bank`() {
        // This source has deliberately little h6+ energy. Place the new note-relative axis near
        // 2.29 times the root so its audible third harmonic reflects to about 1.58 times the root.
        val voice = OrigamiVoice.FAN
        val geometry = previousDefaults(voice) + mapOf("CREASE" to .2f,
            "TURN" to .5f, "WARP" to .5f, "MOTION" to 0f)
        val dry = Origami.renderRaw(voice, geometry + ("FOLD" to 0f), midi = 60,
            diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 3)
        val folded = Origami.renderRaw(voice, geometry + ("FOLD" to 1f), midi = 60,
            diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 3)
        val dryFraction = inharmonicFraction(spectrum(dry), rootHz(60))
        val foldedFraction = inharmonicFraction(spectrum(folded), rootHz(60))
        println("ORIGAMI R3 relocated upper bank: off-harmonic power $dryFraction -> $foldedFraction")
        assertTrue(dryFraction < .01, "gentle FAN source is not the harmonic reference")
        assertTrue(foldedFraction > dryFraction + .04, "gentle FAN bank no longer relocates audible frequencies")
        for (v in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) {
            val baseline = previousDefaults(v) + mapOf("CREASE" to .5f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to 0f)
            val original = Origami.renderRaw(v, baseline + ("FOLD" to 0f), midi = 60, model = 3)
            val transformed = Origami.renderRaw(v, baseline + ("FOLD" to .75f), midi = 60, model = 3)
            val difference = matchedDifference(original, transformed)
            println("ORIGAMI R3 $v baseline fold gain-matched difference $difference")
            assertTrue(difference > .05, "$v gentle source makes the baseline fold inaudible")
        }
    }

    @Test
    fun `fourth model gives warm folds a distinct musical body without changing root or reviving upper bite`() {
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) for (midi in listOf(60, 72)) {
            val macros = mapOf("TUNE" to .5f, "CREASE" to .5f, "FOLD" to .75f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to 0f)
            val source = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.SOURCE, model = 3)
            val previous = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L, model = 3)
            val folded = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L, model = 4)
            val sourceUpper = Origami.renderRaw(voice, macros + ("FOLD" to 0f), midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 3)
            val foldedUpper = Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 4)
            assertContentEquals(Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.ROOT_ONLY, model = 3),
                Origami.renderRaw(voice, macros, midi = midi, seed = 20261012L,
                    diagnostic = OrigamiDiagnostic.ROOT_ONLY, model = 4),
                "$voice MIDI$midi richer folds changed the stable root")
            val sourceSpectrum = spectrum(source, .45, .6)
            val previousSpectrum = spectrum(previous, .45, .6)
            val foldedSpectrum = spectrum(folded, .45, .6)
            val sourceUpperSpectrum = spectrum(sourceUpper, .45, .6)
            val foldedUpperSpectrum = spectrum(foldedUpper, .45, .6)
            val fromSource = spectralDifference(sourceSpectrum, foldedSpectrum)
            val fromPrevious = spectralDifference(previousSpectrum, foldedSpectrum)
            val target = if (voice == OrigamiVoice.FAN) 1.5 else 2.5
            val musicalBody = bandFraction(foldedUpperSpectrum,
                (target - .06) * rootHz(midi), (target + .06) * rootHz(midi))
            val sourceBody = bandFraction(sourceUpperSpectrum,
                (target - .06) * rootHz(midi), (target + .06) * rootHz(midi))
            val hotSource = bandFraction(sourceUpperSpectrum, 4_000.0, 12_000.0)
            val hotFolded = bandFraction(foldedUpperSpectrum, 4_000.0, 12_000.0)
            val highSource = bandFraction(sourceUpperSpectrum, rootHz(midi) * 3.5, 18_000.0)
            val highFolded = bandFraction(foldedUpperSpectrum, rootHz(midi) * 3.5, 18_000.0)
            val residual = halfHarmonicResidual(foldedUpperSpectrum, rootHz(midi))
            println("ORIGAMI R4 $voice MIDI$midi: spectral distance source $fromSource / R3 $fromPrevious, " +
                "$target-root body $sourceBody -> $musicalBody, upper 4–12k $hotSource -> $hotFolded, " +
                "upper h4+ $highSource -> $highFolded, half-grid residual $residual")
            assertTrue(fromSource > .05 && fromPrevious > .05,
                "$voice MIDI$midi richer fold changed only gain or phase, not its spectral body")
            assertTrue(matchedDifference(source, folded) > .1 && matchedDifference(previous, folded) > .1,
                "$voice MIDI$midi richer fold is indistinguishable at matched level")
            assertTrue(sourceBody < .01 && musicalBody > .05,
                "$voice MIDI$midi fold did not put substantial actual audio at $target times the root")
            assertTrue(rms(foldedUpper) > rms(sourceUpper) * .6,
                "$voice MIDI$midi richer fold was made by muting its upper bank")
            assertTrue(hotFolded <= hotSource * 1.1 + 1e-8 && highFolded < highSource * .5,
                "$voice MIDI$midi richer fold revived the rejected upper-frequency emphasis")
            assertTrue(residual < .01,
                "$voice MIDI$midi center fold introduced an uncontrolled inharmonic cluster")
        }
    }

    @Test
    fun `fourth model keeps the second harmonic fixed and stills main folded partials coherent through motion`() {
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) {
            val macros = mapOf("TUNE" to .5f, "CREASE" to .5f, "FOLD" to .75f,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to 1f)
            for (time in listOf(0.0, .4, 1.3, 2.4)) {
                val partials = Origami.partials(voice, macros, midi = 60, seed = 20261012L,
                    timeSeconds = time, model = 4)
                val second = partials.single { abs(it.sourceHz / rootHz(60) - 2.0) < 1e-5 }
                assertEquals(second.sourceHz, second.frequencyHz,
                    "$voice MOTION shifted the strong unreflected second harmonic at $time")
                if (voice == OrigamiVoice.STILL) {
                    val third = partials.single { abs(it.sourceHz / rootHz(60) - 3.0) < 1e-5 }
                    val fourth = partials.single { abs(it.sourceHz / rootHz(60) - 4.0) < 1e-5 }
                    assertEquals(third.frequencyHz, fourth.frequencyHz,
                        "STILL MOTION split its coherent third/fourth-harmonic fold at $time")
                    if (time == 0.0) assertTrue(abs(third.frequencyHz / rootHz(60) - 2.5) < 1e-5,
                        "STILL center did not fold its main body to 2.5 times the root")
                }
            }
            val moving = Origami.renderRaw(voice, macros, midi = 60, seed = 20261012L,
                diagnostic = OrigamiDiagnostic.FOLDED_ONLY, model = 4)
            val secondPower = bandFraction(spectrum(moving, .45, .6),
                rootHz(60) * 1.97, rootHz(60) * 2.03)
            assertTrue(secondPower > .2, "$voice moving geometry erased its stable second harmonic from actual audio")
        }
    }

    @Test
    fun `bright top register geometry leaves no significant power at the Nyquist edge`() {
        for (voice in OrigamiVoice.entries) {
            val macros = Origami.defaults(voice) + mapOf("CREASE" to 1f, "FOLD" to 1f,
                "TURN" to 1f, "WARP" to 1f, "MOTION" to 1f)
            val s = spectrum(Origami.renderRaw(voice, macros, midi = 72), .35, .6)
            val total = s.powers.drop(1).sum()
            val edge = s.powers.indices.filter { it * s.binHz >= 19_500.0 }.sumOf { s.powers[it] } / total
            assertTrue(edge < 1e-5, "$voice has significant unwanted Nyquist-edge power: $edge")
        }
    }

    @Test
    fun `each geometry macro and tune change a gain matched note without moving its envelope length`() {
        for (voice in OrigamiVoice.entries) {
            val defaults = Origami.defaults(voice)
            val base = Origami.renderRaw(voice, defaults)
            val differences = controls.associateWith { name ->
                val current = defaults.getValue(name)
                val moved = if (current > .5f) .08f else .92f
                matchedDifference(base, Origami.renderRaw(voice, defaults + (name to moved)))
            }
            println("ORIGAMI $voice matched macro changes: $differences")
            for ((name, difference) in differences) assertTrue(difference > .05,
                "$voice $name makes no meaningful timbral change: $difference")
        }
    }

    @Test
    fun `explicit MIDI overrides tune and unsupported MIDI is refused`() {
        val voice = OrigamiVoice.CRANE
        assertContentEquals(Origami.renderRaw(voice, mapOf("TUNE" to 0f), midi = 60),
            Origami.renderRaw(voice, mapOf("TUNE" to 1f), midi = 60), "explicit note did not override TUNE")
        for (midi in listOf(Int.MIN_VALUE, 47, 73, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { Origami.renderRaw(voice, midi = midi) }
        }
        for (model in listOf(0, 5, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { Origami.renderRaw(voice, model = model) }
        }
    }

    @Test
    fun `live controls clamp endpoints use defaults for nonfinite values and ignore unknown keys`() {
        val voice = OrigamiVoice.FAN
        val expected = Origami.renderRaw(voice, mapOf("CREASE" to 0f, "WARP" to 1f))
        assertContentEquals(expected, Origami.renderRaw(voice,
            mapOf("CREASE" to -4f, "WARP" to 8f, "REVERB" to 1f)))
        val defaults = Origami.renderRaw(voice)
        assertContentEquals(defaults, Origami.renderRaw(voice,
            mapOf("CREASE" to Float.NaN, "FOLD" to Float.POSITIVE_INFINITY)))
        assertContentEquals(defaults, Origami.renderRaw(voice, velocity = Float.NaN))
        assertContentEquals(defaults, Origami.renderRaw(voice, velocity = 4f))
        assertTrue(Origami.renderRaw(voice, velocity = -1f).all { it == 0f })
    }

    companion object {
        // The same finite default renders underpin bounds, pitch and diagnostic checks. Cache only
        // these references; the replay test above deliberately renders both sides independently.
        private val defaultRenders = ConcurrentHashMap<Triple<OrigamiVoice, Int, Int>, FloatArray>()

        private fun defaultRaw(voice: OrigamiVoice, midi: Int, model: Int = Origami.MODEL_VERSION): FloatArray =
            defaultRenders.computeIfAbsent(Triple(voice, midi, model)) { Origami.renderRaw(voice, midi = midi, model = model) }
    }
}
