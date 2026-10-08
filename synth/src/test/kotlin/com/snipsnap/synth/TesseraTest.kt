package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.json.JsonException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Numerical and causal gates accompany the dry, raw/matched listening audition. */
class TesseraTest {
    @Test
    fun `fixed seeds and reordered controls reproduce the same audio and collector order`() {
        val macros = linkedMapOf("MATERIAL" to 0.3f, "HAMMER" to 0.8f, "SCALE" to 0.6f, "FOLD" to 0.8f, "MOTION" to 0.7f)
        val options = Tessera.ProbeOptions(durationSeconds = 2f, seedContext = 41)
        val a = Tessera.probe(TesseraVoice.ANSWER, macros, 0.8f, options)
        val b = Tessera.probe(TesseraVoice.ANSWER, macros.entries.reversed().associate { it.key to it.value }, 0.8f, options)
        assertContentEquals(a.samples, b.samples)
        for (i in 0..2) {
            assertContentEquals(a.directMaterials[i], b.directMaterials[i])
            assertContentEquals(a.reexcitedMaterials[i], b.reexcitedMaterials[i])
        }
        assertEquals(a.collectorEvents, b.collectorEvents)
        assertEquals(a.hammerContacts, b.hammerContacts)
        assertEquals(a.geometry, b.geometry)
        assertTrue(a.collectorEvents.isNotEmpty(), "determinism case did not exercise the collectors")
        val differentSeed = Tessera.probe(TesseraVoice.ANSWER, macros, 0.8f, options.copy(seedContext = 42))
        assertFalse(a.samples.contentEquals(differentSeed.samples), "seed context never reached the instrument")
    }

    @Test
    fun `an unstruck finite object stays at rest even with all active controls high`() {
        val rest = Tessera.probe(
            TesseraVoice.FOLDING,
            mapOf("HAMMER" to 1f, "SCALE" to 1f, "FOLD" to 1f, "MOTION" to 1f),
            options = Tessera.ProbeOptions(primaryStrikeEnabled = false, durationSeconds = 1f),
        )
        assertTrue(rest.samples.all { it == 0f })
        assertTrue(rest.chamber.all { it == 0f })
        assertTrue(rest.directMaterials.all { samples -> samples.all { it == 0f } })
        assertTrue(rest.reexcitedMaterials.all { samples -> samples.all { it == 0f } })
        assertTrue(rest.collectorEvents.isEmpty())
        assertEquals(0.0, rest.finalPassiveEnergy)
        assertEquals(0.0, rest.wallWork)
        assertEquals(0, rest.recoveredStates)
    }

    @Test
    fun `each coupled material keeps the requested root across the note range`() {
        var worst = 0.0
        for (material in 0..2) for (tune in listOf(0f, 0.5f, 1f)) {
            val probe = Tessera.probe(
                TesseraVoice.ANSWER, mapOf("TUNE" to tune),
                options = Tessera.ProbeOptions(chamberEnabled = false, collectorsEnabled = false, isolatedMaterial = material, durationSeconds = 0.7f),
            )
            val samples = probe.directMaterials[material]
            assertTrue(rms(samples) > 1e-5, "material $material did not sound")
            val hz = Tessera.frequencyFor(TesseraVoice.ANSWER, tune)
            val cents = FineTuning.cents(FineTuning.measuredHz(samples, Dsp.RATE, hz, 0.04f, 0.4f), hz.toDouble())
            worst = max(worst, abs(cents))
            assertTrue(abs(cents) <= 10.0, "material $material TUNE $tune root is $cents cents off")
            assertTrue(rootShare(samples, hz) > 0.10, "material $material TUNE $tune has a weak apparent root")
        }
        println("TESSERA isolated coupled-material tuning: worst $worst cents")
    }

