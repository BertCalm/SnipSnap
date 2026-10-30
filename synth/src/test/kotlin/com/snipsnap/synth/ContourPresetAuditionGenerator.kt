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

/** Complete factory recipes on the unchanged engine, with actual native finite and settled renders. */
object ContourPresetAuditionGenerator {
    private const val TARGET = .12f
    private const val CEILING = .90f
    private val edgeValues = listOf(0f, .5f, 1f)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory." }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
        require(git(repo, "status", "--porcelain").isBlank()) { "Commit the tested source before generating the preset audition." }
        val commit = git(repo, "rev-parse", "HEAD")
        val root = File(args.firstOrNull() ?: "${repo.path}/testkit/contour-presets").canonicalFile
        require(!repo.toPath().startsWith(root.toPath())) { "Output must not contain the source repository." }
        require(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        for (directory in listOf("raw", "matched", "patches", "recipes")) {
            File(root, directory).apply { require(!exists() || deleteRecursively()); require(mkdirs()) }
        }
        val presets = ContourPresets.all().also { require(it.size == 36) }
        val sources = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/ContourEngine.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourTables.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ContourPresetAuditionGenerator.kt",
            "synth/src/test/resources/contour-presets/index.html",
        ).associateWith { sha(File(repo, it)) }
        val clips = mutableListOf<Map<String, Any?>>()
        val rows = mutableListOf<Map<String, Any?>>()
        val csv = mutableListOf("id,preset,mode,variant,edge,frames,raw_loudness,matched_loudness,match_gain,raw_peak,raw_dc,periodic_error,state_error,seam_before_blend")
        for (preset in presets) {
            val presetId = slug(preset.name)
            val description = ContourPresets.description(preset)
            val recipePath = "recipes/$presetId.json"
            val recipe = File(root, recipePath).apply { writeText(preset.toJsonText() + "\n") }
            require(ContourPatch.fromJsonText(recipe.readText()) == preset)
            rows += linkedMapOf("id" to presetId, "name" to preset.name, "voice" to preset.voice.name,
                "role" to description.role, "gesture" to description.gesture, "retained" to description.retained,
                "rootMidi" to ContourEngine.rootMidi(preset.voice), "recipePath" to recipePath,
                "recipeSha256" to sha(recipe), "authoredEdge" to preset.macros.getValue("EDGE"),
                "authoredMode" to if (ContourEngine.isLoop(preset.macros.getValue("HOLD"))) "held" else "finite",
                "edgeResponse" to preset.config.edgeResponse.name,
                "bodyTable" to preset.config.bodyTable.name, "edgeTable" to preset.config.edgeTable.name)
            // The two authored cases preserve every tone control. The held EDGE study
            // changes only EDGE, so finite envelope differences do not hide the sweep.
            val cases = listOf(Triple("finite", "authored", preset.macros.getValue("EDGE")),
                Triple("held", "authored", preset.macros.getValue("EDGE"))) +
                edgeValues.map { Triple("held", "edge", it) }
            for ((mode, variant, edge) in cases) {
                val id = "${presetId}_${mode}_${if (variant == "authored") "authored" else "edge_${tag(edge)}"}"
                val patch = preset.copy(macros = preset.macros + mapOf("HOLD" to if (mode == "held") 1f else 0f, "EDGE" to edge))
                val started = System.nanoTime()
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
                        "$id: public held recipe differs from its measured repeating period"
                    }
                    require(loop.periodicError < 1e-3 && loop.stateError < 1e-3 && loop.seamBeforeBlend < 1e-3) {
                        "$id: the independently measured held continuation did not settle"
                    }
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
                clips += linkedMapOf("id" to id, "presetId" to presetId, "mode" to mode, "variant" to variant,
                    "edge" to edge, "edgeResponse" to patch.config.edgeResponse.name,
                    "matchedPath" to matchedPath, "rawPath" to rawPath, "patchPath" to patchPath,
                    "frames" to raw.frameCount, "loop" to (loop != null), "durationSeconds" to raw.durationSeconds,
                    "rawLoudness" to rawMetrics.getValue("loudness"), "matchedLoudness" to matchedMetrics.getValue("loudness"),
                    "matchGain" to matchGain, "rawMetrics" to rawMetrics, "matchedMetrics" to matchedMetrics,
                    "rawSha256" to sha(rawFile), "matchedSha256" to sha(matchedFile), "patchSha256" to sha(patchFile),
                    "renderAndCheckMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
                    "loopDiagnostics" to loop?.let { linkedMapOf("periodicError" to it.periodicError,
                        "stateError" to it.stateError, "seamBeforeBlend" to it.seamBeforeBlend,
                        "finalSeamError" to Keys.seamError(it.samples, it.loopStart), "prerollCycles" to it.prerollCycles,
                        "requestedHz" to it.noteHz, "actualHz" to it.actualHz, "motionCycles" to it.motionCycles) })
                csv += listOf(id, presetId, mode, variant, edge, raw.frameCount,
                    rawMetrics.getValue("loudness"), matchedMetrics.getValue("loudness"), matchGain,
                    rawMetrics.getValue("peak"), rawMetrics.getValue("dc"),
                    loop?.periodicError, loop?.stateError, loop?.seamBeforeBlend).joinToString(",") { it?.toString().orEmpty() }
            }
            println("CONTOUR presets: ${rows.size}/36 recipes, ${clips.size}/180 complete cases")
        }
        require(clips.size == 180 && clips.map { it.getValue("id") }.distinct().size == 180)
        require(git(repo, "rev-parse", "HEAD") == commit && git(repo, "status", "--porcelain").isBlank()) {
            "Source changed while rendering; regenerate the complete audition."
        }
        require(sources.all { (path, hash) -> sha(File(repo, path)) == hash }) { "Source fingerprint changed." }
        val manifest = linkedMapOf("engine" to "CONTOUR", "kind" to "expanded-preset-bank", "schemaVersion" to 1,
            "sourceCommit" to commit, "sourceSha256" to sources, "workingTreeDirty" to false,
            "modelVersion" to ContourEngine.MODEL_VERSION, "sampleRate" to WavWriter.MPC_SAMPLE_RATE,
            "channels" to 1, "bitDepth" to 16, "presetCount" to presets.size, "retainedCount" to 9,
            "newCount" to 27, "clipCount" to clips.size, "hardwareVerified" to false,
            "newPresetsHeard" to false, "edgeValues" to edgeValues, "presets" to rows,
            "levelPolicy" to linkedMapOf("raw" to "Public native render level, PCM16; no rack effects or extra processing.",
                "matched" to "Scalar gain only: Loudness.of target 0.12, sample-peak ceiling 0.90.",
                "measurement" to "SnipSnap Loudness.of, not LUFS. A peak-limited clip may remain below target."),
            "notes" to listOf("Nine preceding recipes retain their complete saved settings and order. The other 27 are new proposals for listening.",
                "Authored finite and held recordings change only HOLD; the three-position held study changes HOLD and EDGE.",
                "Complete original recipes are separate from each recording's exact replay patch.",
                "Held files contain one complete settled period, without the finite attack. Browser entry/stop fades are playback-only.",
                "EDGE zero, half and full are positions, not equal perceived changes. Only owner listening judges the new recipes."),
            "checks" to listOf("180 complete public renders", "144 independently measured held closures",
                "complete recipe round trips", "PCM16 format, frame and quantization read-back", "source fingerprints"),
            "clips" to clips)
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(root, "metrics.csv").writeText(csv.joinToString("\n") + "\n")
        requireNotNull(javaClass.getResourceAsStream("/contour-presets/index.html")) {
            "Missing preset audition page resource."
        }.use { input -> File(root, "index.html").outputStream().use { input.copyTo(it) } }
        File(root, "README.txt").writeText("""
            CONTOUR EXPANDED PRESET AUDITION
            Native Kotlin source $commit; unchanged render model ${ContourEngine.MODEL_VERSION}.
            Nine retained recipes plus 27 new recipes: 36 in all.
            Authored finite and held forms, plus held EDGE 0%, 50%, 100%: 180 cases.
            Native files retain public render level. Matched files use scalar gain only, target 0.12 / peak ceiling 0.90.
            Complete original recipe JSON and exact recording patches are both included.
            Held downloads contain one exact period; finite attacks are retained as rendered.
            New recipes await owner listening; physical Android/MPC playback is unverified.
            Regenerate with ./gradlew :synth:generateContourPresetAudition from a clean source commit.
        """.trimIndent() + "\n")
        val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        require(bytes < 160L * 1024 * 1024) { "Preset pack exceeds its 160 MiB asset budget: $bytes" }
        println("Verified ${clips.size} cases / 36 recipes; ${root.path}, $bytes bytes")
    }

    private fun writeWav(root: File, path: String, snip: Snip): File {
        val file = WavWriter.write(File(root, path), snip, WavWriter.BitDepth.PCM_16)
        val info = WavInfo.read(file)
        val decoded = WavReader.read(file)
        require(info.bitsPerSample == 16 && info.channels == 1 && info.sampleRate == WavWriter.MPC_SAMPLE_RATE)
        require(decoded.frameCount == snip.frameCount && decoded.samples.all { it.isFinite() })
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
