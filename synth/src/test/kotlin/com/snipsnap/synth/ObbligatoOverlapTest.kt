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

/** Audible coexistence, retained leadership and exact compatibility for the overlap revision. */
class ObbligatoOverlapTest {
    private val dt = 64.0 / (Dsp.RATE * Dsp.OVERSAMPLE)
    private val relationships = listOf(ObbligatoRelationship.PLAY, ObbligatoRelationship.FIGHT,
        ObbligatoRelationship.PROTECT)

    private fun controls(relationship: ObbligatoRelationship, hold: Float = .45f) =
        Obbligato.defaults() + mapOf("BOND" to relationship.bond, "HOLD" to hold)

    private fun probe(relationship: ObbligatoRelationship, midi: Int = 60, hold: Float = .45f) =
        Obbligato.probe(controls(relationship, hold), midi = midi, model = Obbligato.OVERLAP_MODEL)

    private fun rms(samples: FloatArray, from: Double, until: Double): Double {
        val a = (from * Dsp.RATE).toInt().coerceAtLeast(0)
        val b = minOf(samples.size, (until * Dsp.RATE).toInt())
        require(b > a)
        var power = 0.0
        for (i in a until b) power += samples[i].toDouble() * samples[i]
        return sqrt(power / (b - a))
    }

    private data class Run(val controller: ObbligatoConversation, val trace: DoubleArray)

    private fun run(mode: ObbligatoConversation.Mode, overlap: Boolean, seed: Int = 0): Run {
        val controller = ObbligatoConversation(mode, 5.0, .2f, seed, overlap = overlap)
        val count = ceil(5.75 / dt).toInt()
        val trace = DoubleArray(count * 4)
        var previousAir = controller.airPresence
        var previousReed = controller.reedPresence
        repeat(count) { i ->
            controller.step(dt, .8, .7)
            assertTrue(controller.airPresence.isFinite() && controller.airPresence in .18..1.0)
            assertTrue(controller.reedPresence.isFinite() && controller.reedPresence in .18..1.0)
            assertTrue(controller.airPresence * controller.airPresence +
                controller.reedPresence * controller.reedPresence <= 1.04,
                "$mode overlap creates an excessive envelope power bump")
            assertTrue(abs(controller.airPresence - previousAir) < .02 &&
                abs(controller.reedPresence - previousReed) < .02,
                "$mode prominence has a discontinuity at the control rate")
            previousAir = controller.airPresence
            previousReed = controller.reedPresence
            trace[i * 4] = controller.airPresence
            trace[i * 4 + 1] = controller.reedPresence
            trace[i * 4 + 2] = controller.airTone
            trace[i * 4 + 3] = controller.reedTone
        }
        return Run(controller, trace)
    }

