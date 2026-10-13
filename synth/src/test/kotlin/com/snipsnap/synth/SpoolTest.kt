package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Dry machine acceptance: shared output leveling cannot conceal a failed transport or write path. */
class SpoolTest {
    private val timbral = listOf("HEAD", "STRAIN", "RETURN", "IMPRINT", "SPLICE")
    private val drumClasses = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

    private fun rms(s: FloatArray, from: Int = 0, until: Int = s.size): Double {
        val end = until.coerceIn(from, s.size)
        return sqrt((from until end).sumOf { s[it].toDouble() * s[it] } / (end - from).coerceAtLeast(1))
    }

    private fun phraseRms(s: FloatArray, fromSeconds: Double, untilSeconds: Double): Double =
        rms(s, (fromSeconds * Dsp.RATE).toInt(), (untilSeconds * Dsp.RATE).toInt())

    /** Twenty-five milliseconds spans several C4 periods without hiding a perceptible phrase gap. */
    private fun phraseWindows(s: FloatArray, fromSeconds: Double, untilSeconds: Double): List<Double> {
        val width = .025
        val hop = .005
        return (0..((untilSeconds - fromSeconds - width) / hop).toInt()).map { i ->
            val start = fromSeconds + i * hop
            phraseRms(s, start, start + width)
        }
    }

    private fun longestQuietSeconds(windows: List<Double>, threshold: Double): Double {
        var run = 0
        var longest = 0
        for (energy in windows) {
            run = if (energy < threshold) run + 1 else 0
            longest = maxOf(longest, run)
        }
        return longest * .005
    }

    /** Matching RMS rejects a control that changes only output gain. */
    private fun shapeDistance(a: FloatArray, b: FloatArray, fromSeconds: Float = 0f): Double {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceAtMost(minOf(a.size, b.size))
        val end = minOf(a.size, b.size)
        val ra = rms(a, from, end).coerceAtLeast(1e-20)
        val rb = rms(b, from, end).coerceAtLeast(1e-20)
        return sqrt((from until end).sumOf {
            val d = a[it] / ra - b[it] / rb
            d * d
        } / (end - from).coerceAtLeast(1))
    }

    private data class Pitch(val hz: Double, val share: Double)

    private fun boundaryError(s: FloatArray): Double {
        val step = s.first() - s.last().toDouble()
        val slope = (s[1] - s[0]) - (s.last() - s[s.lastIndex - 1]).toDouble()
        return (step * step + slope * slope) / rms(s).let { it * it }.coerceAtLeast(1e-30)
    }

