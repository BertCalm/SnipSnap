package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real dry drives, public renderer and delivered PCM, rather than a perpetual test sine. */
class OhmV4MemoryTest {
    private val rate = Dsp.RATE
    private val base = Ohm.defaults(4) + mapOf("TOUCH" to .5f, "BODY" to .5f,
        "LENGTH" to .65f, "MEDDLING" to .9f, "HOLD" to 0f)

    private fun patch(options: OhmOptions, commune: Float, midi: Int = 60) =
        OhmPatch("Memory proof", base + ("COMMUNE" to commune), midi = midi,
            velocity = .8f, seed = 731, model = 4, options = options)

    private fun raw(patch: OhmPatch) = Ohm.renderRaw(patch.macros, patch.midi, patch.velocity,
        patch.seed, patch.gesture, patch.model, patch.options)

    @Test
    fun `real finite plucks deliver grain and freeze changes at low middle and full amounts in both relationships`() {
        val failures = mutableListOf<String>()
        for (commune in listOf(0f, 1f)) {
            val off = raw(patch(OhmOptions(), commune))
            for (feature in listOf("grains", "freeze")) for (amount in listOf(.25f, .6f, 1f)) {
                val options = if (feature == "grains") OhmOptions(grains = amount) else OhmOptions(freeze = amount)
                val on = raw(patch(options, commune))
                val gain = minOf(1.0, .98 / maxOf(peak(off), peak(on)).coerceAtLeast(1e-12))
                val versions = listOf("raw" to (pcm(scaled(off, gain)) to pcm(scaled(on, gain))),
                    "matched" to (pcm(AuditionLevel.level(off)) to pcm(AuditionLevel.level(on))))
                for ((mode, pair) in versions) {
                    val difference = relativeRms(pair.first.samples, pair.second.samples, .30)
                    val shape = shapeResidual(pair.first.samples, pair.second.samples, .30)
                    val lsb = pair.first.samples.indices.maxOf { i -> abs(pair.first.samples[i] - pair.second.samples[i]) } * 32768
                    val floor = when (amount) { .25f -> .01; .6f -> .03; else -> .05 }
                    println("OHM4 $feature amount$amount C$commune $mode post300ms difference=$difference (${20 * log10(difference.coerceAtLeast(1e-15))}dB), shape=$shape, maxPCM16LSB=$lsb")
                    if (difference < floor || shape < floor * .75 || lsb < 32)
                        failures += "$feature $amount C$commune $mode: difference $difference / shape $shape / LSB $lsb"
                }
            }
        }
        assertTrue(failures.isEmpty(), "memory controls lack a meaningful delivered shape change:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `memory alters the foreground only through the live shared body`() {
        for (commune in listOf(0f, 1f)) {
            val m = base + ("COMMUNE" to commune)
            val off = OhmV3.foregroundProbe(m, textureRevision = 4)
            for (options in listOf(OhmOptions(grains = .6f), OhmOptions(freeze = .75f))) {
                val on = OhmV3.foregroundProbe(m, options = options, textureRevision = 4)
                if (commune == 0f) assertContentEquals(off.foreground, on.foreground,
                    "source memory reached the foreground without a bridge")
                else {
                    val changed = relativeRms(off.foreground, on.foreground, .30, off.sampleRate)
                    println("OHM4 causal primary grains${options.grains} freeze${options.freeze}: relativeRms=$changed")
                    assertTrue(changed > 1e-5, "shared bridge carries no meaningful physical response to memory")
                }
                assertTrue(on.maxState.isFinite() && on.maxState < 8f)
            }
        }
    }

    @Test
    fun `repaired memory keeps native notes bounded replayable and silent at zero velocity`() {
        for (midi in listOf(48, 60, 72)) {
            val options = OhmOptions(grains = .6f, freeze = .75f)
            val p = patch(options, 1f, midi)
            val sound = p.render()
            assertTrue(sound.samples.all { it.isFinite() && abs(it) <= .990001f })
            val hz = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(sound, hz, .08f, .30f)
            assertTrue(abs(FineTuning.cents(measured, hz.toDouble())) < 10,
                "memory moved native MIDI$midi to $measured")
            assertContentEquals(sound.samples, OhmPatch.fromJsonText(p.toJsonText()).render().samples)
            assertTrue(p.copy(velocity = 0f).render().samples.all { it == 0f })
        }
    }

    @Test
    fun `captured material and bowed drive also reach delivered memory instead of a synthetic replacement`() {
        val failures = mutableListOf<String>()
        for (exciter in listOf(OhmExciter.BOW, OhmExciter.CAPTURED)) {
            val options = OhmOptions(exciter = exciter,
                capture = if (exciter == OhmExciter.CAPTURED) capture(3) else null)
            for (commune in listOf(0f, 1f)) {
                val off = pcm(AuditionLevel.level(raw(patch(options, commune))))
                for (onOptions in listOf(options.copy(grains = .6f), options.copy(freeze = .75f))) {
                    val on = pcm(AuditionLevel.level(raw(patch(onOptions, commune))))
                    val difference = relativeRms(off.samples, on.samples, .30)
                    val shape = shapeResidual(off.samples, on.samples, .30)
                    println("OHM4 $exciter grains${onOptions.grains} freeze${onOptions.freeze} C$commune: delivered difference=$difference shape=$shape")
                    if (difference < .03 || shape < .02) failures += "$exciter C$commune $onOptions difference=$difference shape=$shape"
                }
            }
        }
        val a = raw(patch(OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture(3), freeze = .75f), 1f))
        val b = raw(patch(OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture(5), freeze = .75f), 1f))
        val aOff = raw(patch(OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture(3)), 1f))
        val bOff = raw(patch(OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture(5)), 1f))
        val aDelta = FloatArray(a.samples.size) { a.samples[it] - aOff.samples[it] }
        val bDelta = FloatArray(b.samples.size) { b.samples[it] - bOff.samples[it] }
        assertTrue(relativeRms(aDelta, bDelta, .3) > .02,
            "saved source PCM changes the baseline but cannot influence the memory itself")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `delivered memory cannot replace a retained late fundamental with the third harmonic`() {
        for (midi in listOf(48, 60, 72)) {
            val off = pcm(AuditionLevel.level(raw(patch(OhmOptions(), 0f, midi))))
            for (options in listOf(OhmOptions(grains = .6f), OhmOptions(freeze = .75f), OhmOptions(freeze = 1f))) {
                val on = pcm(AuditionLevel.level(raw(patch(options, 0f, midi))))
                for (from in listOf(.5f, 1.25f, 2.5f)) {
                    val wanted = Keys.midiHz(midi).toDouble()
                    val reference = Pitch.detect(off, from, .3f)
                    if (reference != null && reference.confidence > .5f &&
                        abs(FineTuning.cents(reference.hz.toDouble(), wanted)) < 35) {
                        val estimate = Pitch.detect(on, from, .3f)
                        val cents = estimate?.let { FineTuning.cents(it.hz.toDouble(), wanted) }
                        println("OHM4 late MIDI$midi at$from grains${options.grains} freeze${options.freeze}: $estimate cents$cents")
                        assertTrue(estimate != null && estimate.confidence > .3f && cents != null && abs(cents) < 35,
                            "memory introduced a new wrong-note plateau: MIDI$midi at$from, $estimate, $cents cents")
                    }
                }
            }
        }
    }

    @Test
    fun `held memory sources retain actual physical continuation and clean decoded seams`() {
        for (options in listOf(OhmOptions(grains = .6f), OhmOptions(freeze = .75f))) {
            val measured = OhmV3.heldProbe(base + mapOf("HOLD" to 1f, "COMMUNE" to 1f),
                velocity = .8f, seed = 731, options = options, textureRevision = 4)
            assertTrue(!measured.appliedBlend && measured.sustainMethod == "physical-periodic")
            assertTrue(measured.rawContinuationError < 1e-3 && measured.continuationError < 1e-3)
            val decoded = pcm(measured.note.snip)
            assertTrue(Keys.seamError(decoded.samples, measured.loopStart) < Keys.MAX_SEAM_ERROR)
            assertTrue(measured.maxState.isFinite() && measured.maxState < 8f)
            assertTrue(measured.rawLoop !== measured.rawNextCycle)
        }
    }

    @Test
    fun `short dry drives produce populated causal ringing history without body feedback`() {
        val internalRate = rate * 4
        val frames = internalRate / 2
        fun freeze(future: Float): Pair<OhmTextures, FloatArray> {
            val helper = OhmTextures(OhmOptions(freeze = .75f), 60, 731, internalRate, revision = 4)
            val output = FloatArray(2)
            val result = FloatArray(frames)
            for (i in 0 until frames) {
                val drive = if (i < internalRate / 250) .1f * sin(2 * PI * 261.625565 * i / internalRate).toFloat()
                    else if (i >= internalRate / 4) future else 0f
                helper.fill(i, .9f, 1f, drive, output)
                result[i] = output[1]
            }
            return helper to result
        }
        val (helper, first) = freeze(0f)
        val (_, second) = freeze(.1f)
        assertEquals(internalRate / 4, helper.frozenSnapshotFrames)
        assertTrue(helper.maxSourceState > .001f)
        assertTrue(rms(first.copyOfRange(internalRate / 3, frames)) > 1e-5,
            "real impulse history was erased before freeze playback")
        assertContentEquals(first, second, "future source leaked into a captured ringing buffer")
    }

    @Test
    fun `captured source conditioning is bounded monotone and cannot create quiet or zero energy`() {
        val capture = OhmCapture(FloatArray(rate / 4) { .1f }, "Duration fixture")
        var previous = -1f
        for (level in listOf(0f, .0001f, .001f, .01f, .1f)) {
            val helper = OhmTextures(OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture, freeze = 1f),
                60, 731, rate, revision = 4)
            val pair = FloatArray(2)
            for (i in 0 until rate * 3 / 5) {
                val drive = if (i < rate / 4) level * sin(2 * PI * Keys.midiHz(60) * i / rate).toFloat() else 0f
                helper.fill(i, .9f, 1f, drive, pair)
            }
            assertTrue(helper.bankConditioningGain in 0f..32f)
            assertTrue(helper.bankRmsAfter >= previous - 1e-7f, "louder source produced a quieter prepared bank")
            assertTrue(helper.bankRmsAfter <= .200001f && helper.maxPreparedPeak <= .800001f)
            assertTrue(helper.bankRmsAfter <= helper.bankRmsBefore * 32.0001f)
            if (level == 0f) assertEquals(0f, helper.bankRmsAfter)
            if (level == .0001f) assertTrue(helper.bankRmsAfter < .005f, "quiet source became a full-level bank")
            println("OHM4 CAP bank input$level before${helper.bankRmsBefore} gain${helper.bankConditioningGain} after${helper.bankRmsAfter} peak${helper.maxPreparedPeak}")
            previous = helper.bankRmsAfter
        }
    }

    @Test
    fun `finite freeze continues the actual captured source phase instead of resetting its clock`() {
        val internalRate = rate * 4
        for (midi in listOf(48, 60, 72)) {
            val hz = Keys.midiHz(midi).toDouble()
            val source = Modes.Bank(1, internalRate).also { it.tune(0, hz, .65) }
            val participation = doubleArrayOf(2 * hz)
            val helper = OhmTextures(OhmOptions(freeze = 1f), midi, 731, internalRate, revision = 4)
            val from = helper.historyFrames + internalRate / 20
            val count = (4 * internalRate / hz).toInt()
            var aa = 0.0; var bb = 0.0; var ab = 0.0
            val pair = FloatArray(2)
            for (frame in 0 until from + count) {
                val drive = if (frame < internalRate / 250) .1f * sin(2 * PI * hz * frame / internalRate).toFloat() else 0f
                source.step()
                source.drive(participation, drive.toDouble())
                helper.fill(frame, .9f, 1f, drive, pair)
                if (frame >= from) {
                    val actual = pair[1].toDouble()
                    val continued = source.velocity(0)
                    aa += actual * actual; bb += continued * continued; ab += actual * continued
                }
            }
            val correlation = ab / sqrt(aa * bb)
            println("OHM4 finite freeze MIDI$midi actual free-source phase correlation $correlation")
            assertTrue(correlation > .99,
                "freeze replay resets the captured clock at MIDI$midi: correlation $correlation")
        }
    }

    @Test
    fun `strong native captured notes remain bounded after memory source conditioning`() {
        val hz = Keys.midiHz(60).toDouble()
        val source = OhmCapture(FloatArray(rate / 4) { sin(2 * PI * hz * it / rate).toFloat() }, "Strong native contact")
        for (commune in listOf(0f, 1f)) {
            val p = patch(OhmOptions(exciter = OhmExciter.CAPTURED, capture = source, grains = 1f, freeze = 1f), commune)
            val sound = p.render()
            assertTrue(sound.samples.all { it.isFinite() && abs(it) <= .990001f })
            val late = Pitch.detect(sound, 1.25f, .3f)
            assertTrue(late != null && late.confidence > .5f && abs(FineTuning.cents(late.hz.toDouble(), hz)) < 25,
                "strong captured memory lost its native root: $late")
        }
    }

    @Test
    fun `short windowed grains have no net DC force and retain smooth zero ends`() {
        val size = 3528 // A real 20 ms grain at the engine's 4x rate, about 2.6 C3 cycles.
        val window = FloatArray(size) { (.5 - .5 * cos(2 * PI * it / (size - 1))).toFloat() }
        for (phase in listOf(0.0, .7, 2.1)) {
            val source = FloatArray(size) { i ->
                val t = i.toDouble() / (size - 1)
                (.17 + .2 * sin(2 * PI * 2.6 * t + phase) + .04 * t * t).toFloat()
            }
            val centered = source.copyOf()
            OhmTextures.centerWindowedGrain(centered, window)
            val sounding = FloatArray(size) { centered[it] * window[it] }
            assertTrue(sounding.first() == 0f && sounding.last() == 0f,
                "the shaped grain must reach zero at both ends, including signed zero")
            assertTrue(rms(sounding) > .01, "centering erased the actual grain")
            assertTrue(abs(sounding.average()) / rms(sounding) < 1e-6,
                "short Hann grain injects a net DC force at phase$phase")
            val changes = FloatArray(size) { centered[it] - source[it] }
            assertTrue(changes.maxOrNull()!! - changes.minOrNull()!! < 1e-7f,
                "window centering altered the source beyond one constant offset")
        }
    }

    @Test
    fun `short grains keep the real source clock in finite and held playback while seeds choose different audio`() {
        val internalRate = rate * 4
        val hz = Keys.midiHz(48).toDouble()
        val cycles = (hz * .5).toInt()
        val loopFrames = (cycles * internalRate / hz).toInt()
        fun drive(frame: Int): Float = if (frame < internalRate / 250)
            .1f * sin(2 * PI * hz * frame / internalRate).toFloat() else 0f
        for (held in listOf(false, true)) {
            val options = OhmOptions(grains = 1f, grainSeconds = .02f)
            val helper = OhmTextures(options, 48, 731, internalRate,
                loopFrames.takeIf { held }, revision = 4)
            val otherSeed = OhmTextures(options, 48, 997, internalRate,
                loopFrames.takeIf { held }, revision = 4)
            if (held) {
                val actualSource = FloatArray(loopFrames, ::drive)
                helper.prepareLoop(actualSource)
                otherSeed.prepareLoop(actualSource)
            }
            val reference = Modes.Bank(1, internalRate).also { it.tune(0, hz, .65) }
            val participation = doubleArrayOf(2 * hz)
            val pair = FloatArray(2)
            val other = FloatArray(2)
            var signedAgreement = 0.0
            var agreementWeight = 0.0
            var changedEnergy = 0.0
            val end = if (held) loopFrames * 2 else internalRate / 2
            for (frame in 0 until end) {
                val input = drive(frame)
                reference.step()
                reference.drive(participation, input.toDouble())
                helper.fill(frame, .9f, 0f, input, pair)
                otherSeed.fill(frame, .9f, 0f, input, other)
                if (frame > internalRate * .30) {
                    val carrier = reference.velocity(0)
                    val amplitude = sqrt(2 * reference.energy())
                    if (abs(carrier) > amplitude * .5) {
                        val product = pair[1] * carrier
                        signedAgreement += product
                        agreementWeight += abs(product)
                    }
                    val difference = pair[1].toDouble() - other[1]
                    changedEnergy += difference * difference
                }
            }
            val coherence = signedAgreement / agreementWeight
            println("OHM4 20ms grains held$held source-clock polarity coherence $coherence, seeded energy difference $changedEnergy")
            assertTrue(coherence > .98, "short grains replace the source carrier with random phases")
            assertTrue(changedEnergy > 1e-8, "phase coherence replaced the real seeded source pieces with an oscillator")
        }
    }

    private fun capture(harmonic: Int): OhmCapture {
        val hz = Keys.midiHz(60).toDouble()
        return OhmCapture(FloatArray(rate / 4) { i ->
            val t = i.toDouble() / rate
            val phase = if (harmonic == 5) .7 else 0.0
            ((.5 * sin(2 * PI * hz * t + phase) + .3 * sin(2 * PI * hz * harmonic * t)) * exp(-12 * t)).toFloat()
        }, "Original memory fixture")
    }

    private fun pcm(snip: Snip): Snip = ByteArrayOutputStream().use { out ->
        WavWriter.write(out, snip, WavWriter.BitDepth.PCM_16)
        WavReader.read(out.toByteArray())
    }
    private fun scaled(snip: Snip, gain: Double) = Snip(FloatArray(snip.samples.size) { (snip.samples[it] * gain).toFloat() }, 1, rate)
    private fun peak(snip: Snip) = snip.samples.maxOf { abs(it).toDouble() }
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun relativeRms(a: FloatArray, b: FloatArray, seconds: Double, sampleRate: Int = rate): Double {
        val from = (seconds * sampleRate).toInt()
        var signal = 0.0; var delta = 0.0
        for (i in from until a.size) {
            signal += a[i].toDouble() * a[i]
            val d = b[i].toDouble() - a[i]; delta += d * d
        }
        return sqrt(delta / signal.coerceAtLeast(1e-30))
    }
    /** Remove the best constant gain fit: simple loudness changes cannot pass. */
    private fun shapeResidual(a: FloatArray, b: FloatArray, seconds: Double): Double {
        val from = (seconds * rate).toInt()
        var aa = 0.0; var ab = 0.0; var bb = 0.0
        for (i in from until a.size) { aa += a[i].toDouble() * a[i]; ab += a[i].toDouble() * b[i]; bb += b[i].toDouble() * b[i] }
        val gain = ab / aa.coerceAtLeast(1e-30)
        return sqrt((bb - 2 * gain * ab + gain * gain * aa).coerceAtLeast(0.0) / bb.coerceAtLeast(1e-30))
    }
}
