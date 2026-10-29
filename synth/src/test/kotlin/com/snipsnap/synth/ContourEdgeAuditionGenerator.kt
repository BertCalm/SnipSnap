package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Compare the immediately previous recipes with the current complete factory EDGE ranges. */
object ContourEdgeAuditionGenerator {
    private const val BASELINE = "47809ba2de9cf042bd77dc7feebffdffefc987b1"
    private const val TARGET = .12f
    private const val CEILING = .90f
    private val values = listOf(0f, .25f, .5f, .75f, 1f)
    private val responses = listOf("previous", "revised")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory." }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
        require(git(repo, "status", "--porcelain").isBlank()) { "Commit the tested source before generating the EDGE audition." }
        val commit = git(repo, "rev-parse", "HEAD")
        val root = File(args.firstOrNull() ?: "${repo.path}/testkit/contour-edge-audition").canonicalFile
        require(!repo.toPath().startsWith(root.toPath())) { "Output must not contain the source repository." }
        require(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        for (directory in listOf("raw", "matched", "patches")) {
            File(root, directory).apply { require(!exists() || deleteRecursively()); require(mkdirs()) }
        }
        // This historical comparison has nine pinned preceding recipes. The
        // expanded factory bank has its own audition, without invented baselines.
        val presets = ContourPresets.original().also { require(it.size == 9) }
        val baselinePaths = presets.associate { it.name to
            "synth/src/test/resources/contour-edge-audition/baseline/${slug(it.name)}.json" }
        val baselineRecipeSha256 = linkedMapOf<String, String>()
        val previous = presets.associate { preset ->
            val path = baselinePaths.getValue(preset.name)
            val bytes = requireNotNull(javaClass.getResourceAsStream(
                "/contour-edge-audition/baseline/${slug(preset.name)}.json")) {
                "Missing previous recipe for ${preset.name}"
            }.use { it.readBytes() }
            require(bytes.contentEquals(File(repo, path).readBytes())) { "Stale baseline recipe resource: $path" }
            baselineRecipeSha256[slug(preset.name)] = sha(bytes)
            val saved = ContourPatch.fromJsonText(bytes.toString(Charsets.UTF_8))
            require(saved.name == preset.name && saved.voice == preset.voice &&
                saved.config.edgeResponse == ContourEdgeResponse.FOREGROUND) {
                "Invalid $BASELINE baseline recipe for ${preset.name}"
            }
            preset.name to saved
        }
        val sources = (listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/ContourEngine.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourTables.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ContourEdgeAuditionGenerator.kt",
            "synth/src/test/resources/contour-edge-audition/index.html",
        ) + baselinePaths.values).associateWith { sha(File(repo, it)) }
        val clips = mutableListOf<Map<String, Any?>>()
        val csv = mutableListOf("id,preset,mode,response,edge,frames,raw_loudness,matched_loudness,match_gain,raw_peak,raw_dc,periodic_error,state_error,seam_before_blend")
        for (preset in presets) for (mode in listOf("finite", "held")) for (edge in values) {
            for (response in responses) {
                val id = "${slug(preset.name)}_${mode}_${tag(edge)}_$response"
                // The previous side is a pinned complete recipe, never today's preset with
                // one field changed. Future recipe revisions must not rewrite the reference.
                val authored = if (response == "previous") previous.getValue(preset.name) else preset
                val patch = authored.copy(name = "$id EDGE study",
                    macros = authored.macros + mapOf("TUNE" to .5f, "HOLD" to if (mode == "held") 1f else 0f, "EDGE" to edge))
                val started = System.nanoTime()
                // The raw file is the public render, so its recipe can be replayed directly in the CLI.
                val raw = patch.render()
                require(raw.channels == 1 && raw.sampleRate == WavWriter.MPC_SAMPLE_RATE)
                require(raw.frameCount in 1..WavWriter.MPC_SAMPLE_RATE * 8)
                require(raw.samples.all { it.isFinite() } && raw.peak() in 1e-7f..1f) { "Unsafe or silent $id" }
                val rawMetrics = metrics(raw)
                require(abs(rawMetrics.getValue("dc")) < .01) { "Excess DC in $id" }
                val loop = if (mode == "held") ContourEngine.renderLoopMeasured(patch.voice, patch.macros,
                    patch.velocity, patch.config, normalize = true) else null
                if (loop != null) {
                    require(raw.samples.contentEquals(loop.samples.copyOfRange(loop.loopStart, loop.samples.size))) {
                        "$id: the public held recipe differs from its measured repeating period"
                    }
                    require(loop.periodicError < 1e-3 && loop.stateError < 1e-3 && loop.seamBeforeBlend < 1e-3)
                    require(Keys.seamError(loop.samples, loop.loopStart) < Keys.MAX_SEAM_ERROR)
                    require(abs(1200 * ln(loop.actualHz / loop.noteHz) / ln(2.0)) < .05) { "Loop fit moved $id" }
                }
                val matched = Snip(raw.samples.copyOf().also {
                    Dsp.levelTo(it, raw.sampleRate, TARGET, ceiling = CEILING)
                }, raw.channels, raw.sampleRate)
                val matchedMetrics = metrics(matched)
                require(matched.samples.all { it.isFinite() } && matched.peak() <= CEILING + 1e-6f)
                val matchGain = matchedMetrics.getValue("rms") / rawMetrics.getValue("rms")
                val rawPath = "raw/$id.wav"
                val matchedPath = "matched/$id.wav"
                val patchPath = "patches/$id.json"
                val rawFile = writeWav(root, rawPath, raw)
                val matchedFile = writeWav(root, matchedPath, matched)
                val patchFile = File(root, patchPath).apply { writeText(patch.toJsonText() + "\n") }
                require(ContourPatch.fromJsonText(patchFile.readText()) == patch) { "$id: incomplete recipe round trip" }
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                clips += linkedMapOf(
                    "id" to id, "presetId" to slug(preset.name), "mode" to mode, "edge" to edge,
                    "response" to response, "matchedPath" to matchedPath, "rawPath" to rawPath, "patchPath" to patchPath,
                    "edgeResponse" to patch.config.edgeResponse.name,
                    "recipeSourceCommit" to if (response == "previous") BASELINE else commit,
                    "frames" to raw.frameCount, "loop" to (loop != null), "durationSeconds" to raw.durationSeconds,
                    "rawLoudness" to rawMetrics.getValue("loudness"), "matchedLoudness" to matchedMetrics.getValue("loudness"),
                    "matchGain" to matchGain, "rawMetrics" to rawMetrics, "matchedMetrics" to matchedMetrics,
                    "rawSha256" to sha(rawFile), "matchedSha256" to sha(matchedFile), "patchSha256" to sha(patchFile),
                    "renderAndCheckMilliseconds" to elapsed,
                    "loopDiagnostics" to loop?.let { linkedMapOf("periodicError" to it.periodicError,
                        "stateError" to it.stateError, "seamBeforeBlend" to it.seamBeforeBlend,
                        "finalSeamError" to Keys.seamError(it.samples, it.loopStart), "prerollCycles" to it.prerollCycles,
                        "requestedHz" to it.noteHz, "actualHz" to it.actualHz, "motionCycles" to it.motionCycles) },
                )
                csv += listOf(id, slug(preset.name), mode, response, edge, raw.frameCount,
                    rawMetrics.getValue("loudness"), matchedMetrics.getValue("loudness"), matchGain,
                    rawMetrics.getValue("peak"), rawMetrics.getValue("dc"),
                    loop?.periodicError, loop?.stateError, loop?.seamBeforeBlend).joinToString(",") { it?.toString().orEmpty() }
                if (clips.size % 20 == 0) println("CONTOUR EDGE: ${clips.size}/180 complete cases")
            }
        }
        require(clips.size == 180 && clips.map { it.getValue("id") }.distinct().size == 180)
        require(git(repo, "rev-parse", "HEAD") == commit && git(repo, "status", "--porcelain").isBlank()) {
            "Source changed while rendering; regenerate the complete audition."
        }
        require(sources.all { (path, hash) -> sha(File(repo, path)) == hash }) { "Source fingerprint changed." }
        val manifest = linkedMapOf(
            "engine" to "CONTOUR", "kind" to "edge-response-review", "schemaVersion" to 2,
            "sourceCommit" to commit, "baselineCommit" to BASELINE, "sourceSha256" to sources,
            "baselineRecipeSha256" to baselineRecipeSha256,
            "baselineRecipePolicy" to "Complete FOREGROUND factory recipes saved at $BASELINE; only TUNE, HOLD and EDGE are overridden for the study.",
            "workingTreeDirty" to false, "values" to values, "sampleRate" to WavWriter.MPC_SAMPLE_RATE,
            "channels" to 1, "bitDepth" to 16, "clipCount" to clips.size, "hardwareVerified" to false,
            "presets" to presets.map { linkedMapOf("id" to slug(it.name), "name" to it.name,
                "voice" to it.voice.name, "rootMidi" to ContourEngine.rootMidi(it.voice)) },
            "levelPolicy" to linkedMapOf("raw" to "Public native render level, PCM16; no rack effects or extra processing.",
                "matched" to "Scalar gain only: Loudness.of target 0.12, sample-peak ceiling 0.90.",
                "measurement" to "SnipSnap Loudness.of, not LUFS. A peak-limited clip may remain below the target."),
            "notes" to listOf("Previous uses the complete saved recipes from $BASELINE; revised uses the current factory recipes.",
                "Only TUNE, HOLD and EDGE are overridden. All other controls and configuration stay as authored on each side.",
                "The actual saved edge response and source recipe commit are recorded on each clip.",
                "Finite notes preserve their complete attack and decay. Held files contain one complete settled period, without the finite attack.",
                "Browser looping should use the whole held buffer. A short browser entry/stop fade is playback-only.",
                "EDGE zero, half and full are control positions, not claims of equal perceived change.",
                "Only owner listening can judge whether the response is useful or rounded enough."),
            "checks" to listOf("180 complete public renders", "90 independently measured held closures",
                "complete recipe round trips", "pinned previous recipe resource hashes", "PCM16 format, frame and quantization read-back", "source fingerprints"),
            "clips" to clips,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(root, "metrics.csv").writeText(csv.joinToString("\n") + "\n")
        requireNotNull(javaClass.getResourceAsStream("/contour-edge-audition/index.html")) {
            "Missing EDGE audition page resource."
        }.use { input -> File(root, "index.html").outputStream().use { input.copyTo(it) } }
        File(root, "README.txt").writeText("""
            CONTOUR EDGE RESPONSE REVIEW
            Native Kotlin source $commit; immediately previous recipe reference $BASELINE.
            Nine complete presets, finite and held, five EDGE positions, two responses: 180 cases.
            Previous uses pinned FOREGROUND recipes; revised uses current factory recipes.
            Serve index.html over HTTP. Matched playback uses gain only, target 0.12 with a 0.90 peak ceiling.
            Raw files retain public native render level. Complete patch JSON replays each raw WAV through the CLI.
            Held downloads contain one exact period; the page repeats it. Finite attacks are retained as rendered.
            No listening approval or hardware verification is claimed. See manifest.json and metrics.csv for measurements.
        """.trimIndent() + "\n")
        val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        require(bytes < 200L * 1024 * 1024) { "EDGE pack exceeds its 200 MiB asset budget: $bytes" }
        println("Verified ${clips.size} EDGE cases; ${root.path}, $bytes bytes")
    }

