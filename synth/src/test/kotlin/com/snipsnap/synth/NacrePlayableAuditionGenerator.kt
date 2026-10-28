package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Ordinary model-3 patches over registers, gestures and controls, plus explicit note schedules. */
object NacrePlayableAuditionGenerator {
    private const val RATE = 44_100
    private const val MODEL = 3
    private const val SEED = 7L
    private const val TEMPLATE = "design/nacre-playable-audition/index.html"
    private const val PHRASE_FRAMES = RATE * 6
    private const val PHRASE_FADE_FRAMES = RATE * 20 / 1000
    private data class Request(
        val id: String, val title: String, val section: String, val description: String,
        val macros: Map<String, Float>, val midi: Int = 48, val velocity: Float = 1f,
    )
    private data class Rendered(
        val request: Request, val patch: NacrePatch, val probe: Nacre.Probe, val milliseconds: Double,
    )
    private data class Phrase(
        val request: Request, val notes: List<Rendered>, val onsets: List<Int>, val sum: FloatArray,
    )
    private data class Artifact(val path: String, val sha256: String)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one NACRE playable audition output directory." }
        val repo = repositoryRoot()
        val root = File(args.firstOrNull() ?: "${repo.path}/testkit/nacre-playable-audition").absoluteFile
        require(root.canonicalFile != repo.canonicalFile) { "Use a dedicated output directory." }
        root.mkdirs()
        val began = System.nanoTime()
        val cpuBefore = cpuNanoseconds()
        val base = NacreModels.defaults(NacreVoice.PEARL, MODEL) + ("HOLD" to 0f)
        val requests = requests(base)
        check(requests.size == 27 && requests.map { it.id }.distinct().size == 27)
        val records = mutableListOf<Map<String, Any?>>()
        val singles = requests.mapIndexed { index, request ->
            val rendered = render(request)
            // Save/replay while this note's production core is warm. Retain only the venue
            // tap after its other stem hashes have been written, keeping the testkit heap modest.
            records += writeSingle(root, rendered)
            println("NACRE playable ${index + 1}/30: ${request.id}")
            slim(rendered)
        }
        val acousticTiming = verifyControlTiming(singles)
        val references = verifyReferences(repo, singles)
        val phrases = phrases(base).map { request ->
            val notes = listOf(48, 51, 55, 58, 60, 58, 55, 48).mapIndexed { index, midi ->
                slim(render(request.copy(id = "${request.id}_note_$index", title = "${request.title} / note ${index + 1}", midi = midi)))
            }
            val onsets = (0 until 8).map { it * RATE * 55 / 100 }
            Phrase(request, notes, onsets, sumNotes(notes.map { it.probe.snip }, onsets, PHRASE_FRAMES))
        }
        // Preserve the same note-level calibration and all overlap. Only a single safety gain
        // shared by the three phrases can reduce their sum to fit PCM headroom.
        val phraseGain = minOf(1f, .95f / phrases.maxOf { peak(it.sum) }.coerceAtLeast(1e-9f))
        records += phrases.mapIndexed { index, phrase ->
            println("NACRE playable ${28 + index}/30: ${phrase.request.id}")
            writePhrase(root, phrase, phraseGain)
        }
        check(records.size == 30)
        val comparisons = controlComparisons(root, singles, records)
        val source = copySources(repo, root)
        File(repo, TEMPLATE).copyTo(File(root, "index.html"), overwrite = true)
        val splitDownloads = records.chunked(15).mapIndexed { index, members -> linkedMapOf<String, Any?>(
            "path" to "nacre-playable-part-${index + 1}.zip", "title" to "Playable audition / ${index + 1} of 2",
            "description" to "${members.size} canonical WAVs plus page, recipes and evidence. Extract both parts into the same directory.",
            "part" to index + 1, "partCount" to 2, "clipIds" to members.map { it.getValue("id") },
            "comparisonMacros" to comparisons.filterIndexed { comparisonIndex, _ -> comparisonIndex % 2 == index }.map { it.getValue("macro") },
        ) }
        val completeDownload = mapOf("path" to "nacre-playable.zip", "title" to "Complete playable audition",
            "description" to "All 30 canonical clips, three control previews, page, exact recipes and evidence in one ZIP.")
        val manifest = linkedMapOf<String, Any?>(
            "format" to "snipsnap-nacre-playable-audition", "version" to 1, "revision" to "playable-1",
            "title" to "Nacre / Playable range", "engine" to "NACRE", "model" to MODEL, "voice" to "PEARL",
            "description" to "Ordinary saved model-3 patches across three registers, three velocities and three acoustic controls, followed by the same eight-note phrase in three venue positions.",
            "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24, "seed" to SEED.toString(),
            "macros" to base, "hold" to false, "clipCount" to records.size, "clips" to records,
            "comparisons" to comparisons,
            "sections" to sections(), "source" to source, "generatedAt" to Instant.now().toString(),
            "download" to completeDownload.getValue("path"), "downloads" to listOf(completeDownload),
            "gainPolicy" to linkedMapOf(
                "singleNotes" to "Exact ordinary patch.render output at export gain 1. Model 3 uses its fixed production reference calibration and peak guard; no audition loudness matching is applied.",
                "phrases" to "Sum ordinary note outputs with their natural levels and complete overlap, cap at six seconds, apply one safety gain shared by all three phrase versions, and fade only the final 20 ms.",
                "singleNoteExportGain" to 1, "commonPhraseSafetyGain" to phraseGain,
                "referenceOutputGain" to 3.2143452167510986, "productionPeakCeiling" to .95,
            ),
            "verification" to mapOf("allSinglePatchRecipesReplayExactPcm" to true,
                "allPhraseWrappersReplayExactPcm" to true, "allControlPreviewsReplayExactPcm" to true, "controlEventSchedules" to acousticTiming,
                "references" to references),
            "metricsNotes" to mapOf(
                "basis" to "Main metrics describe canonical exported audio before PCM quantization. Prelevel probe diagnostics and return ratios are explicitly named. Note exports receive no additional gain.",
                "phrase" to "A phrase is an explicit eight-patch schedule, not one synthesizer patch or HOLD loop. Its recipe records sample onsets, output length, shared safety gain and final fade. Individual source notes are replayed and hashed.",
                "controlPreviews" to "Each preview concatenates the first two seconds of the five canonical control steps, retains gain 1, fades only their final 20 ms and separates them by .25-second silence. Full six-second source WAVs remain canonical.",
                "pitch" to "Pitch is a local 250 ms estimate after the last pluck for single notes. A polyphonic phrase has no single global pitch estimate; its source note estimates are recorded separately.",
                "classifier" to "The generic classifier uses a duration rule for non-silent heads; six-second finite notes and phrases can classify LOOP. This does not imply a seamless loop.",
                "energy" to "E90/E99 are cumulative squared-sample energy times. Fractions, correlations and spectra describe signals, not audibility or musical quality.",
            ),
            "performance" to mapOf("generationMillisecondsBeforeArchive" to (System.nanoTime() - began) / 1e6,
                "generationCpuMillisecondsBeforeArchive" to cpuNanoseconds()?.let { after -> cpuBefore?.let { (after - it) / 1e6 } },
                "cacheNote" to "Render timings depend on the production core cache and generation order. Recipe replay checks run after the initial probe.",
                "javaVersion" to System.getProperty("java.version"), "architecture" to System.getProperty("os.arch")) + initialRenderPerformance(singles),
            "acceptance" to "Playable-range listening pending. Previous endpoint acceptance does not establish every control, register, velocity or phrase.",
        )
        writeJson(File(root, "playable/manifest.json"), manifest)
        writeMetrics(root, records)
        File(root, "playable/README.txt").writeText(readme(phraseGain, split = false))
        val allAudioPaths = (records + comparisons).map { it.getValue("audio").toString() }.toSet()
        val complete = writeZip(root, completeDownload.getValue("path").toString(), allAudioPaths, enforceLimit = false)
        val split = complete.length() >= 25L * 1024 * 1024
        if (split) {
            check(complete.delete())
            manifest["download"] = splitDownloads.first().getValue("path")
            manifest["downloads"] = splitDownloads
            writeJson(File(root, "playable/manifest.json"), manifest)
            File(root, "playable/README.txt").writeText(readme(phraseGain, split = true))
            val byId = records.associateBy { it.getValue("id").toString() }
            for (download in splitDownloads) {
                val ids = download.getValue("clipIds") as List<*>
                val comparisonMacros = download.getValue("comparisonMacros") as List<*>
                val comparisonPaths = comparisons.filter { it["macro"] in comparisonMacros }.map { it.getValue("audio").toString() }
                writeZip(root, download.getValue("path").toString(), ids.map { byId.getValue(it.toString()).getValue("audio").toString() }.toSet() + comparisonPaths)
            }
        } else {
            for (download in splitDownloads) File(root, download.getValue("path").toString()).takeIf { it.isFile }?.let { check(it.delete()) }
        }
        println("NACRE playable wrote 30 clips, three control previews and ${if (split) "two ZIP parts" else "one complete ZIP"} to $root")
    }

    private fun requests(base: Map<String, Float>): List<Request> = buildList {
        for ((id, title) in listOf("pearl_close" to "Pearl Close", "coral_reply" to "Coral Reply", "kelp_passage" to "Kelp Passage")) {
            val preset = NacrePresets.all().single { it.name == title && it.model == MODEL }
            check(preset.midi == 48 && preset.velocity == 1f && preset.seed == SEED)
            add(Request("preset_$id", preset.name, "presets",
                "Ordinary production starting patch: VENUE ${preset.macros.getValue("VENUE")}, BLOOM ${preset.macros.getValue("BLOOM")}, MIDI 48, velocity 1, seed 7.",
                preset.macros, preset.midi, preset.velocity))
        }
        for (midi in listOf(36, 48, 60)) for (velocity in listOf(.3f, .65f, 1f)) {
            add(Request("range_${midi.toString().padStart(3, '0')}_${tag(velocity)}", "${note(midi)} / velocity $velocity", "range",
                "The same model-3 PEARL defaults at MIDI $midi and velocity $velocity. Fixed production calibration preserves natural register and velocity level differences.", base, midi, velocity))
        }
        for (macro in listOf("VENUE", "CURRENT", "BLOOM")) for (value in listOf(0f, .25f, .5f, .75f, 1f)) {
            // Seaweed exposes moving apertures; coral's CURRENT action is a restrained static
            // early/late balance. Hold this venue context fixed across the whole CURRENT sweep.
            val context = if (macro == "CURRENT") base + ("VENUE" to 1f) else base
            val contextNote = if (macro == "CURRENT") "Seaweed VENUE 1 stays fixed; other controls use their reference settings."
                else "Other macros remain at the reference settings."
            add(Request("sweep_${macro.lowercase()}_${tag(value)}", "$macro / $value", "controls",
                "Change only $macro to $value at MIDI 48, velocity 1, seed 7. $contextNote Production calibration stays fixed.", context + (macro to value)))
        }
    }
    private fun phrases(base: Map<String, Float>) = listOf(0f, .5f, 1f).map { venue ->
        Request("phrase_venue_${tag(venue)}", "Eight notes / VENUE $venue", "phrases",
            "Eight model-3 notes at velocity .65, 550 ms apart, with their natural overlapping returns. VENUE $venue; all other macros and the complete schedule are identical across the three phrases.",
            base + ("VENUE" to venue), velocity = .65f)
    }

    private fun render(request: Request): Rendered {
        val patch = NacrePatch(request.title, NacreVoice.PEARL, request.macros, request.midi, request.velocity, SEED, MODEL)
        val began = System.nanoTime()
        val probe = NacreModels.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed, model = MODEL)
        val milliseconds = (System.nanoTime() - began) / 1e6
        require(probe.snip.channels == 1 && probe.snip.sampleRate == RATE && probe.snip.samples.isNotEmpty())
        require(probe.snip.samples.all { it.isFinite() } && probe.raw.all { it.isFinite() } && probe.diagnostics.values.all { it.isFinite() })
        require(probe.snip.peak() <= .950001f && probe.snip.peak() > 1e-6f)
        val restored = NacrePatch.fromJsonText(patch.toJsonText())
        check(restored == patch)
        check(sameSamples(restored.render(), probe.snip)) { "${request.id}: ordinary patch recipe replay differs." }
        return Rendered(request, patch, probe, milliseconds)
    }

    private fun slim(rendered: Rendered): Rendered = rendered.copy(probe = rendered.probe.copy(
        stems = mapOf("venue" to rendered.probe.stems.getValue("venue")),
    ))

    private fun initialRenderPerformance(singles: List<Rendered>): Map<String, Any?> {
        fun summarize(samples: List<Rendered>): Map<String, Any?> {
            val times = samples.map { it.milliseconds }.sorted()
            val median = when {
                times.isEmpty() -> null
                times.size % 2 == 1 -> times[times.size / 2]
                else -> (times[times.size / 2 - 1] + times[times.size / 2]) / 2
            }
            return mapOf("count" to times.size, "medianMilliseconds" to median,
                "minMilliseconds" to times.minOrNull(), "maxMilliseconds" to times.maxOrNull())
        }
        check(singles.all { it.probe.diagnostics.getValue("coreCacheHit") in setOf(0.0, 1.0) })
        val cold = singles.filter { it.probe.diagnostics.getValue("coreCacheHit") == 0.0 }
        val warm = singles.filter { it.probe.diagnostics.getValue("coreCacheHit") == 1.0 }
        val cache = Nacre.coreCacheStats()
        return mapOf(
            "initialSingleNoteProbeTimings" to mapOf(
                "basis" to "Elapsed wall time around the initial NacreModels.inspect(model=3) call for each of the 27 single-note clips. Classified by that call's coreCacheHit diagnostic; excludes its following recipe replay, audio export, phrase renders and control previews. No additional benchmark renders were performed.",
                "coldSourceBuildPlusReturn" to summarize(cold), "warmCoreReturnRender" to summarize(warm),
                "limitation" to "These are generation-order observations on this JVM and host, not Android-device timings, a realtime guarantee or a general cold/warm latency claim.",
            ),
            "retainedCoreCache" to mapOf("entries" to cache.entries, "bytes" to cache.bytes,
                "capacity" to cache.capacity, "hits" to cache.hits, "builds" to cache.builds,
                "basis" to "Snapshot after all source rendering and recipe replay. Hit/build counters include all model-3 calls in this generator JVM, not just the 27 timed initial probes."),
        )
    }

    private fun verifyControlTiming(singles: List<Rendered>): List<Map<String, Any?>> =
        singles.filter { it.request.section == "controls" }.groupBy { it.request.id.substringBeforeLast('_') }.map { (family, members) ->
            val events = members.first().probe.events
            check(members.all { it.probe.events == events }) { "$family: acoustic control unexpectedly changed pluck events." }
            mapOf("family" to family, "clipIds" to members.map { it.request.id }, "exactEventsUnchanged" to true, "eventCount" to events.size)
        }

    private fun verifyReferences(repo: File, singles: List<Rendered>): Map<String, Any?> {
        val model1 = Json.parse(File(repo, "synth/src/test/resources/nacre-model1-reference.json").readText()).obj()
        val legacyPatch = NacrePatch.fromJsonValue(model1.getValue("recipe")) as NacrePatch
        check(samplesHash(legacyPatch.render().samples) == model1.getValue("nativeSamplesSha256").str()) { "Legacy model-1 reference changed." }
        val presence = Json.parse(File(repo, "synth/src/test/resources/nacre-presence-reference.json").readText()).obj()
        val checks = presence.getValue("endpoints").arr().map { value ->
            val reference = value.obj()
            val id = if (reference.getValue("id").str() == "coral") "sweep_venue_000" else "sweep_venue_100"
            val rendered = singles.first { it.request.id == id }
            check(samplesHash(rendered.probe.raw) == reference.getValue("prelevelSamplesSha256").str()) { "$id: accepted prelevel endpoint changed." }
            check(samplesHash(rendered.probe.stems.getValue("venue")) == reference.getValue("returnSamplesSha256").str()) { "$id: accepted return changed." }
            check(bytesHash(wavBytes(rendered.probe.snip)) == reference.getValue("wavSha256").str()) { "$id: accepted endpoint PCM changed." }
            mapOf("clipId" to id, "acceptedEndpoint" to reference.getValue("id").str(), "exactPrelevelReturnAndPcmMatch" to true)
        }
        return mapOf("legacyModel1NativeMatchesGolden" to true, "acceptedPresenceEndpoints" to checks,
            "acceptedPresenceSourceCommit" to presence.getValue("sourceCommit").str())
    }

    private fun writeSingle(root: File, rendered: Rendered): Map<String, Any?> {
        val request = rendered.request
        val patch = rendered.patch
        val probe = rendered.probe
        val audio = writeAudio(root, request.id, probe.snip)
        val recipePath = "playable/recipes/${request.id}.json"
        val recipe = File(root, recipePath).apply { parentFile.mkdirs(); writeText(patch.toJsonText() + "\n") }
        val replay = NacrePatch.fromJsonText(recipe.readText()).render()
        check(bytesHash(wavBytes(replay)) == audio.sha256) { "${request.id}: saved recipe does not recreate the exact WAV." }
        val eventsPath = "playable/traces/events/${request.id}.csv"
        val events = File(root, eventsPath).apply { parentFile.mkdirs(); writeText(eventsCsv(probe.events, patch)) }
        val diagnosticPath = "playable/diagnostics/${request.id}.json"
        val metric = metrics(probe.snip, patch.midi, probe.events)
        val diagnostics = writeJson(File(root, diagnosticPath), linkedMapOf(
            "id" to request.id, "model" to MODEL, "ordinaryPatchRecipeReplayExactPcm" to true,
            "exportGain" to 1, "renderMilliseconds" to rendered.milliseconds, "engine" to probe.diagnostics,
            "nativeSamplesSha256" to samplesHash(probe.snip.samples), "prelevelSamplesSha256" to samplesHash(probe.raw),
            "prelevelStemHashes" to probe.stems.mapValues { samplesHash(it.value) },
            "metrics" to metric, "prelevelReturnToForeground" to returnRatios(probe),
        ))
        return commonRecord(request, audio, recipePath, eventsPath, diagnosticPath, metric) + linkedMapOf(
            "recipeKind" to "patch", "exportGain" to 1, "renderMilliseconds" to rendered.milliseconds,
            "prelevelReturnToForeground" to returnRatios(probe), "waveform" to waveform(probe.snip.samples),
            "hashes" to mapOf("audio" to audio.sha256, "recipe" to fileHash(recipe), "events" to fileHash(events),
                "diagnostics" to fileHash(diagnostics), "nativeSamples" to samplesHash(probe.snip.samples)),
        )
    }

    private fun writePhrase(root: File, phrase: Phrase, gain: Float): Map<String, Any?> {
        val request = phrase.request
        val recipePath = "playable/recipes/${request.id}.json"
        val schedule = phrase.notes.mapIndexed { index, note -> linkedMapOf<String, Any?>(
            "index" to index, "onsetFrame" to phrase.onsets[index], "patch" to Json.parse(note.patch.toJsonText()),
            "nativeSamplesSha256" to samplesHash(note.probe.snip.samples),
        ) }
        val recipeValue = linkedMapOf<String, Any?>(
            "format" to "snipsnap-nacre-playable-phrase", "version" to 1, "model" to MODEL,
            "sampleRate" to RATE, "channels" to 1, "outputFrames" to PHRASE_FRAMES, "notes" to schedule,
            "commonPhraseSafetyGain" to gain, "finalFadeFrames" to PHRASE_FADE_FRAMES,
            "mixRule" to "Render each ordinary patch; add every overlapping sample in note order using Double; convert the sum to Float; multiply by the recorded common safety gain; multiply only the finalFadeFrames samples by a linear 1-to-0 fade.",
        )
        val recipe = writeJson(File(root, recipePath), recipeValue)
        val expected = finishPhrase(phrase.sum, gain, PHRASE_FADE_FRAMES)
        check(expected.samples.all { it.isFinite() } && expected.peak() <= .950001f)
        val replay = replayPhrase(Json.parse(recipe.readText()))
        check(sameSamples(expected, replay)) { "${request.id}: saved phrase recipe replay differs." }
        val audio = writeAudio(root, request.id, expected)
        check(bytesHash(wavBytes(replay)) == audio.sha256)
        val eventsPath = "playable/traces/events/${request.id}.csv"
        val events = File(root, eventsPath).apply { parentFile.mkdirs(); writeText(phraseEventsCsv(phrase)) }
        val diagnosticPath = "playable/diagnostics/${request.id}.json"
        val noteMetrics = phrase.notes.mapIndexed { index, note -> linkedMapOf<String, Any?>(
            "index" to index, "midi" to note.patch.midi, "velocity" to note.patch.velocity,
            "onsetFrame" to phrase.onsets[index], "onsetSeconds" to phrase.onsets[index].toDouble() / RATE,
            "nativeSamplesSha256" to samplesHash(note.probe.snip.samples), "renderMilliseconds" to note.milliseconds,
            "metrics" to metrics(note.probe.snip, note.patch.midi, note.probe.events),
            "sourceEnergyAfterNextOnsetFraction" to energyAfter(note.probe.snip.samples, RATE * 55 / 100),
            "returnEnergyAfterNextOnsetFraction" to energyAfter(note.probe.stems.getValue("venue"), RATE * 55 / 100),
        ) }
        val metric = metrics(expected, null, emptyList())
        val diagnostic = writeJson(File(root, diagnosticPath), linkedMapOf(
            "id" to request.id, "recipeKind" to "phrase", "exactRecipeReplayPcm" to true,
            "noteCount" to 8, "outputFrames" to PHRASE_FRAMES, "commonPhraseSafetyGain" to gain,
            "finalFadeFrames" to PHRASE_FADE_FRAMES, "unattenuatedSumPeak" to peak(phrase.sum),
            "notes" to noteMetrics, "metrics" to metric,
            "overlapNote" to "Each source note is rendered in full and all samples falling within the six-second phrase are summed. Energy-after-next-onset fractions show actual source and venue tail overlap; they do not establish its audibility.",
        ))
        return commonRecord(request, audio, recipePath, eventsPath, diagnosticPath, metric) + linkedMapOf(
            "recipeKind" to "phrase", "exportGain" to gain, "noteCount" to 8, "noteSchedule" to noteMetrics,
            "finalFadeFrames" to PHRASE_FADE_FRAMES, "waveform" to waveform(expected.samples),
            "hashes" to mapOf("audio" to audio.sha256, "recipe" to fileHash(recipe), "events" to fileHash(events),
                "diagnostics" to fileHash(diagnostic), "nativeSamples" to samplesHash(expected.samples)),
        )
    }

    private fun controlComparisons(root: File, singles: List<Rendered>, records: List<Map<String, Any?>>): List<Map<String, Any?>> =
        listOf("VENUE", "CURRENT", "BLOOM").map { macro ->
            val steps = singles.filter { it.request.id.startsWith("sweep_${macro.lowercase()}_") }
                .sortedBy { it.request.macros.getValue(macro) }
            check(steps.size == 5)
            val segmentFrames = RATE * 2
            val gapFrames = RATE / 4
            val fadeFrames = RATE * 20 / 1000
            val segments = steps.mapIndexed { index, step ->
                val from = index * (segmentFrames + gapFrames).toDouble() / RATE
                mapOf<String, Any?>("id" to step.request.id, "from" to from, "to" to from + 2,
                    "fromSeconds" to from, "toSeconds" to from + 2, "sourceFromSeconds" to 0, "sourceToSeconds" to 2)
            }
            val sourceRecords = records.associateBy { it.getValue("id").toString() }
            val recipePath = "playable/recipes/preview-${macro.lowercase()}.json"
            val recipe = writeJson(File(root, recipePath), linkedMapOf(
                "format" to "snipsnap-nacre-control-preview", "version" to 1, "model" to MODEL,
                "sampleRate" to RATE, "gain" to 1, "segmentFrames" to segmentFrames,
                "gapFrames" to gapFrames, "segmentEndFadeFrames" to fadeFrames,
                "segments" to steps.map { step -> mapOf("id" to step.request.id,
                    "patch" to Json.parse(step.patch.toJsonText()), "nativeSamplesSha256" to samplesHash(step.probe.snip.samples),
                    "sourceAudioSha256" to (sourceRecords.getValue(step.request.id).getValue("hashes") as Map<*, *>)["audio"]) },
                "note" to "Preview montage only. Render each ordinary patch, take its first segmentFrames, fade only its last segmentEndFadeFrames linearly from 1 to 0, concatenate with exact gapFrames silence. No per-step leveling or extra gain.",
            ))
            val expected = previewSamples(steps.map { it.probe.snip }, segmentFrames, gapFrames, fadeFrames)
            val wrapper = Json.parse(recipe.readText()).obj()
            val source = wrapper.getValue("segments").arr().map { entry ->
                val spec = entry.obj()
                val patch = NacrePatch.fromJsonValue(spec.getValue("patch")) as NacrePatch
                val audio = patch.render()
                check(samplesHash(audio.samples) == spec.getValue("nativeSamplesSha256").str())
                check(bytesHash(wavBytes(audio)) == spec.getValue("sourceAudioSha256").str())
                audio
            }
            val replay = previewSamples(source, wrapper.getValue("segmentFrames").int(), wrapper.getValue("gapFrames").int(), wrapper.getValue("segmentEndFadeFrames").int())
            check(sameSamples(expected, replay)) { "$macro: preview recipe replay differs." }
            val audio = writeAudio(root, "preview-${macro.lowercase()}", expected)
            check(bytesHash(wavBytes(replay)) == audio.sha256)
            mapOf("macro" to macro, "title" to "$macro / five steps", "audio" to audio.path, "hash" to audio.sha256,
                "recipe" to recipePath, "recipeHash" to fileHash(recipe), "segments" to segments,
                "durationSeconds" to expected.durationSeconds, "gain" to 1, "gapSeconds" to .25,
                "segmentSeconds" to 2, "segmentEndFadeMilliseconds" to 20, "canonicalSourceClipsUnchanged" to true)
        }

    private fun previewSamples(notes: List<Snip>, segment: Int, gap: Int, fade: Int): Snip {
        val output = FloatArray(notes.size * segment + (notes.size - 1) * gap)
        for ((index, note) in notes.withIndex()) for (i in 0 until segment) {
            val taper = if (i < segment - fade) 1f else (segment - 1 - i).toFloat() / (fade - 1)
            output[index * (segment + gap) + i] = note.samples[i] * taper
        }
        return snip(output)
    }

    private fun commonRecord(request: Request, audio: Artifact, recipe: String, events: String, diagnostics: String, metrics: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("id" to request.id, "title" to request.title, "description" to request.description, "section" to request.section,
            "model" to MODEL, "voice" to "PEARL", "macros" to request.macros, "midi" to if (request.section == "phrases") null else request.midi,
            "velocity" to request.velocity, "seed" to SEED.toString(), "hold" to false,
            "noteName" to if (request.section == "phrases") null else note(request.midi),
            "controlMacro" to if (request.section == "controls") request.id.split('_')[1].uppercase() else null,
            "controlValue" to if (request.section == "controls") request.macros.getValue(request.id.split('_')[1].uppercase()) else null,
            "audio" to audio.path, "recipe" to recipe, "events" to events, "diagnostics" to diagnostics,
            "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24, "durationSeconds" to 6,
            "metrics" to metrics, "waveformKind" to "absolute-peak-per-bin")

    private fun replayPhrase(value: JsonValue): Snip {
        val obj = value.obj()
        check(obj.getValue("format").str() == "snipsnap-nacre-playable-phrase" && obj.getValue("model").int() == MODEL)
        val notes = obj.getValue("notes").arr().map { it.obj() }
        val audio = notes.map { note ->
            val patch = NacrePatch.fromJsonValue(note.getValue("patch")) as NacrePatch
            val rendered = patch.render()
            check(samplesHash(rendered.samples) == note.getValue("nativeSamplesSha256").str())
            rendered
        }
        val sum = sumNotes(audio, notes.map { it.getValue("onsetFrame").int() }, obj.getValue("outputFrames").int())
        return finishPhrase(sum, obj.getValue("commonPhraseSafetyGain").num().toFloat(), obj.getValue("finalFadeFrames").int())
    }
    private fun sumNotes(notes: List<Snip>, onsets: List<Int>, frames: Int): FloatArray {
        val sum = DoubleArray(frames)
        for ((index, note) in notes.withIndex()) {
            check(note.sampleRate == RATE && note.channels == 1)
            val onset = onsets[index]
            for (i in 0 until minOf(note.samples.size, frames - onset)) sum[onset + i] += note.samples[i]
        }
        return FloatArray(frames) { sum[it].toFloat() }
    }
    private fun finishPhrase(sum: FloatArray, gain: Float, fadeFrames: Int): Snip = snip(FloatArray(sum.size) { i ->
        val fade = if (i < sum.size - fadeFrames) 1f else (sum.lastIndex - i).toFloat() / (fadeFrames - 1)
        sum[i] * gain * fade
    })

    private fun metrics(audio: Snip, midi: Int?, events: List<Nacre.Event>): Map<String, Any?> {
        val x = audio.samples
        val energy = energy(x)
        val start = ((events.maxOfOrNull { it.timeSeconds } ?: 0.0) + .04).coerceAtMost((audio.durationSeconds - .30).coerceAtLeast(0.0))
        val pitch = if (midi == null) null else Pitch.detect(audio, start.toFloat(), .25f)
        val root = midi?.let { 440.0 * 2.0.pow((it - 69) / 12.0) }
        val classification = Classifier.classify(audio)
        return linkedMapOf("peak" to peak(x), "rms" to sqrt(energy / x.size), "dc" to x.sumOf { it.toDouble() } / x.size,
            "loudness" to Loudness.of(audio), "totalEnergy" to energy, "energy90Seconds" to energyTime(x, .90),
            "energy99Seconds" to energyTime(x, .99), "above1kFraction" to above1k(x),
            "classifier" to classification.drumClass.name, "classifierConfidence" to classification.confidence,
            "pitch" to if (midi == null) null else mapOf("hz" to pitch?.hz, "confidence" to pitch?.confidence,
                "rootHz" to root, "errorCentsFromRoot" to pitch?.let { 1200 * ln(it.hz / root!!) / ln(2.0) }, "fromSeconds" to start, "windowSeconds" to .25),
            "finite" to x.all { it.isFinite() })
    }
    private fun returnRatios(probe: Nacre.Probe): Map<String, Any?> {
        val space = probe.stems.getValue("venue")
        val foreground = FloatArray(probe.raw.size) { probe.raw[it] - space[it] }
        return mapOf("returnRms" to rms(space), "foregroundRms" to rms(foreground),
            "returnToForegroundDb" to if (rms(space) > 0) 20 * log10(rms(space) / rms(foreground)) else null,
            "returnEnergyAfterPointEightFraction" to energyAfter(space, RATE * 8 / 10))
    }
    private fun energyAfter(x: FloatArray, from: Int): Double? {
        val all = energy(x)
        return if (all > 0) (from.coerceAtMost(x.size) until x.size).sumOf { x[it].toDouble() * x[it] } / all else null
    }
    private fun energyTime(x: FloatArray, fraction: Double): Double? {
        val target = energy(x) * fraction
        if (target <= 0) return null
        var sum = 0.0
        for (i in x.indices) { sum += x[i].toDouble() * x[i]; if (sum >= target) return i.toDouble() / RATE }
        return x.lastIndex.toDouble() / RATE
    }
    private fun above1k(x: FloatArray): Double {
        var size = 1
        while (size < x.size) size = size shl 1
        val re = x.copyOf(size); val im = FloatArray(size); Fft.forward(re, im)
        var all = 0.0; var high = 0.0
        for (bin in 0..size / 2) {
            val p = (re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]) * if (bin == 0 || bin == size / 2) 1 else 2
            all += p; if (bin.toDouble() * RATE / size > 1000) high += p
        }
        return if (all > 0) high / all else 0.0
    }

    private fun copySources(repo: File, root: File): Map<String, Any?> {
        val paths = File(repo, "synth/src/main/kotlin/com/snipsnap/synth").listFiles()!!.filter { it.name.startsWith("Nacre") && it.extension == "kt" }
            .map { it.relativeTo(repo).invariantSeparatorsPath }.toMutableList()
        paths += listOf("synth/src/test/kotlin/com/snipsnap/synth/NacrePlayableAuditionGenerator.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt", "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt", "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt", "audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Features.kt", "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt", "synth/src/test/resources/nacre-model1-reference.json",
            "synth/src/test/resources/nacre-presence-reference.json", TEMPLATE, "synth/build.gradle.kts")
        for (path in listOf("synth/NACRE_PLAYABLE_AUDITION.md", "synth/NACRE_MODEL3.md", "docs/NACRE.md",
            "docs/superpowers/specs/2026-10-18-nacre-engine-design.md",
            "synth/src/test/kotlin/com/snipsnap/synth/NacreTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacreModel3Test.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacreModelCompatibilityTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacrePresetTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacrePatchTest.kt",
            "app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt",
            "app/src/androidTest/kotlin/com/snipsnap/app/ui/NacreSynthScreenTest.kt",
            "cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt",
            "cli/src/test/kotlin/com/snipsnap/cli/NacreSynthCommandTest.kt")) if (File(repo, path).isFile) paths += path
        val files = paths.distinct().sorted().map { path ->
            val original = File(repo, path); check(original.isFile) { "Missing source snapshot input $path" }
            val target = "playable/source/$path"
            File(root, target).apply { parentFile.mkdirs(); original.copyTo(this, overwrite = true) }
            mapOf("repositoryPath" to path, "path" to target, "sha256" to fileHash(original))
        }
        val remoteContains = git(repo, "for-each-ref", "--contains=HEAD", "--format=%(refname)", "refs/remotes/origin")
        return mapOf("repository" to "https://github.com/BertCalm/SnipSnap", "commit" to git(repo, "rev-parse", "HEAD"),
            "dirty" to git(repo, "status", "--porcelain").isNotEmpty(), "repositoryCommitPushed" to remoteContains.isNotEmpty(),
            "remoteObservation" to "Pushed status reflects locally known origin remote-tracking refs at generation.",
            "snapshotRoot" to "playable/source/", "files" to files)
    }
    private fun sections() = listOf(
        mapOf("id" to "presets", "title" to "Three starting points", "description" to "Provisional ordinary model-3 patches, MIDI 48, velocity 1."),
        mapOf("id" to "range", "title" to "Three registers, three touches", "description" to "MIDI 36, 48 and 60, each at velocities .30, .65 and 1. Natural note levels remain intact."),
        mapOf("id" to "controls", "title" to "Venue, current and bloom", "description" to "Each acoustic control at 0, .25, .50, .75 and 1. MIDI 48, velocity 1; pluck schedules remain identical."),
        mapOf("id" to "phrases", "title" to "Returns between notes", "description" to "The same eight-note phrase at VENUE 0, .50 and 1, with natural tail overlap and one common phrase safety gain."),
    )
    private fun writeMetrics(root: File, records: List<Map<String, Any?>>) {
        val keys = listOf("id", "section", "recipeKind", "midi", "velocity", "peak", "rms", "dc", "loudness", "energy99Seconds", "above1kFraction", "classifier", "renderMilliseconds")
        File(root, "playable/metrics.csv").writeText(buildString {
            appendLine(keys.joinToString(","))
            for (record in records) {
                val metric = record.getValue("metrics") as Map<*, *>
                val row = record + metric.entries.associate { it.key.toString() to it.value }
                appendLine(keys.joinToString(",") { csv(row[it]) })
            }
        })
    }
    private fun writeZip(root: File, name: String, audioPaths: Set<String>, enforceLimit: Boolean = true): File {
        val files = listOf(File(root, "index.html")) + File(root, "playable").walkTopDown().filter {
            it.isFile && (it.extension != "wav" || it.relativeTo(root).invariantSeparatorsPath in audioPaths)
        }.toList()
        val archive = File(root, name)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        if (enforceLimit) check(archive.length() < 25L * 1024 * 1024) { "$name exceeds the hosted 25 MiB file limit." }
        return archive
    }
    private fun writeAudio(root: File, id: String, audio: Snip): Artifact {
        val bytes = wavBytes(audio); val hash = bytesHash(bytes); val path = "playable/audio/$id-${hash.take(12)}.wav"
        File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
        return Artifact(path, hash)
    }
    private fun wavBytes(audio: Snip): ByteArray = ByteArrayOutputStream().also { WavWriter.write(it, audio, WavWriter.BitDepth.PCM_24) }.toByteArray()
    private fun eventsCsv(events: List<Nacre.Event>, patch: NacrePatch) = buildString {
        appendLine("noteIndex,noteMidi,velocity,onsetFrame,performer,stringId,timeSeconds,frequencyHz,strength,direction")
        for (e in events) appendLine(listOf(0, patch.midi, patch.velocity, 0, e.performer, e.stringId, e.timeSeconds, e.frequencyHz, e.strength, e.direction).joinToString(",", transform = ::csv))
    }
    private fun phraseEventsCsv(phrase: Phrase) = buildString {
        appendLine("noteIndex,noteMidi,velocity,onsetFrame,performer,stringId,timeSeconds,frequencyHz,strength,direction")
        for ((index, note) in phrase.notes.withIndex()) for (e in note.probe.events) appendLine(listOf(index, note.patch.midi,
            note.patch.velocity, phrase.onsets[index], e.performer, e.stringId, phrase.onsets[index].toDouble() / RATE + e.timeSeconds,
            e.frequencyHz, e.strength, e.direction).joinToString(",", transform = ::csv))
    }
    private fun readme(phraseGain: Float, split: Boolean) = """
        NACRE / Playable range / ordinary model-3 recipes
        30 mono 44.1 kHz PCM24 clips: three provisional presets, nine register/velocity notes,
        fifteen acoustic-control steps and three overlapping eight-note phrases.
        Single-note WAVs are exact ordinary saved-patch output, export gain 1. No audition matching.
        Model 3 uses its fixed reference output calibration plus a peak safety guard.
        Phrase WAVs sum ordinary source notes, preserve overlap, apply common safety gain $phraseGain,
        cap at six seconds and fade only the final 20 ms. Their wrapper records the exact schedule.

        Every saved single patch and phrase wrapper is reloaded and checked for exact PCM replay.
        Legacy model 1 and the previously accepted coral/seaweed endpoint hashes are checked separately.
        Three eleven-second previews concatenate the five acoustic-control steps at their native
        levels, using two-second excerpts, .25-second gaps and 20 ms end fades only on excerpts.
        Canonical source clips remain unchanged. Preview wrappers also verify exact PCM replay.
        Source, exact recipes, event CSVs, metrics, note-tail overlap evidence and CPU timings are bundled.
        Cold-source and warm-core timing summaries classify the 27 initial probe.inspect calls by
        their coreCacheHit diagnostics. They exclude subsequent recipe replay and export work and
        are host/JVM observations, not a device latency or realtime guarantee. No benchmark loops run.
        ${if (split) "Extract BOTH numbered ZIP parts into one directory for all 33 WAVs. Each part includes the same evidence." else "The complete ZIP contains all 33 WAVs, the page and evidence. Extract once for all offline audio."}
        Serve index.html over HTTP, or listen to playable/audio/*.wav directly.
        Prior published auditions are preserved in their own namespaces.
        Listening across the playable range remains pending; numeric differences are not audibility verdicts.
    """.trimIndent() + "\n"
    private fun waveform(x: FloatArray) = (0 until 96).map { bin ->
        var value = 0f; for (i in bin * x.size / 96 until (bin + 1) * x.size / 96) value = maxOf(value, abs(x[i])); value
    }
    private fun sameSamples(a: Snip, b: Snip) = a.sampleRate == b.sampleRate && a.channels == b.channels && a.samples.contentEquals(b.samples)
    private fun snip(x: FloatArray) = Snip(x, channels = 1, sampleRate = RATE)
    private fun energy(x: FloatArray) = x.sumOf { it.toDouble() * it }
    private fun rms(x: FloatArray) = sqrt(energy(x) / x.size)
    private fun peak(x: FloatArray) = x.maxOfOrNull { abs(it) } ?: 0f
    private fun tag(value: Float) = (value * 100).toInt().toString().padStart(3, '0')
    private fun note(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun csv(value: Any?): String = value?.toString()?.let { if (it.any { c -> c in ",\"\n\r" }) "\"${it.replace("\"", "\"\"")}\"" else it } ?: ""
    private fun writeJson(file: File, value: Any?): File = file.apply { parentFile.mkdirs(); writeText(Json.write(json(value)) + "\n") }
    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }.firstOrNull { File(it, ".git").exists() } ?: error("Run inside the SnipSnap checkout.")
    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim(); check(process.waitFor() == 0) { "git command failed" }; return output
    }
    private fun cpuNanoseconds(): Long? = ManagementFactory.getThreadMXBean().let { if (it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled) it.currentThreadCpuTime.takeIf { value -> value >= 0 } else null }
    private fun fileHash(file: File) = bytesHash(file.readBytes())
    private fun bytesHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun samplesHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256"); val block = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) { if (block.remaining() < 4) { digest.update(block.array(), 0, block.position()); block.clear() }; block.putInt(sample.toRawBits()) }
        digest.update(block.array(), 0, block.position()); return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported playable-audition field ${value.javaClass.name}")
    }
}