    @Test
    fun `previous conversation recipes retain their original taps and complete PCM exports`() {
        val fixture = assertNotNull(javaClass.getResourceAsStream("/obbligato/model8-sha256.json"))
            .bufferedReader().use { Json.parse(it.readText()).obj() }
        assertEquals("cf4b816c65203d722cb95ba4f46e68c6bcb11833", fixture.getValue("originalCommit").str())
        val provenance = fixture.getValue("sourceGitBlobs").obj()
        assertTrue(provenance.size >= 10 && provenance.values.all { it.str().matches(Regex("[a-f0-9]{40}")) })
        val cases = fixture.getValue("cases").arr()
        assertEquals(5, cases.size)
        for (value in cases) {
            val case = value.obj()
            val patch = ObbligatoPatch.fromJsonValue(case.getValue("recipe"))
            assertEquals(8, patch.model)
            val raw = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
            assertEquals(case.getValue("rawFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.snip.samples), "${patch.name}: previous sum changed")
            assertEquals(case.getValue("airFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.air), "${patch.name}: previous air changed")
            assertEquals(case.getValue("reedFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.reed), "${patch.name}: previous reed changed")
            assertEquals(case.getValue("finalPcm24WavSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.pcmHash(patch), "${patch.name}: previous PCM export changed")
        }
    }

    @Test
    fun `the overlapping version is an explicit reproducible recipe with actual audition taps`() {
        assertEquals(5, Obbligato.MODEL)
        assertEquals(8, Obbligato.CONVERSATION_MODEL)
        assertTrue(ObbligatoPresets.all().all { it.model == 5 })
        assertTrue(ObbligatoKits.patches().all { it.model == 5 })
        val patch = ObbligatoPatch("Overlapping Play", controls(ObbligatoRelationship.PLAY),
            midi = 67, velocity = .51f, seed = -819, model = Obbligato.OVERLAP_MODEL)
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
        assertContentEquals(expected, patch.render().samples,
            "production render differs from the full duet used by the listening comparison")
    }

    @Test
    fun `overlapping decisions are deterministic continuous and replay the recorded audio curves`() {
        for (mode in ObbligatoConversation.Mode.entries) {
            val original = run(mode, overlap = true, seed = -319)
            val repeated = run(mode, overlap = true, seed = -319)
            assertEquals(original.controller.events, repeated.controller.events)
            assertContentEquals(original.trace, repeated.trace)
            val reader = original.controller.performanceReader()
            repeat(original.trace.size / 4) { i ->
                reader.at((i + 1) * dt)
                val actual = doubleArrayOf(reader.airPresence, reader.reedPresence, reader.airTone, reader.reedTone)
                for (field in actual.indices) assertEquals(original.trace[i * 4 + field], actual[field], 1e-8,
                    "$mode replay changes audible field $field at block $i")
            }
            assertTrue(original.controller.events.size < 30, "$mode exceeded a finite phrase's event budget")
            assertTrue(original.controller.events.all { it.seconds < 5.0 }, "$mode began a gesture during release")
        }
    }

    @Test
    fun `a real pass gains an audible join and tail while retaining a clear leader and power budget`() {
        // Protect already maintains an active supporting pair after SUPPORT. The two
        // trading modes expose a complete foreground-to-background pass to compare.
        for (mode in listOf(ObbligatoConversation.Mode.PLAY, ObbligatoConversation.Mode.FIGHT)) {
            val previous = run(mode, overlap = false).controller
            val current = run(mode, overlap = true).controller
            fun firstPass(controller: ObbligatoConversation) = assertNotNull(controller.events.firstOrNull {
                it.actor == ObbligatoConversation.Player.REED && it.kind != "SETTLE"
            })
            val oldEntry = firstPass(previous)
            val newEntry = firstPass(current)
            assertEquals(oldEntry.seconds, newEntry.seconds, 1e-9,
                "$mode moved the first decision before any changed handoff could be heard")
            val oldReader = previous.performanceReader()
            val newReader = current.performanceReader()
            var oldOverlap = 0.0
            var newOverlap = 0.0
            var oldPeakPower = 0.0
            var newPeakPower = 0.0
            newReader.at(newEntry.seconds - .001)
            val outgoing = newReader.airPresence
            var earlyIncoming = 0.0
            var earlyOutgoing = 0.0
            for (i in 0..260) {
                val age = i * .001
                oldReader.at(oldEntry.seconds + age)
                newReader.at(newEntry.seconds + age)
                if (oldReader.airPresence >= .30 && oldReader.reedPresence >= .30) oldOverlap += .001
                if (newReader.airPresence >= .30 && newReader.reedPresence >= .30) newOverlap += .001
                oldPeakPower = maxOf(oldPeakPower,
                    oldReader.airPresence * oldReader.airPresence + oldReader.reedPresence * oldReader.reedPresence)
                newPeakPower = maxOf(newPeakPower,
                    newReader.airPresence * newReader.airPresence + newReader.reedPresence * newReader.reedPresence)
                if (i == 40) { earlyIncoming = newReader.reedPresence; earlyOutgoing = newReader.airPresence }
            }
            assertTrue(newOverlap >= oldOverlap + .025,
                "$mode audible coexistence did not materially extend: $oldOverlap -> $newOverlap seconds")
            assertTrue(earlyIncoming > .23 && earlyOutgoing >= outgoing * .85,
                "$mode the incoming player waits for the outgoing player to yield")
            assertTrue(newReader.reedPresence > newReader.airPresence * 2.5,
                "$mode overlap blurred the identity of the arriving leader")
            assertTrue(newPeakPower <= oldPeakPower + .01,
                "$mode overlap was obtained by raising the combined envelope power: $oldPeakPower -> $newPeakPower")
        }
    }

    @Test
    fun `overlapping notes keep clear roots both sources and complete endings across the octave`() {
        for (relationship in relationships) for (midi in listOf(60, 67, 72)) for (hold in listOf(0f, 1f)) {
            val report = probe(relationship, midi, hold)
            val samples = report.snip.samples
            val label = "$relationship MIDI $midi HOLD $hold"
            assertEquals(1, report.snip.channels)
            assertEquals(44_100, report.snip.sampleRate)
            assertTrue(samples.isNotEmpty() && samples.all { it.isFinite() }, "$label has non-finite output")
            assertTrue(report.air.all { it.isFinite() } && report.reed.all { it.isFinite() })
            assertTrue(abs(samples.average()) < .015, "$label has excessive DC")
            assertEquals(0f, samples.last(), "$label finite ending is incomplete")
            assertTrue(samples.indices.all { abs(report.air[it] + report.reed[it] - samples[it]) < 1e-6f },
                "$label actual taps do not reconstruct the duet")
            assertTrue(report.conversationEvents.any { it.actor == ObbligatoConversation.Player.REED },
                "$label the partner never speaks")
            for ((name, tap) in listOf("air" to report.air, "reed" to report.reed)) {
                val pitch = assertNotNull(Pitch.detect(Snip(tap, 1, Dsp.RATE), 1.15f, .35f),
                    "$label $name has no periodic root")
                val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi)) / ln(2.0)
                assertTrue(pitch.confidence >= .65f && abs(cents) < 35.0,
                    "$label $name lost the requested root: $pitch ($cents cents)")
            }
            val held = report.snip.durationSeconds.toDouble() - .75
            val air = ArrayList<Double>()
            val reed = ArrayList<Double>()
            val sum = ArrayList<Double>()
            var from = if (relationship == ObbligatoRelationship.PROTECT) 1.1 else .4
            val end = held - if (relationship == ObbligatoRelationship.PROTECT) .35 else .2
            while (from + .1 <= end) {
                air += rms(report.air, from, from + .1)
                reed += rms(report.reed, from, from + .1)
                sum += rms(samples, from, from + .1)
                from += .05
            }
            assertTrue(air.size >= 3, "$label has no material body")
            for ((name, values) in listOf("air" to air, "reed" to reed)) {
                assertTrue(values.min() > 1e-4, "$label loses $name capture")
            }
            val energy = air.indices.map { sqrt(air[it] * air[it] + reed[it] * reed[it]) }
            assertTrue(energy.min() > energy.max() * .30, "$label source energy falls into a handoff gap")
            assertTrue(sum.min() > sum.max() * .15, "$label has a pronounced mono handoff gap")
            if (relationship != ObbligatoRelationship.PROTECT) {
                assertTrue(air.indices.any { air[it] > reed[it] * sqrt(2.0) }, "$label air never has a prominent turn")
                assertTrue(reed.indices.any { reed[it] > air[it] * sqrt(2.0) }, "$label reed never has a prominent turn")
            }
            var coda = held - .30
            while (coda + .05 <= held) {
                val a = rms(report.air, coda, coda + .05)
                val b = rms(report.reed, coda, coda + .05)
                assertTrue(rms(samples, coda, coda + .05) >= .30 * sqrt(a * a + b * b),
                    "$label settling cancels despite healthy source energy")
                coda += .05
            }
        }
    }

    @Test
    fun `Protect keeps its flute opening and a material supporting reed`() {
        for (midi in listOf(60, 67, 72)) {
            val report = probe(ObbligatoRelationship.PROTECT, midi)
            val air = rms(report.air, .25, .60)
            assertTrue(air > 1e-4, "MIDI $midi opening flute is absent")
            assertTrue(rms(report.reed, .25, .60) < air * .01,
                "MIDI $midi overlap admits the reed before the exposed flute opening")
            assertTrue(rms(report.reed, 1.15, 1.50) > 1e-4,
                "MIDI $midi overlap never admits the supporting reed")
        }
    }

    @Test
    fun `overlap retains causal answers and an exactly independent response disabled survivor`() {
        for (relationship in relationships) {
            val settings = controls(relationship)
            val original = probe(relationship)
            val weakening = Obbligato.SourceWeakening(air = true, startSeconds = .3,
                endSeconds = 1.1, strength = .08f)
            val changed = Obbligato.probeWithWeakening(settings, model = Obbligato.OVERLAP_MODEL, weakening = weakening)
            fun answers(report: Obbligato.Report) = report.conversationEvents.filter {
                it.actor == ObbligatoConversation.Player.REED && it.kind != "SETTLE"
            }.map { listOf(it.seconds, it.kind, it.strength, it.duration) }
            assertTrue(answers(original).isNotEmpty(), "$relationship reference has no consequential reed response")
            assertTrue(answers(original) != answers(changed),
                "$relationship losing actual air activity does not change the partner's answer")
            assertTrue(!original.reed.contentEquals(changed.reed), "$relationship survivor's performance did not respond")
            val off = Obbligato.probe(settings, responses = false, model = Obbligato.OVERLAP_MODEL)
            val offChanged = Obbligato.probeWithWeakening(settings, responses = false,
                model = Obbligato.OVERLAP_MODEL, weakening = weakening)
            assertTrue(!off.air.contentEquals(offChanged.air), "$relationship weakening is a no-op")
            assertContentEquals(off.reed, offChanged.reed,
                "$relationship response-disabled survivor depends on its partner")
            assertTrue(off.conversationEvents.isEmpty() && offChanged.conversationEvents.isEmpty())
        }
    }
}
