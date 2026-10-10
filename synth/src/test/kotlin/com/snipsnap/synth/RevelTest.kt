package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mechanical and acoustic evidence. The dry audition remains the gate for source character. */
class RevelTest {
    @Test
    fun `host note is separate from the five timbral macros and finite pitched routing`() {
        for (voice in RevelVoice.entries) {
            assertEquals(listOf("TUNE", "PLAY", "SKIN", "ORBIT", "WEAVE", "REACH", "HOLD"), Revel.macrosFor(voice).map { it.name })
            assertEquals(48, Revel.midiFor(voice, 0f))
            assertEquals(72, Revel.midiFor(voice, 1f))
            assertEquals(25, (0..100).map { Revel.midiFor(voice, it / 100f) }.toSet().size)
            assertEquals(DrumClass.TONAL, Revel.drumClassFor(voice))
            assertEquals(DrumClass.LOOP, Revel.drumClassFor(voice, mapOf("HOLD" to 1f)))
        }
    }

    @Test
    fun `four linked source families sound and the rolling heads have distinct modal projections`() {
        val report = Revel.inspect(RevelVoice.CIRCLE, mapOf("PLAY" to 0f, "ORBIT" to 0f),
            probe = Revel.Probe(recordSources = true, seconds = 3f))
        val taps = requireNotNull(report.sources)
        assertEquals(6, taps.size)
        assertEquals(4, report.events.map { it.family }.toSet().size, "minimum PLAY must retain the whole ensemble")
        assertEquals((0..5).toSet(), report.events.map { it.head }.toSet())
        assertTrue(report.events.zipWithNext().all { (a, b) -> a.timeSeconds <= b.timeSeconds }, "event order changed")
        assertTrue(report.events.map { it.timeSeconds }.distinct().size > 4, "the ensemble only strikes together")
        for (head in taps.indices) assertTrue(rms(taps[head]) > 1e-7, "head $head is silent")
        val rollEvents = report.events.filter { it.head in 3..4 }
        assertTrue(rollEvents.map { it.head }.distinct().size == 2)
        assertTrue(relativeDifference(taps[3], taps[4]) > .1, "rolling heads merely share an output signal")
        assertTrue(abs(brightness(taps[3]) - brightness(taps[4])) > 1e-5, "rolling heads have the same spectrum")
        assertBounded(report, "sparse circle")
    }

