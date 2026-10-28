package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiDiagnostic
import com.snipsnap.synth.OrigamiGesture
import com.snipsnap.synth.OrigamiGestureRenderer
import com.snipsnap.synth.OrigamiGestures
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiVoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A listening experiment for one-shot fold gestures and their settled held bodies.
 * The finite files use the real patch output; held files use one real maker zone
 * through InstrumentEngine, without rendering/exporting the other eight zones.
 * The generator does not assert a musical verdict from a meter or benchmark.
 */
object OrigamiGestureAuditionGenerator {
    private const val RATE = 44_100
    private const val SEED = 20261012L
    private const val VELOCITY = .85f
    private const val MOTION = .65f
    private const val TARGET = .12f
    private const val CEILING = .90f
    private const val HOLD_SECONDS = 12.0
    private const val RELEASE_SECONDS = .6f
    private const val BASE_COMMIT_READ = "89ddb5be26471e2fa9429a66de3e9835a6050ed2"

    private data class Case(
        val id: String, val category: String, val title: String, val description: String,
        val patch: OrigamiPatch, val featured: Boolean = false, val held: Boolean = false,
    )

    private data class Rendered(
        val native: Snip, val raw: Snip?, val timing: Map<String, Any?>,
        val heldEvidence: Map<String, Any?> = emptyMap(),
    )

