package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scales
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.FlockHeld
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockPresets
import com.snipsnap.synth.FlockTouchKeys
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** A new touch-layer handoff. Historical finite and single-layer auditions are never rewritten.
 * Performances reload production PCM24 and resolve each touch through the real keygroup player.
 * Matched comparisons receive one disclosed gain scalar after reference playback; packages do not.
 */
object FlockVelocityInstrumentGenerator {
    const val NAME = "SnipSnap Flock Ribbon Touch"
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val PART_BUDGET = 24L * 1024 * 1024
    private val TOUCH_VELOCITIES = listOf(32, 64, 127)

    private data class Take(val snip: Snip, val metadata: Map<String, Any?>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply only the output root directory." }
        val repository = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val output = File(args.firstOrNull() ?: "../testkit/flock-velocity-instrument").canonicalFile
        val protected = listOf("flock-audition", "flock-flow-audition", "flock-ribbon-sustain", "flock-held-instrument")
            .map { File(repository, "testkit/$it").canonicalFile.toPath() }
        require(output != repository && !repository.toPath().startsWith(output.toPath()) &&
            protected.none { output.toPath().startsWith(it) || it.startsWith(output.toPath()) }) {
            "Choose a generated directory separate from every historical FLOCK audition."
        }
        require(output.isDirectory || output.mkdirs()) { "Could not create $output" }
        val commit = git(repository, "rev-parse", "HEAD")
        val dirty = git(repository, "status", "--porcelain").isNotEmpty()
        val bundle = File(output, NAME)
        require(!bundle.exists() || bundle.deleteRecursively()) { "Could not clear generated bundle $bundle" }
        require(bundle.mkdirs())
        val patch = FlockPresets.forVoice(FlockVoice.RIBBON).single { it.name == "Traveling Turn" }
            .copy(playback = FlockPlayback.HELD, releaseSeconds = .6f)
        require(patch.seed == 42001L && patch.model == 2 && patch.midi == 60 && patch.velocity == 1f)
        val spec = FlockPadMaker.Spec(patch, touchLayers = true)
        val roots = FlockPadMaker.zoneMidis(spec)
        require(roots == (48..72 step 3).toList())
        val notes = ArrayList<List<KeyNote>>()
        val timings = ArrayList<Double>()
        val bankEvidence = ArrayList<Map<String, Any?>>()
        var cachedRootMillis = 0.0
        for (midi in roots) {
            val started = System.nanoTime()
            val bank = FlockTouchKeys.probe(patch, midi)
            notes += bank.layers.map { it.note }
            timings += millis(started)
            require(bank.layers.map { it.velocityWindow } == FlockTouchKeys.VELOCITY_WINDOWS)
            bankEvidence += linkedMapOf("sharedHeadroomGain" to bank.sharedHeadroomGain,
                "layers" to bank.layers.map { layer ->
                    val d = layer.sourceDiagnostics
                    val search = d.endpointSearch
                    linkedMapOf("touch" to layer.touch, "excitationVelocity" to layer.excitationVelocity,
                        "levelGain" to layer.levelGain, "sourceCaptureGain" to d.commonGain,
                        "sourceOutputPeak" to d.outputPeak, "sourceFrames" to d.sourceFrames,
                        "blendCorrelation" to d.blendCorrelation,
                        "nominalLoopFrames" to search.nominalLoopFrames, "selectedLoopFrames" to search.selectedLoopFrames,
                        "searchRadiusFrames" to search.radiusFrames, "evaluatedCandidates" to search.evaluatedCandidates,
                        "selectedMin50msDb" to search.selectedMin50msDb, "selectedMax50msDb" to search.selectedMax50msDb)
                })
            if (midi == patch.midi) {
                val repeatStarted = System.nanoTime()
                val repeated = FlockPadMaker.renderLayers(spec, midi)
                cachedRootMillis = millis(repeatStarted)
                repeated.zip(bank.layers).forEach { (actual, layer) ->
                    require(actual.loopStartFrame == layer.note.loopStartFrame &&
                        actual.snip.samples.contentEquals(layer.note.snip.samples)) { "Maker/probe or cache changed audio." }
                }
            }
            println("captured ${Scales.nameOf(midi)}: three layers in ${format(timings.last())} ms")
        }
        FlockPadMaker.exportLayers(NAME, spec, notes, bundle)
        require(FlockPatch.fromJsonText(File(bundle, "$NAME.patch.json").readText()) == patch)
        val deliveryRecipe = File(bundle, "$NAME.instrument.recipe.json")
        require(deliveryRecipe.isFile) { "Touch export must include its versioned delivery recipe." }
        val instrument = InstrumentStore.read(InstrumentStore.sidecar(bundle, NAME))
        require(instrument.zones.map { it.rootNote } == roots && instrument.release == patch.releaseSeconds)
        require(instrument.zones.first().lowNote == roots.first() && instrument.zones.last().highNote == roots.last())
        require(instrument.sampleFiles.size == 27)
        val decoded = instrument.sampleFiles.associateWith { WavReader.read(File(bundle, it)) }
        val zones = instrument.zones.mapIndexed { zoneIndex, zone ->
            require(zone.playableLayers.size == spec.layerCount)
            val layers = zone.playableLayers.mapIndexed { layerIndex, layer ->
                val window = FlockTouchKeys.VELOCITY_WINDOWS[layerIndex]
                require(layer.velStart..layer.velEnd == window)
                val note = notes[zoneIndex][layerIndex]
                val file = File(bundle, layer.sample)
                val sample = decoded.getValue(layer.sample)
                val info = WavInfo.read(file)
                require(info.sampleRate == RATE && info.channels == 1 && info.bitsPerSample == 24)
                require(info.frameCount == layer.frameCount && sample.frameCount == note.snip.frameCount)
                require(layer.loopStartFrame == note.loopStartFrame)
                require(WavReader.readSmpl(file) == SmplChunk(zone.rootNote,
                    SmplChunk.Loop(layer.loopStartFrame, layer.frameCount))) { "WAV sampler bounds disagree with layer metadata." }
                val error = sample.samples.indices.maxOf { abs(sample.samples[it] - note.snip.samples[it]) }
                require(error <= 2e-7f) { "PCM24 encoding error $error exceeds its precision." }
                linkedMapOf<String, Any?>(
                    "layer" to layerIndex, "touch" to FlockTouchKeys.TOUCHES[layerIndex],
                    "excitationVelocity" to patch.velocity * FlockTouchKeys.TOUCHES[layerIndex],
                    "velocityWindow" to listOf(window.first, window.last),
                    "file" to relative(output, file), "sha256" to sha(file),
                    "frames" to layer.frameCount, "loopStartFrame" to layer.loopStartFrame,
                    "loopEndExclusive" to layer.frameCount,
                    "loopSeconds" to (layer.frameCount - layer.loopStartFrame).toDouble() / RATE,
                    "maxPcm24Error" to error, "decodedAudio" to audioStats(sample, zone.rootNote),
                    "sustainLoudness" to Loudness.of(sample.samples.copyOfRange(layer.loopStartFrame.toInt(), sample.samples.size), 1, RATE),
                    "capture" to (bankEvidence[zoneIndex].getValue("layers") as List<*>)[layerIndex],
                )
            }
            linkedMapOf<String, Any?>("rootMidi" to zone.rootNote, "note" to Scales.nameOf(zone.rootNote),
                "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                "renderMillis" to timings[zoneIndex], "sharedHeadroomGain" to bankEvidence[zoneIndex].getValue("sharedHeadroomGain"),
                "layers" to layers)
        }
        val formats = verifyMpc(bundle, instrument)
        val loaded = InstrumentEngine.Loaded(instrument, RATE, decoded.mapValues { it.value.samples })
        val clips = ArrayList<Map<String, Any?>>()
        val rootTakes = TOUCH_VELOCITIES.map { velocity -> playback(loaded, patch.midi, velocity, 10) }
        // Compare character at the familiar hard-touch listening level. Matching
        // down to the quietest touch would bury this diagnostic in phone noise.
        val matchedTarget = minOf(rms(rootTakes.last().snip.samples), rootTakes.minOf {
            rms(it.snip.samples) * .85 / it.snip.peak().coerceAtLeast(1e-30f)
        })
        rootTakes.forEachIndexed { index, take ->
            val velocity = TOUCH_VELOCITIES[index]
            val label = listOf("soft", "mid", "hard")[index]
            clips += writeClip(output, "touch_$label", "$label touch", "touch", take)
            val gain = matchedTarget / rms(take.snip.samples).coerceAtLeast(1e-30)
            clips += writeClip(output, "matched_$label", "$label touch, RMS matched", "matched", take, gain)
        }
        for (midi in listOf(roots.first(), patch.midi, roots.last())) {
            clips += writeClip(output, "held_${midi}_${Scales.nameOf(midi)}", "${Scales.nameOf(midi)} held", "register",
                playback(loaded, midi, 127, 30))
        }
        val boundaryRows = listOf(42 to 43, 84 to 85).map { (low, high) ->
            val takes = listOf(low, high).map { velocity ->
                val take = playback(loaded, patch.midi, velocity, 8)
                clips += writeClip(output, "boundary_$velocity", "MIDI $velocity", "boundary", take)
                take
            }
            linkedMapOf("lowVelocity" to low, "highVelocity" to high,
                "wholeTakeRmsStepDb" to db(rms(takes[1].snip.samples) / rms(takes[0].snip.samples)),
                "playerGainStepDb" to db(high.toDouble() / low),
                "files" to listOf("listening/boundary_$low.wav", "listening/boundary_$high.wav"))
        }
        val sources = snapshotSources(repository, bundle)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "FLOCK", "voice" to "RIBBON", "playback" to "HELD",
            "title" to NAME, "sourceCommit" to commit, "workingTreeDirty" to dirty,
            "sourceFilesSha256" to sources, "patch" to patch.toJsonValue(),
            "touchLayers" to true, "touchRenderVersion" to FlockTouchKeys.RENDER_VERSION,
            "touches" to FlockTouchKeys.TOUCHES,
            "velocityWindows" to FlockTouchKeys.VELOCITY_WINDOWS.map { listOf(it.first, it.last) },
            "liveTouchRepresentatives" to TOUCH_VELOCITIES,
            "sampleRate" to RATE, "channels" to 1, "sampleBits" to 24,
            "nativeFormat" to "MPC3 keygroup .xty", "compatibilityFormat" to "MPC2 keygroup .xpm",
            "loopEndConvention" to "exclusive; RIFF smpl stores end minus one",
            "zoneCoverage" to listOf(roots.first(), roots.last()), "maxTranspositionSemitones" to 1,
            "sampleCount" to instrument.sampleFiles.size, "zones" to zones,
            "formatReadback" to formats, "referencePlayback" to clips, "boundaries" to boundaryRows,
            "renderTimingLimit" to "Kotlin JVM wall-clock worker measurements, including warmup. Three captures per root; not Android latency measurements.",
            "captureCache" to linkedMapOf("maxEntries" to FlockHeld.CACHE_MAX_ENTRIES, "maxBytes" to FlockHeld.CACHE_MAX_BYTES,
                "repeatThreeLayerRootMillis" to cachedRootMillis, "repeatAudioExact" to true),
            "verified" to listOf("source patch round trip", "versioned delivery recipe", "27 PCM24 sample reloads",
                "phone/MPC2/MPC3 velocity-window and loop metadata agreement", "WAV smpl roots and bounds",
                "KeyHit sample selection at each listening touch", "reference note-off silence", "archive CRC and SHA-256 readback"),
            "hardwareHeard" to false, "phoneHeard" to false,
            "limitations" to listOf("A captured movement repeats; the equations are not made periodic.",
                "Three discrete source strengths switch at MIDI 42/43 and 84/85; no realtime synthesis, crossfade or aftertouch.",
                "Sustain levels are matched before the existing linear touch gain; soft and hard sources retain different motion.",
                "Reference playback is InstrumentEngine, not Android or MPC hardware.",
                "RMS-matched listening clips have an explicitly reported scalar; instrument samples and raw touch clips do not."),
        )
        File(bundle, "README.txt").writeText(readme())
        writeJson(File(bundle, "manifest.json"), manifest)
        File(bundle, "SHA256SUMS.txt").writeText(bundle.walkTopDown().filter { it.isFile }
            .sortedBy { relative(bundle, it) }.joinToString("\n") { "${sha(it)}  ${relative(bundle, it)}" } + "\n")
        val downloads = archives(output, bundle)
        manifest["downloads"] = downloads
        writeJson(File(output, "manifest.json"), manifest)
        File(output, "README.txt").writeText(readme())
        File(output, "downloads/SHA256SUMS.txt").writeText(downloads.joinToString("\n") {
            "${it.getValue("sha256")}  ${File(it.getValue("file") as String).name}"
        } + "\n")
        require(git(repository, "rev-parse", "HEAD") == commit) { "Source commit changed during generation." }
        println("27 exported samples in ${downloads.size} ZIP parts; ${clips.size} reference clips. Hardware listening remains unverified.")
    }

    /** Playback resolves the selected layer rather than relying on a zone's hardest headline sample. */
    private fun playback(loaded: InstrumentEngine.Loaded, midi: Int, midiVelocity: Int, heldSeconds: Int): Take {
        val touch = midiVelocity / 127f
        val hit = requireNotNull(KeyHit.resolve(loaded.instrument, midi, touch) { loaded.layerSamples[it]?.size?.toLong() })
        val zone = requireNotNull(loaded.instrument.zoneFor(midi))
        val layer = requireNotNull(zone.layerFor(midiVelocity))
        require(hit.sampleFile == layer.sample && hit.frames == layer.frameCount && hit.loopStartFrame == layer.loopStartFrame)
        val engine = InstrumentEngine(loaded, RATE)
        require(engine.noteOn(midi, touch))
        val heldFrames = heldSeconds * RATE
        val tailFrames = ceil((loaded.instrument.release.toDouble() + .1) * RATE).toInt()
        val out = FloatArray(heldFrames + tailFrames)
        val block = FloatArray(512)
        fun render(from: Int, frames: Int) {
            var at = 0
            while (at < frames) {
                val count = minOf(256, frames - at)
                engine.render(block, count)
                for (i in 0 until count) {
                    require(block[i * 2] == block[i * 2 + 1])
                    out[from + at + i] = block[i * 2]
                }
                at += count
            }
        }
        render(0, heldFrames)
        require(engine.activeNotes == listOf(midi))
        engine.noteOff(midi)
        render(heldFrames, tailFrames)
        require(engine.activeNotes.isEmpty() && out.takeLast(RATE / 20).all { it == 0f })
        val snip = Snip(out, 1, RATE)
        require(out.all { it.isFinite() } && snip.peak() < .9f) { "Reference take reached nonlinear bus shaping." }
        val wraps = ArrayList<Map<String, Any?>>()
        var frame = hit.frames / hit.pitchRatio
        val period = (hit.frames - hit.loopStartFrame) / hit.pitchRatio
        while (frame < heldFrames) {
            val i = ceil(frame).toInt()
            wraps += linkedMapOf("frame" to i, "seconds" to i.toDouble() / RATE,
                "step" to abs(out[i] - out[i - 1]), "slopeChange" to abs(out[i + 1] - 2f * out[i] + out[i - 1]))
            frame += period
        }
        return Take(snip, linkedMapOf("midi" to midi, "midiVelocity" to midiVelocity, "touchVelocity" to touch,
            "selectedSample" to hit.sampleFile, "velocityWindow" to listOf(layer.velStart, layer.velEnd),
            "gain" to hit.gain, "heldSeconds" to heldSeconds, "noteOffFrame" to heldFrames,
            "releaseSeconds" to loaded.instrument.release, "silentAfterRelease" to true, "wraps" to wraps))
    }

    private fun writeClip(output: File, id: String, title: String, section: String, take: Take,
        comparisonGain: Double = 1.0): Map<String, Any?> {
        require(comparisonGain.isFinite() && comparisonGain > 0.0)
        val audio = if (comparisonGain == 1.0) take.snip else Snip(FloatArray(take.snip.samples.size) {
            (take.snip.samples[it] * comparisonGain).toFloat()
        }, 1, RATE)
        require(audio.samples.all { it.isFinite() } && audio.peak() < .9f)
        val file = File(output, "listening/$id.wav")
        WavWriter.write(file, audio, WavWriter.BitDepth.PCM_16)
        return linkedMapOf("id" to id, "title" to title, "section" to section, "file" to relative(output, file),
            "sha256" to sha(file), "comparisonGain" to comparisonGain,
            "matched" to (section == "matched"), "playback" to take.metadata,
            "audio" to audioStats(audio, take.metadata.getValue("midi") as Int))
    }

    /** Both file formats must describe each layer's own period, not just the hardest layer's. */
    private fun verifyMpc(bundle: File, instrument: InstrumentStore.Instrument): Map<String, Any?> {
        val data = File(bundle, "${NAME}_[TrackData]")
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(data, "$NAME.xpm"))
        val nodes = xml.getElementsByTagName("Layer")
        val xmlLayers = (0 until nodes.length).map { nodes.item(it) as Element }.filter {
            it.getElementsByTagName("SampleName").item(0).textContent.isNotEmpty()
        }.associateBy { it.getElementsByTagName("SampleName").item(0).textContent }
        val nativeLayers = objects(Json.parse(Acvs.read(File(bundle, "$NAME.xty")).payloadText)).filter {
            "sliceInfo" in it && it["sampleName"]?.str()?.isNotEmpty() == true
        }.associateBy { it.getValue("sampleName").str() }
        require(xmlLayers.size == 27 && nativeLayers.size == 27)
        for (zone in instrument.zones) for (layer in zone.playableLayers) {
            val stem = File(layer.sample).nameWithoutExtension
            val element = xmlLayers.getValue(stem)
            fun field(name: String) = element.getElementsByTagName(name).item(0).textContent
            require(field("VelStart").toInt() == layer.velStart && field("VelEnd").toInt() == layer.velEnd)
            require(field("RootNote").toInt() == zone.rootNote && field("SliceLoopStart").toLong() == layer.loopStartFrame)
            require(field("SliceEnd").toLong() == layer.frameCount && field("SliceLoop").toInt() == 1)
            val actual = nativeLayers.getValue(stem)
            require(actual.getValue("velocityStart").int() == layer.velStart && actual.getValue("velocityEnd").int() == layer.velEnd)
            require(actual.getValue("rootNote").int() == zone.rootNote)
            val slice = actual.getValue("sliceInfo").obj()
            require(slice.getValue("LoopStart").long() == layer.loopStartFrame && slice.getValue("End").long() == layer.frameCount)
            require(slice.getValue("LoopMode").int() == 1)
        }
        return linkedMapOf("nativeLayerCount" to nativeLayers.size, "compatibilityLayerCount" to xmlLayers.size,
            "allRootsWindowsLoopStartsAndEndsAgree" to true)
    }

    private fun objects(value: JsonValue): Sequence<Map<String, JsonValue>> = sequence {
        when (value) {
            is JsonValue.Obj -> { yield(value.entries); value.entries.values.forEach { yieldAll(objects(it)) } }
            is JsonValue.Arr -> value.items.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }

    private fun snapshotSources(repository: File, bundle: File): Map<String, String> = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Flock.kt", "synth/src/main/kotlin/com/snipsnap/synth/FlockHeld.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/FlockTouchKeys.kt", "synth/src/main/kotlin/com/snipsnap/synth/FlockPatch.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/FlockPresets.kt", "synth/src/main/kotlin/com/snipsnap/synth/FlockRibbonSustain.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "shell/src/main/kotlin/com/snipsnap/shell/FlockPadMaker.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt", "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt", "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/SmplChunk.kt", "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
        "xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt",
        "shell/src/test/kotlin/com/snipsnap/shell/FlockVelocityInstrumentGenerator.kt", "shell/build.gradle.kts",
    ).associateWith { path ->
        val file = File(repository, path)
        file.copyTo(File(bundle, "source/$path").apply { parentFile.mkdirs() })
        sha(file)
    }

    /** Ordinary ZIP parts share one folder, contain every file once, and independently fit the host limit. */
    private fun archives(output: File, bundle: File): List<Map<String, Any?>> {
        val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.toList()
        val metadata = files.filterNot { it.extension.equals("wav", true) }
        val groups = ArrayList<List<File>>()
        var current = metadata.toMutableList()
        var size = current.sumOf { it.length() }
        require(size < PART_BUDGET)
        for (file in files.filter { it.extension.equals("wav", true) }) {
            require(file.length() < PART_BUDGET)
            if (size + file.length() >= PART_BUDGET) { groups += current; current = ArrayList(); size = 0 }
            current += file; size += file.length()
        }
        if (current.isNotEmpty()) groups += current
        val expected = files.map { "${bundle.name}/${relative(bundle, it)}" }.toSet()
        val covered = ArrayList<String>()
        val retained = ArrayList<File>()
        val downloads = File(output, "downloads").apply { mkdirs() }
        val result = groups.mapIndexed { index, group ->
            val archive = File(downloads, "SnipSnap-Flock-Ribbon-Touch-part-${index + 1}.zip")
            retained += archive.canonicalFile
            ZipOutputStream(archive.outputStream().buffered()).use { out ->
                out.setLevel(Deflater.BEST_COMPRESSION)
                group.sortedBy { relative(bundle, it) }.forEach { file ->
                    out.putNextEntry(ZipEntry("${bundle.name}/${relative(bundle, file)}").apply { time = 0 })
                    file.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
            }
            val entries = ZipFile(archive).use { zip -> zip.entries().asSequence().map { entry ->
                val file = File(bundle.parentFile, entry.name)
                require(file.canonicalFile.toPath().startsWith(bundle.canonicalFile.toPath()))
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                require(sha(bytes) == sha(file) && CRC32().apply { update(bytes) }.value == entry.crc)
                entry.name
            }.toList() }
            covered += entries
            require(archive.length() < PART_BUDGET) { "Archive exceeds 24 MiB safe asset budget." }
            linkedMapOf<String, Any?>("file" to relative(output, archive), "part" to index + 1, "parts" to groups.size,
                "label" to "Instrument part ${index + 1} of ${groups.size}", "extractTogether" to true,
                "bytes" to archive.length(), "sha256" to sha(archive), "entries" to entries,
                "zipCrcVerified" to true, "zipRoundTripByteExact" to true, "packageFilesCoveredExactlyOnce" to true)
        }
        require(covered.size == expected.size && covered.toSet() == expected)
        downloads.listFiles().orEmpty().filter {
            it.name.matches(Regex("SnipSnap-Flock-Ribbon-Touch-part-[0-9]+\\.zip")) && it.canonicalFile !in retained
        }.forEach { require(it.delete()) }
        return result
    }

    private fun audioStats(snip: Snip, midi: Int): Map<String, Any?> {
        val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val pitches = listOf(2f, 4f, 6f).filter { it + .25f <= snip.durationSeconds }.map { second ->
            val pitch = Pitch.detect(snip, second, .25f)
            linkedMapOf("fromSeconds" to second, "hz" to pitch?.hz, "confidence" to pitch?.confidence,
                "centsFromRoot" to pitch?.let { 1200.0 * ln(it.hz / expected) / ln(2.0) })
        }
        return linkedMapOf("frames" to snip.frameCount, "seconds" to snip.durationSeconds,
            "peak" to snip.peak(), "mean" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
            "rms" to rms(snip.samples), "loudness" to Loudness.of(snip), "pitchWindows" to pitches)
    }

    private fun readme() = """
        FLOCK RIBBON TOUCH instrument

        A gentle touch starts a less disturbed flock; a hard touch starts the saved
        performance. Every source keeps its own arrival, captured moving body and
        0.6-second player release. This is sampled velocity response, not aftertouch.

        DOWNLOAD AND LOAD
        Download EVERY numbered ZIP part and extract all parts into the SAME folder.
        These are ordinary ZIPs, not a split-ZIP stream. Each file appears once;
        program metadata is in part 1. Keep the combined folder intact.
        Verify downloads/SHA256SUMS.txt before extraction, then the instrument's
        SHA256SUMS.txt after extraction.
        MPC 3: open "$NAME.xty" beside its _[TrackData] folder.
        MPC 2: open "$NAME.xpm" inside the _[TrackData] folder.
        SnipSnap: keep the .instrument.json beside the same sample folder.

        Nine roots cover MIDI 48 through 72 (C3 through C5). Each root has three
        independent mono PCM24 performances, 27 samples in all. MIDI velocities
        1..42, 43..84 and 85..127 select sources rendered at the saved velocity
        times .35, .65 and 1. The saved velocity remains the hard-touch anchor.
        Sustain loudness is matched before the player's ordinary touch gain,
        with a common headroom scalar per root. No per-touch synthesis is needed.
        Every layer has its own roughly six-second captured period after a
        two-second introduction. Loop ends are exclusive; RIFF stores end - 1.
        The source patch and versioned delivery recipe accompany the instrument.

        LISTENING (separate from the playable download parts)
        touch_soft/mid/hard: actual exported samples through InstrumentEngine at
        MIDI velocity 32/64/127, held ten seconds and then released.
        matched_soft/mid/hard: the SAME performances with one disclosed RMS scalar
        for judging timbre at equal level. Their extra gain is not in the instrument.
        held_48_C3, held_60_C4, held_72_C5: hard touches held thirty seconds.
        boundary_42/43/84/85: adjacent MIDI touches across the two layer thresholds,
        held eight seconds; no comparison leveling is applied.
        Listening WAVs are PCM16 mono without effects. Manifest records each
        actual sample selected, wrap times, gains, readback checks and checksums.

        Timings are Kotlin JVM worker measurements, not target-phone latency.
        Phone/MPC listening and layer-switch acceptance remain unverified.
        The flock equations do not become periodic. Listen for repeated movement,
        cancellation in the blends, level steps at thresholds and release feel.

        Rebuild: ./gradlew --no-daemon :shell:generateFlockVelocityInstrument
        Optional output: -PflockVelocityOutput=/absolute/path/to/output

        Why it is built this way
        - Each layer changes the disturbance entering the original flock model.
        - Sustain matching avoids counting source velocity and touch loudness twice.
        - Three sampled strengths reuse the existing phone and MPC keygroup players.
        - Every layer retains its own attack and loop, rather than sharing false bounds.
        - The risky choice is switching between discrete performances; judge thresholds.
    """.trimIndent() + "\n"

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun db(ratio: Double) = 20.0 * ln(ratio.coerceAtLeast(1e-30)) / ln(10.0)
    private fun millis(started: Long) = (System.nanoTime() - started) / 1_000_000.0
    private fun format(value: Double) = "%.2f".format(Locale.ROOT, value)
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "Could not read source provenance: $result" }
        return result
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> value.toDouble().let { require(it.isFinite()); JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
}