    @Test
    fun `identical seeds and reordered maps repeat raw sources events and final samples`() {
        val macros = linkedMapOf("PLAY" to .72f, "SKIN" to .31f, "ORBIT" to .54f, "WEAVE" to .61f, "REACH" to .83f)
        val config = RevelConfig(micCount = 2, seed = 817L)
        val probe = Revel.Probe(recordSources = true, seconds = 1.4f)
        val a = Revel.inspect(RevelVoice.CROSSING, macros, .7f, config, probe)
        val b = Revel.inspect(RevelVoice.CROSSING, macros.entries.reversed().associate { it.key to it.value }, .7f, config, probe)
        assertContentEquals(a.raw, b.raw)
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.events, b.events)
        for (head in 0..5) assertContentEquals(requireNotNull(a.sources)[head], requireNotNull(b.sources)[head])
        val changed = Revel.inspect(RevelVoice.CROSSING, macros, .7f, config.copy(seed = 818L), probe)
        assertFalse(a.raw.contentEquals(changed.raw), "seed did not change performer variation")
        for (hold in listOf(0f, 1f)) {
            val silent = Revel.inspect(RevelVoice.CIRCLE, mapOf("HOLD" to hold), velocity = 0f,
                probe = Revel.Probe(seconds = 1f))
            assertTrue(silent.raw.all { it == 0f }, "zero velocity generated source work")
            assertTrue(silent.snip.samples.all { it == 0f }, "zero velocity was raised by loudness matching")
        }
    }

    @Test
    fun `microphones observe one identical ongoing performance and coincident paths match one microphone`() {
        val probe = Revel.Probe(recordSources = true, recordMics = true, seconds = 1.6f)
        val config = RevelConfig(micCount = 1, seed = 904L)
        val stationary = Revel.inspect(RevelVoice.CIRCLE, mapOf("ORBIT" to 0f, "WEAVE" to 0f, "REACH" to 0f), configuration = config, probe = probe)
        val moving = Revel.inspect(RevelVoice.CIRCLE, mapOf("ORBIT" to .7f, "WEAVE" to 1f, "REACH" to 1f),
            configuration = config.copy(micCount = 3), probe = probe)
        assertEquals(stationary.events, moving.events, "observer configuration changed the performance")
        for (head in 0..5) assertContentEquals(requireNotNull(stationary.sources)[head], requireNotNull(moving.sources)[head], "head $head changed under pickup")
        assertEquals(stationary.maxEnergy, moving.maxEnergy)
        assertEquals(stationary.poweredWork, moving.poweredWork)
        assertTrue(relativeDifference(stationary.snip.samples, moving.snip.samples) > .1, "mono perspective did not move")

        val single = Revel.inspect(RevelVoice.CIRCLE, configuration = config, probe = probe)
        val coincident = Revel.inspect(RevelVoice.CIRCLE, configuration = config.copy(micCount = 3),
            probe = probe.copy(coincidentMics = true))
        assertTrue(relativeDifference(single.raw, coincident.raw) < 1e-5, "coherent sum changed the matched perspective")
        val mics = requireNotNull(coincident.microphones)
        assertEquals(3, mics.size)
        assertContentEquals(mics[0], mics[1])
        assertContentEquals(mics[0], mics[2])
        assertBounded(moving, "three independent observers")
    }

    @Test
    fun `travel remains clear of players and crossings keep every delay history continuous`() {
        val report = Revel.inspect(RevelVoice.SPIRO,
            mapOf("ORBIT" to 1f, "WEAVE" to 1f, "REACH" to 1f),
            configuration = RevelConfig(micCount = 3),
            probe = Revel.Probe(recordPaths = true, seconds = 2f))
        assertTrue(report.paths.isNotEmpty())
        assertEquals((0..2).toSet(), report.paths.map { it.mic }.toSet())
        for (path in report.paths) {
            assertTrue(hypot(path.x, path.y) <= Revel.MAX_PATH_RADIUS + 1e-6, "path escaped the circle")
            assertTrue(path.distanceMeters >= Revel.MIN_CLEARANCE - 1e-6, "mic ${path.mic} crossed head ${path.head}")
            assertTrue(path.gain.isFinite() && path.gain > 0.0)
            assertTrue(path.delaySamples.isFinite() && path.delaySamples > 0.0)
        }
        for (frames in report.paths.groupBy { it.mic to it.head }.values) {
            for ((a, b) in frames.zipWithNext()) {
                val dt = b.timeSeconds - a.timeSeconds
                assertTrue(dt > 0.0)
                assertTrue(hypot(b.x - a.x, b.y - a.y) < 10.0 * dt + 1e-6, "trajectory jumped at ${b.timeSeconds}")
                assertTrue(abs(b.gain - a.gain) < 20.0 * dt + 1e-5, "pickup gain jumped at crossing")
            }
        }
        assertTrue(report.maxDelayRate <= .00401, "Doppler rate exceeded musical guard ${report.maxDelayRate}")
        assertBounded(report, "fast spiro")

        val stopped = Revel.inspect(RevelVoice.SPIRO, mapOf("ORBIT" to 0f),
            probe = Revel.Probe(recordPaths = true, seconds = .5f))
        for (frames in stopped.paths.groupBy { it.mic to it.head }.values) {
            assertEquals(1, frames.map { it.x to it.y }.distinct().size, "ORBIT zero traveled")
        }
        assertEquals(0.0, stopped.rates.orbitHz)
    }

    @Test
    fun `shared floor transfers real sympathetic motion and decays after input stops`() {
        val probe = Revel.Probe(recordSources = true, soloHead = 5, inputOffSeconds = .1, seconds = 3f)
        val coupled = Revel.inspect(RevelVoice.PROCESSION, mapOf("ORBIT" to 0f), probe = probe)
        val disconnected = Revel.inspect(RevelVoice.PROCESSION, mapOf("ORBIT" to 0f), probe = probe.copy(floor = false))
        assertTrue(coupled.maxFloorEnergy > 0.0)
        assertTrue(rms(requireNotNull(coupled.floorSignal)) > 1e-8)
        assertEquals(coupled.events, disconnected.events, "floor mute changed performer scheduling")
        assertTrue(coupled.events.all { it.head == 5 }, "passive receiver was given a performer stroke")
        val receiving = requireNotNull(coupled.sources).take(5)
        assertTrue(receiving.any { rms(it) > 1e-8 }, "pulse did not excite another head through the floor")
        assertTrue(requireNotNull(disconnected.sources).take(5).all { samples -> samples.all { it == 0f } }, "floor-disabled receiver generated its own force")
        assertTrue(relativeDifference(coupled.raw, disconnected.raw) > .001, "floor transfer is inaudible in the raw output")
        val energy = requireNotNull(coupled.energy)
        assertTrue(energy.last() < coupled.maxEnergy * .02, "finite passive ensemble did not decay")
        assertTrue(abs(energy.last() + coupled.passiveLoss - coupled.poweredWork) < maxOf(1e-8, coupled.maxEnergy * 1e-4),
            "floor energy accounting does not close against supplied work and passive loss")
        // Sample broad intervals to ignore float rounding without ignoring accumulated growth.
        for (index in 8820 until energy.size - 441 step 441) {
            assertTrue(energy[index + 441] <= energy[index] * 1.00002f + 1e-10f, "unforced acoustic energy grew at frame $index")
        }
        assertBounded(coupled, "passive floor tail")
    }

    @Test
    fun `root returns after finite excitation across the supported notes and skin endpoints`() {
        val cases = listOf(0f to .5f, .5f to 0f, .5f to 1f, 1f to .5f)
        for ((tune, skin) in cases) {
            val report = Revel.inspect(RevelVoice.CIRCLE, mapOf("TUNE" to tune, "SKIN" to skin, "ORBIT" to 0f),
                probe = Revel.Probe(soloHead = 5, inputOffSeconds = .1, seconds = .9f))
            assertRoot(report.snip, Revel.frequencyFor(RevelVoice.CIRCLE, tune), "TUNE $tune SKIN $skin", .2f)
        }
    }

    @Test
    fun `all six membrane projections return to the shared root after their own contact releases`() {
        for (head in 0..5) {
            val report = Revel.inspect(RevelVoice.CIRCLE, mapOf("ORBIT" to 0f),
                probe = Revel.Probe(recordSources = true, soloHead = head, inputOffSeconds = 1.1, seconds = 1.9f))
            assertTrue(report.events.isNotEmpty(), "head $head was never driven")
            val tap = requireNotNull(report.sources)[head]
            assertRoot(Snip(tap, sampleRate = Dsp.RATE, channels = 1), Revel.frequencyFor(RevelVoice.CIRCLE, .5f),
                "head $head released contact", 1.2f)
        }
    }

    @Test
    fun `actual dry ensemble classifier excludes drum families before the duration shortcut`() {
        for (voice in RevelVoice.entries) {
            val report = Revel.inspect(voice, probe = Revel.Probe(seconds = 1.35f))
            assertBounded(report, "$voice short dry material")
            val heard = Classifier.classify(report.snip)
            println("REVEL $voice short material: ${heard.drumClass}, centroid ${heard.features.centroidHz}, decay ${heard.features.decayMs}")
            assertTrue(heard.drumClass !in DRUMS, "$voice short material classified ${heard.drumClass}")
            assertShortClassification(report.raw, "$voice raw material")
            val pitch = Pitch.detect(report.snip, .2f, .35f)
            assertNotNull(pitch, "$voice normal ensemble lost periodicity")
            val cents = FineTuning.cents(pitch.hz.toDouble(), Revel.frequencyFor(voice, .5f).toDouble())
            println("REVEL $voice active ensemble pitch: $cents cents, confidence ${pitch.confidence}")
            assertTrue(abs(cents) < 40,
                "$voice detected ${pitch.hz} Hz instead of its root")
        }
    }

    @Test
    fun `supported roots and source velocities retain pitch and pass raw and matched head classification`() {
        val failures = ArrayList<String>()
        for (voice in RevelVoice.entries) for (tune in listOf(0f, .5f, 1f)) for (velocity in listOf(.25f, .65f, 1f)) {
            val label = "$voice MIDI ${Revel.midiFor(voice, tune)} velocity $velocity"
            // Leave 150 ms after the measured head. The diagnostic's final
            // fade and decimator boundary cannot shorten this prefix's tail.
            val report = Revel.inspect(voice, mapOf("TUNE" to tune), velocity = velocity,
                probe = Revel.Probe(seconds = 1.5f))
            assertBounded(report, label)
            checkHeadClassification(report.raw, "$label raw", failures)
            checkHeadClassification(report.snip.samples, "$label matched", failures)
            checkActiveRoot(report.snip, Revel.frequencyFor(voice, tune), label, failures)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `all factory ensembles pass the actual classifier and retain their requested roots`() {
        val failures = ArrayList<String>()
        for (preset in RevelPresets.all()) {
            // The held preset must use its settled export region, rather than
            // the startup of a shortened continuing-performance diagnostic.
            val report = Revel.inspect(preset.voice, preset.macros, preset.velocity, preset.configuration)
            assertBounded(report, preset.name)
            checkHeadClassification(report.raw, "${preset.name} raw", failures)
            checkHeadClassification(report.snip.samples, "${preset.name} matched", failures)
            checkActiveRoot(report.snip, Revel.frequencyFor(preset.voice, preset.macros.getValue("TUNE")),
                preset.name, failures)
            val whole = Classifier.classify(report.snip).drumClass
            if (whole in DRUMS) failures += "${preset.name} complete export classified $whole"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `play changes participation while orbit keeps its own clock and velocity changes source work`() {
        val probe = Revel.Probe(seconds = 2.6f)
        val sparse = Revel.inspect(RevelVoice.CIRCLE, mapOf("PLAY" to 0f, "ORBIT" to .1f), probe = probe)
        val busy = Revel.inspect(RevelVoice.CIRCLE, mapOf("PLAY" to 1f, "ORBIT" to .1f), probe = probe)
        val fast = Revel.inspect(RevelVoice.CIRCLE, mapOf("PLAY" to 0f, "ORBIT" to 1f), probe = probe)
        assertEquals(sparse.events, fast.events, "ORBIT changed phrase timing")
        assertEquals(sparse.rates.phraseSeconds, fast.rates.phraseSeconds)
        assertTrue(fast.rates.orbitHz > sparse.rates.orbitHz)
        assertTrue(busy.events.size > sparse.events.size, "PLAY did not change density")
        assertTrue(busy.poweredWork > sparse.poweredWork, "PLAY did not change performer work")
        val quiet = Revel.inspect(RevelVoice.CIRCLE, velocity = .25f, probe = probe)
        val strong = Revel.inspect(RevelVoice.CIRCLE, velocity = 1f, probe = probe)
        assertTrue(strong.poweredWork > quiet.poweredWork * 2, "velocity only changed output level")
        assertTrue(relativeDifference(quiet.snip.samples, strong.snip.samples) > .01, "shared normalization hid velocity character")
    }

    @Test
    fun `required macro pairs interact through the actual gestures and pickup`() {
        val probe = Revel.Probe(seconds = 1.2f)
        for ((first, second) in listOf("ORBIT" to "REACH", "WEAVE" to "REACH", "PLAY" to "ORBIT", "SKIN" to "REACH")) {
            val corners = listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (x, y) ->
                Revel.inspect(RevelVoice.CIRCLE, mapOf(first to x, second to y), probe = probe).raw
            }
            val authority = minOf(relativeDifference(corners[0], corners[2]), relativeDifference(corners[1], corners[3]),
                relativeDifference(corners[0], corners[1]), relativeDifference(corners[2], corners[3]))
            assertTrue(authority > .005, "$first/$second has a dead endpoint: $authority")
            val interaction = interactionEnergy(corners)
            println("REVEL $first x $second interaction $interaction")
            assertTrue(interaction > .001, "$first/$second reduces to unrelated additive controls")
        }
    }

    @Test
    fun `held phrases settle acoustic state and close slow and fast microphone paths`() {
        for ((voice, macros) in listOf(
            RevelVoice.CIRCLE to mapOf("HOLD" to 1f, "ORBIT" to .01f, "PLAY" to .2f),
            RevelVoice.SPIRO to mapOf("HOLD" to 1f, "ORBIT" to 1f, "WEAVE" to 1f, "REACH" to 1f, "PLAY" to 1f),
        )) {
            val report = Revel.inspect(voice, macros)
            val loop = requireNotNull(report.loop)
            println("REVEL HOLD $voice: seam ${loop.seamError}, acoustic convergence ${loop.convergenceError}, state ${loop.stateConvergenceError}, cycles ${loop.prerollCycles}")
            assertTrue(loop.seamError.isFinite() && loop.seamError < Keys.MAX_SEAM_ERROR, "$voice seam ${loop.seamError}")
            assertTrue(loop.convergenceError < 1e-3, "$voice preceding acoustic period did not settle")
            assertTrue(loop.stateConvergenceError < 1e-3, "$voice complete state did not settle")
            assertTrue(loop.prerollCycles in 1..16)
            assertTrue(report.rates.orbitHz > 0.0, "nonzero slow orbit rounded to stationary")
            val cycles = report.rates.orbitHz * report.rates.loopSeconds
            assertTrue(abs(cycles - kotlin.math.round(cycles)) < 1e-5, "base microphone path does not close")
            assertBounded(report, "$voice held ensemble")
        }
    }

    @Test
    fun `shortest permitted phrase closes twice speed spiro pickup without cutting a friction gesture`() {
        val configuration = RevelConfig(micCount = 1, phraseBeats = 1, phraseTempo = 200f,
            trajectories = listOf(RevelTrajectory.SPIRO), speedRatios = listOf(2f))
        val report = Revel.inspect(RevelVoice.FRICTION,
            mapOf("HOLD" to 1f, "PLAY" to 0f, "ORBIT" to 1f, "WEAVE" to 1f, "REACH" to 1f),
            configuration = configuration, probe = Revel.Probe(recordPaths = true))
        val loop = requireNotNull(report.loop)
        assertTrue(loop.convergenceError < 1e-3 && loop.stateConvergenceError < 1e-3,
            "short loop did not settle the real ensemble: $loop")
        assertTrue(loop.seamError < Keys.MAX_SEAM_ERROR)
        assertTrue(report.maxDelayRate <= Revel.MAX_DELAY_RATE + 1e-9)
        val friction = report.events.filter { it.family == "friction" }
        assertTrue(friction.isNotEmpty())
        for (event in friction) {
            val localTime = event.timeSeconds % report.rates.phraseSeconds
            assertTrue(localTime + event.durationSeconds <= report.rates.phraseSeconds + 1e-5,
                "a repeated rub was cut at the phrase boundary")
        }
        for (frames in report.paths.groupBy { it.mic to it.head }.values) {
            for ((a, b) in frames.zipWithNext()) {
                val dt = b.timeSeconds - a.timeSeconds
                assertTrue(abs(b.delaySamples - a.delaySamples) <= Revel.MAX_DELAY_RATE * Dsp.RATE * Dsp.OVERSAMPLE * dt + .01,
                    "twice speed pickup jumped past the delay rate guard")
            }
        }
        assertBounded(report, "short twice speed spiro")
    }

    @Test
    fun `dense three microphone render stays inside offline CPU and memory bounds`() {
        val started = System.nanoTime()
        val report = Revel.inspect(RevelVoice.SPIRO,
            listOf("PLAY", "SKIN", "ORBIT", "WEAVE", "REACH").associateWith { 1f } + ("TUNE" to 1f),
            configuration = RevelConfig(micCount = 3), probe = Revel.Probe(seconds = 4f))
        val elapsed = (System.nanoTime() - started) / 1e9
        assertTrue(elapsed < 20.0, "four-second offline render took $elapsed seconds")
        assertEquals(4 * Dsp.RATE, report.raw.size)
        assertBounded(report, "dense high register")
        assertShortClassification(report.raw, "dense high register raw prefix")
        assertShortClassification(report.snip.samples, "dense high register matched prefix")
    }

    private fun assertShortClassification(samples: FloatArray, label: String) {
        val snip = Snip(samples.copyOfRange(0, minOf(samples.size, (1.35f * Dsp.RATE).toInt())),
            sampleRate = Dsp.RATE, channels = 1)
        val heard = Classifier.classify(snip).drumClass
        assertTrue(heard !in DRUMS, "$label classified $heard before the duration shortcut")
    }

    private fun checkHeadClassification(samples: FloatArray, label: String, failures: MutableList<String>) {
        val prefix = Snip(samples.copyOfRange(0, minOf(samples.size, (1.35f * Dsp.RATE).toInt())),
            sampleRate = Dsp.RATE, channels = 1)
        val heard = Classifier.classify(prefix)
        if (heard.drumClass in DRUMS) {
            val f = heard.features
            failures += "$label classified ${heard.drumClass}: lowRatio ${f.lowRatio}, centroid ${f.centroidHz}, decay ${f.decayMs} ms"
        }
    }

    private fun checkActiveRoot(snip: Snip, wanted: Float, label: String, failures: MutableList<String>) {
        val estimate = listOf(.08f, .6f, 1.2f).mapNotNull { from -> Pitch.detect(snip, from, .25f) }
            .maxByOrNull { it.confidence }
        if (estimate == null) {
            failures += "$label has no periodic root in its active performance"
            return
        }
        val cents = FineTuning.cents(estimate.hz.toDouble(), wanted.toDouble())
        if (abs(cents) >= 40.0) failures += "$label root is $cents cents off, confidence ${estimate.confidence}"
    }

    private fun assertBounded(report: Revel.Report, label: String) {
        assertEquals(1, report.snip.channels, label)
        assertEquals(44_100, report.snip.sampleRate, label)
        assertTrue(report.raw.all { it.isFinite() }, "$label has nonfinite raw output")
        assertTrue(report.snip.samples.all { it.isFinite() }, "$label has nonfinite leveled output")
        assertTrue(report.raw.maxOf { abs(it) } < 1f, "$label raw peak ${report.raw.maxOf { abs(it) }}")
        assertTrue(report.snip.peak() <= .991f, "$label leveled peak ${report.snip.peak()}")
        assertTrue(abs(report.raw.average()) < .01, "$label raw DC ${report.raw.average()}")
        assertTrue(report.maxEnergy.isFinite() && report.maxEnergy >= 0.0)
        assertTrue(report.passiveLoss.isFinite() && report.passiveLoss >= 0.0)
        assertEquals(0, report.recoveries, "$label reset an unstable internal state")
    }

    private fun assertRoot(snip: Snip, root: Float, label: String, from: Float) {
        val hz = FineTuning.measuredHz(snip, root, from, .4f)
        val cents = FineTuning.cents(hz, root.toDouble())
        val share = rootShare(snip.samples, root, from)
        println("REVEL $label: $cents cents, root energy $share")
        // The initial C3/C4/C5 and released-head probes stay within .15 cents.
        // Five cents leaves estimator/voicing room while catching tuning drift.
        assertTrue(abs(cents) < 5.0, "$label sustained root is $cents cents off")
        assertTrue(share > .1, "$label expected root carries too little energy: $share")
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    private fun brightness(samples: FloatArray): Double {
        var difference = 0.0
        var energy = 0.0
        for (index in 1 until samples.size) {
            val d = samples[index].toDouble() - samples[index - 1]
            difference += d * d
            energy += samples[index].toDouble() * samples[index]
        }
        return difference / energy.coerceAtLeast(1e-30)
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        var energy = 0.0
        var difference = 0.0
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0f }.toDouble()
            val y = b.getOrElse(index) { 0f }.toDouble()
            difference += (x - y) * (x - y)
            energy += maxOf(x * x, y * y)
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    private fun interactionEnergy(corners: List<FloatArray>): Double {
        var interaction = 0.0
        var energy = 0.0
        for (index in corners[0].indices) {
            val d = corners[3][index] - corners[2][index] - corners[1][index] + corners[0][index]
            interaction += d.toDouble() * d
            energy += corners.maxOf { it[index].toDouble() * it[index] }
        }
        return sqrt(interaction / energy.coerceAtLeast(1e-30))
    }

    private fun rootShare(samples: FloatArray, root: Float, fromSeconds: Float): Double {
        val n = 32768
        val from = (fromSeconds * Dsp.RATE).toInt()
        val length = minOf((.4f * Dsp.RATE).toInt(), samples.size - from)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (index in 0 until length) re[index] = samples[from + index] * (.5 - .5 * cos(2 * PI * index / (length - 1))).toFloat()
        Fft.forward(re, im)
        var rootEnergy = 0.0
        var total = 0.0
        for (bin in 1 until n / 2) {
            val hz = bin.toDouble() * Dsp.RATE / n
            val power = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            total += power
            if (abs(hz - root) < maxOf(6.0, root * .03)) rootEnergy += power
        }
        return rootEnergy / total.coerceAtLeast(1e-30)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