    private val why = listOf(
        "BLOOM opens into the chosen fold; UNFOLD starts folded and opens. STEADY keeps the folded color with axis motion off in these comparisons.",
        "MOTION sets a once-only change from fast to slow in BLOOM and UNFOLD. It does not add a repeating wobble to these gestures.",
        "Each matched file gets one gain for its entire duration. The beginning and end are never normalized separately, so changes in balance remain audible.",
        "The held examples complete their gesture before repeating a settled body. A twelve-second hold exposes repetition; note-off then uses a 0.60-second release.",
        "Cold-key and repeated-render timings describe this JVM and machine. They do not establish phone latency, musical success, or what anyone has heard.",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory" }
        val output = File(args.firstOrNull() ?: "../testkit/origami-gestures").absoluteFile
        require(output.isDirectory || output.mkdirs()) { "Cannot create " + output }
        val checkout = File("..").canonicalFile
        val sources = sourceFiles(checkout)
        val hashes = sources.associate { it.relativeTo(checkout).invariantSeparatorsPath to sha(it.readBytes()) }.toSortedMap()
        val sourceHash = sha(hashes.entries.joinToString("\n") { it.key + ":" + it.value }.toByteArray())
        val cases = cases()
        check(cases.map { it.id }.distinct().size == cases.size)
        val recipes = cases.associate { it.id to recipe(it) }
        val recipeHash = sha(Json.write(json(recipes)).toByteArray())
        val round = "origami-gesture-" + OrigamiGestures.MODEL_VERSION + "-" + sourceHash.take(12) + "-" + recipeHash.take(12)
        val started = System.nanoTime()
        val clips = cases.mapIndexed { index, case ->
            println("Rendering " + (index + 1) + "/" + cases.size + " " + case.id)
            val rendered = render(case)
            check(rendered.native.channels == 1 && rendered.native.sampleRate == RATE)
            check(rendered.native.samples.all { it.isFinite() }) { "Non-finite native sample: " + case.id }
            require(rendered.native.peak() <= 1f) { "Native render would clip PCM: " + case.id }
            val matchGain = matchingGain(rendered.native)
            val matched = gain(rendered.native, matchGain)
            val nativePath = "native/" + case.id + ".wav"
            val matchedPath = "matched/" + case.id + ".wav"
            val recipePath = "recipes/" + case.id + ".json"
            WavWriter.write(File(output, nativePath), rendered.native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(output, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            File(output, recipePath).apply { parentFile.mkdirs(); writeText(Json.write(json(recipes.getValue(case.id))) + "\n") }
            val seconds = rendered.native.frameCount.toDouble() / RATE
            val start = if (case.patch.gesture == OrigamiGesture.STEADY) 0.0 else OrigamiGestures.startSeconds(case.patch.voice)
            val duration = if (case.patch.gesture == OrigamiGesture.STEADY) 0.0 else
                OrigamiGestures.durationSeconds(case.patch.voice, case.patch.resolvedMacros().getValue("MOTION"))
            val midi = requireNotNull(case.patch.midi)
            linkedMapOf<String, Any?>(
                "id" to case.id, "category" to case.category, "title" to case.title,
                "description" to case.description, "voice" to case.patch.voice.name,
                "gesture" to case.patch.gesture.name, "midi" to midi,
                "note" to note(midi), "authoredHz" to 440.0 * 2.0.pow((midi - 69) / 12.0),
                "featured" to case.featured, "held" to case.held, "recipe" to recipes.getValue(case.id),
                "recipePath" to recipePath, "recipeSha256" to sha(File(output, recipePath).readBytes()),
                "geometryModel" to case.patch.model, "gestureModel" to OrigamiGestures.MODEL_VERSION,
                "macros" to case.patch.resolvedMacros(), "seed" to case.patch.seed.toString(), "velocity" to case.patch.velocity,
                "frames" to rendered.native.frameCount, "durationSeconds" to seconds, "sampleRate" to RATE,
                "gestureStartSeconds" to start, "gestureDurationSeconds" to duration,
                "gestureEndSeconds" to OrigamiGestures.endSeconds(case.patch),
                "maximumFold" to case.patch.resolvedMacros().getValue("FOLD"),
                "settledFold" to OrigamiGestures.foldAt(case.patch, maxOf(seconds, start + duration)),
                "foldCurveSvg" to curve(case.patch, seconds), "renderTiming" to rendered.timing,
                "nativePath" to nativePath, "matchedPath" to matchedPath, "matchedGain" to matchGain,
                "nativeMetrics" to metrics(rendered.native), "matchedMetrics" to metrics(matched),
                "rawBeforeOutputMetrics" to rendered.raw?.let { metrics(it) },
                "rawBeforeOutputWaveformSha256" to rendered.raw?.let { waveformSha(it.samples) },
                "nativeWaveformSha256" to waveformSha(rendered.native.samples),
                "matchedWaveformSha256" to waveformSha(matched.samples),
                "nativeFileSha256" to sha(File(output, nativePath).readBytes()),
                "matchedFileSha256" to sha(File(output, matchedPath).readBytes()),
                "heldEvidence" to rendered.heldEvidence,
            )
        }
        val benchmarks = benchmarks()
        check(sources.all { sha(it.readBytes()) == hashes.getValue(it.relativeTo(checkout).invariantSeparatorsPath) }) {
            "Source files changed during rendering; rerun after the source tree is stable"
        }
        for (file in sources) file.copyTo(File(output, "sources/" + file.relativeTo(checkout).invariantSeparatorsPath).apply { parentFile.mkdirs() }, overwrite = true)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "ORIGAMI · A fold in time",
            "createdUtc" to Instant.now().toString(), "listeningRound" to round,
            "baseCommitRead" to BASE_COMMIT_READ, "sourceSha256" to sourceHash, "sourceFiles" to hashes,
            "recipeSha256" to recipeHash, "geometryModel" to Origami.MODEL_VERSION,
            "gestureModel" to OrigamiGestures.MODEL_VERSION, "clipCount" to clips.size,
            "sampleRate" to RATE, "bitDepth" to 16, "diagnostic" to OrigamiDiagnostic.COMBINED.name,
            "matching" to mapOf("targetLoudness" to TARGET, "peakCeiling" to CEILING,
                "meter" to "Loudness.of: loudest 200 ms window with the house low-cut weighting",
                "policy" to "One gain per complete native clip; both channels and all gesture times share that gain. No endpoint normalization."),
            "nativePolicy" to "Finite: OrigamiPatch.render including house output. Held: one OrigamiPadMaker zone through InstrumentEngine; mono left channel, identical to right.",
            "pitchPolicy" to "MIDI is authored. No detector result or numerical test is presented as perceived-pitch approval.",
            "benchmarkPolicy" to "Post-matrix JVM wall time: clear the gesture basis cache, render once, take the median of three identical repeats, then measure six FOLD/MOTION edits sharing the geometry key. Not process-cold or a phone benchmark.",
            "whyItIsBuiltThisWay" to why,
            "verificationLedger" to mapOf(
                "verifiedByGenerator" to listOf("finite native output is finite and inside PCM range", "one common gain per clip", "repeated benchmark renders have identical float hashes", "source files stable during generation"),
                "measured" to "Per-clip levels, hashes and timings in this manifest; reference key playback for held files",
                "proposed" to "The chosen fold trajectories and timing ranges are candidates for listening",
                "heard" to "No listening verdict recorded by this generator",
            ),
            "benchmarks" to benchmarks, "clips" to clips,
            "generationMilliseconds" to milliseconds(started),
            "audioBytes" to clips.sumOf { File(output, it.getValue("nativePath").toString()).length() + File(output, it.getValue("matchedPath").toString()).length() },
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        File(output, "manifest.json").writeText(manifestText)
        File(output, "manifest.sha256").writeText(sha(manifestText.toByteArray()) + "  manifest.json\n")
        File(output, "index.html").writeText(page(manifest, emptyMap(), false))
        val starterClips = clips.filter { it["featured"] == true }
        check(starterClips.size == 9)
        val starter = LinkedHashMap(manifest).apply {
            put("clips", starterClips); put("clipCount", starterClips.size); put("parentClipCount", clips.size)
            put("edition", "Nine matched C4 references")
            put("parentManifestSha256", sha(manifestText.toByteArray()))
        }
        val audio = starterClips.associate {
            val path = it.getValue("matchedPath").toString()
            path to ("data:audio/wav;base64," + Base64.getEncoder().encodeToString(File(output, path).readBytes()))
        }
        File(output, "start-here.html").writeText(page(starter, audio, true))
        File(output, "README.txt").writeText(readme(manifest, output))
        println("Wrote " + clips.size + " native/matched clips, full recipes, source evidence, manifest, index.html and self-contained start-here.html")
        println("Audio bytes=" + manifest["audioBytes"] + "; starter bytes=" + File(output, "start-here.html").length())
    }

    private fun patch(voice: OrigamiVoice, gesture: OrigamiGesture, midi: Int = 60, motion: Float = MOTION): OrigamiPatch =
        OrigamiPatch(voice.name + " " + gesture.name, voice,
            Origami.defaults(voice) + mapOf("CREASE" to .5f, "FOLD" to .75f, "TURN" to .5f, "WARP" to .5f,
                "MOTION" to if (gesture == OrigamiGesture.STEADY) 0f else motion),
            midi = midi, velocity = VELOCITY, seed = SEED, model = 4, gesture = gesture)

    private fun cases(): List<Case> = buildList {
        for (voice in OrigamiVoice.entries) for (gesture in OrigamiGesture.entries) {
            add(Case("main_" + voice.name.lowercase() + "_" + gesture.name.lowercase() + "_60", "featured",
                voice.name + " · " + gesture.name, if (gesture == OrigamiGesture.STEADY) "C4, folded color held steady; axis motion off."
                else "C4, one fold gesture; MOTION 65%, neutral TURN and WARP.", patch(voice, gesture), featured = true))
        }
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) {
            for (gesture in listOf(OrigamiGesture.BLOOM, OrigamiGesture.UNFOLD)) {
                for ((label, motion) in listOf("fast" to 0f, "middle" to .5f, "slow" to 1f)) {
                    add(Case("speed_" + voice.name.lowercase() + "_" + gesture.name.lowercase() + "_" + label, "speed",
                        voice.name + " · " + gesture.name + " · " + label,
                        "C4; only gesture duration changes. MOTION " + (motion * 100).toInt() + "%.", patch(voice, gesture, motion = motion)))
                }
                for (midi in listOf(48, 72)) {
                    add(Case("register_" + voice.name.lowercase() + "_" + gesture.name.lowercase() + "_" + midi, "register",
                        voice.name + " · " + gesture.name + " · " + note(midi),
                        "Same fold controls and seed as C4; authored note " + note(midi) + ".", patch(voice, gesture, midi)))
                }
                add(Case("held_" + voice.name.lowercase() + "_" + gesture.name.lowercase() + "_60", "held",
                    voice.name + " · " + gesture.name + " · held C4",
                    "Hold 12 s, then release 0.60 s. The gesture happens once before the settled loop; 0.05 s of silence follows.",
                    patch(voice, gesture), held = true))
            }
        }
    }

    private fun recipe(case: Case): Map<String, Any?> = linkedMapOf(
        "kind" to if (case.held) "held-reference-playback" else "finite-patch",
        "patch" to case.patch.toJsonValue(), "diagnostic" to OrigamiDiagnostic.COMBINED.name,
        "heldSpec" to if (case.held) Json.parse(OrigamiPadMaker.Spec(case.patch, RELEASE_SECONDS).toJsonText()) else null,
        "playback" to if (case.held) mapOf("midi" to 60, "velocity" to 1f, "noteOnSeconds" to 0,
            "noteOffSeconds" to HOLD_SECONDS, "releaseSeconds" to RELEASE_SECONDS, "postReleaseSilenceSeconds" to .05,
            "referenceEngine" to "InstrumentEngine", "voiceLevel" to InstrumentEngine.VOICE_LEVEL, "renderedZoneCount" to 1) else null,
    )

    private fun render(case: Case): Rendered {
        if (!case.held) {
            val startRaw = System.nanoTime()
            val raw = Snip(OrigamiGestureRenderer.renderRaw(case.patch, diagnostic = OrigamiDiagnostic.COMBINED), 1, RATE)
            val rawMs = milliseconds(startRaw)
            val startNative = System.nanoTime()
            val native = case.patch.render()
            return Rendered(native, raw, mapOf("rawMilliseconds" to rawMs, "nativeMilliseconds" to milliseconds(startNative),
                "order" to "raw first, native second; second call may reuse geometry"))
        }
        val started = System.nanoTime()
        val spec = OrigamiPadMaker.Spec(case.patch, RELEASE_SECONDS)
        val zoneNote = OrigamiPadMaker.renderZone(spec, 60)
        val zoneMs = milliseconds(started)
        val zone = InstrumentStore.Zone(60, 60, 60, "reference.wav", zoneNote.snip.frameCount.toLong(), zoneNote.loopStartFrame)
        val instrument = InstrumentStore.Instrument("ORIGAMI GESTURE REFERENCE", RELEASE_SECONDS, listOf(zone))
        val engine = InstrumentEngine(InstrumentEngine.Loaded(instrument, RATE, listOf(zoneNote.snip.samples)), RATE)
        val held = (HOLD_SECONDS * RATE).toInt()
        val release = ceil(RELEASE_SECONDS.toDouble() * RATE).toInt()
        val samples = FloatArray(held + release + RATE / 20)
        val buffer = FloatArray(512 * 2)
        check(engine.noteOn(60, 1f))
        val playbackStart = System.nanoTime()
        var position = 0
        while (position < samples.size) {
            if (position == held) engine.noteOff(60)
            val boundary = if (position < held) held else samples.size
            val frames = minOf(512, boundary - position)
            engine.render(buffer, frames)
            for (i in 0 until frames) {
                check(buffer[2 * i] == buffer[2 * i + 1]) { "Reference is no longer dual mono" }
                samples[position + i] = buffer[2 * i]
            }
            position += frames
        }
        check(engine.activeNotes.isEmpty()) { "Held note did not complete its release" }
        return Rendered(Snip(samples, 1, RATE), null,
            mapOf("singleZoneMilliseconds" to zoneMs, "referencePlaybackMilliseconds" to milliseconds(playbackStart)),
            mapOf("renderedZoneCount" to 1, "zoneRootMidi" to 60, "zoneFrames" to zoneNote.snip.frameCount,
                "loopStartFrame" to zoneNote.loopStartFrame, "loopEndFrameExclusive" to zoneNote.snip.frameCount,
                "loopSeconds" to (zoneNote.snip.frameCount - zoneNote.loopStartFrame).toDouble() / RATE,
                "zoneWaveformSha256" to waveformSha(zoneNote.snip.samples), "zoneMetrics" to metrics(zoneNote.snip),
                "noteOffFrame" to held, "releaseFrames" to release,
                "settledPatch" to OrigamiGestures.settledPatch(case.patch).toJsonValue(),
                "playbackNote" to "InstrumentEngine reference, not a recording from an Android device. One voice at 0.35 gain stays below its bus soft-clip threshold; phone uses the same KeyHit map." ))
    }

    private fun benchmarks(): List<Map<String, Any?>> = buildList {
        for (voice in OrigamiVoice.entries) for (gesture in listOf(OrigamiGesture.BLOOM, OrigamiGesture.UNFOLD)) {
            val probe = patch(voice, gesture)
            OrigamiGestureRenderer.clearCache()
            val before = cacheStats()
            val start = System.nanoTime()
            val first = probe.render()
            val coldMs = milliseconds(start)
            val afterCold = cacheStats()
            val hot = List(3) {
                val hotStart = System.nanoTime()
                val repeated = probe.render()
                val elapsed = milliseconds(hotStart)
                check(first.samples.contentEquals(repeated.samples)) { "Repeated render changed: " + voice + " " + gesture }
                elapsed
            }
            val edits = listOf(
                "FOLD" to .25f, "FOLD" to .50f, "FOLD" to .90f,
                "MOTION" to 0f, "MOTION" to .50f, "MOTION" to 1f,
            ).map { (control, value) ->
                val changed = probe.copy(macros = probe.macros + (control to value))
                val editStart = System.nanoTime()
                val edited = changed.render()
                val editMs = milliseconds(editStart)
                check(edited.samples.all { it.isFinite() })
                linkedMapOf<String, Any?>("control" to control, "value" to value,
                    "milliseconds" to editMs, "waveformSha256" to waveformSha(edited.samples))
            }
            val editTimes = edits.map { it.getValue("milliseconds") as Double }.sorted()
            add(linkedMapOf("voice" to voice.name, "gesture" to gesture.name, "renderer" to "OrigamiPatch.render",
                "coldKeyMilliseconds" to coldMs, "hotMilliseconds" to hot, "hotMedianMilliseconds" to hot.sorted()[1],
                "warmEdits" to edits, "warmEditMedianMilliseconds" to (editTimes[2] + editTimes[3]) / 2,
                "benchmarkPatch" to probe.toJsonValue(), "waveformSha256" to waveformSha(first.samples),
                "cacheBeforeCold" to before, "cacheAfterCold" to afterCold, "cacheAfterWarmEdits" to cacheStats(),
                "coldMeaning" to "Gesture basis cache explicitly cleared after the musical matrix; JVM and other class state remain warm",
                "jvm" to System.getProperty("java.runtime.version"), "architecture" to System.getProperty("os.arch")))
        }
    }

    private fun cacheStats(): Map<String, Any> = OrigamiGestureRenderer.cacheStats().let {
        mapOf("entryCount" to it.entryCount, "bytes" to it.bytes)
    }

    private fun matchingGain(snip: Snip): Float =
        minOf(TARGET / Loudness.of(snip).coerceAtLeast(1e-9f), CEILING / snip.peak().coerceAtLeast(1e-9f))

    private fun gain(snip: Snip, scale: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * scale }, snip.channels, snip.sampleRate)

