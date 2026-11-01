package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiExpression
import com.snipsnap.synth.OrigamiExpressions
import com.snipsnap.synth.OrigamiGesture
import com.snipsnap.synth.OrigamiGestureRenderer
import com.snipsnap.synth.OrigamiGestures
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiPresets
import com.snipsnap.synth.OrigamiVoice
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Reproduce authored factory recipes, musical takes and real MPC programs without retaining all banks. */
object OrigamiPerformanceBankGenerator {
    private const val RATE = 44_100
    private const val TARGET = .12f
    private const val CEILING = .90f
    private const val RELEASE = .6f
    private const val PHRASE_GAIN = .1f
    private const val BASE_COMMIT = "82a1f9c74086d574d4de3eb1b7dc0944069c89d6"
    private val touches = listOf(.3f, .6f, 1f)
    private val touchNames = listOf("soft", "mid", "hard")
    private val why = listOf(
        "Each BLOOM/UNFOLD pair shares its authored controls. Only the direction changes, so you can compare how the note opens or folds.",
        "Soft, mid and hard touches stay beneath the saved factory velocity ceiling. The hard single is the exact factory render; none of the six source recipes is rewritten.",
        "One gain covers an entire matched take, preserving its internal changes. Native retains the actual renderer or reference-player levels.",
        "Held instruments use three real sample layers, their own attacks and loops, and one player velocity gain. The held take actually plays soft, mid and hard notes; each selected layer crosses at least three real wraps before release.",
        "MPC ZIPs include both program formats, samples, playback sidecar and the complete authoring recipe. On the phone, select the factory preset and use MAKE INSTRUMENT; these ZIPs are not phone imports.",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory" }
        val output = File(args.firstOrNull() ?: "../testkit/origami-performance-bank").absoluteFile
        require(output.isDirectory || output.mkdirs())
        val checkout = File("..").canonicalFile
        val sources = sourceFiles(checkout)
        val hashes = sources.associate { it.relativeTo(checkout).invariantSeparatorsPath to sha(it.readBytes()) }.toSortedMap()
        val sourceHash = sha(hashes.entries.joinToString("\n") { it.key + ":" + it.value }.toByteArray())
        val presets = OrigamiPresets.performance()
        require(presets.size == 6 && presets.map { it.name }.distinct().size == 6)
        require(presets.all { it.model == 4 && it.expression == OrigamiExpression.FOLD })
        for (voice in OrigamiVoice.entries) check(presets.filter { it.voice == voice }.map { it.gesture }.toSet() == setOf(OrigamiGesture.BLOOM, OrigamiGesture.UNFOLD))
        val presetRows = presets.map { preset ->
            val path = "recipes/" + id(preset) + ".json"
            val file = File(output, path).apply { parentFile.mkdirs(); writeText(Json.write(preset.toJsonValue()) + "\n") }
            check(OrigamiPatch.fromJsonText(file.readText()) == preset)
            linkedMapOf<String, Any?>("id" to id(preset), "name" to preset.name, "voice" to preset.voice.name,
                "gesture" to preset.gesture.name, "recipe" to preset.toJsonValue(), "recipePath" to path,
                "recipeSha256" to sha(file.readBytes()), "authoredVelocityCeiling" to preset.velocity,
                "midi" to midi(preset))
        }
        val factoryRecipeHash = sha(presetRows.joinToString("\n") { it.getValue("id").toString() + ":" + it.getValue("recipeSha256") }.toByteArray())
        val started = System.nanoTime()
        val clips = mutableListOf<Map<String, Any?>>()
        val hardIdentities = mutableListOf<Map<String, Any?>>()
        for (preset in presets) {
            println("Factory singles and phrase: " + preset.name)
            OrigamiGestureRenderer.clearCache()
            for ((index, touch) in touches.withIndex()) {
                val performed = if (touch == 1f) preset else preset.copy(velocity = OrigamiExpressions.relativeVelocity(preset, touch))
                val before = cacheStats()
                val start = System.nanoTime()
                val native = performed.render()
                val elapsed = milliseconds(start)
                if (touch == 1f) {
                    val exact = preset.render()
                    check(native.samples.contentEquals(exact.samples)) { "Hard take differs from factory: " + preset.name }
                    check(performed.toJsonValue() == preset.toJsonValue())
                    hardIdentities += mapOf("presetId" to id(preset), "hardClipId" to id(preset) + "-hard",
                        "exactFactoryRecipe" to true, "exactFactoryWaveform" to true, "waveformSha256" to waveformSha(native.samples))
                }
                clips += writeClip(output, id(preset) + "-" + touchNames[index], preset, "singles",
                    preset.name + " · " + touchNames[index],
                    "Relative touch " + (touch * 100).roundToInt() + "% beneath the saved velocity ceiling. Hard is the exact factory sound.",
                    native, touch, performed, mapOf("performedVelocity" to performed.velocity, "renderMilliseconds" to elapsed,
                        "cacheBefore" to before, "cacheAfter" to cacheStats(), "renderPatch" to performed.toJsonValue()))
            }
            clips += phrase(output, preset)
        }
        // Export one bank, let its render arrays go, then load only the three C4 samples for its take.
        val instruments = mutableListOf<Map<String, Any?>>()
        for (preset in presets.filter { OrigamiPadMaker.supports(it) }) {
            val bank = exportBank(output, preset)
            instruments += bank
            clips += heldTake(output, preset, bank)
        }
        check(clips.size == 28 && instruments.size == 4)
        check(clips.map { it.getValue("id") }.distinct().size == clips.size)
        check(sources.all { sha(it.readBytes()) == hashes.getValue(it.relativeTo(checkout).invariantSeparatorsPath) }) {
            "Source files changed during generation; rerun after all writers are finished"
        }
        for (file in sources) file.copyTo(File(output, "sources/" + file.relativeTo(checkout).invariantSeparatorsPath).apply { parentFile.mkdirs() }, overwrite = true)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "ORIGAMI performance bank", "createdUtc" to Instant.now().toString(),
            "baseCommitRead" to BASE_COMMIT, "sourceSha256" to sourceHash, "sourceFiles" to hashes,
            "recipeSha256" to factoryRecipeHash, "factoryPresetCount" to presets.size, "factoryPresets" to presetRows,
            "clipCount" to clips.size, "sampleRate" to RATE, "bitDepth" to 16,
            "listeningRound" to "origami-performance-" + sourceHash.take(12) + "-" + factoryRecipeHash.take(12),
            "clips" to clips, "instruments" to instruments, "hardFactoryIdentities" to hardIdentities,
            "instrumentTotals" to mapOf("instrumentCount" to instruments.size,
                "pitchZoneCount" to instruments.sumOf { (it.getValue("pitchZoneCount") as Number).toInt() },
                "sampleCount" to instruments.sumOf { (it.getValue("sampleCount") as Number).toInt() },
                "frameCount" to instruments.sumOf { (it.getValue("frameCount") as Number).toLong() },
                "fullLoadFloatPayloadBytes" to instruments.sumOf { (it.getValue("floatResidentBytes") as Number).toLong() },
                "exportedWavDiskBytes" to instruments.sumOf { (it.getValue("exportedWavDiskBytes") as Number).toLong() }),
            "matching" to mapOf("targetLoudness" to TARGET, "peakCeiling" to CEILING,
                "policy" to "Loudness.of; one gain per complete take, no independent endpoint or note normalization"),
            "relativeTouchPolicy" to "Finite render velocity = saved factory velocity * relative touch; hard remains exactly authored. Held layers use the same relative ceiling, then InstrumentEngine applies key gain once.",
            "phrasePolicy" to "120 BPM minor-pentatonic line followed by a minor triad; native note renders at authored relative velocities mixed through one fixed 0.10 bus gain. Offline score, not a device performance.",
            "memoryPolicy" to "Export one instrument at a time. Its maker API owns 27 render samples during export; only the three exported C4 samples are loaded for the long take. Never retain all four banks' audio.",
            "benchmarkPolicy" to "Per-take and per-bank JVM wall times/cache sizes, not phone latency. No speed threshold is asserted.",
            "whyItIsBuiltThisWay" to why,
            "verificationLedger" to mapOf("verified" to "Six canonical factory recipes; exact hard factory identities; production export readback, sampler sheets and retained companions; three actual sequential touches, each selected layer crossing at least three held wraps",
                "measured" to "Native/matched levels, hashes, sample/frame/loop counts, real file sizes, unique decoded float payload sizes, cache sizes and JVM wall time",
                "proposed" to "Musical score and factory palette are candidates for owner listening", "heard" to "Pending owner audition; no subjective verdict"),
            "generationMilliseconds" to milliseconds(started),
            "audioBytes" to clips.sumOf { File(output, it.getValue("nativePath").toString()).length() + File(output, it.getValue("matchedPath").toString()).length() })
        val text = Json.write(json(manifest)) + "\n"
        File(output, "manifest.json").writeText(text)
        File(output, "manifest.sha256").writeText(sha(text.toByteArray()) + "  manifest.json\n")
        File(output, "index.html").writeText(page(manifest, emptyMap(), false))
        val starterClips = clips.filter { it["category"] == "singles" }
        check(starterClips.size == 18)
        val starter = LinkedHashMap(manifest).apply {
            put("clips", starterClips); put("clipCount", starterClips.size); put("parentClipCount", clips.size)
            put("parentManifestSha256", sha(text.toByteArray()))
        }
        val embedded = starterClips.associate { row ->
            val path = row.getValue("matchedPath").toString()
            path to "data:audio/wav;base64," + Base64.getEncoder().encodeToString(File(output, path).readBytes())
        }
        File(output, "start-here.html").writeText(page(starter, embedded, true))
        File(output, "README.txt").writeText(readme(manifest))
        println("Wrote six exact factory recipes, 28 native/matched takes, four 27-sample MPC banks and portable audition")
        println("Output " + output + "; starter bytes " + File(output, "start-here.html").length())
    }

    private fun id(patch: OrigamiPatch) = patch.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun midi(patch: OrigamiPatch) = patch.midi ?: Origami.midiFor(patch.voice, patch.resolvedMacros().getValue("TUNE"))

    private fun writeClip(output: File, clipId: String, preset: OrigamiPatch, category: String, title: String,
        description: String, native: Snip, touch: Float?, performed: OrigamiPatch?, details: Map<String, Any?>): Map<String, Any?> {
        require(native.channels == 1 && native.sampleRate == RATE && native.samples.all { it.isFinite() })
        check(native.peak() <= 1f) { "Native take would clip PCM: " + clipId }
        val scale = matchingGain(native)
        val matched = gain(native, scale)
        val nativePath = "native/" + clipId + ".wav"
        val matchedPath = "matched/" + clipId + ".wav"
        WavWriter.write(File(output, nativePath), native, WavWriter.BitDepth.PCM_16)
        WavWriter.write(File(output, matchedPath), matched, WavWriter.BitDepth.PCM_16)
        val seconds = native.frameCount.toDouble() / RATE
        val applied = performed?.let { OrigamiExpressions.appliedPatch(it) }
        // Runtime measurements are evidence, not recipe inputs: repeating a generation
        // must keep the performance recipe hash and browser note key unchanged.
        val performance = details.filterKeys { !it.endsWith("Milliseconds") && !it.startsWith("cache") }
        val recipe = linkedMapOf<String, Any?>("factoryPatch" to preset.toJsonValue(), "category" to category,
            "relativeTouch" to touch, "performedPatch" to performed?.toJsonValue(), "performance" to performance)
        return linkedMapOf("id" to clipId, "presetId" to id(preset), "presetName" to preset.name,
            "voice" to preset.voice.name, "gesture" to preset.gesture.name, "expression" to preset.expression.name,
            "category" to category, "title" to title, "description" to description,
            "featured" to (category == "singles"), "held" to (category == "held"), "velocity" to touch,
            "authoredVelocityCeiling" to preset.velocity, "performedVelocity" to performed?.velocity,
            "midi" to midi(preset), "note" to note(midi(preset)), "macros" to preset.resolvedMacros(),
            "seed" to preset.seed.toString(), "geometryModel" to preset.model,
            "gestureModel" to OrigamiGestures.MODEL_VERSION, "expressionModel" to OrigamiExpressions.MODEL_VERSION,
            "factoryRecipePath" to "recipes/" + id(preset) + ".json",
            "factoryRecipeSha256" to sha(File(output, "recipes/" + id(preset) + ".json").readBytes()),
            "recipe" to recipe, "recipeSha256" to sha(Json.write(json(recipe)).toByteArray()),
            "frames" to native.frameCount, "durationSeconds" to seconds, "sampleRate" to RATE,
            "maximumFold" to preset.resolvedMacros().getValue("FOLD"), "effectiveFold" to applied?.resolvedMacros()?.getValue("FOLD"),
            "gestureStartSeconds" to OrigamiGestures.startSeconds(preset.voice),
            "gestureDurationSeconds" to OrigamiGestures.durationSeconds(preset.voice, preset.resolvedMacros().getValue("MOTION")),
            "gestureEndSeconds" to OrigamiGestures.endSeconds(preset),
            "foldCurveSvg" to applied?.let { curve(it, seconds) }, "renderTiming" to details,
            "nativePath" to nativePath, "matchedPath" to matchedPath, "matchedGain" to scale,
            "nativeMetrics" to metrics(native), "matchedMetrics" to metrics(matched),
            "nativeWaveformSha256" to waveformSha(native.samples), "matchedWaveformSha256" to waveformSha(matched.samples),
            "nativeFileSha256" to sha(File(output, nativePath).readBytes()), "matchedFileSha256" to sha(File(output, matchedPath).readBytes()))
    }

    /** Only one note render and the fixed-size phrase bus are live during summation. */
    private fun phrase(output: File, preset: OrigamiPatch): Map<String, Any?> {
        val root = midi(preset)
        val offsets = listOf(0, 3, 7, 10, 12, 7)
        val accents = listOf(.3f, .6f, 1f, .6f, .3f, .85f)
        val score = offsets.mapIndexed { index, semitones -> Triple(index * .5, root + semitones, accents[index]) } +
            listOf(Triple(3.5, root, .6f), Triple(3.5, root + 3, .8f), Triple(3.5, root + 7, 1f))
        val outputFrames = ((score.maxOf { it.first } + Origami.durationSeconds(preset.voice)) * RATE).roundToInt()
        val bus = FloatArray(outputFrames)
        val start = System.nanoTime()
        val events = score.map { (time, midi, touch) ->
            val performed = preset.copy(midi = midi, velocity = OrigamiExpressions.relativeVelocity(preset, touch))
            val note = performed.render()
            val at = (time * RATE).roundToInt()
            for (i in note.samples.indices) if (at + i < bus.size) bus[at + i] += note.samples[i] * PHRASE_GAIN
            mapOf("atSeconds" to time, "midi" to midi, "relativeTouch" to touch,
                "performedVelocity" to performed.velocity, "renderPatch" to performed.toJsonValue(),
                "noteWaveformSha256" to waveformSha(note.samples))
        }
        return writeClip(output, id(preset) + "-phrase", preset, "phrases", preset.name + " · phrase + chord",
            "120 BPM: a minor-pentatonic line followed by a minor triad, using native note renders and one fixed bus gain.",
            Snip(bus, 1, RATE), null, null, mapOf("bpm" to 120, "busGain" to PHRASE_GAIN, "events" to events,
                "renderMilliseconds" to milliseconds(start), "cacheAfter" to cacheStats()))
    }

    /** The production exporter owns 27 render buffers here; none escapes this function. */
    private fun exportBank(output: File, preset: OrigamiPatch): Map<String, Any?> {
        val directory = File(output, "instruments/" + id(preset)).apply { mkdirs() }
        val spec = OrigamiPadMaker.Spec(preset, RELEASE)
        check(spec.layerCount == 3)
        val roots = OrigamiPadMaker.zoneMidis(spec)
        val costs = mutableListOf<Map<String, Any?>>()
        val start = System.nanoTime()
        val notes = roots.map { root ->
            val zoneStart = System.nanoTime()
            val layers = OrigamiPadMaker.renderVelocityZone(spec, root)
            costs += mapOf("rootMidi" to root, "renderMilliseconds" to milliseconds(zoneStart),
                "layerFrames" to layers.map { it.snip.frameCount }, "loopStarts" to layers.map { it.loopStartFrame })
            println("Held " + preset.name + " root " + root + " × " + layers.size)
            layers
        }
        val renderMs = milliseconds(start)
        val exportStart = System.nanoTime()
        OrigamiPadMaker.exportLayers(preset.name, spec, notes, directory, overwrite = true)
        val exportMs = milliseconds(exportStart)
        val instrument = InstrumentStore.read(InstrumentStore.sidecar(directory, preset.name))
        check(instrument.zones.size == 9 && instrument.sampleFiles.size == 27)
        val companion = OrigamiPadMaker.recipeFile(directory, preset.name)
        check(OrigamiPadMaker.Spec.fromJsonText(companion.readText()) == spec)
        check(File(directory, preset.name + ".xty").isFile)
        check(File(directory, preset.name + "_[TrackData]/" + preset.name + ".xpm").isFile)
        var pcm16Bytes = 0L
        val rows = instrument.zones.flatMap { zone -> zone.playableLayers.mapIndexed { index, layer ->
            val file = File(directory, layer.sample)
            val encoded = file.readBytes()
            val snip = WavReader.read(encoded)
            val sheet = WavReader.readSmpl(file)
            check(sheet == SmplChunk(zone.rootNote, SmplChunk.Loop(layer.loopStartFrame, layer.frameCount)))
            check(snip.frameCount.toLong() == layer.frameCount && snip.channels == 1 && snip.sampleRate == RATE)
            val counter = ByteCounter()
            WavWriter.write(counter, snip, WavWriter.BitDepth.PCM_16, smpl = sheet)
            pcm16Bytes += counter.bytes
            val window = OrigamiPadMaker.VELOCITY_WINDOWS[index]
            check(layer.velStart == window.first && layer.velEnd == window.last)
            val bits = (encoded[34].toInt() and 255) or ((encoded[35].toInt() and 255) shl 8)
            check(bits == 24) { "Production WAV encoding changed; update its disclosure" }
            mapOf("rootMidi" to zone.rootNote, "lowNote" to zone.lowNote, "highNote" to zone.highNote,
                "layerIndex" to index, "relativeVelocity" to OrigamiPadMaker.RELATIVE_VELOCITIES[index],
                "renderVelocity" to OrigamiExpressions.relativeVelocity(preset, OrigamiPadMaker.RELATIVE_VELOCITIES[index]),
                "sample" to layer.sample, "velocityWindow" to listOf(layer.velStart, layer.velEnd),
                "frames" to layer.frameCount, "loopStartFrame" to layer.loopStartFrame,
                "loopEndFrameExclusive" to layer.frameCount, "bitDepth" to bits,
                "waveformSha256" to waveformSha(snip.samples), "fileSha256" to sha(encoded), "bytes" to file.length())
        } }
        val frameCount = instrument.zones.sumOf { zone -> zone.playableLayers.sumOf { it.frameCount } }
        val files = directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.toList()
        val relativeZip = "downloads/" + id(preset) + ".zip"
        val zip = File(output, relativeZip).apply { parentFile.mkdirs() }
        zipFiles(directory, files, zip)
        return linkedMapOf("id" to id(preset), "presetName" to preset.name, "voice" to preset.voice.name,
            "gesture" to preset.gesture.name, "factoryRecipe" to preset.toJsonValue(), "spec" to Json.parse(spec.toJsonText()),
            "directoryPath" to "instruments/" + id(preset), "downloadPath" to relativeZip,
            "downloadBytes" to zip.length(), "downloadSha256" to sha(zip.readBytes()),
            "pitchZoneCount" to instrument.zones.size, "layersPerZone" to spec.layerCount,
            "sampleCount" to instrument.sampleFiles.size, "roots" to roots, "releaseSeconds" to RELEASE,
            "frameCount" to frameCount, "floatResidentBytes" to frameCount * 4,
            "floatPayloadScope" to "Unique sample arrays if the complete bank is decoded; not simultaneous generator memory or object overhead",
            "exportedWavBitDepth" to 24, "exportedWavDiskBytes" to instrument.sampleFiles.sumOf { File(directory, it).length() },
            "pcm16EncodedBytesMeasured" to pcm16Bytes, "pcm16Scope" to "Counter-stream encoding of the same sampler sheets; stored exports remain PCM24",
            "renderMilliseconds" to renderMs, "exportMilliseconds" to exportMs, "rootRenderCosts" to costs,
            "layers" to rows, "files" to files.map { mapOf("path" to it.relativeTo(directory).invariantSeparatorsPath,
                "bytes" to it.length(), "sha256" to sha(it.readBytes())) })
    }

    /** Play each real exported C4 layer, not just a calculated schedule for unused layers. */
    private fun heldTake(output: File, preset: OrigamiPatch, bank: Map<String, Any?>): Map<String, Any?> {
        val directory = File(output, bank.getValue("directoryPath").toString())
        val original = InstrumentStore.read(InstrumentStore.sidecar(directory, preset.name))
        val zone = original.zones.single { it.rootNote == 60 }
        val instrument = original.copy(zones = listOf(zone))
        val samples = zone.playableLayers.associate { it.sample to WavReader.read(File(directory, it.sample)).samples }
        val loaded = InstrumentEngine.Loaded(instrument, RATE, samples)
        val releaseFrames = ceil(RELEASE.toDouble() * RATE).toInt()
        val gapFrames = RATE / 20
        data class Stage(val touch: Float, val hit: KeyHit.Hit, val layer: InstrumentStore.Layer,
                         val holdFrames: Int, val wraps: Long)
        val stages = touches.map { touch ->
            val hit = requireNotNull(KeyHit.resolve(instrument, 60, touch) { samples[it]?.size?.toLong() })
            check(hit.loops && hit.pitchRatio == 1.0)
            val layer = zone.playableLayers.single { it.sample == hit.sampleFile }
            val period = hit.frames - hit.loopStartFrame
            val minimum = if (preset.voice == OrigamiVoice.STILL) 36.0 else 12.0
            // The third wrap happens at file length + two owned periods. Leave an extra
            // quarter-second before note-off, with no reliance on another layer's loop.
            val holdSeconds = maxOf(minimum, ceil((hit.frames + 2L * period).toDouble() / RATE + .25))
            val holdFrames = (holdSeconds * RATE).roundToInt()
            val wraps = if (holdFrames > hit.frames) 1 + (holdFrames - 1L - hit.frames) / period else 0L
            check(wraps >= 3) { "Selected layer misses three held wraps: " + preset.name + " " + touch }
            Stage(touch, hit, layer, holdFrames, wraps)
        }
        check(stages.map { it.hit.sampleFile }.distinct().size == 3) { "Soft/mid/hard did not select three real layers" }
        val out = FloatArray(stages.sumOf { it.holdFrames + releaseFrames + gapFrames })
        val buffer = FloatArray(512 * 2)
        val engine = InstrumentEngine(loaded, RATE)
        val events = mutableListOf<Map<String, Any?>>()
        var atFrame = 0
        val started = System.nanoTime()
        for ((index, stage) in stages.withIndex()) {
            check(engine.activeNotes.isEmpty()) { "Previous held note overlaps the next touch" }
            val resolved = requireNotNull(KeyHit.resolve(instrument, 60, stage.touch) { samples[it]?.size?.toLong() })
            check(resolved == stage.hit)
            check(engine.noteOn(60, stage.touch))
            val length = stage.holdFrames + releaseFrames + gapFrames
            var position = 0
            while (position < length) {
                if (position == stage.holdFrames) engine.noteOff(60)
                val boundary = if (position < stage.holdFrames) stage.holdFrames else length
                val frames = minOf(512, boundary - position)
                engine.render(buffer, frames)
                for (i in 0 until frames) {
                    check(buffer[2 * i] == buffer[2 * i + 1])
                    out[atFrame + position + i] = buffer[2 * i]
                }
                position += frames
            }
            check(engine.activeNotes.isEmpty()) { "Held touch did not finish its release before the gap ended" }
            // At the exact C4 root, cubic interpolation reads integer frames. One voice
            // stays below the bus soft clip, so this also verifies the actual selected
            // sample and that the reference player applied key velocity gain once.
            val sample = samples.getValue(resolved.sampleFile)
            val headFrames = minOf(4096, sample.size)
            for (i in 0 until headFrames) check(out[atFrame + i] == sample[i] * resolved.gain) {
                "Played head differs from selected layer/gain: " + preset.name + " " + stage.touch
            }
            val performed = preset.copy(midi = 60, velocity = OrigamiExpressions.relativeVelocity(preset, stage.touch))
            val noteOffFrame = atFrame + stage.holdFrames
            val period = resolved.frames - resolved.loopStartFrame
            events += linkedMapOf(
                "touchName" to touchNames[index], "keyVelocity" to stage.touch,
                "midiVelocity" to (stage.touch * 127).roundToInt(), "midi" to 60,
                "atFrame" to atFrame, "atSeconds" to atFrame.toDouble() / RATE,
                "noteOffFrame" to noteOffFrame, "noteOffSeconds" to noteOffFrame.toDouble() / RATE,
                "holdFrames" to stage.holdFrames, "holdSeconds" to stage.holdFrames.toDouble() / RATE,
                "releaseSeconds" to RELEASE, "releaseFrames" to releaseFrames,
                "gapFrames" to gapFrames, "gapSeconds" to gapFrames.toDouble() / RATE,
                "endFrame" to atFrame + length, "endSeconds" to (atFrame + length).toDouble() / RATE,
                "selectedSample" to resolved.sampleFile,
                "selectedVelocityWindow" to listOf(stage.layer.velStart, stage.layer.velEnd),
                "selectedLayerFrames" to resolved.frames, "loopStartFrame" to resolved.loopStartFrame,
                "loopEndFrameExclusive" to resolved.frames, "playerGain" to resolved.gain,
                "pitchRatio" to resolved.pitchRatio, "firstWrapFrame" to resolved.frames,
                "firstWrapSeconds" to resolved.frames.toDouble() / RATE,
                "firstWrapAtFrame" to atFrame + resolved.frames,
                "firstWrapAtSeconds" to (atFrame + resolved.frames).toDouble() / RATE,
                "periodFrames" to period, "periodSeconds" to period.toDouble() / RATE,
                "wrapsBeforeNoteOff" to stage.wraps, "verifiedHeadFrames" to headFrames,
                "renderVelocity" to performed.velocity, "appliedPatch" to OrigamiExpressions.appliedPatch(performed).toJsonValue())
            println("Held " + preset.name + " " + touchNames[index] + ": selected " + resolved.sampleFile +
                ", hold " + stage.holdFrames.toDouble() / RATE + " s, actual selected-layer wraps " + stage.wraps)
            atFrame += length
        }
        check(atFrame == out.size)
        return writeClip(output, id(preset) + "-held", preset, "held", preset.name + " · held soft / mid / hard",
            "Three actual exported C4 notes: soft, mid, then hard. Each selected layer crosses at least three owned wraps, releases over 0.60 s, and leaves a short gap. One matched gain covers the complete performance.",
            Snip(out, 1, RATE), null, null, mapOf("notes" to events, "touchOrder" to touches,
                "releaseSeconds" to RELEASE, "gapSeconds" to gapFrames.toDouble() / RATE,
                "decodedZoneCount" to 1, "decodedSampleCount" to samples.size,
                "decodedFloatPayloadBytes" to samples.values.sumOf { it.size.toLong() * 4 },
                "referencePlaybackMilliseconds" to milliseconds(started),
                "gestureClockScope" to "Each played note has its own gesture clock; no single-note curve describes this complete sequence",
                "playback" to "Three actual sequential touches through production InstrumentEngine/KeyHit, not a device recording"))
    }

    private fun zipFiles(root: File, files: List<File>, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            for (file in files) {
                val entry = ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0L }
                zip.putNextEntry(entry); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        java.util.zip.ZipFile(target).use { zip ->
            check(zip.size() == files.size)
            for (file in files) {
                val entry = requireNotNull(zip.getEntry(file.relativeTo(root).invariantSeparatorsPath))
                check(sha(zip.getInputStream(entry).use { it.readBytes() }) == sha(file.readBytes()))
            }
        }
    }

    private class ByteCounter : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) { bytes++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes += length }
    }

    private fun readme(manifest: Map<String, Any?>): String = buildString {
        appendLine("ORIGAMI performance bank")
        appendLine("Six factory presets, 18 soft/mid/hard singles, six phrases/chords and four held instrument takes. Open start-here.html for the self-contained matched singles; index.html adds Native, phrases, long holds and MPC downloads.")
        appendLine("Relative touches 0.3 / 0.6 / 1 stay beneath each saved velocity ceiling. Hard singles equal the unchanged factory recipe render. The phrase is an offline 120 BPM minor-pentatonic line plus minor triad, with one fixed 0.10 bus gain.")
        appendLine("downloads/ contains four MPC-ready ZIPs. Extract a complete ZIP together: root .xty for MPC 3, TrackData .xpm for MPC 2, all WAVs, .instrument.json and .origami-keys.json. Each bank has nine roots and three layers (27 PCM24 WAVs), per-layer loops, and 0.60 s release.")
        appendLine("These ZIPs are not SnipSnap phone imports. On phone use the new factory preset and existing MAKE INSTRUMENT. Finite pad layers use SOFT HITS; SEND TO PAD alone renders one hit.")
        appendLine("Each held example plays three sequential notes: soft, mid and hard. Every selected layer is actually played across at least three real loop wraps. Each STILL note holds at least 36 seconds; the full three-note take is roughly 110 seconds. FAN notes hold at least 12 seconds. Each release is 0.60 s followed by a 0.05 s gap. A single matched gain covers the complete sequence. Playback is the production reference map, not a device recording.")
        appendLine("Native and Matched listening WAVs are mono 44.1 kHz PCM16; program WAVs stay production PCM24. Matching uses one Loudness.of gain for the whole take at 0.12 target with a 0.90 peak guard, without leveling endpoints separately.")
        appendLine("Sources, six exact JSON recipes, performance scores, file/float hashes, roots/layers/loops/release, wrap counts, actual file sizes, decoded float payload sizes, render costs and cache sizes are in manifest.json.")
        appendLine("Source fingerprint: " + manifest["sourceSha256"])
        appendLine("Factory recipe fingerprint: " + manifest["recipeSha256"])
        appendLine()
        appendLine("Why it is built this way")
        why.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("Verified: canonical recipe round-trips, exact hard factory waveforms, complete production exports and sampler sheets, ZIP readback hashes, and three actual played touches with at least three owned loop wraps each.")
        appendLine("Measured: levels, hashes, counts, storage, loop clocks and JVM host timing. Float payload counts exclude objects/cache/temporary arrays; no phone latency claim.")
        appendLine("Proposed: the score and palette remain candidates for the owner to judge.")
        appendLine("Heard: pending owner audition; no subjective verdict.")
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
        require(File(checkout, "shell/src/main/kotlin/com/snipsnap/shell/OrigamiPadMaker.kt").isFile) { "Run with shell as working directory" }
        val folders = listOf("synth/src/main/kotlin/com/snipsnap/synth", "shell/src/main/kotlin/com/snipsnap/shell",
            "audio/src/main/kotlin/com/snipsnap/audio", "kit/src/main/kotlin/com/snipsnap/kit", "xpm/src/main/kotlin/com/snipsnap/xpm", "json/src/main/kotlin/com/snipsnap/json")
        val names = setOf("Dsp.kt", "Tide.kt", "Keys.kt", "InstrumentEngine.kt", "KeyHit.kt", "InstrumentStore.kt", "Loudness.kt", "Velocity.kt",
            "Snip.kt", "WavReader.kt", "WavWriter.kt", "SmplChunk.kt", "OneNote.kt", "Names.kt", "AtomicFile.kt", "KeygroupWriter.kt", "WavInfo.kt", "Json.kt")
        return (folders.flatMap { path -> File(checkout, path).listFiles().orEmpty().filter { it.name.startsWith("Origami") || it.name in names } } +
            File(checkout, "shell/src/test/kotlin/com/snipsnap/shell/OrigamiPerformanceBankGenerator.kt")).distinct().sortedBy { it.relativeTo(checkout).invariantSeparatorsPath }
    }

    private fun cacheStats(): Map<String, Any> = OrigamiGestureRenderer.cacheStats().let { mapOf("entryCount" to it.entryCount, "bytes" to it.bytes) }

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
<title>ORIGAMI performance bank</title>
<style>
:root{color-scheme:dark;--bg:#111419;--panel:#191e26;--line:#343c49;--text:#e6e9ee;--dim:#adb6c5;--accent:#8dd6cf;--warm:#eecb83}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif}main{max-width:1080px;margin:auto;padding:32px 20px 80px}h1{font-size:clamp(30px,6vw,48px);line-height:1.15;margin:12px 0}h2{font-size:20px;margin-top:0}p{max-width:78ch}.eyebrow{font:12px ui-monospace,monospace;letter-spacing:.12em;color:var(--accent)}.dim,small{color:var(--dim)}button,select,textarea{font:inherit;color:inherit;background:#222a35;border:1px solid var(--line);border-radius:7px}button,select{min-height:44px;padding:8px 13px}button{cursor:pointer}button:hover{border-color:var(--accent)}button:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--warm);outline-offset:2px}button.active,.play{background:#233e3e;border-color:var(--accent)}a{color:var(--accent)}.row{display:flex;flex-wrap:wrap;gap:10px;align-items:center}.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:20px;margin:22px 0}.transport{position:sticky;top:0;z-index:2;border-color:#557b79}.transport h2{margin:0 0 4px}.status{min-height:24px;color:var(--warm);overflow-wrap:anywhere}audio{width:100%;margin:10px 0}.ab{display:grid;grid-template-columns:auto minmax(0,1fr) auto minmax(0,1fr);gap:8px}.ab select{min-width:0;width:100%}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px}.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px}.card.selected{border-color:var(--accent)}.card h3{font-size:17px;margin:0 0 6px}.card p{font-size:14px;margin:7px 0 14px}.card .row button{flex:1}svg{display:block;width:100%;height:75px;margin:12px 0}.timing{font:13px/1.7 ui-monospace,monospace;color:var(--dim)}.filters{margin:24px 0 16px}details{margin-top:20px}summary{cursor:pointer;min-height:40px}pre{font:12px/1.5 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere;background:#11161d;padding:14px;border-radius:7px;max-height:420px;overflow:auto}textarea{width:100%;min-height:88px;padding:10px}ul{padding-left:24px}.legend{display:flex;justify-content:space-between;font-size:12px;color:var(--dim)}#mode{margin-left:auto}#openBrowser[hidden]{display:none}@media(max-width:580px){main{padding:22px 12px 50px}.panel{padding:14px}.transport{position:static}.ab{grid-template-columns:auto minmax(0,1fr)}.grid{grid-template-columns:1fr}#mode{margin-left:0}}
</style></head><body><main>
<div class="eyebrow">SNIPSNAP / ORIGAMI / PERFORMANCE BANK</div>
<h1>Six ways to play paper.</h1>
<p>Six factory presets pair BLOOM with UNFOLD across CRANE, FAN and STILL. Compare soft, mid and hard touches, then play the phrases and longer held notes.</p>
<p class="dim">Soft and mid are relative to each preset’s saved velocity ceiling. Hard is its exact authored sound. Matched compares color; Native keeps the original levels.</p>
<p class="dim" id="edition"></p>
<section class="panel transport" aria-label="Audio player">
<h2 id="now">Choose a clip</h2><div class="dim" id="caption"></div>
<div class="row"><button class="play" id="play">Play selected</button><button id="stop">Stop</button><label id="modeLabel">Level <select id="mode"><option value="matched">Matched</option><option value="native">Native</option></select></label></div>
<audio id="audio" controls preload="none" playsinline></audio><div class="status" id="status" role="status">Audio is off. Choose Play to begin.</div>
<a id="openBrowser" hidden target="_blank" rel="noopener">Open this page in your browser</a>
<div class="ab"><button id="playA">A</button><select id="a" aria-label="Comparison A"></select><button id="playB">B</button><select id="b" aria-label="Comparison B"></select></div>
<div id="curve"></div><div class="legend"><span>Fold depth · one shared clip gain</span><span id="length"></span></div><div id="timing" class="timing"></div>
</section>
<div class="row filters"><label>Show <select id="category"><option value="singles">Single notes</option><option value="phrases">Phrases + chords</option><option value="held">Long held notes</option><option value="all">All takes</option></select></label><label>Preset <select id="presetFilter"><option value="all">All presets</option></select></label><label>Voice <select id="voice"><option value="all">All voices</option><option>CRANE</option><option>FAN</option><option>STILL</option></select></label><label id="gestureLabel">Gesture <select id="gestureFilter"><option value="all">Both directions</option><option>BLOOM</option><option>UNFOLD</option></select></label><label>Touch <select id="strikeFilter"><option value="all">All touches</option><option value="0.3">Soft</option><option value="0.6">Mid</option><option value="1">Hard</option></select></label></div>
<div id="cards" class="grid"></div>
<section class="panel" id="banks"><h2>MPC instruments</h2><p>Four ready-to-load MPC packages: nine roots, three real touch layers per root, and a 0.60-second release. Use the root .xty on MPC 3 or the TrackData .xpm on MPC 2; keep each complete package together.</p><div class="row" id="bankLinks"></div><p class="dim">These ZIPs are not SnipSnap phone imports. On the phone, select a factory preset and choose MAKE INSTRUMENT. Finite pad layers use SOFT HITS.</p></section>
<section class="panel"><h2>Listening notes</h2><p class="dim">Notes save in this browser for the selected recipe. A meter cannot decide whether the gesture reads clearly or which voice you prefer.</p><textarea id="notes" aria-label="Notes for selected clip" placeholder="Does the change read clearly? Does the held body settle where you expected?"></textarea><div class="row"><button id="export">Export notes</button><a id="download">Download selected WAV</a></div></section>
<section class="panel"><h2>Why it is built this way</h2><ul id="why"></ul><p class="dim">Verified here: exact hard factory sounds and complete production exports. Measured: levels, wraps, storage and render costs on this JVM host. Heard: pending your audition; no phone timing or musical approval is claimed.</p><details><summary>Recipe, levels and render evidence</summary><pre id="recipe"></pre><pre id="bench"></pre><a id="manifestLink">Download manifest</a></details></section>
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
let selected=clips.find(c=>c.id==='fifth-bloom-soft')||clips[0],mode='matched',token=0,watchdog=0,playingId=null;
const noteKey=c=>'origami-performance:'+manifest.sourceSha256+':'+c.recipeSha256;
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
el('now').textContent=selected.title;el('caption').textContent=selected.description;el('curve').innerHTML=selected.foldCurveSvg||'';el('curve').hidden=!selected.foldCurveSvg;el('length').textContent=selected.durationSeconds.toFixed(2)+' s';
el('timing').textContent=selected.category==='phrases'?'120 BPM · minor-pentatonic line + minor triad · fixed phrase bus gain 0.10':selected.held?'Three played notes: soft / mid / hard · each selected layer crosses at least three actual wraps · release 0.60 s per note · one gain for the complete sequence':'Change begins '+selected.gestureStartSeconds.toFixed(3)+' s · lasts '+selected.gestureDurationSeconds.toFixed(3)+' s · settles at '+selected.gestureEndSeconds.toFixed(3)+' s · effective FOLD '+Math.round(selected.effectiveFold*100)+'% · rendered velocity '+selected.performedVelocity.toFixed(3);
el('notes').value=readNote(selected);el('recipe').textContent=JSON.stringify({recipe:selected.recipe,matchedGain:selected.matchedGain,nativeMetrics:selected.nativeMetrics,matchedMetrics:selected.matchedMetrics,rawBeforeOutputMetrics:selected.rawBeforeOutputMetrics,renderTiming:selected.renderTiming,heldEvidence:selected.heldEvidence,recipeSha256:selected.recipeSha256,nativeFileSha256:selected.nativeFileSha256,matchedFileSha256:selected.matchedFileSha256},null,2);
el('download').href=source(selected);el('download').download=selected.id+'-'+mode+'.wav';}
function drawCards(){const category=el('category').value,voice=el('voice').value,preset=el('presetFilter').value,gesture=el('gestureFilter').value,strike=el('strikeFilter').value;el('cards').replaceChildren();clips.filter(c=>(category==='all'||c.category===category)&&(voice==='all'||c.voice===voice)&&(preset==='all'||c.presetId===preset)&&(gesture==='all'||c.gesture===gesture)&&(strike==='all'||(c.velocity!==null&&Math.abs(c.velocity-Number(strike))<0.00001))).forEach(c=>{
const card=document.createElement('article');card.className='card'+(c.id===selected.id?' selected':'');card.dataset.clipId=c.id;
const title=document.createElement('h3');title.textContent=c.title;const desc=document.createElement('p');desc.className='dim';desc.textContent=c.description;
const line=document.createElement('div');line.className='row';const p=document.createElement('button');p.textContent='Play';p.onclick=()=>play(c.id);const inspect=document.createElement('button');inspect.textContent='Select';inspect.onclick=()=>{stop();choose(c.id);};line.append(p,inspect);
card.append(title,desc,line);el('cards').append(card);});}
function saveFile(name,value){const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
el('play').onclick=()=>play(selected.id);el('stop').onclick=stop;
el('mode').onchange=()=>{stop();mode=el('mode').value;drawSelected();};
for(const key of ['category','voice','presetFilter','gestureFilter','strikeFilter'])el(key).onchange=drawCards;
for(const key of ['a','b']){for(const c of clips){const o=document.createElement('option');o.value=c.id;o.textContent=c.title;el(key).append(o);}}
el('a').value=(clips.find(c=>c.id==='fifth-bloom-soft')||clips[0]).id;el('b').value=(clips.find(c=>c.id==='fifth-bloom-hard')||selected).id;
el('playA').onclick=()=>play(el('a').value);el('playB').onclick=()=>play(el('b').value);
el('notes').oninput=()=>{try{localStorage.setItem(noteKey(selected),el('notes').value);}catch(e){status('This browser could not save notes. Export them before closing.');}};
el('export').onclick=()=>saveFile('origami-performance-feedback.json',{listeningRound:manifest.listeningRound,sourceSha256:manifest.sourceSha256,recipeSha256:manifest.recipeSha256,exportedUtc:new Date().toISOString(),notes:clips.map(c=>({id:c.id,recipeSha256:c.recipeSha256,nativeFileSha256:c.nativeFileSha256,matchedFileSha256:c.matchedFileSha256,note:readNote(c)})).filter(c=>c.note)});
for(const text of manifest.whyItIsBuiltThisWay){const li=document.createElement('li');li.textContent=text;el('why').append(li);}
el('bench').textContent=manifest.benchmarkPolicy+'\n\n'+JSON.stringify({factoryHardIdentities:manifest.hardFactoryIdentities,memoryPolicy:manifest.memoryPolicy,instrumentTotals:manifest.instrumentTotals,instrumentStorage:manifest.instruments.map(b=>({preset:b.presetName,pitchZoneCount:b.pitchZoneCount,layersPerZone:b.layersPerZone,sampleCount:b.sampleCount,frameCount:b.frameCount,floatResidentBytes:b.floatResidentBytes,exportedWavBitDepth:b.exportedWavBitDepth,exportedWavDiskBytes:b.exportedWavDiskBytes,pcm16EncodedBytesMeasured:b.pcm16EncodedBytesMeasured,renderMilliseconds:b.renderMilliseconds}))},null,2);
for(const preset of manifest.factoryPresets){const option=document.createElement('option');option.value=preset.id;option.textContent=preset.name;el('presetFilter').append(option);}
for(const bank of manifest.instruments){const a=document.createElement('a');a.href=bank.downloadPath;a.download=bank.id+'.zip';a.textContent=bank.presetName+' · MPC ZIP ('+(bank.downloadBytes/1048576).toFixed(1)+' MiB)';el('bankLinks').append(a);}
const manifestUrl=URL.createObjectURL(new Blob([JSON.stringify(manifest,null,2)],{type:'application/json'}));el('manifestLink').href=manifestUrl;el('manifestLink').download='origami-performance-manifest.json';
if(compact){el('edition').textContent='Self-contained starter · 18 matched singles from six factory presets. The complete pack adds musical phrases, long held notes and four MPC downloads.';el('modeLabel').hidden=true;el('banks').hidden=true;for(const option of Array.from(el('category').options)){if(!['singles','all'].includes(option.value))option.remove();}}
else el('edition').textContent='Full performance bank · '+clips.length+' takes. Native and Matched levels, musical phrases, actual exported instruments and long held notes.';
try{if(window.self!==window.top){el('openBrowser').hidden=false;el('openBrowser').href=window.location.href;}}catch(e){}
drawSelected();drawCards();
</script></body></html>
""".trimIndent()
}