    @Test
    fun `hard contact reserves a bounded rebound within the original hammer energy`() {
        val options = Tessera.ProbeOptions(chamberEnabled = false, collectorsEnabled = false, durationSeconds = 0.1f)
        val soft = Tessera.probe(TesseraVoice.WOOD, mapOf("HAMMER" to 0f), options = options)
        val hard = Tessera.probe(TesseraVoice.WOOD, mapOf("HAMMER" to 1f), options = options)
        assertEquals(1, soft.hammerContacts.size)
        assertTrue(soft.hammerContacts.none { it.rebound })
        assertEquals(2, hard.hammerContacts.size)
        val initial = hard.hammerContacts.single { !it.rebound }
        val rebound = hard.hammerContacts.single { it.rebound }
        assertTrue(rebound.timeSeconds >= initial.timeSeconds + initial.durationSeconds, "rebound preceded separation from the initial contact")
        assertTrue(rebound.reservedEnergy > 0.0 && rebound.reservedEnergy < initial.reservedEnergy * 0.1, "rebound exhausted the finite gesture")
        assertTrue(rebound.timeSeconds + rebound.durationSeconds < 0.025f, "bounded contact escaped the hammer gesture")
        assertEquals(soft.primaryWorkBudget, hard.primaryWorkBudget, 1e-9, "hard contact created additional source energy")
        assertEquals(hard.primaryWorkBudget, hard.hammerContacts.sumOf { it.reservedEnergy }, 1e-9)
        for (probe in listOf(soft, hard)) {
            assertTrue(probe.primaryWork.isFinite() && probe.primaryWork > 0.0)
            assertTrue(probe.primaryWork <= probe.primaryWorkBudget + 1e-9, "hammer contacts exceeded their original work store")
            assertEquals(0, probe.recoveredStates)
        }
        println("TESSERA hammer rebound: delay ${rebound.timeSeconds} s, reserved ${rebound.reservedEnergy}, original source budget ${hard.primaryWorkBudget}, actual work ${hard.primaryWork}")
    }

    @Test
    fun `pressure receivers excite other materials and can be removed while chamber audio remains`() {
        val macros = mapOf("MATERIAL" to 0f, "HAMMER" to 0.75f, "SCALE" to 0.55f, "FOLD" to 0.85f, "MOTION" to 0f)
        val options = Tessera.ProbeOptions(durationSeconds = 2f)
        val full = Tessera.probe(TesseraVoice.ANSWER, macros, options = options)
        val muted = Tessera.probe(TesseraVoice.ANSWER, macros, options = options.copy(receiversEnabled = false))
        val noChamber = Tessera.probe(TesseraVoice.ANSWER, macros, options = options.copy(chamberEnabled = false))
        assertTrue(rms(full.chamber) > 1e-5, "the chamber radiated no traveling sound")
        assertTrue(rms(muted.chamber) > 1e-5, "muting receiving ports removed ordinary chamber audio")
        assertEquals(0.0, rms(noChamber.chamber))
        assertTrue(full.reexcitedMaterials.drop(1).any { rms(it) > 1e-5 }, "a wooden strike never excited another material")
        assertTrue(muted.reexcitedMaterials.all { rms(it) == 0.0 }, "a muted receiver still excited a material")
        assertTrue(noChamber.reexcitedMaterials.all { rms(it) == 0.0 }, "re-excitation occurred without a traveling path")
        assertTrue(muted.collectorEvents.isEmpty(), "muted receiving ports released a collector")
        assertTrue(full.collectorEvents.any { it.material != 0 }, "the collector never answered in another material")
        assertFalse(full.samples.contentEquals(muted.samples), "in-model re-excitation was inaudible")
    }

    @Test
    fun `static scale separates first arrivals and fold redistributes pressure among receivers`() {
        val options = Tessera.ProbeOptions(collectorsEnabled = false, durationSeconds = 1.2f)
        val compact = Tessera.probe(TesseraVoice.ANSWER, mapOf("SCALE" to 0f, "MOTION" to 0f), options = options)
        val spacious = Tessera.probe(TesseraVoice.ANSWER, mapOf("SCALE" to 1f, "MOTION" to 0f), options = options)
        val firstCompact = compact.arrivals.minOfOrNull { it.timeSeconds } ?: error("compact chamber had no arrival")
        val firstSpacious = spacious.arrivals.minOfOrNull { it.timeSeconds } ?: error("spacious chamber had no arrival")
        assertTrue(firstSpacious > firstCompact + 0.15f, "SCALE did not separate the first traveling return: $firstCompact -> $firstSpacious")
        assertTrue(compact.arrivals.all { it.delaySeconds >= 0.022f && it.delaySeconds <= 0.86f })
        assertTrue(spacious.arrivals.all { it.delaySeconds >= 0.022f && it.delaySeconds <= 0.86f })
        fun receiverShares(fold: Float): List<Double> {
            val probe = Tessera.probe(TesseraVoice.ANSWER, mapOf("FOLD" to fold, "MOTION" to 0f), options = options)
            val energy = probe.continuousReturns.map { samples -> samples.sumOf { it.toDouble() * it } }
            assertTrue(energy.sum() > 1e-8, "FOLD $fold delivered no receiving pressure")
            return energy.map { it / energy.sum() }
        }
        val open = receiverShares(0f)
        val folded = receiverShares(1f)
        val redistributed = open.zip(folded).sumOf { (a, b) -> abs(a - b) }
        assertTrue(redistributed > 0.05, "FOLD did not favor different receiving materials: $open -> $folded")
        println("TESSERA static geometry: first return $firstCompact -> $firstSpacious s, receiver shares $open -> $folded")
    }

