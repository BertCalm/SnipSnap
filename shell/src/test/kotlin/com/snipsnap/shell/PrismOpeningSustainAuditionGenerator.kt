package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismMotionEditor
import com.snipsnap.synth.PrismMotionSources
import com.snipsnap.synth.PrismOpeningPresets
import com.snipsnap.synth.PrismPatch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/** Compare unchanged pad performances with the opt-in opening-once instrument delivery. */
object PrismOpeningSustainAuditionGenerator {
    private data class Case(val id: String, val family: String, val title: String, val variant: String,
        val patch: PrismPatch, val description: String)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "usage: PrismOpeningSustainAuditionGenerator [output-directory]"
        }
        val root = File(args.firstOrNull() ?: "../testkit/prism-opening-sustain-audition").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val repository = repositoryRoot()
        val accepted = verifyAcceptedDefaults(repository)
        val cases = cases()
        check(cases.size == 16 && cases.map { it.id }.toSet().size == 16)
        // Warm the new public delivery before recording a single render-call duration.
        val warm = cases.first { it.variant == "opening-sustain" }.patch
        opening(warm)
        val began = System.nanoTime()
        val rows = cases.mapIndexed { index, case ->
            render(root, case).also { println("PRISM opening/sustain ${index + 1}/16: ${case.title}") }
        }
        check(rows.count { it["variant"] == "open" } == 4 &&
            rows.count { it["variant"] == "hold" } == 4 &&
            rows.count { it["variant"] == "opening-sustain" } == 8)
        val sourcePaths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Prism.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismMotion.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismOpeningPresets.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/PrismPadMaker.kt",
            "shell/src/test/kotlin/com/snipsnap/shell/PrismOpeningSustainAuditionGenerator.kt",
            "shell/src/test/resources/audition/prism-opening-sustain.html",
            "shell/build.gradle.kts",
        )
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "PRISM · Opening once, moving sustain",
            "generatedAt" to Instant.now().toString(),
            "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "sourceDirty" to git(repository, "status", "--porcelain", "--untracked-files=normal")?.isNotBlank(),
            "sourceFilesSha256" to sourcePaths.associateWith { hash(File(repository, it)) },
            "sampleRate" to WavWriter.MPC_SAMPLE_RATE, "channels" to 1, "bitDepth" to 24,
            "javaVersion" to System.getProperty("java.version"),
            "osArchitecture" to System.getProperty("os.arch"),
            "clipCount" to rows.size, "clips" to rows,
            "acceptedDefaults" to accepted,
            "nativeMeaning" to "Actual dry public renders. The new instrument route preserves its opening before a positive sampler loop marker.",
            "matchedMeaning" to "One gain change over the complete note, using the shared loudest-200-ms RMS target .03 with peak guard. Loop offsets stay unchanged.",
            "playbackProofMeaning" to "Long files retain the complete prefix once, then concatenate only the marked sustain region eight times. They never repeat the opening eight times. Ordinary HOLD comparisons have no opening prefix.",
            "recipeMeaning" to "OPEN and pure HOLD use unchanged PadRecipe JSON. Opening/sustain uses the real PrismPadMaker.Spec delivery JSON, including held-model version, complete source patch and note-off release.",
            "measurementNote" to "New instrument seam evidence compares the exported sustain with a separately synthesized preceding region in the continuous prefix. No duplicated-cycle measurement is used. Carrier bandwidth is not a complete alias-spectrum measurement.",
            "timingNote" to "Elapsed milliseconds measure one public render only on this JVM, excluding replay, probes, matching and file writes. Android interaction and MPC playback remain device checks.",
            "listeningStatus" to "Rendered and measured; sonic approval still requires a human listen.",
            "generationSeconds" to (System.nanoTime() - began) / 1e9,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        val page = requireNotNull(javaClass.getResourceAsStream("/audition/prism-opening-sustain.html")) {
            "missing PRISM opening/sustain page"
        }
        File(root, "index.html").outputStream().use { output -> page.use { it.copyTo(output) } }
        println("Wrote 16 PRISM opening/sustain comparisons to $root")
    }

    private fun cases(): List<Case> = buildList {
        val presets = PrismOpeningPresets.all()
        for ((index, patch) in presets.withIndex()) {
            val id = "factory-${index + 1}"
            val held = held(patch)
            add(Case("$id-open", patch.name, "${patch.name} · OPEN note", "open", patch,
                "The accepted finite opening at its original note and velocity. This keeps the ordinary pad recipe and renderer."))
            add(Case("$id-hold", patch.name, "${patch.name} · Pure HOLD", "hold", held,
                "The existing repeating HOLD export: sustain only, with its loop starting at frame zero."))
            add(Case("$id-instrument", patch.name, "${patch.name} · Opening + sustain", "opening-sustain", held,
                "MAKE INSTRUMENT delivery: the body opens once, enters powered moving sustain, then loops only the marked region."))
        }
        val bowed = presets.single { it.name == "Bowed Bloom" }
        for ((index, seconds) in listOf(.2f, 2.4f).withIndex()) {
            add(Case("time-$index", "Time checks", "Bowed Bloom · ${seconds}s opening + sustain",
                "opening-sustain", held(PrismMotionEditor.withTime(bowed, seconds)),
                "The requested opening and colour-cycle time is ${seconds}s; the actual fitted period and handoff are measured separately."))
        }
        val key = presets.single { it.name == "Soft Arrival" }
        for (id in listOf("fork-mellow-reed", "tessera-wooden-arrival")) {
            val source = requireNotNull(PrismMotionSources.byId(id)) { "missing source $id" }
            val picked = PrismMotionSources.applySource(key, source)
            add(Case("source-$id", "Source checks", "${source.title} · Opening + sustain", "opening-sustain",
                held(picked), "A source-picker colour saved into both endpoints, then delivered with one opening and a separate sustain loop."))
        }
    }

    private fun held(patch: PrismPatch): PrismPatch = PrismMotionEditor.withRepeat(patch, true)
        .let { it.copy(macros = it.macros + ("HOLD" to 1f)) }

    private fun opening(patch: PrismPatch): KeyNote = Prism.openingNote(patch.voice, patch.macros,
        patch.velocity, patch.template, requireNotNull(patch.motion))

    private fun render(root: File, case: Case): Map<String, Any?> {
        val patch = case.patch
        val instrument = case.variant == "opening-sustain"
        val looped = case.variant != "open"
        val started = System.nanoTime()
        val note = if (instrument) opening(patch) else KeyNote(patch.render())
        val elapsedMillis = (System.nanoTime() - started) / 1e6
        val native = note.snip
        check(native.channels == 1 && native.sampleRate == WavWriter.MPC_SAMPLE_RATE && native.samples.isNotEmpty() &&
            native.samples.all { it.isFinite() } && native.peak() <= 1f) { "${case.id}: invalid audio" }
        val midi = Prism.midiFor(patch.voice, (Prism.defaults(patch.voice) + patch.macros).getValue("TUNE"))
        val loopStart = if (looped) note.loopStartFrame.toInt() else null
        val spec = if (instrument) PrismPadMaker.Spec(patch, releaseSeconds = .6f) else null
        val recipeText: String
        val replay: KeyNote
        if (spec != null) {
            recipeText = spec.toJsonText()
            val reopened = PrismPadMaker.Spec.fromJsonText(recipeText)
            check(reopened == spec && reopened.toJsonText() == recipeText)
            replay = PrismPadMaker.renderZone(reopened, midi)
        } else {
            val recipe = PadRecipe(patch)
            recipeText = recipe.toJsonText()
            val reopened = PadRecipe.fromJsonText(recipeText)
            check(reopened == recipe && reopened.toJsonText() == recipeText)
            replay = KeyNote(reopened.render())
        }
        check(replay.loopStartFrame == note.loopStartFrame && sameBits(replay.snip.samples, native.samples)) {
            "${case.id}: actual production recipe replay changed the note or loop marker"
        }
        val evidence = if (instrument) instrumentEvidence(patch, note) else emptyMap()
        val matched = AuditionLevel.level(native)
        val nativePath = "audio/${case.id}-native.wav"
        val matchedPath = "audio/${case.id}-matched.wav"
        val suffix = if (instrument) PrismPadMaker.RECIPE_SUFFIX else ".json"
        val recipePath = "recipes/${case.id}$suffix"
        val sampler = SmplChunk(midi, loopStart?.let { SmplChunk.Loop(it.toLong(), native.frameCount.toLong()) })
        for ((path, snip) in listOf(nativePath to native, matchedPath to matched)) {
            val file = File(root, path)
            WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24, smpl = sampler)
            check(WavReader.readSmplStrict(file) == sampler) { "${case.id}: WAV sampler metadata changed" }
        }
        File(root, recipePath).apply { parentFile.mkdirs(); writeText(recipeText) }
        val hashes = linkedMapOf("native" to hash(File(root, nativePath)),
            "matched" to hash(File(root, matchedPath)), "recipe" to hash(File(root, recipePath)))
        val row = linkedMapOf<String, Any?>(
            "id" to case.id, "family" to case.family, "title" to case.title,
            "variant" to case.variant, "description" to case.description,
            "voice" to patch.voice.name, "midi" to midi, "velocity" to patch.velocity,
            "sourceId" to patch.motion?.sourceId, "requestedTimeSeconds" to patch.motion?.seconds,
            "native" to nativePath, "matched" to matchedPath, "recipe" to recipePath,
            "recipeType" to if (instrument) "PrismPadMaker.Spec" else "PadRecipe",
            "heldModel" to if (instrument) Prism.OPENING_MODEL_VERSION else null,
            "releaseSeconds" to spec?.releaseSeconds,
            "loopStartFrame" to loopStart, "loopEndFrameExclusive" to if (looped) native.frameCount else null,
            "loopFrames" to loopStart?.let { native.frameCount - it },
            "frames" to native.frameCount, "seconds" to native.durationSeconds,
            "elapsedMs" to elapsedMillis, "recipeReplayExact" to true, "wavSamplerMetadataExact" to true,
            "nativeSamplesSha256" to samplesHash(native.samples),
            "hashes" to hashes, "metrics" to mapOf("native" to metrics(native), "matched" to metrics(matched)),
        )
        row.putAll(evidence)
        if (loopStart != null) {
            val eightNative = prefixThenEight(native, loopStart)
            val eightMatched = prefixThenEight(matched, loopStart)
            val eightNativePath = "audio/${case.id}-native-eight-sustain.wav"
            val eightMatchedPath = "audio/${case.id}-matched-eight-sustain.wav"
            WavWriter.write(File(root, eightNativePath), eightNative, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, eightMatchedPath), eightMatched, WavWriter.BitDepth.PCM_24)
            row["eightNative"] = eightNativePath
            row["eightMatched"] = eightMatchedPath
            row["eightFrames"] = eightNative.frameCount
            row["proofPrefixFrames"] = loopStart
            row["proofSustainRepetitions"] = 8
            row["proofOpeningCount"] = if (instrument) 1 else 0
            hashes["eightNative"] = hash(File(root, eightNativePath))
            hashes["eightMatched"] = hash(File(root, eightMatchedPath))
        }
        return row
    }

    private fun instrumentEvidence(patch: PrismPatch, note: KeyNote): Map<String, Any?> {
        val probe = Prism.openingProbe(patch.voice, patch.macros, patch.velocity, patch.template,
            requireNotNull(patch.motion), keepRaw = false)
        val start = note.loopStartFrame.toInt()
        val samples = note.snip.samples
        check(start > 0 && start < samples.size && probe.note.loopStartFrame == note.loopStartFrame &&
            sameBits(samples, probe.note.snip.samples)) { "${patch.name}: measurement/public delivery mismatch" }
        val loop = samples.copyOfRange(start, samples.size)
        check(loop.size == probe.loopFrames && probe.precedingLoop.size == loop.size)
        check(sameBits(samples.copyOfRange(start - loop.size, start), probe.precedingLoop)) {
            "${patch.name}: preceding-loop evidence is not the independently generated prefix"
        }
        val seam = Keys.seamError(probe.precedingLoop + loop, loop.size)
        check(seam < Keys.MAX_SEAM_ERROR && seam == probe.seamError) { "${patch.name}: real preceding-loop seam $seam" }
        check(probe.rawPeak <= Prism.RAW_PEAK_CEILING + 1e-6f &&
            probe.partialHz.all { it > 0.0 && it < Prism.USABLE_BANDWIDTH_HZ })
        val cycleDelta = sqrt(loop.indices.sumOf {
            val delta = loop[it].toDouble() - probe.precedingLoop[it]
            delta * delta
        } / loop.sumOf { it.toDouble() * it }.coerceAtLeast(1e-20))
        check(cycleDelta < 1e-3) { "${patch.name}: adjacent synthesized sustain regions differ by $cycleDelta" }
        fun boundaryStep(frame: Int) = abs(samples[frame].toDouble() - samples[frame - 1])
        return linkedMapOf(
            "openingFrames" to probe.openingFrames, "bridgeFrames" to probe.bridgeFrames,
            "settlingFrames" to start - probe.openingFrames - probe.bridgeFrames,
            "motionCycleFrames" to probe.motionCycleFrames, "loopFrames" to probe.loopFrames,
            "actualCycleSeconds" to probe.actualPeriodSeconds,
            "loopSeconds" to probe.loopFrames.toDouble() / WavWriter.MPC_SAMPLE_RATE,
            "seamError" to seam, "precedingLoopRelativeRmsDelta" to cycleDelta,
            "precedingLoopSamplesSha256" to samplesHash(probe.precedingLoop),
            "openingToBridgeStep" to boundaryStep(probe.openingFrames),
            "bridgeToSustainStep" to boundaryStep(probe.openingFrames + probe.bridgeFrames),
            "effectiveHz" to probe.effectiveHz, "rawPeak" to probe.rawPeak,
            "openingPublicReplayExact" to true,
        )
    }

    private fun prefixThenEight(snip: Snip, loopStart: Int): Snip {
        val loopFrames = snip.frameCount - loopStart
        require(loopStart >= 0 && loopFrames > 0)
        return snip.copy(samples = FloatArray(loopStart + 8 * loopFrames) { frame ->
            snip.samples[if (frame < loopStart) frame else loopStart + (frame - loopStart) % loopFrames]
        })
    }

    private fun verifyAcceptedDefaults(repository: File): List<Map<String, Any?>> {
        val directory = File(repository, "synth/src/test/resources/prism-opening-accepted")
        val manifest = Json.parse(File(directory, "manifest.json").readText()).obj()
        return manifest.getValue("clips").arr().map { value ->
            val row = value.obj()
            val name = row.getValue("factoryName").str()
            val text = File(directory, row.getValue("recipe").str()).readText()
            check(digest(text.toByteArray(Charsets.UTF_8)) == row.getValue("recipeSha256").str()) {
                "$name: accepted fixture bytes changed"
            }
            val accepted = PadRecipe.fromJsonText(text)
            val original = accepted.patch as PrismPatch
            val factory = PrismOpeningPresets.all().single { it.name == name }
            check(factory.copy(name = original.name) == original &&
                PadRecipe(factory.copy(name = original.name)).toJsonText() == text) {
                "$name: accepted recipe fields or serialization changed"
            }
            val samples = factory.render().samples
            check(sameBits(samples, accepted.render().samples)) { "$name: accepted finite sound changed on this runtime" }
            linkedMapOf<String, Any?>("name" to name, "fixtureRecipeSha256" to row.getValue("recipeSha256").str(),
                "sameRuntimeSamplesExact" to true, "recipeFieldsExact" to true,
                "currentSamplesSha256" to samplesHash(samples),
                "capturedSamplesSha256" to row.getValue("rawSamplesSha256").str())
        }
    }

    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf(
        "peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "loudness" to Loudness.of(snip),
    )
    private fun sameBits(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return digest(bytes.array())
    }
    private fun hash(file: File) = digest(file.readBytes())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun repositoryRoot(): File {
        var file = File(".").canonicalFile
        while (!File(file, ".git").exists() && file.parentFile != null) file = file.parentFile
        require(File(file, ".git").exists()) { "cannot locate source checkout" }
        return file
    }
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported PRISM opening manifest value ${value.javaClass.name}")
    }
}
