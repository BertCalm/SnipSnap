package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Causal and numerical checks on the dry shell; the audition supplies its sonic verdict. */
class UndertowTest {
    private val timbral = listOf("DRAW", "FLAP", "WEIGHT", "SPIRAL", "LEAK")
    private val drums = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
    )

    private fun rms(samples: FloatArray, from: Float = 0f, to: Float = samples.size.toFloat() / Dsp.RATE): Double {
        val first = (from * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val end = (to * Dsp.RATE).toInt().coerceIn(first, samples.size)
        var energy = 0.0
        for (i in first until end) energy += samples[i].toDouble() * samples[i]
        return if (end > first) sqrt(energy / (end - first)) else 0.0
    }

    /** Match RMS before comparing, so changing only final gain cannot pass a timbral control. */
    private fun matchedDifference(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val ra = rms(a)
        val rb = rms(b)
        if (n == 0 || ra < 1e-12 || rb < 1e-12) return 0.0
        var difference = 0.0
        for (i in 0 until n) {
            val d = a[i] / ra - b[i] / rb
            difference += d * d
        }
        return sqrt(difference / n)
    }

    private fun meanSuction(probe: Undertow.Probe, from: Float = 0.05f, to: Float = 0.5f): Double =
        probe.snapshots.filter { it.timeSeconds in from..to }.map { it.suction.toDouble() }.average()

    private fun meanRootFlow(probe: Undertow.Probe, from: Float = 0.05f, to: Float = 0.5f): Double =
        probe.snapshots.filter { it.timeSeconds in from..to }.map { it.flows[0].toDouble() }.average()

    private fun assertFinite(probe: Undertow.Probe, label: String) {
        assertTrue(probe.samples.isNotEmpty() && probe.samples.all { it.isFinite() }, "$label invalid audio")
        assertTrue(probe.rawPeak.isFinite() && probe.rawPeak < 10f, "$label uncontrolled raw peak ${probe.rawPeak}")
        assertTrue(probe.finalEnergy.isFinite() && probe.finalEnergy >= 0.0, "$label invalid final energy")
        assertTrue(probe.snapshots.isNotEmpty(), "$label missing mechanical diagnostics")
        for (state in probe.snapshots) {
            assertTrue(state.suction.isFinite() && state.suction >= 0f, "$label invalid deficit at ${state.timeSeconds}")
            assertTrue(state.energy.isFinite() && state.energy in 0.0..1000.0, "$label unbounded state energy ${state.energy}")
            assertTrue(state.pistonWork.isFinite() && state.pistonWork >= 0.0, "$label invalid piston work")
            for (values in listOf(state.localPressure, state.flows, state.apertures, state.displacement, state.velocity)) {
                assertTrue(values.all { it.isFinite() }, "$label nonfinite mechanics at ${state.timeSeconds}")
            }
            assertTrue(state.flows.all { it >= 0f }, "$label outward flow counted as replenishing suction")
            assertTrue(state.apertures.all { it in 0f..1.00001f }, "$label aperture left its normalized bounds")
        }
    }

    @Test
    fun `each voice exposes the same five timbral controls with tune and hold`() {
        for (voice in UndertowVoice.entries) {
            val specs = Undertow.macrosFor(voice)
            assertEquals(listOf("TUNE") + timbral + "HOLD", specs.map { it.name }, "$voice control surface")
            assertTrue(specs.all { it.default.isFinite() && it.default in 0f..1f && it.neutral in 0f..1f })
            assertEquals(0f, Undertow.defaults(voice).getValue("HOLD"))
        }
    }

    @Test
    fun `identical inputs reproduce all stems contacts and mechanical trajectories`() {
        val options = Undertow.ProbeOptions(durationSeconds = 1f, seedContext = 37)
        val a = Undertow.probe(UndertowVoice.SEAL, energy = 0.7f, options = options)
        val b = Undertow.probe(UndertowVoice.SEAL, energy = 0.7f, options = options)
        assertContentEquals(a.samples, b.samples)
        assertContentEquals(a.ceramic, b.ceramic)
        assertContentEquals(a.airflow, b.airflow)
        assertContentEquals(a.shell, b.shell)
        assertEquals(a.contacts, b.contacts)
        assertEquals(a.snapshots.size, b.snapshots.size)
        for ((left, right) in a.snapshots.zip(b.snapshots)) {
            assertEquals(left.timeSeconds, right.timeSeconds)
            assertEquals(left.suction, right.suction)
            assertEquals(left.pistonWork, right.pistonWork)
            assertEquals(left.energy, right.energy)
            assertContentEquals(left.localPressure, right.localPressure)
            assertContentEquals(left.flows, right.flows)
            assertContentEquals(left.apertures, right.apertures)
            assertContentEquals(left.displacement, right.displacement)
            assertContentEquals(left.velocity, right.velocity)
            assertContentEquals(left.sealed, right.sealed)
        }
        assertTrue(a.contacts.isNotEmpty(), "determinism case did not exercise ceramic contact")
        val changed = Undertow.probe(UndertowVoice.SEAL, energy = 0.7f, options = options.copy(seedContext = 38))
        assertFalse(a.samples.contentEquals(changed.samples), "seed context never reached texture or structure")
    }

    @Test
    fun `opening neighboring inlets replenishes the shared reservoir and changes the root flow`() {
        val macros = mapOf("DRAW" to 0.65f, "FLAP" to 0.5f, "LEAK" to 0.3f)
        val options = Undertow.ProbeOptions(durationSeconds = 0.9f)
        val shared = Undertow.probe(UndertowVoice.BREATH, macros, options = options)
        val isolated = Undertow.probe(UndertowVoice.BREATH, macros, options = options.copy(activeChambers = 1))
        val independent = Undertow.probe(UndertowVoice.BREATH, macros, options = options.copy(independentReservoirs = true))
        val sharedPressure = meanSuction(shared)
        val isolatedPressure = meanSuction(isolated)
        assertTrue(sharedPressure > 0.0 && isolatedPressure > 0.0, "piston never established suction")
        assertTrue(sharedPressure < isolatedPressure * 0.995,
            "other inlets did not reduce the deficit: shared $sharedPressure, isolated $isolatedPressure")
        val flowChange = abs(meanRootFlow(shared) - meanRootFlow(isolated)) / meanRootFlow(isolated).coerceAtLeast(1e-12)
        assertTrue(flowChange > 0.005, "neighboring inlets changed root flow by only $flowChange")
        val independentPressure = meanSuction(independent)
        assertTrue(abs(sharedPressure - independentPressure) > isolatedPressure * 0.005,
            "shared and independent reservoirs have the same trajectory")
        assertTrue(matchedDifference(shared.samples, independent.samples) > 0.01,
            "pressure competition never reached dry audio")
    }

    @Test
    fun `ceramic notes follow staggered moving contacts and disappear when contact is disabled`() {
        val options = Undertow.ProbeOptions(durationSeconds = 1f)
        val full = Undertow.probe(UndertowVoice.SURGE, options = options)
        val noContact = Undertow.probe(UndertowVoice.SURGE, options = options.copy(contactsEnabled = false))
        assertTrue(full.contacts.isNotEmpty(), "powered flaps never caught their rims")
        assertTrue(full.contacts.all { it.timeSeconds > 0f && it.strength.isFinite() && it.strength > 0f })
        assertTrue(full.contacts.zipWithNext().all { (a, b) -> a.timeSeconds <= b.timeSeconds })
        val first = full.contacts.groupBy { it.chamber }.mapValues { (_, events) -> events.first().timeSeconds }
        assertTrue(first.size >= 2, "neighboring chamber never answered: $first")
        assertEquals(0, full.contacts.first().chamber, "dominant chamber did not catch first")
        assertTrue(first.values.maxOrNull()!! - first.values.minOrNull()!! > 0.001f,
            "every rim was triggered at the same time: $first")
        assertTrue(rms(full.ceramic) > 1e-7, "rim crossings did not excite ceramic modes")
        assertEquals(0.0, rms(noContact.ceramic), "disabled contacts still generated ceramic sound")
        assertTrue(rms(noContact.airflow) > 1e-7, "removing contact also removed the tuned airflow mechanism")
        assertTrue(rms(full.shell) > 1e-8, "local chambers never excited the shell")
    }

    @Test
    fun `greater ceramic weight delays the catch rather than merely increasing gain`() {
        val macros = mapOf("DRAW" to 0.7f, "FLAP" to 0.45f, "LEAK" to 0.25f)
        val options = Undertow.ProbeOptions(durationSeconds = 0.9f)
        val light = Undertow.probe(UndertowVoice.KNOCK, macros + ("WEIGHT" to 0.15f), options = options)
        val heavy = Undertow.probe(UndertowVoice.KNOCK, macros + ("WEIGHT" to 0.85f), options = options)
        val lightCatch = light.contacts.firstOrNull { it.chamber == 0 }?.timeSeconds ?: error("light flap never caught")
        val heavyCatch = heavy.contacts.firstOrNull { it.chamber == 0 }?.timeSeconds ?: error("heavy flap never caught")
        assertTrue(heavyCatch > lightCatch + 0.0001f, "WEIGHT failed to change inertia: $lightCatch -> $heavyCatch")
        assertTrue(matchedDifference(light.samples, heavy.samples) > 0.01, "WEIGHT changed only level")
    }

    @Test
    fun `bypass leakage changes pressure before it changes the breath mixture`() {
        val options = Undertow.ProbeOptions(durationSeconds = 1.2f)
        val tight = Undertow.probe(UndertowVoice.BREATH, mapOf("LEAK" to 0f), options = options)
        val leaky = Undertow.probe(UndertowVoice.BREATH, mapOf("LEAK" to 1f), options = options)
        assertTrue(meanSuction(leaky) < meanSuction(tight) * 0.99,
            "LEAK failed to replenish the pressure deficit: ${meanSuction(tight)} -> ${meanSuction(leaky)}")
        assertTrue(rms(leaky.ceramic) > 1e-7 && rms(leaky.airflow) > 1e-7,
            "high leakage made the normal voice lose its contact or pitched response")
        assertTrue(matchedDifference(tight.samples, leaky.samples) > 0.01, "bypass changed only level")
    }

    @Test
    fun `pressure flaps and acoustic energy relax when piston extraction ends`() {
        val options = Undertow.ProbeOptions(durationSeconds = 5.5f, driveStopSeconds = 0.5f)
        val passive = Undertow.probe(
            UndertowVoice.SURGE,
            mapOf("DRAW" to 1f, "FLAP" to 1f, "WEIGHT" to 1f, "SPIRAL" to 1f, "LEAK" to 0f),
            options = options,
        )
        assertFinite(passive, "stopped piston")
        val peakEnergy = passive.snapshots.maxOf { it.energy }
        val peakPressure = passive.snapshots.maxOf { it.suction }
        assertTrue(peakEnergy > 0.0 && peakPressure > 0f, "passive probe never received powered energy")
        assertTrue(passive.finalEnergy < peakEnergy * 0.01, "stored energy did not decay after drive stopped")
        assertTrue(passive.snapshots.last().suction < peakPressure * 0.05f, "low-leak aperture remained pressure locked")
        val late = passive.snapshots.filter { it.timeSeconds > 0.55f }
        assertTrue(late.isNotEmpty())
        assertEquals(late.first().pistonWork, late.last().pistonWork, 1e-9, "work kept entering with extraction disabled")
        assertTrue(rms(passive.samples, 4.8f, 5.5f) < rms(passive.samples, 0f, 0.7f) * 0.01,
            "passive network still sounded at the render horizon")
        val resting = Undertow.probe(UndertowVoice.SURGE, options = options.copy(driveStopSeconds = 0f, durationSeconds = 0.8f))
        assertEquals(0.0, rms(resting.samples), "an undriven resting shell created sound")
        assertTrue(resting.contacts.isEmpty(), "an undriven flap generated ceramic impacts")
    }

    @Test
    fun `all normal voices remain audible at macro extremes before loudness matching`() {
        val options = Undertow.ProbeOptions(durationSeconds = 1f)
        for (voice in UndertowVoice.entries) {
            for (macros in listOf(
                emptyMap(), timbral.associateWith { 0f }, timbral.associateWith { 1f } + ("TUNE" to 1f),
                mapOf("DRAW" to 0f, "LEAK" to 1f, "FLAP" to 1f, "WEIGHT" to 1f),
            )) {
                val probe = Undertow.probe(voice, macros, options = options)
                assertFinite(probe, "$voice $macros")
                assertTrue(rms(probe.samples) > 1e-6, "$voice $macros silent dry source")
            }
        }
    }

    @Test
    fun `every timbral macro moves every voice at matched level and event energy reaches the piston`() {
        val options = Undertow.ProbeOptions(durationSeconds = 1f)
        for (voice in UndertowVoice.entries) {
            val defaults = Undertow.defaults(voice)
            for (name in timbral) {
                val low = Undertow.probe(voice, defaults + (name to 0f), options = options)
                val high = Undertow.probe(voice, defaults + (name to 1f), options = options)
                assertTrue(matchedDifference(low.samples, high.samples) > 0.01, "$voice $name changed only gain or did nothing")
            }
        }
        val quiet = Undertow.probe(UndertowVoice.KNOCK, energy = 0.25f, options = options)
        val strong = Undertow.probe(UndertowVoice.KNOCK, energy = 1f, options = options)
        assertTrue(strong.snapshots.last().pistonWork > quiet.snapshots.last().pistonWork,
            "event energy did not reach the powered extraction")
        assertTrue(rms(strong.samples) > rms(quiet.samples) * 1.05, "event energy failed to increase raw gesture energy")
    }

    @Test
    fun `ordinary dry voices keep the requested root at every semitone from C3 through C5`() {
        var worst = 0.0
        for (voice in UndertowVoice.entries) for (step in 0..Undertow.TUNE_SEMITONES) {
            val tune = step / Undertow.TUNE_SEMITONES.toFloat()
            val probe = Undertow.probe(voice, mapOf("TUNE" to tune), options = Undertow.ProbeOptions(durationSeconds = 1.1f))
            val wanted = Undertow.frequencyFor(voice, tune)
            val actual = TestPitch.estimate(Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE), 0.2f, 0.35f)
            assertTrue(actual > 0f, "$voice TUNE $tune has no detected root")
            val cents = 1200 * ln(actual / wanted.toDouble()) / ln(2.0)
            worst = maxOf(worst, abs(cents))
            assertTrue(abs(cents) <= 10.0, "$voice TUNE $tune lost requested root: $actual Hz for $wanted Hz ($cents cents)")
        }
        println("UNDERTOW default root: worst $worst cents")
    }

    @Test
    fun `doubling the mechanical clock preserves pressure catch timing and pitch`() {
        val options = Undertow.ProbeOptions(durationSeconds = 0.9f, controlRateHz = 1000)
        for (voice in listOf(UndertowVoice.KNOCK, UndertowVoice.BREATH, UndertowVoice.SURGE)) {
            val slow = Undertow.probe(voice, options = options)
            val fast = Undertow.probe(voice, options = options.copy(controlRateHz = 2000))
            val pressureChange = abs(meanSuction(slow) - meanSuction(fast)) / meanSuction(fast).coerceAtLeast(1e-12)
            val slowCatch = slow.contacts.firstOrNull { it.chamber == 0 }?.timeSeconds ?: error("$voice 1k flap never caught")
            val fastCatch = fast.contacts.firstOrNull { it.chamber == 0 }?.timeSeconds ?: error("$voice 2k flap never caught")
            val catchChange = abs(slowCatch - fastCatch)
            val wanted = Undertow.frequencyFor(voice, Undertow.defaults(voice).getValue("TUNE"))
            val pitches = listOf(slow, fast).map { probe ->
                val measured = TestPitch.estimate(Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE), 0.2f, 0.35f)
                assertTrue(measured > 0f, "$voice clock comparison lost the requested root")
                1200 * ln(measured / wanted.toDouble()) / ln(2.0)
            }
            println("UNDERTOW $voice 1k/2k: pressure ${pressureChange * 100}% change, catch ${catchChange * 1000} ms, pitch $pitches cents")
            assertTrue(pressureChange < 0.05, "$voice reservoir depends on coarse integration: $pressureChange")
            assertTrue(catchChange <= 0.003f, "$voice rim timing depends on coarse integration: $catchChange s")
            assertTrue(pitches.all { abs(it) <= 10.0 }, "$voice clock change lost requested pitch: $pitches")
        }
    }

    @Test
    fun `high register rough excitation leaves little near Nyquist energy and no raw DC`() {
        val probe = Undertow.probe(
            UndertowVoice.FLUTTER, timbral.associateWith { 1f } + ("TUNE" to 1f),
            options = Undertow.ProbeOptions(durationSeconds = 1.1f),
        )
        val n = 32768
        val start = (0.25f * Dsp.RATE).toInt()
        val re = FloatArray(n) { i ->
            probe.samples[start + i] * (0.5 - 0.5 * cos(2 * PI * i / (n - 1))).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val binHz = Dsp.RATE.toDouble() / n
        val audible = (40 / binHz).toInt() until power.size
        val upper = (18000 / binHz).toInt() until power.size
        val upperShare = upper.sumOf { power[it] } / audible.sumOf { power[it] }.coerceAtLeast(1e-30)
        val rawRms = rms(probe.samples)
        val relativeDc = abs(probe.samples.average()) / rawRms.coerceAtLeast(1e-30)
        println("UNDERTOW high flutter: near-Nyquist share $upperShare, DC/RMS $relativeDc, raw RMS $rawRms")
        assertTrue(rawRms > 1e-6, "bright register converged to silence")
        assertTrue(upperShare < 0.002, "bright internal excitation escaped the decimation band: $upperShare")
        assertTrue(relativeDc < 0.01, "raw excitation accumulated DC relative to its own level: $relativeDc")
    }

    @Test
    fun `held suction closes both the audio and the complete coupled state in difficult cases`() {
        val cases = listOf(
            UndertowVoice.BREATH to mapOf("DRAW" to 0f, "LEAK" to 1f),
            UndertowVoice.SEAL to mapOf("DRAW" to 1f, "LEAK" to 0f, "FLAP" to 0.8f),
            UndertowVoice.FLUTTER to mapOf("TUNE" to 1f, "FLAP" to 1f, "SPIRAL" to 1f),
            UndertowVoice.SURGE to timbral.associateWith { 1f },
        )
        for ((voice, macros) in cases) {
            val held = Undertow.probe(voice, macros + ("HOLD" to 1f))
            assertFinite(held, "$voice held $macros")
            assertTrue(rms(held.samples) > 1e-5, "$voice held material converged to silence")
            assertTrue(held.previousCycle.isNotEmpty(), "$voice lacked a preceding cycle for convergence evidence")
            val measuredSeam = Keys.seamError(held.previousCycle + held.samples, held.previousCycle.size)
            assertEquals(measuredSeam, held.seamError, 1e-10, "$voice seam metric did not measure the returned dry material")
            assertTrue(held.seamError.isFinite() && held.seamError < Keys.MAX_SEAM_ERROR,
                "$voice held audio did not close: ${held.seamError}")
            assertTrue(held.cycleStateError.isFinite() && held.cycleStateError < 1e-3,
                "$voice pressure/flap/resonator state did not close: ${held.cycleStateError}")
            assertTrue(abs(held.samples.average()) < 0.01, "$voice held material contains DC")
        }
    }

    @Test
    fun `quiet high leakage held voices sustain the root without retriggering contacts`() {
        val macros = mapOf("DRAW" to 0f, "LEAK" to 1f, "HOLD" to 1f)
        for (voice in UndertowVoice.entries) {
            val held = Undertow.probe(voice, macros, energy = 0.25f)
            assertTrue(held.samples.all { it.isFinite() } && rms(held.samples) > 1e-5,
                "$voice quiet bypass cannot maintain useful raw suction material")
            val wanted = Undertow.frequencyFor(voice, Undertow.defaults(voice).getValue("TUNE"))
            val measured = TestPitch.estimate(Snip(held.samples, channels = 1, sampleRate = Dsp.RATE), 0.2f, 0.35f)
            assertTrue(measured > 0f, "$voice quiet bypass lost its maintained root")
            val cents = 1200 * ln(measured / wanted.toDouble()) / ln(2.0)
            assertTrue(abs(cents) <= 10.0, "$voice quiet held root shifted $cents cents")
            assertTrue(held.seamError.isFinite() && held.seamError < Keys.MAX_SEAM_ERROR,
                "$voice quiet held audio did not close: ${held.seamError}")
            assertTrue(held.cycleStateError.isFinite() && held.cycleStateError < 1e-3,
                "$voice quiet maintained state did not close: ${held.cycleStateError}")
            assertTrue(held.contacts.isEmpty(), "$voice maintenance repeatedly retriggered a ceramic catch")
            val exported = Undertow.render(voice, macros, velocity = 0.25f)
            assertTrue(exported.samples.all { it.isFinite() } && rms(exported.samples) > 1e-4,
                "$voice public quiet HOLD did not deliver its maintained material")
            println("UNDERTOW quiet $voice HOLD: RMS ${rms(held.samples)}, root $cents cents, seam ${held.seamError}, state ${held.cycleStateError}")
        }
        val silent = Undertow.render(UndertowVoice.BREATH, macros, velocity = 0f)
        assertEquals(0.0, rms(silent.samples), "zero event energy still powered maintenance suction")
    }

    @Test
    fun `normal exported voices have finite mono pitched tails and avoid drum routing`() {
        for (voice in UndertowVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val snip = Undertow.render(voice, mapOf("TUNE" to tune))
            assertEquals(1, snip.channels)
            assertEquals(Dsp.RATE, snip.sampleRate)
            assertTrue(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() })
            assertTrue(snip.samples.size <= (Undertow.MAX_SECONDS * Dsp.RATE).toInt())
            assertTrue(snip.samples.maxOf { abs(it) } <= 0.991f, "$voice exceeded shared peak ceiling")
            assertTrue(abs(snip.samples.average()) < 0.005, "$voice output contains DC")
            val classification = Classifier.classify(snip).drumClass
            assertTrue(classification !in drums, "$voice TUNE $tune classified as $classification")
            assertEquals(classification, Undertow.drumClassFor(voice, mapOf("TUNE" to tune)))
        }
    }
}