    private fun metrics(snip: Snip): Map<String, Any?> {
        var sum = 0.0
        var squares = 0.0
        var nonfinite = 0
        for (sample in snip.samples) if (!sample.isFinite()) nonfinite++ else {
            sum += sample; squares += sample.toDouble() * sample
        }
        return linkedMapOf("peak" to snip.peak(), "wholeFileRms" to sqrt(squares / snip.samples.size.coerceAtLeast(1)),
            "mean" to sum / snip.samples.size.coerceAtLeast(1), "loudness" to Loudness.of(snip), "nonFiniteSamples" to nonfinite)
    }

    private fun curve(patch: OrigamiPatch, seconds: Double): String {
        val points = (0..96).joinToString(" ") { index ->
            val time = seconds * index / 96
            format(300.0 * index / 96) + "," + format(62 - 54 * OrigamiGestures.foldAt(patch, time))
        }
        return "<svg viewBox=\"0 0 300 70\" role=\"img\" aria-label=\"Fold depth over this clip\"><path d=\"M0 62H300\" stroke=\"#49505c\" fill=\"none\"/><polyline points=\"" +
            points + "\" fill=\"none\" stroke=\"#8dd6cf\" stroke-width=\"2\"/><line id=\"playhead\" x1=\"0\" x2=\"0\" y1=\"0\" y2=\"70\" stroke=\"#eecb83\" opacity=\"0.7\"/></svg>"
    }