    private fun writeWav(root: File, path: String, snip: Snip): File {
        val file = WavWriter.write(File(root, path), snip, WavWriter.BitDepth.PCM_16)
        val info = WavInfo.read(file)
        val decoded = WavReader.read(file)
        require(info.bitsPerSample == 16 && info.channels == 1 && info.sampleRate == WavWriter.MPC_SAMPLE_RATE)
        require(decoded.frameCount == snip.frameCount && decoded.samples.all { it.isFinite() })
        // The writer uses 32767 endpoint scale, whereas decoding divides by 32768.
        require(decoded.samples.indices.maxOf { abs(decoded.samples[it] - snip.samples[it]) } <= 5e-5f)
        require(file.length() < 25L * 1024 * 1024) { "$path exceeds the hosting file limit" }
        return file
    }

    private fun metrics(snip: Snip): Map<String, Double> {
        val x = snip.samples
        val rms = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        return linkedMapOf("peak" to snip.peak().toDouble(), "rms" to rms,
            "dc" to x.sumOf { it.toDouble() } / x.size, "loudness" to Loudness.of(snip).toDouble())
    }

    private fun slug(name: String) = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun sha(file: File) = sha(file.readBytes())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest value ${value.javaClass.name}")
    }
    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        require(process.waitFor() == 0) { output }
        return output.trim()
    }
}
