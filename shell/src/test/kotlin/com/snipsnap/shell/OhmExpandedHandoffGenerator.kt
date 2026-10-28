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
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmCapture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import com.snipsnap.xpm.WavInfo
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Three original, provisional model-3 studies through the production instrument exporter. */
object OhmExpandedHandoffGenerator {
    const val BUNDLE = "SnipSnap OHM Expanded Studies"
    const val ARCHIVE = "SnipSnap-Ohm-Expanded-Studies.zip"
    private const val RATE = WavWriter.MPC_SAMPLE_RATE

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply only the destination directory." }
        val repository = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val output = File(args.firstOrNull() ?: "../testkit/ohm-expanded-handoff").canonicalFile
        require(output != repository && !repository.toPath().startsWith(output.toPath())) { "Choose a generated output directory." }
        require(output.isDirectory || output.mkdirs()) { "Could not create $output" }
        val bundle = File(output, BUNDLE)
        require(!bundle.exists() || bundle.deleteRecursively()) { "Could not replace generated bundle $bundle" }
        require(bundle.mkdirs()) { "Could not create $bundle" }

        val sourcePaths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Ohm.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmV2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmV3.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmOptions.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmCapture.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/OhmPadMaker.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt",
            "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt",
            "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/SmplChunk.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
            "xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt",
            "shell/src/test/kotlin/com/snipsnap/shell/OhmExpandedHandoffGenerator.kt",
            "shell/build.gradle.kts",
        )
        val sourceHashes = sourcePaths.associateWith { path -> sha(File(repository, path)) }
        val capture = authoredCapture()
        val captureJson = File(bundle, "capture-source/original-tap.capture.json").also { it.parentFile.mkdirs() }
        captureJson.writeText(capture.toJsonText() + "\n")
        val captureWav = File(bundle, "capture-source/original-tap.wav")
        WavWriter.write(captureWav, Snip(capture.samples(), 1, RATE))
        val macros = Ohm.defaults(3) + mapOf("LENGTH" to .2f, "MEDDLING" to .65f, "COMMUNE" to .7f, "HOLD" to 1f)
        fun patch(name: String, options: OhmOptions, gesture: OhmGesture = OhmGesture.STEADY) =
            OhmPatch(name, macros, midi = 60, velocity = .8f, seed = 731, gesture = gesture,
                model = 3, options = options, releaseSeconds = .4f)
        val studies = listOf(
            patch("OHM Plucked Study", OhmOptions(), OhmGesture.GATHER),
            patch("OHM Bowed Study", OhmOptions(exciter = OhmExciter.BOW, elder = .35f, fmRatio = 1.5f, fmIndex = 1.2f)),
            patch("OHM Captured Study", OhmOptions(exciter = OhmExciter.CAPTURED, grains = .55f,
                freeze = .5f, grainSeconds = .08f, capture = capture)),
        )
        val instruments = studies.map { patch ->
            val spec = OhmPadMaker.Spec(patch)
            val roots = OhmPadMaker.zoneMidis(spec)
            require(roots == (48..72 step 3).toList())
            // Sequential notes and one instrument at a time keep peak memory bounded.
            val notes = roots.map { midi ->
                println("Rendering ${patch.name}: ${Scales.nameOf(midi)}")
                OhmPadMaker.renderZone(spec, midi)
            }
            val folder = File(bundle, "instruments").apply { mkdirs() }
            val program = OhmPadMaker.export(patch.name, spec, notes, folder)
            val sidecarFile = InstrumentStore.sidecar(folder, patch.name)
            val instrument = InstrumentStore.read(sidecarFile)
            require(instrument.release == patch.releaseSeconds && instrument.zones.map { it.rootNote } == roots)
            val recipeFile = File(folder, "${patch.name}.patch.json")
            require(OhmPatch.fromJsonText(recipeFile.readText()) == patch) { "Saved instrument recipe lost source or performance fields." }
            val nativeFile = File(folder, "${patch.name}.xty")
            val nativeProject = Mpc3Project.read(nativeFile)
            require(nativeProject.programs().single().getValue("type").int() == 1) { "Native program is not a keygroup." }
            val native = nativeProject.drumPrograms().single().getValue("instruments").arr()
            val xpmFile = File(folder, "${patch.name}_[TrackData]/${patch.name}.xpm")
            val xmlFactory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val document = xmlFactory.newDocumentBuilder().parse(xpmFile)
            require((document.getElementsByTagName("Program").item(0) as Element).getAttribute("type") == "Keygroup")
            val xml = document.getElementsByTagName("Instrument")
            require(xml.length == roots.size && native.size >= roots.size)
            val zones = instrument.zones.mapIndexed { index, zone ->
                val file = File(folder, zone.sample)
                val info = WavInfo.read(file)
                val audio = WavReader.read(file)
                require(info.channels == 1 && info.sampleRate == RATE && info.bitsPerSample == 24)
                require(info.frameCount == zone.frameCount && audio.frameCount == notes[index].snip.frameCount)
                require(zone.loopStartFrame == notes[index].loopStartFrame)
                require(WavReader.readSmpl(file) == SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount)))
                val error = audio.samples.indices.maxOf { abs(audio.samples[it] - notes[index].snip.samples[it]) }
                require(error <= 2e-7f) { "PCM24 error exceeds encoding precision: $error" }
                val n = native[index].obj()
                require(n.getValue("triggerMode").int() == 2 && n.getValue("lowNote").int() == zone.lowNote && n.getValue("highNote").int() == zone.highNote)
                val nativeLayer = n.getValue("layersv").arr().first().obj()
                val slice = nativeLayer.getValue("sliceInfo").obj()
                require(nativeLayer.getValue("rootNote").int() == zone.rootNote)
                require(slice.getValue("LoopMode").int() == 1 && slice.getValue("LoopStart").long() == zone.loopStartFrame && slice.getValue("End").long() == zone.frameCount)
                require(n.getValue("synthSection").obj().getValue("ampEnvelope").obj().getValue("Release").obj().getValue("value0").num() == spec.releaseSeconds.toDouble())
                val x = xml.item(index) as Element
                fun text(tag: String) = x.getElementsByTagName(tag).item(0).textContent
                require(text("RootNote").toInt() == zone.rootNote && text("SliceLoop").toInt() == 1)
                require(text("SliceLoopStart").toLong() == zone.loopStartFrame && text("SliceEnd").toLong() == zone.frameCount)
                require(text("VolumeRelease").toFloat() == patch.releaseSeconds)
                require(program.keygroups[index].layers.single().frameCount == zone.frameCount)
                linkedMapOf<String, Any?>(
                    "rootMidi" to zone.rootNote, "note" to Scales.nameOf(zone.rootNote), "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                    "file" to relative(bundle, file), "sha256" to sha(file), "frames" to zone.frameCount,
                    "loopStartFrame" to zone.loopStartFrame, "loopEndExclusive" to zone.frameCount,
                    "loopSeconds" to (zone.frameCount - zone.loopStartFrame).toDouble() / RATE, "maxPcm24Error" to error,
                )
            }
            val preview = File(bundle, "previews/${patch.name}.wav").also { it.parentFile.mkdirs() }
            WavWriter.write(preview, OhmPadMaker.preview(spec))
            linkedMapOf<String, Any?>(
                "name" to patch.name, "status" to "provisional audition study; not a factory preset",
                "patch" to patch.toJsonValue(), "model" to patch.model, "velocity" to patch.velocity, "seed" to patch.seed,
                "gesture" to patch.gesture.name, "releaseSeconds" to patch.releaseSeconds, "options" to patch.options.toJsonValue(),
                "sustainMethod" to if (patch.options.exciter == OhmExciter.BOW) "measured capture from live bow sustain" else "independently checked physical periodic sustain",
                "recipeFile" to relative(bundle, recipeFile), "recipeSha256" to sha(recipeFile),
                "mpc3" to relative(bundle, nativeFile), "mpc3Sha256" to sha(nativeFile),
                "mpc2" to relative(bundle, xpmFile), "mpc2Sha256" to sha(xpmFile),
                "phoneSidecar" to relative(bundle, sidecarFile), "phoneSidecarSha256" to sha(sidecarFile),
                "preview" to relative(bundle, preview), "previewSha256" to sha(preview), "zones" to zones,
            )
        }
        require(sourcePaths.all { sha(File(repository, it)) == sourceHashes.getValue(it) }) { "Renderer/package sources changed during generation; regenerate from stable source." }
        for (path in sourcePaths) File(repository, path).copyTo(File(bundle, "source/$path").also { it.parentFile.mkdirs() })
        File(bundle, "README.txt").writeText(readme())
        File(bundle, "manifest.json").writeText(Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to "OHM", "model" to 3, "title" to BUNDLE,
            "status" to "Three provisional original audition studies; owner listening and device acceptance pending.",
            "sourceCommit" to git(repository, "rev-parse", "HEAD"), "workingTreeDirty" to git(repository, "status", "--porcelain").isNotEmpty(),
            "sourceFilesSha256" to sourceHashes, "sourceFilesStableDuringRender" to true,
            "renderSource" to "OhmPadMaker.renderZone / Ohm.renderHeld", "audio" to mapOf("sampleRate" to RATE, "channels" to 1, "bits" to 24),
            "instrumentCount" to 3, "nativeZoneCount" to 27, "coverageMidi" to listOf(48, 72), "maximumPlaybackShiftSemitones" to 1,
            "loopConvention" to "positive start frame through file end, exclusive; WAV smpl end is stored inclusive",
            "authoredCapture" to mapOf("description" to "Original deterministic decaying two-tone and noise tap, synthesized by this generator; no external recording.",
                "file" to relative(bundle, captureJson), "fileSha256" to sha(captureJson), "pcmSha256" to capture.sha256,
                "frames" to capture.frameCount, "label" to capture.label, "wav" to relative(bundle, captureWav), "wavSha256" to sha(captureWav)),
            "verification" to listOf("complete patch/source JSON replay", "27 mono PCM24 samples and sampler sheets", "native keygroup and compatibility loop/release fields", "phone instrument sidecars", "source hashes stable while rendering"),
            "phoneImportScope" to "MPC keygroups are not imported as drum kits. Use OHM model 3/HOLD/MAKE INSTRUMENT on the phone; packaged sidecars describe the standard phone keys format.",
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
        File(output, "$ARCHIVE.sha256").writeText("${sha(archive)}  $ARCHIVE\n")
        println("${archive.absolutePath}: ${archive.length()} bytes; SHA-256 ${sha(archive)}")
        println("Three provisional instruments / 27 native zones; phone and MPC listening remains pending.")
    }

    private fun authoredCapture(): OhmCapture {
        val noise = java.util.Random(731L)
        val source = FloatArray(RATE / 4) { i ->
            val t = i.toDouble() / RATE
            (.28 * sin(2 * PI * 173 * t) * exp(-t / .035) +
                .12 * sin(2 * PI * 431 * t) * exp(-t / .060) + .16 * (2f * noise.nextFloat() - 1f) * exp(-t / .008)).toFloat()
        }
        return OhmCapture.fromSnip(Snip(source, 1, RATE), "Original generated tap study")
    }

    private fun readme() = """
        OHM EXPANDED STUDIES — PROVISIONAL AUDITION INSTRUMENTS

        Three original model-3 studies: plucked shared family (GATHER), bowed string
        with FM elder, and an original generated tap with grains and audio freeze.
        These are listening studies, not accepted factory presets. The captured
        exciter is synthesized here and embedded in its patch, with no external assets.

        MPC 3: copy instruments/ intact to the card and load the desired .xty.
        Keep its matching _[TrackData] folder beside it.
        MPC 2: load the matching .xpm inside that _[TrackData] folder.
        Each instrument samples C3–C5 at nine native roots, with at most a semitone
        of playback transposition between roots. Each key keeps its authored attack,
        loops the marked sustain while held and releases over the saved .4 seconds.
        The bow uses a measured capture of live sustain; this is not a claim that
        its nonlinear physical state repeats. GATHER occurs once before sustain.

        Source velocity .8 is baked into each sample's excitation. Keyboard touch
        changes playback gain; there are no separate velocity layers or live
        inter-key coupling. WAV smpl markers, MPC programs and phone sidecars
        use the same loop bounds. Root previews add a finite release on a copy.

        SnipSnap's existing share/import door imports drum kits, not this keygroup
        ZIP. On the phone use OHM model 3, choose the desired source and HOLD, then
        MAKE INSTRUMENT. The adjacent .instrument.json files use that same keys
        format; .patch.json files preserve every source, macro and release choice.
        A saved recipe can be rebuilt on the CLI:
        snipsnap synth OHM STRING --patch "OHM Captured Study.patch.json" --instrument --out /tmp/ohm-keys

        manifest.json records source/recipe/sample hashes and exact notes/options.
        source/ preserves the renderer/capture/package source snapshot.
        SHA256SUMS.txt covers every bundled file except itself.
        Check note-on, long sustain, note-off, clean loop joins and chords on the
        actual phone/MPC. Automated metadata checks do not establish hardware acceptance.

        Rebuild: ./gradlew :shell:generateOhmExpandedHandoff
        Override output: -PohmExpandedHandoffOutput=/path/to/output
    """.trimIndent() + "\n"

    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun git(repo: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + arguments).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "git failed" }
        return result
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported manifest value ${value.javaClass.name}")
    }
}