    @Test
    fun `static passive extremes decay in raw audio and internal material state`() {
        val passive = Tessera.probe(
            TesseraVoice.CHAMBER,
            mapOf("MATERIAL" to 1f, "HAMMER" to 1f, "SCALE" to 1f, "FOLD" to 1f, "MOTION" to 0f),
            options = Tessera.ProbeOptions(collectorsEnabled = false, durationSeconds = 6f),
        )
        assertTrue(passive.samples.all { it.isFinite() })
        assertEquals(0, passive.recoveredStates, "a final limiter concealed nonfinite internal state")
        assertTrue(passive.rawPeak < 1f, "passive raw peak ${passive.rawPeak} exhausted its headroom")
        assertTrue(passive.collectorEvents.isEmpty())
        assertEquals(0.0, passive.wallWork)
        assertTrue(passive.maxPassiveEnergy > 0.0)
        val afterContact = passive.geometry.filter { it.timeSeconds >= 0.025f }
        assertTrue(afterContact.size > 2, "passivity check did not observe the unpowered tail")
        for ((a, b) in afterContact.zipWithNext()) {
            assertTrue(b.passiveEnergy <= a.passiveEnergy * 1.00001 + 1e-12, "passive material/chamber state gained energy at ${b.timeSeconds} s: ${a.passiveEnergy} -> ${b.passiveEnergy}")
        }
        assertTrue(passive.finalPassiveEnergy < passive.maxPassiveEnergy * 1e-4, "passive state did not ring down: ${passive.finalPassiveEnergy} / ${passive.maxPassiveEnergy}")
        val window = (0.1f * Dsp.RATE).roundToInt()
        val loudest = (0 until passive.samples.size / window).maxOf { rms(passive.samples, it * window, (it + 1) * window) }
        val final = rms(passive.samples, passive.samples.size - window, passive.samples.size)
        println("TESSERA passive: raw peak ${passive.rawPeak}, final/max state ${passive.finalPassiveEnergy / passive.maxPassiveEnergy}, final/loudest RMS ${final / loudest}")
        assertTrue(final < loudest * 0.01, "passive raw tail did not decay 40 dB in six seconds: $final / $loudest")
    }

    @Test
    fun `collectors release a finite energy budget with refractory time`() {
        val active = Tessera.probe(
            TesseraVoice.ANSWER,
            mapOf("HAMMER" to 1f, "SCALE" to 0.65f, "FOLD" to 1f, "MOTION" to 1f),
            options = Tessera.ProbeOptions(durationSeconds = 3f),
        )
        val events = active.collectorEvents
        assertTrue(events.isNotEmpty(), "the dense gesture never exercised a collector")
        assertTrue(events.size <= Tessera.MAX_COLLECTOR_RELEASES)
        assertTrue(events.zipWithNext().all { (a, b) -> b.timeSeconds >= a.timeSeconds })
        assertTrue(events.all { it.strength.isFinite() && it.strength > 0f && it.spentEnergy.isFinite() && it.spentEnergy > 0f && it.remainingBudget >= 0.0 })
        assertTrue(events.sumOf { it.spentEnergy.toDouble() } <= active.initialCollectorBudget + 1e-7, "collector strikes created fresh one-shot energy")
        assertTrue(active.remainingCollectorBudget in 0.0..active.initialCollectorBudget)
        for (material in 0..2) {
            for ((a, b) in events.filter { it.material == material }.zipWithNext()) {
                assertTrue(b.timeSeconds - a.timeSeconds >= Tessera.MIN_REFRACTORY_SECONDS - 1e-3f, "material $material retriggered inside its refractory interval")
            }
        }
        assertTrue(active.geometry.all { it.collectorEnergy.isFinite() && it.collectorEnergy >= 0.0 && it.releaseCount <= Tessera.MAX_COLLECTOR_RELEASES })
        assertEquals(0, active.recoveredStates)
        println("TESSERA one-shot collectors: ${events.size} releases, spent ${events.sumOf { it.spentEnergy.toDouble() }}, spring budget ${active.initialCollectorBudget}, remaining ${active.remainingCollectorBudget}")
    }

