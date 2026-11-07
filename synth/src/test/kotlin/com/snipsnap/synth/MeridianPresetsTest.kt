package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved identity, sanitation and measured variety; the new bank still needs listening. */
class MeridianPresetsTest {
    private class Reading(val patch: MeridianPatch) {
        val snip: Snip = patch.render()
        val matched: Snip by lazy { AuditionLevel.level(snip) }
        val held: Meridian.HeldResult? by lazy {
            if (Meridian.isLoop(patch.macros, patch.model))
                Meridian.renderHeld(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seedContext)
            else null
        }
        val analysis: FloatArray by lazy {
            val region = held
            if (region == null) matched.samples else {
                // Compare continuous sampler playback, rather than rewarding a
                // held preset merely because its two-cycle evidence file is shorter.
                val length = region.loopEndFrameExclusive - region.loopStartFrame
                FloatArray(2 * Dsp.RATE) { matched.samples[region.loopStartFrame + it % length] }
            }
        }
        val envelope: DoubleArray by lazy { activity(analysis) }
        val spectrum: DoubleArray by lazy { powerDistribution(analysis) }
    }

    private companion object {
        val names = listOf("FIRST ARC", "BACK ARC", "CLOSE GRAIN", "DEEP KNOCK",
            "STILL PAIR", "LONG TRAIL", "ROUND THREAD", "FINE STRAND")
        val readings by lazy { MeridianPresets.all().map(::Reading) }
        val byName by lazy { readings.associateBy { it.patch.name } }
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

        /** First two seconds, represented as normalized 100-ms energy shares. */
        fun activity(samples: FloatArray): DoubleArray {
            val n = minOf(samples.size, 2 * Dsp.RATE)
            val block = Dsp.RATE / 10
            val result = DoubleArray((n + block - 1) / block)
            for (i in 0 until n) result[i / block] += samples[i].toDouble() * samples[i]
            val total = result.sum().coerceAtLeast(1e-30)
            for (i in result.indices) result[i] /= total
            return result
        }

        /** Averaged windowed power, so a phase rotation alone cannot prove variety. */
        fun powerDistribution(samples: FloatArray): DoubleArray {
            val size = 4096
            val n = minOf(samples.size, 2 * Dsp.RATE)
            val result = DoubleArray(size / 2 + 1)
            val window = FloatArray(size) { (.5 - .5 * cos(2 * PI * it / size)).toFloat() }
            var start = 0
            while (start < n) {
                val re = FloatArray(size)
                val im = FloatArray(size)
                for (i in 0 until minOf(size, n - start)) re[i] = samples[start + i] * window[i]
                Fft.forward(re, im)
                for (i in result.indices) result[i] += re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
                start += size / 2
            }
            val total = result.sum().coerceAtLeast(1e-30)
            for (i in result.indices) result[i] /= total
            return result
        }

        /** Total variation of normalized energy distributions, bounded by 0..1. */
        fun distance(a: DoubleArray, b: DoubleArray): Double = .5 *
            (0 until maxOf(a.size, b.size)).sumOf { abs(a.getOrElse(it) { 0.0 } - b.getOrElse(it) { 0.0 }) }
    }