    /** Search a broad fundamental region so an out-of-tune peak cannot hide outside a narrow bin. */
    private fun forwardPitch(s: FloatArray, wanted: Float): Pitch {
        val first = (.25f * Dsp.RATE).toInt()
        val count = minOf((.70f * Dsp.RATE).toInt(), s.size - first)
        require(count > 1000)
        val n = 65536
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = s[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val binHz = Dsp.RATE.toDouble() / n
        val lo = (wanted * .65 / binHz).toInt().coerceAtLeast(2)
        val hi = (wanted * 1.4 / binHz).toInt().coerceAtMost(power.lastIndex - 1)
        val peak = (lo..hi).maxBy { power[it] }
        val left = sqrt(power[peak - 1])
        val center = sqrt(power[peak])
        val right = sqrt(power[peak + 1])
        val curvature = left - 2 * center + right
        val offset = if (abs(curvature) < 1e-20) 0.0 else (.5 * (left - right) / curvature).coerceIn(-1.0, 1.0)
        val root = ((wanted * .96 / binHz).toInt()..(wanted * 1.04 / binHz).toInt()).sumOf { power[it] }
        val total = ((40 / binHz).toInt()..(15000 / binHz).toInt()).sumOf { power[it] }
        return Pitch((peak + offset) * binHz, root / total.coerceAtLeast(1e-30))
    }

    @Test
    fun `six voices expose five distinct timbral controls plus tuning and hold`() {
        assertEquals(listOf("STRING", "CHOIR", "FLUTE", "BRASS", "PALIMPSEST", "CUT"), SpoolVoice.entries.map { it.name })
        for (voice in SpoolVoice.entries) {
            assertEquals(listOf("TUNE") + timbral + "HOLD", Spool.macrosFor(voice).map { it.name })
            assertTrue(Spool.macrosFor(voice).all { it.default.isFinite() && it.default in 0f..1f && it.neutral.isFinite() && it.neutral in 0f..1f })
            assertEquals(0f, Spool.defaults(voice).getValue("HOLD"))
        }
    }

    @Test
    fun `fresh machines reproduce source transport writes audio and exported samples`() {
        val macros = mapOf("HEAD" to .6f, "STRAIN" to .4f, "RETURN" to .8f, "IMPRINT" to .8f, "SPLICE" to .5f)
        val machine = SpoolMachine(seed = 1907, spliceSchedule = listOf(SpoolSplice(.25f, .12f), SpoolSplice(.55f, -.16f)))
        val first = Spool.renderProbe(SpoolVoice.PALIMPSEST, macros, machine, velocity = .7f)
        val second = Spool.renderProbe(SpoolVoice.PALIMPSEST, macros, machine, velocity = .7f)
        assertContentEquals(first.samples, second.samples, "dry machine is nondeterministic")
        assertEquals(first.diagnostics, second.diagnostics, "machine history is nondeterministic")
        assertContentEquals(Spool.render(SpoolVoice.PALIMPSEST, macros, machine, .7f).samples,
            Spool.render(SpoolVoice.PALIMPSEST, macros, machine, .7f).samples)
        val quiet = Spool.renderProbe(SpoolVoice.BRASS, velocity = .25f)
        val strong = Spool.renderProbe(SpoolVoice.BRASS, velocity = 1f)
        assertTrue(rms(strong.samples, until = Dsp.RATE / 5) > rms(quiet.samples, until = Dsp.RATE / 5) * 1.3,
            "velocity failed to articulate the original performance before leveling")
    }

    @Test
    fun `all voices and all high corners remain audible finite bounded and end quietly`() {
        for (voice in SpoolVoice.entries) for (macros in listOf(emptyMap(), timbral.associateWith { 1f })) {
            val played = Spool.renderProbe(voice, macros)
            val s = played.samples
            val d = played.diagnostics
            val label = "$voice $macros"
            assertTrue(s.isNotEmpty() && s.all { it.isFinite() }, "$label nonfinite output")
            assertTrue(s.size <= Dsp.RATE * 10, "$label exceeded finite host duration")
            assertTrue(s.maxOf { abs(it) } < 4f && rms(s) > 1e-5, "$label runaway or silent source")
            assertTrue(abs(s.average()) < .01, "$label DC offset")
            assertTrue(s.takeLast(Dsp.RATE / 100).maxOf { abs(it) } < 1e-3, "$label failed to terminate quietly")
            assertTrue(d.sourcePeak.isFinite() && d.overlayPeak.isFinite() && d.overlayPeak <= 1f, "$label unbounded recording")
            assertTrue(d.feedbackBound in 0f..0.550001f, "$label full write operator exceeds contraction bound")
            assertTrue(d.allocatedBytes > 0 && d.allocatedBytes < 64L * 1024 * 1024, "$label unbounded render allocation")
            assertTrue(d.spliceCount in 0..4, "$label exceeded four splice boundaries")
            assertTrue(d.trace.all { frame ->
                frame.motorRate.isFinite() && frame.positions.all { it.isFinite() && it >= 0f } &&
                    frame.rates.all { it.isFinite() } && frame.headCoordinates.all { it.isFinite() && it >= 0f && it <= 3.001f } &&
                    frame.springEnergy.isFinite() && frame.springEnergy >= 0f && frame.motorWork.isFinite() && frame.motorWork >= 0f &&
                    frame.frictionLoss.isFinite() && frame.frictionLoss >= 0f
            }, "$label transport state left its valid support")
        }
    }

    @Test
    fun `main forward material retains C3 C4 and C5 separately from its slowing reverse answer`() {
        for (voice in SpoolVoice.entries) for (midi in listOf(48, 60, 72)) {
            val macros = mapOf("TUNE" to (midi - 48) / 24f, "HEAD" to .2f, "STRAIN" to .1f, "SPLICE" to 0f)
            val s = Spool.renderProbe(voice, macros).samples
            val wanted = Keys.midiHz(midi)
            val measured = forwardPitch(s, wanted)
            val cents = 1200 * ln(measured.hz / wanted) / ln(2.0)
            assertTrue(abs(cents) <= 10.0, "$voice MIDI $midi forward root ${measured.hz}Hz ($cents cents)")
            assertTrue(measured.share > .03, "$voice MIDI $midi root has only ${measured.share} of audible power")
        }
    }

    @Test
    fun `minimum gate and extreme head geometry stay inside the actual return support`() {
        val machine = SpoolMachine(sourceSeconds = 2f, gateSeconds = .15f,
            headOffsets = listOf(-.6f, .6f, .6f), spliceSchedule = emptyList())
        val p = Spool.renderProbe(SpoolVoice.CUT, timbral.associateWith { 1f }, machine)
        assertTrue(p.samples.all { it.isFinite() } && rms(p.samples) > 1e-5)
        assertEquals(0, p.diagnostics.spliceCount)
        val d = p.diagnostics
        assertTrue(d.maxForwardRate <= Spool.MAX_FORWARD_RATE + .0001f)
        assertTrue(d.maxReverseRate <= Spool.MAX_REVERSE_RATE + .0001f)
        val returning = d.trace.filter { it.timeSeconds in d.gateSeconds..d.returnEndSeconds }
        assertTrue(returning.all { frame -> frame.headCoordinates.indices.all { k ->
            frame.headCoordinates[k] in 0f..(d.returnTravel[k] + .0001f)
        } }, "a short gate and extreme head offset revealed future material")
        assertTrue(p.samples.takeLast(Dsp.RATE / 100).maxOf { abs(it) } < 1e-3)
    }

    @Test
    fun `return reverses the portion travelled and longer gates produce broader answers`() {
        val macros = mapOf("RETURN" to .9f, "HEAD" to 0f, "STRAIN" to .2f, "IMPRINT" to 0f, "SPLICE" to 0f)
        val short = Spool.renderProbe(SpoolVoice.STRING, macros, SpoolMachine(gateSeconds = .45f))
        val long = Spool.renderProbe(SpoolVoice.STRING, macros, SpoolMachine(gateSeconds = 1.7f))
        assertTrue(long.diagnostics.returnTravel.average() > short.diagnostics.returnTravel.average() * 2.5,
            "reverse answer does not depend on actual forward travel")
        for (played in listOf(short, long)) {
            val d = played.diagnostics
            val atGate = d.trace.minBy { abs(it.timeSeconds - d.gateSeconds) }
            assertTrue(d.returnTravel.indices.all { abs(d.returnTravel[it] - atGate.positions[it]) < .025f },
                "return displacement was invented instead of inherited from the tape")
            val returning = d.trace.filter { it.timeSeconds > d.gateSeconds + .02f && it.timeSeconds < d.returnEndSeconds - .02f }
            assertTrue(returning.isNotEmpty() && returning.any { frame -> frame.rates.any { it < -.01f } }, "no actual reverse coordinate motion")
            assertTrue(returning.all { frame -> frame.rates.all { it <= .001f } }, "return head replayed material forward")
            assertTrue(returning.all { frame -> frame.headCoordinates.indices.all { frame.headCoordinates[it] <= d.returnTravel[it] + .001f } },
                "return head revealed future recording material")
            val early = returning.take(returning.size / 3).sumOf { frame -> frame.rates.sumOf { abs(it).toDouble() } }
            val late = returning.takeLast(returning.size / 3).sumOf { frame -> frame.rates.sumOf { abs(it).toDouble() } }
            assertTrue(early > late * 1.3, "reverse contour did not decelerate toward its rest")
        }
        val muted = Spool.renderProbe(SpoolVoice.STRING, macros + ("RETURN" to 0f), writes = false)
        val audible = Spool.renderProbe(SpoolVoice.STRING, macros, writes = false)
        val from = ((audible.diagnostics.gateSeconds + .1f) * Dsp.RATE).toInt()
        val until = ((audible.diagnostics.returnEndSeconds - .05f) * Dsp.RATE).toInt()
        assertTrue(requireNotNull(muted.reverseStem).all { it == 0f }, "RETURN=0 left a backward pickup")
        assertTrue(rms(requireNotNull(audible.reverseStem), from, until) > 1e-4,
            "RETURN did not reveal the independent backward journey")
    }

    @Test
    fun `clean forward and backward pickups overlap substantially and agree on the requested note`() {
        for (voice in listOf(SpoolVoice.STRING, SpoolVoice.CHOIR, SpoolVoice.FLUTE, SpoolVoice.BRASS)) {
            for (midi in listOf(48, 60, 72)) {
                val macros = mapOf("TUNE" to (midi - 48) / 24f, "HEAD" to .2f, "STRAIN" to .1f,
                    "SPLICE" to 0f, "IMPRINT" to .15f, "RETURN" to .65f)
                val p = Spool.renderProbe(voice, macros)
                val forward = requireNotNull(p.forwardStem)
                val reverse = requireNotNull(p.reverseStem)
                assertEquals(p.samples.size, forward.size)
                assertEquals(p.samples.size, reverse.size)
                val d = p.diagnostics
                val overlap = SpoolListeningMeasure.overlap(forward, reverse, d.gateSeconds.toDouble(), d.returnEndSeconds.toDouble())
                val label = "$voice MIDI $midi"
                assertTrue(overlap.preGateSeconds >= .4, "$label lacked audible concurrent pickups before release: $overlap")
                assertTrue(overlap.postGateFraction >= .6, "$label handed off instead of layering across the return: $overlap")
                assertTrue(overlap.totalSeconds >= 1.0 && overlap.reverseToForwardRms >= .10,
                    "$label backward pickup was too brief or hidden: $overlap")
                val wanted = Keys.midiHz(midi).toDouble()
                val f = SpoolListeningMeasure.tone(forward, overlap.fromSeconds, overlap.untilSeconds, wanted)
                val r = SpoolListeningMeasure.tone(reverse, overlap.fromSeconds, overlap.untilSeconds, wanted)
                assertTrue(abs(f.cents) <= 15 && abs(r.cents) <= 15 && abs(f.cents - r.cents) <= 20,
                    "$label independent layers disagreed on pitch: $f / $r")
                assertTrue(f.harmonicPowerShare >= .85 && r.harmonicPowerShare >= .85,
                    "$label layers acquired substantial inharmonic energy: $f / $r")
                val mixed = FloatArray(forward.size) { forward[it] + reverse[it] }
                val tone = SpoolListeningMeasure.tone(mixed, overlap.fromSeconds, overlap.untilSeconds, wanted)
                val detuned = SpoolListeningMeasure.semitoneReference(reverse, overlap.fromSeconds, overlap.untilSeconds)
                val first = (overlap.fromSeconds * Dsp.RATE).toInt()
                val reference = FloatArray(detuned.size) { forward[first + it] + detuned[it] }
                val rough = SpoolListeningMeasure.tone(reference, 0.0, reference.size.toDouble() / Dsp.RATE, wanted)
                assertTrue(tone.roughness <= rough.roughness * .80,
                    "$label overlap was as rough as a semitone-detuned layer: $tone / $rough")
                assertEquals(0f, d.reengageSeconds, "$label restarted an attacked third pass")
            }
        }
    }

    @Test
    fun `clean voices retain distinct harmonic envelopes in both pickups and their audible overlap`() {
        val voices = listOf(SpoolVoice.STRING, SpoolVoice.CHOIR, SpoolVoice.FLUTE, SpoolVoice.BRASS)
        for (midi in listOf(48, 60, 72)) {
            val macros = mapOf("TUNE" to (midi - 48) / 24f, "HEAD" to .2f, "STRAIN" to .1f,
                "RETURN" to .65f, "IMPRINT" to 0f, "SPLICE" to 0f)
            val profiles = mutableMapOf<String, MutableMap<SpoolVoice, SpoolTimbreListeningMeasure.Profile>>()
            for (voice in voices) {
                val p = Spool.renderProbe(voice, macros, writes = false)
                for ((stage, audio) in listOf("mixed" to p.samples,
                    "forward" to requireNotNull(p.forwardStem), "reverse" to requireNotNull(p.reverseStem))) {
                    // Both actual pickups sound throughout this pre-gate span. Identical settings,
                    // note and energy normalization prevent macros, gain or timing disguising a
                    // common source spectrum beneath the four advertised recording families.
                    profiles.getOrPut(stage) { mutableMapOf() }[voice] =
                        SpoolTimbreListeningMeasure.profile(audio, Keys.midiHz(midi).toDouble(), .85, 1.25)
                }
            }
            for ((stage, byVoice) in profiles) {
                for (i in voices.indices) for (j in i + 1 until voices.size) {
                    val a = voices[i]; val b = voices[j]
                    val distance = byVoice.getValue(a).distance(byVoice.getValue(b))
                    assertTrue(distance >= .15,
                        "MIDI $midi $stage $a/$b collapsed into similar normalized harmonic envelopes: $distance")
                }
                if (midi == 60) {
                    val flute = byVoice.getValue(SpoolVoice.FLUTE)
                    val string = byVoice.getValue(SpoolVoice.STRING)
                    val brass = byVoice.getValue(SpoolVoice.BRASS)
                    val choir = byVoice.getValue(SpoolVoice.CHOIR)
                    assertTrue(flute.rootShare >= .70,
                        "$stage FLUTE lost the root-dominant airy recording: $flute")
                    assertTrue(string.rootShare < .80 && string.oddUpperShare >= .035 && string.evenShare >= .06,
                        "$stage STRING lost audible bowed odd/even harmonic body: $string")
                    assertTrue(brass.upperShare >= .10 && brass.centroidHarmonic >= string.centroidHarmonic * 1.12,
                        "$stage BRASS did not retain a substantially brighter upper-partial body: $brass / $string")
                    assertTrue(choir.rootShare < .75 && choir.share(500.0, 2200.0) >= .30,
                        "$stage CHOIR reduced its vowel formants to a faint tint of the root: $choir")
                }
            }
        }
    }

    @Test
    fun `choir vowels retain absolute frequency formants when the note moves an octave`() {
        val profiles = mutableMapOf<String, MutableList<SpoolTimbreListeningMeasure.Profile>>()
        for (midi in listOf(48, 60)) {
            val macros = mapOf("TUNE" to (midi - 48) / 24f, "HEAD" to .2f, "STRAIN" to .1f,
                "RETURN" to .65f, "IMPRINT" to 0f, "SPLICE" to 0f)
            val p = Spool.renderProbe(SpoolVoice.CHOIR, macros, writes = false)
            for ((stage, audio) in listOf("mixed" to p.samples,
                "forward" to requireNotNull(p.forwardStem), "reverse" to requireNotNull(p.reverseStem))) {
                profiles.getOrPut(stage) { mutableListOf() } +=
                    SpoolTimbreListeningMeasure.profile(audio, Keys.midiHz(midi).toDouble(), .85, 1.25)
            }
        }
        for ((stage, pair) in profiles) for ((low, high) in listOf(500.0 to 1100.0, 1100.0 to 2200.0)) {
            for (p in pair) assertTrue(p.share(low, high) >= .07,
                "$stage CHOIR ${p.wantedHz}Hz lost an audible vowel band at $low..$high Hz: $p")
            val a = pair[0].strongestPartialHz(low, high)
            val b = pair[1].strongestPartialHz(low, high)
            assertTrue(maxOf(a, b) / minOf(a, b) <= 1.4,
                "$stage CHOIR transposed its vowel resonance with the note: $a -> $b Hz")
        }
    }

    @Test
    fun `reverse grain footprints stay inside already captured recording support`() {
        for (gate in listOf(.15f, 1.35f, 2.4f)) {
            val machine = SpoolMachine(gateSeconds = gate, sourceSeconds = 3f,
                headOffsets = listOf(-.6f, .6f, .6f))
            val p = Spool.renderProbe(SpoolVoice.STRING,
                mapOf("HEAD" to 1f, "RETURN" to 1f, "STRAIN" to .1f, "SPLICE" to 1f), machine)
            val d = p.diagnostics
            for (frame in d.pickupTrace) for (k in 0..2) {
                val support = frame.reverseSupports[k]
                assertTrue(frame.reverseMinCoordinates[k] >= -1e-6f && frame.reverseMaxCoordinates[k] <= support + 1e-6f,
                    "reverse grain/kernel exceeded captured support: gate=$gate $frame")
                assertTrue(support <= d.returnTravel[k] + .001f,
                    "reverse pickup revealed future tape: gate=$gate $frame")
                // A newly launched Hann grain still has a kernel footprint at zero weight.
                // Its documented read rate is zero until it contributes actual pickup audio.
                if (frame.reverseReadRates[k] != 0f)
                    assertEquals(-frame.tapeRate, frame.reverseReadRates[k], "clean reverse grain did not follow the backward tape clock")
                if (frame.forwardReadRates[k] != 0f)
                    assertEquals(frame.tapeRate, frame.forwardReadRates[k], "clean forward grain did not follow the forward tape clock")
            }
            assertTrue(d.pickupTrace.any { frame -> frame.forwardReadRates.indices.any { k ->
                frame.forwardReadRates[k] > 0f && frame.forwardGains[k] > 0f
            } }, "no forward pickup actually activated: gate=$gate")
            assertTrue(d.pickupTrace.any { frame -> frame.reverseReadRates.indices.any { k ->
                frame.reverseReadRates[k] < 0f && frame.reverseGains[k] > 0f
            } }, "no backward pickup actually activated: gate=$gate")
        }
    }

    @Test
    fun `clean pickup audio has shared gentle wow and audible faster flutter`() {
        val machine = singleStripMachine()
        for (midi in listOf(48, 60, 72)) {
            val macros = mapOf("TUNE" to (midi - 48) / 24f, "HEAD" to 0f, "STRAIN" to 0f,
                "SPLICE" to 0f, "IMPRINT" to 0f, "RETURN" to .65f)
            val p = Spool.renderProbe(SpoolVoice.STRING, macros, machine, writes = false)
            val from = p.diagnostics.gateSeconds * .58
            val until = p.diagnostics.returnEndSeconds - .20
            val wanted = Keys.midiHz(midi).toDouble()
            val f = SpoolTapeListeningMeasure.pitchMotion(requireNotNull(p.forwardStem), wanted, from, until)
            val r = SpoolTapeListeningMeasure.pitchMotion(requireNotNull(p.reverseStem), wanted, from, until)
            for ((name, motion) in listOf("forward" to f, "reverse" to r)) {
                val label = "MIDI $midi $name $motion"
                assertTrue(abs(motion.meanCents) < 6, "$label shifted the clean note's center")
                assertTrue(motion.motionRmsCents in 2.0..7.0 && motion.maximumCents < 15,
                    "$label omitted gentle tape motion or bent the clean note excessively")
                assertTrue(motion.fastRmsCents >= .60,
                    "$label moved only slowly, without audible faster flutter")
            }
            val disagreement = sqrt(f.cents.indices.sumOf { i ->
                val difference = f.cents[i] - r.cents[i]
                difference * difference
            } / f.cents.size)
            assertTrue(disagreement < 2.0,
                "MIDI $midi pickups fluttered independently into beating: $disagreement cents RMS")
        }
    }

    @Test
    fun `backward pickup reverses a recorded decay into an audible brightening swell`() {
        // Strip isolation removes changing companion mix and imprint as explanations for the
        // gesture. Both measured reverse windows follow the pickup's contact fade-in.
        val macros = mapOf("TUNE" to .5f, "HEAD" to 0f, "STRAIN" to 0f, "SPLICE" to 0f,
            "IMPRINT" to 0f, "RETURN" to .65f)
        val p = Spool.renderProbe(SpoolVoice.STRING, macros, singleStripMachine(), writes = false)
        val f = requireNotNull(p.forwardStem)
        val r = requireNotNull(p.reverseStem)
        val forwardAttack = phraseRms(f, .16, .28)
        val forwardLater = phraseRms(f, 1.10, 1.24)
        val reverseEarlier = phraseRms(r, .76, .90)
        val reverseLater = phraseRms(r, 1.12, 1.26)
        assertTrue(forwardLater < forwardAttack * .90,
            "original performance did not contain a recognizable decay: $forwardAttack -> $forwardLater")
        assertTrue(reverseLater > reverseEarlier * 1.15,
            "backward pickup did not turn recorded decay into a swell: $reverseEarlier -> $reverseLater")
        val wanted = Keys.midiHz(60).toDouble()
        val forwardBrightness = SpoolTapeListeningMeasure.brightness(f, wanted, 1.10, 1.24) /
            SpoolTapeListeningMeasure.brightness(f, wanted, .16, .28)
        val reverseBrightness = SpoolTapeListeningMeasure.brightness(r, wanted, 1.12, 1.26) /
            SpoolTapeListeningMeasure.brightness(r, wanted, .76, .90)
        assertTrue(forwardBrightness < .98 && reverseBrightness > 1.04,
            "pickup reversal changed only loudness rather than reversing articulation: $forwardBrightness / $reverseBrightness")
    }

    @Test
    fun `reverse preview joins physical return without a gate gap or abrupt level reset`() {
        val cases = listOf(SpoolVoice.STRING, SpoolVoice.CHOIR, SpoolVoice.FLUTE, SpoolVoice.BRASS)
            .map { it to 60 } + listOf(SpoolVoice.CHOIR to 48, SpoolVoice.CHOIR to 72)
        for ((voice, midi) in cases) {
            val macros = mapOf("TUNE" to (midi - 48) / 24f, "HEAD" to .2f, "STRAIN" to .1f,
                "SPLICE" to 0f, "IMPRINT" to 0f, "RETURN" to .65f)
            val p = Spool.renderProbe(voice, macros, writes = false)
            val gate = p.diagnostics.gateSeconds.toDouble()
            val mixed = phraseWindows(p.samples, gate - .12, gate + .12)
            val forward = phraseWindows(requireNotNull(p.forwardStem), gate - .12, gate + .12)
            val reverse = phraseWindows(requireNotNull(p.reverseStem), gate - .12, gate + .12)
            val label = "$voice MIDI $midi"
            assertTrue(mixed.min() > mixed.max() * .5,
                "$label dropped more than 6dB around the preview-to-return handoff")
            val largestStepDb = mixed.zipWithNext().maxOf { (a, b) -> abs(20 * ln(b / a) / ln(10.0)) }
            assertTrue(largestStepDb < 3,
                "$label reset its level abruptly at the gate: $largestStepDb dB per 5ms")
            assertTrue(reverse.indices.all { i -> reverse[i] > forward[i] * .10 },
                "$label backward pickup disappeared during the handoff")
        }
    }

    private fun singleStripMachine(): SpoolMachine = SpoolMachine(
        roster = List(3) { i -> SpoolStrip(SpoolSourceFamily.STRING, gain = if (i == 0) 1f else 0f, doubling = 0f) },
        headOffsets = listOf(0f, 0f, 0f), spliceSchedule = emptyList(),
    )

    @Test
    fun `held clean phrases remain connected across the actual repeat boundary`() {
        for (voice in listOf(SpoolVoice.STRING, SpoolVoice.CHOIR, SpoolVoice.FLUTE, SpoolVoice.BRASS)) {
            for (answer in listOf(.2f, .65f)) {
                val macros = mapOf("HEAD" to .2f, "STRAIN" to .1f, "SPLICE" to 0f,
                    "IMPRINT" to 0f, "RETURN" to answer, "HOLD" to 1f)
                val loop = Spool.renderLoop(voice, macros)
                val peakWindow = phraseWindows(loop, 0.0, loop.size.toDouble() / Dsp.RATE).max()
                assertTrue(peakWindow > 1e-4, "$voice RETURN=$answer held phrase was silent")
                // Listen through the exported cycle's real last-to-first boundary, including
                // the next recording's attack. A numerically perfect seam may still join silence.
                val wrapped = loop.copyOfRange(loop.size - Dsp.RATE / 2, loop.size) +
                    loop.copyOfRange(0, Dsp.RATE * 3 / 10)
                val windows = phraseWindows(wrapped, 0.0, wrapped.size.toDouble() / Dsp.RATE)
                val quietSeconds = longestQuietSeconds(windows, peakWindow * .03)
                assertTrue(quietSeconds <= .15,
                    "$voice RETURN=$answer HOLD repeat contained ${quietSeconds}s below 3% of peak phrase RMS")
            }
        }
    }

    @Test
    fun `return approaches mute continuously without a fixed audible turnaround at tiny settings`() {
        val machine = SpoolMachine(reengage = false)
        val macros = mapOf("HEAD" to .2f, "STRAIN" to .1f, "SPLICE" to 0f, "IMPRINT" to 0f)
        for (voice in listOf(SpoolVoice.STRING, SpoolVoice.CHOIR, SpoolVoice.FLUTE, SpoolVoice.BRASS)) {
            val muted = Spool.renderProbe(voice, macros + ("RETURN" to 0f), machine, writes = false)
            val tiny = Spool.renderProbe(voice, macros + ("RETURN" to .001f), machine, writes = false)
            val quiet = Spool.renderProbe(voice, macros + ("RETURN" to .01f), machine, writes = false)
            val gate = muted.diagnostics.gateSeconds.toDouble()
            val from = (gate * Dsp.RATE).toInt()
            val until = (muted.diagnostics.returnEndSeconds * Dsp.RATE).toInt()
            val body = phraseRms(muted.samples, gate - .30, gate - .10)
            fun distanceFromMute(s: FloatArray): Double = sqrt((from until until).sumOf { i ->
                val difference = s[i] - muted.samples[i].toDouble()
                difference * difference
            } / (until - from))
            // Include the complete audible turnaround, while the muted reference permits its
            // ordinary contact/filter tail. A fixed retrigger at every positive value fails.
            val tinyDistance = distanceFromMute(tiny.samples)
            val quietDistance = distanceFromMute(quiet.samples)
            assertTrue(quietDistance > body * 1e-5, "$voice near-mute probe did not reveal its return")
            assertTrue(tinyDistance <= quietDistance * .25,
                "$voice RETURN .001 did not approach mute from .01: $quietDistance -> $tinyDistance")
            assertTrue(rms(tiny.samples, from, until) <= maxOf(rms(muted.samples, from, until) * 1.5, body * .005),
                "$voice tiny RETURN left an audible fixed turnaround")
        }
    }

    @Test
    fun `imprint is causal bounded and heard in one finite reread with originals intact`() {
        val macros = mapOf("RETURN" to .8f, "IMPRINT" to 1f, "HEAD" to .55f, "SPLICE" to .2f)
        val written = Spool.play(SpoolVoice.PALIMPSEST, macros, record = true)
        val clean = Spool.play(SpoolVoice.PALIMPSEST, macros, writes = false, record = true)
        val d = written.diagnostics
        assertEquals(clean.diagnostics.sourceHashes, d.sourceHashes, "imprint changed immutable first-generation performances")
        assertTrue(d.writeCount > 0 && d.overlayPeak > 1e-5f, "no local recording was written")
        assertEquals(0L, clean.diagnostics.writeCount)
        val firstWrite = requireNotNull(d.firstWriteSeconds)
        val firstRead = requireNotNull(d.firstOverlayReadSeconds)
        assertTrue(firstRead > firstWrite, "current output read its own just-written sample")
        assertTrue(firstRead < d.durationSeconds, "inherited material became available after the render ended")
        val before = ((firstWrite - .002f).coerceAtLeast(0f) * Dsp.RATE * Dsp.OVERSAMPLE).toInt()
        assertContentEquals(clean.raw.copyOf(before), written.raw.copyOf(before), "writing changed the first encounter before causal exposure")
        assertTrue(shapeDistance(Spool.finish(written.raw, normalize = false), Spool.finish(clean.raw, normalize = false), firstRead) > .005,
            "second encounter failed to hear the inherited trace")
        val zero = Spool.renderProbe(SpoolVoice.PALIMPSEST, macros + ("IMPRINT" to 0f))
        assertEquals(0L, zero.diagnostics.writeCount)
        assertEquals(0f, zero.diagnostics.overlayPeak)
    }

    @Test
    fun `each positive imprint step changes the inherited reread beyond output gain`() {
        val voice = SpoolVoice.CHOIR
        val machine = SpoolMachine.forVoice(voice).let { defaults ->
            defaults.copy(
                roster = defaults.roster.mapIndexed { i, strip -> strip.copy(gain = if (i == 0) 1f else 0f) },
                routes = listOf(SpoolRoute(2, 0, .55f)),
                reengage = true,
            )
        }
        val macros = mapOf("HEAD" to 0f, "STRAIN" to .1f, "RETURN" to 1f, "SPLICE" to 0f, "HOLD" to 0f)
        val levels = listOf(0f, .25f, .5f, .75f, 1f)
        val performances = levels.map { level ->
            Spool.play(voice, macros + ("IMPRINT" to level), machine, record = true)
        }
        val dry = performances.first()
        val beforeWrite = (dry.diagnostics.gateSeconds * Dsp.RATE * Dsp.OVERSAMPLE).toInt()
        for ((level, played) in levels.zip(performances)) {
            val d = played.diagnostics
            assertEquals(dry.diagnostics.sourceHashes, d.sourceHashes, "IMPRINT $level changed its source recordings")
            assertContentEquals(dry.raw.copyOf(beforeWrite), played.raw.copyOf(beforeWrite),
                "IMPRINT $level changed the first encounter before recording began")
            assertEquals(dry.diagnostics.trace, d.trace, "IMPRINT $level changed the transport")
            assertEquals(dry.diagnostics.returnEndSeconds, d.returnEndSeconds)
            assertEquals(dry.diagnostics.reengageSeconds, d.reengageSeconds)
            assertEquals(dry.raw.size, played.raw.size)
        }
        assertTrue(dry.diagnostics.reengageSeconds > .8f, "the finite inherited reread is missing")
        val from = (dry.diagnostics.returnEndSeconds * Dsp.RATE).toInt()
        val until = ((dry.diagnostics.returnEndSeconds + dry.diagnostics.reengageSeconds) * Dsp.RATE).toInt()
        val rereads = performances.map { Spool.finish(it.raw, normalize = false).copyOfRange(from, until) }
        var previousInheritedRms = 0.0
        for ((level, read) in levels.drop(1).zip(rereads.drop(1))) {
            val inherited = FloatArray(read.size) { read[it] - rereads.first()[it] }
            val inheritedRms = rms(inherited)
            assertTrue(inheritedRms > previousInheritedRms, "IMPRINT $level failed to strengthen inherited material")
            previousInheritedRms = inheritedRms
        }
        // Testing only silence versus maximum misses a control whose positive values all sound
        // alike. Remove the best scalar gain before comparing every adjacent positive reread.
        for (i in 1 until rereads.lastIndex) {
            val a = rereads[i]
            val b = rereads[i + 1]
            val gain = a.indices.sumOf { a[it].toDouble() * b[it] } /
                a.indices.sumOf { a[it].toDouble() * a[it] }.coerceAtLeast(1e-30)
            val residual = sqrt(a.indices.sumOf {
                val difference = b[it] - gain * a[it]
                difference * difference
            } / a.size) / rms(b).coerceAtLeast(1e-20)
            assertTrue(residual >= .07, "IMPRINT ${levels[i]} to ${levels[i + 1]} changed only gain or a masked trace: $residual")
        }
    }

    @Test
    fun `every timbral macro changes normal string shape and diagonal heads differ from frozen pickup`() {
        val baseline = mapOf("HEAD" to .25f, "STRAIN" to .2f, "RETURN" to .6f, "IMPRINT" to .4f, "SPLICE" to .2f)
        for (macro in timbral) {
            val low = Spool.renderProbe(SpoolVoice.STRING, baseline + (macro to 0f))
            val high = Spool.renderProbe(SpoolVoice.STRING, baseline + (macro to 1f))
            assertTrue(shapeDistance(low.samples, high.samples) > .01, "$macro is only gain or has no finite audible encounter")
        }
        val moving = Spool.renderProbe(SpoolVoice.STRING, baseline + ("HEAD" to .9f))
        val frozen = Spool.renderProbe(SpoolVoice.STRING, baseline + ("HEAD" to .9f), frozenHead = true)
        assertEquals(moving.diagnostics.sourceHashes, frozen.diagnostics.sourceHashes)
        val toneChange = shapeDistance(moving.samples, frozen.samples)
        val movingHeads = moving.diagnostics.pickupTrace
        val frozenHeads = frozen.diagnostics.pickupTrace
        val travelChange = movingHeads.indices.sumOf { i -> movingHeads[i].forwardCoordinates.indices.sumOf { k ->
            abs(movingHeads[i].forwardCoordinates[k] - frozenHeads[i].forwardCoordinates[k]).toDouble()
        } } / (movingHeads.size * 3).coerceAtLeast(1)
        // Pitch preservation deliberately removes the large phase distance of detuned heads.
        // HEAD must still select different recorded material and produce an audible tone change.
        assertTrue(toneChange > .01 && travelChange > .001,
            "HEAD failed to change tone and recorded-time pickup: $toneChange / $travelChange")
        assertEquals(moving.diagnostics.returnTravel, frozen.diagnostics.returnTravel, "freezing the head changed the transport")
    }

    @Test
    fun `one companion load changes the shared motor and the unchanged dominant strip`() {
        val machine = SpoolMachine.forVoice(SpoolVoice.STRING).copy(motorTorque = .4f)
        val heavier = machine.copy(roster = machine.roster.mapIndexed { i, strip ->
            if (i == 2) strip.copy(tension = 2f, drag = 1f) else strip
        })
        val macros = mapOf("STRAIN" to .8f, "HEAD" to .2f, "IMPRINT" to 0f)
        val base = Spool.renderProbe(SpoolVoice.STRING, macros, machine)
        val loaded = Spool.renderProbe(SpoolVoice.STRING, macros, heavier)
        assertEquals(base.diagnostics.sourceHashes, loaded.diagnostics.sourceHashes, "loading changed the original source performances")
        val a = base.diagnostics.trace.filter { it.timeSeconds < machine.gateSeconds }
        val b = loaded.diagnostics.trace.filter { it.timeSeconds < machine.gateSeconds }
        assertEquals(a.size, b.size)
        val motorDifference = a.indices.sumOf { abs(a[it].motorRate - b[it].motorRate).toDouble() } / a.size
        val dominantDifference = a.indices.sumOf { abs(a[it].rates[0] - b[it].rates[0]).toDouble() } / a.size
        assertTrue(motorDifference > 1e-4, "companion load never affected shared powered drive: $motorDifference")
        assertTrue(dominantDifference > 1e-4, "dominant strip only heard its independent slip gesture: $dominantDifference")
    }

    @Test
    fun `a genuine delayed write pass contracts the full tape including cells the write head never visits`() {
        val macros = timbral.associateWith { 1f }
        for (voice in listOf(SpoolVoice.PALIMPSEST, SpoolVoice.CUT)) {
            val p = Spool.imprintContractionProbe(voice, macros, SpoolMachine(gateSeconds = .6f, sourceSeconds = 2f), delta = .01f)
            assertTrue(p.initialDistance > .005f, "$voice contraction probe omitted its seeded perturbation")
            assertTrue(p.finalDistance <= p.initialDistance * .4001f,
                "$voice complete overlay operator failed its .40 infinity-norm bound: ${p.initialDistance} -> ${p.finalDistance}")
            assertTrue(p.unvisitedDistance <= p.initialDistance * .4001f,
                "$voice untouched cells retained unity-gain feedback: ${p.unvisitedDistance}")
        }
    }

    @Test
    fun `broadband inherited traces are filtered before fast forward and reverse coordinate reads`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val high = FloatArray(rate / 3) { (.2 * sin(2 * PI * 60_000 * it / rate)).toFloat() }
        val low = FloatArray(rate / 3) { (.2 * sin(2 * PI * 1_000 * it / rate)).toFloat() }
        for (speed in listOf(3.2f, -3.2f)) {
            fun rendered(input: FloatArray, filtered: Boolean) =
                Spool.finish(Spool.resampleOverlayProbe(input, speed, filtered), normalize = false)
            val unfiltered = rendered(high, false)
            val filtered = rendered(high, true)
            val margin = Dsp.RATE / 100
            val alias = rms(unfiltered, margin, unfiltered.size - margin)
            val safe = rms(filtered, margin, filtered.size - margin)
            assertTrue(alias > .01, "alias probe did not place an audible folded component: $speed $alias")
            assertTrue(safe < alias * .02, "inherited broadband material folded into the audible band: $speed $alias -> $safe")
            val lowPlain = rendered(low, false)
            val lowFiltered = rendered(low, true)
            assertTrue(rms(lowFiltered, margin, lowFiltered.size - margin) > rms(lowPlain, margin, lowPlain.size - margin) * .8,
                "overlay anti-alias path suppressed its useful low recorded tone")
        }
    }

