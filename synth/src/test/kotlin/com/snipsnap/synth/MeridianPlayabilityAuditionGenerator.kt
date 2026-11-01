package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A separate round-two listening pack. The accepted round-one PCM files and recipe
 * are copied unchanged; current source, new finite renders and fixed-alignment held
 * renders have separate provenance. A listening repetition is never used as seam proof.
 */
object MeridianPlayabilityAuditionGenerator {
    private const val SEED = 4810
    private const val LEGACY_MODEL = 1
    private val voice = MeridianVoice.SWEEP

    private data class Case(
        val id: String, val group: String, val title: String, val detail: String,
        val overrides: Map<String, Float>, val held: Boolean = true,
        val midi: Int = Meridian.DEFAULT_MIDI, val velocity: Float = 1f,
    )

    private fun cases() = listOf(
        Case("finite-default", "Finite gesture", "Same finite gesture, current renderer",
            "Model 2, HOLD 0. Compare with the unchanged accepted model-1 reference. The decoded PCM samples must match.", emptyMap(), held = false),
        Case("finite-long", "Finite gesture", "A longer finite journey",
            "HOLD .90 remains finite. The swing continues discovering materials and then releases.", mapOf("HOLD" to .9f), held = false),
        Case("held-default", "Fixed-alignment sustain", "The swing held at its selected alignment",
            "HOLD 1 freezes the plane the finite gesture reaches after two seconds. Swing contact keeps repeating in that plane; ongoing precession stops.", emptyMap()),
        Case("held-memory", "Fixed-alignment sustain", "Maximum material memory",
            "MEMORY 1. Listen through eight exported-loop repeats for accumulation, a level dip or a join.", mapOf("MEMORY" to 1f)),
        Case("held-length-0", "Swing and reach", "Shortest swing",
            "LENGTH 0. The articulation clock changes while the requested note remains the same.", mapOf("LENGTH" to 0f)),
        Case("held-length-1", "Swing and reach", "Longest swing",
            "LENGTH 1. Judge the slower recurring gesture and the transition at the exported wrap.", mapOf("LENGTH" to 1f)),
        Case("held-reach-0", "Swing and reach", "Light engagement",
            "REACH 0. The inward ribbons should still supply useful contact at minimum excursion.", mapOf("REACH" to 0f)),
        Case("held-reach-1", "Swing and reach", "Deep engagement",
            "REACH 1. Compare pressure, catch and upper material response with the light setting.", mapOf("REACH" to 1f)),
        Case("held-latitude-2", "Held latitude contract", "Southern setting, fixed alignment",
            "LATITUDE .20 selects the plane reached after two finite seconds, then holds that different material alignment without continued traversal.", mapOf("LATITUDE" to .2f)),
        Case("held-latitude-5", "Held latitude contract", "Equator setting, fixed alignment",
            "LATITUDE .50 selects the starting plane. Compare with the southern/default settings: LATITUDE chooses the held plane rather than continuing to rotate it.", mapOf("LATITUDE" to .5f)),
        Case("held-note-36", "Register and touch", "C2 held",
            "Low register. Judge whether the root remains clear behind recurring contact.", emptyMap(), midi = 36),
        Case("held-note-84", "Register and touch", "C6 held",
            "High register. The material upper modes remain band limited; judge the note and its fine ringing.", emptyMap(), midi = 84),
        Case("held-velocity-25", "Register and touch", "Soft contact held",
            "Velocity .25. Matched playback lets the contact character be compared without a louder clip winning.", emptyMap(), velocity = .25f),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.getOrNull(0) ?: "../../meridian-round2-audition").absoluteFile
        val original = File(args.getOrNull(1) ?: "../../meridian-kotlin-audition").absoluteFile
        check(root.mkdirs() || root.isDirectory) { "Cannot create ${root.path}" }
        check(root.canonicalFile != original.canonicalFile) { "Round two must not overwrite the accepted audition" }
        val began = System.nanoTime()
        val repository = File(git("rev-parse", "--show-toplevel"))
        val source = retainSource(root, repository)
        val originalManifestFile = File(original, "manifest.json")
        val originalManifest = Json.parse(originalManifestFile.readText()).obj()
        val accepted = originalManifest.getValue("clips").arr().single { it.obj().getValue("id").str() == "default" }.obj()
        val acceptedFiles = accepted.getValue("files").obj()
        val acceptedRecipe = PadRecipe.fromJsonText(File(original, acceptedFiles.getValue("recipe").str()).readText())
        val acceptedPatch = acceptedRecipe.patch as? MeridianPatch ?: error("Reference recipe must contain Meridian")
        check(acceptedPatch.model == LEGACY_MODEL && acceptedPatch.seedContext == SEED)
        val defaults = acceptedPatch.macros
        val clips = mutableListOf<Map<String, Any?>>()
        clips += copyReference(root, original, accepted, originalManifest)

