package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.OhmPresets
import com.snipsnap.xpm.WavInfo
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/** The five approved finite factory encounters through the production native-zone exporter. */
object OhmReplyRingHandoffGenerator {
    const val BUNDLE = "SnipSnap OHM Reply Ring Instruments"
    const val ARCHIVE = "SnipSnap-OHM-Reply-Ring-Instruments.zip"
    private const val MODEL = 5
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val FRAMES = 7 * RATE

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply only the destination directory." }
        val repository = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val output = File(args.firstOrNull() ?: "testkit/ohm-reply-ring-handoff").canonicalFile
        require(output != repository && !repository.toPath().startsWith(output.toPath())) { "Choose a generated output directory." }
        require(output.isDirectory || output.mkdirs()) { "Could not create $output" }
        val bundle = File(output, BUNDLE)
        require(!bundle.exists() || bundle.deleteRecursively()) { "Could not replace generated bundle $bundle" }
        require(bundle.mkdirs())
        val sourcePaths = sourcePaths()
        val sourceHashes = sourcePaths.associateWith { sha(File(repository, it)) }
        val sourceCommit = git(repository, "rev-parse", "HEAD")
        val sourceDirty = git(repository, "status", "--porcelain").isNotEmpty()
        val patches = OhmPresets.all().filter { it.model == MODEL }
        require(patches.size == 5 && patches.map { it.name }.distinct().size == 5)
        require(patches.map { it.reply to it.ring }.toSet() == setOf(.5f to .5f, 0f to .5f, 1f to .5f, .5f to 0f, .5f to 1f))
        val folder = File(bundle, "instruments").apply { mkdirs() }
        val instruments = patches.map { patch ->
            // Keep the complete factory recipe. Only each native zone's MIDI changes below.
            require(patch.midi == 60 && patch.velocity == .8f && patch.seed == 731 && patch.releaseSeconds == .3f)
            require("HOLD" !in patch.macros)
            val spec = OhmPadMaker.Spec(patch)
            val roots = OhmPadMaker.zoneMidis(spec)
            require(roots == (48..72 step 3).toList())
            val notes = roots.map { midi ->
                println("Rendering ${patch.name}: ${Scales.nameOf(midi)}")
                OhmPadMaker.renderZone(spec, midi).also { note ->
                    require(note.loopStartFrame == 0L && note.snip.frameCount == FRAMES)
                    require(note.snip.channels == 1 && note.snip.sampleRate == RATE)
                }
            }
            val program = OhmPadMaker.export(patch.name, spec, notes, folder)
            require(program.explicitNoteOn && program.volumeRelease == patch.releaseSeconds)
            val recipeFile = File(folder, "${patch.name}.patch.json")
            val saved = OhmPatch.fromJsonText(recipeFile.readText())
            require(saved == patch) { "Saved factory recipe lost a source or performance field." }
            val sidecarFile = InstrumentStore.sidecar(folder, patch.name)
            val instrument = InstrumentStore.read(sidecarFile)
            require(instrument.explicitNoteOn && instrument.release == patch.releaseSeconds)
            require(instrument.zones.map { it.rootNote } == roots)
            require(instrument.zones.first().lowNote == 48 && instrument.zones.last().highNote == 72)
            require((48..72).all { instrument.zoneFor(it) != null } && instrument.zoneFor(47) == null && instrument.zoneFor(73) == null)
            require(abs(KeyHit.releaseMs(instrument) - 300f) < .001f)

            val nativeFile = File(folder, "${patch.name}.xty")
            val nativeProject = Mpc3Project.read(nativeFile)
            val nativeProgram = nativeProject.programs().single()
            require(nativeProject.isTrack && nativeProgram.getValue("type").int() == 1)
            require(nativeProgram.getValue("keygroup").obj().getValue("numKeygroups").int() == roots.size)
            val native = nativeProject.drumPrograms().single().getValue("instruments").arr()
            require(native.size == 128)
            val samplePool = requireNotNull(nativeProject.data).getValue("samples").arr()
            require(samplePool.size == roots.size)
            val xpmFile = File(folder, "${patch.name}_[TrackData]/${patch.name}.xpm")
            val xmlFactory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val document = xmlFactory.newDocumentBuilder().parse(xpmFile)
            require((document.getElementsByTagName("Program").item(0) as Element).getAttribute("type") == "Keygroup")
            val xml = document.getElementsByTagName("Instrument")
            require(xml.length == roots.size)
            val decoded = mutableListOf<FloatArray>()
            val zones = instrument.zones.mapIndexed { index, zone ->
                val file = File(folder, zone.sample)
                val info = WavInfo.read(file)
                val audio = WavReader.read(file)
                decoded += audio.samples
                require(info.channels == 1 && info.sampleRate == RATE && info.bitsPerSample == 24 && info.frameCount == FRAMES.toLong())
                require(zone.frameCount == FRAMES.toLong() && zone.loopStartFrame == 0L)
                require(WavReader.readSmplStrict(file) == SmplChunk(zone.rootNote, null)) { "Finite zone gained a sampler loop or lost its root." }
                val error = audio.samples.indices.maxOf { abs(audio.samples[it] - notes[index].snip.samples[it]) }
                require(error <= 2e-7f) { "PCM24 encoding error exceeds precision: $error" }
                val layer = program.keygroups[index].layers.single()
                require(layer.loopStartFrame == 0L && layer.frameCount == FRAMES.toLong())

                val n = native[index].obj()
                require(n.getValue("triggerMode").int() == 2 && n.getValue("lowNote").int() == zone.lowNote && n.getValue("highNote").int() == zone.highNote)
                val envelope = n.getValue("synthSection").obj().getValue("ampEnvelope").obj()
                require(envelope.getValue("Release").obj().getValue("value0").num() == patch.releaseSeconds.toDouble())
                require(envelope.getValue("Sustain").obj().getValue("value0").num() == 1.0)
                require(!envelope.getValue("OneShot").obj().getValue("value0").bool())
                val nativeLayer = n.getValue("layersv").arr().first().obj()
                val slice = nativeLayer.getValue("sliceInfo").obj()
                require(nativeLayer.getValue("sampleName").str() == layer.sampleName && nativeLayer.getValue("sampleFile").str() == file.name)
                require(nativeLayer.getValue("rootNote").int() == zone.rootNote && !nativeLayer.getValue("loop").bool())
                require(slice.getValue("Start").long() == 0L && slice.getValue("End").long() == zone.frameCount)
                require(slice.getValue("LoopStart").long() == 0L && slice.getValue("LoopMode").int() == 0)
                val poolEntry = samplePool.single { it.obj().getValue("name").str() == layer.sampleName }.obj()
                require(File(xpmFile.parentFile, poolEntry.getValue("path").str()).canonicalFile == file.canonicalFile)
                val x = xml.item(index) as Element
                fun text(element: Element, tag: String) = element.getElementsByTagName(tag).item(0).textContent
                require(text(x, "TriggerMode").toInt() == 2 && text(x, "VolumeRelease").toFloat() == patch.releaseSeconds)
                require(text(x, "LowNote").toInt() == zone.lowNote && text(x, "HighNote").toInt() == zone.highNote)
                val xLayer = x.getElementsByTagName("Layer").item(0) as Element
                require(text(xLayer, "SampleName") == layer.sampleName && text(xLayer, "RootNote").toInt() == zone.rootNote)
                require(text(xLayer, "SliceStart").toLong() == 0L && text(xLayer, "SliceEnd").toLong() == zone.frameCount)
                require(text(xLayer, "SliceLoopStart").toLong() == 0L && text(xLayer, "SliceLoop").toInt() == 0)
                require(text(xLayer, "VelStart").toInt() == 0 && text(xLayer, "VelEnd").toInt() == 127)
                val hit = requireNotNull(KeyHit.resolve(instrument, zone.rootNote, 1f) { path ->
                    WavInfo.read(File(folder, path)).frameCount
                })
                require(!hit.loops && hit.loopStartFrame == -1L && hit.pitchRatio == 1.0 && hit.frames == zone.frameCount)

                // A complete zone recipe makes the native pitch explicit rather than relying on a page or inherited default.
                val zonePatch = saved.copy(midi = zone.rootNote)
                val zoneRecipe = File(bundle, "recipes/zones/${patch.name}/${Scales.nameOf(zone.rootNote)}.patch.json")
                    .also { it.parentFile.mkdirs(); it.writeText(zonePatch.toJsonText() + "\n") }
                val replayPatch = OhmPatch.fromJsonText(zoneRecipe.readText())
                require(replayPatch == patch.copy(midi = zone.rootNote))
                val replay = replayPatch.render()
                require(replay.samples.contentEquals(notes[index].snip.samples)) { "${patch.name}/${zone.rootNote}: saved patch replay changed the production render." }
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, replay, WavWriter.BitDepth.PCM_24, smpl = SmplChunk(zone.rootNote, null)) }.toByteArray()
                require(file.readBytes().contentEquals(encoded)) { "${file.name}: replay no longer reproduces the entire exported PCM24 WAV." }
                linkedMapOf<String, Any?>(
                    "rootMidi" to zone.rootNote, "note" to Scales.nameOf(zone.rootNote), "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                    "file" to relative(bundle, file), "sha256" to sha(file), "frames" to zone.frameCount, "seconds" to 7,
                    "loopStartFrame" to 0, "loops" to false, "samplerRootMidi" to zone.rootNote, "samplerLoop" to null,
                    "patch" to replayPatch.toJsonValue(), "recipeFile" to relative(bundle, zoneRecipe), "recipeSha256" to sha(zoneRecipe),
                    "rawPatchReplayFloatExact" to true, "exportedWavReplayByteExact" to true, "maxPcm24Error" to error,
                )
            }
            verifyPlayer(instrument, decoded)
            val rootFile = File(folder, instrument.zones.single { it.rootNote == patch.midi }.sample)
            val preview = File(bundle, "previews/${patch.name}.wav").also { it.parentFile.mkdirs() }
            rootFile.copyTo(preview)
            require(sha(preview) == sha(rootFile))
            linkedMapOf<String, Any?>(
                "name" to patch.name, "patch" to patch.toJsonValue(), "model" to patch.model,
                "reply" to patch.reply, "ring" to patch.ring, "velocity" to patch.velocity, "seed" to patch.seed,
                "releaseSeconds" to patch.releaseSeconds, "playback" to "Note On; complete finite sample; no loop",
                "recipeFile" to relative(bundle, recipeFile), "recipeSha256" to sha(recipeFile),
                "mpc3" to relative(bundle, nativeFile), "mpc3Sha256" to sha(nativeFile),
                "mpc2" to relative(bundle, xpmFile), "mpc2Sha256" to sha(xpmFile),
                "phoneSidecar" to relative(bundle, sidecarFile), "phoneSidecarSha256" to sha(sidecarFile),
                "preview" to relative(bundle, preview), "previewSha256" to sha(preview), "previewPolicy" to "Byte-identical complete native C4 sample",
                "referencePlayerNoteOffChecked" to true, "referencePlayerFiniteEofChecked" to true, "zones" to zones,
            )
        }
        require(instruments.size == 5)
        require(folder.walkTopDown().count { it.isFile && it.extension == "wav" } == 45)
        require(sourcePaths.all { sha(File(repository, it)) == sourceHashes.getValue(it) }) { "Renderer/package sources changed during generation; rebuild from stable source." }
        for (path in sourcePaths) File(repository, path).copyTo(File(bundle, "source/$path").also { it.parentFile.mkdirs() })
        File(bundle, "README.txt").writeText(readme())
        File(bundle, "manifest.json").writeText(Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to "OHM", "model" to MODEL, "title" to BUNDLE,
            "sourceCommit" to sourceCommit, "workingTreeDirty" to sourceDirty, "sourceFilesSha256" to sourceHashes,
            "sourceFilesStableDuringRender" to true, "renderSource" to "OhmPadMaker.renderZone / OhmPatch.render / OhmV5.render",
            "audio" to mapOf("sampleRate" to RATE, "channels" to 1, "bits" to 24, "nativeFramesPerZone" to FRAMES),
            "instrumentCount" to 5, "nativeZoneCount" to 45, "coverageMidi" to listOf(48, 72), "maximumPlaybackShiftSemitones" to 1,
            "samplePolicy" to "Each finite seven-second native note plays once; note-off invokes the saved .3-second player release. Root sampler sheets carry no loops.",
            "previewPolicy" to "Five complete native C4 WAVs copied without cropping, normalization, new release or effects.",
            "verification" to listOf("all 45 complete zone patch replays reproduce the exported PCM24 WAV byte-for-byte",
                "native and compatibility Note On, disabled loop, exact note bounds and .3-second release fields",
                "strict sampler-sheet root/no-loop and phone KeyHit readback", "JVM reference player note-off and finite EOF", "stable source snapshot and archive CRC"),
            "phoneImportScope" to "This is an MPC keygroup handoff, not a drum-kit ZIP import. The sidecars describe the same instrument format SnipSnap MAKE INSTRUMENT writes.",
            "phoneHeard" to false, "mpcHardwareHeard" to false, "instruments" to instruments,
        ))) + "\n")
        val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.toList()
        File(bundle, "SHA256SUMS.txt").writeText(files.joinToString("\n") { "${sha(it)}  ${relative(bundle, it)}" } + "\n")
        val packaged = (files + File(bundle, "SHA256SUMS.txt")).sortedBy { relative(bundle, it) }
        val archive = File(output, ARCHIVE)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in packaged) {
                zip.putNextEntry(ZipEntry("$BUNDLE/${relative(bundle, file)}").apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        ZipFile(archive).use { zip ->
            require(zip.size() == packaged.size)
            for (file in packaged) require(sha(zip.getInputStream(zip.getEntry("$BUNDLE/${relative(bundle, file)}")).use { it.readBytes() }) == sha(file))
        }
        File(output, "$ARCHIVE.sha256").writeText("${sha(archive)}  $ARCHIVE\n")
        println("${archive.absolutePath}: ${archive.length()} bytes; SHA-256 ${sha(archive)}")
        println("Five factory model-$MODEL instruments / 45 finite native zones; hardware listening remains separate.")
    }

    private fun verifyPlayer(instrument: InstrumentStore.Instrument, samples: List<FloatArray>) {
        val loaded = InstrumentEngine.Loaded(instrument, RATE, samples)
        val releasePlayer = InstrumentEngine(loaded, RATE)
        require(releasePlayer.noteOn(60))
        releasePlayer.render(FloatArray(RATE * 2), RATE)
        releasePlayer.noteOff(60)
        val releaseFrames = (instrument.release * RATE).toInt() + RATE / 100
        releasePlayer.render(FloatArray(releaseFrames * 2), releaseFrames)
        require(releasePlayer.activeNotes.isEmpty()) { "Reference player did not finish the declared note-off release." }
        val finitePlayer = InstrumentEngine(loaded, RATE)
        require(finitePlayer.noteOn(60) && !finitePlayer.noteOn(47) && !finitePlayer.noteOn(73))
        finitePlayer.render(FloatArray((FRAMES + 2) * 2), FRAMES + 2)
        require(finitePlayer.activeNotes.isEmpty()) { "Finite note sustained beyond EOF." }
    }

    private fun sourcePaths(): List<String> = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Ohm.kt", "synth/src/main/kotlin/com/snipsnap/synth/OhmV5.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/OhmPresets.kt", "synth/src/main/kotlin/com/snipsnap/synth/OhmPatch.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/OhmOptions.kt", "synth/src/main/kotlin/com/snipsnap/synth/OhmCapture.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt", "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Modes.kt", "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt", "shell/src/main/kotlin/com/snipsnap/shell/OhmPadMaker.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt", "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt", "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/Kit.kt", "kit/src/main/kotlin/com/snipsnap/kit/AtomicFile.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/SmplChunk.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Scales.kt", "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
        "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3Project.kt", "mpc3/src/main/kotlin/com/snipsnap/mpc3/Acvs.kt",
        "xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt", "xpm/src/main/kotlin/com/snipsnap/xpm/WavInfo.kt",
        "shell/src/test/kotlin/com/snipsnap/shell/OhmReplyRingHandoffGenerator.kt", "shell/build.gradle.kts",
    )

    private fun readme() = """
        OHM REPLY / RING — FIVE FACTORY INSTRUMENTS

        Natural Replies, Tight Replies, Spaced Replies, Short Ring and Long Ring
        retain their complete model-5 factory patches: velocity .8, seed731,
        saved REPLY/RING and shared-body settings, and .3-second note-off release.
        Each of the nine native roots C3–C5 retains all seven seconds. Between
        roots, playback transposes by at most one semitone; keys outside C3–C5
        are silent. All notes are finite. No HOLD, sustain loop or added effect.

        MPC 3: copy instruments/ intact and load the desired .xty. Keep its
        matching _[TrackData] folder beside it. MPC 2: load the .xpm inside that
        folder. Both formats reference the same nine mono44.1kHz PCM24 WAVs.
        Each WAV has its native root in a sampler pitch sheet with no loop.
        Note On playback responds to note-off with the saved .3-second release;
        a key held longer than the recording finishes at its natural file end.

        Source velocity .8 is baked into the excitation. Keyboard velocity changes
        playback gain; this package has one velocity layer and no live coupling
        between separately played keys. previews/ contains exact, complete C4
        zone WAVs, without cropping, matching, extra fades or artificial looping.

        Every factory .patch.json and all45 zone-specific patch JSON files retain
        the complete portable production recipe. Each saved zone patch is rendered
        again and checked byte-for-byte against its exported WAV, including the
        native sampler pitch sheet. Phone .instrument.json sidecars use the same
        format as MAKE INSTRUMENT. This keygroup ZIP is not a drum-kit import.

        manifest.json records patches, native note ranges, release behavior,
        source revision and file hashes. source/ preserves the selected renderer
        and exporter snapshot. SHA256SUMS.txt covers every bundled file except
        itself. Automated readback and reference-player checks do not establish
        phone or MPC hardware listening acceptance.

        Rebuild: ./gradlew :shell:generateOhmReplyRingHandoff
        Override output: -PohmReplyRingHandoffOutput=/path/to/output
    """.trimIndent() + "\n"

    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun sha(file: File) = sha(file.readBytes())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun git(repo: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + arguments).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0)
        return result
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest value")
    }
}
