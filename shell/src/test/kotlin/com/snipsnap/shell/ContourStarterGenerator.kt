package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.SafeXml
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourPresets
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.xpm.KeygroupProgram
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Package all 36 factory recipes as held instruments, with source and file read-back evidence. */
object ContourStarterGenerator {
    private const val BANK = "SnipSnap Contour Starter"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory." }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
        require(git(repo, "status", "--porcelain").isBlank()) { "Commit the tested source before generating the starter." }
        val output = File(args.firstOrNull() ?: "${repo.path}/testkit/contour-starter").canonicalFile
        require(!repo.toPath().startsWith(output.toPath())) { "Output must not contain the source repository." }
        val bundle = File(output, BANK).apply { deleteRecursively(); mkdirs() }
        val mpc = File(bundle, "mpc").apply { mkdirs() }
        val previews = File(output, "root-loops").apply { deleteRecursively(); mkdirs() }
        val web = File(output, "web").apply { deleteRecursively(); mkdirs() }
        val sourceCommit = git(repo, "rev-parse", "HEAD")
        val sourcePaths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/ContourEngine.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourTables.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ContourPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/ContourPadMaker.kt",
            "shell/src/test/kotlin/com/snipsnap/shell/ContourStarterGenerator.kt",
            "shell/src/test/resources/contour-instruments/index.html",
            "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt",
            "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt",
            "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
        )
        val patches = ContourPresets.all().also { require(it.size == 36) }
        val rows = patches.map { patch ->
            val description = ContourPresets.description(patch)
            val name = "Contour ${patch.name}"
            val spec = ContourPadMaker.Spec(patch)
            val midis = ContourPadMaker.zoneMidis(spec)
            println("Rendering $name: ${midis.first()}..${midis.last()}, ${midis.size} roots")
            val notes = midis.map { ContourPadMaker.renderZone(spec, it) }
            val program = ContourPadMaker.export(name, spec, notes, mpc)
            val zones = verify(mpc, program, notes)
            val recipe = File(bundle, "recipes/${patch.name}.json").apply {
                parentFile.mkdirs(); writeText(patch.toJsonText() + "\n")
            }
            require(ContourPatch.fromJsonText(recipe.readText()) == patch)
            val center = notes[midis.indexOf(ContourEngine.rootMidi(patch.voice))]
            val loop = Snip(center.snip.samples.copyOfRange(center.loopStartFrame.toInt(), center.snip.frameCount), 1, 44_100)
            val preview = WavWriter.write(File(previews, "${patch.name.lowercase(Locale.ROOT).replace(' ', '_')}.wav"),
                loop, WavWriter.BitDepth.PCM_16)
            File(web, "root-loops/${preview.name}").apply { parentFile.mkdirs(); preview.copyTo(this) }
            File(web, "recipes/${recipe.name}").apply { parentFile.mkdirs(); recipe.copyTo(this) }
            linkedMapOf("name" to patch.name, "voice" to patch.voice.name,
                "role" to description.role, "gesture" to description.gesture, "retained" to description.retained,
                "recipe" to relative(bundle, recipe),
                "recipeSha256" to sha(recipe), "canonicalPatchSha256" to sha(patch.toJsonText().toByteArray()),
                "nativeTrack" to "mpc/$name.xty", "compatibilityProgram" to "mpc/${name}_[TrackData]/$name.xpm",
                "releaseSeconds" to spec.releaseSeconds, "capturedVelocity" to patch.velocity,
                "sourceRenderMode" to if (ContourEngine.isLoop(patch.macros.getValue("HOLD"))) "loop" else "finite",
                "sourceRenderLevels" to levels(patch.render()), "rootHeldLevels" to levels(center.snip), "zones" to zones,
                "preview" to linkedMapOf("file" to relative(output, preview), "sha256" to sha(preview),
                    "frames" to loop.frameCount, "encoding" to "PCM16, one complete settled period, no level change"))
        }
        requireNotNull(javaClass.getResourceAsStream("/contour-instruments/index.html")) {
            "Missing the Contour instrument listening page resource."
        }.use { input -> File(web, "index.html").outputStream().use { input.copyTo(it) } }
        writeJson(File(web, "manifest.json"), linkedMapOf("sourceCommit" to sourceCommit, "hardwareVerified" to false,
            "instruments" to patches.zip(rows).map { (patch, row) ->
                val midis = Keys.contourPadMidis(patch.voice)
                val description = ContourPresets.description(patch)
                linkedMapOf("name" to patch.name, "voice" to patch.voice.name,
                    "role" to description.role, "gesture" to description.gesture, "retained" to description.retained,
                    "rootMidi" to ContourEngine.rootMidi(patch.voice), "lowMidi" to midis.first(), "highMidi" to midis.last(),
                    "releaseSeconds" to row.getValue("releaseSeconds"),
                    "preview" to "root-loops/${patch.name.lowercase(Locale.ROOT).replace(' ', '_')}.wav",
                    "previewSha256" to (row.getValue("preview") as Map<*, *>)["sha256"],
                    "recipe" to "recipes/${patch.name}.json", "recipeSha256" to row.getValue("recipeSha256"),
                    "zoneCount" to midis.size)
            }))
        File(bundle, "README.txt").writeText(readme())
        val manifest = File(bundle, "manifest.json")
        writeJson(manifest, linkedMapOf("engine" to "CONTOUR", "sourceCommit" to sourceCommit,
            "workingTreeDirty" to false, "sourceSha256" to sourcePaths.associateWith { sha(File(repo, it)) },
            "instrumentCount" to rows.size, "zoneCount" to rows.sumOf { (it.getValue("zones") as List<*>).size },
            "hardwareVerified" to false, "checks" to listOf("XPM XML", "native ACVS JSON", "phone sidecar", "WAV PCM24 and sampler sheets", "ZIP byte identity"),
            "instruments" to rows, "files" to bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }
                .map { linkedMapOf("file" to relative(bundle, it), "bytes" to it.length(), "sha256" to sha(it)) }.toList()))
        val archive = File(output, "snipsnap-contour-starter.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.forEach { file ->
                zip.putNextEntry(ZipEntry("$BANK/${relative(bundle, file)}").apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        ZipFile(archive).use { zip ->
            val files = bundle.walkTopDown().filter { it.isFile }.toList()
            require(zip.size() == files.size)
            files.forEach { file ->
                require(zip.getInputStream(zip.getEntry("$BANK/${relative(bundle, file)}")).use { sha(it.readBytes()) } == sha(file))
            }
        }
        writeJson(File(output, "handoff-manifest.json"), linkedMapOf("archive" to archive.name, "bytes" to archive.length(),
            "sha256" to sha(archive), "manifestSha256" to sha(manifest), "hardwareVerified" to false))
        require(git(repo, "rev-parse", "HEAD") == sourceCommit && git(repo, "status", "--porcelain").isBlank()) {
            "Source changed while rendering; regenerate the complete bank."
        }
        val zoneCount = rows.sumOf { (it.getValue("zones") as List<*>).size }
        println("Verified ${rows.size} instruments / $zoneCount zones; ${archive.path}: ${archive.length()} bytes, SHA-256 ${sha(archive)}")
        println("Android and MPC hardware playback remain unverified.")
    }

    private fun verify(root: File, program: KeygroupProgram, notes: List<KeyNote>): List<Map<String, Any?>> {
        val data = File(root, "${program.name}_[TrackData]")
        val xml = SafeXml.newBuilder().parse(File(data, "${program.name}.xpm"))
        val xmlZones = xml.getElementsByTagName("Instrument")
        val native = requireNotNull(Acvs.read(File(root, "${program.name}.xty")).payload)
            .obj().getValue("data").obj().getValue("program").obj()
        require(native.getValue("keygroup").obj().getValue("numKeygroups").int() == program.keygroups.size)
        val nativeZones = native.getValue("drum").obj().getValue("instruments").arr()
        val phone = InstrumentStore.read(InstrumentStore.sidecar(root, program.name))
        require(xmlZones.length == program.keygroups.size && phone.zones.size == program.keygroups.size)
        require(phone.release == program.volumeRelease)
        return program.keygroups.mapIndexed { i, kg ->
            val layer = kg.layers.single()
            val x = xmlZones.item(i) as Element
            val n = nativeZones[i].obj()
            val l = n.getValue("layersv").arr().first().obj()
            val slice = l.getValue("sliceInfo").obj()
            val p = phone.zones[i]
            require(x.text("LowNote").toInt() == kg.lowNote && x.text("HighNote").toInt() == kg.highNote)
            require(x.text("RootNote").toInt() == kg.rootNote && x.text("SampleName") == layer.sampleName)
            require(x.text("SliceEnd").toLong() == layer.frameCount && x.text("SliceLoopStart").toLong() == layer.loopStartFrame)
            require(x.text("SliceLoop") == "1" && abs(x.text("VolumeRelease").toFloat() - program.volumeRelease) < 1e-6f)
            require(x.text("VelStart").toInt() == 0 && x.text("VelEnd").toInt() == 127)
            require(n.getValue("lowNote").int() == kg.lowNote && n.getValue("highNote").int() == kg.highNote)
            require(n.getValue("triggerMode").int() == 2 && l.getValue("rootNote").int() == kg.rootNote)
            require(l.getValue("sampleFile").str() == "${layer.sampleName}.wav")
            require(l.getValue("velocityStart").int() == 0 && l.getValue("velocityEnd").int() == 127)
            require(slice.getValue("End").long() == layer.frameCount && slice.getValue("LoopStart").long() == layer.loopStartFrame)
            require(slice.getValue("LoopMode").int() == 1)
            val envelope = n.getValue("synthSection").obj().getValue("ampEnvelope").obj()
            require(abs(envelope.getValue("Release").obj().getValue("value0").num() - program.volumeRelease) < 1e-6)
            require(n.getValue("synthSection").obj().getValue("velocitySensitivity").num() == 1.0)
            require(p.lowNote == kg.lowNote && p.highNote == kg.highNote && p.rootNote == kg.rootNote)
            require(p.loopStartFrame == layer.loopStartFrame && p.frameCount == layer.frameCount && p.playableLayers.size == 1)
            require(p.playableLayers.single().velStart == 0 && p.playableLayers.single().velEnd == 127)
            val file = File(root, p.sample)
            val wav = WavReader.read(file)
            val sheet = requireNotNull(WavReader.readSmpl(file))
            require(wav.channels == 1 && wav.sampleRate == 44_100 && wav.frameCount.toLong() == layer.frameCount)
            require(sheet.rootNote == kg.rootNote && sheet.loop?.startFrame == layer.loopStartFrame && sheet.loop?.endFrameExclusive == layer.frameCount)
            val error = wav.samples.indices.maxOf { abs(wav.samples[it] - notes[i].snip.samples[it]) }
            val seam = Keys.seamError(wav.samples, layer.loopStartFrame.toInt())
            require(error < 2e-7f && seam < Keys.MAX_SEAM_ERROR && wav.samples.all { it.isFinite() } && wav.peak() <= .991f)
            linkedMapOf("rootMidi" to kg.rootNote, "lowMidi" to kg.lowNote, "highMidi" to kg.highNote,
                "sample" to relative(root.parentFile, file), "frames" to layer.frameCount, "loopStart" to layer.loopStartFrame,
                "sha256" to sha(file), "maxPcm24Error" to error, "seamError" to seam, "levels" to levels(wav))
        }
    }

    private fun readme() = """
        CONTOUR STARTER — 36 COMPLETE RECIPES
        Thirty-six recipes, each captured as 17 minor-third sample roots over
        its voice's four-octave range. Each program covers the inclusive range of
        its lowest to highest root. The samples hold the settled tone and omit
        the original finite attack. ATTACK, DECAY and filter-envelope transients
        do not retrigger when these captured sustains are played.
        A gentle five-millisecond entry prevents a hard key-on step; the marked
        repeating region retains the unchanged settled waveform.

        MPC 3: copy mpc/ together; load the .xty beside its _[TrackData]/ folder.
        MPC 2: load the .xpm inside that program's _[TrackData]/ folder.
        SnipSnap: keep each .instrument.json beside its _[TrackData]/ folder.
        These are raw PCM24, mono 44.1 kHz, with root and sustain-loop WAV sheets.
        Default key-up release is 0.60 seconds. All patches' complete configs
        and 14 control values are saved under recipes/. Export replaces saved
        TUNE with each zone's MIDI root and HOLD with the repeating region.
        Saved excitation velocity is baked into each single-layer tone; playing
        velocity controls sampler volume, rather than switching captured timbres.
        The MPC's velocity response still needs a hardware check.

        manifest.json records source commit/hashes, recipes, note maps, levels,
        WAV hashes and software read-back checks. Saved source recipes are retained;
        no bank gain or effects are added. Read-back is not a hardware pass.
        Android phone playback, MPC loading, sustain and release are unverified.
        The nine original recipes retain their previous complete configurations;
        the 27 additions are proposals for owner listening, not approved sounds.
        Regenerate with ./gradlew :shell:generateContourStarter from a clean HEAD.
    """.trimIndent() + "\n"

    private fun Element.text(tag: String) = getElementsByTagName(tag).item(0).textContent
    private fun levels(snip: Snip) = linkedMapOf("peak" to snip.peak(), "loudness" to Loudness.of(snip),
        "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size))
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun sha(file: File) = sha(file.readBytes())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText()
        require(process.waitFor() == 0) { result }
        return result.trim()
    }
}
