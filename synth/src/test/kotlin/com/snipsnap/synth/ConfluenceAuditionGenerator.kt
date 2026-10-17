package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExpansionMeta
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import com.snipsnap.kit.XpnPackager
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A source-based feasibility audition, not a production engine or a playable factory preset pack.
 * All vocal content comes from the attributed human recordings in the fixture manifest. Raw and
 * matched WAVs, exact recipes and event traces travel together; this generator never writes HTML.
 */
object ConfluenceAuditionGenerator {
    private const val GENERATOR_VERSION = 2
    private const val SEED = 20261017
    private const val MATCHED_LOUDEST_WINDOW_RMS = .03f

    private data class Fixture(
        val id: String, val file: File, val source: ConfluenceSource,
        val region: ConfluenceRegion, val metadata: Map<String, JsonValue>,
    )

    private data class Case(
        val id: String, val group: String, val title: String, val description: String,
        val profile: ConfluenceProfile = ConfluenceProfile.UNISON,
        val count: Int = 8, val seconds: Float = 9f,
        val fixtureKind: String = "vowel", val multiSource: Boolean = false,
        val divergence: Float = .35f, val register: Float = .25f,
        val response: Float = .15f, val space: Float = 0f,
        val fixedIdentities: Boolean = true, val coordination: Boolean = true,
        val isolatedPerformer: Int? = null,
    )

    private data class SharedGains(val raw: Float, val matched: Float, val referenceId: String)

    private data class RoleCase(
        val id: String, val title: String, val description: String, val roles: Set<ConfluenceRole>,
    )

