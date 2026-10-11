package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raw component and mechanical probes complement the published listening audition. */
class NimbusTest {
    private val drums = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
    )
    private val timbralControls = listOf("EXCITE", "SPACING", "HEIGHT", "FIELD", "FUNNEL")

    @Test
    fun `seeded excitation reproduces the whole stack and changing the seed changes sound`() {
        val macros = Nimbus.defaults(NimbusVoice.GATHER) + mapOf("EXCITE" to .8f, "SPACING" to .2f)
        val options = Nimbus.ProbeOptions(durationSeconds = 1f, seedContext = 37)
        val a = Nimbus.probe(NimbusVoice.GATHER, macros, .8f, options)
        val b = Nimbus.probe(NimbusVoice.GATHER, macros, .8f, options)
        assertContentEquals(a.samples, b.samples)
        for (i in 0 until 6) assertContentEquals(a.cymbals[i], b.cymbals[i])
        assertContentEquals(a.funnel, b.funnel)
        assertContentEquals(a.contact, b.contact)
        assertEquals(a.initialEnergy, b.initialEnergy)
        assertEquals(a.finalPassiveEnergy, b.finalPassiveEnergy)
        val other = Nimbus.probe(NimbusVoice.GATHER, macros, .8f, options.copy(seedContext = 38))
        assertFalse(a.samples.contentEquals(other.samples), "seed context never reached the stack")
    }

    @Test
    fun `levitation with no event or powered drive stays silent`() {
        val options = Nimbus.ProbeOptions(primaryStrikeEnabled = false, poweredDriveEnabled = false, durationSeconds = 1f)
        val rest = Nimbus.probe(
            NimbusVoice.CONTACT,
            mapOf("EXCITE" to 1f, "SPACING" to 0f, "HEIGHT" to 0f, "FIELD" to 1f, "FUNNEL" to 1f),
            options = options,
        )
        assertEquals(0.0, rms(rest.samples))
        for (tap in rest.cymbals) assertEquals(0.0, rms(tap))
        assertEquals(0.0, rms(rest.funnel))
        assertEquals(0.0, rms(rest.contact))
        assertEquals(0.0, rms(rest.returns))
        assertEquals(0.0, rest.initialEnergy)
        assertEquals(0.0, rest.finalPassiveEnergy)
        assertTrue(rest.bodyTransfers.isEmpty(), "an unexcited field scheduled a new metal response")
        assertTrue(rest.snapshots.all { state -> state.bodyReservoirEnergy.all { it == 0.0 } },
            "an unexcited field charged a hidden body reservoir")
    }

    @Test
    fun `all six isolated metal characters retain one requested principal and distinct spectra`() {
        val options = Nimbus.ProbeOptions(
            couplingEnabled = false, funnelEnabled = false, contactsEnabled = false, durationSeconds = .7f,
        )
        var worst = 0.0
        val signatures = mutableListOf<DoubleArray>()
        for (tune in floatArrayOf(0f, .5f, 1f)) {
            for (cymbal in 0 until 6) {
                val p = Nimbus.probe(NimbusVoice.RING, mapOf("TUNE" to tune), options = options.copy(selectedCymbal = cymbal))
                val tap = p.cymbals[cymbal]
                assertTrue(rms(tap) > 1e-5, "isolated cymbal $cymbal is silent at TUNE $tune")
                for (other in 0 until 6) if (other != cymbal) {
                    assertEquals(0.0, rms(p.cymbals[other]), "isolated $cymbal also excited $other")
                }
                val cents = FineTuning.cents(
                    FineTuning.measuredHz(tap, Dsp.RATE, p.rootHz.toFloat(), .05f, .35f), p.rootHz.toDouble(),
                )
                worst = max(worst, abs(cents))
                assertTrue(abs(cents) <= 10.0, "cymbal $cymbal TUNE $tune principal is $cents cents off root")
                if (tune == .5f) signatures += spectrum(tap, minimumHz = p.rootHz * 1.8)
            }
        }
        var closest = Double.POSITIVE_INFINITY
        for (i in signatures.indices) for (j in 0 until i) {
            val difference = spectralDifference(signatures[i], signatures[j])
            closest = minOf(closest, difference)
            assertTrue(difference > .35, "isolated upper-mode spectra $j and $i collapsed: $difference")
        }
        println("NIMBUS isolated roots: worst $worst cents; closest normalized spectra $closest")
    }

    @Test
    fun `ordinary metal retains substantial upper bands through attack and body`() {
        // These anti-regression bounds reject the original 98–100% common-root bell.
        // They measure spectral substance; the owner's listening verdict remains separate.
        val deficient = mutableListOf<String>()
        for (voice in NimbusVoice.entries) for (tune in floatArrayOf(0f, .5f, 1f)) {
            val p = Nimbus.probe(voice, mapOf("TUNE" to tune), options = Nimbus.ProbeOptions(durationSeconds = .8f, recordDiagnostics = false))
            val attack = bands(p.samples, p.rootHz.toDouble(), .015, .15)
            val body = bands(p.samples, p.rootHz.toDouble(), .18, .65)
            println("NIMBUS $voice TUNE $tune spectral substance: attack root=${attack.rootShare} upper=${attack.upperShare} bands=${attack.upperOccupied}, body root=${body.rootShare} upper=${body.upperShare} bands=${body.upperOccupied}")
            if (attack.upperShare < .20 || body.upperShare < .10 || attack.upperOccupied < 4 || body.upperOccupied < 3) {
                deficient += "$voice TUNE $tune attack upper=${attack.upperShare}/bands=${attack.upperOccupied}, body upper=${body.upperShare}/bands=${body.upperOccupied}"
            }
            if (body.rootShare < .01) deficient += "$voice TUNE $tune lost material requested-root energy: ${body.rootShare}"
            val cents = FineTuning.cents(FineTuning.measuredHz(p.samples, Dsp.RATE, p.rootHz, .08f, .4f), p.rootHz.toDouble())
            println("NIMBUS $voice TUNE $tune material principal: $cents cents")
            if (abs(cents) > 10.0) deficient += "$voice TUNE $tune material principal moved $cents cents"
        }
        assertTrue(deficient.isEmpty(), "Metal upper bands disappeared or collapsed into a sparse common-root bell: ${deficient.joinToString("; ")}")
    }

    @Test
    fun `default voices keep contrasting metal spectra and temporal profiles`() {
        val probes = NimbusVoice.entries.associateWith { voice ->
            Nimbus.probe(voice, options = Nimbus.ProbeOptions(durationSeconds = 1.5f, recordDiagnostics = false))
        }
        val attacks = probes.mapValues { (_, p) -> bands(p.samples, p.rootHz.toDouble(), .015, .15) }
        val bodies = probes.mapValues { (_, p) -> bands(p.samples, p.rootHz.toDouble(), .18, .65) }
        val envelopes = probes.mapValues { (_, p) -> envelope(p.samples) }
        val deficient = mutableListOf<String>()
        var leastContrast = Double.POSITIVE_INFINITY
        for ((i, a) in NimbusVoice.entries.withIndex()) for (b in NimbusVoice.entries.take(i)) {
            val attack = spectralDifference(attacks.getValue(a).profile, attacks.getValue(b).profile)
            val body = spectralDifference(bodies.getValue(a).profile, bodies.getValue(b).profile)
            val temporal = spectralDifference(envelopes.getValue(a), envelopes.getValue(b))
            val contrast = maxOf(attack, body, temporal)
            leastContrast = minOf(leastContrast, contrast)
            println("NIMBUS $a/$b default contrast: attack=$attack body=$body envelope=$temporal")
            if (max(attack, body) < .20 || contrast < .30) deficient += "$a/$b attack=$attack body=$body envelope=$temporal"
        }
        println("NIMBUS minimum default voice contrast: $leastContrast")
        val highContrast = attacks.getValue(NimbusVoice.SHIMMER).highShare /
            attacks.getValue(NimbusVoice.THROAT).highShare.coerceAtLeast(1e-30)
        if (highContrast <= 1.5) deficient += "SHIMMER/THROAT high-band contrast=$highContrast"
        val shimmer = probes.getValue(NimbusVoice.SHIMMER).samples
        val suspend = probes.getValue(NimbusVoice.SUSPEND).samples
        fun bodySupport(samples: FloatArray) = rms(samples, (1f * Dsp.RATE).roundToInt(), (1.4f * Dsp.RATE).roundToInt()) /
            rms(samples, (.015f * Dsp.RATE).roundToInt(), (.15f * Dsp.RATE).roundToInt()).coerceAtLeast(1e-30)
        val supportContrast = bodySupport(suspend) / bodySupport(shimmer).coerceAtLeast(1e-30)
        println("NIMBUS intended character contrast: SHIMMER/THROAT high-band=$highContrast, SUSPEND/SHIMMER relative late-body=$supportContrast")
        if (supportContrast <= 1.5) deficient += "SUSPEND/SHIMMER relative late-body contrast=$supportContrast"
        assertTrue(deficient.isEmpty(), "Default voices collapsed or lost their intended contrast: ${deficient.joinToString("; ")}")
    }

    @Test
    fun `one metal attack grows into several substantial native cymbal contributions`() {
        // Shares exclude the shared principal, retain the actual simultaneous tap levels,
        // and inspect the audible body rather than a nearly silent late tail. These bounds
        // reject the c95 single-plate body; they do not replace the listening audition.
        val deficient = mutableListOf<String>()
        for (voice in NimbusVoice.entries) for (tune in floatArrayOf(0f, .5f, 1f)) {
            val label = "$voice TUNE $tune"
            val p = Nimbus.probe(voice, mapOf("TUNE" to tune), options = Nimbus.ProbeOptions(durationSeconds = 1.6f, snapshotStride = 65536))
            val onset = DoubleArray(6) { bands(p.cymbals[it], p.rootHz.toDouble(), 0.0, .010, upperCutoff = 1.35).upperPower }
            val selected = onset.indices.maxBy { onset[it] }
            val onsetOthers = 1.0 - onset[selected] / onset.sum().coerceAtLeast(1e-30)
            val body = ensemble(p, selected, .3, 1.5)
            // CONTACT deliberately has a shorter upper tail. Require useful neighbor level
            // in the audible gathering body, then assess later balance separately.
            val audible = ensemble(p, selected, .08, .6)
            val windows = listOf(.06 to .3, .3 to .8, .8 to 1.5).map { (start, end) -> ensemble(p, selected, start, end) }
            val strongestShare = DoubleArray(6) { plate -> windows.maxOf { it.shares[plate] } }
            println("NIMBUS $label ensemble: selected=$selected onsetOtherShare=$onsetOthers bodyShares=${body.shares.contentToString()} effective=${body.effectiveCount} otherFive/fullUpper=${body.otherToFullUpper} otherFive/attack=${body.otherToAttack} audibleBody/attack=${audible.otherToAttack} peakWindowShares=${strongestShare.contentToString()}")
            for ((index, window) in windows.withIndex()) println("NIMBUS $label ensemble window $index shares=${window.shares.contentToString()} effective=${window.effectiveCount} otherFive/fullUpper=${window.otherToFullUpper}")
            if (onsetOthers > .10) deficient += "$label seeded a simultaneous broad attack: other upper share=$onsetOthers"
            if (body.effectiveCount <= 2.0 || body.shares.max() >= .70 || body.shares.count { it >= .05 } < 3) {
                deficient += "$label body remains one plate: shares=${body.shares.contentToString()} effective=${body.effectiveCount}"
            }
            if (body.otherToFullUpper <= .35 || audible.otherToAttack <= .03) {
                deficient += "$label neighbors are too quiet at whole-mix gain: laterOtherFive/fullUpper=${body.otherToFullUpper}, audibleBody/attack=${audible.otherToAttack}"
            }
            if (1.0 - body.shares[selected] - onsetOthers <= .25) {
                deficient += "$label has no substantial growing neighbor body: onsetOtherShare=$onsetOthers bodyOtherShare=${1.0 - body.shares[selected]}"
            }
            for (plate in 0 until 6) if (strongestShare[plate] <= .005) {
                deficient += "$label plate $plate never contributes substantial native upper energy: peakWindowShare=${strongestShare[plate]}"
            }
            val contributors = body.shares.indices.sortedByDescending { body.shares[it] }.take(3)
            val signatures = contributors.associateWith { plate -> spectrum(p.cymbals[plate], p.rootHz * 1.35, .15) }
            for ((index, plate) in contributors.withIndex()) for (other in contributors.take(index)) {
                val contrast = spectralDifference(signatures.getValue(plate), signatures.getValue(other))
                println("NIMBUS $label connected material $plate/$other upper contrast=$contrast")
                if (contrast <= .35) deficient += "$label connected material $plate/$other collapsed into the same upper spectrum: $contrast"
            }
        }
        assertTrue(deficient.isEmpty(), "The gathering body lost the audible ensemble: ${deficient.joinToString("; ")}")
    }

    @Test
    fun `settled powered hold preserves actual six cymbal upper participation`() {
        val deficient = mutableListOf<String>()
        for (voice in NimbusVoice.entries) {
            val p = Nimbus.probe(voice, mapOf("HOLD" to 1f), options = Nimbus.ProbeOptions(snapshotStride = 65536))
            val end = p.samples.size.toDouble() / Dsp.RATE
            val powers = DoubleArray(6) { bands(p.cymbals[it], p.rootHz.toDouble(), 0.0, end, upperCutoff = 1.35).upperPower }
            val selected = powers.indices.maxBy { powers[it] }
            val held = ensemble(p, selected, 0.0, end)
            println("NIMBUS $voice HOLD ensemble: shares=${held.shares.contentToString()} effective=${held.effectiveCount} otherFive/fullUpper=${held.otherToFullUpper}")
            if (held.effectiveCount <= 2.0 || held.shares.max() >= .70 || held.shares.min() <= .01 || held.otherToFullUpper <= .45) {
                deficient += "$voice held ensemble collapsed: shares=${held.shares.contentToString()} effective=${held.effectiveCount} otherFive/fullUpper=${held.otherToFullUpper}"
            }
            if (bands(p.samples, p.rootHz.toDouble(), 0.0, end).upperShare < .15) deficient += "$voice held upper material became too quiet"
            if (p.seamError >= 1e-3) deficient += "$voice held participation is not continuous: seam=${p.seamError}"
            if (p.bodyTransfers.isNotEmpty()) deficient += "$voice repeatedly schedules gathering attacks inside settled HOLD"
        }
        assertTrue(deficient.isEmpty(), "Powered HOLD lost the settled ensemble: ${deficient.joinToString("; ")}")
    }

    @Test
    fun `a selected strike reaches silent neighbors through the coupled structure`() {
        val options = Nimbus.ProbeOptions(funnelEnabled = false, contactsEnabled = false, selectedCymbal = 0, durationSeconds = 1.6f)
        val macros = mapOf("SPACING" to .1f, "FIELD" to .7f)
        val isolated = Nimbus.probe(NimbusVoice.RING, macros, options = options.copy(couplingEnabled = false))
        val coupled = Nimbus.probe(NimbusVoice.RING, macros, options = options)
        assertTrue(rms(coupled.cymbals[0]) > 1e-5)
        for (i in 1 until 6) {
            assertEquals(0.0, rms(isolated.cymbals[i]), "neighbor $i was directly seeded")
            assertTrue(rms(coupled.cymbals[i]) > 1e-7, "neighbor $i never answered")
        }
        val sourceStart = firstAudible(coupled.cymbals[0])
        val remoteStart = firstAudible(coupled.cymbals[5])
        assertTrue(remoteStart > sourceStart, "a remote plate answered before finite transfer: $sourceStart -> $remoteStart")
        assertTrue(relativeDifference(coupled.samples, isolated.samples) > .03, "sympathetic transfer is inaudible")
        val body = ensemble(coupled, 0, .3, 1.5)
        println("NIMBUS source-only causal ensemble: shares=${body.shares.contentToString()} effective=${body.effectiveCount} otherFive/fullUpper=${body.otherToFullUpper} otherFive/attack=${body.otherToAttack}")
        assertTrue(body.effectiveCount > 2.0 && body.shares.max() < .70 && body.shares.count { it >= .05 } >= 3,
            "coupling reaches modal states but not several audible native upper bodies: ${body.shares.contentToString()}")
        val audible = ensemble(coupled, 0, .08, .6)
        assertTrue(body.otherToFullUpper > .35 && audible.otherToAttack > .03,
            "causal upper neighbors remain below the whole-mix listening level: later ${body.otherToFullUpper}, audible body ${audible.otherToAttack}")
        for (plate in 1 until 6) assertTrue(body.shares[plate] > .005,
            "causal neighbor $plate has no meaningful native upper contribution: ${body.shares[plate]}")
    }

    @Test
    fun `source specific funnel pressure returns to the metal without neighboring links`() {
        val macros = mapOf("HEIGHT" to 0f, "FUNNEL" to 1f)
        val options = Nimbus.ProbeOptions(couplingEnabled = false, contactsEnabled = false, selectedCymbal = 2, durationSeconds = .8f)
        val enclosed = Nimbus.probe(NimbusVoice.THROAT, macros, options = options)
        val dry = Nimbus.probe(NimbusVoice.THROAT, macros, options = options.copy(funnelEnabled = false))
        assertTrue(rms(enclosed.funnel) > 1e-7, "funnel stored no pressure")
        assertTrue(rms(enclosed.returns) > 1e-7, "funnel pressure did not return to metal")
        assertEquals(0.0, rms(dry.funnel))
        assertEquals(0.0, rms(dry.returns))
        assertTrue(normalizedDifference(enclosed.samples, dry.samples) > .02, "chamber return was inaudible")
        assertTrue(enclosed.finalPassiveEnergy <= enclosed.initialEnergy, "passive pressure return created energy")
    }

    @Test
    fun `an extreme finite event loses acoustic and mechanical energy without powered sustain`() {
        val p = Nimbus.probe(
            NimbusVoice.GATHER,
            mapOf("EXCITE" to 1f, "SPACING" to 0f, "HEIGHT" to 0f, "FIELD" to 1f, "FUNNEL" to 1f, "HOLD" to 0f),
            options = Nimbus.ProbeOptions(durationSeconds = 6f),
        )
        assertTrue(p.initialEnergy > 0.0, "event stored no energy")
        assertEquals(0.0, p.poweredDriveWork, "one-shot levitation supplied sustain energy")
        assertTrue(p.bodyTransfers.any { it.cymbal != p.selectedCymbal && it.injectedEnergy > 0.0 },
            "finite field energy never paid for a neighboring native body")
        assertTrue(p.bodyTransfers.all { event ->
            event.timeSeconds > 0f && event.gap.isFinite() && event.gap > 0f &&
                event.reservedEnergy.isFinite() && event.reservedEnergy > 0.0 &&
                event.injectedEnergy.isFinite() && event.injectedEnergy >= 0.0 &&
                event.injectedEnergy <= event.reservedEnergy * (1 + 1e-10) + 1e-14
        }, "a native body release exceeded its reserved passive work or escaped real geometry")
        assertTrue(p.samples.all { it.isFinite() })
        assertTrue(p.rawPeak < 1f, "raw extreme peak ${p.rawPeak} escaped its headroom")
        assertTrue(p.snapshots.isNotEmpty())
        assertTrue(p.snapshots.all {
            it.passiveEnergy.isFinite() && it.passiveEnergy >= 0.0 &&
                it.modalEnergy.isFinite() && it.mechanicalEnergy.isFinite() &&
                it.controllerWork.isFinite() && it.geometryValid
        }, "an internal state escaped its energy or geometry bounds")
        val maximumEnergy = p.snapshots.maxOf { it.passiveEnergy }
        assertTrue(maximumEnergy <= p.initialEnergy * 1.02, "passive state created energy: $maximumEnergy from ${p.initialEnergy}")
        assertTrue(p.finalPassiveEnergy < p.initialEnergy * 1e-4, "energy persisted without HOLD: ${p.finalPassiveEnergy}/${p.initialEnergy}")
        val window = (.1f * Dsp.RATE).roundToInt()
        val loudest = (0 until p.samples.size / window).maxOf { rms(p.samples, it * window, (it + 1) * window) }
        val end = rms(p.samples, p.samples.size - window, p.samples.size)
        val decayDb = 20 * log10(loudest / end.coerceAtLeast(1e-30))
        assertTrue(decayDb > 40.0, "raw finite resonance decayed only $decayDb dB")
        val displacement = p.snapshots.map(::displacement)
        assertTrue(displacement.max() > 1e-4, "excitation did not yield the suspension")
        assertTrue(displacement.last() < displacement.max() * .02, "stack did not gather back to rest")
        assertEquals(0f, p.snapshots.last().contactPenetration, "restored stack still penetrated a rim")
        println("NIMBUS passive extreme: $decayDb dB decay, energy ${p.finalPassiveEnergy}/${p.initialEnergy}")
    }

    @Test
    fun `field changes recovery and every height spacing corner keeps an ordered stack`() {
        val options = Nimbus.ProbeOptions(durationSeconds = 1.5f)
        val macros = mapOf("EXCITE" to 1f, "SPACING" to .4f, "HEIGHT" to .5f)
        val yielding = Nimbus.probe(NimbusVoice.GATHER, macros + ("FIELD" to 0f), options = options)
        val firm = Nimbus.probe(NimbusVoice.GATHER, macros + ("FIELD" to 1f), options = options)
        val softRecovery = yielding.snapshots.filter { it.timeSeconds in .6f..1.4f }.map(::displacement).average()
        val firmRecovery = firm.snapshots.filter { it.timeSeconds in .6f..1.4f }.map(::displacement).average()
        assertTrue(softRecovery > 1e-5, "yielding case never exercised suspension recovery")
        assertTrue(firmRecovery < softRecovery * .8, "FIELD did not restore faster: soft $softRecovery, firm $firmRecovery")
        for (height in floatArrayOf(0f, 1f)) for (spacing in floatArrayOf(0f, 1f)) {
            val p = Nimbus.probe(
                NimbusVoice.CONTACT,
                mapOf("EXCITE" to 1f, "HEIGHT" to height, "SPACING" to spacing, "FIELD" to 0f, "FUNNEL" to 1f),
                options = Nimbus.ProbeOptions(durationSeconds = 1f),
            )
            for (snapshot in p.snapshots) {
                assertTrue(snapshot.geometryValid, "HEIGHT $height SPACING $spacing invalid at ${snapshot.timeSeconds}")
                assertTrue(snapshot.heights.all { it.isFinite() })
                assertTrue((1 until snapshot.heights.size).all { i -> snapshot.heights[i] > snapshot.heights[i - 1] }, "plates crossed at HEIGHT $height SPACING $spacing")
            }
        }
    }

    @Test
    fun `fine contact requires actual close rim motion and wide stacks remain collision free`() {
        val macros = mapOf("EXCITE" to 1f, "SPACING" to 0f, "FIELD" to .5f)
        val options = Nimbus.ProbeOptions(durationSeconds = 1f)
        val close = Nimbus.probe(NimbusVoice.CONTACT, macros, options = options)
        val muted = Nimbus.probe(NimbusVoice.CONTACT, macros, options = options.copy(contactsEnabled = false))
        val wide = Nimbus.probe(NimbusVoice.CONTACT, macros + ("SPACING" to 1f), options = options)
        assertTrue(close.contactEvents > 0 && close.peakContactPenetration > 0f, "close case never exercised rim contact")
        assertTrue(rms(close.contact) > 1e-7, "actual contact emitted no sound")
        assertTrue(close.snapshots.all { it.contactPenetration.isFinite() && it.contactPenetration >= 0f })
        assertEquals(0, muted.contactEvents)
        assertEquals(0.0, rms(muted.contact))
        assertEquals(0, wide.contactEvents, "wide plates collided")
        assertEquals(0f, wide.peakContactPenetration)
        assertEquals(0.0, rms(wide.contact))
        assertTrue(normalizedDifference(close.samples, muted.samples) > .01, "rim contact made no audible contribution")
        val ordinary = Nimbus.probe(NimbusVoice.CONTACT, options = options)
        val ordinaryMuted = Nimbus.probe(NimbusVoice.CONTACT, options = options.copy(contactsEnabled = false))
        assertTrue(ordinary.contactEvents > 0 && ordinary.peakContactPenetration > 0f, "default CONTACT contained no actual rim encounters")
        val contactRatio = rms(ordinary.contact) / rms(ordinary.samples).coerceAtLeast(1e-30)
        val contribution = normalizedDifference(ordinary.samples, ordinaryMuted.samples)
        println("NIMBUS default contact: ${ordinary.contactEvents} encounters, relative tap=$contactRatio, on/off difference=$contribution")
        assertTrue(contactRatio >= .01 && contribution >= .03, "default rim contact remained negligible: tap=$contactRatio change=$contribution")
    }

    @Test
    fun `velocity changes finite excitation and physical displacement`() {
        val options = Nimbus.ProbeOptions(durationSeconds = .8f)
        val quiet = Nimbus.probe(NimbusVoice.GATHER, energy = .25f, options = options)
        val strong = Nimbus.probe(NimbusVoice.GATHER, energy = 1f, options = options)
        assertTrue(strong.initialEnergy > quiet.initialEnergy * 2.0)
        assertTrue(rms(strong.samples) > rms(quiet.samples) * 1.5)
        assertTrue(strong.snapshots.maxOf(::displacement) > quiet.snapshots.maxOf(::displacement) * 1.2)
    }

    @Test
    fun `intermediate hold powers a smooth finite extension before the loop threshold`() {
        val options = Nimbus.ProbeOptions(durationSeconds = 6.5f)
        val finite = Nimbus.probe(NimbusVoice.RING, mapOf("HOLD" to 0f), options = options)
        val extended = Nimbus.probe(NimbusVoice.RING, mapOf("HOLD" to .6f), options = options)
        val unpowered = Nimbus.probe(
            NimbusVoice.RING, mapOf("HOLD" to .6f), options = options.copy(poweredDriveEnabled = false),
        )
        assertEquals(0.0, finite.poweredDriveWork)
        assertTrue(extended.poweredDriveWork > 0.0, "intermediate HOLD supplied no explicit magnetic work")
        assertContentEquals(finite.samples, unpowered.samples, "turning off powered drive failed to restore finite decay")
        assertTrue(extended.previousCycle.isEmpty(), "intermediate HOLD became a loop")
        assertFalse(Nimbus.isLoop(.6f))
        assertFalse(Nimbus.isLoop(.989f))
        assertTrue(Nimbus.isLoop(.99f))
        val from = (1.5f * Dsp.RATE).roundToInt()
        val to = (2.5f * Dsp.RATE).roundToInt()
        assertTrue(rms(extended.samples, from, to) > rms(finite.samples, from, to) * 1.2, "intermediate HOLD did not support the decaying note")
        val attackEnd = (.08f * Dsp.RATE).roundToInt()
        val laterStart = (.2f * Dsp.RATE).roundToInt()
        val attackPeak = (0 until attackEnd).maxOf { abs(extended.samples[it]) }
        val laterPeak = (laterStart until extended.samples.size).maxOf { abs(extended.samples[it]) }
        assertTrue(laterPeak < attackPeak * .8f, "finite powered extension reintroduced attack peaks")
        val peakState = extended.snapshots.maxOf { it.passiveEnergy }
        assertTrue(extended.finalPassiveEnergy < peakState * 1e-4, "finite drive did not switch off and decay")
        val finalWindow = (.2f * Dsp.RATE).roundToInt()
        assertTrue(rms(extended.samples, extended.samples.size - finalWindow) < rms(extended.samples, from, to) * .01)
    }

    @Test
    fun `every timbral control changes every voice after matching level`() {
        val options = Nimbus.ProbeOptions(durationSeconds = .65f, recordDiagnostics = false)
        val activity = linkedMapOf<String, Double>()
        for (voice in NimbusVoice.entries) {
            val neutral = Nimbus.defaults(voice)
            for (control in timbralControls) {
                val low = Nimbus.probe(voice, neutral + (control to 0f), options = options).samples
                val high = Nimbus.probe(voice, neutral + (control to 1f), options = options).samples
                activity["$voice $control"] = normalizedDifference(low, high)
            }
        }
        val minimum = activity.minBy { it.value }
        println("NIMBUS minimum matched macro activity: ${minimum.key} ${minimum.value}")
        val deficient = activity.filterValues { it <= .02 || !it.isFinite() }
        assertTrue(deficient.isEmpty(), "Matched macro activity must exceed .02; deficient voice/control pairs: $deficient")
    }

    @Test
    fun `height and spacing produce independent timbre and required macro interactions`() {
        val options = Nimbus.ProbeOptions(durationSeconds = .65f, recordDiagnostics = false)
        val neutral = Nimbus.macrosFor(NimbusVoice.GATHER).associate { it.name to it.neutral }
        val pairs = listOf(
            "EXCITE" to "FIELD", "SPACING" to "FIELD", "HEIGHT" to "FUNNEL",
            "SPACING" to "FUNNEL", "EXCITE" to "SPACING", "HEIGHT" to "SPACING",
        )
        for ((a, b) in pairs) {
            val corners = listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (x, y) ->
                Nimbus.probe(NimbusVoice.GATHER, neutral + mapOf(a to x, b to y), options = options).samples
            }
            var mixed = 0.0
            var signal = 0.0
            for (i in corners[0].indices) {
                val delta = corners[3][i].toDouble() - corners[2][i] - corners[1][i] + corners[0][i]
                mixed += delta * delta
                signal += corners.maxOf { it[i].toDouble() * it[i] }
            }
            val interaction = sqrt(mixed / signal.coerceAtLeast(1e-30))
            println("NIMBUS $a × $b: $interaction")
            assertTrue(interaction > .01, "$a × $b had no measurable interaction: $interaction")
        }
        val lowHigh = Nimbus.probe(NimbusVoice.GATHER, neutral + mapOf("HEIGHT" to 0f, "SPACING" to 1f), options = options)
        val highLow = Nimbus.probe(NimbusVoice.GATHER, neutral + mapOf("HEIGHT" to 1f, "SPACING" to 0f), options = options)
        assertTrue(normalizedDifference(lowHigh.samples, highLow.samples) > .1, "HEIGHT and SPACING collapsed into one size control")
    }

    @Test
    fun `control solver refinement converges rather than changing the instrument`() {
        val macros = mapOf("EXCITE" to .85f, "SPACING" to .2f, "FIELD" to .35f, "FUNNEL" to .8f)
        val options = Nimbus.ProbeOptions(durationSeconds = 1.2f, controlStride = 128)
        val ordinary = Nimbus.probe(NimbusVoice.GATHER, macros, options = options)
        val finer = Nimbus.probe(NimbusVoice.GATHER, macros, options = options.copy(controlStride = 64))
        val finest = Nimbus.probe(NimbusVoice.GATHER, macros, options = options.copy(controlStride = 32))
        val firstError = normalizedDifference(ordinary.samples, finer.samples)
        val secondError = normalizedDifference(finer.samples, finest.samples)
        assertTrue(secondError < .05, "mechanical solver refinement changed sound by $secondError")
        assertTrue(secondError <= firstError * 1.2 + 1e-5, "solver failed to converge: $firstError -> $secondError")
        assertTrue(abs(displacement(finer.snapshots.last()) - displacement(finest.snapshots.last())) < .01)
    }

    @Test
    fun `slow position advances smoothly between controller updates`() {
        val p = Nimbus.probe(
            NimbusVoice.GATHER, mapOf("EXCITE" to 1f, "SPACING" to 1f, "FIELD" to .35f),
            options = Nimbus.ProbeOptions(
                selectedCymbal = 4, couplingEnabled = false, funnelEnabled = false, contactsEnabled = false,
                durationSeconds = .1f, controlStride = 128, snapshotStride = 8,
            ),
        )
        val moving = p.snapshots.filter { it.timeSeconds in .025f.. .09f }
        val steps = moving.zipWithNext().map { (a, b) -> abs(b.heights[4].toDouble() - a.heights[4]) }
        assertTrue(steps.size > 500, "dense mechanical evidence was not captured")
        val activeFraction = steps.count { it > 0.0 }.toDouble() / steps.size
        assertTrue(activeFraction > .7, "moving plate held stair-step positions between controller ticks: active fraction $activeFraction")
        assertTrue(steps.max() < steps.average() * 6.0, "slow motion contained controller-sized geometry jumps")
    }

    @Test
    fun `ordinary voices are root bearing mono tones outside drum routing guards`() {
        for (voice in NimbusVoice.entries) {
            val snip = Nimbus.render(voice)
            assertEquals(1, snip.channels)
            assertEquals(Dsp.RATE, snip.sampleRate)
            assertTrue(snip.samples.all { it.isFinite() }, "$voice emitted a non-finite output")
            assertTrue(snip.peak() in .01f.. .991f, "$voice peak ${snip.peak()}")
            assertTrue(abs(snip.samples.average()) < .005, "$voice DC ${snip.samples.average()}")
            assertTrue(snip.durationSeconds in 1f..10f)
            val heard = Classifier.classify(snip).drumClass
            val filed = Nimbus.drumClassFor(voice)
            assertTrue(heard !in drums, "$voice sounds like $heard to current routing guards")
            assertTrue(filed !in drums, "$voice filed as $filed")
            val want = Nimbus.frequencyFor(voice, Nimbus.defaults(voice).getValue("TUNE"))
            val cents = FineTuning.cents(FineTuning.measuredHz(snip, want, .08f, .4f), want.toDouble())
            assertTrue(abs(cents) <= 10.0, "$voice physical principal moved $cents cents off requested note")
            assertTrue(bands(snip.samples, want.toDouble(), .18, .65).rootShare >= .01, "$voice calibrated principal became negligible")
        }
        for (tune in floatArrayOf(0f, .5f, 1f)) {
            val p = Nimbus.probe(NimbusVoice.RING, mapOf("TUNE" to tune), options = Nimbus.ProbeOptions(durationSeconds = .7f))
            val cents = FineTuning.cents(FineTuning.measuredHz(p.samples, Dsp.RATE, p.rootHz, .05f, .35f), p.rootHz.toDouble())
            assertTrue(abs(cents) <= 10.0, "full coupled TUNE $tune root moved $cents cents")
        }
    }

    @Test
    fun `all high ordinary controls stay finite before normalization`() {
        for (voice in NimbusVoice.entries) {
            for (tune in floatArrayOf(0f, 1f)) {
                val macros = timbralControls.associateWith { 1f } + mapOf("TUNE" to tune, "HOLD" to 0f)
                val p = Nimbus.probe(voice, macros, options = Nimbus.ProbeOptions(durationSeconds = 1.2f))
                assertTrue(p.samples.all { it.isFinite() }, "$voice TUNE $tune extreme was non-finite")
                assertTrue(p.rawPeak < 1f, "$voice TUNE $tune extreme raw peak ${p.rawPeak}")
                assertTrue(p.snapshots.all { it.geometryValid && it.passiveEnergy.isFinite() && it.passiveEnergy >= 0.0 })
            }
        }
    }

    @Test
    fun `powered metal and difficult contact holds converge across a genuine preceding cycle`() {
        val cases = listOf(
            NimbusVoice.SUSPEND to mapOf("TUNE" to 0f, "HOLD" to 1f),
            NimbusVoice.CONTACT to mapOf("TUNE" to 0f, "EXCITE" to 1f, "SPACING" to 0f, "FIELD" to .6f, "HOLD" to 1f),
            NimbusVoice.SHIMMER to (timbralControls.associateWith { 1f } + mapOf("TUNE" to 1f, "HOLD" to 1f)),
        )
        for ((voice, macros) in cases) {
            val p = Nimbus.probe(voice, macros)
            assertEquals(p.samples.size, p.previousCycle.size, "$voice supplied no natural preceding cycle")
            assertTrue(p.poweredDriveWork > 0.0, "$voice HOLD was not powered separately")
            assertTrue(p.samples.all { it.isFinite() } && p.rawPeak < 1f)
            val heldMetal = bands(p.samples, p.rootHz.toDouble(), .05, .65)
            assertTrue(heldMetal.upperShare >= .15 && heldMetal.upperOccupied >= 4, "$voice held texture collapsed to a sparse bell: upper=${heldMetal.upperShare}, bands=${heldMetal.upperOccupied}")
            assertTrue(heldMetal.rootShare >= .01, "$voice held requested root became negligible: ${heldMetal.rootShare}")
            if (voice == NimbusVoice.CONTACT) {
                assertTrue(p.contactEvents > 0, "difficult held case contained no settled rim contacts")
                assertTrue(p.contact.all { it.isFinite() } && rms(p.contact) > 1e-7, "settled contacts emitted no finite contact sound")
                assertTrue(
                    p.peakContactPenetration.isFinite() && p.peakContactPenetration > 0f && p.peakContactPenetration <= .003f,
                    "settled rim penetration escaped its bound: ${p.peakContactPenetration}",
                )
                val relativeDc = abs(p.samples.average()) / rms(p.samples).coerceAtLeast(1e-30)
                assertTrue(relativeDc < .01, "held contact output DC was $relativeDc of its RMS")
            }
            val seam = Keys.seamError(p.previousCycle + p.samples, p.previousCycle.size)
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "$voice genuine held seam $seam")
            assertEquals(seam, p.seamError, 1e-10)
            // Compare the interior too: a crossfade of an unsettled tail cannot pass this check.
            val from = p.samples.size / 5
            val to = p.samples.size * 4 / 5
            val mismatch = normalizedDifference(p.previousCycle.copyOfRange(from, to), p.samples.copyOfRange(from, to))
            assertTrue(mismatch < .03, "$voice preceding cycle remained unsettled: $mismatch")
            val cents = FineTuning.cents(FineTuning.measuredHz(p.samples, Dsp.RATE, p.rootHz, .05f, .35f), p.rootHz.toDouble())
            assertTrue(abs(cents) <= 10.0, "$voice HOLD transposed $cents cents")
            assertTrue(p.snapshots.all { it.geometryValid && it.passiveEnergy.isFinite() })
            println("NIMBUS $voice HOLD: seam $seam, interior mismatch $mismatch, root $cents cents")
        }
    }

    private fun displacement(snapshot: Nimbus.StateSnapshot): Double = sqrt(
        snapshot.heights.indices.sumOf { i ->
            val delta = snapshot.heights[i].toDouble() - snapshot.restingHeights[i]
            delta * delta
        } / snapshot.heights.size,
    )

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var energy = 0.0
        for (i in from until to) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (to - from).coerceAtLeast(1))
    }

    private fun firstAudible(samples: FloatArray): Int {
        // A causal onset threshold above decimator pre-ringing, measured relative to each tap.
        val threshold = samples.maxOf { abs(it) } * .01f
        return samples.indexOfFirst { abs(it) > threshold }
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        var difference = 0.0
        var energy = 0.0
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            difference += (x - y) * (x - y)
            energy += max(x * x, y * y)
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    private fun normalizedDifference(a: FloatArray, b: FloatArray): Double {
        val aRms = rms(a).coerceAtLeast(1e-30)
        val bRms = rms(b).coerceAtLeast(1e-30)
        var difference = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val delta = a[i] / aRms - b[i] / bRms
            difference += delta * delta
        }
        return sqrt(difference / n.coerceAtLeast(1))
    }

    private data class Ensemble(
        val shares: DoubleArray, val effectiveCount: Double, val otherToFullUpper: Double, val otherToAttack: Double,
    )

    private fun ensemble(p: Nimbus.Probe, selected: Int, start: Double, end: Double): Ensemble {
        val root = p.rootHz.toDouble()
        // Include every material's lowest native upper (1.54–2.06x root). The separate
        // full-mix richness checks keep their broader >=1.8x-root threshold.
        val powers = DoubleArray(6) { bands(p.cymbals[it], root, start, end, upperCutoff = 1.35).upperPower }
        val total = powers.sum().coerceAtLeast(1e-30)
        val shares = DoubleArray(6) { powers[it] / total }
        val others = FloatArray(p.samples.size) { frame ->
            var sum = 0.0
            for (plate in 0 until 6) if (plate != selected) sum += p.cymbals[plate][frame]
            sum.toFloat()
        }
        val otherUpper = sqrt(bands(others, root, start, end, upperCutoff = 1.35).upperPower)
        val wholeUpper = sqrt(bands(p.samples, root, start, end, upperCutoff = 1.35).upperPower).coerceAtLeast(1e-30)
        val attack = rms(p.samples, 0, minOf(p.samples.size, (.15 * Dsp.RATE).roundToInt())).coerceAtLeast(1e-30)
        return Ensemble(shares, 1.0 / shares.sumOf { it * it }.coerceAtLeast(1e-30), otherUpper / wholeUpper, otherUpper / attack)
    }

    private data class Bands(
        val rootShare: Double, val upperShare: Double, val highShare: Double,
        val upperOccupied: Int, val profile: DoubleArray, val upperPower: Double,
    )

    private fun bands(samples: FloatArray, root: Double, start: Double, end: Double, upperCutoff: Double = 1.8): Bands {
        val from = (start * Dsp.RATE).roundToInt()
        val count = minOf(samples.size - from, ((end - start) * Dsp.RATE).roundToInt())
        require(count > 8)
        var n = 2048
        while (n < count) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        var windowPower = 0.0
        for (i in 0 until count) {
            val window = .5 - .5 * cos(2 * PI * i / (count - 1))
            re[i] = (samples[from + i] * window).toFloat()
            windowPower += window * window
        }
        Fft.forward(re, im)
        // Center a band on the requested root. A root at a band boundary could make tiny
        // phase/loading shifts look like a substantial change in an otherwise identical bell.
        val edges = DoubleArray(26) { root * .5 * 2.0.pow((it - .5) * .25) }
        val energyBands = DoubleArray(25)
        var total = 0.0
        var principal = 0.0
        var upper = 0.0
        var high = 0.0
        var band = 0
        for (k in 1 until n / 2) {
            val hz = k.toDouble() * Dsp.RATE / n
            if (hz < root * .45) continue
            val energy = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            total += energy
            if (hz in root * .94..root * 1.06) principal += energy
            if (hz >= root * upperCutoff) upper += energy
            if (hz >= root * 4.0) high += energy
            while (band < energyBands.size && hz >= edges[band + 1]) band++
            if (band < energyBands.size && hz >= edges[band]) energyBands[band] += energy
        }
        val denominator = total.coerceAtLeast(1e-30)
        val occupied = energyBands.indices.count { edges[it] >= root * upperCutoff && energyBands[it] >= total * .005 }
        val inBands = energyBands.sum().coerceAtLeast(1e-30)
        val profile = DoubleArray(energyBands.size) { sqrt(energyBands[it] / inBands) }
        // Parseval plus the Hann power correction retains each tap's actual physical level.
        // A normalized upper spectrum alone could certify an inaudibly quiet neighbor.
        return Bands(principal / denominator, upper / denominator, high / denominator, occupied, profile,
            2.0 * upper / (n * windowPower))
    }

    private fun envelope(samples: FloatArray): DoubleArray {
        val energies = DoubleArray(14) { index ->
            val from = (.1 * index * Dsp.RATE).roundToInt()
            val to = minOf(samples.size, (.1 * (index + 1) * Dsp.RATE).roundToInt())
            (from until to).sumOf { samples[it].toDouble() * samples[it] }
        }
        val total = energies.sum().coerceAtLeast(1e-30)
        return DoubleArray(energies.size) { sqrt(energies[it] / total) }
    }

    private fun spectrum(samples: FloatArray, minimumHz: Double = 0.0, startSeconds: Double = .015): DoubleArray {
        val n = 16384
        val from = (startSeconds * Dsp.RATE).roundToInt()
        val count = minOf(n, samples.size - from)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = (samples[from + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(re, im)
        val magnitudes = DoubleArray(n / 2) {
            if (it.toDouble() * Dsp.RATE / n < minimumHz) 0.0
            else sqrt(re[it].toDouble() * re[it] + im[it].toDouble() * im[it])
        }
        val norm = sqrt(magnitudes.sumOf { it * it }).coerceAtLeast(1e-30)
        return magnitudes.map { it / norm }.toDoubleArray()
    }

    private fun spectralDifference(a: DoubleArray, b: DoubleArray): Double =
        sqrt(a.indices.sumOf { (a[it] - b[it]) * (a[it] - b[it]) })
}