    @Test
    fun `frozen snapshot hold remains deterministic audible and seamless at difficult corners`() {
        for (voice in SpoolVoice.entries) {
            val macros = Spool.defaults(voice) + ("HOLD" to 1f) + ("IMPRINT" to .85f) + ("RETURN" to .8f)
            val probe = Spool.renderProbe(voice, macros, normalize = true)
            val loop = Spool.renderLoop(voice, macros)
            assertContentEquals(probe.samples, loop, "$voice measured and exported loops diverge")
            assertTrue(loop.all { it.isFinite() } && rms(loop) > 1e-5, "$voice silent/nonfinite HOLD")
            assertTrue(probe.diagnostics.writesFrozen, "$voice loop metadata does not disclose frozen writes")
            assertTrue(probe.diagnostics.writeCount > 0, "$voice IMPRINT failed to prepare the held snapshot")
            val previous = requireNotNull(probe.previousCycle)
            val seam = Keys.seamError(previous + loop, previous.size)
            assertTrue(seam < 1e-3 && requireNotNull(probe.seamError) < 1e-3, "$voice HOLD seam $seam")
            assertTrue(boundaryError(loop) < 1e-3, "$voice HOLD boundary step/slope ${boundaryError(loop)}")
        }
        val extreme = Spool.renderLoop(SpoolVoice.CUT, timbral.associateWith { 1f } + ("HOLD" to 1f))
        assertTrue(extreme.all { it.isFinite() } && boundaryError(extreme) < 1e-3, "all-high CUT held loop failed")
    }

