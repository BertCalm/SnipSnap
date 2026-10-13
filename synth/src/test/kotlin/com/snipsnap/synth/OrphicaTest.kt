package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrphicaTest {
    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    private fun mono(snip: Snip): Snip = Snip(
        FloatArray(snip.frameCount) { snip.samples[it * 2] }, 1, snip.sampleRate,
    )

    @Test
    fun `every seeded mode reproduces stereo audio and preserves the dry attack`() {
        for (mode in OrphicaMode.entries) {
            val config = OrphicaConfig(mode = mode, fracture = .9f, seconds = .6f, seed = 923748123L)
            val dry = Orphica.dry(config)
            val first = Orphica.render(config)
            val again = Orphica.render(config)
            assertEquals(44100, first.sampleRate)
            assertEquals(2, first.channels)
            assertEquals((config.seconds * Dsp.RATE).toInt(), first.frameCount)
            assertContentEquals(first.samples, again.samples, "$mode is not repeatable")
            val attackSamples = (Orphica.ATTACK_SECONDS * Dsp.RATE).toInt() * 2
            assertContentEquals(dry.samples.copyOf(attackSamples), first.samples.copyOf(attackSamples), "$mode obscures the pluck")
            if (mode != OrphicaMode.CLEAR) {
                assertFalse(first.samples.contentEquals(dry.samples), "$mode did not change the sustain")
                assertTrue((0 until first.frameCount).any { abs(first.samples[it * 2] - first.samples[it * 2 + 1]) > 1e-5f },
                    "$mode has no stereo grain field")
            }
        }
    }

    @Test
    fun `fracture zero and clear mode are exact dry bypasses`() {
        for (mode in OrphicaMode.entries) {
            val config = OrphicaConfig(mode = mode, fracture = 0f, seconds = .4f,
                grainSizeMs = 8f, grainRateHz = 100f, seed = Long.MIN_VALUE)
            assertContentEquals(Orphica.dry(config).samples, Orphica.render(config).samples, "$mode zero fracture")
        }
        val clear = OrphicaConfig(mode = OrphicaMode.CLEAR, fracture = 1f, seconds = .4f)
        assertContentEquals(Orphica.dry(clear).samples, Orphica.render(clear).samples)
    }

    @Test
    fun `material rate size damping and piano range extremes remain finite and bounded`() {
        for (material in OrphicaMaterial.entries) for (mode in OrphicaMode.entries) {
            for (high in listOf(false, true)) {
                val config = OrphicaConfig(material = material, mode = mode,
                    midi = if (high) Orphica.MIDI_MAX else Orphica.MIDI_MIN,
                    pluck = if (high) 1f else 0f, damping = if (high) 1f else 0f,
                    grainSizeMs = if (high) 120f else 8f, grainRateHz = if (high) 100f else 2f,
                    scatter = 1f, fracture = 1f, seconds = .4f, seed = Long.MAX_VALUE)
                val result = Orphica.render(config)
                assertTrue(result.samples.all { it.isFinite() && abs(it) <= .721f }, "$material $mode $high unstable output")
                assertTrue(result.peak() > .01f, "$material $mode $high silent output")
                assertTrue(result.samples.last() == 0f, "$material $mode unfinished release")
            }
        }
    }

    @Test
    fun `the dry string retains requested MIDI pitch across all three materials`() {
        for (material in OrphicaMaterial.entries) for (midi in listOf(36, 48, 60, 72, 84)) {
            val config = OrphicaConfig(material = material, midi = midi, fracture = 0f, damping = .1f, seconds = .6f)
            val hz = Keys.midiHz(midi)
            val cents = FineTuning.cents(FineTuning.measuredHz(mono(Orphica.render(config)), hz, .05f, .25f), hz.toDouble())
            assertTrue(abs(cents) < 5.0, "$material MIDI $midi is $cents cents from its requested note")
        }
    }

    @Test
    fun `clouds cannot hear future samples and live slice bounds stay in recent history`() {
        val frames = Dsp.RATE
        val cut = (Dsp.RATE * .4f).toInt()
        val before = FloatArray(frames) { sin(2.0 * PI * 261.6 * it / Dsp.RATE).toFloat() * .4f }
        val changed = before.copyOf().also { a -> for (i in cut until a.size) a[i] = if (i % 2 == 0) 1f else -1f }
        for (mode in listOf(OrphicaMode.SCATTER, OrphicaMode.REVERSE, OrphicaMode.STUTTER)) {
            val events = mutableListOf<Orphica.GrainEvent>()
            val config = OrphicaConfig(mode = mode, seconds = 1f, fracture = 1f,
                grainSizeMs = 120f, grainRateHz = 100f, scatter = 1f)
            val first = Orphica.fragment(before, config, events::add)
            val second = Orphica.fragment(changed, config)
            assertContentEquals(first.samples.copyOf(cut * 2), second.samples.copyOf(cut * 2), "$mode used future input")
            assertTrue(events.size > 80, "$mode fixture did not exercise the onset rate")
            for (event in events) {
                assertTrue(event.sourceFromFrame >= maxOf(0, event.onsetFrame - (Orphica.HISTORY_SECONDS * Dsp.RATE).toInt()),
                    "$mode grain escaped recent history: $event")
                assertTrue(event.sourceUntilFrame <= event.onsetFrame, "$mode read ahead: $event")
                assertTrue(event.sourceUntilFrame > event.sourceFromFrame, "$mode copied empty history")
                assertEquals(mode == OrphicaMode.REVERSE, event.reversed)
                assertFalse(event.frozen)
            }
            val count = ((frames - (Orphica.ATTACK_SECONDS * Dsp.RATE).toInt()) / Dsp.RATE.toDouble() * config.grainRateHz)
            assertTrue(abs(events.size - count) < 1.1, "$mode does not honor the requested grain onset rate")
        }
    }

    @Test
    fun `freeze captures populated history and holds it independently of later input`() {
        val config = OrphicaConfig(mode = OrphicaMode.FREEZE, fracture = 1f, seconds = 1.6f, captureSeconds = .15f)
        val capture = (config.captureSeconds * Dsp.RATE).toInt()
        val source = FloatArray((config.seconds * Dsp.RATE).toInt()) {
            if (it < capture) (sin(2.0 * PI * 220.0 * it / Dsp.RATE) * .4).toFloat() else 0f
        }
        val laterChanged = source.copyOf().also { a -> for (i in capture until a.size) a[i] = .8f }
        val events = mutableListOf<Orphica.GrainEvent>()
        val frozen = Orphica.fragment(source, config, events::add)
        assertContentEquals(frozen.samples, Orphica.fragment(laterChanged, config).samples,
            "captured grains follow subsequent source writes")
        assertTrue(rms(frozen.samples.copyOfRange(Dsp.RATE * 2, (Dsp.RATE * 1.3f).toInt() * 2)) > .01,
            "freeze latched silence instead of the populated history")
        val held = events.filter { it.frozen }
        assertTrue(held.isNotEmpty(), "no captured grain was emitted")
        assertTrue(held.all { it.sourceUntilFrame <= capture && it.sourceFromFrame >= 0 }, "freeze read outside its captured buffer")
        assertTrue(held.any { it.onsetFrame > Dsp.RATE }, "captured sustain did not continue through the tail")

        val note = Orphica.render(config)
        assertTrue(rms(note.samples.copyOfRange(Dsp.RATE * 2, (Dsp.RATE * 1.3f).toInt() * 2)) > 1e-4,
            "the public renderer does not populate freeze before latching")
    }

    @Test
    fun `normalizing actual overlaps bounds dense clouds without muting sparse grains`() {
        val source = FloatArray(Dsp.RATE) { .4f }
        val dense = Orphica.fragment(source, OrphicaConfig(fracture = 1f, seconds = 1f,
            grainSizeMs = 120f, grainRateHz = 100f))
        val sparse = Orphica.fragment(source, OrphicaConfig(fracture = 1f, seconds = 1f,
            grainSizeMs = 8f, grainRateHz = 2f))
        assertTrue(dense.samples.all { it in 0f..0.40001f }, "density amplified the source")
        assertTrue(rms(dense.samples.copyOfRange(Dsp.RATE / 2, Dsp.RATE)) > .28, "dense overlap lost its level")
        assertTrue(sparse.samples.copyOfRange(Dsp.RATE / 5, sparse.samples.size).max() > .35f,
            "normalization erased sparse grain peaks after the dry attack")
    }

    @Test
    fun `zero velocity remains silent even when history is frozen`() {
        for (mode in OrphicaMode.entries) {
            val result = Orphica.render(OrphicaConfig(mode = mode, velocity = 0f, fracture = 1f, seconds = .4f))
            assertTrue(result.samples.all { it == 0f }, "$mode emits sound at zero velocity")
        }
    }

    @Test
    fun `harder velocity brightens the excitation after level matching`() {
        for (material in OrphicaMaterial.entries) {
            val config = OrphicaConfig(material = material, fracture = 0f, seconds = .4f, pluck = .8f)
            val soft = mono(Orphica.dry(config.copy(velocity = .3f))).samples
            val hard = mono(Orphica.dry(config.copy(velocity = 1f))).samples
            val softLevel = rms(soft)
            val hardLevel = rms(hard)
            assertTrue(hardLevel > softLevel, "$material velocity lost excitation strength")
            var distance = 0.0
            for (i in soft.indices) {
                val delta = soft[i] / softLevel - hard[i] / hardLevel
                distance += delta * delta
            }
            assertTrue(sqrt(distance / soft.size) > .05, "$material velocity affects gain alone")
            val softTreble = PluckSpectra.highShare(soft, Dsp.RATE, 2500f, .025f)
            val hardTreble = PluckSpectra.highShare(hard, Dsp.RATE, 2500f, .025f)
            assertTrue(hardTreble > softTreble, "$material harder stroke does not brighten the attack: $softTreble / $hardTreble")
        }
    }
}
