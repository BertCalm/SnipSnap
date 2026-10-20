package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A focused rhythmic-arc revision: twenty positions, one pitch,
 * one velocity and one seed. Companion taps come from the exact DRAMA .5 performance.
 * The page embeds its manifest so an extracted pack works over file:// as well as HTTP.
 */
object TrioAuditionGenerator {
    private const val MIDI = 60
    private const val VELOCITY = 1f
    private val config = TrioConfig(seed = 47010)
    private val baseline = linkedMapOf("TOUCH" to .55f, "SIDES" to (1f / 6f),
        "MOTION" to 0f, "DRAMA" to 0f, "SPACE" to .35f, "DAMP" to .48f)
    private val husbands = listOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)
    private data class ListeningStep(val id: String, val label: String, val clipId: String,
        val drama: Float, val listenFor: String)
    private val listeningRoute = listOf(
        ListeningStep("low", "Low · DRAMA .25", "drama_025", .25f, "Listen for spaced husband replies."),
        ListeningStep("middle", "Middle · DRAMA .5", "drama_050", .5f, "Listen for the husbands answering between the wives' gestures."),
        ListeningStep("high", "High · DRAMA 1", "drama_100", 1f, "Listen for the accepted overlapping husband entrances."),
    )

    private data class Group(val id: String, val title: String, val description: String)
    private val groups = listOf(
        Group("arc", "Low / Middle / High", "The wives keep their established DRAMA response. Listen to the husbands' changing spacing; each still makes one hit and rings out."),
        Group("scene", "Previous / Rhythmic arc", "Each pair holds DRAMA at the same setting. Pitch, velocity, seed and the wives' performance are identical within that pair."),
        Group("husbands", "Just the husbands", "The three husband contributions summed from each actual scene. Hear their spacing clearly, then compare the full scene above."),
        Group("drama", "The rest of the range", "DRAMA 0 remains wives only. These additional settings join Low, Middle and High to cover seven positions across the control."),
        Group("companions", "Each husband at Middle", "Oud, bouzouki and phin, isolated from the same DRAMA .5 scene. Matched mode reveals character; raw mode preserves the scene balance."),
        Group("players", "Meet the three players", "The primary players and baseline trio, all at DRAMA 0."),
    )
    private data class Case(val id: String, val group: String, val label: String,
        val description: String, val voice: TrioVoice = TrioVoice.ENSEMBLE,
        val changes: Map<String, Float> = emptyMap(), val actor: TrioActor? = null,
        val model: Int = Trio.MODEL_VERSION, val compareId: String? = null,
        val beforeLabel: String = "Previous", val afterLabel: String = "Rhythmic arc",
        val husbandsOnly: Boolean = false, val sourceSceneId: String? = null,
        val listenFor: String? = null, val playLabel: String = "Play clip")
    private data class RenderKey(val voice: TrioVoice, val macros: Map<String, Float>, val config: TrioConfig)

    private fun roster() = buildList {
        for (step in listeningRoute) add(Case(step.clipId, "arc", step.label,
            "The full scene at this DRAMA setting. The recorded attack markers below come from the actual render.",
            changes = mapOf("DRAMA" to step.drama), listenFor = step.listenFor,
            playLabel = "Play ${step.id.replaceFirstChar { it.titlecase(Locale.ROOT) }}"))
        for ((value, tag) in listOf(.25f to "025", .5f to "050", .75f to "075")) {
            add(Case("previous_drama_$tag", "scene", "DRAMA ${display(value)} · compare",
                "Hear the previous scene, then the new rhythmic relationship, with DRAMA held at ${display(value)}.",
                changes = mapOf("DRAMA" to value), model = 4, compareId = "drama_$tag"))
        }
        for (step in listeningRoute) add(Case("husbands_${step.id}", "husbands", "${step.label} · husbands only",
            "Only oud, bouzouki and phin from the corresponding full scene. Their patch and pad recipe recreate that whole scene.",
            changes = mapOf("DRAMA" to step.drama), husbandsOnly = true, sourceSceneId = step.clipId,
            listenFor = step.listenFor))
        for ((value, tag) in listOf(0f to "000", .7f to "070", .75f to "075", .85f to "085")) {
            add(Case("drama_$tag", "drama", "DRAMA ${display(value)}",
                "The same trio relationship at an additional point in the rhythmic arc.", changes = mapOf("DRAMA" to value)))
        }
        for (actor in husbands) {
            val name = actor.name.lowercase(Locale.ROOT)
            add(Case("tap_$name", "companions", "${name.replaceFirstChar { it.titlecase(Locale.ROOT) }} · DRAMA .5",
                "One triggered $name contribution, left to ring out, captured from the latest DRAMA .5 scene. Its saved recipe recreates that scene.",
                changes = mapOf("DRAMA" to .5f), actor = actor, sourceSceneId = "drama_050"))
        }
        add(Case("tres", "players", "Tres", "Staggered attacks across doubled courses.", TrioVoice.TRES))
        add(Case("berimbau", "players", "Berimbau", "A struck wire, gourd body and saved pressure gesture.", TrioVoice.BERIMBAU))
        add(Case("charango", "players", "Charango", "Bright paired strings, articulated by repeated plucks.", TrioVoice.CHARANGO))
        add(Case("between_alliances", "players", "Trio · between alliances", "The three players together, midway between two fixed alliances."))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: TrioAuditionGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/trio-audition")
        check(root.isDirectory || root.mkdirs()) { "Cannot create ${root.absolutePath}" }
        val repository = listOf(File("."), File("..")).firstOrNull {
            File(it, "synth/src/main/kotlin/com/snipsnap/synth/Trio.kt").isFile
        }?.canonicalFile ?: error("Run from the repository root or synth module")
        val sourcePaths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Trio.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TrioV1.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TrioV2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TrioV3.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TrioV4.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TrioPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Modes.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/TrioAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "synth/src/test/resources/trio-audition/index.html",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt",
        )
        val sources = sourcePaths.associateWith { path -> sha256(File(repository, path).readBytes()) }
        val cases = roster()
        check(cases.size == 20 && cases.map { it.id }.distinct().size == cases.size)
        val patchValues = cases.map { case -> patch(case).toJsonValue() }
        val recipeHash = sha256(Json.write(arr(cases.mapIndexed { index, case ->
            obj("id" to str(case.id), "tap" to str(tapName(case)), "patch" to patchValues[index])
        })).toByteArray(Charsets.UTF_8))
        val sourceHash = sha256(Json.write(obj(*sources.map { it.key to str(it.value) }.toTypedArray())).toByteArray(Charsets.UTF_8))
        val round = "trio-${sourceHash.take(12)}-${recipeHash.take(12)}"
        val probes = HashMap<RenderKey, TrioProbe>()
        val clips = ArrayList<JsonValue>()

        for (case in cases) {
            val patch = patch(case)
            val key = RenderKey(case.voice, patch.macros, patch.config)
            val performanceId = sha256(Json.write(patch.toJsonValue().let { value ->
                JsonValue.Obj(value.entries.filterKeys { it != "name" })
            }).toByteArray(Charsets.UTF_8)).take(16)
            val reused = probes.containsKey(key)
            val probe = probes.getOrPut(key) { Trio.renderProbe(case.voice, patch.macros, MIDI, VELOCITY, patch.config) }
            check(probe.samples.isNotEmpty() && probe.samples.all { it.isFinite() }) { "Invalid mix: ${case.id}" }
            check(probe.actors.values.all { tap -> tap.size == probe.samples.size && tap.all { it.isFinite() } }) { "Invalid actor tap: ${case.id}" }
            check(probe.events.all { it.timeSeconds.isFinite() && it.energy.isFinite() }) { "Invalid event: ${case.id}" }
            val husbandSum = if (husbands.all { it in probe.actors }) sumActors(probe, husbands) else null
            val husbandSumHash = husbandSum?.let(::floatHash)
            val selectedActors = when {
                case.husbandsOnly -> husbands
                case.actor != null -> listOf(case.actor)
                else -> probe.actors.keys.toList()
            }
            val samples = when {
                case.husbandsOnly -> requireNotNull(husbandSum) { "Missing husbands in ${case.id}" }
                case.actor != null -> requireNotNull(probe.actors[case.actor]) { "Missing ${case.actor} in ${case.id}" }
                else -> probe.samples
            }
            val native = Snip(samples, 1, Dsp.RATE)
            val nativeMetrics = metrics(native)
            val peak = nativeMetrics.entries.getValue("peak").num()
            val rawGain = if (peak > .99) (.99 / peak).toFloat() else 1f
            val raw = scale(native, rawGain)
            val matched = AuditionLevel.level(native)
            val rawPath = "raw/$round/${case.id}.wav"
            val matchedPath = "matched/$round/${case.id}.wav"
            val patchPath = "patches/$round/${case.id}.json"
            val recipePath = "recipes/$round/${case.id}.json"
            val eventsPath = "diagnostics/$round/${case.id}_events.csv"
            val diagnosticPath = "diagnostics/$round/${case.id}.json"
            val events = probe.events.filter { it.actor in selectedActors }
            val husbandEntrances = arr(events.filter { it.actor in husbands }.map {
                obj("actor" to str(it.actor.name), "timeSeconds" to num(it.timeSeconds), "kind" to str(it.kind), "energy" to num(it.energy))
            })
            WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            writeText(root, patchPath, patch.toJsonText() + "\n")
            writeText(root, recipePath, PadRecipe(patch = patch).toJsonText() + "\n")
            writeText(root, eventsPath, buildString {
                append("actor,time_seconds,kind,energy\n")
                for (event in events) append("${event.actor.name},${event.timeSeconds},${csv(event.kind)},${event.energy}\n")
            })
            val actorSummary = arr(probe.actors.map { (actor, actorSamples) ->
                obj("actor" to str(actor.name), "eventCount" to num(probe.events.count { it.actor == actor }),
                    "nativeFloatSha256" to str(floatHash(actorSamples)),
                    "metrics" to metrics(Snip(actorSamples, 1, Dsp.RATE)))
            })
            val rawMetrics = metrics(raw)
            val matchedMetrics = metrics(matched)
            val detail = obj("id" to str(case.id), "performanceId" to str(performanceId),
                "tap" to str(tapName(case)), "tapActors" to arr(selectedActors.map { str(it.name) }),
                "sourceSceneId" to str(case.sourceSceneId ?: case.id), "patch" to patch.toJsonValue(),
                "nativeFloatSha256" to str(floatHash(samples)), "nativeMetrics" to nativeMetrics,
                "husbandSumNativeFloatSha256" to (husbandSumHash?.let(::str) ?: JsonValue.Null),
                "husbandEntrances" to husbandEntrances,
                "rawMetrics" to rawMetrics, "matchedMetrics" to matchedMetrics,
                "rawExportGain" to num(rawGain), "actorSummary" to actorSummary,
                "events" to arr(events.map { obj("actor" to str(it.actor.name), "timeSeconds" to num(it.timeSeconds),
                    "kind" to str(it.kind), "energy" to num(it.energy)) }))
            writeText(root, diagnosticPath, Json.write(detail) + "\n")
            val assetPaths = listOf(rawPath, matchedPath, patchPath, recipePath, eventsPath, diagnosticPath)
            clips += obj("id" to str(case.id), "group" to str(case.group), "label" to str(case.label),
                "description" to str(case.description), "voice" to str(case.voice.name),
                "model" to num(patch.config.model), "compareId" to (case.compareId?.let(::str) ?: JsonValue.Null),
                "beforeLabel" to str(case.beforeLabel), "afterLabel" to str(case.afterLabel),
                "listenFor" to (case.listenFor?.let(::str) ?: JsonValue.Null), "playLabel" to str(case.playLabel),
                "tap" to str(tapName(case)), "tapActors" to arr(selectedActors.map { str(it.name) }),
                "sourceSceneId" to str(case.sourceSceneId ?: case.id), "midi" to num(MIDI), "velocity" to num(VELOCITY),
                "macros" to obj(*patch.macros.map { it.key to num(it.value) }.toTypedArray()),
                "configuration" to configJson(patch.config), "frames" to num(samples.size), "seconds" to num(native.durationSeconds),
                "performanceId" to str(performanceId), "reusedPerformance" to bool(reused),
                "rawPath" to str(rawPath), "matchedPath" to str(matchedPath), "patchPath" to str(patchPath),
                "recipePath" to str(recipePath), "eventsPath" to str(eventsPath), "diagnosticsPath" to str(diagnosticPath),
                "recipeScope" to str(if (tapName(case) == "MIX") "source mix" else "source scene; diagnostic tap selected separately"),
                "eventCount" to num(events.size), "actorSummary" to actorSummary,
                "husbandEntrances" to husbandEntrances,
                "husbandSumNativeFloatSha256" to (husbandSumHash?.let(::str) ?: JsonValue.Null),
                "nativeMetrics" to nativeMetrics, "rawMetrics" to rawMetrics, "matchedMetrics" to matchedMetrics,
                "rawExportGain" to num(rawGain), "nativeFloatSha256" to str(floatHash(samples)),
                "assets" to obj(*assetPaths.map { it to str(sha256(File(root, it).readBytes())) }.toTypedArray()))
            println("TRIO audition ${clips.size}/${cases.size}: ${case.id}${if (reused) " (same performance tap)" else ""}")
        }

        check(sourcePaths.all { sha256(File(repository, it).readBytes()) == sources[it] }) {
            "TRIO sources changed during rendering; rebuild the listening pack"
        }
        val manifestValue = obj("engine" to str("TRIO"), "version" to num(1),
            "listeningRound" to str(round), "sampleRate" to num(Dsp.RATE), "channels" to num(1), "bitDepth" to num(24),
            "clipCount" to num(clips.size), "wavCount" to num(clips.size * 2), "renderCount" to num(probes.size),
            "sonicAcceptance" to str("Rhythmic DRAMA arc awaiting listener review; previous model-4 scenes remain available for direct comparison."),
            "currentModel" to num(Trio.MODEL_VERSION), "previousModel" to num(4),
            "heroClipId" to str("drama_025"), "initialClipId" to str("drama_025"),
            "meetClipId" to str("tres"), "companionTapSourceId" to str("drama_050"),
            "listeningRoute" to arr(listeningRoute.map { obj("id" to str(it.id), "label" to str(it.label),
                "clipId" to str(it.clipId), "listenFor" to str(it.listenFor), "drama" to num(it.drama)) }),
            "dramaSteps" to arr(listOf(0f, .25f, .5f, .7f, .75f, .85f, 1f).map(::num)),
            "configurations" to arr(cases.map { config.copy(model = it.model) }.distinct().map(::configJson)),
            "levelMethod" to str("Matched: loudest 200 ms window after a gentle low cut, target .03, peak guard .99. This is SnipSnap's perceived-loudness proxy, not integrated LUFS. Raw: native probe level, reduced only if peak exceeds .99; rawExportGain records any reduction."),
            "metricNotes" to str("Metrics describe float samples before 24-bit PCM quantization. DC is whole-file mean. Actor taps are actual contributions from one simulation. HUSBANDS sums OUD, BOUZOUKI and PHIN from that same source; husbandSumNativeFloatSha256 records the source sum, and sourceSceneId identifies its full scene. Attack markers use recorded event times on the same fixed clip-duration scale. Patches and pad recipes recreate the engine's final levelled source mix; the audition rebuild selects the recorded mix, actor tap or husband sum and applies the export method."),
            "rebuildCommand" to str("./gradlew :synth:generateTrioAudition"),
            "recipeSha256" to str(recipeHash), "sourceSha256" to str(sourceHash),
            "sourceFiles" to obj(*sources.map { it.key to str(it.value) }.toTypedArray()),
            "compiledEngineSha256" to str(classHash(Trio::class.java)),
            "compiledOriginalEngineSha256" to str(classHash(TrioV1::class.java)),
            "compiledPreviousEngineSha256" to str(classHash(TrioV4::class.java)),
            "compiledGeneratorSha256" to str(classHash(TrioAuditionGenerator::class.java)),
            "groups" to arr(groups.map { obj("id" to str(it.id), "title" to str(it.title), "description" to str(it.description)) }),
            "clips" to arr(clips))
        val manifest = Json.write(manifestValue) + "\n"
        writeText(root, "manifest.json", manifest)
        val page = TrioAuditionGenerator::class.java.getResourceAsStream("/trio-audition/index.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("Missing trio-audition/index.html")
        writeText(root, "index.html", page.replace("__MANIFEST__", manifest.replace("</", "<\\/")))
        writeText(root, "README.md", """
            # TRIO rhythmic DRAMA arc

            Open listen.html to hear the complete audition from one file. It embeds every WAV and downloadable evidence asset, and works over file:// and HTTP without a server or external dependencies. Alternatively, extract the complete folder and open index.html; that smaller page uses the neighboring raw/ and matched/ WAVs.

            Twenty clips, forty mono 44.1 kHz / 24-bit WAVs. All request C4 (MIDI 60), velocity 1, seed ${config.seed}. Start with Low (.25), Middle (.5), then High (1). Listen for a changing rhythmic conversation, and check the recorded attack strips. Previous / Rhythmic arc comparisons cover .25, .5 and .75. Three husbands-only sums expose the same Low / Middle / High scenes; three individual taps use Middle. Additional full scenes at 0, .7, .75 and .85 complete the seven-step range. Compare at matched level first, then check raw balance. Matching uses SnipSnap's loudest 200 ms perceived-loudness proxy at .03, with a .99 peak guard; it is not integrated LUFS. Raw files preserve the probe amplitude unless the recorded rawExportGain is below 1.

            The wives retain their established DRAMA response, including its existing intensification across the control. At the same DRAMA setting, Previous / Rhythmic arc wife performances are identical. Each husband keeps one hit and natural ring-out; the new rhythmic contrast is their changing attack spacing. DRAMA 0 remains wives only; DRAMA 1 retains the accepted overlapping scene. Previous clips explicitly save model 4; current clips save model ${Trio.MODEL_VERSION}. Source configurations, per-actor native sample hashes and event CSVs make each comparison inspectable. Primary solo identities and the baseline trio use DRAMA 0. Earlier auditions remain in the archived packs.

            Individual husband taps share the performanceId of drama_050. Each husbands-only sum shares its sourceSceneId and performanceId with the corresponding Low / Middle / High scene. tapActors lists exactly which actual contributions were selected. Their patch and pad recipe reproduce the corresponding complete source scene, not a separate instrument. The rebuild selects the actual diagnostic tap or sum. Patch.render returns the engine's final levelled mix; raw/matched audition WAVs export renderProbe through the method recorded in manifest.json.

            Rebuild from the repository root: ./gradlew :synth:generateTrioAudition
            Custom destination: ./gradlew :synth:generateTrioAudition -PtrioOutput=/absolute/path
            Listening round: $round

            Every audio and clip-evidence URL includes its listening-round directory, so a newly published page requests new assets rather than cached files from a previous revision.

            manifest.json records recipes, configuration, sources, compiled-class fingerprints, actual event counts and per-asset SHA-256 hashes. diagnostics contains full float metrics and actor-event CSVs. CHECKSUMS.sha256 covers the regular folder pack, including index.html. listen.html embeds those assets after checksums are written and excludes itself from that list to avoid recursive content. Metrics precede PCM quantization.

            Keyboard: Left/Right choose the previous/next clip; Space plays or pauses when focus is outside a form control; M toggles matched/raw. One shared audio player prevents overlapping clips.
        """.trimIndent() + "\n")
        val assets = buildList {
            addAll(listOf("index.html", "manifest.json", "README.md"))
            for (clip in clips) for (path in clip.obj().getValue("assets").obj().keys) add(path)
        }.distinct().sorted()
        writeText(root, "CHECKSUMS.sha256", assets.joinToString("\n", postfix = "\n") { "${sha256(File(root, it).readBytes())}  $it" })
        // Embed only evidence and audio assets, never index.html or listen.html itself.
        // The ordinary manifest/checksums remain the source of truth for the folder pack.
        val embeddedPaths = (assets.filter { it != "index.html" } + "CHECKSUMS.sha256").sorted()
        val embeddedFiles = obj(*embeddedPaths.map { path ->
            path to str("data:${assetMime(path)};base64," + Base64.getEncoder().encodeToString(File(root, path).readBytes()))
        }.toTypedArray())
        val standaloneManifest = JsonValue.Obj(LinkedHashMap(manifestValue.entries).apply {
            this["embeddedFiles"] = embeddedFiles
        })
        val standalonePage = page.replace("__MANIFEST__", Json.write(standaloneManifest).replace("</", "<\\/"))
        check(standalonePage.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024) { "Self-contained TRIO page exceeds the 32 MiB upload limit" }
        writeText(root, "listen.html", standalonePage)
        println("Wrote ${clips.size} listening positions, ${clips.size * 2} WAVs, index.html and self-contained listen.html under ${root.absolutePath}")
    }

    private fun patch(case: Case) = TrioPatch(name = case.label, voice = case.voice,
        macros = Trio.defaults(case.voice) + baseline + case.changes, midi = MIDI, velocity = VELOCITY,
        config = config.copy(model = case.model))
    private fun tapName(case: Case) = when {
        case.husbandsOnly -> "HUSBANDS"
        case.actor != null -> case.actor.name
        else -> "MIX"
    }
    private fun sumActors(probe: TrioProbe, actors: List<TrioActor>): FloatArray {
        val samples = FloatArray(probe.samples.size)
        for (actor in actors) {
            val tap = probe.actors.getValue(actor)
            for (index in samples.indices) samples[index] += tap[index]
        }
        return samples
    }
    private fun configJson(config: TrioConfig) = obj("model" to num(config.model), "seed" to num(config.seed),
        "tempoBpm" to num(config.tempoBpm), "beats" to num(config.beats), "press" to num(config.press),
        "tremoloHz" to num(config.tremoloHz), "strumMs" to num(config.strumMs))
    private fun metrics(snip: Snip): JsonValue.Obj {
        val samples = snip.samples
        check(samples.isNotEmpty())
        var peak = 0.0
        var sum = 0.0
        var squares = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            check(value.isFinite()) { "Nonfinite TRIO measurement" }
            val magnitude = if (value < 0.0) -value else value
            if (magnitude > peak) peak = magnitude
            sum += value
            squares += value * value
        }
        return obj("finite" to bool(true), "peak" to num(peak), "dc" to num(sum / samples.size),
            "rms" to num(sqrt(squares / samples.size)), "loudness" to num(Loudness.of(snip)),
            "firstSample" to num(samples.first()), "lastSample" to num(samples.last()))
    }
    private fun scale(snip: Snip, gain: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, snip.channels, snip.sampleRate)
    private fun floatHash(samples: FloatArray): String {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) buffer.putFloat(sample)
        return sha256(buffer.array())
    }
    private fun classHash(type: Class<*>): String = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")
        ?.use { sha256(it.readBytes()) } ?: error("Cannot fingerprint ${type.name}")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun writeText(root: File, path: String, text: String) = File(root, path).also { it.parentFile?.mkdirs() }.writeText(text)
    private fun csv(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
    private fun display(value: Float) = if (value == 0f || value == 1f) value.toInt().toString() else value.toString()
    private fun assetMime(path: String) = when {
        path.endsWith(".wav") -> "audio/wav"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".csv") -> "text/csv;charset=utf-8"
        path.endsWith(".md") -> "text/markdown;charset=utf-8"
        else -> "text/plain;charset=utf-8"
    }
    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*pairs))
    private fun arr(values: List<JsonValue>) = JsonValue.Arr(values)
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Number) = JsonValue.Num(value.toDouble())
    private fun bool(value: Boolean) = JsonValue.Bool(value)
}
