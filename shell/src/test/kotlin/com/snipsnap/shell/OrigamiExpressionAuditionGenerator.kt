package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiExpression
import com.snipsnap.synth.OrigamiExpressions
import com.snipsnap.synth.OrigamiDiagnostic
import com.snipsnap.synth.OrigamiGesture
import com.snipsnap.synth.OrigamiGestureRenderer
import com.snipsnap.synth.OrigamiGestures
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiVoice
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Strike-expression listening study. Finite recipes use actual performed velocity;
 * held takes read the maker's complete exported three-layer banks through InstrumentEngine.
 * Existing gesture generators and legacy recipes remain separate evidence.
 */
object OrigamiExpressionAuditionGenerator {
    private const val RATE = 44_100
    private const val SEED = 20261012L
    private const val MOTION = .65f
    private const val TARGET = .12f
    private const val CEILING = .90f
    private const val HOLD_SECONDS = 12.0
    private const val RELEASE_SECONDS = .6f
    private const val BASE_COMMIT_READ = "df675d00426486ce301c7a742b4a7691baf30af8"

    private data class Case(
        val id: String, val category: String, val title: String, val description: String,
        val patch: OrigamiPatch, val featured: Boolean = false, val held: Boolean = false,
        val strike: Float = patch.velocity,
    )

    private data class Rendered(
        val native: Snip, val raw: Snip?, val timing: Map<String, Any?>,
        val heldEvidence: Map<String, Any?> = emptyMap(),
    )

    private data class HeldBank(
        val spec: OrigamiPadMaker.Spec, val loaded: InstrumentEngine.Loaded,
        val evidence: Map<String, Any?>,
    )