    @Test
    fun `eight complete model two recipes are registered with six finite and two held last`() {
        val patches = MeridianPresets.all()
        assertEquals(names, patches.map { it.name })
        assertEquals(patches, MeridianPresets.forVoice(MeridianVoice.SWEEP))
        assertEquals(patches, Presets.forVoice(MeridianPatch.ENGINE, MeridianVoice.SWEEP.name))
        assertEquals(patches, Presets.all().filterIsInstance<MeridianPatch>())
        assertTrue(Presets.forVoice(MeridianPatch.ENGINE, "UNKNOWN").isEmpty())
        assertFalse(MeridianPatch.ENGINE in PatchTypes.ROSTERLESS)
        for ((index, patch) in patches.withIndex()) {
            assertEquals(MeridianVoice.SWEEP, patch.voice)
            assertEquals(2, patch.model, "${patch.name}: later engine versions must not migrate this bank")
            assertEquals(57, patch.midi)
            assertEquals(1f, patch.velocity)
            assertEquals(4810, patch.seedContext)
            assertEquals(.5f, patch.macros.getValue("TUNE"))
            assertEquals(Meridian.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertEquals(index >= 6, Meridian.isLoop(patch.macros, patch.model), patch.name)
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertTrue(MeridianPresets.roleDescription(patch.name)?.isNotBlank() == true)
        }
        assertEquals(null, MeridianPresets.roleDescription("NOT A FACTORY SOUND"))
    }

    @Test
    fun `first arc keeps the accepted starting sound and back arc changes only its direction`() {
        val first = MeridianPresets.all().first()
        val reverse = MeridianPresets.all()[1]
        assertEquals(Meridian.defaults(MeridianVoice.SWEEP), first.macros)
        assertEquals(first.macros + ("LATITUDE" to .2f), reverse.macros)
        val original = Meridian.render(MeridianVoice.SWEEP, Meridian.defaults(MeridianVoice.SWEEP),
            midi = 57, velocity = 1f, seedContext = 4810, model = 1)
        assertContentEquals(original.samples, byName.getValue("FIRST ARC").snip.samples,
            "the initial factory selection changed the heard finite swing")
    }

    @Test
    fun `every preset renders finite unclipped pitched audio at the house level`() {
        for (r in readings) {
            val label = r.patch.name
            assertEquals(1, r.snip.channels, label)
            assertEquals(Dsp.RATE, r.snip.sampleRate, label)
            assertTrue(r.snip.frameCount > 0 && r.snip.samples.all { it.isFinite() }, label)
            assertTrue(r.snip.peak() in .001f..1f, "$label must produce usable uncut audio")
            assertTrue(abs(r.snip.samples.average()) < .005, "$label DC offset")
            val loudness = Loudness.of(r.snip)
            assertTrue(loudness >= Dsp.MELODIC_LOUDNESS_TARGET * .90f || r.snip.peak() >= .95f,
                "$label is too quiet: level=$loudness peak=${r.snip.peak()}")
            assertTrue(Classifier.classify(r.snip).drumClass !in drums, "$label acquired a drum choke group")
            if (!Meridian.isLoop(r.patch.macros, r.patch.model)) {
                assertTrue(r.snip.frameCount <= 8 * Dsp.RATE, "$label escaped the finite duration budget")
                assertTrue(abs(r.snip.samples.last()) < 1e-6f, "$label finite release did not finish")
            }
        }
    }

    @Test
    fun `every recipe saves all model note velocity seed and macro fields and replays exactly`() {
        for (r in readings) {
            val original = r.patch
            val json = original.toJsonText()
            val restored = Patches.fromJsonText(json) as MeridianPatch
            assertEquals(original, restored, original.name)
            assertEquals(json, restored.toJsonText(), "${original.name}: canonical complete recipe")
            val fields = restored.toJsonValue().entries
            assertEquals(2, fields.getValue("model").int())
            assertEquals(57, fields.getValue("midi").int())
            assertEquals(1.0, fields.getValue("velocity").num())
            assertEquals(4810, fields.getValue("seedContext").int())
            assertEquals(original.macros.keys, fields.getValue("macros").obj().keys)
            assertContentEquals(r.snip.samples, restored.render().samples, "${original.name}: JSON replay")
            val edited = Patches.edited(restored, "EDITED", restored.macros + ("REACH" to .45f)) as MeridianPatch
            assertEquals(restored.midi, edited.midi)
            assertEquals(restored.velocity, edited.velocity)
            assertEquals(restored.model, edited.model)
            assertEquals(restored.seedContext, edited.seedContext)
        }
    }

    @Test
    fun `preset names are short uppercase unique and avoid reserved or maker names`() {
        assertEquals(names.size, names.map { it.trim().uppercase(Locale.ROOT) }.toSet().size)
        val reserved = PatchTypes.engines + MeridianVoice.entries.map { it.name } +
            FxChain.SECTION_NAMES.map { it.uppercase(Locale.ROOT) }
        for (patch in MeridianPresets.all()) {
            assertTrue(patch.name.isNotBlank() && patch.name.length <= 14, patch.name)
            assertEquals(patch.name.uppercase(Locale.ROOT), patch.name)
            val words = patch.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotBlank() }
            assertTrue(words.none { it in reserved }, "${patch.name} borrows a reserved engine, voice or rack label")
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
        }
    }

    @Test
    fun `every macro vector is separated and chosen roles differ beyond level and phase`() {
        val presets = MeridianPresets.all()
        for ((index, a) in presets.withIndex()) for (b in presets.drop(index + 1)) {
            val spread = PresetTestSupport.rmsDistance(a.macros, b.macros)
            assertTrue(spread > .05f, "${a.name} and ${b.name} collapse to the same macro neighborhood: $spread")
        }
        val pairs = listOf("FIRST ARC" to "BACK ARC", "CLOSE GRAIN" to "DEEP KNOCK",
            "STILL PAIR" to "LONG TRAIL", "ROUND THREAD" to "FINE STRAND")
        for ((nameA, nameB) in pairs) {
            val a = byName.getValue(nameA)
            val b = byName.getValue(nameB)
            val spectral = distance(a.spectrum, b.spectrum)
            val temporal = distance(a.envelope, b.envelope)
            println("MERIDIAN PRESETS $nameA / $nameB normalized power distance=$spectral activity distance=$temporal")
            // Current Kotlin renders, independently analyzed before this test,
            // measured at least .0809 spectral and .2700 temporal distance.
            // Four percent retains margin without accepting a gain/phase-only
            // change or using a held evidence file's duration as the distinction.
            assertTrue(spectral > .04 && temporal > .04,
                "$nameA / $nameB differ by less than four percent of spectral or temporal energy after matching")
        }
    }

    @Test
    fun `both held factory recipes retain independently measured closed sampler regions`() {
        for (patch in MeridianPresets.all().takeLast(2)) {
            val held = requireNotNull(byName.getValue(patch.name).held)
            assertContentEquals(byName.getValue(patch.name).snip.samples, held.snip.samples, patch.name)
            assertTrue(held.loopStartFrame >= 256 && held.loopEndFrameExclusive <= held.snip.frameCount, patch.name)
            assertTrue(held.loopEndFrameExclusive > held.loopStartFrame, patch.name)
            assertTrue(held.seamError.isFinite() && held.seamError < Keys.MAX_SEAM_ERROR, patch.name)
            assertTrue(held.cycleError.isFinite() && held.cycleError < 1e-3, patch.name)
            assertTrue(held.stateError.isFinite() && held.stateError < 1e-3, patch.name)
            assertTrue(held.noiseStateError.isFinite() && held.noiseStateError < 1e-9, patch.name)
            assertEquals(held.seamError, Keys.seamError(held.snip.samples, held.loopStartFrame), 1e-12,
                "${patch.name}: a duplicated loop replaced the real previous-cycle evidence")
        }
    }
}