    private val groups = listOf(
        group("roles", "A gathering with three roles", "Hear the low foundation, the actual breathy upper source and the clear answering phrase separately, then together. Every part keeps the complete gathering's schedule and the same playback gain; leave the volume unchanged to hear their balance."),
        group("source", "Original recordings and existing SECTION", "Hear the actual human recordings first. The existing six-player SECTION comparison uses the same untransformed vowel timeline at WIDTH 0. Every file is mono."),
        group("crowd", "One voice becomes a gathering", "One, two, four and eight persistent transformed variants of the same singer. Matched playback uses the same loudest-window RMS; raw playback retains the engine's crowd gain."),
        group("behavior", "Complete phrases and individual breaths", "UNISON and RESPONSE schedule complete marked source phrases; DRONE uses the marked sustained vowel. These are deterministic finite renders, without HOLD."),
        group("controls", "Small control comparisons", "Compare crowd with divergence, register with divergence, and answering sections close or in a shared room. The dry clips leave source and scheduling artifacts exposed."),
        group("identity", "Do performers stay themselves?", "Fixed versus deliberately reshuffled identities, and isolated roster slots heard across rests. These are diagnostic ablations of the same recording, not new human singers."),
        group("coordination", "Collective influence", "The same seeded roster and source with section following enabled or disabled. Read the event trace alongside the recording; the listening verdict remains open."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 2) { "supply optional output and fixture directories" }
        val root = File(args.getOrNull(0) ?: "../testkit/confluence-audition").absoluteFile
        val fixtureRoot = args.getOrNull(1)?.let(::File)?.absoluteFile
            ?: File(root.parentFile.parentFile, "reference/fixtures/confluence")
        val fixtureManifest = File(fixtureRoot, "manifest.json")
        require(fixtureManifest.isFile) { "missing human-source fixture manifest: $fixtureManifest" }
        val fixtureJson = Json.parse(fixtureManifest.readText()).obj()
        val fixtures = fixtureJson.getValue("sources").arr().map { loadFixture(fixtureRoot, it.obj()) }
        require(fixtures.isNotEmpty()) { "no source fixtures" }
        val primaryVowel = fixtures.firstOrNull { it.id == "vowel-male" }
            ?: fixtures.first { it.metadata["kind"]?.str() == "vowel" }
        val primaryPhraseId = fixtureJson.getValue("primaryPhraseId").str()
        val primaryPhrase = fixtures.firstOrNull { it.id == primaryPhraseId }
            ?: error("the audition needs its assigned answering phrase $primaryPhraseId")
        val secondVowel = fixtures.firstOrNull { it.id != primaryVowel.id && it.metadata["kind"]?.str() == "vowel" }
        val primaryAiryId = fixtureJson.getValue("primaryAiryId").str()
        val airy = fixtures.firstOrNull { it.id == primaryAiryId }
            ?: error("the three-role audition needs its assigned complete airy source $primaryAiryId and its provenance")
        require(airy.metadata["kind"]?.str() == "airy-phrase") { "the airy group requires an explicitly identified complete breathy phrase" }
        root.mkdirs()
        val sourceRecords = copySources(root, fixtures, fixtureManifest)
        val implementationSources = copyImplementation(root, fixtureRoot)
        val resolved = fixtures.associate { it.source.hash to it.source }
        val clips = mutableListOf<Map<String, Any?>>()
        val renderedById = linkedMapOf<String, Snip>()
        val generationStarted = System.nanoTime()

        fun record(
            id: String, group: String, title: String, description: String,
            audio: Snip, recipe: JsonValue.Obj, diagnostics: JsonValue.Obj,
            renderMs: Double, extra: Map<String, Any?> = emptyMap(),
            sharedGains: SharedGains? = null,
        ) {
            require(audio.channels == 1 && audio.sampleRate == 44_100)
            require(audio.frameCount > 0 && audio.samples.all { it.isFinite() }) { "nonfinite or empty audition $id" }
            val rawMetrics = metrics(audio)
            val rawGain = sharedGains?.raw ?: minOf(1f, .99f / audio.peak().coerceAtLeast(1e-9f))
            val rawExport = gain(audio, rawGain)
            val matched = sharedGains?.let { gain(audio, it.matched) } ?: AuditionLevel.level(audio)
            require(rawExport.peak() <= .990001f && matched.peak() <= .990001f) { "shared gain exceeds PCM headroom: $id" }
            val matchingPolicy = if (sharedGains == null) "individual-loudness-match" else "shared-gathering-gain"
            val matchedGain = sharedGains?.matched ?: (matched.peak() / audio.peak().coerceAtLeast(1e-9f))
            val rawPath = "raw/$id.wav"
            val matchedPath = "matched/$id.wav"
            val recipePath = "recipes/$id.json"
            val diagnosticsPath = "diagnostics/$id.json"
            val rawFile = WavWriter.write(File(root, rawPath), rawExport, WavWriter.BitDepth.PCM_24)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val recipeFile = File(root, recipePath).apply { parentFile.mkdirs(); writeText(Json.write(recipe) + "\n") }
            val diagnosticFile = File(root, diagnosticsPath).apply {
                parentFile.mkdirs()
                writeText(Json.write(json(linkedMapOf(
                    "id" to id, "rawMetrics" to rawMetrics, "rawExportMetrics" to metrics(rawExport),
                    "matchedMetrics" to metrics(matched), "rawExportGain" to rawGain,
                    "matchedGain" to matchedGain, "matchingPolicy" to matchingPolicy,
                    "matchedReferenceId" to sharedGains?.referenceId,
                    "renderMilliseconds" to renderMs, "engine" to diagnostics,
                ))) + "\n")
            }
            clips += linkedMapOf<String, Any?>(
                "id" to id, "group" to group, "title" to title, "description" to description,
                "seconds" to audio.durationSeconds, "frames" to audio.frameCount,
                "sampleRate" to audio.sampleRate, "channels" to audio.channels, "bitDepth" to 24,
                "rawPath" to rawPath, "matchedPath" to matchedPath,
                "recipePath" to recipePath, "diagnosticsPath" to diagnosticsPath,
                "recipeSha256" to sha256(recipeFile), "diagnosticsSha256" to sha256(diagnosticFile),
                "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                "rawPeak" to rawMetrics.getValue("peak"), "rawRms" to rawMetrics.getValue("rms"),
                "rawDc" to rawMetrics.getValue("dc"), "loudestWindowRms" to rawMetrics.getValue("loudestWindowRms"),
                "matchedLoudestWindowRms" to Loudness.of(matched), "rawExportGain" to rawGain,
                "matchedGain" to matchedGain, "matchingPolicy" to matchingPolicy,
                "matchedReferenceId" to sharedGains?.referenceId,
                "renderMilliseconds" to renderMs, "finite" to true,
            ) + extra
            renderedById[id] = matched
            println("Confluence audition ${clips.size}: $id (${audio.durationSeconds}s, ${renderMs.toInt()} ms)")
        }

        val vowelTimeline = referenceTimeline(primaryVowel, 9f)
        for ((fixture, id, title, seconds) in listOf(
            Quad(primaryVowel, "source_vowel", "Original human vowel, with rests", 9f),
            Quad(primaryPhrase, "source_phrase", "Original human phrase", maxOf(9f, regionSeconds(primaryPhrase) + .5f)),
            Quad(airy, "source_airy", "Original complete breathy arpeggio", maxOf(9f, regionSeconds(airy) + 1.5f)),
        )) {
            val audio = referenceTimeline(fixture, seconds)
            record(id, "source", title,
                "Untransformed ${fixture.id}; complete marked material is repeated only when it fits, with 650 ms rests. No ensemble or room.",
                audio, referenceRecipe(fixture, seconds, "source"), json(mapOf("operation" to "source-playback", "performerCount" to 1)) as JsonValue.Obj,
                0.0, mapOf("sourceIds" to listOf(fixture.id), "performerCount" to 1, "profile" to "SOURCE"))
        }
        val beforeSection = System.nanoTime()
        val section = Ensemble.process(vowelTimeline, mapOf("SECTION" to 1f, "WIDTH" to 0f, "DEPTH" to .5f, "RATE" to .5f))
        record("ensemble_section", "source", "Existing ENSEMBLE SECTION, mono",
            "Six existing delay-line players over exactly the original vowel timeline. SECTION 1, WIDTH 0, DEPTH .50, RATE .50; this is the comparison baseline, not Confluence.",
            section, referenceRecipe(primaryVowel, 9f, "ensemble-section"),
            json(mapOf("operation" to "Ensemble.process", "SECTION" to 1f, "WIDTH" to 0f, "DEPTH" to .5f, "RATE" to .5f)) as JsonValue.Obj,
            elapsed(beforeSection), mapOf("sourceIds" to listOf(primaryVowel.id), "performerCount" to 6, "profile" to "ENSEMBLE"))

        val phraseSeconds = maxOf(12f, (regionSeconds(primaryPhrase) + .75f) * 2f + 2f)
        val cases = cases(phraseSeconds, secondVowel != null)
        require(cases.map { it.id }.distinct().size == cases.size)
        var portableCheck: Map<String, Any?>? = null
        for (case in cases) {
            val selected = if (case.fixtureKind == "phrase") listOf(primaryPhrase)
                else if (case.multiSource) listOf(primaryVowel, requireNotNull(secondVowel)) else listOf(primaryVowel)
            val macros = linkedMapOf(
                "CROWD" to Confluence.crowdForCount(case.count), "REGISTER" to case.register,
                "DIVERGENCE" to case.divergence, "RESPONSE" to case.response, "SPACE" to case.space,
            )
            val recipe = ConfluenceRecipe(
                name = case.title, profile = case.profile, regions = selected.map { it.region }, macros = macros,
                seed = SEED, seconds = case.seconds,
            )
            val options = ConfluenceOptions(
                normalize = false, fixedIdentities = case.fixedIdentities,
                coordination = case.coordination, isolatedPerformer = case.isolatedPerformer,
            )
            val started = System.nanoTime()
            val report = Confluence.render(recipe, resolved, options)
            val renderMs = elapsed(started)
            val recipeJson = json(linkedMapOf(
                "format" to "snipsnap-confluence-audition-recipe", "version" to GENERATOR_VERSION,
                "recipe" to Json.parse(recipe.toJsonText()),
                "renderOptions" to mapOf("normalize" to false, "fixedIdentities" to case.fixedIdentities,
                    "coordination" to case.coordination, "isolatedPerformer" to case.isolatedPerformer, "spaceBypassed" to false),
                "sourceIds" to selected.map { it.id }, "sourcePaths" to selected.map { "sources/${it.id}.wav" },
                "sourcePcmHashes" to selected.map { it.source.hash },
            )) as JsonValue.Obj
            record(case.id, case.group, case.title, case.description, report.snip, recipeJson,
                report.diagnosticsJson(), renderMs, linkedMapOf(
                    "profile" to case.profile.name, "performerCount" to if (case.isolatedPerformer == null) case.count else 1,
                    "rosterCount" to case.count,
                    "sourceIds" to selected.map { it.id }, "macros" to macros,
                    "evidence" to mapOf("fixedIdentities" to case.fixedIdentities, "coordinationEnabled" to case.coordination,
                        "isolatedPerformer" to case.isolatedPerformer, "hold" to false, "roomBypassedBySpaceZero" to (case.space == 0f)),
                ))
            if (case.id == "crowd_4") portableCheck = verifyMovedRecipe(root, recipe, options, report.snip)
        }

        val roleSources = linkedMapOf(
            ConfluenceRole.FOUNDATION to primaryVowel,
            ConfluenceRole.AIRY to airy,
            ConfluenceRole.ANSWER to primaryPhrase,
        )
        val airyToAnswerDurationRatio = regionSeconds(airy) / regionSeconds(primaryPhrase)
        require(airyToAnswerDurationRatio in .75f..1.5f) {
            "this audition requires comparably complete airy and answering phrases; source duration ratio is $airyToAnswerDurationRatio"
        }
        val assemblyRecipe = ConfluenceAssemblyRecipe(
            name = "Low foundation, breathy upper voices and a clear answer",
            roles = listOf(
                ConfluenceRoleSpec(ConfluenceRole.FOUNDATION, primaryVowel.region, listOf(0, 1, 2), .52f),
                ConfluenceRoleSpec(ConfluenceRole.AIRY, airy.region, listOf(3, 4, 5), .32f),
                ConfluenceRoleSpec(ConfluenceRole.ANSWER, primaryPhrase.region, listOf(6, 7), 1f),
            ),
            seed = SEED, seconds = 24f, space = .20f, divergence = .25f,
        )
        val assemblyStarted = System.nanoTime()
        val assembly = ConfluenceAssembly.render(assemblyRecipe, resolved, normalize = false)
        val assemblyMs = elapsed(assemblyStarted)
        val roleExposure = assemblyRecipe.roles.associate { spec ->
            val entrances = assembly.events.filter { it.role == spec.role && it.state == "ENTER" }
            val durations = entrances.map { entrance ->
                val release = assembly.events.single { it.role == spec.role && it.performerId == entrance.performerId &&
                    it.eventIndex == entrance.eventIndex && it.state == "RELEASE" }
                (release.endFrame - entrance.startFrame) / 44_100f
            }
            spec.role.name to mapOf(
                "sourceId" to roleSources.getValue(spec.role).id,
                "sourceRegionSeconds" to if (spec.role == ConfluenceRole.FOUNDATION)
                    (spec.region.sustainEndFrame!! - spec.region.sustainStartFrame!!) / 44_100f
                    else (spec.region.endFrame - spec.region.startFrame) / 44_100f,
                "completePhrasesPerPerformer" to entrances.groupingBy { it.performerId }.eachCount().mapKeys { it.key.toString() },
                "minimumPerformedPhraseSeconds" to durations.minOrNull(),
                "maximumPerformedPhraseSeconds" to durations.maxOrNull(),
                "firstEntranceSeconds" to entrances.minOf { it.startFrame } / 44_100.0,
                "lastReleaseSeconds" to assembly.events.filter { it.role == spec.role && it.state == "RELEASE" }.maxOf { it.endFrame } / 44_100.0,
            )
        }
        val roleSum = sum(ConfluenceRole.entries.map { assembly.rawStems.getValue(it) })
        val stemSumError = roleSum.samples.indices.maxOf { abs(roleSum.samples[it] - assembly.raw[it]) }
        check(stemSumError <= 2e-6f) { "role stems do not reconstruct the full raw gathering: $stemSumError" }
        val fullMatched = AuditionLevel.level(assembly.mix)
        val familyGains = SharedGains(
            minOf(1f, .99f / assembly.mix.peak().coerceAtLeast(1e-9f)),
            fullMatched.peak() / assembly.mix.peak().coerceAtLeast(1e-9f), "role_gathering",
        )
        val roleCases = listOf(
            RoleCase("low_foundation", "Low foundation", "Three persistent low voices use the actual low source's marked sustain, with individual breath rests. This is the foundation stem from the full gathering, at its original mix level.", setOf(ConfluenceRole.FOUNDATION)),
            RoleCase("airy_upper", "Complete airy upper phrase", "Three upper voices carry the full native 7.20-second breathy arpeggio, C4 to C5 and back to C4. It is a complete recorded phrase, not a lengthened or repeated short vowel; this part keeps its full-gathering schedule and mix level.", setOf(ConfluenceRole.AIRY)),
            RoleCase("clear_answer", "Clear answering phrase", "Two responders carry the complete 7.25-second marked sung phrase after the upper group's complete breathy phrase. The full schedule remains in the trace while only this part is heard.", setOf(ConfluenceRole.ANSWER)),
            RoleCase("foundation_airy", "Foundation and airy upper group", "The same low bed and breathy upper group together, before the answering phrase is added. Their gain and entrances are unchanged.", setOf(ConfluenceRole.FOUNDATION, ConfluenceRole.AIRY)),
            RoleCase("role_gathering", "The complete gathering", "The three roles together: a low foundation, one complete 7.20-second breathy upper call and its 7.25-second sung answer, then a quiet foundation coda. Compare with the isolated parts without changing volume.", ConfluenceRole.entries.toSet()),
        )
        for (case in roleCases) {
            val audio = if (case.roles.size == ConfluenceRole.entries.size) assembly.mix
                else sum(case.roles.map { assembly.rawStems.getValue(it) })
            val selectedSources = case.roles.map { roleSources.getValue(it) }
            val selectedEvents = assembly.events.filter { it.role in case.roles }
            val firstEntranceSeconds = selectedEvents.filter { it.state == "ENTER" }.minOf { it.startFrame } / 44_100.0
            val lastReleaseSeconds = selectedEvents.filter { it.state == "RELEASE" }.maxOf { it.endFrame } / 44_100.0
            val recipeJson = json(linkedMapOf(
                "format" to "snipsnap-confluence-assembly-audition", "version" to GENERATOR_VERSION,
                "assembly" to Json.parse(assemblyRecipe.toJsonText()),
                "selectedRoles" to case.roles.map { it.name }, "normalize" to false,
                "sourceIds" to roleSources.values.map { it.id }, "sourcePaths" to roleSources.values.map { "sources/${it.id}.wav" },
                "sourcePcmHashes" to roleSources.values.map { it.source.hash },
                "playbackGains" to mapOf("raw" to familyGains.raw, "matched" to familyGains.matched, "referenceId" to familyGains.referenceId),
            )) as JsonValue.Obj
            val diagnostics = JsonValue.Obj(LinkedHashMap(assembly.diagnosticsJson().entries).apply {
                put("selectedRoles", json(case.roles.map { it.name }))
                put("allRolesScheduled", JsonValue.Bool(true))
                put("stemSumRawMaximumAbsoluteError", json(stemSumError))
                put("roleExposure", json(roleExposure))
            })
            record(case.id, "roles", case.title, case.description, audio, recipeJson, diagnostics, assemblyMs,
                linkedMapOf(
                    "profile" to "ASSEMBLY", "performerCount" to assemblyRecipe.roles.filter { it.role in case.roles }.sumOf { it.performerIds.size },
                    "rosterCount" to 8, "roleIds" to case.roles.map { it.name },
                    "firstEntranceSeconds" to firstEntranceSeconds, "lastReleaseSeconds" to lastReleaseSeconds,
                    "sourceIds" to selectedSources.map { it.id }, "assemblySourceIds" to roleSources.values.map { it.id },
                    "evidence" to mapOf("sharedSchedule" to true, "sharedMasterGain" to true, "hold" to false,
                        "fullGatheringReferenceId" to "role_gathering", "stemSumRawMaximumAbsoluteError" to stemSumError,
                        "completedExchanges" to assembly.exchanges, "roleExposure" to roleExposure),
                ), familyGains)
        }
        val assemblyPortableCheck = verifyMovedAssembly(root, assemblyRecipe, assembly.mix)
        val kit = writeRenderedKit(root, clips, renderedById)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to GENERATOR_VERSION, "title" to "Confluence · a gathering in three roles",
            "engineVersion" to Confluence.MODEL, "analysisVersion" to Confluence.ANALYSIS_VERSION, "generatorVersion" to GENERATOR_VERSION,
            "implementationSources" to implementationSources,
            "runtime" to runtime(),
            "sourceRevision" to git("rev-parse", "HEAD"), "workingTreeChanges" to git("status", "--short"),
            "sampleRate" to 44_100, "channels" to 1, "bitDepth" to 24, "seed" to SEED,
            "matchedLoudestWindowRmsTarget" to MATCHED_LOUDEST_WINDOW_RMS,
            "levelPolicy" to "Existing proof clips: repository AuditionLevel, loudest 200 ms filtered RMS .03 with .99 peak guard. Three-role family: one matched gain derived from the full gathering, also applied to every stem and pair; isolated parts intentionally remain quieter. Raw: prelevel renderer output, with the same full-derived peak guard across the role family. Silence and rests are not removed.",
            "status" to "feasibility-prototype-awaiting-owner-listening",
            "scope" to "Actual licensed human vocal sources; conservative offline transformed variants, persistent seeded roster, complete phrase schedules and a shared mono room. No 48-voice or seamless HOLD claim; no native patch/kit editing integration.",
            "limitations" to listOf("A transformed copy is not a new human singer.", "Pitch/formant quality and phrase-boundary integrity still need listening.",
                "Phrase regions were selected from source pauses and waveform/pitch continuity; individual words were not verified by listening or transcription.",
                "Coordination changes bounded future entrance timing only; no pitch-consensus model is implemented.",
                "The source bundle is a reproducible prototype archive, not a native SnipSnap project.", "The rendered MPC kit needs no source assets for playback; editing the prototype needs the separate source bundle."),
            "sources" to sourceRecords,
            "sourceProvenance" to fixtureJson.filterKeys { it != "sources" }.mapValues { native(it.value) },
            "groups" to groups, "clips" to clips,
            "portableSourceCheck" to portableCheck, "renderedKit" to kit,
            "assemblyPortableSourceCheck" to assemblyPortableCheck,
            "assembly" to mapOf("referenceId" to "role_gathering", "roleIds" to ConfluenceRole.entries.map { it.name },
                "performersPerRole" to assemblyRecipe.roles.associate { it.role.name to it.performerIds.size },
                "seconds" to assemblyRecipe.seconds, "matchingPolicy" to "shared-gathering-gain",
                "matchedGain" to familyGains.matched, "rawExportGain" to familyGains.raw,
                "stemSumRawMaximumAbsoluteError" to stemSumError, "completedExchanges" to assembly.exchanges,
                "airySourceSeconds" to regionSeconds(airy), "answerSourceSeconds" to regionSeconds(primaryPhrase),
                "airyToAnswerSourceDurationRatio" to airyToAnswerDurationRatio,
                "roleExposure" to roleExposure),
            "sourceBundlePath" to "confluence-source-bundle.zip",
            "generationMilliseconds" to elapsed(generationStarted),
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(root, "SOURCE-BUNDLE.txt").writeText(
            "Confluence recorded-source and three-role audition.\n\nRecipes and canonical human-source WAVs are bundled with their content hashes and license attribution. " +
                "This is a prototype replay archive, not a native SnipSnap project. Use the matching repository revision and Confluence.render or ConfluenceAssembly.render with the recipe's options. " +
                "The assembly's isolated roles use the full gathering's schedule and common playback gains. The generator verifies both recipe types after moving their inputs to a new temporary directory. See manifest.json and sources/fixture-manifest.json.\n"
        )
        writeZip(root, File(root, "confluence-source-bundle.zip"), listOf("manifest.json", "SOURCE-BUNDLE.txt", "sources", "recipes", "diagnostics", "implementation"))
        println("Wrote ${clips.size} raw/matched pairs, exact recipes, traces, source bundle and rendered kit under $root")
    }