    private val banks = linkedMapOf<OrigamiVoice, HeldBank>()
    private val velocities = listOf(.3f, .6f, 1f)
    private val why = listOf(
        "LEVEL keeps the accepted dynamics. FOLD also changes folded color: effective FOLD = authored FOLD × (0.25 + 0.75 × strike^1.25). A full-strength strike takes the exact old path.",
        "The same source, root, gesture clock and geometry let strike-driven color be compared without adding pitch bends or extra motion.",
        "Matched files use one gain for the entire clip. Matching removes overall level differences, while preserving the changing balance inside each note.",
        "Held instruments store three real sample layers under the saved velocity ceiling. Softer layers are peak-matched to the hard layer; the player then applies key velocity gain once.",
        "Finite pads need the existing SOFT HITS workflow to export strike layers. SEND TO PAD alone captures one rendered hit. Measurements do not decide the listening verdict.",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory" }
        val output = File(args.firstOrNull() ?: "../testkit/origami-expression").absoluteFile
        require(output.isDirectory || output.mkdirs()) { "Cannot create " + output }
        val checkout = File("..").canonicalFile
        val sources = sourceFiles(checkout)
        val hashes = sources.associate { it.relativeTo(checkout).invariantSeparatorsPath to sha(it.readBytes()) }.toSortedMap()
        val sourceHash = sha(hashes.entries.joinToString("\n") { it.key + ":" + it.value }.toByteArray())
        val cases = cases()
        check(cases.map { it.id }.distinct().size == cases.size)
        val recipes = cases.associate { it.id to recipe(it) }
        val recipeHash = sha(Json.write(json(recipes)).toByteArray())
        val round = "origami-expression-" + OrigamiGestures.MODEL_VERSION + "-" + sourceHash.take(12) + "-" + recipeHash.take(12)
        val started = System.nanoTime()
        banks.clear()
        val instrumentOutput = File(output.parentFile, output.name + "-instruments")
        val instrumentEvidence = prepareBanks(instrumentOutput, sourceHash)
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
            val applied = appliedFor(case)
            val start = if (case.patch.gesture == OrigamiGesture.STEADY) 0.0 else OrigamiGestures.startSeconds(case.patch.voice)
            val duration = if (case.patch.gesture == OrigamiGesture.STEADY) 0.0 else
                OrigamiGestures.durationSeconds(case.patch.voice, case.patch.resolvedMacros().getValue("MOTION"))
            val midi = requireNotNull(case.patch.midi)
            linkedMapOf<String, Any?>(
                "id" to case.id, "category" to case.category, "title" to case.title,
                "description" to case.description, "voice" to case.patch.voice.name,
                "gesture" to case.patch.gesture.name, "expression" to case.patch.expression.name, "midi" to midi,
                "note" to note(midi), "authoredHz" to 440.0 * 2.0.pow((midi - 69) / 12.0),
                "featured" to case.featured, "held" to case.held, "recipe" to recipes.getValue(case.id),
                "recipePath" to recipePath, "recipeSha256" to sha(File(output, recipePath).readBytes()),
                "geometryModel" to case.patch.model, "gestureModel" to OrigamiGestures.MODEL_VERSION,
                "macros" to case.patch.resolvedMacros(), "seed" to case.patch.seed.toString(), "velocity" to case.strike,
                "authoredVelocityCeiling" to if (case.held) case.patch.velocity else null,
                "expressionModel" to OrigamiExpressions.MODEL_VERSION, "appliedPatch" to applied.toJsonValue(),
                "effectiveFold" to applied.resolvedMacros().getValue("FOLD"),
                "frames" to rendered.native.frameCount, "durationSeconds" to seconds, "sampleRate" to RATE,
                "gestureStartSeconds" to start, "gestureDurationSeconds" to duration,
                "gestureEndSeconds" to OrigamiGestures.endSeconds(case.patch),
                "maximumFold" to case.patch.resolvedMacros().getValue("FOLD"),
                "settledFold" to OrigamiGestures.foldAt(applied, maxOf(seconds, start + duration)),
                "foldCurveSvg" to curve(applied, seconds), "renderTiming" to rendered.timing,
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
        val identities = verifyIdentityReferences(clips)
        val benchmarks = benchmarks()
        val ceilingProbe = ceilingProbe()
        check(sources.all { sha(it.readBytes()) == hashes.getValue(it.relativeTo(checkout).invariantSeparatorsPath) }) {
            "Source files changed during rendering; rerun after the source tree is stable"
        }
        for (file in sources) file.copyTo(File(output, "sources/" + file.relativeTo(checkout).invariantSeparatorsPath).apply { parentFile.mkdirs() }, overwrite = true)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "ORIGAMI · Strike and fold",
            "createdUtc" to Instant.now().toString(), "listeningRound" to round,
            "baseCommitRead" to BASE_COMMIT_READ, "sourceSha256" to sourceHash, "sourceFiles" to hashes,
            "recipeSha256" to recipeHash, "geometryModel" to Origami.MODEL_VERSION,
            "gestureModel" to OrigamiGestures.MODEL_VERSION, "expressionModel" to OrigamiExpressions.MODEL_VERSION, "clipCount" to clips.size,
            "sampleRate" to RATE, "bitDepth" to 16, "diagnostic" to OrigamiDiagnostic.COMBINED.name,
            "matching" to mapOf("targetLoudness" to TARGET, "peakCeiling" to CEILING,
                "meter" to "Loudness.of: loudest 200 ms window with the house low-cut weighting",
                "policy" to "One gain per complete native clip; both channels and all gesture times share that gain. No endpoint normalization."),
            "nativePolicy" to "Finite: OrigamiPatch.render at actual strike velocity, including house output. Held: actual exported three-layer samples read back through InstrumentEngine; mono left channel, identical to right.",
            "expressionLaw" to "FOLD = authored FOLD * (0.25 + 0.75 * velocity^1.25); LEVEL retains accepted behavior; velocity 1 is exact identity",
            "heldLayerPolicy" to "Sample render velocity = saved authored ceiling * relative layer (0.3/0.6/1). Softer complete Snips are peak-matched to hard; playback gain = performed strike * VOICE_LEVEL once.",
            "finitePadPolicy" to "Use existing SOFT HITS for finite layered export; ordinary SEND TO PAD stores one rendered hit.",
            "instruments" to instrumentEvidence, "instrumentArtifactDirectoryName" to instrumentOutput.name,
            "instrumentTotals" to mapOf(
                "pitchZoneCount" to instrumentEvidence.sumOf { (it.getValue("pitchZoneCount") as Number).toInt() },
                "sampleCount" to instrumentEvidence.sumOf { (it.getValue("sampleCount") as Number).toInt() },
                "frameCount" to instrumentEvidence.sumOf { (it.getValue("frameCount") as Number).toLong() },
                "floatResidentBytes" to instrumentEvidence.sumOf { (it.getValue("floatResidentBytes") as Number).toLong() },
                "exportedWavDiskBytes" to instrumentEvidence.sumOf { (it.getValue("exportedWavDiskBytes") as Number).toLong() },
                "pcm16EncodedBytesMeasured" to instrumentEvidence.sumOf { (it.getValue("pcm16EncodedBytesMeasured") as Number).toLong() },
            ),
            "identityReferences" to identities, "savedCeilingProbe" to ceilingProbe,
            "pitchPolicy" to "MIDI is authored. No detector result or numerical test is presented as perceived-pitch approval.",
            "benchmarkPolicy" to "Post-matrix JVM wall time: clear the gesture basis cache, render once, repeat three times, then measure strike/FOLD/MOTION edits sharing geometry. Not process-cold or a phone benchmark.",
            "whyItIsBuiltThisWay" to why,
            "verificationLedger" to mapOf(
                "verifiedByGenerator" to listOf("finite native output is finite and inside PCM range", "one common gain per clip", "LEVEL/FOLD velocity-1 references are float-and-WAV identical", "real 27-sample instrument exports read back", "repeated benchmark renders have identical float hashes", "source files stable during generation"),
                "measured" to "Per-clip levels, hashes and timings in this manifest; reference key playback for held files",
                "proposed" to "The expression law and three layer thresholds are authored choices awaiting listening",
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
            put("edition", "Nine matched C4 strike comparisons")
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

    private fun patch(voice: OrigamiVoice, gesture: OrigamiGesture = OrigamiGesture.BLOOM,
        midi: Int = 60, velocity: Float = 1f, expression: OrigamiExpression = OrigamiExpression.FOLD): OrigamiPatch =
        OrigamiPatch(voice.name + " " + gesture.name + " " + expression.name, voice,
            Origami.defaults(voice) + mapOf("CREASE" to .5f, "FOLD" to .75f, "TURN" to .5f, "WARP" to .5f,
                "MOTION" to if (gesture == OrigamiGesture.STEADY) 0f else MOTION),
            midi = midi, velocity = velocity, seed = SEED, model = 4, gesture = gesture, expression = expression)

    private fun strikeId(velocity: Float) = (velocity * 100).toInt().toString()

    private fun cases(): List<Case> = buildList {
        for (voice in OrigamiVoice.entries) {
            for (velocity in velocities) add(Case("main_" + voice.name.lowercase() + "_bloom_v" + strikeId(velocity),
                "featured", voice.name + " · strike " + strikeId(velocity) + "% · BLOOM",
                "C4; FOLD expression, authored FOLD 75%, MOTION 65%. Matched mode compares color; Native also keeps dynamics.",
                patch(voice, velocity = velocity), featured = true))
            for (gesture in listOf(OrigamiGesture.STEADY, OrigamiGesture.UNFOLD)) for (velocity in listOf(.3f, 1f)) {
                add(Case("gesture_" + voice.name.lowercase() + "_" + gesture.name.lowercase() + "_v" + strikeId(velocity),
                    "gestures", voice.name + " · " + gesture.name + " · strike " + strikeId(velocity) + "%",
                    "C4; source, root and gesture timing retained. STEADY comparisons have axis motion off.", patch(voice, gesture, velocity = velocity)))
            }
            add(Case("identity_" + voice.name.lowercase() + "_level_v100", "identity", voice.name + " · LEVEL · strike 100%",
                "Exact identity reference for FOLD expression at strike 100%, including raw floats and exported WAV bytes.",
                patch(voice, expression = OrigamiExpression.LEVEL)))
        }
        for ((voice, gesture, midi) in listOf(
            Triple(OrigamiVoice.FAN, OrigamiGesture.BLOOM, 48),
            Triple(OrigamiVoice.STILL, OrigamiGesture.UNFOLD, 72),
            Triple(OrigamiVoice.CRANE, OrigamiGesture.STEADY, 72),
        )) {
            val strike = if (voice == OrigamiVoice.STILL) .6f else .3f
            add(Case("register_" + voice.name.lowercase() + "_" + gesture.name.lowercase() + "_" + midi, "register",
                voice.name + " · " + note(midi) + " · strike " + strikeId(strike) + "%",
                "Selected authored C3/C5 checks, with the same geometry and source seed.", patch(voice, gesture, midi, strike)))
        }
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) for (velocity in velocities) {
            add(Case("held_" + voice.name.lowercase() + "_bloom_v" + strikeId(velocity), "held",
                voice.name + " · held C4 · strike " + strikeId(velocity) + "%",
                "Actual three-layer exported instrument, saved ceiling 100%. Hold 12 s, then release 0.60 s; 0.05 s trailing silence.",
                patch(voice), held = true, strike = velocity))
        }
    }.also { check(it.size == 33) }

    private fun recipe(case: Case): Map<String, Any?> = linkedMapOf(
        "kind" to if (case.held) "held-exported-layer-playback" else "finite-patch",
        "patch" to case.patch.toJsonValue(), "diagnostic" to OrigamiDiagnostic.COMBINED.name,
        "heldSpec" to if (case.held) Json.parse(OrigamiPadMaker.Spec(case.patch, RELEASE_SECONDS).toJsonText()) else null,
        "playback" to if (case.held) mapOf("midi" to 60, "performedVelocity" to case.strike,
            "authoredVelocityCeiling" to case.patch.velocity, "noteOnSeconds" to 0, "noteOffSeconds" to HOLD_SECONDS,
            "releaseSeconds" to RELEASE_SECONDS, "postReleaseSilenceSeconds" to .05,
            "referenceEngine" to "InstrumentEngine", "voiceLevel" to InstrumentEngine.VOICE_LEVEL,
            "layerCount" to 3, "relativeLayerVelocities" to OrigamiPadMaker.RELATIVE_VELOCITIES) else null,
    )

    private fun appliedFor(case: Case): OrigamiPatch {
        if (!case.held) return OrigamiExpressions.appliedPatch(case.patch)
        val midiVelocity = (case.strike * 127).roundToInt().coerceIn(1, 127)
        val index = OrigamiPadMaker.VELOCITY_WINDOWS.indexOfFirst { midiVelocity in it }
        check(index >= 0)
        return OrigamiExpressions.appliedPatch(case.patch.copy(velocity =
            OrigamiExpressions.relativeVelocity(case.patch, OrigamiPadMaker.RELATIVE_VELOCITIES[index])))
    }

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
        val bank = banks.getValue(case.patch.voice)
        val engine = InstrumentEngine(bank.loaded, RATE)
        val hit = requireNotNull(KeyHit.resolve(bank.loaded.instrument, 60, case.strike) { bank.loaded.layerSamples[it]?.size?.toLong() })
        val zone = requireNotNull(bank.loaded.instrument.zoneFor(60))
        val selected = requireNotNull(zone.layerFor((case.strike * 127).roundToInt().coerceIn(1, 127)))
        val selectedSamples = bank.loaded.layerSamples.getValue(selected.sample)
        val held = (HOLD_SECONDS * RATE).toInt()
        val release = ceil(RELEASE_SECONDS.toDouble() * RATE).toInt()
        val samples = FloatArray(held + release + RATE / 20)
        val buffer = FloatArray(512 * 2)
        check(engine.noteOn(60, case.strike))
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
            mapOf("exportedInstrumentBank" to case.patch.voice.name, "referencePlaybackMilliseconds" to milliseconds(playbackStart)),
            mapOf("pitchZoneCount" to 9, "samplesPerZone" to bank.spec.layerCount, "selectedZoneRootMidi" to 60,
                "selectedLayer" to selected.sample, "midiVelocity" to (case.strike * 127).roundToInt(),
                "selectedLayerWindow" to listOf(selected.velStart, selected.velEnd), "playerGain" to hit.gain,
                "selectedLayerFrames" to selected.frameCount, "loopStartFrame" to selected.loopStartFrame,
                "selectedLayerWaveformSha256" to waveformSha(selectedSamples), "selectedLayerMetrics" to metrics(Snip(selectedSamples, 1, RATE)),
                "noteOffFrame" to held, "releaseFrames" to release,
                "settledPatch" to OrigamiGestures.settledPatch(appliedFor(case)).toJsonValue(),
                "playbackNote" to "Actual exported PCM24 samples and sidecar through InstrumentEngine, not a device recording. The player applies velocity gain once."))
    }