    @Test
    fun `complete recipes roundtrip routing timing velocity and exact sound through the shared dispatcher`() {
        val machine = SpoolMachine.forVoice(SpoolVoice.PALIMPSEST).copy(sourceSeconds = 2.7f, gateSeconds = .8f, seed = 411,
            routes = listOf(SpoolRoute(0, 2, .3f), SpoolRoute(2, 1, .4f), SpoolRoute(1, 0, .25f)),
            headOffsets = listOf(-.08f, .18f, .02f), spliceSchedule = listOf(SpoolSplice(.3f, .12f)))
        val patch = SpoolPatch("Saved Spool", SpoolVoice.PALIMPSEST, Spool.defaults(SpoolVoice.PALIMPSEST), machine, .6f)
        val restored = Patches.fromJsonText(patch.toJsonText()) as SpoolPatch
        assertEquals(patch, restored)
        assertEquals(patch.toJsonText(), restored.toJsonText())
        assertContentEquals(patch.render().samples, restored.render().samples)
        val changed = patch.macros + ("HEAD" to .75f)
        assertEquals(patch.copy(name = "Edited Spool", macros = changed), Patches.edited(patch, "Edited Spool", changed))
        assertFailsWith<IllegalArgumentException> { patch.copy(velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("IMPRINT" to 1.1f)) }
        val wrongVoice = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { it["voice"] = JsonValue.Str("KICK") })
        assertFailsWith<JsonException> { Patches.fromJsonValue(wrongVoice) }
        assertTrue(SpoolPresets.all().isNotEmpty())
        assertTrue(Presets.all().containsAll(SpoolPresets.all()))
    }

    @Test
    fun `machine validation refuses instantaneous feedback excessive routing and ambiguous splice geometry`() {
        assertFailsWith<IllegalArgumentException> { SpoolMachine(writeDelaySamples = 0) }
        assertFailsWith<IllegalArgumentException> {
            SpoolMachine(routes = listOf(SpoolRoute(0, 1, .4f), SpoolRoute(2, 1, .4f)))
        }
        assertFailsWith<IllegalArgumentException> { SpoolRoute(1, 1) }
        assertFailsWith<IllegalArgumentException> { SpoolMachine(sourceSeconds = 2f, gateSeconds = 2f) }
        assertFailsWith<IllegalArgumentException> {
            SpoolMachine(spliceSchedule = listOf(SpoolSplice(.3f, .1f), SpoolSplice(.32f, -.1f)))
        }
        assertFailsWith<IllegalArgumentException> { SpoolMachine(roster = listOf(SpoolStrip(SpoolSourceFamily.STRING))) }
    }

    @Test
    fun `standard pitched exports avoid drum classification and held routing remains loop`() {
        for (voice in SpoolVoice.entries) {
            val macros = mapOf("HEAD" to .2f, "STRAIN" to .1f, "RETURN" to .25f, "IMPRINT" to .2f, "SPLICE" to 0f)
            val snip = Spool.render(voice, macros)
            val classified = Classifier.classify(Snip(snip.samples, 1, Dsp.RATE)).drumClass
            assertTrue(classified !in drumClasses, "$voice classified as $classified")
            assertEquals(DrumClass.TONAL, Spool.drumClassFor(voice, macros))
            assertEquals(DrumClass.LOOP, Spool.drumClassFor(voice, macros + ("HOLD" to 1f)))
        }
    }
}