        for (case in cases()) {
            val macros = defaults + case.overrides + ("HOLD" to if (case.held) 1f else (case.overrides["HOLD"] ?: 0f))
            val patch = MeridianPatch(case.title, voice, macros, case.midi, case.velocity,
                model = Meridian.MODEL_VERSION, seedContext = SEED)
            MeridianMotion.clearCache()
            val firstStart = System.nanoTime()
            val held = if (case.held) Meridian.renderHeld(voice, macros, case.midi, case.velocity, SEED) else null
            val native = held?.snip ?: patch.render()
            val firstMillis = (System.nanoTime() - firstStart) / 1e6
            checkAudio(case.id, native)
            val repeatStart = System.nanoTime()
            val replay = patch.render()
            val repeatMillis = (System.nanoTime() - repeatStart) / 1e6
            check(sameBits(native, replay)) { "${case.id}: native result differs from ordinary patch replay" }
            val recipe = PadRecipe(patch = patch)
            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            check(restored.patch == patch) { "${case.id}: recipe fields changed" }
            if (case.id in setOf("finite-default", "held-default", "held-memory"))
                check(sameBits(native, restored.render())) { "${case.id}: serialized recipe did not reproduce audio" }
            val matched = AuditionLevel.level(native)
            checkAudio("${case.id} matched", matched)
            val actualMidi = Meridian.midiFor(voice, macros.getValue("TUNE"), case.midi)
            val sampler = SmplChunk(actualMidi, held?.let {
                SmplChunk.Loop(it.loopStartFrame.toLong(), it.loopEndFrameExclusive.toLong())
            })
            val files = linkedMapOf(
                "native" to "sampler/${case.id}-native.wav",
                "matched" to "sampler/${case.id}-matched.wav",
                "recipe" to "recipes/${case.id}.json",
            )
            WavWriter.write(File(root, files.getValue("native")), native, WavWriter.BitDepth.PCM_16, smpl = sampler)
            WavWriter.write(File(root, files.getValue("matched")), matched, WavWriter.BitDepth.PCM_16, smpl = sampler)
            File(root, files.getValue("recipe")).also { it.parentFile.mkdirs() }.writeText(recipe.toJsonText())
            val decoded = WavReader.read(File(root, files.getValue("native")))
            val decodedMatched = WavReader.read(File(root, files.getValue("matched")))
            checkAudio("${case.id} decoded", decoded)
            check(WavReader.readSmpl(File(root, files.getValue("native"))) == sampler) { "${case.id}: native sampler metadata changed" }
            check(WavReader.readSmpl(File(root, files.getValue("matched"))) == sampler) { "${case.id}: matched sampler metadata changed" }
            var loopMetrics: Map<String, Any?>? = null
            if (held != null) {
                check(held.loopStartFrame >= 256 && held.loopEndFrameExclusive == native.frameCount)
                check(held.loopStartFrame * 2 == held.loopEndFrameExclusive) { "Expected two actual evolved cycles" }
                val nativeSeam = Keys.seamError(decoded.samples, held.loopStartFrame)
                val matchedSeam = Keys.seamError(decodedMatched.samples, held.loopStartFrame)
                check(listOf(held.seamError, nativeSeam, matchedSeam).all { it.isFinite() && it < Keys.MAX_SEAM_ERROR }) {
                    "${case.id}: exported loop seam failed (${held.seamError}, $nativeSeam, $matchedSeam)"
                }
                val nativeEight = repeatExportedLoop(decoded, held.loopStartFrame, held.loopEndFrameExclusive)
                val matchedEight = repeatExportedLoop(decodedMatched, held.loopStartFrame, held.loopEndFrameExclusive)
                files["nativeListening"] = "audio/${case.id}-native-eight-loops.wav"
                files["matchedListening"] = "audio/${case.id}-matched-eight-loops.wav"
                WavWriter.write(File(root, files.getValue("nativeListening")), nativeEight, WavWriter.BitDepth.PCM_16)
                WavWriter.write(File(root, files.getValue("matchedListening")), matchedEight, WavWriter.BitDepth.PCM_16)
                loopMetrics = linkedMapOf(
                    "adaptation" to "Fixed alignment: no ongoing precession",
                    "loopStartFrame" to held.loopStartFrame,
                    "loopEndFrameExclusive" to held.loopEndFrameExclusive,
                    "loopFrames" to held.loopEndFrameExclusive - held.loopStartFrame,
                    "loopSeconds" to (held.loopEndFrameExclusive - held.loopStartFrame).toDouble() / Dsp.RATE,
                    "realPreviousCycleSeamError" to held.seamError,
                    "exportedNativePcm16SeamError" to nativeSeam,
                    "exportedMatchedPcm16SeamError" to matchedSeam,
                    "cycleError" to held.cycleError, "stateError" to held.stateError,
                    "noiseStateError" to held.noiseStateError,
                    "swingsPerLoop" to held.swings,
                    "nominalSwingPeriodSeconds" to held.nominalSwingPeriodSeconds,
                    "effectiveSwingPeriodSeconds" to held.effectiveSwingPeriodSeconds,
                    "swingPeriodChangePercent" to 100 * (held.effectiveSwingPeriodSeconds / held.nominalSwingPeriodSeconds - 1),
                    "requestedRootHz" to held.rootHz,
                    "rootHarmonicGridHz" to held.rootGridHz,
                    "rootHarmonicGridErrorCents" to held.rootCents,
                    "fixedOrientationRadians" to held.fixedOrientation,
                    "activeSurfaceCount" to held.activeSurfaces,
                    "rawPeakBeforeOutputLeveling" to held.rawPeak,
                    "peakModalEnergy" to held.peakEnergy,
                    "inputHashes" to held.inputHashes.map { java.lang.Long.toUnsignedString(it, 16).padStart(16, '0') },
                    "roughnessHashes" to held.roughnessHashes.map { java.lang.Long.toUnsignedString(it, 16).padStart(16, '0') },
                    "listeningRepeats" to 8,
                    "evidence" to "Seams compare actual preceding and exported conditioned cycles before any listening repetition. Repeated files demonstrate playback only. Root-grid error describes the periodic forcing grid, separately from the measured full-mix pitch estimate. The swing period is slightly fitted to a bounded loop; modal tuning is not retuned.",
                )
            }
            if (case.id == "finite-default") {
                val copied = WavReader.read(File(root, "reference/model1-default-native.wav"))
                check(sameBits(copied, decoded)) { "Current finite default PCM differs from the unchanged accepted reference" }
            }
            val classification = Classifier.classify(decoded)
            val clip = linkedMapOf<String, Any?>(
                "id" to case.id, "group" to case.group, "title" to case.title, "detail" to case.detail,
                "source" to "Current retained Kotlin source", "model" to Meridian.MODEL_VERSION,
                "voice" to voice.name, "midi" to case.midi, "actualMidi" to actualMidi,
                "note" to noteName(actualMidi), "velocity" to case.velocity, "seedContext" to SEED,
                "macros" to macros, "held" to case.held, "files" to files,
                "durationSeconds" to native.durationSeconds, "native" to metrics(native),
                "matched" to metrics(matched), "decodedNativePcm16" to metrics(decoded),
                "nativeSampleSha256" to sampleHash(native.samples),
                "nativeWavSha256" to sha(File(root, files.getValue("native")).readBytes()),
                "matchedWavSha256" to sha(File(root, files.getValue("matched")).readBytes()),
                "firstPassMillis" to firstMillis, "repeatPassMillis" to repeatMillis,
                "timingScope" to "First pass follows motion-cache clear; repeat is ordinary patch render. JVM warmup and any other caches are not reset. Held setup and finite output conditioning are included.",
                "recipeRoundtrip" to true, "ordinaryPatchReplayIdentical" to true,
                "samePcmAsAcceptedReference" to if (case.id == "finite-default") true else null,
                "loop" to loopMetrics,
                "pitch" to pitch(decoded, actualMidi, held?.loopStartFrame),
                "classifier" to linkedMapOf("class" to classification.drumClass.name, "confidence" to classification.confidence),
            )
            clips += clip
            println("MERIDIAN round 2 ${clips.size}/14: ${case.id}, first ${number(firstMillis, 1)} ms, repeat ${number(repeatMillis, 1)} ms")
        }
        check(clips.size == 14)
        val timing = benchmark(defaults)
        val provenance = linkedMapOf<String, Any?>(
            "sourceCommit" to git("rev-parse", "HEAD"), "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "generatedAt" to Instant.now().toString(), "retainedSourceFilesSha256" to source,
            "sourceSnapshotNote" to "Source bytes were retained before rendering and checked unchanged afterwards. The base commit plus local source snapshot identifies these renders.",
        )
        for ((path, hash) in source) check(sha(File(repository, path).readBytes()) == hash) { "Source changed during render: $path" }
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "MERIDIAN", "round" to 2, "model" to Meridian.MODEL_VERSION,
            "sampleRate" to Dsp.RATE, "channels" to 1, "clipCount" to clips.size,
            "clips" to clips, "warmPublicRenderBenchmark" to timing,
            "acceptedReferenceManifestSha256" to sha(originalManifestFile.readBytes()),
            "historicalDefaultBenchmark" to originalManifest["warmPublicRenderBenchmark"],
            "notes" to listOf(
                "The accepted model-1 reference WAVs and recipe were copied unchanged. New finite model-2 default PCM is checked against that reference.",
                "HOLD below .975 remains finite. At the top, model 2 holds the plane reached after two finite seconds while swing contact repeats. LATITUDE chooses that plane, not continuing precession. The swing period is fitted slightly to the bounded loop; its measured change is recorded.",
                "Sampler WAVs retain two independently evolved conditioned cycles and a forward smpl loop over the second cycle. Browser files repeat the decoded exported loop eight times.",
                "Native audio has the normal engine output level. Matched audio applies AuditionLevel.level to the complete two-cycle source before export.",
                "Local pitch and octave-estimator limitations from round one are not claimed fixed. Read pitch estimates as observations and judge the audible root.",
                "Historical and current timings come from separate runs. Current warm measurements include public rendering and normal output conditioning; they are not phone performance.",
                "These new Kotlin held examples await owner listening. Numeric seams do not establish freedom from audible gain dips or material discontinuities.",
            ),
        ) + provenance
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        File(root, "diagnostics.json").writeText(Json.write(json(linkedMapOf(
            "clipCount" to clips.size, "newHeldCases" to clips.count { it["held"] == true },
            "finiteAndUnclipped" to true, "nativeSamplerMarkersVerified" to true,
            "matchedSamplerMarkersVerified" to true, "ordinaryPatchReplayIdentical" to true,
            "warmPublicRenderBenchmark" to timing, "generationSeconds" to (System.nanoTime() - began) / 1e9,
            "acceptance" to "Numerical/export evidence collected. New held audio awaits owner listening; full-mix pitch limits remain disclosed.",
        ) + provenance)))
        File(root, "index.html").writeText(page(clips, timing))
        // Packaging is a separate final step after the repository tests, so the
        // published ZIP can include their evidence without rerendering this pack.
        println("MERIDIAN round 2 wrote 14 comparisons, sampler loops, eight-repeat playback and retained source under ${root.path}")
    }

    private fun copyReference(root: File, original: File, accepted: Map<String, JsonValue>, originalManifest: Map<String, JsonValue>): Map<String, Any?> {
        val oldFiles = accepted.getValue("files").obj()
        val files = linkedMapOf(
            "native" to "reference/model1-default-native.wav",
            "matched" to "reference/model1-default-matched.wav",
            "recipe" to "reference/model1-default-recipe.json",
            "evidence" to "reference/model1-default-evidence.json",
        )
        val hashes = linkedMapOf<String, String>()
        for (kind in listOf("native", "matched", "recipe")) {
            val from = File(original, oldFiles.getValue(kind).str())
            val to = File(root, files.getValue(kind)).also { it.parentFile.mkdirs() }
            val bytes = from.readBytes()
            to.writeBytes(bytes)
            hashes[kind] = sha(bytes)
            check(sha(to.readBytes()) == hashes[kind]) { "Reference copy changed $kind" }
        }
        File(root, files.getValue("evidence")).writeText(Json.write(json(linkedMapOf(
            "originalClip" to JsonValue.Obj(accepted), "originalSourceCommit" to originalManifest["sourceCommit"],
            "originalSourceFilesSha256" to originalManifest["sourceFilesSha256"],
            "copySha256" to hashes, "note" to "These are unchanged copied round-one files, not rerenders from current source.",
        ))))
        val native = WavReader.read(File(root, files.getValue("native")))
        return linkedMapOf(
            "id" to "model1-reference", "group" to "Accepted reference", "title" to "The unchanged model-1 starting swing",
            "detail" to "The exact native and matched WAVs previously presented for listening. Copied byte-for-byte with their original recipe; no new processing.",
            "source" to "Accepted round-one artifact, copied unchanged", "model" to LEGACY_MODEL,
            "voice" to accepted["voice"], "midi" to accepted["midi"], "actualMidi" to accepted["actualMidi"],
            "note" to accepted["note"], "velocity" to accepted["velocity"], "seedContext" to accepted["seedContext"],
            "macros" to accepted["macros"], "files" to files, "held" to false,
            "durationSeconds" to native.durationSeconds, "native" to metrics(native),
            "copiedUnchanged" to true, "copySha256" to hashes, "pitch" to accepted["pitch"],
            "historicalTimingNote" to "This reference was copied. Current render timings are listed separately.",
        )
    }

    private fun repeatExportedLoop(source: Snip, start: Int, end: Int): Snip {
        val n = end - start
        require(n > 0 && start >= 0 && end <= source.frameCount)
        return Snip(FloatArray(n * 8) { source.samples[start + it % n] }, 1, Dsp.RATE)
    }

    private fun benchmark(defaults: Map<String, Float>): List<Map<String, Any?>> = listOf(
        "current-model1-finite" to MeridianPatch("Timing model 1", voice, defaults, model = 1, seedContext = SEED),
        "current-model2-finite" to MeridianPatch("Timing model 2", voice, defaults, model = Meridian.MODEL_VERSION, seedContext = SEED),
        "current-model2-held" to MeridianPatch("Timing held", voice, defaults + ("HOLD" to 1f), model = Meridian.MODEL_VERSION, seedContext = SEED),
    ).map { (name, patch) ->
        repeat(3) { patch.render() }
        var frames = 0
        val times = List(7) {
            val began = System.nanoTime()
            frames = patch.render().frameCount
            (System.nanoTime() - began) / 1e6
        }
        val sorted = times.sorted()
        linkedMapOf<String, Any?>(
            "case" to name, "warmupRenders" to 3, "measuredRenders" to times.size,
            "milliseconds" to times, "minMillis" to sorted.first(), "medianMillis" to sorted[sorted.size / 2],
            "maxMillis" to sorted.last(), "frames" to frames, "secondsOfStoredAudio" to frames.toDouble() / Dsp.RATE,
            "scope" to "Current public patch render with normal conditioning. Setup is included; three warmups precede seven measurements. Not a phone benchmark.",
        )
    }

    private fun pitch(snip: Snip, midi: Int, loopStart: Int?): Map<String, Any?> {
        val from = if (loopStart == null) .18f else (loopStart.toDouble() / snip.sampleRate).toFloat()
        val seconds = minOf(.35f, snip.durationSeconds - from)
        val estimate = Pitch.detect(snip, from, seconds)
        val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
        return linkedMapOf(
            "method" to "Pitch.detect on decoded native PCM16; local body window or the exported held cycle",
            "fromSeconds" to from, "windowSeconds" to seconds, "targetHz" to expected,
            "hz" to estimate?.hz, "confidence" to estimate?.confidence,
            "errorCents" to estimate?.let { 1200 * ln(it.hz / expected) / ln(2.0) },
            "note" to "An estimator observation. Broad modes, recurring excitation and octave choices can disagree with the declared natural modal root.",
        )
    }

    /** Retain the actual bytes used for this render, rather than hashing later edited files. */
    private fun retainSource(root: File, repository: File): Map<String, String> {
        val engineDirectory = File(repository, "synth/src/main/kotlin/com/snipsnap/synth")
        val paths = engineDirectory.listFiles().orEmpty().filter { it.name.startsWith("Meridian") && it.extension == "kt" }
            .map { it.relativeTo(repository).invariantSeparatorsPath } + listOf(
                "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Modes.kt",
                "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
                "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
                "audio/src/main/kotlin/com/snipsnap/audio/SmplChunk.kt",
                "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
                "synth/src/test/kotlin/com/snipsnap/synth/MeridianPlayabilityAuditionGenerator.kt",
            )
        return paths.distinct().sorted().associateWith { path ->
            val bytes = File(repository, path).readBytes()
            File(root, "source/$path").also { it.parentFile.mkdirs() }.writeBytes(bytes)
            sha(bytes)
        }
    }

    private fun checkAudio(id: String, snip: Snip) {
        check(snip.sampleRate == Dsp.RATE && snip.channels == 1) { "$id: wrong format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id: invalid samples" }
        check(snip.peak() > 1e-7f && snip.peak() < 1f) { "$id: silent or clipping output" }
    }

    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf(
        "peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "loudness" to Loudness.of(snip),
    )

    private fun sameBits(a: Snip, b: Snip) = a.sampleRate == b.sampleRate && a.channels == b.channels &&
        a.samples.size == b.samples.size && a.samples.indices.all { a.samples[it].toRawBits() == b.samples[it].toRawBits() }

    private fun sampleHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in samples) bytes.putInt(value.toRawBits())
        return sha(bytes.array())
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[Math.floorMod(midi, 12)] + (midi / 12 - 1)
    private fun number(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(clips: List<Map<String, Any?>>, timing: List<Map<String, Any?>>) = buildString {
        append("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Meridian · Round two</title><style>body{font:16px/1.55 system-ui;background:#121e29;color:#eee7da;max-width:1000px;margin:38px auto;padding:0 22px}h1{font-size:36px;margin-bottom:8px}h2{font-size:24px;margin-top:35px}h3{font-size:19px;margin-bottom:5px}p{color:#bdcbd4}a{color:#82d5d8}article{border-top:1px solid #3d5362;padding:18px 0}audio{width:min(100%,670px)}.row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.row span{width:78px}small{color:#afbfcb}pre{font-size:12px;overflow:auto;background:#1b2e3b;padding:13px}details{margin:14px 0}.badge{display:inline-block;padding:3px 9px;border:1px solid #506a77;border-radius:12px;font-size:12px;color:#d0e4e6}@media(max-width:600px){body{padding:0 15px;margin-top:22px}.row span{width:100%}h1{font-size:29px}}</style><h1>Meridian · Round two</h1><p>A quicker finite gesture and a fixed-alignment sustained adaptation, beside the unchanged model-1 reference.</p><p>Start with the accepted reference and the current finite default. Their exported PCM samples are checked for equality. Then play the held examples through eight returns: listen for clicks, level dips, a sudden material change, and whether the requested note remains clear.</p><p>At HOLD's top step, the swing keeps moving while its alignment stays fixed. LATITUDE selects the plane reached after two finite seconds, then that plane is held. The southern/equator comparisons show the different selected materials. Swing timing is slightly fitted to a bounded loop; the timing and root-grid errors are recorded beside each clip. The faster renderer and numerical seams do not establish a new listening verdict.</p><p>Matched playback uses the shared audition loudness rule. Native sampler downloads retain the engine's ordinary level and real forward-loop metadata. Browser playback repeats the decoded exported loop eight times; seam measurements use the independently evolved preceding cycle before repetition.</p><p><a href="audition.zip">Download audition.zip</a> · <a href="manifest.json">Recipes, source hashes and measurements</a> · <a href="diagnostics.json">Numerical and timing evidence</a></p>""")
        var group = ""
        for (clip in clips) {
            val currentGroup = clip.getValue("group").toString()
            if (currentGroup != group) { append("<h2>${escape(currentGroup)}</h2>"); group = currentGroup }
            @Suppress("UNCHECKED_CAST") val files = clip.getValue("files") as Map<String, String>
            append("<article id=\"${clip["id"]}\"><span class=\"badge\">Model ${clip["model"]} · ${if (clip["held"] == true) "fixed-alignment HOLD" else "finite"}</span><h3>${escape(clip.getValue("title").toString())}</h3><p>${escape(clip.getValue("detail").toString())}</p>")
            for ((label, kind) in listOf("Matched" to "matched", "Native" to "native")) {
                val src = files[kind + "Listening"] ?: files.getValue(kind)
                append("<div class=\"row\"><span>$label</span><audio controls preload=\"none\" src=\"$src\"></audio></div>")
            }
            append("<small><a href=\"${files.getValue("native")}\">Native sampler WAV</a> · <a href=\"${files.getValue("matched")}\">Matched WAV</a> · <a href=\"${files.getValue("recipe")}\">Exact recipe</a></small>")
            append("<details><summary>Controls and measured evidence</summary><pre>${escape(Json.write(json(clip.filterKeys { it != "files" })))}</pre></details></article>")
        }
        append("<h2>Measured render cost</h2><p>These warm public-render measurements include setup and normal output conditioning. The unchanged historical clip has its earlier timing record in the manifest. Separate runs are not a phone benchmark.</p><pre>${escape(Json.write(json(timing)))}</pre>")
        append("<h2>Why it is built this way</h2><p>Keeping the accepted reference untouched protects the sound already heard. Fixed alignment allows a bounded repeated swing while separate material states keep ringing. Exported sampler markers and eight-loop listening serve different jobs: the markers tell the sampler where to return, and the repeats let an owner judge that return. Pitch estimates and seams remain evidence for that judgment.</p><script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})));</script></html>")
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Unable to record source provenance: $result" }
        return result
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> { check(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported evidence value ${value.javaClass.name}")
    }
}
