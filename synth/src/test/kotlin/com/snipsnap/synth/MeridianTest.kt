package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Causal and numerical gates for the finite, dry prototype; timbral approval stays with listening. */
class MeridianTest {
    private val voice = MeridianVoice.SWEEP

    @Test
    fun `saved native note velocity seed and model survive JSON and a macro edit`() {
        val patch = MeridianPatch("Material memory", voice, mapOf("LATITUDE" to .2f, "MEMORY" to .7f),
            midi = 51, velocity = .65f, seedContext = 41)
        val roundTrip = Patches.fromJsonText(patch.toJsonText()) as MeridianPatch
        assertEquals(patch, roundTrip)
        assertContentEquals(patch.render().samples, roundTrip.render().samples)
        val edited = Patches.edited(patch, "Changed reach", patch.macros + ("REACH" to .4f)) as MeridianPatch
        assertEquals(patch.midi, edited.midi)
        assertEquals(patch.velocity, edited.velocity)
        assertEquals(patch.seedContext, edited.seedContext)
        assertEquals(patch.model, edited.model)
        assertEquals(.4f, edited.macros.getValue("REACH"))
        assertEquals(8, Presets.forVoice(MeridianPatch.ENGINE, voice.name).size, "the playable bank remains available after recipe edits")
    }

