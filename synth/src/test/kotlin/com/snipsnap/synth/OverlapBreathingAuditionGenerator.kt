package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * An isolated listening comparison of steady and moving physical OVERLAP circuits.
 * The physical solver's unlevelled audio receives one collection-wide safety gain.
 * RMS matching adds one constant per complete clip, never one gain per breath.
 * These recipes deliberately remain outside the saved OverlapPatch/PadRecipe formats.
 */
object OverlapBreathingAuditionGenerator {
    const val ARCHIVE_NAME = "overlap-breathing-proof.zip"
    const val VERSION = 1
    const val RATE = WavWriter.MPC_SAMPLE_RATE
    const val REQUESTED_RMS = .10
    const val CEILING = .90
    private const val VELOCITY = .8f
    private const val MARKER = "overlapBreathingAudition"
    private val sourcePaths = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/OverlapBreathingPrototype.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/OverlapHeldPrototype.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/OverlapV2.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Overlap.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/OverlapBreathingAuditionGenerator.kt",
    )

    private data class Rendered(val mode: OverlapBreathingPrototype.Mode,
        val result: OverlapBreathingPrototype.LoopResult, val repeats: Int, val physical: FloatArray)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size in 3..4) { "Use --replay prototype-recipe.json output.wav [raw|matched]" }
            replay(File(args[1]), File(args[2]), args.getOrNull(3) ?: "raw")
            return
        }
        require(args.size <= 1) { "Use [dedicated-output-directory] or --replay recipe.json output.wav [raw|matched]" }
        val output = File(args.firstOrNull() ?: File(repository(), "testkit/overlap-breathing-audition").path)
        println("OVERLAP breathing proof: ${generate(output).absolutePath}")
    }

    fun generate(output: File): File {
        val repo = repository()
        val destination = output.canonicalFile
        require(!Files.isSymbolicLink(output.absoluteFile.toPath())) { "Output must not be a symbolic link" }
        require(destination.parentFile != null && !repo.toPath().startsWith(destination.toPath()) &&
            !destination.toPath().startsWith(File(repo, "synth").toPath())) { "Choose a dedicated generated-output directory" }
        requireReplaceable(destination)
        // Capture provenance before creating staging files. The staging prefix is ignored.
        val source = source(repo)
        check(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) { "Cannot create output parent" }
        val work = Files.createTempDirectory(destination.parentFile.toPath(), ".overlap-breathing-").toFile()
        try {
            val rendered = OverlapBreathingPrototype.Mode.entries.map { mode ->
                val result = OverlapBreathingPrototype.renderLoopMeasured(OverlapBreathingPrototype.Recipe(mode, VELOCITY))
                validate(result)
                val repeats = if (mode == OverlapBreathingPrototype.Mode.STEADY) 12 else 3
                println("OVERLAP $mode: ${result.plan.periodFrames} frames per settled cycle, $repeats cycles")
                Rendered(mode, result, repeats, repeat(result.raw, repeats))
            }
            require(rendered.map { it.physical.size }.distinct().size == 1) { "Comparisons must have exactly equal duration" }
            val collectionGain = minOf(1.0, CEILING / rendered.maxOf { peak(it.physical) })
            val raw = rendered.associate { it.mode to scaled(it.physical, collectionGain) }
            // One achievable target gives every mode the same complete-cycle RMS,
            // even if one mode's crest factor would otherwise hit the peak ceiling.
            val target = minOf(REQUESTED_RMS, raw.values.minOf { CEILING * rms(it) / peak(it) })
            require(collectionGain.isFinite() && collectionGain in 0.0..1.0 && collectionGain > 0.0 && target > 0.0)
            val gains = raw.mapValues { target / rms(it.value) }
            val cancellation = cancellationEvidence()
            val clips = mutableListOf<Map<String, Any?>>()
            val runtimes = mutableListOf<Map<String, Any?>>()
            for (item in rendered) {
                val slug = item.mode.name.lowercase(Locale.ROOT)
                val rawSamples = raw.getValue(item.mode)
                val matchedGain = gains.getValue(item.mode)
                val matched = scaled(rawSamples, matchedGain)
                val rawPath = "assets/audio/$slug-raw.wav"
                val matchedPath = "assets/audio/$slug-matched.wav"
                val recipePath = "assets/recipes/$slug.json"
                val diagnosticsPath = "assets/diagnostics/$slug.json"
                val rawFile = writeWav(File(work, rawPath), rawSamples)
                val matchedFile = writeWav(File(work, matchedPath), matched)
                val outputs = linkedMapOf("raw" to rawPath, "matched" to matchedPath,
                    "sha256" to linkedMapOf("raw" to sha(rawFile), "matched" to sha(matchedFile)))
                val levels = linkedMapOf("rawCollectionGain" to collectionGain, "matchedClipGain" to matchedGain,
                    "requestedMatchedRms" to REQUESTED_RMS, "commonMatchedRms" to target,
                    "peakCeiling" to CEILING, "meter" to "complete-settled-cycle-rms",
                    "actualMatchedFloatRms" to rms(matched), "matchedRmsDifference" to rms(matched) - target,
                    "gainApplication" to "Physical Float audio -> shared scalar -> Float -> fixed clip scalar -> Float -> PCM24.")
                val d = item.result.diagnostics
                writeJson(File(work, diagnosticsPath), linkedMapOf(
                    "prototype" to "OverlapBreathingPrototype", "mode" to item.mode.name,
                    "plan" to plan(item.result.plan), "recurrence" to diagnostics(d),
                    "physical" to metrics(item.physical), "raw" to metrics(rawSamples), "matched" to metrics(matched),
                    "levelPolicy" to levels, "measuredCircuitBalance" to balance(item.result.trace),
                    "trace" to trace(item.result.trace), "cancellation" to cancellation,
                    "context" to "Three independently evolved solver cycles: preceding, exported kept, following. Full-state recurrence includes cursor-relative delay storage; no copied-loop substitute.",
                    "timingsExcluded" to "metrics-runtime.json is outside the deterministic archive.",
                ))
                val recipe = linkedMapOf<String, Any?>(
                    "overlapBreathingPrototypeRecipe" to VERSION,
                    "status" to "Isolated experimental audition recipe; unsupported by ordinary OverlapPatch or PadRecipe import.",
                    "prototype" to "OverlapBreathingPrototype", "mode" to item.mode.name,
                    "parameters" to plan(item.result.plan) + linkedMapOf("velocity" to VELOCITY, "repeatCount" to item.repeats),
                    "levels" to levels, "physicalFloatSha256" to floatSha(item.physical),
                    "outputs" to outputs, "source" to source,
                )
                val recipeFile = writeJson(File(work, recipePath), recipe)
                clips += linkedMapOf(
                    "id" to slug, "mode" to item.mode.name,
                    "title" to when (item.mode) {
                        OverlapBreathingPrototype.Mode.STEADY -> "Steady approved body"
                        OverlapBreathingPrototype.Mode.INDEPENDENT -> "Independent circuit breaths"
                        OverlapBreathingPrototype.Mode.SYNCHRONIZED -> "Synchronized circuit breaths"
                    },
                    "description" to when (item.mode) {
                        OverlapBreathingPrototype.Mode.STEADY -> "The approved steady circuit without moving gates, at the physical raw scale before house leveling."
                        OverlapBreathingPrototype.Mode.INDEPENDENT -> "Six excitation and absorption paths follow separate 3/4/4/5/3/5 breath counts and fixed starting offsets."
                        OverlapBreathingPrototype.Mode.SYNCHRONIZED -> "All six paths take four aligned breaths over the same moving cycle; their mean rate matches the independent version."
                    },
                    "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                    "diagnostics" to diagnosticsPath, "frames" to rawSamples.size,
                    "seconds" to rawSamples.size.toDouble() / RATE, "repeatCount" to item.repeats,
                    "sourcePeriodFrames" to item.result.plan.sourcePeriodFrames,
                    "periodFrames" to item.result.plan.periodFrames, "levels" to levels,
                    "metrics" to linkedMapOf("physical" to metrics(item.physical), "raw" to metrics(rawSamples), "matched" to metrics(matched)),
                    "measuredCircuitBalance" to balance(item.result.trace),
                    "hashes" to linkedMapOf("raw" to sha(rawFile), "matched" to sha(matchedFile),
                        "recipe" to sha(recipeFile), "diagnostics" to sha(File(work, diagnosticsPath))),
                )
                runtimes += linkedMapOf("mode" to item.mode.name, "setupMillis" to d.setupMillis,
                    "solveMillis" to d.solveMillis, "conditioningMillis" to d.conditioningMillis, "totalMillis" to d.totalMillis)
            }
            writeJson(File(work, "assets/manifest.json"), linkedMapOf(
                MARKER to VERSION, "prototype" to "OverlapBreathingPrototype", "title" to "OVERLAP breathing proof",
                "sampleRate" to RATE, "channels" to 1, "bitsPerSample" to 24,
                "bundle" to "assets/$ARCHIVE_NAME", "source" to source, "clips" to clips,
                "levelPolicy" to linkedMapOf("rawCollectionGain" to collectionGain, "requestedMatchedRms" to REQUESTED_RMS,
                    "commonMatchedRms" to target, "peakCeiling" to CEILING,
                    "raw" to "One collection-wide only-down scalar applied directly to unlevelled physical solver audio.",
                    "matched" to "One static gain per entire clip, measured over complete settled cycles. This matches RMS, not perceived loudness. No per-breath leveling, compression or moving gain.",
                    "commonTargetRule" to "min(0.10, min(0.90 * rawRms / rawPeak across all three clips))"),
                "steadyCompatibility" to "STEADY retains the original approved 1.5-second solver and physical raw shape, covered by the core parity regression. Raw audio omits the approved download's house level scalar; this is not byte-identical to its native WAV. Original steady downloads remain unchanged on the existing page.",
                "notes" to listOf(
                    "TREFOIL, MIDI 48 geometry reference, KNOT 1, RING 0.6, velocity 0.8. No claim that this is the strongest perceived root.",
                    "Moving loops contain four approved source periods. Independent circuit counts are 3/4/4/5/3/5; synchronized circuits all count four. Their mean rate is equal.",
                    "Each moving preview contains three full settled cycles; STEADY contains twelve approved source periods for exactly equal duration.",
                    "Real neighbouring solver cycles establish recurrence and seam evidence. No onset ramp, end fade, crossfade or browser synthesis is added.",
                    "An isolated audition prototype, not a new saved playback mode, factory preset, phone build or tempo-synchronized instrument.",
                    "Measurements are host-JVM evidence. Listening and physical phone/MPC timing are not claimed.",
                ),
            ))
            File(work, "assets/README.txt").writeText(readme(collectionGain, target))
            val payload = work.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(work).invariantSeparatorsPath }.toList()
            File(work, "assets/SHA256SUMS.txt").writeText(payload.joinToString("\n", postfix = "\n") {
                "${sha(it)}  ${it.relativeTo(work).invariantSeparatorsPath}"
            })
            writeArchive(work, File(work, "assets/$ARCHIVE_NAME"))
            // Real clock values never participate in recipes, checksums or ZIP bytes.
            writeJson(File(work, "metrics-runtime.json"), linkedMapOf("prototype" to "OverlapBreathingPrototype", "source" to source,
                "timings" to runtimes, "timingScope" to "Host JVM renders; not physical phone timing."))
            requireReplaceable(destination)
            check(!destination.exists() || destination.deleteRecursively()) { "Cannot replace generated output $destination" }
            check(work.renameTo(destination)) { "Cannot move generated output into $destination" }
            return File(destination, "assets/$ARCHIVE_NAME")
        } finally { work.deleteRecursively() }
    }

    /** Rebuild and hash both gain paths before creating the requested replay file. */
    fun replay(recipeFile: File, output: File, variant: String = "raw"): File {
        require(variant in listOf("raw", "matched")) { "Choose raw or matched" }
        require(!Files.isSymbolicLink(output.absoluteFile.toPath())) { "Replay output must not be a symbolic link" }
        require(!output.exists()) { "Replay output already exists: $output" }
        val obj = Json.parse(recipeFile.readText()).obj()
        require(obj.getValue("overlapBreathingPrototypeRecipe").num() == VERSION.toDouble()) { "Unsupported breathing recipe version" }
        require(obj.getValue("prototype").str() == "OverlapBreathingPrototype")
        val mode = OverlapBreathingPrototype.Mode.valueOf(obj.getValue("mode").str())
        val parameters = obj.getValue("parameters").obj()
        require(parameters.getValue("velocity").num() == VELOCITY.toDouble()) { "Breathing audition velocity is fixed at 0.8" }
        val recipe = OverlapBreathingPrototype.Recipe(mode, VELOCITY)
        val p = OverlapBreathingPrototype.plan(recipe)
        val repeats = if (mode == OverlapBreathingPrototype.Mode.STEADY) 12 else 3
        require(parameters.getValue("repeatCount").num() == repeats.toDouble()) { "Replay must retain the complete comparison duration" }
        require(JsonValue.Obj(parameters - setOf("velocity", "repeatCount")) == json(plan(p))) { "Recipe parameters differ from this prototype's fixed circuit plan" }
        validateSource(repository(), obj.getValue("source"))
        val levels = obj.getValue("levels").obj()
        require(levels.getValue("meter").str() == "complete-settled-cycle-rms")
        require(levels.getValue("requestedMatchedRms").num() == REQUESTED_RMS && levels.getValue("peakCeiling").num() == CEILING)
        val gain = levels.getValue("rawCollectionGain").num().also { require(it.isFinite() && it > 0.0 && it <= 1.0) }
        val matchedGain = levels.getValue("matchedClipGain").num().also { require(it.isFinite() && it > 0.0) }
        val target = levels.getValue("commonMatchedRms").num().also { require(it.isFinite() && it > 0.0 && it <= REQUESTED_RMS) }
        val result = OverlapBreathingPrototype.renderLoopMeasured(recipe)
        validate(result)
        val physical = repeat(result.raw, repeats)
        check(floatSha(physical) == obj.getValue("physicalFloatSha256").str()) { "Physical replay differs from the recorded source" }
        val raw = scaled(physical, gain)
        require(abs(rms(raw) * matchedGain - target) <= maxOf(1e-12, target * 1e-9)) { "Stored matched scalar does not match the complete-cycle RMS target" }
        val matched = scaled(raw, matchedGain)
        val hashes = obj.getValue("outputs").obj().getValue("sha256").obj()
        val bytes = mapOf("raw" to encode(raw), "matched" to encode(matched))
        for ((name, audio) in bytes) check(digest(audio) == hashes.getValue(name).str()) { "$name replay differs from the recipe WAV hash" }
        check(output.parentFile == null || output.parentFile.isDirectory || output.parentFile.mkdirs()) { "Cannot create replay directory" }
        output.writeBytes(bytes.getValue(variant))
        return output
    }

    private fun validate(result: OverlapBreathingPrototype.LoopResult) {
        val d = result.diagnostics
        val frames = result.plan.periodFrames
        require(d.converged && d.maxLoopGain < 1.0 && result.raw.size == frames && result.rawContext.size == 3 * frames)
        require(d.cycleSourceCursor == 0 && d.cycleControlCursor == 0 && d.internalFrames <= d.maxInternalFrames)
        require(result.raw.contentEquals(result.rawContext.copyOfRange(frames, 2 * frames))) { "Context must contain the actual exported physical cycle" }
        require(result.rawContext.all(Float::isFinite) && peak(result.raw) > 1e-10 && rms(result.raw) > 1e-12)
        val before = Keys.seamError(result.rawContext.copyOfRange(0, 2 * frames), frames)
        val after = Keys.seamError(result.rawContext.copyOfRange(frames, 3 * frames), frames)
        require(before.isFinite() && after.isFinite() && before < Keys.MAX_SEAM_ERROR && after < Keys.MAX_SEAM_ERROR) { "Real-neighbour seam failed" }
    }

    private fun plan(p: OverlapBreathingPrototype.Plan): Map<String, Any?> = linkedMapOf(
        "topology" to p.topology.name, "midi" to p.midi, "knot" to p.knot, "ring" to p.ring,
        "referenceHz" to p.referenceHz, "sourceRootHz" to p.sourceRootHz,
        "sourcePeriodFrames" to p.sourcePeriodFrames, "sourcePeriodSeconds" to p.sourcePeriodSeconds,
        "periodFrames" to p.periodFrames, "periodSeconds" to p.periodSeconds,
        "counts" to p.counts, "offsets" to p.offsets, "ratesHz" to p.ratesHz,
        "delaySamples" to p.delaySamples, "controlStride" to p.controlStride,
    )

    private fun diagnostics(d: OverlapBreathingPrototype.Diagnostics): Map<String, Any?> = linkedMapOf(
        "strategy" to d.strategy, "attempts" to d.attempts, "maxAttempts" to d.maxAttempts,
        "converged" to d.converged, "stateError" to d.stateError, "audioError" to d.audioError,
        "precedingSeam" to d.seam, "followingSeam" to d.nextSeam, "rawPeak" to d.rawPeak,
        "rawRms" to d.rawRms, "rawMean" to d.rawMean, "maxBufferAbs" to d.maxBufferAbs,
        "minLoopGain" to d.minLoopGain, "maxLoopGain" to d.maxLoopGain, "internalFrames" to d.internalFrames,
        "stateSize" to d.stateSize, "cycleSourceCursor" to d.cycleSourceCursor,
        "cycleControlCursor" to d.cycleControlCursor, "maxInternalFrames" to d.maxInternalFrames,
        "cancellationChecks" to d.cancellationChecks,
    )

    private fun trace(t: OverlapBreathingPrototype.Trace): Map<String, Any?> = linkedMapOf(
        "windowStartFrames" to t.windowStartFrames, "windowEndFrames" to t.windowEndFrames,
        "gateMean" to t.gateMean, "extraLossMean" to t.extraLossMean, "nodeRms" to t.nodeRms,
        "excitationRms" to t.excitationRms, "nodeEnergyShares" to t.nodeEnergyShares,
    )

    private fun balance(t: OverlapBreathingPrototype.Trace): Map<String, Any?> {
        val windows = t.nodeEnergyShares
        require(windows.isNotEmpty() && windows.all { it.size == 6 && it.all(Double::isFinite) })
        return linkedMapOf("measurement" to "Normalized mean-square damped delay-read powers across the six physical nodes, before pickup or gain. These are not total stored-energy shares or commanded gate values.",
            "windowCount" to windows.size, "nodeMeanShares" to (0 until 6).map { node -> windows.sumOf { it[node] } / windows.size },
            "nodeMinShares" to (0 until 6).map { node -> windows.minOf { it[node] } },
            "nodeMaxShares" to (0 until 6).map { node -> windows.maxOf { it[node] } },
            "largestNodeShareRange" to (0 until 6).maxOf { node -> windows.maxOf { it[node] } - windows.minOf { it[node] } })
    }

    private fun metrics(samples: FloatArray): Map<String, Any?> {
        val window = RATE / 20 // 50 ms windows measure motion; they never control gain.
        val windows = (samples.indices step window).map { from -> rms(samples, from, minOf(from + window, samples.size)) }
        val mean = windows.average()
        val minimum = windows.minOrNull() ?: 0.0
        val maximum = windows.maxOrNull() ?: 0.0
        return linkedMapOf("peak" to peak(samples), "rms" to rms(samples), "dc" to samples.sumOf { it.toDouble() } / samples.size,
            "modulationWindowFrames" to window, "windowRmsMin" to minimum, "windowRmsMax" to maximum,
            "windowRmsDepth" to if (maximum + minimum > 0.0) (maximum - minimum) / (maximum + minimum) else 0.0,
            "windowRmsCoefficientOfVariation" to if (mean > 0.0) sqrt(windows.sumOf { (it - mean) * (it - mean) } / windows.size) / mean else 0.0)
    }

    private fun cancellationEvidence(): Map<String, Any?> {
        fun probe(stopAt: Int): Int {
            var calls = 0
            try {
                OverlapBreathingPrototype.renderLoopMeasured(OverlapBreathingPrototype.Recipe(OverlapBreathingPrototype.Mode.INDEPENDENT)) {
                    ++calls >= stopAt
                }
            } catch (_: CancellationException) { return calls }
            error("Requested prototype cancellation was ignored")
        }
        return linkedMapOf("immediateCancellationObserved" to (probe(1) == 1),
            "cooperativeCancellationCallbackCount" to probe(3), "requestedCallbackCount" to 3,
            "probeScope" to "The third callback interrupts periodic noise-source preparation; complete-render callback counts are reported in recurrence diagnostics.",
            "conditioningLimit" to "The shared decimator has no internal cancellation callback. Checkpoints surround bounded conditioning, and callbacks interrupt setup and physical simulation.")
    }

    private fun requireReplaceable(destination: File) {
        require(!destination.exists() || destination.isDirectory) { "Output is not a directory" }
        if (!destination.exists() || destination.listFiles()?.isEmpty() == true) return
        val owned = runCatching { Json.parse(File(destination, "assets/manifest.json").readText()).obj()[MARKER]?.num() == VERSION.toDouble() }.getOrDefault(false)
        require(owned) { "Refusing to replace nonempty output without an OVERLAP breathing-audition manifest: $destination" }
    }

    internal fun writeArchive(directory: File, archive: File) {
        val files = directory.walkTopDown().filter { it.isFile && it.canonicalFile != archive.canonicalFile && it.name != "metrics-runtime.json" }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.toList()
        archive.parentFile.mkdirs()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.setLevel(9)
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writeWav(file: File, samples: FloatArray): File {
        file.parentFile.mkdirs()
        file.writeBytes(encode(samples))
        val decoded = WavReader.read(file)
        require(decoded.sampleRate == RATE && decoded.channels == 1 && decoded.frameCount == samples.size)
        require(decoded.samples.all(Float::isFinite) && decoded.peak() <= CEILING + 1e-6)
        return file
    }

    private fun encode(samples: FloatArray): ByteArray {
        require(samples.isNotEmpty() && samples.all(Float::isFinite) && peak(samples) <= CEILING + 1e-6)
        return ByteArrayOutputStream().also { WavWriter.write(it, Snip(samples, 1, RATE), WavWriter.BitDepth.PCM_24) }.toByteArray()
    }

    private fun repeat(samples: FloatArray, count: Int) = FloatArray(samples.size * count) { samples[it % samples.size] }
    private fun scaled(samples: FloatArray, gain: Double) = FloatArray(samples.size) { (samples[it] * gain).toFloat() }
    private fun peak(samples: FloatArray): Double = samples.maxOf { abs(it.toDouble()) }
    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double = sqrt((from until to).sumOf { samples[it].toDouble() * samples[it] } / (to - from))
    private fun floatSha(samples: FloatArray): String = digest(ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putFloat(it) } }.array())
    private fun sha(file: File): String = digest(file.readBytes())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun repository(): File = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull {
        File(it, sourcePaths.first()).isFile
    } ?: error("Run from the SnipSnap checkout or a module directory")

    private fun source(repo: File): Map<String, Any?> {
        val hashes = sourcePaths.associateWith { sha(File(repo, it).also { f -> require(f.isFile) { "Missing source $it" } }) }
        return linkedMapOf("revision" to git(repo, "rev-parse", "HEAD"), "treeDirty" to git(repo, "status", "--porcelain").isNotEmpty(),
            "sha256" to digest(hashes.entries.joinToString("\n") { "${it.key} ${it.value}" }.toByteArray()), "files" to hashes)
    }

    private fun validateSource(repo: File, value: JsonValue) {
        val obj = value.obj()
        val hashes = obj.getValue("files").obj()
        require(hashes.keys.toList() == sourcePaths) { "Unknown prototype source inventory" }
        for ((path, expected) in hashes) check(sha(File(repo, path)) == expected.str()) { "Replay source differs: $path" }
        check(digest(hashes.entries.joinToString("\n") { "${it.key} ${it.value.str()}" }.toByteArray()) == obj.getValue("sha256").str()) { "Invalid source fingerprint" }
    }

    private fun git(repo: File, vararg args: String): String = ProcessBuilder(listOf("git", "-C", repo.path) + args)
        .redirectErrorStream(true).start().let { process ->
            val text = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "git provenance failed" }; text
        }

    private fun writeJson(file: File, value: Any?): File {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
        return file
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "Non-finite diagnostic" } })
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported audition evidence ${value::class}")
    }

    private fun readme(gain: Double, target: Double) = """
        OVERLAP breathing proof 1
        Separate experimental audition. Accepted steady/held downloads, finite models and factory presets remain unchanged.
        TREFOIL; reference MIDI 48; KNOT 1; RING 0.6; velocity 0.8.
        Moving cycle: four approved source periods. Independent node breath counts 3/4/4/5/3/5; synchronized counts 4/4/4/4/4/4.
        Each moving clip repeats three settled cycles. STEADY repeats twelve original periods for exactly equal duration.
        Source reference MIDI describes geometry; the strongest perceived pitch can belong to another resonant family.

        RAW: unlevelled physical audio with ONE collection-wide only-down safety scalar, $gain.
        MATCHED: one fixed gain per whole clip to the common complete-cycle RMS $target (requested0.10, peak ceiling0.90).
        RMS matching is not a claim of equal perceived loudness. There is no per-breath/window leveling or compression.
        No onset ramp, end fade, loop crossfade, tempo synchronization or browser synthesis is added.
        STEADY uses the approved original solver; this raw scale omits the accepted download's house level scalar.
        Original steady WAV downloads remain on the prior listening page.

        Read assets/manifest.json, per-mode recipes and diagnostics for full controls, fixed scalars, raw/matched metrics,
        recurrence against independently evolved neighbouring cycles, actual node-read power shares, cancellation and source hashes.
        Standalone recipes are unsupported by ordinary OverlapPatch/PadRecipe import and do not silently upgrade saved sounds.
        Replay: OverlapBreathingAuditionGenerator --replay assets/recipes/independent.json output.wav raw (or matched).
        Replay verifies source hashes, complete physical audio and both PCM24 WAV hashes before writing output.
        metrics-runtime.json records host-JVM timings separately and is excluded from the deterministic ZIP and checksum inventory.
        No listening result, physical phone render time or MPC playback validation is claimed.

        Why it is built this way
        - The steady reference isolates what movement adds while retaining the approved source algorithm.
        - Four whole source periods close both forcing and breathing phases without a seam repair.
        - Equal duration and equal mean breath rates make the two motion strategies comparable.
        - Physical raw gain preserves differences in circuit energy; fixed whole-clip RMS matching offers another listening perspective.
        - Measured node-read power traces distinguish actual circuit balance from commanded control movement.
    """.trimIndent() + "\n"
}