    private fun sourceFiles(checkout: File): List<File> {
        require(File(checkout, "shell/src/main/kotlin/com/snipsnap/shell/OrigamiPadMaker.kt").isFile) {
            "Run this generator with shell as its working directory"
        }
        val folders = listOf("synth/src/main/kotlin/com/snipsnap/synth", "shell/src/main/kotlin/com/snipsnap/shell",
            "audio/src/main/kotlin/com/snipsnap/audio", "kit/src/main/kotlin/com/snipsnap/kit")
        val names = setOf("Dsp.kt", "Keys.kt", "KeyNote.kt", "InstrumentEngine.kt", "KeyHit.kt", "Loudness.kt", "WavWriter.kt", "InstrumentStore.kt")
        val files = folders.flatMap { path -> File(checkout, path).listFiles().orEmpty().filter { it.name.startsWith("Origami") || it.name in names } } +
            File(checkout, "shell/src/test/kotlin/com/snipsnap/shell/OrigamiGestureAuditionGenerator.kt")
        require(files.any { it.name == "OrigamiGestureRenderer.kt" }) { "Gesture renderer source is missing" }
        return files.distinct().sortedBy { it.relativeTo(checkout).invariantSeparatorsPath }
    }

    private fun readme(manifest: Map<String, Any?>, output: File): String = buildString {
        appendLine("ORIGAMI: a fold in time")
        appendLine()
        appendLine("Open start-here.html first: nine embedded matched C4 clips work without a server or sibling files.")
        appendLine("Open index.html beside native/, matched/ and recipes/ for the complete matrix and native/matched switch.")
        appendLine("Audio is off until Play. New selections stop the previous clip. A/B plays one clip at a time.")
        appendLine()
        appendLine("Why it is built this way")
        why.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("The authored geometry, seed, note and controls are fixed across the C4 gesture comparison.")
        appendLine("STEADY has MOTION 0; BLOOM and UNFOLD have MOTION 0.65. FOLD 0.75, CREASE 0.5, TURN/WARP 0.5.")
        appendLine("In new gestures MOTION means duration, so it is not the old repeating axis motion.")
        appendLine("Held files render exactly one C4 zone per recipe, hold 12 seconds, release 0.60 seconds and keep 0.05 seconds of trailing silence.")
        appendLine("The reference engine follows the phone's KeyHit mapping; this is offline reference playback, not a device recording.")
        appendLine()
        appendLine("Source SHA-256: " + manifest["sourceSha256"])
        appendLine("Recipe matrix SHA-256: " + manifest["recipeSha256"])
        appendLine("Listening round: " + manifest["listeningRound"])
        appendLine("Native and matched WAVs are mono 44.1 kHz PCM16. Float hashes describe the pre-quantized waveforms; file hashes cover exact WAV bytes.")
        appendLine("Source snapshots and complete per-clip recipes accompany the manifest. Timings use this JVM, include no phone-latency claim, and are not musical verdicts.")
        appendLine("Audio files total " + manifest["audioBytes"] + " bytes. Output: " + output.name)
    }

