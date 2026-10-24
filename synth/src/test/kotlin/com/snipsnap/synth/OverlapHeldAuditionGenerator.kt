package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * An isolated steady-body proof, not a third OVERLAP patch model or a preset bank.
 *
 * The exported loop is the settled simulation period. Eight copies make repetition
 * audible without a browser loop or another synthesis pass at every return. Seam
 * measurements use the actual preceding and following simulation periods instead
 * of comparing the exported period with a copy of itself.
 */
object OverlapHeldAuditionGenerator {
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val MIDI = 48
    private const val VELOCITY = .8f
    private const val PERIOD_SECONDS = 1.5
    private const val SUSTAIN_SECONDS = 2.5
    private const val RELEASE_SECONDS = .25
    private const val REPEATS = 8
    private const val TARGET = .03
    private const val CEILING = .90
    private const val MAX_CAPTURE_SECONDS = 30.0
    private const val MAX_EXPORTED_AUDIO_SECONDS = 260.0

    private data class Rendered(
        val topology: OverlapTopology,
        val macros: Map<String, Float>,
        val loop: OverlapHeldPrototype.LoopResult,
        val held: OverlapHeldPrototype.HeldNoteResult,
        val precedingSeam: Double,
        val followingSeam: Double,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size in 3..4) { "Use --replay prototype-recipe.json output.wav [native|matched]" }
            replay(File(args[1]), File(args[2]), args.getOrNull(3) ?: "native")
            return
        }
        require(args.size <= 1) { "Use [output-directory] or --replay recipe.json output.wav [native|matched]" }
        val repository = repository()
        val output = File(args.firstOrNull() ?: File(repository, "testkit/overlap-held-audition").path).canonicalFile
        require(output != repository && output != File(repository, "synth")) { "Choose a dedicated generated-output directory" }
        output.mkdirs()
        val owned = mutableListOf<File>()
        val source = source(repository)

        val rendered = OverlapTopology.entries.map { topology ->
            val macros = linkedMapOf(
                "KNOT" to if (topology == OverlapTopology.SELF) 0f else 1f,
                "PULSE" to 0f, "LOCK" to 0f, "GLOW" to 0f, "RING" to .6f, "HOLD" to 1f,
            )
            val loop = OverlapHeldPrototype.renderLoopMeasured(topology, macros, MIDI, VELOCITY, PERIOD_SECONDS)
            val held = OverlapHeldPrototype.renderHeldNote(
                topology, macros, MIDI, VELOCITY,
                sustainSeconds = SUSTAIN_SECONDS, releaseSeconds = RELEASE_SECONDS,
                periodSeconds = PERIOD_SECONDS, tailSeconds = null,
            )
            valid("$topology loop", loop.snip, loop.raw)
            valid("$topology held note", held.snip, held.raw)
            require(loop.diagnostics.converged) { "$topology: steady simulation did not converge" }
            val frames = loop.snip.frameCount
            require(frames > 256 && frames == loop.diagnostics.periodFrames)
            require(loop.seamContext.size == 3 * frames && loop.seamContext.all(Float::isFinite))
            require(loop.seamContext.copyOfRange(frames, 2 * frames).contentEquals(loop.snip.samples)) {
                "$topology: seam context does not contain the exported native loop"
            }
            val precedingSeam = Keys.seamError(loop.seamContext.copyOf(2 * frames), frames)
            val followingSeam = Keys.seamError(loop.seamContext.copyOfRange(frames, 3 * frames), frames)
            require(precedingSeam.isFinite() && precedingSeam < Keys.MAX_SEAM_ERROR &&
                followingSeam.isFinite() && followingSeam < Keys.MAX_SEAM_ERROR) {
                "$topology: real-neighbour seam exceeds ${Keys.MAX_SEAM_ERROR}: $precedingSeam / $followingSeam"
            }
            require(held.noteOffFrame in 1 until held.snip.frameCount &&
                held.driveStopsFrame in held.noteOffFrame until held.snip.frameCount)
            require(held.internalRaw.isNotEmpty() && held.internalRaw.all(Float::isFinite))
            require(!held.diagnostics.tailWasExplicit) { "The audition must retain the prototype's full default tail" }
            println("OVERLAP held $topology: ${frames.toDouble() / RATE}s loop, ${held.snip.durationSeconds}s note-off capture")
            Rendered(topology, macros, loop, held, precedingSeam, followingSeam)
        }

        // A common only-down native scalar protects the entire collection without
        // silently changing the relative output levels of the four bodies.
        val nativePeak = rendered.maxOf { maxOf(it.loop.snip.peak(), it.held.snip.peak()).toDouble() }
        val nativeGain = minOf(1.0, CEILING / nativePeak)
        val pairGains = rendered.associate { item ->
            // Meter the actual eight-return preview. A single period does not
            // include the same filter startup and across-wrap meter windows.
            val loopNative = scaled(Snip(repeat(item.loop.snip.samples, REPEATS), 1, RATE), nativeGain)
            val heldNative = scaled(item.held.snip, nativeGain)
            val pairLevel = maxOf(loudness(loopNative.samples), loudness(heldNative.samples))
            val pairPeak = maxOf(loopNative.peak(), heldNative.peak()).toDouble()
            require(pairLevel >= 1e-5) { "${item.topology}: pair is too quiet for a meaningful audition" }
            item.topology.name to minOf(TARGET / pairLevel, CEILING / pairPeak)
        }
        val clips = mutableListOf<Map<String, Any?>>()
        val rows = mutableListOf<Map<String, Any?>>()
        val runtime = mutableListOf<Map<String, Any?>>()
        var exportedAudioSeconds = 0.0

        fun writeWav(path: String, snip: Snip): File {
            valid(path, snip)
            require(snip.peak() <= CEILING + 1e-6) { "$path: export exceeds the stated ceiling" }
            exportedAudioSeconds += snip.frameCount.toDouble() / RATE
            require(exportedAudioSeconds <= MAX_EXPORTED_AUDIO_SECONDS) { "Audition exceeded the total audio budget" }
            return File(output, path).also {
                WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24)
                owned += it
            }
        }
        fun writeJson(path: String, value: Any?): File = File(output, path).also {
            it.parentFile.mkdirs(); it.writeText(Json.write(json(value)) + "\n"); owned += it
        }

        for (item in rendered) {
            val name = item.topology.name
            val slug = name.lowercase(Locale.ROOT)
            val pairGain = pairGains.getValue(name)
            val loopPath = "assets/loops/$slug-body-native.wav"
            val loopFile = writeWav(loopPath, scaled(item.loop.snip, nativeGain))
            val diagnosticPath = "assets/diagnostics/$slug.json"
            writeJson(diagnosticPath, linkedMapOf(
                "prototype" to "OverlapHeldPrototype", "topology" to name,
                "timingsExcluded" to "Runtime timing is in metrics-runtime.json, outside the deterministic archive.",
                "loop" to diagnostics(item.loop.diagnostics), "heldNote" to diagnostics(item.held.diagnostics),
                "actualNeighbourSeams" to linkedMapOf("preceding" to item.precedingSeam,
                    "following" to item.followingSeam, "maximumAllowed" to Keys.MAX_SEAM_ERROR),
                "seamContextMeaning" to "Three native-level periods from the advancing simulation: actual preceding, exported kept, and actual following. The kept period starts at periodFrames.",
                "source" to source,
            ))
            runtime += linkedMapOf("topology" to name, "loop" to timings(item.loop.diagnostics),
                "heldNote" to timings(item.held.diagnostics))

            for (kind in listOf("loop", "release")) {
                val isLoop = kind == "loop"
                val id = "$slug-$kind"
                val original = if (isLoop) Snip(repeat(item.loop.snip.samples, REPEATS), 1, RATE) else item.held.snip
                val raw = if (isLoop) repeat(item.loop.raw, REPEATS) else item.held.raw
                val native = scaled(original, nativeGain)
                val matched = scaled(native, pairGain)
                val nativePath = "assets/audio/$id-native.wav"
                val matchedPath = "assets/audio/$id-matched.wav"
                val recipePath = "assets/recipes/$id.json"
                val nativeFile = writeWav(nativePath, native)
                val matchedFile = writeWav(matchedPath, matched)
                val hashes = linkedMapOf("native" to sha(nativeFile), "matched" to sha(matchedFile),
                    "loopBody" to sha(loopFile))
                val metrics = linkedMapOf<String, Any?>(
                    "frames" to native.frameCount, "raw" to metrics(raw),
                    "native" to metrics(native.samples), "matched" to metrics(matched.samples),
                    "nativeCollectionGain" to nativeGain, "matchedPairGain" to pairGain,
                    "pairTargetLoudest200msRms" to TARGET,
                    "periodFrames" to item.loop.diagnostics.periodFrames,
                    "actualPeriodSeconds" to item.loop.diagnostics.periodSeconds,
                    "repeats" to if (isLoop) REPEATS else 0,
                    "precedingSeam" to if (isLoop) item.precedingSeam else null,
                    "followingSeam" to if (isLoop) item.followingSeam else null,
                    "noteOffFrame" to if (isLoop) null else item.held.noteOffFrame,
                    "driveStopsFrame" to if (isLoop) null else item.held.driveStopsFrame,
                    "tailRmsRatio" to if (isLoop) null else item.held.diagnostics.tailRmsRatio,
                )
                writeJson(recipePath, linkedMapOf(
                    "overlapHeldPrototypeRecipe" to 1,
                    "status" to "Isolated experimental recipe. Unsupported by ordinary SnipSnap PadRecipe import; not an OverlapPatch model or a factory preset.",
                    "prototype" to "OverlapHeldPrototype", "unchangedFiniteEngineModel" to Overlap.MODEL_VERSION,
                    "entryPoint" to if (isLoop) "OverlapHeldPrototype.renderLoopMeasured" else "OverlapHeldPrototype.renderHeldNote",
                    "selectedOutput" to if (isLoop) "loop-preview" else "held-note",
                    "topology" to name,
                    "parameters" to linkedMapOf("macros" to item.macros, "midi" to MIDI, "velocity" to VELOCITY,
                        "periodSeconds" to PERIOD_SECONDS, "repeatCount" to if (isLoop) REPEATS else 1,
                        "sustainSeconds" to SUSTAIN_SECONDS, "releaseSeconds" to RELEASE_SECONDS, "tailSeconds" to null),
                    "referenceNote" to "C3 (MIDI 48) is a geometry reference, not a guarantee of the perceived root.",
                    "assembly" to if (isLoop) "Eight exact copies of one settled simulation period, without crossfade or browser looping. Loop-body download contains one period without an end fade."
                        else "One advancing driven simulation. Note-off withdraws the drive over the requested release; the subsequent default tail is retained without an imposed end fade.",
                    "levels" to linkedMapOf("nativeCollectionGain" to nativeGain, "matchedPairGain" to pairGain,
                        "matchedPairTarget" to TARGET, "peakCeiling" to CEILING),
                    "outputs" to linkedMapOf("native" to nativePath, "matched" to matchedPath,
                        "loopBody" to loopPath, "diagnostics" to diagnosticPath, "sha256" to hashes),
                    "metrics" to metrics, "source" to source,
                ))
                val row = linkedMapOf<String, Any?>("id" to id, "topology" to name, "kind" to kind,
                    "seconds" to native.frameCount.toDouble() / RATE, "nativePeak" to native.peak(),
                    "nativeRms" to rms(native.samples), "nativeLoudest200msRms" to loudness(native.samples),
                    "matchedPeak" to matched.peak(), "matchedRms" to rms(matched.samples),
                    "matchedLoudest200msRms" to loudness(matched.samples),
                    "nativeCollectionGain" to nativeGain, "matchedPairGain" to pairGain,
                    "precedingSeam" to if (isLoop) item.precedingSeam else null,
                    "followingSeam" to if (isLoop) item.followingSeam else null,
                    "tailRmsRatio" to if (isLoop) null else item.held.diagnostics.tailRmsRatio,
                    "nativeSha256" to hashes.getValue("native"), "matchedSha256" to hashes.getValue("matched"),
                    "loopBodySha256" to hashes.getValue("loopBody"))
                rows += row
                clips += linkedMapOf<String, Any?>(
                    "id" to id, "title" to "$name / ${if (isLoop) "eight returns" else "note off"}",
                    "description" to if (isLoop) "One settled $name body repeated eight times. Judge the sustained colour, the audible return and the difference from the other bodies."
                        else "$name driven for 2.5 seconds, then drive withdrawn over 0.25 seconds. The network continues through its default decay; judge how the body lets go.",
                    "group" to if (isLoop) "loops" else "release", "topology" to name,
                    "note" to "C3 reference", "midi" to MIDI, "native" to nativePath, "matched" to matchedPath,
                    "loop" to loopPath, "recipe" to recipePath, "diagnostics" to diagnosticPath,
                    "seconds" to native.frameCount.toDouble() / RATE, "metrics" to metrics,
                    "hashes" to hashes, "prototypeRecipe" to true,
                )
            }
        }
        require(clips.size == 8 && rows.size == 8)
        val levelPolicy = linkedMapOf<String, Any?>(
            "native" to "Prototype rendering with one common only-down safety gain across all eight previews and four loop-body downloads. No per-clip native leveling is added by this generator.",
            "matched" to "One fixed gain shared by each topology's repeated-body and note-off pair. The louder house loudest-200-ms RMS targets 0.03 with a shared 0.90 peak ceiling. Other topology pairs can use different gains.",
            "nativeCollectionGain" to nativeGain, "matchedPairGains" to pairGains,
            "targetLoudest200msRms" to TARGET, "peakCeiling" to CEILING,
            "meter" to "SnipSnap Loudness.of: loudest 200-ms RMS after its gentle low cut.",
        )
        writeJson("assets/metrics.json", linkedMapOf("prototype" to "OverlapHeldPrototype", "clips" to rows,
            "levelPolicy" to levelPolicy, "source" to source))
        File(output, "assets/metrics.csv").also { it.writeText(csv(rows)); owned += it }
        writeJson("assets/manifest.json", linkedMapOf(
            "title" to "OVERLAP", "subtitle" to "Isolated steady held proof", "prototypeVersion" to 1,
            "engineModelUnchanged" to Overlap.MODEL_VERSION, "baseline" to "../index.html",
            "sampleRate" to RATE, "channels" to 1, "format" to "PCM_24",
            "bundle" to "assets/overlap-held-proof.zip", "levelPolicy" to levelPolicy,
            "notes" to listOf(
                "This is a separate steady-state proof. The existing model-two instrument, musical bank and old auditions remain unchanged.",
                "Full KNOT for TREFOIL, MIRROR and TORUS; SELF has KNOT 0. PULSE, LOCK and GLOW are 0; RING is 0.6; HOLD is 1.",
                "C3/MIDI 48 is the requested geometry reference. Perceived pitch can belong to another resonant family.",
                "Four previews repeat real settled loop bodies eight times; four examples withdraw drive after a 2.5-second hold. No loop proof is claimed for the finite note-off captures.",
                "Seam measurements use actual preceding and following advancing-simulation periods, never an exported loop copied beside itself.",
                "No crossfade or artificial end fade is applied by this generator. Prototype recipes are unsupported by ordinary SnipSnap PadRecipe import.",
            ), "source" to source, "clips" to clips,
        ))
        File(output, "assets/README.txt").also { file ->
            file.writeText("""
                OVERLAP / isolated steady held proof 1
                Existing finite engine model: ${Overlap.MODEL_VERSION}, unchanged.
                Eight dry mono 44.1-kHz PCM24 previews and four native loop-body WAVs.

                The characteristic gesture is steady excitation -> connected body -> real settled period -> drive withdrawal and decay.
                Four bodies: SELF KNOT 0, TREFOIL/MIRROR/TORUS KNOT 1.
                Common controls: PULSE 0, LOCK 0, GLOW 0, RING 0.6, HOLD 1, MIDI 48, velocity 0.8.
                C3 is a geometry reference, not a promise of the strongest perceived root.
                Loop previews replay the settled native body eight times. The body WAV contains exactly one period.
                Note-off previews use 2.5 seconds of excitation, 0.25-second drive withdrawal, and the complete default prototype tail.
                No added effects, loop crossfades, browser synthesis or generator end fades.
                Native collection gain: $nativeGain. The same fixed matched gain is used for each topology's two previews.
                Matching target: house loudest-200-ms RMS 0.03, peak ceiling 0.90. Read assets/manifest.json for every scalar.
                Seam checks use actual preceding/kept/following simulation periods. The tests do not compare a copied loop with itself.

                Recipes are experimental OverlapHeldPrototype instructions, not ordinary PadRecipe imports or a new patch model.
                Replay one with OverlapHeldAuditionGenerator --replay recipe.json output.wav native (or matched).
                Source and generator hashes, every WAV hash, complete controls and diagnostics are saved alongside the previews.
                Runtime timings are in metrics-runtime.json outside this deterministic download archive.

                Why it is built this way
                - Disabling PULSE and GLOW exposes the sustained routing body before testing moving gestures.
                - Eight audible returns make a short repeating body easy to judge; one-period downloads preserve the actual loop.
                - Real neighbouring simulation periods give the seam test independent context.
                - Pair-shared matching preserves the level relationship between holding the body and letting it decay.
                - The unforced tail shows the network's release instead of hiding it behind an end fade.
            """.trimIndent() + "\n")
            owned += file
        }
        val template = File(repository, "prototype/overlap-held-audition")
        for (name in listOf("index.html", "app.js", "styles.css")) {
            val file = File(template, name)
            require(file.isFile) { "Missing held audition template: $name" }
            owned += File(output, name).also { file.copyTo(it, overwrite = true) }
        }
        File(output, "assets/SHA256SUMS.txt").also { sums ->
            sums.writeText(owned.sortedBy { it.relativeTo(output).invariantSeparatorsPath }.joinToString("\n", postfix = "\n") {
                "${sha(it)}  ${it.relativeTo(output).invariantSeparatorsPath}"
            })
            owned += sums
        }
        // Millisecond timings vary with JIT and host scheduling. Keeping them out
        // of the archive makes repeat generation byte-stable for the same source.
        File(output, "metrics-runtime.json").writeText(Json.write(json(linkedMapOf(
            "prototype" to "OverlapHeldPrototype", "source" to source, "timings" to runtime,
        ))) + "\n")
        ZipOutputStream(File(output, "assets/overlap-held-proof.zip").outputStream()).use { zip ->
            for (file in owned.sortedBy { it.relativeTo(output).invariantSeparatorsPath }) {
                zip.putNextEntry(ZipEntry(file.relativeTo(output).invariantSeparatorsPath).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        println("OVERLAP held proof: ${output.path}; $exportedAudioSeconds seconds of exported WAV audio")
    }

    private fun replay(recipeFile: File, output: File, mode: String) {
        require(mode == "native" || mode == "matched")
        val recipe = Json.parse(recipeFile.readText()).obj()
        require(recipe.getValue("overlapHeldPrototypeRecipe").int() == 1)
        val parameters = recipe.getValue("parameters").obj()
        val macros = parameters.getValue("macros").obj().mapValues { it.value.num().toFloat() }
        val topology = OverlapTopology.valueOf(recipe.getValue("topology").str())
        val midi = parameters.getValue("midi").int()
        val velocity = parameters.getValue("velocity").num().toFloat()
        val period = parameters.getValue("periodSeconds").num()
        val original = when (recipe.getValue("selectedOutput").str()) {
            "loop-preview" -> {
                val loop = OverlapHeldPrototype.renderLoopMeasured(topology, macros, midi, velocity, period)
                val count = parameters.getValue("repeatCount").int().also { require(it in 1..REPEATS) }
                Snip(repeat(loop.snip.samples, count), 1, RATE)
            }
            "held-note" -> OverlapHeldPrototype.renderHeldNote(topology, macros, midi, velocity,
                sustainSeconds = parameters.getValue("sustainSeconds").num(),
                releaseSeconds = parameters.getValue("releaseSeconds").num(), periodSeconds = period,
                tailSeconds = parameters["tailSeconds"]?.takeUnless { it == JsonValue.Null }?.num()).snip
            else -> error("Unsupported held prototype output")
        }
        val levels = recipe.getValue("levels").obj()
        val native = scaled(original, levels.getValue("nativeCollectionGain").num())
        val selected = if (mode == "native") native else scaled(native, levels.getValue("matchedPairGain").num())
        valid("prototype recipe replay", selected)
        WavWriter.write(output, selected, WavWriter.BitDepth.PCM_24)
        val expected = recipe.getValue("outputs").obj().getValue("sha256").obj().getValue(mode).str()
        check(sha(output) == expected) { "Replay differs from the recipe WAV; check the recorded source revision and hashes" }
        println("Exact $mode prototype replay: ${output.path}")
    }

    private fun valid(label: String, snip: Snip, raw: FloatArray? = null) {
        require(snip.channels == 1 && snip.sampleRate == RATE && snip.frameCount > 256)
        require(snip.frameCount.toDouble() / RATE <= MAX_CAPTURE_SECONDS) { "$label: capture exceeds budget" }
        require(snip.samples.all(Float::isFinite) && snip.peak() in 1e-5f..1.001f && rms(snip.samples) >= 1e-6) {
            "$label: silent, non-finite or unbounded native audio"
        }
        if (raw != null) require(raw.size == snip.frameCount && raw.all(Float::isFinite) &&
            peak(raw) in 1e-7..4.0 && rms(raw) >= 1e-8) { "$label: invalid or silent unlevelled audio" }
    }
    private fun repeat(samples: FloatArray, count: Int): FloatArray = FloatArray(samples.size * count) { samples[it % samples.size] }
    private fun scaled(snip: Snip, gain: Double) = Snip(FloatArray(snip.samples.size) { (snip.samples[it] * gain).toFloat() }, 1, RATE)
    private fun peak(samples: FloatArray): Double = samples.maxOf { abs(it).toDouble() }
    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun loudness(samples: FloatArray): Double = Loudness.of(samples, 1, RATE).toDouble()
    private fun metrics(samples: FloatArray): Map<String, Any> = linkedMapOf(
        "peak" to peak(samples), "rms" to rms(samples),
        "dc" to samples.sumOf { it.toDouble() } / samples.size, "loudest200msRms" to loudness(samples),
    )
    private fun diagnostics(d: OverlapHeldPrototype.Diagnostics): Map<String, Any?> = linkedMapOf(
        "strategy" to d.strategy, "periodFrames" to d.periodFrames, "periodSeconds" to d.periodSeconds,
        "requestedPeriodSeconds" to d.requestedPeriodSeconds, "referenceHz" to d.referenceHz,
        "sourceRootHz" to d.sourceRootHz, "attempts" to d.attempts, "maxAttempts" to d.maxAttempts,
        "converged" to d.converged, "stateError" to d.stateError, "audioError" to d.audioError,
        "seam" to d.seam, "nextSeam" to d.nextSeam, "rawPeak" to d.rawPeak, "rawRms" to d.rawRms,
        "maxBufferAbs" to d.maxBufferAbs, "maxLoopGain" to d.maxLoopGain, "internalFrames" to d.internalFrames,
        "tailRmsRatio" to d.tailRmsRatio, "tailWasExplicit" to d.tailWasExplicit, "delaySamples" to d.delaySamples,
    )
    private fun timings(d: OverlapHeldPrototype.Diagnostics): Map<String, Any> = linkedMapOf(
        "setupMillis" to d.setupMillis, "solveMillis" to d.solveMillis,
        "conditioningMillis" to d.conditioningMillis, "totalMillis" to d.totalMillis,
    )
    private fun repository(): File = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull {
        File(it, "synth/src/main/kotlin/com/snipsnap/synth/Overlap.kt").isFile
    } ?: error("Run from the SnipSnap checkout or a module directory")
    private fun source(repository: File): Map<String, Any?> {
        val paths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/OverlapHeldPrototype.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OverlapV2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Overlap.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OverlapHeldAuditionGenerator.kt",
            "prototype/overlap-held-audition/index.html",
            "prototype/overlap-held-audition/app.js",
            "prototype/overlap-held-audition/styles.css",
        )
        val hashes = paths.associateWith { path -> File(repository, path).also { require(it.isFile) { "Missing source $path" } }.let(::sha) }
        return linkedMapOf("revision" to git(repository, "rev-parse", "HEAD"),
            "treeDirty" to git(repository, "status", "--porcelain").isNotEmpty(),
            "sha256" to digest(hashes.entries.joinToString("\n") { "${it.key} ${it.value}" }.toByteArray()),
            "files" to hashes)
    }
    private fun git(repository: File, vararg args: String): String = ProcessBuilder(listOf("git", "-C", repository.path) + args)
        .redirectErrorStream(true).start().let { process ->
            val text = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "git source provenance failed" }; text
        }
    private fun sha(file: File): String = digest(file.readBytes())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun csv(rows: List<Map<String, Any?>>): String {
        val columns = rows.flatMap { it.keys }.distinct()
        fun cell(value: Any?): String = value?.toString()?.let { if (it.any { c -> c == ',' || c == '"' || c == '\n' }) "\"${it.replace("\"", "\"\"")}\"" else it } ?: ""
        return columns.joinToString(",") + "\n" + rows.joinToString("\n", postfix = "\n") { row -> columns.joinToString(",") { cell(row[it]) } }
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()) { "Non-finite evidence value" }; JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported held audition value ${value::class}")
    }
}