    private fun benchmarks(): List<Map<String, Any?>> = buildList {
        for (voice in OrigamiVoice.entries) {
            val gesture = OrigamiGesture.BLOOM
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
                "VELOCITY" to .3f, "VELOCITY" to .6f, "VELOCITY" to 1f,
                "FOLD" to .25f, "FOLD" to .50f, "FOLD" to .90f,
                "MOTION" to 0f, "MOTION" to .50f, "MOTION" to 1f,
            ).map { (control, value) ->
                val changed = if (control == "VELOCITY") probe.copy(velocity = value)
                    else probe.copy(macros = probe.macros + (control to value))
                val editStart = System.nanoTime()
                val edited = changed.render()
                val editMs = milliseconds(editStart)
                check(edited.samples.all { it.isFinite() })
                linkedMapOf<String, Any?>("control" to control, "value" to value,
                    "milliseconds" to editMs, "waveformSha256" to waveformSha(edited.samples))
            }
            val editTimes = edits.map { it.getValue("milliseconds") as Double }.sorted()
            add(linkedMapOf("voice" to voice.name, "gesture" to gesture.name, "expression" to probe.expression.name, "renderer" to "OrigamiPatch.render",
                "coldKeyMilliseconds" to coldMs, "hotMilliseconds" to hot, "hotMedianMilliseconds" to hot.sorted()[1],
                "warmEdits" to edits, "warmEditMedianMilliseconds" to editTimes[editTimes.size / 2],
                "geometryModel" to probe.model, "expressionModel" to OrigamiExpressions.MODEL_VERSION, "benchmarkPatch" to probe.toJsonValue(), "waveformSha256" to waveformSha(first.samples),
                "cacheBeforeCold" to before, "cacheAfterCold" to afterCold, "cacheAfterWarmEdits" to cacheStats(),
                "coldMeaning" to "Gesture basis cache explicitly cleared after the musical matrix; JVM and other class state remain warm",
                "jvm" to System.getProperty("java.runtime.version"), "architecture" to System.getProperty("os.arch")))
        }
    }

    /** Export real programs outside the listening site, then audition their decoded samples. */
    private fun prepareBanks(output: File, sourceHash: String): List<Map<String, Any?>> {
        output.mkdirs()
        val evidence = listOf(OrigamiVoice.FAN, OrigamiVoice.STILL).map { voice ->
            val spec = OrigamiPadMaker.Spec(patch(voice), RELEASE_SECONDS)
            check(spec.layerCount == 3)
            val roots = OrigamiPadMaker.zoneMidis(spec)
            val renderStart = System.nanoTime()
            val notes = roots.map { midi ->
                println("Rendering instrument " + voice.name + " root " + midi + " × " + spec.layerCount + " layers")
                OrigamiPadMaker.renderVelocityZone(spec, midi)
            }
            val renderMs = milliseconds(renderStart)
            val center = notes[roots.indexOf(60)]
            val old = OrigamiPadMaker.renderZone(spec.copy(patch = spec.patch.copy(expression = OrigamiExpression.LEVEL)), 60)
            check(center.last().snip.samples.contentEquals(old.snip.samples) && center.last().loopStartFrame == old.loopStartFrame) {
                "Held full-strength FOLD differs from LEVEL: " + voice
            }
            val directory = File(output, voice.name).apply { mkdirs() }
            val name = voice.name + " STRIKE"
            val exportStart = System.nanoTime()
            OrigamiPadMaker.exportLayers(name, spec, notes, directory, overwrite = true)
            val exportMs = milliseconds(exportStart)
            val sidecar = InstrumentStore.sidecar(directory, name)
            val instrument = InstrumentStore.read(sidecar)
            check(instrument.zones.size == 9 && instrument.sampleFiles.size == 27)
            val readStart = System.nanoTime()
            var pcm16Bytes = 0L
            val decoded = instrument.sampleFiles.associateWith { path ->
                val file = File(directory, path)
                val snip = WavReader.read(file)
                check(snip.channels == 1 && snip.sampleRate == RATE)
                val counter = ByteCounter()
                WavWriter.write(counter, snip, WavWriter.BitDepth.PCM_16, smpl = WavReader.readSmpl(file))
                pcm16Bytes += counter.bytes
                snip.samples
            }
            val loaded = InstrumentEngine.Loaded(instrument, RATE, decoded)
            val fileRows = directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.map {
                mapOf("path" to it.relativeTo(directory).invariantSeparatorsPath, "bytes" to it.length(), "sha256" to sha(it.readBytes()))
            }.toList()
            val layers = instrument.zones.flatMap { zone -> zone.playableLayers.mapIndexed { index, layer ->
                val file = File(directory, layer.sample)
                val bytes = file.readBytes()
                val bits = (bytes[34].toInt() and 255) or ((bytes[35].toInt() and 255) shl 8)
                check(bits == 24) { "Production export bit depth changed; update its disclosure: " + file }
                mapOf("rootMidi" to zone.rootNote, "layerIndex" to index,
                    "relativeVelocity" to OrigamiPadMaker.RELATIVE_VELOCITIES[index],
                    "renderVelocity" to OrigamiExpressions.relativeVelocity(spec.patch, OrigamiPadMaker.RELATIVE_VELOCITIES[index]),
                    "velocityWindow" to listOf(layer.velStart, layer.velEnd), "frames" to layer.frameCount,
                    "loopStartFrame" to layer.loopStartFrame, "file" to layer.sample, "bitDepth" to bits,
                    "fileBytes" to file.length(), "fileSha256" to sha(bytes),
                    "decodedWaveformSha256" to waveformSha(decoded.getValue(layer.sample)))
            } }
            val frames = decoded.values.sumOf { it.size.toLong() }
            val hardFrames = instrument.zones.sumOf { it.frameCount }
            val row = linkedMapOf<String, Any?>(
                "voice" to voice.name, "artifactDirectoryName" to output.name, "instrumentName" to name,
                "sourceSha256" to sourceHash, "spec" to Json.parse(spec.toJsonText()),
                "pitchZoneCount" to instrument.zones.size, "layersPerZone" to spec.layerCount,
                "sampleCount" to instrument.sampleFiles.size, "frameCount" to frames,
                "hardLayerOnlyFrameCount" to hardFrames, "floatResidentBytes" to frames * 4,
                "floatResidentScope" to "Unique decoded sample arrays; excludes object overhead, temporary render arrays and geometry cache",
                "exportedWavBitDepth" to 24,
                "exportedWavDiskBytes" to instrument.sampleFiles.sumOf { File(directory, it).length() },
                "pcm16EncodedBytesMeasured" to pcm16Bytes,
                "pcm16Meaning" to "Measured by running the PCM16 WAV encoder into a counting stream with the same sampler sheets; stored production exports remain PCM24",
                "packageDiskBytes" to directory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                "renderMilliseconds" to renderMs, "exportMilliseconds" to exportMs,
                "readBackAndEvidenceMilliseconds" to milliseconds(readStart),
                "heldHardLayerMatchesLevelAtOne" to true, "layers" to layers, "files" to fileRows,
            )
            banks[voice] = HeldBank(spec, loaded, row)
            row
        }
        File(output, "manifest.json").writeText(Json.write(json(mapOf("sourceSha256" to sourceHash, "instruments" to evidence))) + "\n")
        File(output, "README.txt").writeText(
            "ORIGAMI FOLD expression instruments\nTwo actual nine-root, three-layer production exports (27 WAVs per instrument).\n" +
                "Use .xty for MPC 3 or the .xpm inside TrackData for MPC 2.\n" +
                "Saved patch velocity is the authored hard-layer ceiling; softer render velocities multiply that ceiling by 0.3 and 0.6.\n" +
                "MIDI windows 1–42 / 43–84 / 85–127 select the layers. The player supplies strike velocity gain once.\n" +
                "Production WAVs are PCM24; the manifest separately counts PCM16 equivalent encoding and decoded float storage.\n" +
                "This is an export and reference-player experiment, with no device or listening-quality claim.\n")
        return evidence
    }

    private class ByteCounter : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) { bytes++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes += length }
    }

    private fun verifyIdentityReferences(clips: List<Map<String, Any?>>): List<Map<String, Any?>> =
        OrigamiVoice.entries.map { voice ->
            val folded = clips.single { it["id"] == "main_" + voice.name.lowercase() + "_bloom_v100" }
            val level = clips.single { it["id"] == "identity_" + voice.name.lowercase() + "_level_v100" }
            val keys = listOf("rawBeforeOutputWaveformSha256", "nativeWaveformSha256", "matchedWaveformSha256", "nativeFileSha256", "matchedFileSha256")
            check(keys.all { folded[it] == level[it] }) { "Velocity-1 FOLD is not exact LEVEL identity: " + voice }
            mapOf("voice" to voice.name, "foldClipId" to folded.getValue("id"), "levelClipId" to level.getValue("id"),
                "exactRawNativeMatchedAndWavIdentity" to true, "nativeFileSha256" to folded.getValue("nativeFileSha256"))
        }

    /** The saved ceiling is not silently replaced with a full-strength sample during export. */
    private fun ceilingProbe(): Map<String, Any?> {
        val patch = patch(OrigamiVoice.FAN, velocity = .42f)
        val spec = OrigamiPadMaker.Spec(patch, RELEASE_SECONDS)
        val started = System.nanoTime()
        val layers = OrigamiPadMaker.renderVelocityZone(spec, 60)
        return mapOf("authoredVelocityCeiling" to patch.velocity, "patch" to patch.toJsonValue(),
            "renderMilliseconds" to milliseconds(started), "layerCount" to layers.size,
            "layerPolicy" to "Saved ceiling multiplied by each relative layer; player gain remains separate",
            "layers" to layers.mapIndexed { index, key ->
                val velocity = OrigamiExpressions.relativeVelocity(patch, OrigamiPadMaker.RELATIVE_VELOCITIES[index])
                val applied = OrigamiExpressions.appliedPatch(patch.copy(velocity = velocity))
                mapOf("relativeVelocity" to OrigamiPadMaker.RELATIVE_VELOCITIES[index], "renderVelocity" to velocity,
                    "effectiveFold" to applied.resolvedMacros().getValue("FOLD"), "frames" to key.snip.frameCount,
                    "loopStartFrame" to key.loopStartFrame, "peak" to key.snip.peak(), "waveformSha256" to waveformSha(key.snip.samples))
            })
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
            "audio/src/main/kotlin/com/snipsnap/audio", "kit/src/main/kotlin/com/snipsnap/kit", "xpm/src/main/kotlin/com/snipsnap/xpm")
        val names = setOf("Dsp.kt", "Keys.kt", "KeyNote.kt", "InstrumentEngine.kt", "KeyHit.kt", "Loudness.kt", "WavWriter.kt", "WavReader.kt", "InstrumentStore.kt", "Velocity.kt", "Snip.kt", "SmplChunk.kt",
            "OneNote.kt", "Names.kt", "AtomicFile.kt", "KeygroupWriter.kt")
        val files = folders.flatMap { path -> File(checkout, path).listFiles().orEmpty().filter { it.name.startsWith("Origami") || it.name in names } } +
            File(checkout, "shell/src/test/kotlin/com/snipsnap/shell/OrigamiExpressionAuditionGenerator.kt")
        require(files.any { it.name == "OrigamiGestureRenderer.kt" }) { "Gesture renderer source is missing" }
        return files.distinct().sortedBy { it.relativeTo(checkout).invariantSeparatorsPath }
    }

    private fun readme(manifest: Map<String, Any?>, output: File): String = buildString {
        appendLine("ORIGAMI: strike and fold")
        appendLine()
        appendLine("Open start-here.html first: nine embedded matched C4 BLOOM clips compare three voices at strikes 0.3, 0.6 and 1.")
        appendLine("Keep index.html beside native/, matched/ and recipes/ for all 33 clips: gestures, C3/C4/C5, exact velocity-1 references and six held performances.")
        appendLine("Audio stays off until Play. A/B uses one native audio player and stops the preceding clip.")
        appendLine("Matched compares timbre at a common target loudness; Native retains the original overall level response. Neither normalizes the note's endpoints separately.")
        appendLine()
        appendLine("EXPRESSION LEVEL retains accepted behavior. EXPRESSION FOLD multiplies the chosen FOLD by (0.25 + 0.75 * velocity^1.25).")
        appendLine("At velocity 1, LEVEL and FOLD take an exact identity path. The full page and manifest carry paired references and exact float/WAV checks.")
        appendLine("BLOOM starts open and folds; UNFOLD starts folded and opens. MOTION keeps its gesture duration meaning. Sources, pitch anchor and timing are unchanged by expression.")
        appendLine("Featured controls: FOLD 0.75, CREASE 0.5, MOTION 0.65, TURN/WARP 0.5, MIDI 60, seed 20261012.")
        appendLine()
        appendLine("Held instruments are separate sibling artifacts in " + manifest["instrumentArtifactDirectoryName"] + ". No bank download is required by the listening page.")
        appendLine("Each production export has nine roots and three samples per root. MIDI windows 1–42 / 43–84 / 85–127 select 0.3 / 0.6 / 1 times the saved velocity ceiling.")
        appendLine("The main banks use ceiling 1; a separate measured ceiling-0.42 C4 probe is in the manifest. Softer sample peaks match hard, then the player applies strike gain once.")
        appendLine("Six held takes use actual read-back exported samples, a 12-second hold, 0.60-second note-off release and 0.05-second silence. This is reference playback, not a device recording.")
        appendLine("Stored program samples are production PCM24. The manifest separately measures PCM16 encoded byte counts, actual files, frames, and unique decoded float-array storage.")
        appendLine("For finite pads, use the existing SOFT HITS workflow to export strike layers; SEND TO PAD alone captures one rendered hit.")
        appendLine()
        appendLine("Source SHA-256: " + manifest["sourceSha256"])
        appendLine("Recipe matrix SHA-256: " + manifest["recipeSha256"])
        appendLine("Listening round: " + manifest["listeningRound"])
        appendLine("Listening WAVs are mono 44.1 kHz PCM16. Float hashes precede quantization; file hashes cover exact WAV bytes.")
        appendLine("Native and matched listening audio bytes: " + manifest["audioBytes"] + ". Output: " + output.name)
        appendLine()
        appendLine("Why it is built this way")
        why.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("Verified: source fingerprints, exact velocity-1 references, complete production layer exports, and deterministic repeated benchmark waveforms.")
        appendLine("Measured: per-clip levels, file/float hashes, cached render time and cache size, layer/frame/file counts and storage on this JVM host.")
        appendLine("Proposed: the expression law and three layer thresholds are authored choices for owner evaluation.")
        appendLine("Heard: pending owner audition. No phone-latency or subjective approval is claimed.")
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
<title>ORIGAMI · Strike and fold</title>
<style>
:root{color-scheme:dark;--bg:#111419;--panel:#191e26;--line:#343c49;--text:#e6e9ee;--dim:#adb6c5;--accent:#8dd6cf;--warm:#eecb83}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif}main{max-width:1080px;margin:auto;padding:32px 20px 80px}h1{font-size:clamp(30px,6vw,48px);line-height:1.15;margin:12px 0}h2{font-size:20px;margin-top:0}p{max-width:78ch}.eyebrow{font:12px ui-monospace,monospace;letter-spacing:.12em;color:var(--accent)}.dim,small{color:var(--dim)}button,select,textarea{font:inherit;color:inherit;background:#222a35;border:1px solid var(--line);border-radius:7px}button,select{min-height:44px;padding:8px 13px}button{cursor:pointer}button:hover{border-color:var(--accent)}button:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--warm);outline-offset:2px}button.active,.play{background:#233e3e;border-color:var(--accent)}a{color:var(--accent)}.row{display:flex;flex-wrap:wrap;gap:10px;align-items:center}.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:20px;margin:22px 0}.transport{position:sticky;top:0;z-index:2;border-color:#557b79}.transport h2{margin:0 0 4px}.status{min-height:24px;color:var(--warm);overflow-wrap:anywhere}audio{width:100%;margin:10px 0}.ab{display:grid;grid-template-columns:auto minmax(0,1fr) auto minmax(0,1fr);gap:8px}.ab select{min-width:0;width:100%}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px}.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px}.card.selected{border-color:var(--accent)}.card h3{font-size:17px;margin:0 0 6px}.card p{font-size:14px;margin:7px 0 14px}.card .row button{flex:1}svg{display:block;width:100%;height:75px;margin:12px 0}.timing{font:13px/1.7 ui-monospace,monospace;color:var(--dim)}.filters{margin:24px 0 16px}details{margin-top:20px}summary{cursor:pointer;min-height:40px}pre{font:12px/1.5 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere;background:#11161d;padding:14px;border-radius:7px;max-height:420px;overflow:auto}textarea{width:100%;min-height:88px;padding:10px}ul{padding-left:24px}.legend{display:flex;justify-content:space-between;font-size:12px;color:var(--dim)}#mode{margin-left:auto}#openBrowser[hidden]{display:none}@media(max-width:580px){main{padding:22px 12px 50px}.panel{padding:14px}.transport{position:static}.ab{grid-template-columns:auto minmax(0,1fr)}.grid{grid-template-columns:1fr}#mode{margin-left:0}}
</style></head><body><main>
<div class="eyebrow">SNIPSNAP / ORIGAMI / VELOCITY STUDY</div>
<h1>Let the strike choose the fold.</h1>
<p>EXPRESSION FOLD lets a harder strike reach more of the chosen folded color. Start with the matched C4 clips to compare color, then switch the full page to Native for the original dynamics.</p>
<p class="dim">LEVEL keeps the accepted response. At a full-strength strike, LEVEL and FOLD are exactly identical. Finite pad layers use SOFT HITS; SEND TO PAD captures one hit.</p>
<p class="dim" id="edition"></p>
<section class="panel transport" aria-label="Audio player">
<h2 id="now">Choose a clip</h2><div class="dim" id="caption"></div>
<div class="row"><button class="play" id="play">Play selected</button><button id="stop">Stop</button><label id="modeLabel">Level <select id="mode"><option value="matched">Matched</option><option value="native">Native</option></select></label></div>
<audio id="audio" controls preload="none" playsinline></audio><div class="status" id="status" role="status">Audio is off. Choose Play to begin.</div>
<a id="openBrowser" hidden target="_blank" rel="noopener">Open this page in your browser</a>
<div class="ab"><button id="playA">A</button><select id="a" aria-label="Comparison A"></select><button id="playB">B</button><select id="b" aria-label="Comparison B"></select></div>
<div id="curve"></div><div class="legend"><span>Fold depth · one shared clip gain</span><span id="length"></span></div><div id="timing" class="timing"></div>
</section>
<div class="row filters"><label>Show <select id="category"><option value="featured">C4 strike comparison</option><option value="gestures">Other gestures</option><option value="identity">100% identity</option><option value="register">C3 / C5</option><option value="held">12-second holds</option><option value="all">All clips</option></select></label><label>Voice <select id="voice"><option value="all">All voices</option><option>CRANE</option><option>FAN</option><option>STILL</option></select></label><label id="gestureLabel">Gesture <select id="gestureFilter"><option value="all">All gestures</option><option>STEADY</option><option>BLOOM</option><option>UNFOLD</option></select></label><label>Strike <select id="strikeFilter"><option value="all">All strikes</option><option value="0.3">30%</option><option value="0.6">60%</option><option value="1">100%</option></select></label><label id="expressionLabel">Expression <select id="expressionFilter"><option value="all">Both modes</option><option>LEVEL</option><option>FOLD</option></select></label></div>
<div id="cards" class="grid"></div>
<section class="panel"><h2>Listening notes</h2><p class="dim">Notes save in this browser for the selected recipe. A meter cannot decide whether the gesture reads clearly or which voice you prefer.</p><textarea id="notes" aria-label="Notes for selected clip" placeholder="Does the change read clearly? Does the held body settle where you expected?"></textarea><div class="row"><button id="export">Export notes</button><a id="download">Download selected WAV</a></div></section>
<section class="panel"><h2>Why it is built this way</h2><ul id="why"></ul><p class="dim">Verified here: exact full-strength references and real layer exports. Measured: levels, cache times and storage on this JVM host. Heard: pending your audition; no phone timing or musical approval is claimed.</p><details><summary>Recipe, levels and render evidence</summary><pre id="recipe"></pre><pre id="bench"></pre><a id="manifestLink">Download manifest</a></details></section>
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
let selected=clips.find(c=>c.id==='main_fan_bloom_v30')||clips[0],mode='matched',token=0,watchdog=0,playingId=null;
const noteKey=c=>'origami-expression:'+manifest.sourceSha256+':'+c.recipeSha256;
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
el('timing').textContent=(selected.gesture==='STEADY'?'STEADY · axis motion off · FOLD '+Math.round(selected.maximumFold*100)+'%':'Change begins '+selected.gestureStartSeconds.toFixed(3)+' s · lasts '+selected.gestureDurationSeconds.toFixed(3)+' s · settles at '+selected.gestureEndSeconds.toFixed(3)+' s'+(selected.held?' · note-off at 12.00 s · release 0.60 s':''))+' · strike '+Math.round(selected.velocity*100)+'% · '+selected.expression+' expression · actual FOLD '+Math.round(selected.effectiveFold*100)+'%';
el('notes').value=readNote(selected);el('recipe').textContent=JSON.stringify({recipe:selected.recipe,matchedGain:selected.matchedGain,nativeMetrics:selected.nativeMetrics,matchedMetrics:selected.matchedMetrics,rawBeforeOutputMetrics:selected.rawBeforeOutputMetrics,renderTiming:selected.renderTiming,heldEvidence:selected.heldEvidence,recipeSha256:selected.recipeSha256,nativeFileSha256:selected.nativeFileSha256,matchedFileSha256:selected.matchedFileSha256},null,2);
el('download').href=source(selected);el('download').download=selected.id+'-'+mode+'.wav';}
function drawCards(){const category=el('category').value,voice=el('voice').value,gesture=el('gestureFilter').value,strike=el('strikeFilter').value,expression=el('expressionFilter').value;el('cards').replaceChildren();clips.filter(c=>(category==='all'||c.category===category||(category==='identity'&&c.featured&&c.velocity===1))&&(voice==='all'||c.voice===voice)&&(gesture==='all'||c.gesture===gesture)&&(strike==='all'||Math.abs(c.velocity-Number(strike))<0.00001)&&(expression==='all'||c.expression===expression)).forEach(c=>{
const card=document.createElement('article');card.className='card'+(c.id===selected.id?' selected':'');
const title=document.createElement('h3');title.textContent=c.title;const desc=document.createElement('p');desc.className='dim';desc.textContent=c.description;
const line=document.createElement('div');line.className='row';const p=document.createElement('button');p.textContent='Play';p.onclick=()=>play(c.id);const inspect=document.createElement('button');inspect.textContent='Select';inspect.onclick=()=>{stop();choose(c.id);};line.append(p,inspect);
card.append(title,desc,line);el('cards').append(card);});}
function saveFile(name,value){const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
el('play').onclick=()=>play(selected.id);el('stop').onclick=stop;
el('mode').onchange=()=>{stop();mode=el('mode').value;drawSelected();};
for(const key of ['category','voice','gestureFilter','strikeFilter','expressionFilter'])el(key).onchange=drawCards;
for(const key of ['a','b']){for(const c of clips){const o=document.createElement('option');o.value=c.id;o.textContent=c.title;el(key).append(o);}}
el('a').value=(clips.find(c=>c.id==='main_fan_bloom_v30')||clips[0]).id;el('b').value=(clips.find(c=>c.id==='main_fan_bloom_v100')||selected).id;
el('playA').onclick=()=>play(el('a').value);el('playB').onclick=()=>play(el('b').value);
el('notes').oninput=()=>{try{localStorage.setItem(noteKey(selected),el('notes').value);}catch(e){status('This browser could not save notes. Export them before closing.');}};
el('export').onclick=()=>saveFile('origami-expression-feedback.json',{listeningRound:manifest.listeningRound,sourceSha256:manifest.sourceSha256,recipeSha256:manifest.recipeSha256,exportedUtc:new Date().toISOString(),notes:clips.map(c=>({id:c.id,recipeSha256:c.recipeSha256,nativeFileSha256:c.nativeFileSha256,matchedFileSha256:c.matchedFileSha256,note:readNote(c)})).filter(c=>c.note)});
for(const text of manifest.whyItIsBuiltThisWay){const li=document.createElement('li');li.textContent=text;el('why').append(li);}
el('bench').textContent=manifest.benchmarkPolicy+'\n\n'+JSON.stringify({benchmarks:manifest.benchmarks,identityReferences:manifest.identityReferences,savedCeilingProbe:manifest.savedCeilingProbe,instrumentStorage:manifest.instruments.map(b=>({voice:b.voice,pitchZoneCount:b.pitchZoneCount,layersPerZone:b.layersPerZone,sampleCount:b.sampleCount,frameCount:b.frameCount,floatResidentBytes:b.floatResidentBytes,exportedWavBitDepth:b.exportedWavBitDepth,exportedWavDiskBytes:b.exportedWavDiskBytes,pcm16EncodedBytesMeasured:b.pcm16EncodedBytesMeasured,renderMilliseconds:b.renderMilliseconds}))},null,2);
const manifestUrl=URL.createObjectURL(new Blob([JSON.stringify(manifest,null,2)],{type:'application/json'}));el('manifestLink').href=manifestUrl;el('manifestLink').download='origami-expression-manifest.json';
if(compact){el('edition').textContent='Self-contained starter · nine matched C4 BLOOM clips, three voices × strikes 30 / 60 / 100%. Full study adds gestures, identity references, registers and held layers.';el('modeLabel').hidden=true;el('gestureLabel').hidden=true;el('expressionLabel').hidden=true;for(const option of Array.from(el('category').options)){if(!['featured','all'].includes(option.value))option.remove();}}
else el('edition').textContent='Full study · '+clips.length+' clips. Compare Native and Matched, inspect LEVEL/FOLD at 100%, or try the real three-layer held performances.';
try{if(window.self!==window.top){el('openBrowser').hidden=false;el('openBrowser').href=window.location.href;}}catch(e){}
drawSelected();drawCards();
</script></body></html>
""".trimIndent()
}