    private fun note(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")[midi % 12] + (midi / 12 - 1)
    private fun format(value: Double) = "%.3f".format(Locale.ROOT, value)
    private fun milliseconds(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun waveformSha(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return sha(bytes.array())
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported manifest value " + value.javaClass)
    }

    private fun page(manifest: Map<String, Any?>, embedded: Map<String, String>, compact: Boolean): String =
        HTML.replace("__MANIFEST__", Json.write(json(manifest)).replace("<", "\\u003c"))
            .replace("__EMBEDDED__", Json.write(json(embedded)).replace("<", "\\u003c"))
            .replace("__COMPACT__", compact.toString())

    private val HTML = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ORIGAMI · A fold in time</title>
<style>
:root{color-scheme:dark;--bg:#111419;--panel:#191e26;--line:#343c49;--text:#e6e9ee;--dim:#adb6c5;--accent:#8dd6cf;--warm:#eecb83}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif}main{max-width:1080px;margin:auto;padding:32px 20px 80px}h1{font-size:clamp(30px,6vw,48px);line-height:1.15;margin:12px 0}h2{font-size:20px;margin-top:0}p{max-width:78ch}.eyebrow{font:12px ui-monospace,monospace;letter-spacing:.12em;color:var(--accent)}.dim,small{color:var(--dim)}button,select,textarea{font:inherit;color:inherit;background:#222a35;border:1px solid var(--line);border-radius:7px}button,select{min-height:44px;padding:8px 13px}button{cursor:pointer}button:hover{border-color:var(--accent)}button:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--warm);outline-offset:2px}button.active,.play{background:#233e3e;border-color:var(--accent)}a{color:var(--accent)}.row{display:flex;flex-wrap:wrap;gap:10px;align-items:center}.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:20px;margin:22px 0}.transport{position:sticky;top:0;z-index:2;border-color:#557b79}.transport h2{margin:0 0 4px}.status{min-height:24px;color:var(--warm);overflow-wrap:anywhere}audio{width:100%;margin:10px 0}.ab{display:grid;grid-template-columns:auto minmax(0,1fr) auto minmax(0,1fr);gap:8px}.ab select{min-width:0;width:100%}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px}.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px}.card.selected{border-color:var(--accent)}.card h3{font-size:17px;margin:0 0 6px}.card p{font-size:14px;margin:7px 0 14px}.card .row button{flex:1}svg{display:block;width:100%;height:75px;margin:12px 0}.timing{font:13px/1.7 ui-monospace,monospace;color:var(--dim)}.filters{margin:24px 0 16px}details{margin-top:20px}summary{cursor:pointer;min-height:40px}pre{font:12px/1.5 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere;background:#11161d;padding:14px;border-radius:7px;max-height:420px;overflow:auto}textarea{width:100%;min-height:88px;padding:10px}ul{padding-left:24px}.legend{display:flex;justify-content:space-between;font-size:12px;color:var(--dim)}#mode{margin-left:auto}#openBrowser[hidden]{display:none}@media(max-width:580px){main{padding:22px 12px 50px}.panel{padding:14px}.transport{position:static}.ab{grid-template-columns:auto minmax(0,1fr)}.grid{grid-template-columns:1fr}#mode{margin-left:0}}
</style></head><body><main>
<div class="eyebrow">SNIPSNAP / ORIGAMI / GESTURE STUDY</div>
<h1>A fold in time.</h1>
<p>BLOOM starts open and reaches the chosen fold. UNFOLD starts folded and opens. Compare them with STEADY, then try a longer hold.</p>
<p class="dim" id="edition"></p>
<section class="panel transport" aria-label="Audio player">
<h2 id="now">Choose a clip</h2><div class="dim" id="caption"></div>
<div class="row"><button class="play" id="play">Play selected</button><button id="stop">Stop</button><label id="modeLabel">Level <select id="mode"><option value="matched">Matched</option><option value="native">Native</option></select></label></div>
<audio id="audio" controls preload="none" playsinline></audio><div class="status" id="status" role="status">Audio is off. Choose Play to begin.</div>
<a id="openBrowser" hidden target="_blank" rel="noopener">Open this page in your browser</a>
<div class="ab"><button id="playA">A</button><select id="a" aria-label="Comparison A"></select><button id="playB">B</button><select id="b" aria-label="Comparison B"></select></div>
<div id="curve"></div><div class="legend"><span>Fold depth · one shared clip gain</span><span id="length"></span></div><div id="timing" class="timing"></div>
</section>
<div class="row filters"><label>Show <select id="category"><option value="featured">Start at C4</option><option value="speed">Gesture speed</option><option value="register">C3 / C5</option><option value="held">12-second holds</option><option value="all">All clips</option></select></label><label>Voice <select id="voice"><option value="all">All voices</option><option>CRANE</option><option>FAN</option><option>STILL</option></select></label></div>
<div id="cards" class="grid"></div>
<section class="panel"><h2>Listening notes</h2><p class="dim">Notes save in this browser for the selected recipe. A meter cannot decide whether the gesture reads clearly or which voice you prefer.</p><textarea id="notes" aria-label="Notes for selected clip" placeholder="Does the change read clearly? Does the held body settle where you expected?"></textarea><div class="row"><button id="export">Export notes</button><a id="download">Download selected WAV</a></div></section>
<section class="panel"><h2>Why it is built this way</h2><ul id="why"></ul><details><summary>Recipe, levels and render evidence</summary><pre id="recipe"></pre><pre id="bench"></pre><a id="manifestLink">Download manifest</a></details></section>
<p class="dim">No listening verdict has been assigned. Full recipes, source fingerprints and whole-clip level gains travel with this experiment.</p>
</main>
<script id="manifest" type="application/json">__MANIFEST__</script>
<script id="embedded" type="application/json">__EMBEDDED__</script>
<script>
'use strict';
const manifest=JSON.parse(document.getElementById('manifest').textContent);
const embedded=JSON.parse(document.getElementById('embedded').textContent),compact=__COMPACT__;
const clips=manifest.clips,byId=new Map(clips.map(c=>[c.id,c]));
const el=id=>document.getElementById(id),audio=el('audio');
let selected=clips.find(c=>c.id==='main_fan_bloom_60')||clips[0],mode='matched',token=0,watchdog=0,playingId=null;
const noteKey=c=>'origami-gesture:'+manifest.sourceSha256+':'+c.recipeSha256;
function readNote(c){try{return localStorage.getItem(noteKey(c))||'';}catch(e){return '';}}
function status(text){el('status').textContent=text;}
function source(c){const p=mode==='native'?c.nativePath:c.matchedPath;return embedded[p]||p;}
function stop(){token++;clearTimeout(watchdog);audio.pause();try{audio.currentTime=0;}catch(e){}playingId=null;status('Stopped.');}
function choose(id){selected=byId.get(id);drawSelected();drawCards();}
function play(id){stop();choose(id);const current=++token;playingId=id;audio.preload='auto';status('Loading '+selected.title+'…');
try{if(navigator.audioSession)navigator.audioSession.type='playback';}catch(e){}
watchdog=setTimeout(()=>{if(current===token){status('Playback has not started. Use the player controls; keep the full page beside its audio folders.');}},12000);
try{const result=audio.play();if(result&&result.catch)result.catch(e=>{if(current!==token)return;clearTimeout(watchdog);status(e.name==='NotAllowedError'?'The browser blocked playback. Use the player’s Play control or open this page in your browser.':'Could not play this WAV: '+e.message);});}
catch(e){if(current===token){clearTimeout(watchdog);status('Could not start audio: '+e.message);}}}
audio.addEventListener('playing',()=>{clearTimeout(watchdog);status((audio.muted||audio.volume===0?'Playing with volume at zero: ':'Playing: ')+(byId.get(playingId)||selected).title);});
audio.addEventListener('ended',()=>{clearTimeout(watchdog);status('Finished.');playingId=null;});
audio.addEventListener('error',()=>{clearTimeout(watchdog);const code=audio.error&&audio.error.code;status(code===2?'Audio could not load. Keep index.html beside native/ and matched/, or use the self-contained starter.':code===3?'This browser could not decode the WAV. Try downloading it.':'Audio is unavailable. Use the complete pack or the self-contained starter.');});
audio.addEventListener('timeupdate',()=>{const marker=el('curve').querySelector('#playhead');if(marker){const x=300*Math.min(1,audio.currentTime/selected.durationSeconds);marker.setAttribute('x1',x);marker.setAttribute('x2',x);}});
function drawSelected(){if(audio.dataset.clip!==selected.id||audio.dataset.mode!==mode){audio.src=source(selected);audio.preload='none';audio.dataset.clip=selected.id;audio.dataset.mode=mode;}
el('now').textContent=selected.title;el('caption').textContent=selected.description;el('curve').innerHTML=selected.foldCurveSvg;el('length').textContent=selected.durationSeconds.toFixed(2)+' s';
el('timing').textContent=selected.gesture==='STEADY'?'STEADY · axis motion off · FOLD '+Math.round(selected.maximumFold*100)+'%':'Change begins '+selected.gestureStartSeconds.toFixed(3)+' s · lasts '+selected.gestureDurationSeconds.toFixed(3)+' s · settles at '+selected.gestureEndSeconds.toFixed(3)+' s'+(selected.held?' · note-off at 12.00 s · release 0.60 s':'');
el('notes').value=readNote(selected);el('recipe').textContent=JSON.stringify({recipe:selected.recipe,matchedGain:selected.matchedGain,nativeMetrics:selected.nativeMetrics,matchedMetrics:selected.matchedMetrics,rawBeforeOutputMetrics:selected.rawBeforeOutputMetrics,renderTiming:selected.renderTiming,heldEvidence:selected.heldEvidence,recipeSha256:selected.recipeSha256,nativeFileSha256:selected.nativeFileSha256,matchedFileSha256:selected.matchedFileSha256},null,2);
el('download').href=source(selected);el('download').download=selected.id+'-'+mode+'.wav';}
function drawCards(){const category=el('category').value,voice=el('voice').value;el('cards').replaceChildren();clips.filter(c=>(category==='all'||c.category===category)&&(voice==='all'||c.voice===voice)).forEach(c=>{
const card=document.createElement('article');card.className='card'+(c.id===selected.id?' selected':'');
const title=document.createElement('h3');title.textContent=c.title;const desc=document.createElement('p');desc.className='dim';desc.textContent=c.description;
const line=document.createElement('div');line.className='row';const p=document.createElement('button');p.textContent='Play';p.onclick=()=>play(c.id);const inspect=document.createElement('button');inspect.textContent='Select';inspect.onclick=()=>{stop();choose(c.id);};line.append(p,inspect);
card.append(title,desc,line);el('cards').append(card);});}
function saveFile(name,value){const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
el('play').onclick=()=>play(selected.id);el('stop').onclick=stop;
el('mode').onchange=()=>{stop();mode=el('mode').value;drawSelected();};
el('category').onchange=drawCards;el('voice').onchange=drawCards;
for(const key of ['a','b']){for(const c of clips){const o=document.createElement('option');o.value=c.id;o.textContent=c.title;el(key).append(o);}}
el('a').value=(clips.find(c=>c.id==='main_fan_steady_60')||clips[0]).id;el('b').value=selected.id;
el('playA').onclick=()=>play(el('a').value);el('playB').onclick=()=>play(el('b').value);
el('notes').oninput=()=>{try{localStorage.setItem(noteKey(selected),el('notes').value);}catch(e){status('This browser could not save notes. Export them before closing.');}};
el('export').onclick=()=>saveFile('origami-gesture-feedback.json',{listeningRound:manifest.listeningRound,sourceSha256:manifest.sourceSha256,recipeSha256:manifest.recipeSha256,exportedUtc:new Date().toISOString(),notes:clips.map(c=>({id:c.id,recipeSha256:c.recipeSha256,nativeFileSha256:c.nativeFileSha256,matchedFileSha256:c.matchedFileSha256,note:readNote(c)})).filter(c=>c.note)});
for(const text of manifest.whyItIsBuiltThisWay){const li=document.createElement('li');li.textContent=text;el('why').append(li);}
el('bench').textContent=manifest.benchmarkPolicy+'\n\n'+JSON.stringify(manifest.benchmarks,null,2);
const manifestUrl=URL.createObjectURL(new Blob([JSON.stringify(manifest,null,2)],{type:'application/json'}));el('manifestLink').href=manifestUrl;el('manifestLink').download='origami-gesture-manifest.json';
if(compact){el('edition').textContent='Self-contained starter · nine matched C4 clips. The full pack adds speed, register and held-note comparisons.';el('modeLabel').hidden=true;for(const option of Array.from(el('category').options)){if(!['featured','all'].includes(option.value))option.remove();}}
else el('edition').textContent='Full study · '+clips.length+' clips. Start with the C4 trio; compare Native and Matched levels if the balance itself is the question.';
try{if(window.self!==window.top){el('openBrowser').hidden=false;el('openBrowser').href=window.location.href;}}catch(e){}
drawSelected();drawCards();
</script></body></html>
""".trimIndent()
}