    @Test
    fun `saved malformed inputs are rejected while rendering settles nonfinite macros`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            assertFailsWith<IllegalArgumentException> { MeridianPatch("Bad", voice, mapOf("REACH" to bad)) }
        }
        assertFailsWith<IllegalArgumentException> { MeridianPatch("Bad", voice, mapOf("DRIFT" to .5f)) }
        assertFailsWith<IllegalArgumentException> { MeridianPatch("Bad", voice, emptyMap(), midi = Meridian.MIDI_MIN - 1) }
        assertFailsWith<IllegalArgumentException> { MeridianPatch("Bad", voice, emptyMap(), velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { MeridianPatch("Bad", voice, emptyMap(), model = Meridian.MODEL_VERSION + 1) }
        val future = JsonValue.Obj(LinkedHashMap(MeridianPatch("Bad", voice, emptyMap()).toJsonValue().entries).apply {
            put("model", JsonValue.Num((Meridian.MODEL_VERSION + 1).toDouble()))
        })
        assertFailsWith<JsonException> { MeridianPatch.fromJsonValue(future) }
        val clean = probe()
        val settled = probe(mapOf("LATITUDE" to Float.NaN, "REACH" to Float.POSITIVE_INFINITY))
        assertContentEquals(clean.samples, settled.samples)
    }

    @Test
    fun `repeated seeds and reordered macro maps reproduce every sample`() {
        val controls = linkedMapOf("LATITUDE" to .8f, "LENGTH" to .35f, "REACH" to .7f,
            "SURFACE" to .6f, "MEMORY" to .55f)
        val a = probe(controls)
        val b = probe(controls.entries.reversed().associate { it.key to it.value })
        assertContentEquals(a.samples, b.samples)
        assertContentEquals(a.diagnostics.inputHashes, b.diagnostics.inputHashes)
        val changedSeed = Meridian.renderProbe(voice, controls, seedContext = 42,
            activeSeconds = .65f, tailSeconds = .4f)
        assertFalse(a.samples.contentEquals(changedSeed.samples), "the saved seed never reached the contact roughness")
    }

    @Test
    fun `zero velocity leaves the modal state silent while interruption cancels rendering`() {
        val silent = Meridian.renderProbe(voice, velocity = 0f, activeSeconds = .5f, tailSeconds = .3f, taps = true)
        assertTrue(silent.samples.all { it == 0f })
        assertTrue(silent.surfaceOutputs.all { samples -> samples.all { it == 0f } })
        assertTrue(silent.diagnostics.perSurfaceInputEnergy.all { it == 0.0 })
        assertEquals(0.0, silent.diagnostics.peakEnergy)
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException> { Meridian.render(voice) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `latitude reverses traversal from the same start and has an exactly stationary center`() {
        val south = motion(latitude = .2)
        val north = motion(latitude = .8)
        val still = motion(latitude = .5)
        assertEquals(0.0, still.precessionRate)
        assertEquals(south.pointAt(0.0).x, north.pointAt(0.0).x)
        assertEquals(south.pointAt(0.0).y, north.pointAt(0.0).y)
        assertEquals(-south.precessionRate, north.precessionRate, 1e-14)
        val t = south.periodSeconds
        fun angle(plan: MeridianMotion.Plan) = plan.pointAt(t).let { atan2(it.y, it.x) }
        assertTrue(angle(south) > .12, "southern latitude did not turn counterclockwise")
        assertTrue(angle(north) < .12, "northern latitude did not turn clockwise")
        assertEquals(.12, angle(still), 1e-12)
        val movingContacts = south.weightsAt(1.7)
        assertFalse(movingContacts.contentEquals(north.weightsAt(1.7)), "opposite directions retained identical contacts")
        val centerAudio = probe(mapOf("LATITUDE" to .5f))
        val fixedAudio = Meridian.renderProbe(voice, mapOf("LATITUDE" to .8f), activeSeconds = .65f,
            tailSeconds = .4f, fixedAlignment = true)
        assertContentEquals(centerAudio.samples, fixedAudio.samples, "the equator acquired an artificial drift")
    }

    @Test
    fun `musical length changes full swings independently of requested pitch and precession`() {
        val short = motion(length = 0.0)
        val long = motion(length = 1.0)
        assertEquals(.15, short.periodSeconds, 1e-12)
        assertEquals(1.5, long.periodSeconds, 1e-12)
        assertEquals(short.precessionRate, long.precessionRate)
        val fast = Meridian.renderProbe(voice, mapOf("LATITUDE" to .5f, "LENGTH" to 0f),
            activeSeconds = 1.6f, tailSeconds = .25f)
        val slow = Meridian.renderProbe(voice, mapOf("LATITUDE" to .5f, "LENGTH" to 1f),
            activeSeconds = 1.6f, tailSeconds = .25f)
        assertTrue(fast.diagnostics.triggerCounts.sum() > slow.diagnostics.triggerCounts.sum() * 3,
            "LENGTH did not change the number of surface entries")
        assertEquals(fast.diagnostics.rootHz, slow.diagnostics.rootHz)
        val originalNote = probe()
        val differentNote = Meridian.renderProbe(voice, midi = 69, activeSeconds = .65f, tailSeconds = .4f)
        assertTrue(differentNote.diagnostics.geometryCacheHit, "pitch needlessly invalidated the prepared contact geometry")
        assertEquals(originalNote.motion.periodSeconds, differentNote.motion.periodSeconds)
    }

    @Test
    fun `simultaneously rotating floor and trajectory preserves all relative contact histories`() {
        val ordinary = motion()
        val rotation = PI / 7
        val rotated = MeridianMotion.prepare(.8, .45, .6, 2.0,
            initialOrientation = .12 + rotation, floorRotation = rotation).plan
        for (i in 0..160) {
            val time = i * 2.0 / 160
            val a = ordinary.weightsAt(time)
            val b = rotated.weightsAt(time)
            for (surface in a.indices) assertEquals(a[surface], b[surface], 1e-12, "surface $surface at $time")
        }
        for (surface in ordinary.entries.indices) {
            assertEquals(ordinary.entries[surface].size, rotated.entries[surface].size)
            for ((a, b) in ordinary.entries[surface].zip(rotated.entries[surface])) {
                assertEquals(a.seconds, b.seconds, 1e-11)
                assertEquals(a.strength, b.strength, 1e-11)
            }
        }
    }

    @Test
    fun `center crossings and minimum reach keep contact finite bounded and useful`() {
        for (reach in listOf(0.0, .5, 1.0)) {
            val plan = motion(reach = reach)
            // Prepared weights are float32; the sum may exceed one by a few rounding ulps.
            assertTrue(plan.maxWeightSum <= 1.0 + 1e-6)
            assertTrue(plan.entries.flatten().all { it.strength.isFinite() && it.strength >= 0.0 })
            var contact = 0.0
            for (i in 0..2000) {
                val weights = plan.weightsAt(i / 1000.0)
                assertTrue(weights.all { it.isFinite() && it >= 0.0 })
                assertTrue(weights.sum() <= 1.0 + 1e-6)
                contact += weights.sum()
            }
            assertTrue(contact > 10.0, "REACH $reach produced a silent ring")
            val atCenter = plan.weightsAt(plan.periodSeconds / 4)
            assertTrue(atCenter.all { it == 0.0 }, "the direction singularity at the center produced contact")
        }
    }

    @Test
    fun `a new grazing visit adds a soft catch instead of a full tangential strike`() {
        // Regression: total bob speed was high here, but surface 1 only barely
        // entered contact. A .001 REACH edit formerly introduced a .26 impulse.
        val lower = MeridianMotion.prepare(.8, 0.0, .869, 2.0).plan
        val upper = MeridianMotion.prepare(.8, 0.0, .870, 2.0).plan
        assertTrue(lower.entries[1].none { it.seconds in .40..0.46 })
        val grazing = upper.entries[1].single { it.seconds in .40..0.46 }
        println("MERIDIAN new grazing catch at ${grazing.seconds}s strength=${grazing.strength}")
        assertTrue(grazing.strength < 1e-5, "a barely contacted ribbon received a full-strength catch")
        val controls = mapOf("LENGTH" to 0f)
        val a = Meridian.renderProbe(voice, controls + ("REACH" to .869f),
            activeSeconds = .8f, tailSeconds = .3f)
        val b = Meridian.renderProbe(voice, controls + ("REACH" to .870f),
            activeSeconds = .8f, tailSeconds = .3f)
        val difference = relativeDifference(a.samples, b.samples)
        println("MERIDIAN grazing REACH .869 to .870 raw waveform difference=$difference")
        assertTrue(difference < .03, "a fine REACH edit introduced an abrupt new hit")
    }

    @Test
    fun `surfaces outside the contact path receive no note-on force or resonator output`() {
        val p = Meridian.renderProbe(voice, mapOf("LATITUDE" to .5f), activeSeconds = .8f,
            tailSeconds = .4f, taps = true)
        val noncontacts = (0 until MeridianMotion.SURFACES).filter { surface ->
            (0..800).all { p.motion.weightsAt(it / 1000.0)[surface] == 0.0 }
        }
        assertTrue(noncontacts.size >= 6, "stationary fixture did not leave surfaces untouched")
        for (surface in noncontacts) {
            assertEquals(0.0, p.diagnostics.perSurfaceInputEnergy[surface])
            assertEquals(0.0, p.diagnostics.perSurfaceInputPeak[surface])
            assertTrue(p.surfaceOutputs[surface].all { it == 0f }, "uncontacted surface $surface was excited")
        }
        assertTrue(p.diagnostics.perSurfaceInputEnergy.any { it > 0.0 })
    }

    @Test
    fun `memory keeps the same excitation while extending each existing surface tail`() {
        val controls = mapOf("LATITUDE" to .5f, "LENGTH" to .3f)
        val short = Meridian.renderProbe(voice, controls + ("MEMORY" to .15f),
            activeSeconds = .7f, tailSeconds = .9f, taps = true)
        val long = Meridian.renderProbe(voice, controls + ("MEMORY" to .8f),
            activeSeconds = .7f, tailSeconds = .9f, taps = true)
        assertContentEquals(short.diagnostics.inputHashes, long.diagnostics.inputHashes,
            "MEMORY changed the actual excitation stream")
        assertContentEquals(short.diagnostics.roughnessHashes, long.diagnostics.roughnessHashes)
        assertTrue(long.diagnostics.geometryCacheHit, "MEMORY rebuilt pitch-independent geometry")
        val shortTail = rms(short.samples, .85, 1.1)
        val longTail = rms(long.samples, .85, 1.1)
        println("MERIDIAN raw MEMORY tail RMS .15=$shortTail .8=$longTail")
        assertTrue(longTail > shortTail * 3, "MEMORY did not preserve earlier ringing after excitation stopped")
        assertTrue(long.surfaceOutputs.count { rms(it, .85, 1.1) > 1e-7 } >= 2,
            "past contacts lost their independent ringing states")
    }

    @Test
    fun `revisits add to surface state rather than resetting the ringing phase`() {
        val controls = mapOf("LATITUDE" to .5f, "LENGTH" to .2f, "MEMORY" to .9f)
        val preserved = Meridian.renderProbe(voice, controls, activeSeconds = 1.1f,
            tailSeconds = .3f, taps = true)
        val reset = Meridian.renderProbe(voice, controls, activeSeconds = 1.1f,
            tailSeconds = .3f, taps = true, resetOnContact = true)
        assertContentEquals(preserved.diagnostics.inputHashes, reset.diagnostics.inputHashes,
            "the reset ablation changed the contact excitation")
        assertTrue(preserved.diagnostics.triggerCounts.sum() >= 4, "the fixture did not revisit a surface")
        assertTrue(relativeDifference(preserved.samples, reset.samples) > .02,
            "preserving and clearing old modal state gave the same repeated-contact response")
    }

    @Test
    fun `all surface roots keep the native note and unforced tails retain its frequency`() {
        var worstCents = 0.0
        for (midi in listOf(51, 57, 81)) {
            val p = Meridian.renderProbe(voice, mapOf("MEMORY" to .7f, "SURFACE" to .7f), midi = midi,
                activeSeconds = .35f, tailSeconds = .65f)
            val hz = Meridian.frequencyFor(voice, .5f, midi)
            for (surface in 0 until MeridianMotion.SURFACES) {
                assertEquals(hz.toDouble(), p.diagnostics.rootModalHz[surface * Meridian.MODES_PER_SURFACE], 1e-5)
            }
            val cents = FineTuning.cents(FineTuning.measuredHz(p.samples, Dsp.RATE, hz, .4f, .3f), hz.toDouble())
            worstCents = max(worstCents, abs(cents))
            assertTrue(abs(cents) < 5.0, "MIDI $midi unforced tail is $cents cents from its root")
            assertTrue(rootShare(p.samples, hz, .4f, .3f) > .15,
                "MIDI $midi root estimate follows a negligible mode")
        }
        println("MERIDIAN unforced root spectral estimate worst $worstCents cents")
    }

    @Test
    fun `short moderate full mixes have a detectable root and avoid drum classification`() {
        for (midi in listOf(48, 57, 81)) {
            val p = Meridian.renderProbe(voice, midi = midi, activeSeconds = .65f, tailSeconds = .55f, normalize = true)
            val snip = Snip(p.samples, 1, Dsp.RATE)
            val detected = Pitch.detect(snip, .1f, .4f) ?: error("MIDI $midi has no detected full-mix pitch")
            val hz = Meridian.frequencyFor(voice, .5f, midi)
            val cents = FineTuning.cents(detected.hz.toDouble(), hz.toDouble())
            val classification = Classifier.classify(snip).drumClass
            println("MERIDIAN MIDI $midi full mix $cents cents confidence ${detected.confidence}, class $classification")
            assertTrue(abs(cents) < 50.0, "MIDI $midi full mix lost its requested root: $cents cents")
            assertTrue(rootShare(p.samples, hz, .1f, .4f) > .15)
            assertTrue(classification !in DRUMS, "MIDI $midi filed as $classification")
        }
    }

    @Test
    fun `highest native note plus full tune fades upper modes while retaining an audible root`() {
        // MIDI 108's upper ratios straddle 14–18 kHz and extend above the output band.
        // MIDI 81/84 do not exercise that smooth suppression region.
        val controls = mapOf("TUNE" to 1f, "SURFACE" to 1f, "MEMORY" to .7f)
        val p = Meridian.renderProbe(voice, controls, midi = Meridian.MIDI_MAX,
            activeSeconds = 2f, tailSeconds = .65f)
        val hz = Meridian.frequencyFor(voice, 1f, Meridian.MIDI_MAX)
        assertTrue(p.diagnostics.rootModalHz.any { it in 14000.0..18000.0 }, "fixture missed the upper-mode fade")
        assertTrue(p.diagnostics.rootModalHz.any { it > 18000.0 }, "fixture missed modes suppressed above the output band")
        assertTrue(p.samples.all { it.isFinite() })
        assertTrue(rms(p.samples) > 1e-5, "upper-mode suppression silenced the instrument")
        val cents = FineTuning.cents(FineTuning.measuredHz(p.samples, Dsp.RATE, hz, 2.05f, .25f), hz.toDouble())
        val share = rootShare(p.samples, hz, 2.05f, .25f)
        val aboveBand = highBandShare(p.samples, 18000.0)
        println("MERIDIAN top-note upper-band probe: root $cents cents, root share $share, >18kHz energy fraction $aboveBand")
        assertTrue(abs(cents) < 5.0, "top-note tail lost its root: $cents cents")
        assertTrue(share > .15, "top-note root became negligible")
        assertTrue(aboveBand < .001, "substantial energy escaped above the modal fade: $aboveBand")
    }

    @Test
    fun `every timbral macro changes normalized audio at low and middle settings`() {
        for (macro in listOf("LATITUDE", "LENGTH", "REACH", "SURFACE", "MEMORY")) {
            val values = listOf(.2f, .5f, .8f).map { value ->
                Meridian.renderProbe(voice, mapOf(macro to value), activeSeconds = 1.7f, tailSeconds = .3f).samples
            }
            val lower = normalizedDifference(values[0], values[1])
            val upper = normalizedDifference(values[1], values[2])
            println("MERIDIAN $macro normalized differences .2→.5=$lower .5→.8=$upper")
            assertTrue(lower > .01 && upper > .01, "$macro was inert or only changed gain in one part of its range")
        }
    }

    @Test
    fun `finite extremes are bounded before leveling and end with a quiet release`() {
        for ((midi, controls) in listOf(
            36 to mapOf("LATITUDE" to .5f, "LENGTH" to 0f, "REACH" to 1f, "SURFACE" to 1f, "MEMORY" to 1f),
            84 to mapOf("LATITUDE" to 1f, "LENGTH" to 1f, "REACH" to 0f, "SURFACE" to 1f, "MEMORY" to .5f),
        )) {
            val raw = Meridian.renderProbe(voice, controls, midi = midi)
            assertTrue(raw.samples.all { it.isFinite() })
            assertTrue(raw.diagnostics.rawPeak.isFinite() && raw.diagnostics.rawPeak < 8.0,
                "MIDI $midi has unbounded pre-level output ${raw.diagnostics.rawPeak}")
            assertTrue(raw.diagnostics.peakEnergy.isFinite() && raw.diagnostics.peakEnergy < 100.0,
                "MIDI $midi has unbounded internal modal energy ${raw.diagnostics.peakEnergy}")
            assertTrue(rms(raw.samples, raw.samples.size / Dsp.RATE.toDouble() - .02,
                raw.samples.size / Dsp.RATE.toDouble()) < rms(raw.samples) * .005,
                "MIDI $midi finite release left substantial state at the file boundary")
            val finished = Meridian.render(voice, controls, midi = midi)
            assertTrue(finished.samples.size <= 8 * Dsp.RATE)
            assertTrue(finished.samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(abs(finished.samples.average()) < .001)
        }
        val short = Meridian.render(voice, mapOf("HOLD" to 0f))
        val longer = Meridian.render(voice, mapOf("HOLD" to 1f), model = 1)
        assertTrue(longer.frameCount > short.frameCount, "the prototype's finite HOLD gate did not lengthen the gesture")
    }

    private fun probe(controls: Map<String, Float> = emptyMap()) = Meridian.renderProbe(
        voice, controls, activeSeconds = .65f, tailSeconds = .4f)

    private fun motion(latitude: Double = .8, length: Double = .45, reach: Double = .6) =
        MeridianMotion.prepare(latitude, length, reach, 2.0).plan

    private fun rms(samples: FloatArray, fromSeconds: Double = 0.0,
        toSeconds: Double = samples.size / Dsp.RATE.toDouble()): Double {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val to = (toSeconds * Dsp.RATE).toInt().coerceIn(from, samples.size)
        var energy = 0.0
        for (i in from until to) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (to - from).coerceAtLeast(1))
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var difference = 0.0
        var energy = 0.0
        for (i in a.indices) {
            difference += (a[i] - b[i]).toDouble() * (a[i] - b[i])
            energy += a[i].toDouble() * a[i]
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    private fun normalizedDifference(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        val gain = rms(a) / rms(b).coerceAtLeast(1e-30)
        var difference = 0.0
        var energy = 0.0
        for (i in a.indices) {
            difference += (a[i] - gain * b[i]) * (a[i] - gain * b[i])
            energy += a[i].toDouble() * a[i]
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    /** Wide enough to include a short note's linewidth; a narrow search alone could follow a negligible root. */
    private fun rootShare(samples: FloatArray, root: Float, fromSeconds: Float, durationSeconds: Float): Double {
        val n = 32768
        val from = (fromSeconds * Dsp.RATE).toInt()
        val count = minOf(n, (durationSeconds * Dsp.RATE).toInt(), samples.size - from)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = (samples[from + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(re, im)
        var rootEnergy = 0.0
        var total = 0.0
        val halfWidth = max(5.0, root * .04)
        for (bin in 1 until n / 2) {
            val energy = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            total += energy
            if (abs(bin.toDouble() * Dsp.RATE / n - root) < halfWidth) rootEnergy += energy
        }
        return rootEnergy / total.coerceAtLeast(1e-30)
    }

    private fun highBandShare(samples: FloatArray, fromHz: Double): Double {
        val n = 32768
        val from = (.15 * Dsp.RATE).toInt()
        val count = minOf(n, (.65 * Dsp.RATE).toInt(), samples.size - from)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = (samples[from + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(re, im)
        var high = 0.0
        var total = 0.0
        for (bin in 1 until n / 2) {
            val energy = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            total += energy
            if (bin.toDouble() * Dsp.RATE / n >= fromHz) high += energy
        }
        return high / total.coerceAtLeast(1e-30)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
