package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A longer join and tail must add coexistence without losing a leader, root or ending. */
class ObbligatoMoreOverlapTest {
    private val dt = 64.0 / (Dsp.RATE * Dsp.OVERSAMPLE)
    private val relationships = listOf(ObbligatoRelationship.PLAY, ObbligatoRelationship.FIGHT,
        ObbligatoRelationship.PROTECT)

    private fun controls(relationship: ObbligatoRelationship, hold: Float = .45f) =
        Obbligato.defaults() + mapOf("BOND" to relationship.bond, "HOLD" to hold)

    private fun rms(samples: FloatArray, from: Double, until: Double): Double {
        val a = (from * Dsp.RATE).toInt().coerceAtLeast(0)
        val b = minOf(samples.size, (until * Dsp.RATE).toInt())
        require(b > a)
        var power = 0.0
        for (i in a until b) power += samples[i].toDouble() * samples[i]
        return sqrt(power / (b - a))
    }

    @Test
    fun `saved model nine recipes retain their original taps and PCM exports`() {
        val fixture = assertNotNull(javaClass.getResourceAsStream("/obbligato/model9-sha256.json"))
            .bufferedReader().use { Json.parse(it.readText()).obj() }
        assertEquals("3e02bc1ae29cd0fc708b7eb5293ad6a5e8f38a0c", fixture.getValue("originalCommit").str())
        val provenance = fixture.getValue("sourceGitBlobs").obj()
        assertTrue(provenance.size >= 10 && provenance.values.all { it.str().matches(Regex("[a-f0-9]{40}")) })
        val cases = fixture.getValue("cases").arr()
        assertEquals(5, cases.size)
        for (value in cases) {
            val case = value.obj()
            val patch = ObbligatoPatch.fromJsonValue(case.getValue("recipe"))
            assertEquals(9, patch.model)
            val raw = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
            assertEquals(case.getValue("rawFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.snip.samples), "${patch.name}: saved sum changed")
            assertEquals(case.getValue("airFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.air), "${patch.name}: saved air changed")
            assertEquals(case.getValue("reedFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.reed), "${patch.name}: saved reed changed")
            assertEquals(case.getValue("finalPcm24WavSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.pcmHash(patch), "${patch.name}: saved export changed")
        }
    }

    @Test
    fun `accepted longer overlap is a deterministic saved recipe and production matches the audition`() {
        assertEquals(10, Obbligato.MODEL)
        assertEquals(9, Obbligato.OVERLAP_MODEL)
        assertEquals(10, Obbligato.MORE_OVERLAP_MODEL)
        assertTrue(ObbligatoPresets.all().all { it.model == 10 })
        assertTrue(ObbligatoKits.patches().all { it.model == 10 })
        val patch = ObbligatoPatch("Longer overlap Play", controls(ObbligatoRelationship.PLAY),
            midi = 67, velocity = .51f, seed = -819, model = Obbligato.MORE_OVERLAP_MODEL)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        val missing = JsonValue.Obj(patch.toJsonValue().entries.toMutableMap().apply { remove("model") })
        assertEquals(5, ObbligatoPatch.fromJsonValue(missing).model)
        val first = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
        val repeated = Obbligato.probe(patch.macros.entries.reversed().associate { it.key to it.value },
            patch.midi, patch.velocity, patch.seed, model = patch.model)
        assertContentEquals(first.snip.samples, repeated.snip.samples)
        assertContentEquals(first.air, repeated.air)
        assertContentEquals(first.reed, repeated.reed)
        assertEquals(first.conversationEvents, repeated.conversationEvents)
        val expected = first.snip.samples.copyOf()
        Dsp.levelTo(expected, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        assertContentEquals(expected, patch.render().samples)
    }

    private data class Run(val controller: ObbligatoConversation, val trace: DoubleArray)

    private fun run(mode: ObbligatoConversation.Mode, more: Boolean, mischief: Float = .2f): Run {
        val controller = ObbligatoConversation(mode, 5.0, mischief, -319,
            overlap = true, moreOverlap = more)
        val trace = DoubleArray(ceil(5.75 / dt).toInt() * 4)
        var air = controller.airPresence
        var reed = controller.reedPresence
        repeat(trace.size / 4) { i ->
            controller.step(dt, .8, .7)
            assertTrue(controller.airPresence in .18..1.0 && controller.reedPresence in .18..1.0)
            assertTrue(controller.airPresence * controller.airPresence +
                controller.reedPresence * controller.reedPresence <= 1.04, "$mode exceeds the common power budget")
            assertTrue(abs(controller.airPresence - air) < .02 && abs(controller.reedPresence - reed) < .02,
                "$mode has a control-rate prominence discontinuity")
            air = controller.airPresence
            reed = controller.reedPresence
            trace[i * 4] = air
            trace[i * 4 + 1] = reed
            trace[i * 4 + 2] = controller.airTone
            trace[i * 4 + 3] = controller.reedTone
        }
        return Run(controller, trace)
    }

    @Test
    fun `longer curves replay continuously including high temperament and interrupted endings`() {
        for (mode in ObbligatoConversation.Mode.entries) for (mischief in listOf(.2f, 1f)) {
            val original = run(mode, true, mischief)
            val repeated = run(mode, true, mischief)
            assertEquals(original.controller.events, repeated.controller.events)
            assertContentEquals(original.trace, repeated.trace)
            val reader = original.controller.performanceReader()
            repeat(original.trace.size / 4) { i ->
                reader.at((i + 1) * dt)
                val actual = doubleArrayOf(reader.airPresence, reader.reedPresence, reader.airTone, reader.reedTone)
                for (field in actual.indices) assertEquals(original.trace[i * 4 + field], actual[field], 1e-8,
                    "$mode MISCHIEF $mischief field $field block $i")
            }
            val events = original.controller.events
            assertTrue(events.size < 30 && events.any { it.kind == "SETTLE" })
            assertTrue(events.all { it.seconds < 5.0 && it.rememberedSlope.isFinite() })
        }
    }

    @Test
    fun `a pass extends coexistence beyond model nine and still gives the next player a clear lead`() {
        for (mode in listOf(ObbligatoConversation.Mode.PLAY, ObbligatoConversation.Mode.FIGHT)) {
            val old = run(mode, false).controller
            val new = run(mode, true).controller
            fun firstPass(controller: ObbligatoConversation) = assertNotNull(controller.events.firstOrNull {
                it.actor == ObbligatoConversation.Player.REED && it.kind != "SETTLE"
            })
            val oldEntry = firstPass(old)
            val newEntry = firstPass(new)
            assertEquals(oldEntry.seconds, newEntry.seconds, 1e-9)
            val previous = old.performanceReader()
            val current = new.performanceReader()
            var oldBoth = 0.0
            var newBoth = 0.0
            var oldPeakPower = 0.0
            var newPeakPower = 0.0
            for (i in 0..310) {
                previous.at(oldEntry.seconds + i * .001)
                current.at(newEntry.seconds + i * .001)
                if (previous.airPresence >= .30 && previous.reedPresence >= .30) oldBoth += .001
                if (current.airPresence >= .30 && current.reedPresence >= .30) newBoth += .001
                oldPeakPower = maxOf(oldPeakPower, previous.airPresence * previous.airPresence +
                    previous.reedPresence * previous.reedPresence)
                newPeakPower = maxOf(newPeakPower, current.airPresence * current.airPresence +
                    current.reedPresence * current.reedPresence)
            }
            assertTrue(newBoth >= oldBoth + .025, "$mode coexistence did not extend: $oldBoth -> $newBoth")
            // Sample after the complete tail and before the next legal 320 ms decision.
            assertTrue(current.reedPresence > current.airPresence * 2.5, "$mode arriving leader remains ambiguous")
            assertTrue(newPeakPower <= oldPeakPower + .01, "$mode obtained overlap by adding envelope power")
        }
    }

    @Test
    fun `longer overlap keeps both pipes pitched and complete across registers and hold endpoints`() {
        for (relationship in relationships) for (midi in listOf(60, 67, 72)) for (hold in listOf(0f, 1f)) {
            val settings = controls(relationship, hold)
            val report = Obbligato.probe(settings, midi = midi, model = Obbligato.MORE_OVERLAP_MODEL)
            val label = "$relationship MIDI $midi HOLD $hold"
            assertTrue(report.snip.samples.all { it.isFinite() })
            assertTrue(report.air.all { it.isFinite() } && report.reed.all { it.isFinite() })
            assertTrue(abs(report.snip.samples.average()) < .015, "$label excessive DC")
            assertEquals(0f, report.snip.samples.last())
            assertTrue(report.snip.samples.indices.all {
                abs(report.air[it] + report.reed[it] - report.snip.samples[it]) < 1e-6f
            }, "$label source taps no longer reconstruct the duet")
            for ((name, tap) in listOf("air" to report.air, "reed" to report.reed)) {
                val pitch = assertNotNull(Pitch.detect(Snip(tap, 1, Dsp.RATE), 1.15f, .35f), "$label $name root absent")
                val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi)) / ln(2.0)
                assertTrue(pitch.confidence >= .65f && abs(cents) < 35.0, "$label $name lost root: $cents cents")
            }
            if (relationship == ObbligatoRelationship.PROTECT) {
                val air = rms(report.air, .25, .60)
                assertTrue(air > 1e-4 && rms(report.reed, .25, .60) < air * .01, "$label flute opening obscured")
                assertTrue(rms(report.reed, 1.15, 1.50) > 1e-4, "$label supporting reed absent")
            }
            val held = report.snip.durationSeconds.toDouble() - .75
            val bodyAir = ArrayList<Double>()
            val bodyReed = ArrayList<Double>()
            val bodySum = ArrayList<Double>()
            var from = if (relationship == ObbligatoRelationship.PROTECT) 1.1 else .4
            val end = held - if (relationship == ObbligatoRelationship.PROTECT) .35 else .2
            while (from + .1 <= end) {
                bodyAir += rms(report.air, from, from + .1)
                bodyReed += rms(report.reed, from, from + .1)
                bodySum += rms(report.snip.samples, from, from + .1)
                from += .05
            }
            assertTrue(bodyAir.min() > 1e-4 && bodyReed.min() > 1e-4, "$label loses pipe capture")
            val energy = bodyAir.indices.map { sqrt(bodyAir[it] * bodyAir[it] + bodyReed[it] * bodyReed[it]) }
            assertTrue(energy.min() > energy.max() * .30, "$label source energy falls into a handoff gap")
            assertTrue(bodySum.min() > bodySum.max() * .15, "$label pronounced mono handoff gap")
            if (relationship != ObbligatoRelationship.PROTECT) {
                assertTrue(bodyAir.indices.any { bodyAir[it] > bodyReed[it] * sqrt(2.0) }, "$label air never leads")
                assertTrue(bodyReed.indices.any { bodyReed[it] > bodyAir[it] * sqrt(2.0) }, "$label reed never leads")
            }
            var coda = held - .30
            while (coda + .05 <= held) {
                val a = rms(report.air, coda, coda + .05)
                val b = rms(report.reed, coda, coda + .05)
                assertTrue(rms(report.snip.samples, coda, coda + .05) >= .30 * sqrt(a * a + b * b),
                    "$label settling cancels despite healthy source energy")
                coda += .05
            }
        }
        val extreme = Obbligato.probe(controls(ObbligatoRelationship.FIGHT, 1f) + ("MISCHIEF" to 1f),
            midi = 72, seed = 17, model = Obbligato.MORE_OVERLAP_MODEL)
        assertTrue(extreme.conversationEvents.size < 30 && extreme.conversationEvents.any { it.kind == "COUNTER" })
        assertTrue(extreme.conversationEvents.all { it.rememberedSlope.isFinite() })
        assertTrue(extreme.snip.samples.all { it.isFinite() })
        assertEquals(0f, extreme.snip.samples.last())
        var coda = extreme.snip.durationSeconds.toDouble() - .75 - .30
        val held = extreme.snip.durationSeconds.toDouble() - .75
        while (coda + .05 <= held) {
            val a = rms(extreme.air, coda, coda + .05)
            val b = rms(extreme.reed, coda, coda + .05)
            assertTrue(rms(extreme.snip.samples, coda, coda + .05) >= .30 * sqrt(a * a + b * b),
                "high MISCHIEF Fight loses the complete ending")
            coda += .05
        }
    }

    @Test
    fun `longer overlap still hears physical weakening and leaves a response disabled survivor exact`() {
        for (relationship in relationships) {
            val settings = controls(relationship)
            val original = Obbligato.probe(settings, model = Obbligato.MORE_OVERLAP_MODEL)
            val weakening = Obbligato.SourceWeakening(air = true, startSeconds = .3,
                endSeconds = 1.1, strength = .08f)
            val changed = Obbligato.probeWithWeakening(settings, model = Obbligato.MORE_OVERLAP_MODEL, weakening = weakening)
            fun answers(report: Obbligato.Report) = report.conversationEvents.filter {
                it.actor == ObbligatoConversation.Player.REED && it.kind != "SETTLE"
            }.map { listOf(it.seconds, it.kind, it.strength, it.duration) }
            assertTrue(answers(original).isNotEmpty() && answers(original) != answers(changed), "$relationship reply ignores the partner")
            assertTrue(!original.reed.contentEquals(changed.reed), "$relationship performance did not respond")
            val off = Obbligato.probe(settings, responses = false, model = Obbligato.MORE_OVERLAP_MODEL)
            val offChanged = Obbligato.probeWithWeakening(settings, responses = false,
                model = Obbligato.MORE_OVERLAP_MODEL, weakening = weakening)
            assertTrue(!off.air.contentEquals(offChanged.air), "$relationship weakening is a no-op")
            assertContentEquals(off.reed, offChanged.reed, "$relationship response-disabled survivor depends on its partner")
            assertTrue(off.conversationEvents.isEmpty() && offChanged.conversationEvents.isEmpty())
        }
    }
}
