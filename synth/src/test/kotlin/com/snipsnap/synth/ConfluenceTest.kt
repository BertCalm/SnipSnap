package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfluenceTest {
    private fun vowel(seconds: Float = .65f): ConfluenceSource {
        val samples = FloatArray((seconds * Confluence.RATE).toInt()) { i ->
            val phase = 2 * PI * 130.81278265 * i / Confluence.RATE
            (.28 * sin(phase) + .12 * sin(3 * phase) + .06 * sin(7 * phase)).toFloat()
        }
        return ConfluenceSource.fromSnip(Snip(samples, 1, Confluence.RATE))
    }

    private fun recipe(source: ConfluenceSource, count: Int = 4, profile: ConfluenceProfile = ConfluenceProfile.UNISON) =
        ConfluenceRecipe("Test gathering", profile,
            listOf(ConfluenceRegion(source.hash, 0, source.frameCount, referenceMidi = 48f,
                sustainStartFrame = 2205, sustainEndFrame = source.frameCount - 2205)),
            macros = mapOf("CROWD" to Confluence.crowdForCount(count), "SPACE" to 0f), seed = 7, seconds = 3.5f)

    @Test
    fun `same resolved assets recipe and options regenerate identical finite samples and traces`() {
        val source = vowel()
        val input = recipe(source)
        val a = Confluence.render(input, mapOf(source.hash to source))
        val b = Confluence.render(ConfluenceRecipe.fromJsonText(input.toJsonText()), mapOf(source.hash to source))
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.events, b.events)
        assertEquals(a.performers, b.performers)
        assertEquals(Confluence.RATE, a.snip.sampleRate)
        assertEquals(1, a.snip.channels)
        assertEquals((input.seconds * Confluence.RATE).toInt(), a.snip.frameCount)
        assertTrue(a.snip.samples.all { it.isFinite() })
        assertTrue(a.snip.peak() in .01f..1f)
        assertTrue(abs(a.diagnostics.dc) < .001f)
        assertTrue(a.diagnostics.voicedShare > .7f, "clean periodic test fixture should take the pitched path")
    }

    @Test
    fun `growth retains every existing identity and complete phrase event`() {
        val source = vowel()
        val a = Confluence.render(recipe(source, 2), mapOf(source.hash to source))
        val b = Confluence.render(recipe(source, 8), mapOf(source.hash to source))
        assertEquals(a.performers, b.performers.take(2))
        assertEquals(a.events.filter { it.performerId == 0 && it.state == "ENTER" }.map { it.pitchSemitones to it.durationRatio },
            b.events.filter { it.performerId == 0 && it.state == "ENTER" }.map { it.pitchSemitones to it.durationRatio })
        assertEquals(8, b.diagnostics.activePerformers)
        assertTrue(b.events.all { it.startFrame >= 0 && it.endFrame <= b.snip.frameCount })
        assertTrue(b.events.filter { it.state == "SING" }.all { it.endFrame > it.startFrame })
        assertTrue(b.events.count { it.performerId == 0 && it.state == "ENTER" } >= 2)
        assertTrue(b.events.any { it.performerId == 0 && it.state == "REST" && it.startFrame > 0 })
        assertEquals(8, b.performers.map { it.id }.toSet().size)
    }

    @Test
    fun `source snapshots and verified hashes reject changed relinks and caller mutations`() {
        val source = vowel()
        val changed = FloatArray(source.frameCount) { .1f }
        assertFailsWith<IllegalArgumentException> { ConfluenceSource(source.hash, changed) }
        assertFailsWith<IllegalArgumentException> { Confluence.render(recipe(source), emptyMap()) }
        val original = FloatArray(22_050) { sin(2 * PI * 130.8128 * it / Confluence.RATE).toFloat() * .2f }
        val frozen = ConfluenceSource.fromSnip(Snip(original, 1, Confluence.RATE))
        val before = Confluence.render(recipe(frozen), mapOf(frozen.hash to frozen))
        original.fill(0f)
        val after = Confluence.render(recipe(frozen), mapOf(frozen.hash to frozen))
        assertContentEquals(before.snip.samples, after.snip.samples)
        val mutable = mutableMapOf("CROWD" to .5f)
        val input = recipe(source).copy(macros = mutable)
        mutable["CROWD"] = Float.NaN
        assertFailsWith<IllegalArgumentException> { Confluence.render(input, mapOf(source.hash to source)) }
    }

    @Test
    fun `unsupported holds missing sustains silent corrupt and incomplete phrase assignments refuse clearly`() {
        val source = vowel()
        val input = recipe(source)
        assertFailsWith<IllegalArgumentException> { input.copy(hold = true) }
        assertFailsWith<IllegalArgumentException> { input.copy(profile = ConfluenceProfile.DRONE,
            regions = listOf(input.regions.single().copy(sustainStartFrame = null, sustainEndFrame = null))) }
        assertFailsWith<IllegalArgumentException> { input.regions.single().copy(referenceMidi = null) }
        assertFailsWith<IllegalArgumentException> { ConfluenceSource.fromSnip(Snip(FloatArray(22_050) { Float.NaN }, 1, Confluence.RATE)) }
        val silence = ConfluenceSource.fromSnip(Snip(FloatArray(22_050), 1, Confluence.RATE))
        assertFailsWith<IllegalArgumentException> { Confluence.render(recipe(silence), mapOf(silence.hash to silence)) }
        val long = vowel(2.5f)
        assertFailsWith<IllegalArgumentException> { Confluence.render(recipe(long).copy(seconds = 2f), mapOf(long.hash to long)) }
    }

    @Test
    fun `small pitch transformation moves the pulse clock while preserving phrase duration`() {
        val source = vowel(1.2f)
        val analysis = ConfluenceVoice.analyze(source.pcm, 48f)
        val original = ConfluenceVoice.render(analysis, ConfluenceSourceMode.PITCHED, 0f, 1f, 1f, 0f, 5f, 0f)
        val raised = ConfluenceVoice.render(analysis, ConfluenceSourceMode.PITCHED, 2f, 1f, 1f, 0f, 5f, 0f)
        val stretched = ConfluenceVoice.render(analysis, ConfluenceSourceMode.PITCHED, 0f, 1.03f, 1f, 0f, 5f, 0f)
        assertEquals(original.size, raised.size)
        assertTrue(abs((original.size * 1.03f).toInt() - stretched.size) <= 1, "whole-phrase time control is separate")
        val low = requireNotNull(Pitch.detect(Snip(original, 1, Confluence.RATE)))
        val high = requireNotNull(Pitch.detect(Snip(raised, 1, Confluence.RATE)))
        assertTrue(abs(high.hz / low.hz - 1.122462) < .025, "two semitones should change the measured fundamental: ${low.hz} -> ${high.hz}")
        assertTrue(original.indices.any { abs(original[it] - raised[it]) > .01f })
    }

    @Test
    fun `texture never acquires the requested pitched mapping and dry bypass removes the room`() {
        val random = Random(72)
        val noise = ConfluenceSource.fromSnip(Snip(FloatArray(22_050) { random.nextFloat() * .2f - .1f }, 1, Confluence.RATE))
        val input = ConfluenceRecipe("Air", ConfluenceProfile.UNISON,
            listOf(ConfluenceRegion(noise.hash, 0, noise.frameCount, ConfluenceSourceMode.TEXTURE)),
            mapOf("CROWD" to .4f, "SPACE" to 1f), seconds = 3f)
        val sourceMap = mapOf(noise.hash to noise)
        val a = Confluence.render(input.copy(midi = 12), sourceMap, ConfluenceOptions(spaceBypassed = true, normalize = false))
        val b = Confluence.render(input.copy(midi = 100), sourceMap, ConfluenceOptions(spaceBypassed = true, normalize = false))
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertContentEquals(a.raw, a.dry.samples)
        val room = Confluence.render(input, sourceMap, ConfluenceOptions(normalize = false))
        assertTrue(room.raw.indices.any { abs(room.raw[it] - a.raw[it]) > .001f })
        assertEquals(a.events, room.events, "SPACE and its bypass must preserve the exact arrangement")
    }

    @Test
    fun `response sections and marked drone breaths preserve persistent identities`() {
        val source = vowel()
        for (profile in listOf(ConfluenceProfile.RESPONSE, ConfluenceProfile.DRONE)) {
            val report = Confluence.render(recipe(source, 4, profile).copy(seconds = 7f), mapOf(source.hash to source))
            val entrances = report.events.filter { it.state == "ENTER" }
            assertTrue(entrances.groupBy { it.performerId }.values.all { it.size >= 2 })
            assertTrue(entrances.map { it.startFrame }.toSet().size >= 4)
            assertTrue(report.events.any { it.state == "PAUSE" })
            assertEquals(4, report.performers.size)
            assertTrue(report.diagnostics.maximumSimultaneousPerformers <= 4)
            for (slot in report.performers) {
                val states = report.events.filter { it.performerId == slot.id }.sortedBy { it.startFrame }
                for ((before, after) in states.zipWithNext()) {
                    assertTrue(before.endFrame <= after.startFrame, "$profile performer${slot.id} ${before.state} overlaps ${after.state}")
                }
                assertTrue(states.filter { it.state == "ENTER" }.all { it.formantScale == slot.formantScale })
            }
        }
        val input = recipe(source, 4).copy(seconds = 5f, macros = recipe(source, 4).macros + ("DIVERGENCE" to .8f))
        val coordinated = Confluence.render(input, mapOf(source.hash to source))
        val independent = Confluence.render(input, mapOf(source.hash to source), ConfluenceOptions(coordination = false))
        assertTrue(coordinated.events.any { it.state == "ENTER" && abs(it.neighborPullSeconds) > .0001f })
        assertTrue(independent.events.all { it.neighborPullSeconds == 0f })
        assertNotEquals(coordinated.events, independent.events)
        val isolated = Confluence.render(input, mapOf(source.hash to source), ConfluenceOptions(isolatedPerformer = 0))
        assertEquals(1, isolated.diagnostics.activePerformers)
        assertEquals(4, isolated.diagnostics.rosterPerformers)
    }

    @Test
    fun `endpoint controls admit every roster slot and responses follow completed calls`() {
        val source = vowel()
        for (profile in ConfluenceProfile.entries) for (count in listOf(1, 8)) for (value in listOf(0f, 1f)) {
            val base = recipe(source, count, profile)
            val input = base.copy(seconds = 6f, macros = Confluence.MACRO_NAMES.associateWith { value } +
                ("CROWD" to Confluence.crowdForCount(count)))
            val report = Confluence.render(input, mapOf(source.hash to source))
            assertEquals((0 until count).toSet(), report.events.filter { it.state == "ENTER" }.map { it.performerId }.toSet(),
                "$profile count$count endpoint$value")
            assertTrue(report.snip.samples.all { it.isFinite() })
            assertTrue(report.snip.peak() <= .991f)
            if (profile == ConfluenceProfile.RESPONSE && count > 1) {
                for (round in report.events.filter { it.state == "ENTER" && it.section == 1 }.map { it.eventIndex }.toSet()) {
                    val callEnd = report.events.filter { it.eventIndex == round && it.section == 0 && it.state == "RELEASE" }.maxOf { it.endFrame }
                    val answerStart = report.events.filter { it.eventIndex == round && it.section == 1 && it.state == "ENTER" }.minOf { it.startFrame }
                    assertTrue(answerStart > callEnd, "answer must wait for the complete call at endpoint$value")
                }
            }
        }
    }

    @Test
    fun `committed vowel phrases retain quiet natural releases and reject the retired hot crop`() {
        val fixtureDir = listOf(File("../reference/fixtures/confluence"), File("reference/fixtures/confluence"))
            .firstOrNull { File(it, "manifest.json").isFile }
            ?: error("Confluence source fixtures are required for the boundary regression")
        val manifest = Json.parse(File(fixtureDir, "manifest.json").readText()).obj()
        val vowels = manifest.getValue("sources").arr().map { it.obj() }.filter { it.getValue("kind").str() == "vowel" }
        assertTrue(vowels.size >= 2, "both human vowel fixtures must be covered")

        fun rms(samples: FloatArray, from: Int, end: Int): Double =
            sqrt((from until end).sumOf { samples[it].toDouble() * samples[it] } / (end - from))

        fun requireQuietRelease(label: String, samples: FloatArray, source: Map<String, JsonValue>, guard: Map<String, JsonValue>) {
            val phraseStart = source.getValue("phraseStartFrame").int()
            val phraseEnd = source.getValue("phraseEndFrame").int()
            val sustainStart = source.getValue("sustainStartFrame").int()
            val sustainEnd = source.getValue("sustainEndFrame").int()
            val tailFrames = guard.getValue("tailWindowFrames").int()
            val absoluteLimit = guard.getValue("maxTailRms").num()
            val relativeLimit = guard.getValue("maxTailToSustainRmsRatio").num()
            assertTrue(phraseStart >= 0 && phraseStart <= sustainStart && sustainStart < sustainEnd &&
                sustainEnd <= phraseEnd && phraseEnd <= samples.size, "$label: invalid phrase or sustain markers")
            assertTrue(tailFrames > 0 && tailFrames <= phraseEnd - phraseStart, "$label: invalid tail window")
            assertTrue(absoluteLimit.isFinite() && absoluteLimit in 0.0..1.0 &&
                relativeLimit.isFinite() && relativeLimit in 0.0..1.0, "$label: invalid boundary guard limits")
            val sustainRms = rms(samples, sustainStart, sustainEnd)
            // Read the selected phrase boundary, which need not be the end of the asset file.
            val tailRms = rms(samples, phraseEnd - tailFrames, phraseEnd)
            assertTrue(sustainRms > 1e-5, "$label: a silent sustain cannot qualify a quiet release")
            assertTrue(tailRms <= absoluteLimit,
                "$label: phrase ends while hot (tail RMS $tailRms, limit $absoluteLimit)")
            assertTrue(tailRms / sustainRms <= relativeLimit,
                "$label: phrase release is too loud relative to its sustain (ratio ${tailRms / sustainRms}, limit $relativeLimit)")
        }

        for (source in vowels) {
            val id = source.getValue("id").str()
            val audio = WavReader.read(File(fixtureDir, source.getValue("path").str()))
            assertEquals(1, audio.channels, "$id: canonical fixture must be mono")
            assertEquals(44_100, audio.sampleRate, "$id: canonical fixture rate")
            assertEquals(source.getValue("frames").int(), audio.frameCount, "$id: manifest frame count")
            val guard = source["boundaryGuard"]?.obj() ?: error("$id: held-vowel boundaryGuard is required")
            requireQuietRelease(id, audio.samples, source, guard)
        }

        // Reconstruct the exact rejected selection from its retained original and documented gain.
        // The first implementation's frame-count and zero-edge checks accepted this next-octave onset.
        val retired = manifest.getValue("retiredVowelMale").obj().getValue("previousSource").obj()
        val original = WavReader.read(File(fixtureDir, retired.getValue("originalPath").str()))
        assertEquals(1, original.channels)
        assertEquals(44_100, original.sampleRate)
        val from = retired.getValue("originalCropStartFrame").int()
        val end = retired.getValue("originalCropEndFrame").int()
        val gain = 10.0.pow(retired.getValue("gainDb").num() / 20)
        val rejected = original.samples.copyOfRange(from, end).also { samples ->
            for (i in samples.indices) samples[i] = (samples[i] * gain).toFloat()
        }
        assertEquals(retired.getValue("frames").int(), rejected.size)
        val guard = vowels.first { it.getValue("id").str() == "vowel-male" }.getValue("boundaryGuard").obj()
        val failure = assertFailsWith<AssertionError> { requireQuietRelease("retired male1 crop", rejected, retired, guard) }
        assertTrue(failure.message.orEmpty().contains("phrase ends while hot"),
            "the regression must fail for its audible hot boundary, not malformed markers: ${failure.message}")
    }

    private fun assemblyFixture(space: Float = .4f): Pair<ConfluenceAssemblyRecipe, Map<String, ConfluenceSource>> {
        fun source(seconds: Float, midi: Int): ConfluenceSource {
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val samples = FloatArray((seconds * Confluence.RATE).toInt()) { i ->
                val phase = 2 * PI * hz * i / Confluence.RATE
                (.19 * sin(phase) + .065 * sin(3 * phase) + .025 * sin(5 * phase)).toFloat()
            }
            return ConfluenceSource.fromSnip(Snip(samples, 1, Confluence.RATE))
        }
        val low = source(1.1f, 43)
        val upper = source(.55f, 55)
        val answer = source(.75f, 50)
        val roles = listOf(
            ConfluenceRoleSpec(ConfluenceRole.FOUNDATION,
                ConfluenceRegion(low.hash, 0, low.frameCount, referenceMidi = 43f,
                    sustainStartFrame = Confluence.RATE / 10, sustainEndFrame = Confluence.RATE * 9 / 10),
                listOf(0, 1, 2), gain = .7f),
            ConfluenceRoleSpec(ConfluenceRole.AIRY,
                ConfluenceRegion(upper.hash, 0, upper.frameCount, referenceMidi = 55f),
                listOf(3, 4, 5), gain = .4f),
            ConfluenceRoleSpec(ConfluenceRole.ANSWER,
                ConfluenceRegion(answer.hash, 0, answer.frameCount, referenceMidi = 50f),
                listOf(6, 7), gain = .9f),
        )
        return ConfluenceAssemblyRecipe("Three native registers", roles, seed = 17, seconds = 9f,
            space = space, divergence = .25f) to listOf(low, upper, answer).associateBy { it.hash }
    }

    @Test
    fun `mixed roles reproduce their native source assignments and whole selected regions`() {
        val (recipe, sources) = assemblyFixture()
        val restored = ConfluenceAssemblyRecipe.fromJsonText(recipe.toJsonText())
        val a = ConfluenceAssembly.render(recipe, sources, normalize = false)
        val b = ConfluenceAssembly.render(restored, sources, normalize = false)
        assertEquals(recipe, restored)
        assertContentEquals(a.raw, b.raw)
        assertEquals(a.events, b.events)
        assertEquals(a.performers, b.performers)
        assertEquals((0 until Confluence.MAX_PERFORMERS).toSet(), a.performers.map { it.id }.toSet())

        for (role in recipe.roles) {
            val region = role.region
            val expectedStart = if (role.role == ConfluenceRole.FOUNDATION) region.sustainStartFrame!! else region.startFrame
            val expectedEnd = if (role.role == ConfluenceRole.FOUNDATION) region.sustainEndFrame!! else region.endFrame
            val entrances = a.events.filter { it.role == role.role && it.state == "ENTER" }
            assertEquals(role.performerIds.toSet(), entrances.map { it.performerId }.toSet())
            assertTrue(entrances.isNotEmpty())
            for (event in entrances) {
                assertEquals(region.assetHash, event.sourceHash)
                assertEquals(expectedStart, event.sourceStartFrame)
                assertEquals(expectedEnd, event.sourceEndFrame)
                assertTrue(abs(event.pitchSemitones) < .5f,
                    "a native role should retain its supplied register, not gain an octave mapping")
                val release = a.events.single { it.role == event.role && it.performerId == event.performerId &&
                    it.eventIndex == event.eventIndex && it.state == "RELEASE" }
                val expectedFrames = ((expectedEnd - expectedStart) * event.durationRatio).toInt()
                assertTrue(abs(release.endFrame - event.startFrame - expectedFrames) <= 1,
                    "the complete selected source must survive each role event")
            }
        }
    }

    @Test
    fun `role isolates retain one mix schedule and one gain through the shared room`() {
        val (recipe, sources) = assemblyFixture()
        val raw = ConfluenceAssembly.render(recipe, sources, normalize = false)
        val matched = ConfluenceAssembly.render(recipe, sources, normalize = true)
        assertEquals(raw.events, matched.events)
        assertEquals(raw.performers, matched.performers)
        assertContentEquals(raw.raw, matched.raw)
        assertEquals(ConfluenceRole.entries.toSet(), matched.stems.keys)
        assertEquals(matched.stems.keys, matched.rawStems.keys)
        assertEquals(matched.stems.keys, matched.dryStems.keys)
        assertTrue(matched.normalizationGain.isFinite() && matched.normalizationGain > 0f)
        for (role in ConfluenceRole.entries) {
            assertEquals(raw.raw.size, matched.stems.getValue(role).samples.size)
            assertEquals(raw.raw.size, matched.rawStems.getValue(role).samples.size)
            assertEquals(raw.raw.size, matched.dryStems.getValue(role).samples.size)
        }

        var rawError = 0f
        var matchedError = 0f
        var dryError = 0f
        for (i in raw.raw.indices) {
            var rawSum = 0f; var matchedSum = 0f; var drySum = 0f
            for (role in ConfluenceRole.entries) {
                val stem = matched.stems.getValue(role)
                val rawStem = matched.rawStems.getValue(role).samples
                val dryStem = matched.dryStems.getValue(role)
                rawSum += rawStem[i]
                matchedSum += stem.samples[i]
                drySum += dryStem.samples[i]
                assertTrue(abs(stem.samples[i] - rawStem[i] * matched.normalizationGain) < 2e-6f,
                    "a quiet role must not be independently boosted when isolated")
            }
            rawError = maxOf(rawError, abs(rawSum - raw.raw[i]))
            matchedError = maxOf(matchedError, abs(matchedSum - matched.mix.samples[i]))
            dryError = maxOf(dryError, abs(drySum - matched.dry.samples[i]))
        }
        assertTrue(rawError < 2e-6f, "raw role sum changed the full mix: $rawError")
        assertTrue(matchedError < 2e-6f, "matched role sum changed the full mix: $matchedError")
        assertTrue(dryError < 2e-6f, "dry role sum changed the full mix: $dryError")
        assertTrue(matched.mix.samples.all { it.isFinite() })
        assertTrue(matched.mix.peak() <= .991f)
    }

    @Test
    fun `mixed exchanges answer completed calls and all performer states remain exclusive`() {
        val (recipe, sources) = assemblyFixture(space = 0f)
        val report = ConfluenceAssembly.render(recipe, sources, normalize = false)
        val enters = report.events.filter { it.state == "ENTER" }
        val callRounds = enters.filter { it.role == ConfluenceRole.AIRY }.map { it.eventIndex }.toSet()
        val answerRounds = enters.filter { it.role == ConfluenceRole.ANSWER }.map { it.eventIndex }.toSet()
        assertTrue(callRounds.size >= 2, "the fixture must exercise an answer followed by another call")
        assertEquals(callRounds, answerRounds, "a finite render must admit complete call and answer exchanges")
        var previousAnswerEnd = -1
        for (round in callRounds.sorted()) {
            fun states(role: ConfluenceRole, state: String) = report.events.filter {
                it.role == role && it.eventIndex == round && it.state == state
            }
            val callStart = states(ConfluenceRole.AIRY, "ENTER").minOf { it.startFrame }
            val callEnd = states(ConfluenceRole.AIRY, "RELEASE").maxOf { it.endFrame }
            val answerStart = states(ConfluenceRole.ANSWER, "ENTER").minOf { it.startFrame }
            val answerEnd = states(ConfluenceRole.ANSWER, "RELEASE").maxOf { it.endFrame }
            assertTrue(callStart > previousAnswerEnd, "a fresh call must wait for the preceding complete answer")
            assertTrue(answerStart >= callEnd + (recipe.answerDelaySeconds * Confluence.RATE).toInt() - 1,
                "a clear answer must wait for the final caller and the explicit response gap")
            previousAnswerEnd = answerEnd
        }
        val totalFrames = report.mix.frameCount
        for (performer in report.performers) {
            val states = report.events.filter { it.performerId == performer.id }.sortedBy { it.startFrame }
            assertTrue(states.all { it.startFrame >= 0 && it.endFrame <= totalFrames && it.endFrame >= it.startFrame })
            for ((before, after) in states.zipWithNext()) {
                assertTrue(before.endFrame <= after.startFrame,
                    "performer ${performer.id}: ${before.state} overlaps ${after.state}")
            }
        }
        assertTrue(report.events.filter { it.state == "RELEASE" }.maxOf { it.endFrame } < totalFrames,
            "whole releases must leave room for the finite export ending")
        val foundationEnters = enters.filter { it.role == ConfluenceRole.FOUNDATION }
        assertTrue(foundationEnters.groupBy { it.performerId }.values.all { it.size >= 2 },
            "the foundation must exercise independent rests and reentries")
    }

    @Test
    fun `mixed role assignments reject missing duplicate excessive and changed inputs`() {
        val (recipe, sources) = assemblyFixture()
        assertFailsWith<IllegalArgumentException> { recipe.copy(roles = recipe.roles.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { recipe.copy(roles = recipe.roles + recipe.roles.first()) }
        assertFailsWith<IllegalArgumentException> { recipe.copy(roles = recipe.roles.map {
            if (it.role == ConfluenceRole.ANSWER) it.copy(performerIds = listOf(0, 7)) else it
        }) }
        assertFailsWith<IllegalArgumentException> { recipe.copy(roles = recipe.roles.map {
            if (it.role == ConfluenceRole.ANSWER) it.copy(performerIds = listOf(6, 7, 8)) else it
        }) }
        val foundation = recipe.roles.first { it.role == ConfluenceRole.FOUNDATION }
        assertFailsWith<IllegalArgumentException> { recipe.copy(roles = recipe.roles.map {
            if (it == foundation) it.copy(region = it.region.copy(sustainStartFrame = null, sustainEndFrame = null)) else it
        }) }
        assertFailsWith<IllegalArgumentException> { recipe.copy(roles = recipe.roles.map {
            if (it.role == ConfluenceRole.AIRY) it.copy(region = it.region.copy(mode = ConfluenceSourceMode.TEXTURE)) else it
        }) }
        assertFailsWith<IllegalArgumentException> { ConfluenceAssembly.render(recipe, sources - foundation.region.assetHash) }
        val wrongSource = sources.getValue(recipe.roles.last().region.assetHash)
        assertFailsWith<IllegalArgumentException> { ConfluenceAssembly.render(recipe,
            sources + (foundation.region.assetHash to wrongSource)) }
    }
}