    private fun cases(phraseSeconds: Float, multiSource: Boolean): List<Case> = buildList {
        for (n in listOf(1, 2, 4, 8)) add(Case("crowd_$n", "crowd", "$n ${if (n == 1) "performer" else "performers"}, dry",
            "Same marked human vowel and seed. CROWD retains the existing roster slots as performers join; SPACE is zero.", count = n))
        add(Case("phrase_solo", "behavior", "One performer · original melodic contour", "One transformed performer carries the whole marked sung source region in native order. Compare its melody and articulation with the original phrase before judging the four-voice arrangement.",
            count = 1, fixtureKind = "phrase", seconds = phraseSeconds))
        add(Case("phrase_unison", "behavior", "Complete phrase · UNISON", "Four performers carry the complete marked phrase in source order, rest and reenter when another whole phrase fits.",
            count = 4, fixtureKind = "phrase", seconds = phraseSeconds))
        add(Case("phrase_response", "behavior", "Complete phrase · RESPONSE", "Two sections answer after completed source phrases. The longer file permits a complete answer rather than cutting off a word.",
            profile = ConfluenceProfile.RESPONSE, count = 4, fixtureKind = "phrase", seconds = phraseSeconds, response = .9f))
        add(Case("drone_4", "behavior", "Four breathing sustained voices", "Four performers reuse only the marked sustained vowel and take staggered rests. No consonants are stretched into a drone.",
            profile = ConfluenceProfile.DRONE, count = 4))
        add(Case("drone_8", "behavior", "Eight breathing sustained voices", "The same marked vowel and seeded slots with eight participants, still dry.", profile = ConfluenceProfile.DRONE))
        for (n in listOf(2, 8)) for (d in listOf(.1f, .8f)) add(Case("crowd_${n}_div_${tag(d)}", "controls", "$n performers · divergence ${label(d)}",
            "CROWD × DIVERGENCE corner: compare individual delivery and agreement at the same loudness. Register and source stay fixed.", count = n, divergence = d))
        for (r in listOf(0f, 1f)) for (d in listOf(.1f, .8f)) add(Case("register_${tag(r)}_div_${tag(d)}", "controls", "Register ${label(r)} · divergence ${label(d)}",
            "REGISTER × DIVERGENCE corner on eight persistent vowel variants. Register shifts are deliberately conservative in this prototype.", register = r, divergence = d))
        for (s in listOf(0f, .75f)) add(Case("response_space_${tag(s)}", "controls", "Answering vowels · space ${label(s)}",
            "The same eight-performer answering arrangement, close or in the shared mono room. Listen for section clarity and room masking.",
            profile = ConfluenceProfile.RESPONSE, seconds = 18f, response = .9f, space = s))
        add(Case("identity_fixed", "identity", "Fixed identities across rests", "Eight stable performer slots repeat the marked vowel with their habitual pitch, tone, timing and breathing tendencies intact.", seconds = 12f, divergence = .65f))
        add(Case("identity_reshuffled", "identity", "Identities reshuffled at each phrase", "Diagnostic ablation: the same seed and source deliberately choose a new identity per phrase, exposing what persistence contributes.", seconds = 12f, divergence = .65f, fixedIdentities = false))
        for (slot in listOf(0, 3)) add(Case("identity_solo_$slot", "identity", "Roster slot ${slot + 1}, before and after rest",
            "Only stable roster slot ${slot + 1} is audible from the eight-performer schedule. Listen across its silence and reentry; these are transformed variants of one singer.",
            seconds = 12f, divergence = .65f, isolatedPerformer = slot))
        for (on in listOf(true, false)) add(Case("coordination_${if (on) "on" else "off"}", "coordination", "Section following ${if (on) "on" else "off"}",
            "Same seed, identities and marked vowel. The causal trace shows how bounded following changes subsequent entrances; judge whether the timing difference is musically useful.",
            seconds = 12f, divergence = .75f, response = .55f, coordination = on))
        if (multiSource) add(Case("two_source_gathering", "behavior", "Two actual source singers", "Four slots alternate between the male and female human vowel recordings, avoiding the claim that one recording creates four different people.", count = 4, multiSource = true))
    }

