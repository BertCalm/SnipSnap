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

/** Musical consequences, causal controls and durable audio compatibility for the new duet. */
class ObbligatoConversationTest {
    private val dt = 64.0 / (Dsp.RATE * Dsp.OVERSAMPLE)
    private val relationships = listOf(ObbligatoRelationship.PLAY, ObbligatoRelationship.FIGHT,
        ObbligatoRelationship.PROTECT)

    private fun controls(relationship: ObbligatoRelationship, hold: Float = .45f) =
        Obbligato.defaults() + mapOf("BOND" to relationship.bond, "HOLD" to hold)

    private fun probe(relationship: ObbligatoRelationship, midi: Int = 60, hold: Float = .45f) =
        Obbligato.probe(controls(relationship, hold), midi = midi, model = Obbligato.CONVERSATION_MODEL)

    private fun rms(samples: FloatArray, from: Double, until: Double): Double {
        val start = (from * Dsp.RATE).toInt().coerceAtLeast(0)
        val end = minOf(samples.size, (until * Dsp.RATE).toInt())
        require(end > start)
        var energy = 0.0
        for (i in start until end) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (end - start))
    }

    private data class ControllerRun(val controller: ObbligatoConversation, val trajectory: DoubleArray)

    private fun controllerRun(
        mode: ObbligatoConversation.Mode,
        hold: Double = 5.0,
        seed: Int = 0,
        responses: Boolean = true,
        activity: (Double) -> Pair<Double, Double> = { .8 to .7 },
    ): ControllerRun {
        val controller = ObbligatoConversation(mode, hold, .2f, seed)
        val count = ceil((hold + .75) / dt).toInt()
        val trace = DoubleArray(count * 10)
        var lastAir = controller.airPresence
        var lastReed = controller.reedPresence
        repeat(count) { index ->
            val (air, reed) = activity(index * dt)
            controller.step(dt, air, reed, responses)
            assertTrue(controller.airPresence.isFinite() && controller.airPresence in .18..1.0)
            assertTrue(controller.reedPresence.isFinite() && controller.reedPresence in .18..1.0)
            assertTrue(controller.airPressure.isFinite() && controller.airPressure in .97..1.025)
            assertTrue(controller.reedPressure.isFinite() && controller.reedPressure in .97..1.025)
            assertTrue(controller.airPitch.isFinite() && abs(controller.airPitch) <= 3.0)
            assertTrue(controller.reedPitch.isFinite() && abs(controller.reedPitch) <= 3.0)
            assertTrue(controller.airTurbulence.isFinite() && controller.airTurbulence in .65..1.15)
            assertTrue(controller.reedTurbulence.isFinite() && controller.reedTurbulence in .65..1.15)
            assertTrue(controller.airTone.isFinite() && controller.airTone in 0.0..1.0)
            assertTrue(controller.reedTone.isFinite() && controller.reedTone in 0.0..1.0)
            assertTrue(abs(controller.airPresence - lastAir) < .02 && abs(controller.reedPresence - lastReed) < .02,
                "$mode created a control-rate step in audible prominence")
            lastAir = controller.airPresence
            lastReed = controller.reedPresence
            val offset = index * 10
            trace[offset] = controller.airPresence
            trace[offset + 1] = controller.reedPresence
            trace[offset + 2] = controller.airPressure
            trace[offset + 3] = controller.reedPressure
            trace[offset + 4] = controller.airPitch
            trace[offset + 5] = controller.reedPitch
            trace[offset + 6] = controller.airTurbulence
            trace[offset + 7] = controller.reedTurbulence
            trace[offset + 8] = controller.airTone
            trace[offset + 9] = controller.reedTone
        }
        return ControllerRun(controller, trace)
    }

    @Test
    fun `gentler reference recipes retain the exact original source taps and complete PCM exports`() {
        val fixture = assertNotNull(javaClass.getResourceAsStream("/obbligato/model7-sha256.json"))
            .bufferedReader().use { Json.parse(it.readText()).obj() }
        assertEquals("122c7c0c6486521951db33ffbf78aef32d7fb31f", fixture.getValue("originalCommit").str())
        val provenance = fixture.getValue("sourceGitBlobs").obj()
        assertTrue(provenance.size >= 9 && provenance.values.all { it.str().matches(Regex("[a-f0-9]{40}")) })
        val cases = fixture.getValue("cases").arr()
        assertEquals(5, cases.size)
        for (value in cases) {
            val case = value.obj()
            val patch = ObbligatoPatch.fromJsonValue(case.getValue("recipe"))
            assertEquals(7, patch.model)
            val report = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
            assertEquals(case.getValue("rawFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(report.snip.samples), "${patch.name}: reference duet changed")
            assertEquals(case.getValue("airFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(report.air), "${patch.name}: reference air changed")
            assertEquals(case.getValue("reedFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(report.reed), "${patch.name}: reference reed changed")
            assertEquals(case.getValue("finalPcm24WavSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.pcmHash(patch), "${patch.name}: reference export changed")
        }
    }

    @Test
    fun `the new conversation is an explicit reproducible recipe with actual audition taps`() {
        assertEquals(5, Obbligato.MODEL)
        assertEquals(7, Obbligato.MUSICAL_MODEL)
        assertTrue(ObbligatoPresets.all().all { it.model == 5 })
        assertTrue(ObbligatoKits.patches().all { it.model == 5 })
        val patch = ObbligatoPatch("Remembered Play", controls(ObbligatoRelationship.PLAY),
            midi = 67, velocity = .51f, seed = -819, model = Obbligato.CONVERSATION_MODEL)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        val missingModel = JsonValue.Obj(patch.toJsonValue().entries.toMutableMap().apply { remove("model") })
        assertEquals(5, ObbligatoPatch.fromJsonValue(missingModel).model)
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
            "production audio differs from the complete duet exposed by the audition")
    }

    @Test
    fun `seeded decisions remain bounded and the audio reader reconstructs their actual shapes`() {
        val runs = ObbligatoConversation.Mode.entries.map { mode ->
            val first = controllerRun(mode, seed = -319)
            val repeated = controllerRun(mode, seed = -319)
            assertEquals(first.controller.events, repeated.controller.events)
            assertContentEquals(first.trajectory, repeated.trajectory)
            assertTrue(first.controller.events.any { it.actor == ObbligatoConversation.Player.REED },
                "$mode never gives the other audible player a response")
            assertTrue(first.controller.events.size < 30, "$mode exceeded a finite phrase's event budget")
            for (event in first.controller.events) {
                assertTrue(event.seconds in 0.0..5.0, "$mode started an event during release")
                assertTrue(event.rememberedDuration.isFinite() && event.rememberedDuration >= 0.0)
                assertTrue(event.rememberedIntensity.isFinite() && event.rememberedIntensity in 0.0..1.0)
                assertTrue(event.rememberedSlope.isFinite())
                assertTrue(event.strength.isFinite() && event.duration.isFinite() && event.duration > 0.0)
                assertTrue(event.airReserve in 0.0..1.0 && event.reedReserve in 0.0..1.0)
            }
            val reader = first.controller.performanceReader()
            for (index in 0 until first.trajectory.size / 10) {
                reader.at((index + 1) * dt)
                val actual = doubleArrayOf(reader.airPresence, reader.reedPresence, reader.airPressure,
                    reader.reedPressure, reader.airPitch, reader.reedPitch, reader.airTurbulence,
                    reader.reedTurbulence, reader.airTone, reader.reedTone)
                for (field in actual.indices) assertEquals(first.trajectory[index * 10 + field], actual[field], 1e-8,
                    "$mode replay changed a recorded gesture at block $index field $field")
            }
            first
        }
        for (a in runs.indices) for (b in a + 1 until runs.size) {
            assertTrue(!runs[a].trajectory.contentEquals(runs[b].trajectory),
                "the relationship modes produce the same expressive performance")
            assertTrue(runs[a].controller.events.map { it.kind } != runs[b].controller.events.map { it.kind },
                "the relationships differ only in a source mix, not in the kinds of answers")
        }
    }

    @Test
    fun `an absent or disabled partner does not fabricate a social answer`() {
        for (mode in ObbligatoConversation.Mode.entries) {
            for (activity in listOf(0.0 to 0.0, .8 to 0.0, 0.0 to .7,
                Double.NaN to .7, .8 to Double.POSITIVE_INFINITY)) {
                assertTrue(controllerRun(mode, activity = { activity }).controller.events.isEmpty(),
                    "$mode invented an answer to an absent partner: $activity")
            }
            val steady = controllerRun(mode, responses = false)
            val changed = controllerRun(mode, responses = false,
                activity = { t -> (if (t in .2..1.4) 0.0 else .9) to .2 })
            assertTrue(steady.controller.events.isEmpty() && changed.controller.events.isEmpty())
            assertContentEquals(steady.trajectory, changed.trajectory,
                "$mode response-off performance still depends on the other player's measured history")
        }
    }

    @Test
    fun `Play remembers the heard contour and changes the shape of its answering gesture`() {
        fun run(rising: Boolean) = controllerRun(ObbligatoConversation.Mode.PLAY, activity = { t ->
            val progress = (t / .8).coerceIn(0.0, 1.0)
            (if (rising) .3 + .6 * progress else .9 - .6 * progress) to .7
        }).controller
        val rising = run(true)
        val falling = run(false)
        val up = assertNotNull(rising.events.firstOrNull { it.actor == ObbligatoConversation.Player.REED })
        val down = assertNotNull(falling.events.firstOrNull { it.actor == ObbligatoConversation.Player.REED })
        assertTrue(up.rememberedSlope > down.rememberedSlope + .02,
            "the reply does not remember whether the heard phrase rose or fell")
        assertTrue(abs(up.strength - down.strength) > .01 || abs(up.duration - down.duration) > .015,
            "the changed contour is reported but does not alter the answering articulation")
        val a = rising.performanceReader()
        val b = falling.performanceReader()
        var difference = 0.0
        for (age in listOf(.08, .16, .25, .35)) {
            a.at(up.seconds + age); b.at(down.seconds + age)
            difference += abs(a.reedPresence - b.reedPresence) + abs(a.reedTone - b.reedTone)
        }
        assertTrue(difference > .005,
            "the new memory changes event metadata without changing the audio-rate response shape")
    }

    @Test
    fun `a handoff rise alone is not remembered as a rising voiced body`() {
        val controller = controllerRun(ObbligatoConversation.Mode.PLAY, activity = { t ->
            (.2 + .6 * (t / .14).coerceIn(0.0, 1.0)) to .7
        }).controller
        val answer = assertNotNull(controller.events.firstOrNull { it.kind == "ANSWER" })
        assertTrue(answer.rememberedDuration > .2, "the preceding turn was not materially heard")
        assertTrue(abs(answer.rememberedSlope) < 1e-9,
            "a flat voiced body inherited the compulsory entrance's rising contour")
    }

    @Test
    fun `Fight can renew a heard assertion before yielding to a different counterstatement`() {
        val events = controllerRun(ObbligatoConversation.Mode.FIGHT).controller.events
        val hold = events.indexOfFirst { it.kind == "HOLD" }
        assertTrue(hold >= 0, "steady audible players still alternate without a renewed assertion")
        val renewal = events[hold]
        val counter = assertNotNull(events.drop(hold + 1).firstOrNull { it.kind == "COUNTER" },
            "a player can hold ground but never yields to the partner")
        assertTrue(renewal.actor != counter.actor, "the counterstatement was assigned to the same player")
        assertTrue(renewal.rememberedDuration > .2 && renewal.rememberedIntensity > .4,
            "the renewal is unrelated to a materially heard preceding statement")
        assertTrue(renewal.airReserve > counter.airReserve,
            "holding ground does not spend the asserting player's breath reserve")
    }

    @Test
    fun `all three relationships retain clear complete pitched gestures across the playable octave`() {
        for (relationship in relationships) for (midi in listOf(60, 67, 72)) for (hold in listOf(0f, 1f)) {
            val report = probe(relationship, midi, hold)
            val samples = report.snip.samples
            val label = "$relationship MIDI $midi HOLD $hold"
            assertEquals(1, report.snip.channels)
            assertEquals(44_100, report.snip.sampleRate)
            assertTrue(samples.isNotEmpty() && samples.all { it.isFinite() }, "$label is not finite")
            assertTrue(report.air.all { it.isFinite() } && report.reed.all { it.isFinite() })
            assertTrue(abs(samples.average()) < .015, "$label has excessive DC before leveling")
            assertEquals(0f, samples.last(), "$label has no complete finite ending")
            assertTrue(samples.indices.all { abs(report.air[it] + report.reed[it] - samples[it]) < 1e-6f },
                "$label actual sources do not reconstruct the duet")
            assertTrue(report.conversationEvents.any { it.actor == ObbligatoConversation.Player.REED },
                "$label never gives the partner an answering gesture")
            for ((name, tap) in listOf("air" to report.air, "reed" to report.reed)) {
                val pitch = assertNotNull(Pitch.detect(Snip(tap, 1, Dsp.RATE), 1.15f, .35f),
                    "$label $name has no periodic root")
                val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi)) / ln(2.0)
                assertTrue(pitch.confidence >= .65f && abs(cents) < 35.0,
                    "$label $name does not retain a clear requested root: $pitch ($cents cents)")
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
            assertTrue(air.size >= 3, "$label has no material body to assess")
            for ((name, levels) in listOf("air" to air, "reed" to reed)) {
                assertTrue(levels.min() > 1e-4, "$label loses $name capture inside the gesture")
            }
            val energy = air.indices.map { sqrt(air[it] * air[it] + reed[it] * reed[it]) }
            assertTrue(energy.min() > energy.max() * .30, "$label has a source-energy gap between turns")
            assertTrue(sum.min() > sum.max() * .15, "$label has a pronounced mono cancellation gap")
            if (relationship != ObbligatoRelationship.PROTECT) {
                assertTrue(air.indices.any { air[it] > reed[it] * sqrt(2.0) }, "$label air has no actual prominent turn")
                assertTrue(reed.indices.any { reed[it] > air[it] * sqrt(2.0) }, "$label reed has no actual prominent turn")
            }
            // The ending must retain real mono energy, even when two near-unison
            // fundamentals oppose phase. This compares the mix with independent taps.
            var coda = held - .30
            while (coda + .05 <= held) {
                val a = rms(report.air, coda, coda + .05)
                val b = rms(report.reed, coda, coda + .05)
                val mixed = rms(samples, coda, coda + .05)
                assertTrue(mixed >= .30 * sqrt(a * a + b * b),
                    "$label settling cancels into a gap at $coda seconds")
                coda += .05
            }
        }
    }

    @Test
    fun `default Fight settling retains mono energy when the near unison sources oppose phase`() {
        val report = probe(ObbligatoRelationship.FIGHT)
        val held = report.snip.durationSeconds.toDouble() - .75
        var minimum = 1.0
        var from = held - .45
        while (from + .05 <= held) {
            val a = rms(report.air, from, from + .05)
            val b = rms(report.reed, from, from + .05)
            minimum = minOf(minimum, rms(report.snip.samples, from, from + .05) / sqrt(a * a + b * b))
            from += .01
        }
        assertTrue(minimum >= .50,
            "default Fight's settling cancelled into a gap despite healthy source energy: $minimum")
    }

    @Test
    fun `Protect preserves the developed flute opening before an answering reed enters`() {
        for (midi in listOf(60, 67, 72)) {
            val report = probe(ObbligatoRelationship.PROTECT, midi)
            val flute = rms(report.air, .25, .60)
            val reed = rms(report.reed, .25, .60)
            assertTrue(flute > 1e-4, "MIDI $midi Protect lost its opening flute tone")
            assertTrue(reed < flute * .01, "MIDI $midi Protect masks the flute before the reed's entry")
            assertTrue(rms(report.reed, 1.15, 1.5) > 1e-4, "MIDI $midi Protect never admits the supporting reed")
        }
    }

    @Test
    fun `physical source weakening changes the partners gesture only with responses enabled`() {
        for (relationship in relationships) {
            val settings = controls(relationship)
            val original = probe(relationship)
            val intervention = Obbligato.SourceWeakening(air = true, startSeconds = .3,
                endSeconds = 1.1, strength = .08f)
            val changed = Obbligato.probeWithWeakening(settings, model = Obbligato.CONVERSATION_MODEL,
                weakening = intervention)
            fun answers(report: Obbligato.Report) = report.conversationEvents
                .filter { it.actor == ObbligatoConversation.Player.REED && it.kind != "SETTLE" }
                .map { listOf(it.seconds, it.kind, it.strength, it.duration) }
            assertTrue(answers(original).isNotEmpty(), "$relationship reference has no meaningful reed response")
            assertTrue(answers(original) != answers(changed),
                "$relationship keeps the same answer despite losing the actual flute activity")
            assertTrue(!original.reed.contentEquals(changed.reed), "$relationship unweakened reed did not answer")
            val off = Obbligato.probe(settings, responses = false, model = Obbligato.CONVERSATION_MODEL)
            val offChanged = Obbligato.probeWithWeakening(settings, responses = false,
                model = Obbligato.CONVERSATION_MODEL, weakening = intervention)
            assertTrue(!off.air.contentEquals(offChanged.air), "$relationship pressure intervention is a no-op")
            assertContentEquals(off.reed, offChanged.reed,
                "$relationship unweakened source depends on its disabled partner")
            assertTrue(off.conversationEvents.isEmpty() && offChanged.conversationEvents.isEmpty())
        }
    }
}