    @Test
    fun `zero motion is stationary and low motion changes geometry within the gesture`() {
        val options = Tessera.ProbeOptions(durationSeconds = 1.5f)
        val fixed = Tessera.probe(TesseraVoice.FOLDING, mapOf("MOTION" to 0f), options = options)
        val moving = Tessera.probe(TesseraVoice.FOLDING, mapOf("MOTION" to 0.1f), options = options)
        assertTrue(fixed.geometry.size > 2 && moving.geometry.size > 2)
        val rest = fixed.geometry.first()
        assertTrue(fixed.geometry.all { abs(it.size - rest.size) < 1e-7 && abs(it.fold - rest.fold) < 1e-7 && abs(it.velocity) < 1e-7 })
        assertEquals(0.0, fixed.wallWork)
        val initial = moving.geometry.first()
        assertTrue(moving.geometry.any { abs(it.size - initial.size) + abs(it.fold - initial.fold) > 1e-4 }, "low MOTION required a long cycle before any response")
        assertTrue(moving.wallWork > 0.0 && moving.wallWork.isFinite())
        assertTrue(moving.geometry.all { it.size.isFinite() && it.size in 0f..1f && it.fold.isFinite() && it.fold in 0f..1f && it.velocity.isFinite() && abs(it.velocity) <= 3.0 && it.passiveEnergy.isFinite() })
        assertTrue(relativeDifference(fixed.samples, moving.samples) > 0.005, "low wall motion had no audible authority")
        println("TESSERA MOTION .1: wall work ${moving.wallWork}, normalized displacement ${moving.geometry.maxOf { abs(it.size - initial.size) + abs(it.fold - initial.fold) }}")
    }

    @Test
    fun `each timbral macro moves every voice with a fixed structural seed`() {
        val options = Tessera.ProbeOptions(normalize = true, durationSeconds = 1.2f, seedContext = 23, recordDiagnostics = false)
        for (voice in TesseraVoice.entries) {
            val defaults = Tessera.defaults(voice)
            val base = Tessera.probe(voice, defaults, options = options).samples
            for (macro in listOf("MATERIAL", "HAMMER", "SCALE", "FOLD", "MOTION")) {
                val endpoint = if (defaults.getValue(macro) < 0.5f) 1f else 0f
                val changed = Tessera.probe(voice, defaults + (macro to endpoint), options = options).samples
                val difference = relativeDifference(base, changed)
                assertTrue(difference > 0.005, "$voice $macro changed normalized audio by only $difference")
                println("TESSERA $voice $macro normalized audio change $difference")
            }
        }
    }

