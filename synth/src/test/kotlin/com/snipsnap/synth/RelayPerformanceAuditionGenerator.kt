package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Relay's curated bank and playing examples, rendered entirely through the real DSP.
 * Musical examples sum complete finite notes with one documented headroom gain; there
 * are no effects, browser oscillators, truncated releases or pitch-shifted recordings.
 */
object RelayPerformanceAuditionGenerator {
    private data class Note(val beat: Double, val offset: Int)
    private data class Case(
        val id: String,
        val group: String,
        val patch: RelayPatch,
        val title: String,
        val description: String,
        val notes: List<Note> = emptyList(),
        val sequenceTempoBpm: Double? = null,
        val padIndex: Int? = null,
        val actualPad: Snip? = null,
    )

    private data class Performance(
        val snip: Snip,
        val report: Relay.Report,
        val sequence: List<Map<String, Any?>>? = null,
        val mixGain: Double = 1.0,
        val headroom: Map<String, Any>? = null,
        val maxNoteRawPeak: Double = report.rawPeak,
    )

    private val musicalNames = mapOf(
        RelayVoice.KEY to "VELVET TURN", RelayVoice.BASS to "ROUND TRIP",
        RelayVoice.PLUCK to "COPPER THREAD", RelayVoice.ORGAN to "REED CROSSING",
        RelayVoice.CHIME to "DUSK METAL", RelayVoice.FLOW to "WARM DRIFT",
    )

