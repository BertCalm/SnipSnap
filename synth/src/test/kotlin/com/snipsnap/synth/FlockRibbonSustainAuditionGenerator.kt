package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.SmplChunk
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

/** Offline sustain evidence. No factory patch, kit or accepted finite audition is rewritten. */
object FlockRibbonSustainAuditionGenerator {
    private const val EXPERIMENT = "ribbon-sustain-v1"
    private const val HELD = 30.0
    private const val RELEASE = 1.5
    private const val LOOP = 6.0
    private const val CROSSFADE = .5
    private const val MIDI = 60
    private const val VELOCITY = 1f
    private const val SEED = 42001L
    private const val OVERSAMPLE = 4
    private const val TARGET = .12
    private const val CEILING = .90
    private val assetDirectory = "audition/flock-ribbon-sustain"

    private data class Setting(val id: String, val label: String, val flow: Float, val description: String)
    private data class Rendered(val setting: Setting, val macros: Map<String, Float>, val probe: FlockRibbonSustain.Probe)
    private val settings = listOf(
        Setting("flow-low", "Low FLOW", 0f,
            "A slow clock. The short capture may repeat the flock's initial drift; compare it with the longer continuous path."),
        Setting("flow-default", "Default FLOW", .78f,
            "The accepted RIBBON default keeps its original simulation speed. Does the repeated movement stay interesting?"),
        Setting("flow-high", "High FLOW", 1f,
            "A fast clock. Listen for a recurring turn or a change in texture as the same six seconds return."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the destination directory" }
        val root = File(args.firstOrNull() ?: "../testkit/flock-ribbon-sustain").canonicalFile
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, ".git").exists() } ?: error("run within the SnipSnap repository")
        val accepted = listOf("testkit/flock-audition", "testkit/flock-flow-audition")
            .map { File(repo, it).canonicalFile.toPath() }
        require(accepted.none { root.toPath().startsWith(it) || it.startsWith(root.toPath()) }) {
            "experimental output must not overlap either accepted finite audition"
        }
        require(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val began = System.nanoTime()
        val source = provenance(repo)
        for (entry in source.getValue("files") as List<*>) {
            val item = entry as Map<*, *>
            val path = item["path"] as String
            copy(File(repo, path), File(root, "source/$path"))
        }
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream("$assetDirectory/$name")) {
                "missing tracked sustain frontend $name"
            }.use { it.readBytes() }
            File(root, name).writeBytes(bytes)
        }
        var renderNanos = 0L
        val rendered = settings.map { setting ->
            val macros = Flock.defaults(FlockVoice.RIBBON) + ("FLOW" to setting.flow)
            val start = System.nanoTime()
            val probe = FlockRibbonSustain.render(macros, MIDI, VELOCITY, SEED, HELD, LOOP, CROSSFADE, RELEASE, OVERSAMPLE)
            renderNanos += System.nanoTime() - start
            require(probe.plan.sampleRate == 44_100 && probe.plan.totalFrames == ((HELD + RELEASE) * 44_100).toInt())
            require(probe.rawReference.size == probe.plan.totalFrames && probe.rawLooped.size == probe.plan.totalFrames)
            require(listOf(probe.rawReference, probe.rawLooped, probe.rawLoopBody).all { it.all(Float::isFinite) })
            require(probe.rawReference.first() == 0f && probe.rawReference.last() == 0f &&
                probe.rawLooped.first() == 0f && probe.rawLooped.last() == 0f)
            require(probe.plan.wrapFrames.count { it < probe.plan.releaseStartFrame } >= 3)
            println("rendered ${setting.id}: ${probe.plan.totalFrames} frames, ${probe.plan.wrapFrames.size} returns, FLOW clock ${Flock.motionRate(FlockVoice.RIBBON, setting.flow)}x")
            Rendered(setting, macros, probe)
        }
        val largestRawPeak = rendered.maxOf { maxOf(peak(it.probe.rawReference), peak(it.probe.rawLooped), peak(it.probe.rawLoopBody)) }
        // One gain for the whole raw collection. Never normalize one FLOW endpoint independently.
        val rawGain = minOf(1.0, CEILING / largestRawPeak.coerceAtLeast(1e-12))
        val clips = mutableListOf<Map<String, Any?>>()
        val pairs = mutableListOf<Map<String, Any?>>()
        for ((setting, macros, probe) in rendered) {
            val p = probe.plan
            val pairPeak = maxOf(peak(probe.rawReference), peak(probe.rawLooped))
            val pairLoudness = maxOf(loudness(probe.rawReference), loudness(probe.rawLooped))
            val matchedGain = minOf(TARGET / pairLoudness.coerceAtLeast(1e-12), CEILING / pairPeak.coerceAtLeast(1e-12))
            val traceSrc = "traces/${setting.id}-continuous-source.csv"
            writeTrace(File(root, traceSrc), probe.source.trace)
            val samplerSrc = "sampler/${setting.id}-attack-loop.wav"
            val bodySrc = "sampler/${setting.id}-body.wav"
            val rawAttackLoop = probe.rawLooped.copyOfRange(0, p.loopEndExclusive)
            WavWriter.write(File(root, samplerSrc), snip(scaled(rawAttackLoop, rawGain)), WavWriter.BitDepth.PCM_16,
                smpl = SmplChunk(MIDI, SmplChunk.Loop(p.loopStartFrame.toLong(), p.loopEndExclusive.toLong())))
            WavWriter.write(File(root, bodySrc), snip(scaled(probe.rawLoopBody, rawGain)), WavWriter.BitDepth.PCM_16,
                smpl = SmplChunk(MIDI, SmplChunk.Loop(0, p.loopFrames.toLong())))
            val timing = linkedMapOf<String, Any?>(
                "captureStartSeconds" to p.loopStartFrame.toDouble() / p.sampleRate,
                "captureEndSeconds" to p.loopEndExclusive.toDouble() / p.sampleRate,
                "wrapSeconds" to p.wrapFrames.map { it.toDouble() / p.sampleRate },
            )
            for ((kind, samples) in listOf("continuous" to probe.rawReference, "looped" to probe.rawLooped)) {
                val id = "${setting.id}-$kind"
                val rawSrc = "raw/$id.wav"
                val matchedSrc = "matched/$id.wav"
                val recipeSrc = "recipes/$id.json"
                val native = scaled(samples, rawGain)
                val matched = scaled(samples, matchedGain)
                require(peak(native) <= CEILING + 1e-6 && peak(matched) <= CEILING + 1e-6)
                WavWriter.write(File(root, rawSrc), snip(native), WavWriter.BitDepth.PCM_16)
                WavWriter.write(File(root, matchedSrc), snip(matched), WavWriter.BitDepth.PCM_16)
                val level = linkedMapOf<String, Any?>(
                    "rawGain" to rawGain, "matchedGain" to matchedGain,
                    "synthesisPeak" to peak(samples), "synthesisRms" to rms(samples),
                    "rawPeak" to peak(native), "rawRms" to rms(native), "rawLoudest200msRms" to loudness(native),
                    "matchedPeak" to peak(matched), "matchedRms" to rms(matched), "matchedLoudest200msRms" to loudness(matched),
                    "matchGroup" to setting.id, "pairLoudest200msRmsBeforeGain" to pairLoudness,
                    "peakLimited" to (matchedGain < TARGET / pairLoudness.coerceAtLeast(1e-12) - 1e-12),
                )
                val outputHashes = linkedMapOf("raw" to sha(File(root, rawSrc)), "matched" to sha(File(root, matchedSrc)),
                    "trace" to sha(File(root, traceSrc)), "sampler" to sha(File(root, samplerSrc)), "body" to sha(File(root, bodySrc)))
                writeJson(File(root, recipeSrc), linkedMapOf(
                    "schemaVersion" to 1, "experiment" to EXPERIMENT,
                    "status" to "Experimental audition recipe; not a loadable app FlockPatch or a factory sustain promise.",
                    "engine" to "FLOCK", "voice" to "RIBBON", "sourceModel" to Flock.MODEL_VERSION,
                    "experimentVersion" to FlockRibbonSustain.EXPERIMENT_VERSION,
                    "entryPoint" to "FlockRibbonSustain.render", "selectedOutput" to kind,
                    "parameters" to linkedMapOf("macros" to macros, "midi" to MIDI, "velocity" to VELOCITY,
                        "seed" to SEED.toString(), "heldSeconds" to HELD, "loopSeconds" to LOOP,
                        "crossfadeSeconds" to CROSSFADE, "releaseSeconds" to RELEASE, "oversample" to OVERSAMPLE),
                    "envelope" to linkedMapOf("attackSeconds" to FlockRibbonSustain.ATTACK_SECONDS,
                        "attackAndReleaseShape" to "smoothstep", "finiteHoldMacroIgnored" to true,
                        "continuousSourceHasFiniteGestureEnvelope" to false),
                    "loopAssembly" to linkedMapOf("plan" to plan(p),
                        "method" to "Equal-power sine/cosine tail blend, normalized by nonnegative whole-blend source correlation; smoothstep blend phase; exact endpoint weights.",
                        "target" to "The 0.5 seconds immediately before the original loop start, preserving natural one-sample wrap context.",
                        "continuation" to "Reference advances the simulation throughout; looped output replays the captured body. Both share identical attack and release envelopes."),
                    "outputs" to linkedMapOf("sampleRate" to p.sampleRate, "channels" to 1, "format" to "PCM_16",
                        "rawSrc" to rawSrc, "matchedSrc" to matchedSrc, "traceSrc" to traceSrc,
                        "traceMeaning" to "The continuous source simulation. Repeated playback is reconstructed with loopAssembly.plan; this trace does not imply new simulation during a repeated body.",
                        "samplerSrc" to samplerSrc, "bodySrc" to bodySrc, "samplerGain" to rawGain,
                        "samplerLoopEndConvention" to "exclusive in recipe; RIFF smpl stores the last included frame",
                        "samplerRelease" to "Attack-plus-loop sample has no release baked in. Expanded comparison WAVs have the documented 1.5-second release.",
                        "level" to level, "sha256" to outputHashes),
                    "diagnostics" to diagnostics(probe.diagnostics), "source" to source,
                ))
                clips += linkedMapOf(
                    "id" to id, "pairId" to setting.id, "kind" to kind,
                    "label" to "${setting.label} / ${if (kind == "continuous") "continuous" else "repeated body"}",
                    "flow" to setting.flow, "motionRate" to Flock.motionRate(FlockVoice.RIBBON, setting.flow),
                    "duration" to p.totalFrames.toDouble() / p.sampleRate, "frames" to p.totalFrames,
                    "src" to matchedSrc, "rawSrc" to rawSrc, "recipeSrc" to recipeSrc, "traceSrc" to traceSrc,
                    "level" to level, "sha256" to (outputHashes + ("recipe" to sha(File(root, recipeSrc)))),
                )
            }
            pairs += linkedMapOf(
                "id" to setting.id, "label" to setting.label, "flow" to setting.flow,
                "description" to setting.description, "motionRate" to Flock.motionRate(FlockVoice.RIBBON, setting.flow),
                "continuousId" to "${setting.id}-continuous", "loopedId" to "${setting.id}-looped",
                "timing" to timing, "plan" to plan(p), "samplerSrc" to samplerSrc, "bodySrc" to bodySrc,
                "samplerGain" to rawGain, "diagnostics" to diagnostics(probe.diagnostics),
                "samplerSha256" to sha(File(root, samplerSrc)), "bodySha256" to sha(File(root, bodySrc)),
            )
        }
        require(clips.size == 6 && pairs.size == 3)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "FLOCK", "experiment" to EXPERIMENT,
            "title" to "RIBBON — can the flock keep moving?", "createdUtc" to Instant.now().toString(),
            "status" to "Experimental sustain listening proof. Accepted finite sounds, patch models, app and factory kit remain unchanged.",
            "clipCount" to clips.size,
            "settings" to linkedMapOf("voice" to "RIBBON", "sourceModel" to Flock.MODEL_VERSION,
                "experimentVersion" to FlockRibbonSustain.EXPERIMENT_VERSION,
                "midi" to MIDI, "velocity" to VELOCITY, "seed" to SEED.toString(),
                "macros" to Flock.defaults(FlockVoice.RIBBON), "heldSeconds" to HELD,
                "releaseSeconds" to RELEASE, "loopSeconds" to LOOP, "crossfadeSeconds" to CROSSFADE,
                "attackSeconds" to FlockRibbonSustain.ATTACK_SECONDS, "sampleRate" to 44_100,
                "channels" to 1, "format" to "PCM_16", "oversample" to OVERSAMPLE),
            "captureNote" to "The body captures source seconds 2–8. Its last 0.5 seconds blend into the 0.5 seconds immediately before the capture. At low FLOW, this short window may contain initialization drift; no convergence or natural periodicity is claimed.",
            "levelPolicy" to linkedMapOf(
                "matched" to "Level matched: one fixed gain shared by each continuous/repeated pair. The louder of its two loudest-200-ms RMS measurements targets 0.12, with a shared 0.90 peak guard. FLOW pairs can use different gains; within each pair no dynamics are altered.",
                "raw" to "Raw: the unlevelled synthesis and offline assembly, with one shared only-down safety gain across all six recordings and the sampler files. No per-clip normalization, compression, effects or browser synthesis.",
                "targetLoudest200msRms" to TARGET, "peakCeiling" to CEILING, "rawGain" to rawGain),
            "clips" to clips, "pairs" to pairs, "source" to source,
            "generation" to linkedMapOf("renderSeconds" to renderNanos / 1e9,
                "wallSecondsBeforeArchives" to (System.nanoTime() - began) / 1e9,
                "javaVersion" to System.getProperty("java.version"), "renderedSourceCount" to 3,
                "expandedComparisonCount" to clips.size),
            "archives" to listOf("flock-ribbon-sustain-matched.zip", "flock-ribbon-sustain-raw.zip"),
        )
        writeJson(File(root, "manifest.json"), manifest)
        File(root, "README.txt").writeText("""
            FLOCK / RIBBON sustain experiment v1

            Six dry mono PCM16 recordings, 44.1 kHz, 31.5 seconds each.
            Three FLOW settings (0, original RIBBON default 0.78, 1), each comparing:
              continuous: 30 held seconds of evolving simulation + 1.5-second release
              looped: same introduction, then a repeated six-second body + same release
            Root C4 / MIDI 60, velocity 1, seed "42001", original companion macros.
            The 45-ms attack and 1.5-second smoothstep release are shared by both paths.
            Sustain overrides finite HOLD duration; HOLD is retained only as source state.

            This is an audition-only continuation and sample-loop construction, outside
            the app patch schema. Accepted finite model-1/model-2 renders are unchanged.
            No app sustain mode, instrument export, phone or MPC hardware acceptance
            is claimed. Sampler WAVs carry root and exclusive loop bounds via RIFF smpl,
            but their attack-plus-loop samples need a sampler envelope for note release.

            The loop captures seconds 2–8. Its final 0.5 seconds blend toward source
            seconds 1.5–2, so its last sample naturally precedes the first loop sample.
            This makes a sample loop; it does not make the simulation periodic or prove
            it has converged. Slow FLOW may replay initialization drift. Listen through
            the returns at seconds 8, 14, 20 and 26, against the continuous reference.
            Diagnostics quantify edges, crossfade energy and captured motion; the final
            decision about audible recurrence and join quality remains a listening check.

            matched/: shared fixed gain within each continuous/looped pair. The greater
            loudest-200-ms RMS targets 0.12; one shared 0.90 peak guard applies to both.
            raw/: same one only-down safety gain across all source states and examples.
            sampler/: attack+loop and isolated body, with that same raw safety gain.
            Neither collection uses compression, effects, pitch changes or browser DSP.
            Each archive includes its WAV collection plus sampler files, recipes,
            continuous-source traces, the full manifest, source snapshots and frontend.
            SHA256SUMS covers the complete output; an individual archive contains only
            one audio level, so paths for the other level are intentionally absent.
            archives.sha256 records the hashes of the two downloadable archives.

            Exact experimental recipes: recipes/*.json. Seed is a signed decimal string.
            Traces show continuous source simulation, not newly simulated repeated audio.
            Replay the recorded source revision with source/ overlaid at the repo root:
              ./gradlew :synth:generateFlockRibbonSustainAudition
            Optional destination: -PflockSustainOutput=/absolute/output/directory
            Serve this folder over HTTP to use the listening page (fetch needs HTTP).
            The page's audio sources are these WAVs. Its repeat switch repeats the whole
            recording, including attack/release; internal six-second returns are baked in.

            Source revision: ${source.getValue("revision")}
            Working tree changed at capture: ${source.getValue("dirty")}
            Every captured source file has an exact SHA-256 in manifest.json.
        """.trimIndent() + "\n")
        verifyClips(root, clips)
        writeChecksums(root)
        zipPacks(root)
        println("wrote ${clips.size} RIBBON sustain recordings, experimental sampler WAVs, exact recipes and two archives under $root")
    }

    private fun plan(p: FlockRibbonSustain.Plan): Map<String, Any?> = linkedMapOf(
        "sampleRate" to p.sampleRate, "attackFrames" to p.attackFrames,
        "loopStartFrame" to p.loopStartFrame, "loopEndExclusive" to p.loopEndExclusive,
        "loopFrames" to p.loopFrames, "crossfadeFrames" to p.crossfadeFrames,
        "sourceBlendStartFrame" to p.sourceBlendStartFrame, "sourceBlendTargetStartFrame" to p.sourceBlendTargetStartFrame,
        "releaseStartFrame" to p.releaseStartFrame, "releaseFrames" to p.releaseFrames,
        "totalFrames" to p.totalFrames, "wrapFrames" to p.wrapFrames,
    )

    private fun motion(m: FlockRibbonSustain.Motion): Map<String, Any?> = linkedMapOf(
        "fromSeconds" to m.fromSeconds, "toSeconds" to m.toSeconds,
        "rmsPitchDepthSemitones" to m.rmsPitchDepthSemitones,
        "rmsPitchSpeedSemitonesPerSecond" to m.rmsPitchSpeedSemitonesPerSecond,
        "characteristicMotionHz" to m.characteristicMotionHz,
        "rmsEndpointDriftSemitones" to m.rmsEndpointDriftSemitones,
    )

    private fun diagnostics(d: FlockRibbonSustain.Diagnostics): Map<String, Any?> = linkedMapOf(
        "rendererConvenienceGain" to d.commonGain,
        "rendererConvenienceGainUsedForPack" to false,
        "rawReferencePeak" to d.rawReferencePeak, "rawLoopedPeak" to d.rawLoopedPeak,
        "blendCorrelation" to d.blendCorrelation, "blendMin50msDb" to d.blendMin50msDb,
        "blendMax50msDb" to d.blendMax50msDb, "loopRmsVsSourceDb" to d.loopRmsVsSourceDb,
        "unmodifiedLoopFraction" to d.unmodifiedLoopFraction,
        "naturalStepP99" to d.naturalStepP99, "naturalStepMax" to d.naturalStepMax,
        "naturalSlopeChangeP99" to d.naturalSlopeChangeP99, "naturalSlopeChangeMax" to d.naturalSlopeChangeMax,
        "capturedMotion" to motion(d.capturedMotion), "continuedMotion" to motion(d.continuedMotion),
        "edges" to d.edges.map { e -> linkedMapOf(
            "kind" to e.kind, "phase" to e.phase, "outputFrame" to e.outputFrame,
            "jump" to e.jump, "slopeChangeBefore" to e.slopeChangeBefore, "slopeChangeAfter" to e.slopeChangeAfter,
            "slopeChange" to e.slopeChange, "localRms" to e.localRms,
            "jumpOverLocalRms" to e.jumpOverLocalRms, "slopeChangeOverLocalRms" to e.slopeChangeOverLocalRms,
            "jumpOverNaturalP99" to e.jumpOverNaturalP99, "slopeChangeOverNaturalP99" to e.slopeChangeOverNaturalP99,
        ) },
    )

    private fun provenance(repo: File): Map<String, Any?> {
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            require(process.waitFor() == 0) { output }
            return output
        }
        val paths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Flock.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/FlockRibbonSustain.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/FlockPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/FlockRibbonSustainAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/FlockRibbonSustainTest.kt",
            "synth/src/test/resources/$assetDirectory/index.html",
            "synth/src/test/resources/$assetDirectory/styles.css",
            "synth/src/test/resources/$assetDirectory/app.js",
            "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/SmplChunk.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt", "synth/build.gradle.kts",
        )
        return linkedMapOf("repository" to "https://github.com/BertCalm/SnipSnap", "revision" to git("rev-parse", "HEAD"),
            "dirty" to git("status", "--porcelain").isNotEmpty(), "snapshotDirectory" to "source",
            "files" to paths.map { linkedMapOf("path" to it, "sha256" to sha(File(repo, it))) })
    }

    private fun writeTrace(file: File, trace: List<Flock.TraceFrame>) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine((listOf("seconds") + (0 until 24).map { "pitch_%02d".format(it) } +
                (0 until 24).map { "energy_%02d".format(it) }).joinToString(","))
            for (frame in trace) out.appendLine((listOf(frame.seconds) + frame.pitchSemitones + frame.energy)
                .joinToString(",") { "%.9f".format(Locale.ROOT, it) })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyClips(root: File, clips: List<Map<String, Any?>>) {
        for (clip in clips) {
            val hashes = clip.getValue("sha256") as Map<String, String>
            for ((hash, path) in listOf("raw" to "rawSrc", "matched" to "src", "recipe" to "recipeSrc", "trace" to "traceSrc")) {
                val file = File(root, clip.getValue(path) as String).canonicalFile
                require(file.toPath().startsWith(root.toPath()) && file.isFile && sha(file) == hashes[hash])
            }
        }
    }

    private fun writeChecksums(root: File) {
        val files = root.walkTopDown().filter { it.isFile && it.name != "SHA256SUMS" && it.name != "archives.sha256" && it.extension != "zip" }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        File(root, "SHA256SUMS").writeText(files.joinToString("\n", postfix = "\n") {
            "${sha(it)}  ${it.relativeTo(root).invariantSeparatorsPath}"
        })
    }

    private fun zipPacks(root: File) {
        val hashes = mutableListOf<String>()
        for (level in listOf("matched", "raw")) {
            val archive = File(root, "flock-ribbon-sustain-$level.zip")
            val files = root.walkTopDown().filter { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                file.isFile && file.extension != "zip" && path != "archives.sha256" &&
                    (path.substringBefore('/') !in listOf("matched", "raw") || path.startsWith("$level/"))
            }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in files) {
                    val entry = ZipEntry(file.relativeTo(root).invariantSeparatorsPath); entry.time = 0L
                    zip.putNextEntry(entry); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            require(archive.length() < 24L * 1024 * 1024) { "${archive.name} exceeds the per-asset hosting budget" }
            hashes += "${sha(archive)}  ${archive.name}"
        }
        File(root, "archives.sha256").writeText(hashes.joinToString("\n", postfix = "\n"))
    }

    private fun snip(samples: FloatArray) = Snip(samples, channels = 1, sampleRate = 44_100)
    private fun scaled(samples: FloatArray, gain: Double) = FloatArray(samples.size) { (samples[it] * gain).toFloat() }
    private fun peak(samples: FloatArray) = samples.maxOf { abs(it.toDouble()) }
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun loudness(samples: FloatArray) = Loudness.of(snip(samples)).toDouble()
    private fun copy(from: File, to: File) { to.parentFile.mkdirs(); from.copyTo(to, overwrite = true) }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
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
}