    @Test
    fun `the required control pairs interact within the coupled instrument`() {
        val neutral = Tessera.macrosFor(TesseraVoice.ANSWER).associate { it.name to it.neutral }
        val pairs = listOf("MATERIAL" to "FOLD", "HAMMER" to "MOTION", "SCALE" to "MOTION", "SCALE" to "FOLD", "FOLD" to "MOTION")
        val options = Tessera.ProbeOptions(durationSeconds = 1.2f, seedContext = 23, recordDiagnostics = false)
        for ((a, b) in pairs) {
            val corners = listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (x, y) ->
                Tessera.probe(TesseraVoice.ANSWER, neutral + mapOf(a to x, b to y), options = options).samples
            }
            var mixed = 0.0
            var energy = 0.0
            for (i in corners[0].indices) {
                val delta = corners[3][i] - corners[2][i] - corners[1][i] + corners[0][i].toDouble()
                mixed += delta * delta
                energy += corners.maxOf { it[i].toDouble() * it[i] }
            }
            val interaction = sqrt(mixed / energy.coerceAtLeast(1e-30))
            assertTrue(interaction > 0.005, "$a × $b behaved independently: $interaction")
            println("TESSERA $a × $b interaction $interaction")
        }
    }

    @Test
    fun `all-high and actively moving extremes retain finite raw headroom at both note limits`() {
        for (tune in listOf(0f, 1f)) for (activeMotion in listOf(false, true)) {
            val high = Tessera.defaults(TesseraVoice.FOLDING).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f } + ("TUNE" to tune)
            val macros = if (activeMotion) high + mapOf("SCALE" to 0.65f, "FOLD" to 0.7f) else high
            val probe = Tessera.probe(TesseraVoice.FOLDING, macros, options = Tessera.ProbeOptions(durationSeconds = 2f))
            assertTrue(probe.samples.all { it.isFinite() })
            assertTrue(probe.rawPeak < 1f, "moving all-high TUNE $tune raw peak ${probe.rawPeak}")
            assertEquals(0, probe.recoveredStates, "moving all-high concealed an invalid internal state")
            assertTrue(probe.wallWork.isFinite() && probe.wallWork >= 0.0)
            assertTrue(probe.wallWork <= probe.wallWorkBudget + 1e-9, "walls exceeded their finite work budget")
            if (activeMotion) assertTrue(probe.wallWork > 0.0, "the moving extreme did not exercise powered walls")
            assertTrue(probe.geometry.all { it.passiveEnergy.isFinite() && it.passiveEnergy >= 0.0 })
            assertTrue(probe.collectorEvents.size <= Tessera.MAX_COLLECTOR_RELEASES)
            println("TESSERA all-high TUNE $tune activeMotion=$activeMotion: raw peak ${probe.rawPeak}, wall work ${probe.wallWork} / ${probe.wallWorkBudget}, max state ${probe.maxPassiveEnergy}")
        }
    }

    @Test
    fun `intermediate hold extends a finite gesture before selecting the loop contract`() {
        val options = Tessera.ProbeOptions(durationSeconds = 1.5f, recordDiagnostics = false)
        val struck = Tessera.probe(TesseraVoice.ANSWER, mapOf("HOLD" to 0f), options = options)
        val continued = Tessera.probe(TesseraVoice.ANSWER, mapOf("HOLD" to 0.5f), options = options)
        assertFalse(Tessera.isLoop(0.5f))
        assertEquals(DrumClass.TONAL, Tessera.drumClassFor(TesseraVoice.ANSWER, mapOf("HOLD" to 0.5f)))
        assertTrue(Tessera.isLoop(1f))
        assertEquals(DrumClass.LOOP, Tessera.drumClassFor(TesseraVoice.ANSWER, mapOf("HOLD" to 1f)))
        assertTrue(continued.previousCycle.isEmpty())
        assertTrue(continued.samples.all { it.isFinite() })
        assertTrue(continued.rawPeak < 1f)
        assertTrue(continued.primaryWork.isFinite() && continued.primaryWork > struck.primaryWork, "intermediate HOLD supplied no additional finite source work")
        assertTrue(continued.primaryWork <= continued.primaryWorkBudget + 1e-9)
        assertTrue(continued.wallWork <= continued.wallWorkBudget + 1e-9)
        assertTrue(relativeDifference(struck.samples, continued.samples) > 0.005, "intermediate HOLD was inactive")
    }

    @Test
    fun `held chamber preset converges across its complete state`() {
        val patch = TesseraPresets.all().single { it.name == "Held Chamber" }
        val held = Tessera.probe(patch.voice, patch.macros)
        println("TESSERA Held Chamber: complete state ${held.loopStateError}, groups ${held.loopStateErrors}, genuine seam ${held.seamError}")
        assertTrue(held.loopConverged, "Held Chamber did not converge: ${held.loopStateError}; groups ${held.loopStateErrors}")
        assertTrue(Tessera.withinTolerance(held.loopStateErrors), "groups outside tolerance: ${held.loopStateErrors}")
        assertEquals(held.samples.size, held.previousCycle.size)
        assertTrue(Keys.seamError(held.previousCycle + held.samples, held.previousCycle.size) < Keys.MAX_SEAM_ERROR)
        assertTrue(held.collectorEvents.isNotEmpty())
        assertTrue(held.samples.all { it.isFinite() })
        assertTrue(held.wallWork <= held.wallWorkBudget + 1e-9)
    }

    @Test
    fun `a quiet path whose pressure history diverges cannot hide behind its delay`() {
        // Copilot's case on #475: 158,764 history samples flip sign while the half-second delay stays put.
        // Scored as one norm with the delay, that read 0.16% and passed the 0.3% gate at any quietness.
        val samples = 158_764
        fun path(level: Double, sign: Double) = DoubleArray(samples + 3).also { p ->
            for (i in 0 until samples) p[i] = sign * level
            p[samples] = sign * level // the low-passed pressure follows the history
            p[samples + 1] = .5 // delay, seconds
            p[samples + 2] = .5 * level * level * samples // stored energy is sign-blind
        }
        val shared = List(11) { doubleArrayOf(1.0, 2.0) }
        for (level in listOf(1e-2, 1e-4, 1e-6)) {
            val steady = shared + List(6) { path(level, 1.0) }
            val flipped = shared + listOf(path(level, -1.0)) + List(5) { path(level, 1.0) }
            val errors = Tessera.stateErrors(steady, flipped)
            assertTrue(errors.getValue("path0.pressure") > Tessera.STATE_TOLERANCE, "a pressure history flipped at $level hid: $errors")
            assertTrue(errors.getValue("path0.filter") > Tessera.STATE_TOLERANCE, "a filter state flipped at $level hid: $errors")
            assertEquals(0.0, errors.getValue("path0.delay"))
            assertEquals(0.0, errors.getValue("path0.energy"))
            assertEquals(0.0, errors.getValue("path1.pressure"))
            assertTrue(Tessera.stateErrors(steady, steady).values.all { it == 0.0 }, "identical states must score zero")
        }
    }

    @Test
    fun `held neutral ANSWER transition converges across its complete state`() {
        val neutral = Tessera.macrosFor(TesseraVoice.ANSWER).associate { it.name to it.neutral }
        val held = Tessera.probe(TesseraVoice.ANSWER, neutral + ("HOLD" to 1f))
        println("TESSERA neutral ANSWER HOLD: complete state ${held.loopStateError}, groups ${held.loopStateErrors}, genuine seam ${held.seamError}")
        assertTrue(held.loopConverged, "neutral ANSWER HOLD did not converge: ${held.loopStateError}; groups ${held.loopStateErrors}")
        assertTrue(Tessera.withinTolerance(held.loopStateErrors), "groups outside tolerance: ${held.loopStateErrors}")
        assertEquals(0.0, held.loopStateErrors.getValue("sourcePhase"))
        assertEquals(0.0, held.loopStateErrors.getValue("eventGates"))
        assertEquals(0, held.samples.size * Dsp.OVERSAMPLE % 256)
        assertEquals(held.samples.size, held.previousCycle.size)
        assertTrue(Keys.seamError(held.previousCycle + held.samples, held.previousCycle.size) < Keys.MAX_SEAM_ERROR)
        assertTrue(relativeDifference(held.previousCycle, held.samples) < 0.05)
        assertTrue(held.collectorEvents.isNotEmpty())
        assertTrue(held.samples.all { it.isFinite() })
        assertTrue(held.primaryWork <= held.primaryWorkBudget + 1e-9)
        assertTrue(held.wallWork <= held.wallWorkBudget + 1e-9)
    }

    @Test
    fun `held material converges across a genuine preceding cycle`() {
        val timbral = listOf("MATERIAL", "HAMMER", "SCALE", "FOLD", "MOTION")
        val cases = TesseraVoice.entries.flatMap { voice ->
            listOf(
                Triple(voice, Tessera.defaults(voice) + ("HOLD" to 1f), true),
                Triple(voice, Tessera.defaults(voice) + timbral.associateWith { 1f } + ("HOLD" to 1f), false),
            )
        } + Triple(TesseraVoice.WOOD, mapOf("TUNE" to 0f, "HOLD" to 1f), true)
        for ((voice, macros, checkPitch) in cases) {
            val tag = if (macros["TUNE"] == 0f) "low" else if (checkPitch) "default" else "high"
            val label = "$voice $tag"
            val held = Tessera.probe(voice, macros)
            assertEquals(held.samples.size, held.previousCycle.size)
            assertTrue(held.samples.size > 256)
            assertEquals(0, held.samples.size * Dsp.OVERSAMPLE % 256, "$label held source and CONTROL clocks cannot repeat together")
            assertTrue(held.samples.all { it.isFinite() })
            assertTrue(held.rawPeak < 1f, "$label held raw peak ${held.rawPeak}")
            assertTrue(held.primaryWork <= held.primaryWorkBudget + 1e-9, "$label hammer/rebound contacts exceeded their powered source budget")
            assertEquals(0, held.recoveredStates)
            assertTrue(held.loopConverged, "$label complete held object did not converge: ${held.loopStateError}; groups ${held.loopStateErrors}")
            assertTrue(held.loopStateErrors.isNotEmpty(), "$label did not measure complete object convergence")
            assertTrue(Tessera.withinTolerance(held.loopStateErrors), "$label a held state group did not converge: ${held.loopStateErrors}")
            assertEquals(0.0, held.loopStateErrors.getValue("sourcePhase"), "$label source and CONTROL clock phase changed at the wrap")
            assertEquals(0.0, held.loopStateErrors.getValue("eventGates"), "$label collector count or hysteresis gate changed at the wrap")
            val seam = Keys.seamError(held.previousCycle + held.samples, held.previousCycle.size)
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "$label genuine previous-cycle seam $seam")
            assertEquals(seam, held.seamError, 1e-10)
            assertTrue(relativeDifference(held.previousCycle, held.samples) < 0.05, "$label preceding cycle had not settled")
            assertTrue(rms(held.samples) > 1e-5, "$label HOLD was silent")
            assertTrue(held.collectorEvents.isNotEmpty(), "$label HOLD did not exercise pressure answers")
            if (checkPitch) {
                val tune = macros["TUNE"] ?: Tessera.defaults(voice).getValue("TUNE")
                val root = Tessera.frequencyFor(voice, tune)
                val cents = FineTuning.cents(FineTuning.measuredHz(held.samples, Dsp.RATE, root, 0.05f, 0.4f), root.toDouble())
                assertTrue(abs(cents) < 30.0, "$label held material moved the root by $cents cents")
                assertTrue(rootShare(held.samples, root) > 0.10, "$label held material lost its fundamental energy")
            }
            println("TESSERA held $label: genuine seam $seam, complete state error ${held.loopStateError}, previous-cycle difference ${relativeDifference(held.previousCycle, held.samples)}, raw peak ${held.rawPeak}")
            for (material in 0..2) {
                val events = held.collectorEvents.filter { it.material == material }
                if (events.isNotEmpty()) {
                    val seconds = held.samples.size.toFloat() / Dsp.RATE
                    val gap = seconds - events.last().timeSeconds + events.first().timeSeconds
                    assertTrue(gap >= Tessera.MIN_REFRACTORY_SECONDS - 1e-3f, "$label material $material retriggered across the wrap")
                }
            }
        }
    }

    @Test
    fun `every voice stores the same controls and recipes preserve the mixed instrument`() {
        val controls = setOf("TUNE", "MATERIAL", "HAMMER", "SCALE", "FOLD", "MOTION", "HOLD")
        for (voice in TesseraVoice.entries) {
            val defaults = Tessera.defaults(voice)
            assertEquals(controls, Tessera.macrosFor(voice).map { it.name }.toSet())
            assertEquals(0f, defaults.getValue("HOLD"))
            assertTrue(Tessera.macrosFor(voice).all { it.default in 0f..1f && it.neutral in 0f..1f })
            val patch = TesseraPatch("Test ${voice.name}", voice, defaults)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val edited = Patches.edited(patch, "Edited", defaults + ("FOLD" to 0.73f))
            assertEquals(patch.copy(name = "Edited", macros = defaults + ("FOLD" to 0.73f)), edited)
            assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
            assertTrue(Presets.forVoice("TESSERA", voice.name).isNotEmpty(), "$voice has no preset")
        }
        assertEquals(8, TesseraPresets.all().size)
        assertEquals(8, TesseraPresets.all().map { it.name }.toSet().size)
        assertFailsWith<IllegalArgumentException> {
            TesseraPatch("Bad", TesseraVoice.WOOD, mapOf("MOTION" to Float.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            TesseraPatch("Bad", TesseraVoice.WOOD, mapOf("UNKNOWN" to 0.5f))
        }
        assertFailsWith<JsonException> {
            TesseraPatch.fromJsonText("""{"engine":"TESSERA","version":99,"name":"Bad","voice":"WOOD","macros":{}}""")
        }
    }

    @Test
    fun `the pitched kit is dry and regenerates from typed recipes`() {
        val kit = SynthKits.tessera()
        assertEquals(16, kit.size)
        for ((i, nullablePad) in kit.withIndex()) {
            val pad = requireNotNull(nullablePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            assertTrue(recipe.patch is TesseraPatch, "pad $i lost its typed TESSERA recipe")
            assertEquals(null, recipe.fx, "pad $i changed the dry instrument")
            val patch = recipe.patch as TesseraPatch
            assertEquals(Tessera.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            if (i == 0 || i == kit.lastIndex) assertContentEquals(pad.snip.samples, recipe.render().samples)
        }
        assertTrue(kit.take(8).map { pad ->
            val patch = PadRecipe.fromJsonValue(requireNotNull(requireNotNull(pad).recipe)).patch as TesseraPatch
            patch.macros.getValue("TUNE")
        }.toSet().size >= 5, "the pitched kit did not span its scale")
    }

    @Test
    fun `host velocity supplies event energy without rewriting hammer character`() {
        val patch = TesseraPatch("Quiet Hammer", TesseraVoice.COURSE, mapOf("HAMMER" to 0.8f, "FOLD" to 0.6f))
        val host = Velocity.atVelocity(patch, 0.35f)
        val direct = Tessera.render(patch.voice, patch.macros, velocity = 0.35f)
        assertContentEquals(direct.samples, host.samples)
        assertEquals(0.8f, patch.macros.getValue("HAMMER"))
        assertTrue(Tessera.render(TesseraVoice.ANSWER, velocity = 0f).samples.all { it == 0f })
    }

    @Test
    fun `standard voices remain mono finite pitched and outside percussion guards`() {
        val cases = TesseraVoice.entries.map { it to Tessera.defaults(it) } + listOf(
            TesseraVoice.WOOD to (Tessera.defaults(TesseraVoice.WOOD) + ("TUNE" to 0f)),
            TesseraVoice.TUBE to (Tessera.defaults(TesseraVoice.TUBE) + ("TUNE" to 1f)),
        )
        for ((voice, macros) in cases) {
            val snip = Tessera.render(voice, macros)
            assertEquals(1, snip.channels)
            assertEquals(Dsp.RATE, snip.sampleRate)
            assertTrue(snip.samples.all { it.isFinite() }, "$voice $macros has nonfinite samples")
            assertTrue(snip.peak() in 0.01f..0.991f, "$voice peak ${snip.peak()}")
            assertTrue(abs(snip.samples.average()) < 0.005, "$voice DC ${snip.samples.average()}")
            assertTrue(snip.durationSeconds in 1f..10f, "$voice duration ${snip.durationSeconds}")
            val classified = Classifier.classify(snip).drumClass
            assertTrue(classified !in DRUMS, "$voice classified as $classified")
            assertTrue(Tessera.drumClassFor(voice, macros) !in DRUMS, "$voice stored as percussion")
            val root = Tessera.frequencyFor(voice, macros.getValue("TUNE"))
            val detected = Pitch.detect(snip) ?: error("$voice has no detected full-mix root")
            val cents = FineTuning.cents(detected.hz.toDouble(), root.toDouble())
            assertTrue(abs(cents) < 50.0, "$voice moderate full mix moved the root by $cents cents")
            assertTrue(rootShare(snip.samples, root) > 0.10, "$voice has too little fundamental energy")
            println("TESSERA $voice TUNE ${macros.getValue("TUNE")}: full-mix root $cents cents, peak ${snip.peak()}, DC ${snip.samples.average()}")
        }
    }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var energy = 0.0
        for (i in from until to) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (to - from).coerceAtLeast(1))
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        val size = minOf(a.size, b.size)
        var difference = 0.0
        var energy = 0.0
        for (i in 0 until size) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            difference += (x - y) * (x - y)
            energy += max(x * x, y * y)
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    /** A narrow pitch estimator alone can report a root that carries negligible energy. */
    private fun rootShare(samples: FloatArray, rootHz: Float): Double {
        val n = 32768
        val from = (0.05f * Dsp.RATE).roundToInt()
        val count = minOf((0.4f * Dsp.RATE).roundToInt(), samples.size - from)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = (samples[from + i] * (0.5 - 0.5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(re, im)
        var fundamental = 0.0
        var total = 0.0
        val halfWidth = max(5.0, rootHz * 0.04)
        for (k in 1 until n / 2) {
            val hz = k.toDouble() * Dsp.RATE / n
            val energy = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            total += energy
            if (abs(hz - rootHz) < halfWidth) fundamental += energy
        }
        return fundamental / total.coerceAtLeast(1e-30)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