    private val groups = listOf(
        group("musical", "Six short performances", "Complete notes overlap naturally in C minor pentatonic. Each example uses one curated recipe, its saved source phrase and native excitation velocity. A fixed, recorded gain provides summing headroom; there are no effects."),
        group("presetbank", "The 36-preset bank", "Every curated factory recipe at its saved note, velocity and complete phrase. Held presets contain three exact repetitions of the actual engine loop region. Compare matched level for tone and raw level for native energy."),
        group("velocity", "Touch, from soft to firm", "The same finite recipe for each voice at excitation velocities 0.30, 0.65 and 1.00. Raw playback preserves the energy change; matched playback makes changes in attack and color easier to compare."),
        group("kit", "The sixteen starter pads", "The real sixteen-pad starter, in pad order. Raw playback uses the actual starter sample level, including its standard engine normalization. Each pad retains its complete editable recipe. Held pads repeat the same exported region three times here."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.filter { it.startsWith("--") }.all { it == "--quick" }) {
            "supply one output directory and optional --quick"
        }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/relay-performance-audition")
        val quick = "--quick" in args
        check(root.isDirectory || root.mkdirs()) { "cannot create ${root.absolutePath}" }
        val sourceFiles = listOf("Relay.kt", "RelayPresets.kt", "SynthKits.kt").associateWith { name ->
            listOf(File("src/main/kotlin/com/snipsnap/synth/$name"), File("synth/src/main/kotlin/com/snipsnap/synth/$name"))
                .firstOrNull { it.isFile } ?: error("run from the synth module or repository root")
        }
        val sourceHashes = sourceFiles.mapValues { sha256(it.value) }
        val revision = git("rev-parse", "HEAD")
        val dirty = git("status", "--porcelain")?.isNotBlank() ?: true
        val cases = cases(quick)
        check(cases.map { it.id }.distinct().size == cases.size) { "duplicate performance clip IDs" }
        val recipeHash = sha256(cases.joinToString("\n") { case ->
            listOf(case.id, Json.write(case.patch.toJsonValue()), case.notes.toString(), case.sequenceTempoBpm).joinToString("|")
        }.toByteArray())
        val clips = ArrayList<Map<String, Any?>>(cases.size)
        val started = System.nanoTime()
        for ((index, case) in cases.withIndex()) {
            check(sourceFiles.all { (name, file) -> sha256(file) == sourceHashes.getValue(name) }) {
                "Relay source changed during rendering; regenerate the complete listening round"
            }
            val before = System.nanoTime()
            val performance = if (case.notes.isEmpty()) single(case) else sequence(case)
            val renderMs = (System.nanoTime() - before) / 1e6
            val report = performance.report
            val engine = performance.snip
            check(engine.channels == 1 && engine.sampleRate == Dsp.RATE)
            check(engine.samples.isNotEmpty() && engine.samples.all { it.isFinite() }) { "invalid ${case.id}" }
            val repetitions = if (report.loop) 3 else 1
            val playback = if (repetitions == 1) engine else Snip(
                FloatArray(engine.samples.size * repetitions) { engine.samples[it % engine.samples.size] }, 1, Dsp.RATE,
            )
            val exportGain = minOf(1f, .99f / playback.peak().coerceAtLeast(1e-9f))
            val raw = Snip(FloatArray(playback.samples.size) { playback.samples[it] * exportGain }, 1, Dsp.RATE)
            val matched = AuditionLevel.level(playback)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val midi = midi(case.patch)
            clips += linkedMapOf(
                "id" to case.id, "group" to case.group, "voice" to case.patch.voice.name,
                "title" to case.title, "description" to case.description,
                "patchName" to case.patch.name, "patchJson" to case.patch.toJsonValue(),
                "sources" to case.patch.phrase.sources.map { it.role.name },
                "midi" to midi, "note" to noteName(midi), "velocity" to case.patch.velocity,
                "tempoBpm" to case.patch.phrase.tempoBpm,
                "actualTempoBpm" to case.patch.phrase.phraseBeats * 60.0 / report.phraseSeconds,
                "macros" to (Relay.defaults(case.patch.voice) + case.patch.macros),
                "requestedHz" to report.requestedHz, "actualHz" to report.actualHz,
                "tuningErrorCents" to cents(report), "phraseSeconds" to report.phraseSeconds,
                "gateSeconds" to report.gateSeconds, "frames" to playback.frameCount,
                "seconds" to playback.durationSeconds, "rawPath" to rawPath, "matchedPath" to matchedPath,
                "rawExportGain" to exportGain, "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                "rawMetrics" to metrics(playback), "matchedMetrics" to metrics(matched),
                "internalRawPeak" to performance.maxNoteRawPeak,
                "internalRawPeakScope" to if (performance.sequence == null) "single engine render" else "maximum individual note before summing",
                "engineNormalization" to (case.actualPad != null),
                "renderMs" to renderMs, "renderMsPerAudioSecond" to renderMs / engine.durationSeconds,
                "loop" to loop(report, repetitions),
                "accents" to if (performance.sequence == null) accents(report) else emptyList<Any>(),
                "weights" to if (performance.sequence == null) weights(report) else emptyList<Any>(),
                "weightScope" to if (performance.sequence == null) {
                    if (report.loop) "One actual engine loop region" else "Actual engine note, including its release"
                } else "Multiple complete notes; actual per-note schedules are stored in sequence",
                "contour" to contour(playback), "sequence" to performance.sequence,
                "sequenceTempoBpm" to case.sequenceTempoBpm, "mixGain" to performance.mixGain,
                "headroom" to performance.headroom,
                "padIndex" to case.padIndex,
                "padLabel" to case.padIndex?.let { "A" + it.toString().padStart(2, '0') },
                "padRole" to case.padIndex?.let { if (report.loop) "Held ${case.patch.voice.name.lowercase(Locale.ROOT)} texture" else "${case.patch.voice.name.lowercase(Locale.ROOT)} · ${noteName(midi)}" },
            )
            println("Relay performance: ${index + 1}/${cases.size} · ${case.id}")
        }
        check(git("rev-parse", "HEAD") == revision && sourceFiles.all { (name, file) -> sha256(file) == sourceHashes.getValue(name) }) {
            "source changed during rendering; regenerate the complete listening round"
        }
        val wavBytes = clips.sumOf { File(root, it.getValue("rawPath") as String).length() + File(root, it.getValue("matchedPath") as String).length() }
        check(wavBytes <= 120L * 1024 * 1024) { "performance WAV pack exceeds 120 MiB: $wavBytes bytes" }
        val manifest = Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to "RELAY", "auditionRevision" to 4,
            "status" to "curated bank — awaiting owner playability review", "presetExpansion" to "36 curated recipes",
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24,
            "referenceNote" to "saved patch root", "quick" to quick, "clipCount" to clips.size,
            "listeningTarget" to .03, "listeningPeakCeiling" to .99, "loopThreshold" to Relay.LOOP_THRESHOLD,
            "sourceRevision" to revision, "workingTreeDirty" to dirty,
            "sourceSha256" to sourceHashes, "recipeSha256" to recipeHash,
            "compiledRelaySha256" to Relay::class.java.getResourceAsStream("/com/snipsnap/synth/Relay.class")?.use { sha256(it.readBytes()) },
            "metricNotes" to "All WAVs are actual Relay offline renders, 44.1 kHz mono PCM24, with no rack effects. Preset, velocity and musical raw files preserve conditioned engine level before normalization. Kit raw files preserve the actual starter sample level after its standard engine normalization, checked sample-for-sample against SynthKits.relay. Any additional PCM export attenuation is rawExportGain. rawMetrics precedes that export gain. Matched playback uses the loudest 200 ms RMS target 0.03 and peak ceiling 0.99. Musical examples preserve complete note tails and saved phrase/velocity; MIDI replaces TUNE absolutely. A fixed mixGain bounds the sum using the maximum simultaneous sum of individual note peaks; sequence contains every event and its actual engine schedule. Musical weights and accents are intentionally empty at clip level. Musical internalRawPeak is the largest individual note's unconditioned 4× peak, not the mixed output. HOLD playback contains three exact copies of one real engine loop; seamError compares it with the preceding settled cycle and must be < 0.001. contour brightness is first-difference RMS divided by RMS, not spectral centroid.",
            "groups" to groups, "clips" to clips,
            "summary" to mapOf(
                "wavCount" to clips.size * 2, "wavBytes" to wavBytes,
                "presetCount" to clips.count { it["group"] == "presetbank" },
                "musicalExamples" to clips.count { it["group"] == "musical" },
                "velocityExamples" to clips.count { it["group"] == "velocity" },
                "kitPads" to clips.count { it["group"] == "kit" },
                "maxInternalRawPeak" to clips.maxOf { (it.getValue("internalRawPeak") as Number).toDouble() },
                "heldCases" to clips.count { it["loop"] != null },
                "loopSeamsPassed" to clips.filter { it["loop"] != null }.all { (it["loop"] as Map<*, *>)["seamPass"] == true },
                "generationSeconds" to (System.nanoTime() - started) / 1e9,
            ),
        ))) + "\n"
        val page = RelayPerformanceAuditionGenerator::class.java.getResourceAsStream("/audition/relay-performance-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("Relay performance audition page resource is missing")
        check(page.contains("__MANIFEST__")) { "performance page has no manifest placeholder" }
        prune(root, clips.flatMap { listOf(it.getValue("rawPath") as String, it.getValue("matchedPath") as String) }.toSet())
        File(root, "manifest.json").writeText(manifest)
        File(root, "index.html").writeText(page.replace("__MANIFEST__", manifest.replace("</", "<\\/")))
        File(root, "README.md").writeText("""
            # Relay performance audition

            ${clips.size} listening cases: ${clips.count { it["group"] == "presetbank" }} factory presets,
            ${clips.count { it["group"] == "musical" }} musical examples, ${clips.count { it["group"] == "velocity" }} velocity probes
            and ${clips.count { it["group"] == "kit" }} actual starter pads. Each has raw and matched 24-bit mono WAVs.
            Open index.html locally or serve the directory. The manifest is embedded; no remote assets or browser synthesis are used.

            Musical examples trigger complete dry RelayPatch renders, preserve each selected phrase and native excitation velocity,
            and replace TUNE absolutely for each MIDI note. Their full releases overlap without trimming. A fixed mixGain is
            derived from the maximum simultaneous sum of individual peaks, bounding output to 0.8 or below. Event onset frames,
            exact recipes, native velocities, note lengths, schedules, peak/RMS and tuning evidence are in manifest.json.
            Musical note timelines show actual triggers; they do not claim a single source-weight trajectory for a chord.

            Preset, velocity and musical raw files precede engine loudness normalization. Kit raw files use the real starter
            samples' normalized engine level, verified sample-for-sample against SynthKits.relay. rawExportGain records any
            further export attenuation. Matched files use AuditionLevel: loudest 200 ms RMS 0.03, peak guard 0.99.
            Every held clip repeats its actual engine loop region exactly three times, with the real seam metric recorded.
            SHA-256 values identify every WAV; source and recipe fingerprints identify this round. Owner listening is still required.

            Regenerate: ./gradlew :synth:generateRelayPerformanceAudition${if (quick) " -PrelayPerformanceQuick" else ""}
        """.trimIndent() + "\n")
        println("Wrote ${clips.size * 2} WAVs ($wavBytes bytes), manifest.json, index.html and README.md under ${root.absolutePath}")
    }

    private fun cases(quick: Boolean): List<Case> = buildList {
        val bank = RelayPresets.all()
        check(bank.size == 36 && bank.map { it.name }.distinct().size == 36) { "expected the frozen 36-preset Relay bank" }
        for ((index, patch) in bank.withIndex()) {
            if (quick && patch != RelayPresets.forVoice(patch.voice).first()) continue
            add(Case("preset_${(index + 1).toString().padStart(2, '0')}_${slug(patch.name)}", "presetbank", patch,
                patch.name, "${patch.voice.name} factory recipe at ${noteName(midi(patch))}, with its complete saved source phrase and native excitation velocity."))
        }
        for (voice in RelayVoice.entries) {
            val patch = bank.single { it.voice == voice && it.name == musicalNames.getValue(voice) }
            check(!Relay.isLoop((Relay.defaults(voice) + patch.macros).getValue("HOLD"))) { "musical recipe must be finite" }
            val (bpm, notes) = notes(voice)
            add(Case("musical_${voice.name.lowercase(Locale.ROOT)}", "musical", patch, "${voice.name} · ${patch.name}",
                "A short C-minor-pentatonic ${if (voice in listOf(RelayVoice.ORGAN, RelayVoice.FLOW)) "chord passage" else "line"} using ${patch.name}. Each complete note keeps its saved phrase, excitation velocity and natural release; one fixed gain gives the overlap headroom.",
                notes, bpm))
            for (velocity in if (quick) listOf(.65f) else listOf(.30f, .65f, 1f)) {
                add(Case("velocity_${voice.name.lowercase(Locale.ROOT)}_${(velocity * 100).roundToInt().toString().padStart(3, '0')}",
                    "velocity", patch.copy(velocity = velocity), "${voice.name} · velocity ${"%.2f".format(Locale.ROOT, velocity)}",
                    "${patch.name} at ${noteName(midi(patch))}. Only excitation velocity changes. Use raw level to judge touch energy and matched level to compare articulation."))
            }
        }
        val pads = SynthKits.relay()
        check(pads.size == 16 && pads.all { it != null }) { "expected sixteen populated Relay starter pads" }
        for ((index, nullablePad) in pads.withIndex()) {
            if (quick && index !in listOf(0, 8, 14, 15)) continue
            val pad = requireNotNull(nullablePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            check(recipe.fx == null) { "Relay performance pack only accepts dry starter recipes" }
            val patch = recipe.patch as? RelayPatch ?: error("starter pad ${index + 1} is not Relay")
            add(Case("kit_a${(index + 1).toString().padStart(2, '0')}", "kit", patch,
                "A${(index + 1).toString().padStart(2, '0')} · ${patch.name}",
                "Actual starter pad ${index + 1}: ${patch.voice.name} at ${noteName(midi(patch))}. Raw playback preserves the starter sample level and the complete editable recipe.",
                padIndex = index + 1, actualPad = pad.snip))
        }
        check(size == if (quick) 22 else 76) { "unexpected Relay performance roster size $size" }
    }

    private fun single(case: Case): Performance {
        val patch = case.patch
        val report = Relay.inspect(patch.voice, patch.macros, patch.velocity, normalize = case.actualPad != null, phrase = patch.phrase)
        case.actualPad?.let { actual ->
            check(actual.channels == report.snip.channels && actual.sampleRate == report.snip.sampleRate && actual.samples.contentEquals(report.snip.samples)) {
                "${case.id} no longer matches the actual starter render"
            }
        }
        return Performance(report.snip, report)
    }

    private fun sequence(case: Case): Performance {
        val bpm = requireNotNull(case.sequenceTempoBpm)
        val patch = case.patch
        val baseMidi = midi(patch)
        val renders = case.notes.map { note ->
            val noteMidi = baseMidi + note.offset
            check(noteMidi in Relay.rootMidi(patch.voice)..Relay.rootMidi(patch.voice) + Relay.TUNE_SEMITONES)
            val tuned = patch.copy(macros = Relay.defaults(patch.voice) + patch.macros +
                ("TUNE" to (noteMidi - Relay.rootMidi(patch.voice)).toFloat() / Relay.TUNE_SEMITONES))
            val report = Relay.inspect(tuned.voice, tuned.macros, tuned.velocity, normalize = false, phrase = tuned.phrase)
            check(!report.loop)
            Triple((note.beat * 60.0 / bpm * Dsp.RATE).roundToInt(), tuned, report)
        }
        val boundaries = sortedMapOf<Int, Double>()
        for ((start, _, report) in renders) {
            boundaries[start] = boundaries.getOrDefault(start, 0.0) + report.snip.peak()
            val end = start + report.snip.frameCount
            boundaries[end] = boundaries.getOrDefault(end, 0.0) - report.snip.peak()
        }
        var activePeak = 0.0
        var peakBound = 0.0
        for (delta in boundaries.values) { activePeak += delta; peakBound = maxOf(peakBound, activePeak) }
        val gain = minOf(1.0, .8 / peakBound.coerceAtLeast(1e-12))
        val mixed = DoubleArray(renders.maxOf { it.first + it.third.snip.frameCount })
        for ((start, _, report) in renders) for (frame in report.snip.samples.indices) {
            mixed[start + frame] += report.snip.samples[frame] * gain
        }
        val snip = Snip(FloatArray(mixed.size) { mixed[it].toFloat() }, 1, Dsp.RATE)
        check(snip.peak() <= .800001f) { "musical headroom bound failed" }
        val events = renders.mapIndexed { index, (start, tuned, report) ->
            linkedMapOf<String, Any?>(
                "beat" to case.notes[index].beat, "timeSeconds" to start.toDouble() / Dsp.RATE, "startFrame" to start,
                "midi" to midi(tuned), "note" to noteName(midi(tuned)), "velocity" to tuned.velocity,
                "frames" to report.snip.frameCount, "seconds" to report.snip.durationSeconds,
                "peak" to report.snip.peak(), "rms" to metrics(report.snip).getValue("rms"),
                "requestedHz" to report.requestedHz, "actualHz" to report.actualHz, "tuningErrorCents" to cents(report),
                "internalRawPeak" to report.rawPeak, "patchJson" to tuned.toJsonValue(),
                "weights" to weights(report), "accents" to accents(report),
            )
        }
        return Performance(snip, renders.first().third, events, gain,
            mapOf("maxConcurrentPeakBound" to peakBound, "outputPeakBound" to peakBound * gain, "peakCeiling" to .8),
            renders.maxOf { it.third.rawPeak })
    }

    private fun notes(voice: RelayVoice): Pair<Double, List<Note>> = when (voice) {
        RelayVoice.KEY -> 104.0 to listOf(0.0 to 0, 1.0 to 3, 2.0 to 7, 3.0 to 5, 4.5 to 10, 6.0 to 7)
        RelayVoice.BASS -> 100.0 to listOf(0.0 to 0, 1.5 to 0, 3.0 to 7, 4.0 to 10, 5.5 to 5, 7.0 to 0)
        RelayVoice.PLUCK -> 112.0 to listOf(0.0 to 0, .75 to 7, 1.5 to 3, 2.5 to 10, 3.5 to 5, 5.0 to 12)
        RelayVoice.ORGAN -> 88.0 to listOf(0.0 to 0, 0.0 to 3, 0.0 to 7, 4.0 to 5, 4.0 to 7, 4.0 to 10)
        RelayVoice.CHIME -> 104.0 to listOf(0.0 to 0, 1.5 to 7, 3.0 to 12, 4.5 to 10, 6.0 to 7, 7.5 to 3)
        RelayVoice.FLOW -> 72.0 to listOf(0.0 to 0, 0.0 to 7, 0.0 to 12, 4.0 to 3, 4.0 to 5, 4.0 to 10)
    }.let { (bpm, values) -> bpm to values.map { Note(it.first, it.second) } }

    private fun loop(report: Relay.Report, repetitions: Int): Map<String, Any>? = if (!report.loop) null else report.snip.samples.let { x ->
        mapOf("regionFrames" to x.size, "regionSeconds" to report.snip.durationSeconds, "playbackRepetitions" to repetitions,
            "seamError" to report.seamError, "seamPass" to (report.seamError < 1e-3),
            "wrapStep" to abs(x.first() - x.last()),
            "wrapSlopeDifference" to abs((x[1] - x[0]) - (x.last() - x[x.lastIndex - 1])))
    }

    private fun accents(report: Relay.Report) = report.accents.map { mapOf("beat" to it.beat, "timeSeconds" to it.timeSeconds, "strength" to it.strength) }
    private fun weights(report: Relay.Report) = report.weights.map { mapOf("timeSeconds" to it.timeSeconds, "weights" to it.weights) }
    private fun cents(report: Relay.Report) = 1200.0 * ln(report.actualHz / report.requestedHz) / ln(2.0)
    private fun midi(patch: RelayPatch) = Relay.midiFor(patch.voice, (Relay.defaults(patch.voice) + patch.macros).getValue("TUNE"))

    private fun metrics(snip: Snip): Map<String, Any> {
        val x = snip.samples
        var power = 0.0; var sum = 0.0; var difference = 0.0
        for (i in x.indices) {
            power += x[i].toDouble() * x[i]; sum += x[i]
            if (i > 0) difference = maxOf(difference, abs(x[i].toDouble() - x[i - 1]))
        }
        val tailFrames = minOf(x.size, snip.sampleRate / 20)
        var tailPower = 0.0
        for (i in x.size - tailFrames until x.size) tailPower += x[i].toDouble() * x[i]
        return mapOf("peak" to snip.peak(), "rms" to sqrt(power / x.size), "dc" to sum / x.size,
            "loudestWindowRms" to Loudness.of(snip), "last50msRms" to sqrt(tailPower / tailFrames),
            "firstSample" to x.first(), "lastSample" to x.last(), "firstDifferencePeak" to difference,
            "finite" to x.all { it.isFinite() })
    }

    private fun contour(snip: Snip): List<Map<String, Any>> = (0 until 24).map { block ->
        val x = snip.samples
        val from = block * x.size / 24
        val to = (block + 1) * x.size / 24
        var energy = 0.0; var difference = 0.0
        for (i in from until to) {
            energy += x[i].toDouble() * x[i]
            if (i > from) { val d = x[i].toDouble() - x[i - 1]; difference += d * d }
        }
        mapOf("timeSeconds" to (from + to).toDouble() / 2 / Dsp.RATE,
            "rms" to sqrt(energy / (to - from)), "brightnessProxy" to sqrt(difference / energy.coerceAtLeast(1e-20)))
    }

    private fun prune(root: File, retained: Set<String>) {
        val previous = File(root, "manifest.json")
        if (!previous.isFile) return
        val clips = runCatching { Json.parse(previous.readText()).obj().getValue("clips").arr() }.getOrDefault(emptyList())
        for (clip in clips) for (key in listOf("rawPath", "matchedPath")) {
            val path = runCatching { clip.obj().getValue(key).str() }.getOrNull() ?: continue
            if (!Regex("(raw|matched)/[a-z0-9_]+\\.wav").matches(path) || path in retained) continue
            val file = File(root, path).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) check(file.delete())
        }
    }

    private fun group(id: String, title: String, description: String) = mapOf("id" to id, "title" to title, "description" to description)
    private fun slug(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is FloatArray -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported Relay performance manifest value: ${value.javaClass}")
    }
}