    private fun loadFixture(root: File, metadata: Map<String, JsonValue>): Fixture {
        val id = metadata.getValue("id").str()
        require(Regex("[a-z0-9-]+").matches(id)) { "unsafe fixture ID: $id" }
        val path = metadata.getValue("path").str()
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) { "missing or unsafe fixture: $path" }
        metadata["sha256"]?.str()?.let { require(sha256(file) == it) { "fixture hash changed: $id" } }
        val snip = WavReader.read(file)
        require(snip.channels == 1 && snip.sampleRate == 44_100)
        val source = ConfluenceSource.fromSnip(snip)
        fun int(key: String, default: Int): Int = metadata[key]?.int() ?: default
        val start = int("phraseStartFrame", 0)
        val end = int("phraseEndFrame", snip.frameCount)
        val sustainStart = metadata["sustainStartFrame"]?.takeUnless { it == JsonValue.Null }?.int()
        val sustainEnd = metadata["sustainEndFrame"]?.takeUnless { it == JsonValue.Null }?.int()
        val reference = metadata["referenceMidi"]?.takeUnless { it == JsonValue.Null }?.num()?.toFloat()
        val region = ConfluenceRegion(source.hash, start, end, ConfluenceSourceMode.PITCHED, reference, sustainStart, sustainEnd)
        return Fixture(id, file, source, region, metadata)
    }

    private fun copySources(root: File, fixtures: List<Fixture>, manifest: File): List<Map<String, Any?>> {
        File(root, "sources").mkdirs()
        manifest.copyTo(File(root, "sources/fixture-manifest.json"), overwrite = true)
        for (name in listOf("README.md", "LICENSE.txt", "LICENSE.md", "LICENSE-CC-BY-4.0.txt", "provenance.json")) {
            File(manifest.parentFile, name).takeIf { it.isFile }?.copyTo(File(root, "sources/$name"), overwrite = true)
        }
        return fixtures.map { fixture ->
            val path = "sources/${fixture.id}.wav"
            val copied = fixture.file.copyTo(File(root, path), overwrite = true)
            fixture.metadata.mapValues { native(it.value) } + mapOf(
                "path" to path, "sha256" to sha256(copied), "pcmSha256" to fixture.source.hash,
                "frames" to fixture.source.frameCount, "sampleRate" to 44_100,
            )
        }
    }

    private fun referenceTimeline(fixture: Fixture, seconds: Float): Snip {
        val source = WavReader.read(fixture.file).samples
        val r = fixture.region
        val phrase = source.copyOfRange(r.startFrame, r.endFrame)
        val out = FloatArray((seconds * 44_100).toInt())
        var cursor = (.15f * 44_100).toInt()
        // Match the renderer's terminal phrase-admission reserve without transforming the source.
        val admissionEnd = out.size - (1.25f * 44_100).toInt()
        while (cursor + phrase.size <= admissionEnd) {
            phrase.copyInto(out, cursor)
            cursor += phrase.size + (.65f * 44_100).toInt()
        }
        require(out.any { it != 0f }) { "reference duration cannot contain the complete selected phrase" }
        return Snip(out, 1, 44_100)
    }

    private fun copyImplementation(root: File, fixtureRoot: File): List<Map<String, Any?>> {
        val repo = (generateSequence(fixtureRoot) { it.parentFile } +
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile })
            .firstOrNull { File(it, "synth/src/main/kotlin/com/snipsnap/synth/Confluence.kt").isFile }
            ?: error("cannot locate the exact Confluence implementation for the replay bundle")
        val paths = listOf("synth/src/main/kotlin/com/snipsnap/synth/Confluence.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ConfluenceRecipe.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ConfluenceVoice.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ConfluenceAssembly.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ConfluenceAuditionGenerator.kt")
        return paths.map { path ->
            val source = File(repo, path)
            val bundled = "implementation/${source.name}"
            File(root, bundled).apply { parentFile.mkdirs(); source.copyTo(this, overwrite = true) }
            mapOf("repositoryPath" to path, "path" to bundled, "sha256" to sha256(source))
        }.also {
            for (name in listOf("LICENSE", "LICENSE.md", "LICENSE.txt")) File(repo, name).takeIf { it.isFile }
                ?.copyTo(File(root, "implementation/SnipSnap-$name"), overwrite = true)
        }
    }

    private fun runtime(): Map<String, Any?> = linkedMapOf(
        "javaVersion" to System.getProperty("java.version"), "javaVendor" to System.getProperty("java.vendor"),
        "osName" to System.getProperty("os.name"), "osVersion" to System.getProperty("os.version"),
        "architecture" to System.getProperty("os.arch"), "availableProcessors" to Runtime.getRuntime().availableProcessors(),
        "maximumJvmHeapBytes" to Runtime.getRuntime().maxMemory(),
        "cpuModel" to runCatching { File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
        } }.getOrNull(),
    )

    private fun referenceRecipe(fixture: Fixture, seconds: Float, operation: String): JsonValue.Obj = json(linkedMapOf(
        "format" to "snipsnap-confluence-reference", "version" to GENERATOR_VERSION, "operation" to operation,
        "sourceId" to fixture.id, "sourcePath" to "sources/${fixture.id}.wav", "sourcePcmHash" to fixture.source.hash,
        "startFrame" to fixture.region.startFrame, "endFrame" to fixture.region.endFrame,
        "seconds" to seconds, "initialSilenceSeconds" to .15, "restSeconds" to .65,
        "terminalAdmissionReserveSeconds" to 1.25,
        "ensemble" to if (operation == "ensemble-section") mapOf("SECTION" to 1f, "WIDTH" to 0f, "DEPTH" to .5f, "RATE" to .5f) else null,
    )) as JsonValue.Obj

    private fun verifyMovedRecipe(root: File, recipe: ConfluenceRecipe, options: ConfluenceOptions, expected: Snip): Map<String, Any?> {
        val moved = Files.createTempDirectory("confluence-portable-check-").toFile()
        return try {
            File(root, "sources").copyRecursively(File(moved, "sources"), overwrite = true)
            File(moved, "recipe.json").writeText(recipe.toJsonText())
            val loaded = ConfluenceRecipe.fromJsonText(File(moved, "recipe.json").readText())
            val sources = File(moved, "sources").listFiles().orEmpty().filter { it.extension == "wav" }
                .map { ConfluenceSource.fromSnip(WavReader.read(it)) }.associateBy { it.hash }
            val actual = Confluence.render(loaded, sources, options).snip
            check(expected.samples.contentEquals(actual.samples)) { "moved source recipe changed its rendered samples" }
            mapOf("caseId" to "crowd_4", "passed" to true, "sampleIdentical" to true,
                "absolutePathsUsed" to false, "sourceCacheUsed" to false,
                "method" to "Copied canonical sources and serialized recipe to a new temporary directory, rebuilt source objects, rerendered and compared every float.")
        } finally { moved.deleteRecursively() }
    }

    private fun verifyMovedAssembly(root: File, recipe: ConfluenceAssemblyRecipe, expected: Snip): Map<String, Any?> {
        val moved = Files.createTempDirectory("confluence-assembly-portable-check-").toFile()
        return try {
            File(root, "sources").copyRecursively(File(moved, "sources"), overwrite = true)
            File(moved, "assembly.json").writeText(recipe.toJsonText())
            val loaded = ConfluenceAssemblyRecipe.fromJsonText(File(moved, "assembly.json").readText())
            val sources = File(moved, "sources").listFiles().orEmpty().filter { it.extension == "wav" }
                .map { ConfluenceSource.fromSnip(WavReader.read(it)) }.associateBy { it.hash }
            val actual = ConfluenceAssembly.render(loaded, sources, normalize = false).mix
            check(expected.samples.contentEquals(actual.samples)) { "moved assembly source recipe changed its rendered samples" }
            mapOf("caseId" to "role_gathering", "passed" to true, "sampleIdentical" to true,
                "absolutePathsUsed" to false, "sourceCacheUsed" to false,
                "method" to "Copied all three assigned canonical sources and the serialized assembly recipe to a new directory, rebuilt source objects, rerendered and compared every float.")
        } finally { moved.deleteRecursively() }
    }

    private fun writeRenderedKit(root: File, clips: List<Map<String, Any?>>, audio: Map<String, Snip>): Map<String, Any?> {
        val provenance = Json.parse(File(root, "sources/fixture-manifest.json").readText()).obj()
        val originalCredit = provenance.getValue("sources").arr().first().obj().getValue("attribution").str()
        val credit = "$originalCredit Source: ${provenance.getValue("sourceUrl").str()}. License: ${provenance.getValue("licenseUrl").str()}. " +
            "Modified by source-specific cropping, constant gain, source-based pitch/formant/timing processing, ensemble scheduling and optional room rendering. The full 7.20-second breathy arpeggio retains its natural entrance and release; no short vowel was stretched or repeated to lengthen that source."
        val ids = listOf("crowd_1", "crowd_2", "crowd_4", "crowd_8", "phrase_solo", "phrase_unison", "phrase_response", "drone_4", "drone_8", "two_source_gathering", "role_gathering")
            .filter { it in audio }
        val dir = File(root, "rendered-kit/Confluence First Proof").apply { mkdirs() }
        val pads = ids.mapIndexed { i, id ->
            val file = "A${(i + 1).toString().padStart(2, '0')}_$id.wav"
            WavWriter.write(File(dir, file), audio.getValue(id), WavWriter.BitDepth.PCM_24)
            KitPad(i + 1, file, clips.single { it["id"] == id }.getValue("title").toString(), DrumClass.UNKNOWN,
                source = mapOf("origin" to "confluence-feasibility-prototype", "auditionCase" to id,
                    "attribution" to credit,
                    "recipe" to "See the separate confluence-source-bundle.zip; rendered pad playback is source independent."))
        }
        val kit = Kit("Confluence First Proof", pads)
        KitStore.save(kit, dir)
        File(root, "rendered-kit/SOURCE-ATTRIBUTION.txt").writeText(credit + "\n")
        File(root, "sources/LICENSE-CC-BY-4.0.txt").takeIf { it.isFile }
            ?.copyTo(File(root, "rendered-kit/LICENSE-CC-BY-4.0.txt"), overwrite = true)
        val findings = Preflight.check(kit, dir)
        check(findings.none { it.severity == Severity.FAIL })
        Mpc3Exporter.exportTrack(kit, dir, File(root, "rendered-kit/mpc"), overwrite = true, mpc2Twin = true)
        XpnPackager.write(kit, dir, File(root, "confluence-rendered-kit.xpn"),
            ExpansionMeta("Confluence First Proof", identifier = "app.snipsnap.confluence.prototype", version = "0.2.0",
                description = "Human-source vocal ensemble feasibility renders. $credit Prototype sources and recipes are supplied separately."), overwrite = true)
        val zip = File(root, "confluence-rendered-kit.zip")
        var zipContents = listOf("rendered-kit")
        writeZip(root, zip, zipContents)
        if (zip.length() > 25L * 1024 * 1024) {
            // Keep one copy of the WAVs when the full native+MPC package exceeds hosting's file limit.
            zipContents = listOf("rendered-kit/mpc", "rendered-kit/SOURCE-ATTRIBUTION.txt", "rendered-kit/LICENSE-CC-BY-4.0.txt")
            writeZip(root, zip, zipContents)
        }
        check(zip.length() <= 25L * 1024 * 1024) { "rendered kit ZIP exceeds the hosting asset limit" }
        return mapOf("xpnPath" to "confluence-rendered-kit.xpn", "zipPath" to "confluence-rendered-kit.zip",
            "nativeKitPath" to "rendered-kit/Confluence First Proof/kit.json", "pads" to ids.size,
            "caseIds" to ids, "preflight" to findings.map { mapOf("severity" to it.severity.name, "message" to it.message) },
            "zipContents" to zipContents,
            "editableEngine" to false, "levelPolicy" to "The same matched 24-bit audio heard on the audition page; ordinary unpitched pads, no implicit auto-retune.")
    }

    private fun writeZip(root: File, output: File, paths: List<String>) {
        val files = paths.flatMap { path -> File(root, path).let { if (it.isDirectory) it.walkTopDown().filter(File::isFile).toList() else listOf(it) } }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 1_577_836_800_000L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun metrics(snip: Snip): Map<String, Any> {
        var sum = 0.0; var energy = 0.0
        for (sample in snip.samples) { sum += sample; energy += sample.toDouble() * sample }
        return linkedMapOf("peak" to snip.peak(), "rms" to sqrt(energy / snip.samples.size),
            "dc" to sum / snip.samples.size, "loudestWindowRms" to Loudness.of(snip), "finite" to snip.samples.all(Float::isFinite))
    }

    private data class Quad(val fixture: Fixture, val id: String, val title: String, val seconds: Float)
    private fun regionSeconds(fixture: Fixture) = (fixture.region.endFrame - fixture.region.startFrame) / 44_100f
    private fun gain(snip: Snip, amount: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * amount }, snip.channels, snip.sampleRate)
    private fun sum(parts: List<Snip>): Snip {
        require(parts.isNotEmpty() && parts.all { it.channels == 1 && it.sampleRate == 44_100 && it.frameCount == parts.first().frameCount })
        return Snip(FloatArray(parts.first().frameCount) { i -> parts.sumOf { it.samples[i].toDouble() }.toFloat() }, 1, 44_100)
    }
    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1e6
    private fun group(id: String, title: String, description: String) = mapOf("id" to id, "title" to title, "description" to description)
    private fun tag(v: Float) = (v * 100).toInt().toString().padStart(3, '0')
    private fun label(v: Float) = (v * 100).toInt().toString() + "%"
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun native(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Arr -> value.items.map(::native)
        is JsonValue.Obj -> value.entries.mapValues { native(it.value) }
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported Confluence manifest value: ${value.javaClass}")
    }
}