/** Harmonic energy envelopes from pickup audio, independent of phase, gain and recipe coefficients. */
internal object SpoolTimbreListeningMeasure {
    private const val RATE = 44100

    data class Profile(val wantedHz: Double, val partialPower: List<Double>) {
        val rootShare get() = partialPower.first()
        val centroidHarmonic get() = partialPower.indices.sumOf { (it + 1) * partialPower[it] }
        val upperShare get() = partialPower.drop(3).sum()
        val oddUpperShare get() = partialPower.indices.filter { it >= 2 && it % 2 == 0 }.sumOf { partialPower[it] }
        val evenShare get() = partialPower.indices.filter { it % 2 == 1 }.sumOf { partialPower[it] }

        fun share(lowHz: Double, highHz: Double) = partialPower.indices
            .filter { (it + 1) * wantedHz in lowHz..highHz }.sumOf { partialPower[it] }

        fun strongestPartialHz(lowHz: Double, highHz: Double): Double {
            val index = partialPower.indices.filter { (it + 1) * wantedHz in lowHz..highHz }
                .maxBy { partialPower[it] }
            return (index + 1) * wantedHz
        }

        /** Hellinger distance compares amplitude envelopes after normalizing total partial power. */
        fun distance(other: Profile) = sqrt(partialPower.indices.sumOf {
            (sqrt(partialPower[it]) - sqrt(other.partialPower[it])).pow(2)
        } / 2)

