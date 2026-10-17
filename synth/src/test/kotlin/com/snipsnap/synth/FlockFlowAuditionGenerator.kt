package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Revises only the five FLOW recordings; the accepted first-flight audio is copied exactly. */
object FlockFlowAuditionGenerator {
    private const val TARGET = .12
    private const val CEILING = .90
    private const val MODEL = 2
    private const val OVERSAMPLE = 4
    private val paces = listOf("Slowest", "Slow", "Medium", "Near original", "Fastest")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 2) { "supply output directory, then immutable baseline directory" }
        val root = File(args.getOrNull(0) ?: "../testkit/flock-flow-audition").canonicalFile
        val baseline = File(args.getOrNull(1) ?: "../testkit/flock-audition").canonicalFile
        require(root != baseline && !baseline.toPath().startsWith(root.toPath()) && !root.toPath().startsWith(baseline.toPath())) {
            "output and immutable baseline directories must not overlap"
        }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, ".git").exists() } ?: error("run within the SnipSnap repository")
        val baselineManifest = File(baseline, "manifest.json")
        val manifest = objectMap(fromJson(Json.parse(baselineManifest.readText())))
        require(manifest["engine"] == "FLOCK" && (manifest["clipCount"] as Number).toInt() == 51)
        val baselineSource = objectMap(manifest.getValue("source"))
        val clips = objectList(manifest.getValue("clips"))
        val flowIds = (0..4).map { "sweep-flow-$it" }.toSet()
        require(clips.count { it["id"] in flowIds } == 5)
        for (clip in clips) verifyClip(baseline, clip)
        root.mkdirs()
        // Copy the baseline assets and old source snapshots. Archive names are rebuilt below.
        for (file in baseline.walkTopDown().filter { it.isFile }) {
            val path = file.relativeTo(baseline).invariantSeparatorsPath
            if (path.endsWith(".zip") || path == "flock-archives.sha256" || path == "manifest.json") continue
            copy(file, File(root, path))
        }
        copy(baselineManifest, File(root, "baseline-manifest.json"))
        val source = provenance(repo)
        for (entry in objectList(source.getValue("files"))) {
            val path = entry.getValue("path") as String
            copy(File(repo, path), File(root, "source-flow/$path"))
        }
        for (clip in clips) {
            clip["source"] = baselineSource
            clip["model"] = 1
        }
        val pairs = mutableListOf<Map<String, Any?>>()
        val originalCopies = mutableListOf<MutableMap<String, Any?>>()
        val endpointAudio = mutableListOf<FloatArray>()
        val began = System.nanoTime()
        var renderNanos = 0L
        for (index in 0..4) {
            val id = "sweep-flow-$index"
            val original = clips.single { it["id"] == id }
            val before = LinkedHashMap(original)
            val beforeId = "before-flow-$index"
            before["id"] = beforeId
            before["label"] = "Previous FLOW ${fmt((original.getValue("sweepValue") as Number).toDouble())}"
            before["category"] = "flow-revision"
            before["description"] = "Original model-1 FLOW recording, copied byte for byte from the first audition."
            for ((key, directory, extension) in listOf(
                Triple("rawSrc", "raw", "wav"), Triple("src", "matched", "wav"),
                Triple("recipeSrc", "recipes", "json"), Triple("traceSrc", "traces", "csv"),
            )) {
                val path = "$directory/$beforeId.$extension"
                copy(File(baseline, original.getValue(key) as String), File(root, path))
                before[key] = path
            }
            val beforeTrace = readTrace(File(baseline, original.getValue("traceSrc") as String))
            val duration = (original.getValue("duration") as Number).toDouble()
            val beforeMetrics = motionMetrics(beforeTrace, duration)
            before["motionMetrics"] = beforeMetrics
            originalCopies += before

            val voice = FlockVoice.valueOf(original.getValue("gesture") as String)
            val macros = objectMap(original.getValue("params")).mapValues { (_, value) -> (value as Number).toFloat() }
            val midi = (original.getValue("note") as Number).toInt()
            val velocity = (original.getValue("velocity") as Number).toFloat()
            val seed = (original.getValue("seed") as Number).toLong()
            val mode = FlockMode.valueOf(original.getValue("mode") as String)
            val perturb = original.getValue("perturb") as Boolean
            val start = System.nanoTime()
            val probe = Flock.renderDetailed(voice, macros, midi, velocity, seed, mode, perturb,
                oversample = OVERSAMPLE, model = MODEL)
            renderNanos += System.nanoTime() - start
            val raw = Snip(probe.rawSamples, channels = 1, sampleRate = probe.sampleRate)
            require(raw.sampleRate == 44_100 && raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() })
            require(abs(raw.frameCount.toDouble() / raw.sampleRate - duration) < 1e-9)
            val rate = Flock.motionRate(voice, macros.getValue("FLOW"), MODEL)
            require(rate == probe.diagnostics.motionRate)
            val nativeGain = minOf(1.0, CEILING / peak(raw).coerceAtLeast(1e-12))
            val matchedGain = minOf(TARGET / Loudness.of(raw), CEILING / peak(raw).coerceAtLeast(1e-12))
            val native = gain(raw, nativeGain)
            val matched = gain(raw, matchedGain)
            val level = linkedMapOf<String, Any?>(
                "rawGain" to nativeGain, "matchedGain" to matchedGain,
                "rawPeak" to peak(native), "rawRms" to rms(native),
                "matchedPeak" to peak(matched), "matchedRms" to rms(matched),
                "synthesisPeak" to peak(raw), "synthesisRms" to rms(raw),
                "rawLoudest200msRms" to Loudness.of(native), "matchedLoudest200msRms" to Loudness.of(matched),
                "peakLimited" to (peak(matched) >= CEILING - 1e-6),
                "matchGroup" to null, "matchingPolicy" to "per-clip fixed gain",
            )
            WavWriter.write(File(root, original.getValue("rawSrc") as String), native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, original.getValue("src") as String), matched, WavWriter.BitDepth.PCM_16)
            writeTrace(File(root, original.getValue("traceSrc") as String), probe.trace)
            val afterMetrics = motionMetrics(probe.trace, duration)
            val label = "Revised FLOW ${fmt(macros.getValue("FLOW").toDouble())}"
            val patch = FlockPatch(label, voice, macros, midi, velocity, seed, model = MODEL)
            writeJson(File(root, original.getValue("recipeSrc") as String), linkedMapOf(
                "schemaVersion" to 1, "patch" to patch.toJsonValue(),
                "render" to linkedMapOf("entryPoint" to "Flock.renderDetailed", "model" to MODEL,
                    "mode" to mode.name, "perturb" to perturb, "oversample" to OVERSAMPLE, "sourceSamples" to "Probe.rawSamples"),
                "output" to linkedMapOf("sampleRate" to raw.sampleRate, "channels" to 1, "format" to "PCM_16", "loop" to false, "level" to level),
                "source" to source, "baselineSource" to baselineSource, "motionMetrics" to afterMetrics,
            ))
            original["label"] = label
            original["description"] = "FLOW changes the flock's simulation clock as well as its original neighbor response. ${fmtRate(rate)} times the original clock rate."
            original["model"] = MODEL
            original["source"] = source
            original["baselineSource"] = baselineSource
            original["level"] = level
            original["motionMetrics"] = afterMetrics
            original["diagnostics"] = diagnostics(probe.diagnostics)
            original["sha256"] = linkedMapOf("raw" to sha(File(root, original.getValue("rawSrc") as String)),
                "matched" to sha(File(root, original.getValue("src") as String)),
                "recipe" to sha(File(root, original.getValue("recipeSrc") as String)),
                "trace" to sha(File(root, original.getValue("traceSrc") as String)))
            pairs += linkedMapOf(
                "value" to macros.getValue("FLOW"), "rate" to rate, "before" to beforeId, "after" to id,
                "pace" to paces[index], "label" to "FLOW ${fmt(macros.getValue("FLOW").toDouble())} · ${fmtRate(rate)}×",
                "metrics" to linkedMapOf("before" to beforeMetrics, "after" to afterMetrics),
            )
            if (index == 0 || index == 4) endpointAudio += matched.samples
            println("$id model $MODEL, ${fmtRate(rate)}x clock; latter-window pitch speed ${afterMetrics.getValue("rmsPitchSpeedSemitonesPerSecond")}")
        }
        clips += originalCopies
        require(clips.size == 56)
        val newLow = objectMap(objectMap(pairs.first().getValue("metrics")).getValue("after"))
        val newHigh = objectMap(objectMap(pairs.last().getValue("metrics")).getValue("after"))
        val lowSpeed = (newLow.getValue("rmsPitchSpeedSemitonesPerSecond") as Number).toDouble()
        val highSpeed = (newHigh.getValue("rmsPitchSpeedSemitonesPerSecond") as Number).toDouble()
        val lowRate = (newLow.getValue("characteristicMotionHz") as Number).toDouble()
        val highRate = (newHigh.getValue("characteristicMotionHz") as Number).toDouble()
        val endpointDistance = sqrt(endpointAudio[0].indices.sumOf {
            val d = endpointAudio[0][it].toDouble() - endpointAudio[1][it]; d * d
        } / endpointAudio[0].size)
        require(endpointDistance > .01) { "FLOW endpoint audio remains too similar" }
        require(highRate >= lowRate * 2.0) {
            "FLOW high-end characteristic movement rate is less than twice its low end: $lowRate, $highRate"
        }
        val sections = objectList(manifest.getValue("sections"))
        sections.add(0, linkedMapOf("id" to "flow-revision", "title" to "FLOW — slow to fast",
            "description" to "Five revised clock rates, each beside its exact previous recording. The remaining 46 original clips retain their audio, recipes and traces byte for byte.",
            "clipIds" to pairs.flatMap { listOf(it.getValue("after"), it.getValue("before")) }))
        manifest["source"] = source
        manifest["baselineSource"] = baselineSource
        manifest["createdUtc"] = Instant.now().toString()
        manifest["title"] = "FLOCK — FLOW, slow to fast"
        manifest["subtitle"] = "A wider range of movement speeds. The rest of the first audition is preserved."
        manifest["status"] = "FLOW revision; unchanged material retains model-1 provenance."
        manifest["clipCount"] = clips.size
        manifest["clips"] = clips
        manifest["sections"] = sections
        manifest["flowRevision"] = linkedMapOf(
            "title" to "FLOW — slow to fast", "description" to "FLOW now changes the simulation clock from 0.12× to 4×, alongside its existing neighbor response. RIBBON's original default FLOW 0.78 remains exactly 1×. Only these five sweep recordings use model 2; all other first-flight material is preserved.",
            "model" to MODEL, "baselineModel" to 1, "anchorFlow" to Flock.defaults(FlockVoice.RIBBON).getValue("FLOW"),
            "anchorRate" to 1.0, "labels" to paces, "pairs" to pairs,
            "source" to source, "baselineSource" to baselineSource,
            "baselineManifestSrc" to "baseline-manifest.json", "baselineManifestSha256" to sha(baselineManifest),
            "preservedOriginalClipCount" to 46,
            "endpointMatchedRmsDifference" to endpointDistance,
            "endpointLatterWindowPitchSpeedRatio" to highSpeed / lowSpeed,
            "endpointLatterWindowCharacteristicRateRatio" to highRate / lowRate,
            "metricNote" to "Pitch speed and excursion use the latter 25–90% of each finite note; these are trajectory diagnostics, not an audibility guarantee. FLOW also retains its existing neighbor-response changes, so equal rate ratios do not imply equal excursion ratios.",
        )
        manifest["generation"] = linkedMapOf("renderSeconds" to renderNanos / 1e9,
            "newAudioSeconds" to 5 * (clips.first { it["id"] == "sweep-flow-0" }.getValue("duration") as Number).toDouble(),
            "wallSeconds" to (System.nanoTime() - began) / 1e9, "javaVersion" to System.getProperty("java.version"),
            "renderedClipCount" to 5, "copiedClipCount" to 51)
        writeJson(File(root, "manifest.json"), manifest)
        File(root, "README.txt").writeText("""
            FLOCK — FLOW revision, model 2

            Only the five FLOW sweep clips were rendered again. The other 46 original clips
            preserve their WAVs, recipes and traces byte for byte. before-flow-0..4 preserve
            the five original FLOW recordings, also byte for byte. Total: 56 finite clips.
            FLOW now changes simulation speed from 0.12x to 4x, anchored at each voice's
            original default. RIBBON FLOW 0.78 remains exactly 1x. No loops or effects.

            Each clip declares its own source/model. Old recipes and source/ retain model 1;
            revised recipes and source-flow/ retain the new exact render source. The root
            source describes only revised FLOW renders; baselineSource describes the rest.
            baseline-manifest.json preserves the original manifest and its asset hashes.
            matched/ uses loudest-200-ms RMS 0.12, peak capped at 0.90. Original causal pairs
            retain their shared gains. raw/ retains pre-level samples and declared safety gains.

            To rebuild, check out the recorded revision and overlay source-flow/ at its root:
              ./gradlew :synth:generateFlockFlowAudition -PflockBaseline=/path/to/first-flight
            Supply the original immutable 51-clip baseline; never write over that directory.
            Revision for revised FLOW: ${source.getValue("revision")}
            Original baseline revision: ${baselineSource.getValue("revision")}
        """.trimIndent() + "\n")
        // Verify preservation from the original asset hashes, including renamed comparisons.
        for (clip in clips) verifyClip(root, clip)
        for (old in objectList(objectMap(fromJson(Json.parse(baselineManifest.readText()))).getValue("clips"))) {
            if (old["id"] !in flowIds) {
                val current = clips.single { it["id"] == old["id"] }
                require(current["sha256"] == old["sha256"]) { "accepted clip ${old["id"]} changed" }
            }
        }
        zipPacks(root)
        println("wrote 56-clip FLOW revision: 5 revised, 5 preserved previous FLOW, 46 untouched originals under $root")
    }

    private fun motionMetrics(trace: List<Flock.TraceFrame>, duration: Double): Map<String, Any?> {
        val start = duration * .25
        val end = duration * .90
        val sum = DoubleArray(24)
        val squares = DoubleArray(24)
        val minimum = DoubleArray(24) { Double.POSITIVE_INFINITY }
        val maximum = DoubleArray(24) { Double.NEGATIVE_INFINITY }
        var speedSquares = 0.0
        var absoluteSpeed = 0.0
        var weight = 0.0
        for (index in 1 until trace.size) {
            val a = trace[index - 1]
            val b = trace[index]
            val dt = b.seconds - a.seconds
            val overlap = minOf(end, b.seconds) - maxOf(start, a.seconds)
            if (overlap <= 0.0) continue
            require(dt > 0.0)
            for (particle in 0 until 24) {
                val velocity = (b.pitchSemitones[particle] - a.pitchSemitones[particle]) / dt
                val pitch = (a.pitchSemitones[particle] + b.pitchSemitones[particle]) * .5
                speedSquares += velocity * velocity * overlap
                absoluteSpeed += abs(velocity) * overlap
                sum[particle] += pitch * overlap
                squares[particle] += pitch * pitch * overlap
                minimum[particle] = minOf(minimum[particle], a.pitchSemitones[particle], b.pitchSemitones[particle])
                maximum[particle] = maxOf(maximum[particle], a.pitchSemitones[particle], b.pitchSemitones[particle])
            }
            weight += overlap
        }
        require(weight > 0.0)
        val rmsSpeed = sqrt(speedSquares / (weight * 24))
        val centeredDepth = sqrt((0 until 24).map {
            maxOf(0.0, squares[it] / weight - (sum[it] / weight) * (sum[it] / weight))
        }.average())
        return linkedMapOf("windowStartSeconds" to start, "windowEndSeconds" to end,
            "rmsPitchSpeedSemitonesPerSecond" to rmsSpeed,
            "meanAbsPitchSpeedSemitonesPerSecond" to absoluteSpeed / (weight * 24),
            "meanPitchExcursionSemitones" to (0 until 24).map { maximum[it] - minimum[it] }.average(),
            "rmsCenteredPitchExcursionSemitones" to centeredDepth,
            "characteristicMotionHz" to rmsSpeed / (2 * Math.PI * centeredDepth.coerceAtLeast(1e-12)))
    }

    private fun diagnostics(d: Flock.Diagnostics): Map<String, Any?> = linkedMapOf(
        "durationSeconds" to d.durationSeconds, "rawPeak" to d.rawPeak, "rawRms" to d.rawRms,
        "outputPeak" to d.outputPeak, "minFrequencyHz" to d.minFrequencyHz, "maxFrequencyHz" to d.maxFrequencyHz,
        "maxSpeed" to d.maxSpeed, "neighborMean" to d.neighborMean, "perturbationSeconds" to d.perturbationSeconds,
        "directNeighborsAtImpulse" to d.directNeighborsAtImpulse, "motionRate" to d.motionRate,
        "perturbationNeighbors" to d.perturbationNeighbors.map { linkedMapOf("seconds" to it.seconds, "receiversOfZero" to it.receiversOfZero) })

    private fun provenance(repo: File): Map<String, Any?> {
        fun git(vararg args: String): String {
            val p = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText().trim()
            require(p.waitFor() == 0) { text }
            return text
        }
        val paths = listOf("synth/src/main/kotlin/com/snipsnap/synth/Flock.kt", "synth/src/main/kotlin/com/snipsnap/synth/FlockPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt", "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/FlockFlowAuditionGenerator.kt", "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt", "json/src/main/kotlin/com/snipsnap/json/Json.kt", "synth/build.gradle.kts")
        return linkedMapOf("revision" to git("rev-parse", "HEAD"), "dirty" to git("status", "--porcelain").isNotEmpty(),
            "repository" to "https://github.com/BertCalm/SnipSnap", "snapshotDirectory" to "source-flow",
            "files" to paths.map { linkedMapOf("path" to it, "sha256" to sha(File(repo, it))) })
    }

    private fun verifyClip(root: File, clip: Map<String, Any?>) {
        val hashes = objectMap(clip.getValue("sha256"))
        for ((hash, path) in listOf("raw" to "rawSrc", "matched" to "src", "recipe" to "recipeSrc", "trace" to "traceSrc")) {
            val file = File(root, clip.getValue(path) as String).canonicalFile
            require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile)
            require(sha(file) == hashes[hash]) { "${clip["id"]}: $hash hash mismatch" }
        }
    }

    private fun readTrace(file: File): List<Flock.TraceFrame> = file.useLines { lines -> lines.drop(1).map { line ->
        val values = line.split(',').map(String::toDouble)
        require(values.size == 49 && values.all(Double::isFinite))
        Flock.TraceFrame(values[0], values.subList(1, 25), values.subList(25, 49))
    }.toList() }

    private fun writeTrace(file: File, trace: List<Flock.TraceFrame>) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine((listOf("seconds") + (0 until 24).map { "pitch_%02d".format(it) } + (0 until 24).map { "energy_%02d".format(it) }).joinToString(","))
            for (frame in trace) out.appendLine((listOf(frame.seconds) + frame.pitchSemitones + frame.energy).joinToString(",") { "%.9f".format(Locale.ROOT, it) })
        }
    }

    private fun gain(snip: Snip, gain: Double) = Snip(FloatArray(snip.samples.size) { (snip.samples[it] * gain).toFloat() }, snip.channels, snip.sampleRate)
    private fun peak(snip: Snip) = snip.samples.maxOf { abs(it.toDouble()) }
    private fun rms(snip: Snip) = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
    private fun fmt(value: Double) = "%.2f".format(Locale.ROOT, value)
    private fun fmtRate(value: Double) = "%.3f".format(Locale.ROOT, value)
    private fun copy(from: File, to: File) { to.parentFile.mkdirs(); from.copyTo(to, overwrite = true) }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectMap(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun objectList(value: Any?): MutableList<MutableMap<String, Any?>> = value as MutableList<MutableMap<String, Any?>>
    private fun fromJson(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValuesTo(linkedMapOf()) { fromJson(it.value) }
        is JsonValue.Arr -> value.items.mapTo(mutableListOf()) { fromJson(it) }
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported JSON value ${value.javaClass.name}")
    }
    private fun writeJson(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n") }

    private fun zipPacks(root: File) {
        val checksums = mutableListOf<String>()
        for (folder in listOf("matched", "raw")) {
            val archive = File(root, "flock-$folder.zip")
            val files = root.walkTopDown().filter { file ->
                if (!file.isFile) false else {
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    path.substringBefore('/') in listOf(folder, "recipes", "traces", "source", "source-flow") ||
                        path in listOf("manifest.json", "baseline-manifest.json", "README.txt")
                }
            }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
            ZipOutputStream(archive.outputStream().buffered()).use { zip -> for (file in files) {
                val entry = ZipEntry(file.relativeTo(root).invariantSeparatorsPath); entry.time = 0L
                zip.putNextEntry(entry); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            } }
            require(archive.length() < 24L * 1024 * 1024) { "${archive.name} exceeds hosting asset limit" }
            checksums += "${sha(archive)}  ${archive.name}"
        }
        File(root, "flock-archives.sha256").writeText(checksums.joinToString("\n", postfix = "\n"))
    }
}