        override fun toString() = "root=$rootShare, centroid=$centroidHarmonic, upper=$upperShare, " +
            "oddUpper=$oddUpperShare, even=$evenShare"
    }

    fun profile(s: FloatArray, wanted: Double, fromSeconds: Double, untilSeconds: Double): Profile {
        val first = (fromSeconds * RATE).toInt()
        val count = ((untilSeconds - fromSeconds) * RATE).toInt().coerceAtMost(s.size - first)
        require(count > RATE / 5)
        val n = 65536
        require(count <= n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = s[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val bin = RATE.toDouble() / n
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val partial = (1..minOf(24, (8000 / wanted).toInt())).map { h ->
            // Admit tape-motion sidebands without assigning power to neighboring harmonics.
            val center = h * wanted
            val width = maxOf(3 / (count.toDouble() / RATE),
                center * (2.0.pow(20.0 / 1200) - 1)).coerceAtMost(wanted * .25)
            val lo = ((center - width) / bin).toInt().coerceAtLeast(1)
            val hi = ((center + width) / bin).toInt().coerceAtMost(power.lastIndex)
            (lo..hi).sumOf { power[it] }
        }
        val total = partial.sum().coerceAtLeast(1e-30)
        return Profile(wanted, partial.map { it / total })
    }
}

/** Audio-derived tape motion; no transport trace or known modulation signal enters these meters. */
internal object SpoolTapeListeningMeasure {
    private const val RATE = 44100

    data class PitchMotion(val cents: List<Double>, val meanCents: Double, val motionRmsCents: Double,
                           val fastRmsCents: Double, val maximumCents: Double) {
        override fun toString() = "mean=$meanCents, motion=$motionRmsCents, fast=$fastRmsCents, max=$maximumCents cents"
    }

    /**
     * Isolate the fundamental as an analytic signal, then measure its phase advance over 20ms.
     * The broad band retains wow/flutter sidebands while rejecting upper harmonics. Sampling
     * every 10ms distinguishes ordinary slow drift from several cycles of audible flutter.
     */
    fun pitchMotion(s: FloatArray, wanted: Double, fromSeconds: Double, untilSeconds: Double): PitchMotion {
        var n = 1
        while (n < s.size) n *= 2
        val re = s.copyOf(n)
        val im = FloatArray(n)
        Fft.forward(re, im)
        val lo = (wanted * .82 * n / RATE).toInt()
        val hi = (wanted * 1.18 * n / RATE).toInt()
        for (i in re.indices) {
            if (i in lo..hi) { re[i] *= 2f; im[i] *= 2f }
            else { re[i] = 0f; im[i] = 0f }
        }
        Fft.inverse(re, im)
        val half = (RATE * .01).toInt()
        val hop = half
        val first = (fromSeconds * RATE).toInt() + half
        val end = (untilSeconds * RATE).toInt() - half
        val omega = 2 * PI * wanted / RATE
        val cents = (first until end step hop).map { at ->
            val a = at - half
            val b = at + half
            val cross = re[a].toDouble() * im[b] - im[a].toDouble() * re[b]
            val dot = re[a].toDouble() * re[b] + im[a].toDouble() * im[b]
            val phaseError = atan2(sin(atan2(cross, dot) - omega * (b - a)),
                cos(atan2(cross, dot) - omega * (b - a)))
            val frequency = wanted + phaseError * RATE / (2 * PI * (b - a))
            1200 * ln(frequency / wanted) / ln(2.0)
        }
        require(cents.size > 30)
        val mean = cents.average()
        val motion = sqrt(cents.sumOf { (it - mean).pow(2) } / cents.size)
        // A 150ms local mean follows sub-Hz wow but removes very little 6-12Hz flutter.
        val radius = 7
        val fast = (radius until cents.size - radius).map { i ->
            cents[i] - (i - radius..i + radius).map { cents[it] }.average()
        }
        return PitchMotion(cents, mean, motion, sqrt(fast.sumOf { it * it } / fast.size), cents.maxOf { abs(it) })
    }

    /** Upper harmonic energy relative to the fundamental rejects a plain gain-only swell. */
    fun brightness(s: FloatArray, wanted: Double, fromSeconds: Double, untilSeconds: Double): Double {
        val first = (fromSeconds * RATE).toInt()
        val count = ((untilSeconds - fromSeconds) * RATE).toInt()
        var n = 1
        while (n < count) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = s[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        fun band(low: Double, high: Double): Double {
            val from = (low * n / RATE).toInt().coerceAtLeast(1)
            val until = (high * n / RATE).toInt().coerceAtMost(n / 2 - 1)
            return (from..until).sumOf { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        }
        val root = band(wanted * .88, wanted * 1.12)
        val upper = (2..(6000 / wanted).toInt()).sumOf { harmonic ->
            band(wanted * (harmonic - .12), wanted * (harmonic + .12))
        }
        return upper / root.coerceAtLeast(1e-30)
    }
}

/** Shared listening meters for tests and the real audition manifest. */
internal object SpoolListeningMeasure {
    private const val RATE = 44100
    private const val WINDOW = .025
    private const val HOP = .005

    data class Overlap(val preGateSeconds: Double, val postGateSeconds: Double, val totalSeconds: Double,
                       val postGateFraction: Double, val reverseToForwardRms: Double,
                       val fromSeconds: Double, val untilSeconds: Double) {
        fun values() = mapOf("preGateSeconds" to preGateSeconds, "postGateSeconds" to postGateSeconds,
            "totalSeconds" to totalSeconds, "postGateFraction" to postGateFraction,
            "reverseToForwardRms" to reverseToForwardRms, "fromSeconds" to fromSeconds, "untilSeconds" to untilSeconds,
            "windowSeconds" to WINDOW, "hopSeconds" to HOP)
    }

    data class Tone(val hz: Double, val cents: Double, val rootPowerShare: Double,
                    val harmonicPowerShare: Double, val roughness: Double) {
        fun values() = mapOf("hz" to hz, "cents" to cents, "rootPowerShare" to rootPowerShare,
            "harmonicPowerShare" to harmonicPowerShare, "roughness" to roughness)
    }

    fun rms(s: FloatArray, from: Int = 0, until: Int = s.size): Double =
        sqrt((from until until).sumOf { s[it].toDouble() * s[it] } / (until - from).coerceAtLeast(1))

    fun overlap(forward: FloatArray, reverse: FloatArray, gate: Double, end: Double): Overlap {
        val size = minOf(forward.size, reverse.size)
        val width = (WINDOW * RATE).toInt()
        val count = ((size.toDouble() / RATE - WINDOW) / HOP).toInt() + 1
        val f = DoubleArray(count) { j -> val at = (j * HOP * RATE).toInt(); rms(forward, at, at + width) }
        val r = DoubleArray(count) { j -> val at = (j * HOP * RATE).toInt(); rms(reverse, at, at + width) }
        val peakF = f.maxOrNull() ?: 0.0
        val peakR = r.maxOrNull() ?: 0.0
        var pre = 0; var post = 0; var run = 0; var longest = 0; var last = 0
        var fEnergy = 0.0; var rEnergy = 0.0
        for (j in f.indices) {
            val t = j * HOP + WINDOW / 2
            val threshold = if (t < gate) .05 else .02
            val active = t < end && peakF > 1e-8 && peakR > 1e-8 &&
                f[j] > peakF * threshold && r[j] > peakR * threshold && r[j] >= f[j] * .05
            if (active) {
                if (t < gate) pre++ else post++
                fEnergy += f[j] * f[j]; rEnergy += r[j] * r[j]
                run++
                if (run > longest) { longest = run; last = j }
            } else run = 0
        }
        val span = longest * HOP
        val middle = ((last - longest + 1 + last) * HOP + WINDOW) / 2
        val measured = minOf(.55, (span - .10).coerceAtLeast(0.0))
        return Overlap(pre * HOP, post * HOP, (pre + post) * HOP,
            post * HOP / (end - gate).coerceAtLeast(1e-12), sqrt(rEnergy / fEnergy.coerceAtLeast(1e-30)),
            middle - measured / 2, middle + measured / 2)
    }

    /** Hann resolution widens harmonic bands; their width is capped so adjacent roots remain separable. */
    fun tone(s: FloatArray, fromSeconds: Double, untilSeconds: Double, wanted: Double): Tone {
        val first = (fromSeconds * RATE).toInt().coerceAtLeast(0)
        val count = minOf(((untilSeconds - fromSeconds) * RATE).toInt(), s.size - first)
        require(count > RATE / 5)
        val n = 65536
        require(count <= n)
        val re = FloatArray(n); val im = FloatArray(n)
        for (i in 0 until count) re[i] = s[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val bin = RATE.toDouble() / n
        val duration = count.toDouble() / RATE
        val lo = (40 / bin).toInt().coerceAtLeast(2)
        val hi = (8000 / bin).toInt()
        fun frequency(p: Int): Double {
            val left = sqrt(power[p - 1]); val center = sqrt(power[p]); val right = sqrt(power[p + 1])
            val curve = left - 2 * center + right
            val offset = if (abs(curve) < 1e-25) 0.0 else (.5 * (left - right) / curve).coerceIn(-1.0, 1.0)
            return (p + offset) * bin
        }
        val rootPeak = ((wanted * .65 / bin).toInt()..(wanted * 1.4 / bin).toInt()).maxBy { power[it] }
        val hz = frequency(rootPeak)
        val total = (lo..hi).sumOf { power[it] }.coerceAtLeast(1e-30)
        val root = ((wanted * .96 / bin).toInt()..(wanted * 1.04 / bin).toInt()).sumOf { power[it] }
        val harmonic = BooleanArray(power.size)
        for (h in 1..(8000 / wanted).toInt()) {
            val center = wanted * h
            val halfWidth = maxOf(2 / duration, center * (2.0.pow(25.0 / 1200) - 1)).coerceAtMost(wanted * .25)
            val start = ((center - halfWidth) / bin).toInt().coerceAtLeast(lo)
            val end = ((center + halfWidth) / bin).toInt().coerceAtMost(hi)
            for (p in start..end) harmonic[p] = true
        }
        val harmonicEnergy = (lo..hi).filter { harmonic[it] }.sumOf { power[it] }
        val maximum = (lo..hi).maxOf { power[it] }
        val peaks = (lo + 1 until hi).filter { p -> power[p] > power[p - 1] && power[p] >= power[p + 1] && power[p] > maximum * .0009 }
            .sortedByDescending { power[it] }
        val selected = mutableListOf<Int>()
        for (p in peaks) {
            if (selected.all { abs(frequency(p) - frequency(it)) >= 2 / duration }) selected += p
            if (selected.size == 60) break
        }
        // Normalized critical-band interaction is a roughness proxy, not a listening verdict.
        var roughness = 0.0
        for (i in selected.indices) for (j in i + 1 until selected.size) {
            val a = selected[i]; val b = selected[j]
            val x = abs(frequency(a) - frequency(b)) * .24 / (.021 * minOf(frequency(a), frequency(b)) + 19)
            roughness += sqrt(power[a] * power[b]) * (exp(-3.5 * x) - exp(-5.75 * x))
        }
        return Tone(hz, 1200 * ln(hz / wanted) / ln(2.0), root / total, harmonicEnergy / total,
            roughness / selected.sumOf { power[it] }.coerceAtLeast(1e-30))
    }

    /** A deliberately detuned counterexample calibrates the roughness check; it is never exported. */
    fun semitoneReference(s: FloatArray, fromSeconds: Double, untilSeconds: Double): FloatArray {
        val size = ((untilSeconds - fromSeconds) * RATE).toInt()
        val first = fromSeconds * RATE
        val speed = 2.0.pow(1.0 / 12)
        return FloatArray(size) { i ->
            val at = (first + i * speed).coerceAtMost(s.size - 2.0001)
            val p = at.toInt(); val fraction = (at - p).toFloat()
            s[p] + (s[p + 1] - s[p]) * fraction
        }
    }
}
