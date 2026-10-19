package com.snipsnap.cli

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.shell.BellowsPadMaker
import com.snipsnap.synth.BellowsGesture
import com.snipsnap.synth.BellowsKeys
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.BellowsPresets
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reproducible model-2 phone/MPC playtest packages, through the same builder as MAKE INSTRUMENT. */
object BellowsKeysCommand {
    const val ZIP_NAME = "Bellows-phone-mpc-m2.zip"

    fun run(args: List<String>, out: PrintStream): Int {
        val options = Options.parse(args, valued = setOf("--out", "--release"), boolean = setOf("--overwrite"))
        if (options.positional.isNotEmpty()) throw CliError("bellows-keys takes no positional arguments; use --out DIR [--release .6] [--overwrite]")
        val release = options["--release"]?.let {
            it.toFloatOrNull() ?: throw CliError("--release wants seconds, got '$it'")
        } ?: BellowsPadMaker.RELEASE.default
        if (!release.isFinite() || release !in BellowsPadMaker.RELEASE_MIN_SECONDS..BellowsPadMaker.RELEASE_MAX_SECONDS)
            throw CliError("--release wants ${BellowsPadMaker.RELEASE_MIN_SECONDS}..${BellowsPadMaker.RELEASE_MAX_SECONDS} seconds")
        val root = File(options["--out"] ?: "snipsnap-out/bellows-keys").absoluteFile
        val playable = File(root, "playable")
        val archive = File(root, ZIP_NAME)
        if (!options.has("--overwrite") && (playable.exists() || archive.exists()))
            throw CliError("BELLOWS playtest output already exists under $root; choose another --out or use --overwrite")
        root.mkdirs()
        val staging = Files.createTempDirectory(root.toPath(), ".bellows-playable-").toFile()
        try {
            val card = File(staging, "card").apply { mkdirs() }
            val recipes = File(staging, "recipes").apply { mkdirs() }
            val programs = mutableListOf<JsonValue>()
            for (voice in BellowsVoice.entries) {
                val source = BellowsPresets.forVoice(voice).first()
                val spec = BellowsPadMaker.Spec(source, release)
                val name = "Bellows ${voice.name.lowercase().replaceFirstChar { it.uppercase() }}"
                out.println("rendering $name: 9 pitch zones x 3 velocity layers")
                val notes = BellowsPadMaker.zoneMidis(spec).map { BellowsPadMaker.renderZone(spec, it) }
                val program = BellowsPadMaker.export(name, spec, notes, card)
                File(recipes, "${voice.name}-source.json").writeText(source.toJsonText())
                val patch = BellowsPadMaker.melodyPatch(source)
                program.keygroups.forEach { zone -> zone.layers.forEachIndexed { index, layer ->
                    File(recipes, "${layer.sampleName}.json").writeText(patch.copy(
                        midi = zone.rootNote, velocity = BellowsPadMaker.VELOCITIES[index],
                    ).toJsonText())
                } }
                programs += programManifest(program, source, "BellowsKeys.layer", "melody")
            }
            val source = BellowsPresets.forVoice(BellowsVoice.WHEEL).first().copy(
                midi = 48, gesture = BellowsGesture.STEADY,
                macros = BellowsPresets.forVoice(BellowsVoice.WHEEL).first().macros +
                    mapOf("TUNE" to 0f, "HOLD" to 1f, "DRONE" to 1f),
            )
            out.println("rendering Bellows Tonic C3: separate fixed-tonic WHEEL bed")
            val bed = BellowsKeys.tonicBed(48, source.macros, 1f, source.gesture, source.model)
            val bedProgram = BellowsPadMaker.exportTonicBed("Bellows Tonic C3", BellowsPadMaker.Spec(source, release), bed, card)
            File(recipes, "WHEEL-tonic-bed-source.json").writeText(source.toJsonText())
            programs += programManifest(bedProgram, source, "BellowsKeys.tonicBed", "fixed-tonic-bed")
            File(staging, "PLAYTEST.md").writeText(PLAYTEST)
            val files = staging.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }.map { file ->
                obj("path" to str(file.relativeTo(staging).invariantSeparatorsPath),
                    "bytes" to num(file.length()), "sha256" to str(sha256(file)))
            }.toList()
            val manifest = obj(
                "format" to str("snipsnap-bellows-playtest-1"), "engineModel" to num(2),
                "hardwareVerified" to JsonValue.Bool(false),
                "dryListeningAccepted" to JsonValue.Bool(true),
                "sampleRate" to num(44_100), "channels" to num(1),
                "velocityGain" to str("BellowsKeys.layer removes the source's velocity^0.60 scalar; the target player supplies velocity gain once"),
                "releaseSeconds" to num(release), "programs" to JsonValue.Arr(programs),
                "files" to JsonValue.Arr(files),
            )
            File(staging, "manifest.json").writeText(Json.write(manifest))
            val downloads = mutableListOf<JsonValue>()
            val stagedDownloads = linkedMapOf<File, File>()
            for (programValue in programs) {
                val info = programValue.obj()
                val name = info.getValue("name").str()
                val voice = info.getValue("voice").str()
                val bed = info.getValue("role").str() == "fixed-tonic-bed"
                val stem = name.replace(" ", "")
                val zipName = if (bed) "Bellows-Tonic-C3-m2.zip" else "Bellows-${voice.lowercase().replaceFirstChar { it.uppercase() }}-keys-m2.zip"
                val selected = staging.walkTopDown().filter { file ->
                    if (!file.isFile) false else {
                        val path = file.relativeTo(staging).invariantSeparatorsPath
                        path == "PLAYTEST.md" || path == "card/$name.xty" || path == "card/$name.instrument.json" ||
                            path.startsWith("card/${name}_[TrackData]/") ||
                            path.startsWith("recipes/${stem}_") ||
                            path == "recipes/${if (bed) "WHEEL-tonic-bed" else voice}-source.json"
                    }
                }.toList()
                val selectedPaths = selected.map { it.relativeTo(staging).invariantSeparatorsPath }.toSet()
                val familyManifest = JsonValue.Obj(LinkedHashMap(manifest.entries).also { fields ->
                    fields["programs"] = JsonValue.Arr(listOf(programValue))
                    fields["files"] = JsonValue.Arr(files.filter { it.obj().getValue("path").str() in selectedPaths })
                })
                val tempZip = File(root, ".${staging.name}-$zipName")
                stagedDownloads[tempZip] = File(root, zipName)
                ZipOutputStream(tempZip.outputStream().buffered()).use { zip ->
                    for (file in selected.sortedBy { it.path }) {
                        zip.putNextEntry(ZipEntry(file.relativeTo(staging).invariantSeparatorsPath).apply { time = 0L })
                        file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
                    zip.write(Json.write(familyManifest).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                downloads += obj("label" to str(if (bed) "Tonic C3 companion" else "${voice.lowercase().replaceFirstChar { it.uppercase() }} keys"),
                    "file" to str(zipName), "bytes" to num(tempZip.length()))
            }
            val stagedZip = File(root, ".${staging.name}.zip")
            try {
                ZipOutputStream(stagedZip.outputStream().buffered()).use { zip ->
                    staging.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(staging).invariantSeparatorsPath).apply { time = 0L })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                if (playable.exists()) check(playable.deleteRecursively()) { "could not replace $playable" }
                Files.move(staging.toPath(), playable.toPath())
                Files.move(stagedZip.toPath(), archive.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                for ((temp, target) in stagedDownloads) Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                File(root, "instrument-downloads.json").writeText(Json.write(JsonValue.Arr(downloads)))
            } finally {
                stagedZip.delete()
                stagedDownloads.keys.forEach { it.delete() }
            }
            out.println("playtest: ${archive.path}")
            out.println("programs: ${File(playable, "card").path} (.xty + .xpm twins + phone sidecars)")
            out.println("phone and MPC checks are pending; see playable/PLAYTEST.md")
            return 0
        } finally { staging.deleteRecursively() }
    }

    private fun programManifest(program: KeygroupProgram, source: BellowsPatch, adapter: String, role: String): JsonValue.Obj = obj(
        "name" to str(program.name), "voice" to str(source.voice.name), "role" to str(role),
        "sourcePreset" to str(source.name), "adapter" to str(adapter), "model" to num(source.model),
        "releaseSeconds" to num(program.volumeRelease),
        "zones" to JsonValue.Arr(program.keygroups.map { kg -> obj(
            "low" to num(kg.lowNote), "high" to num(kg.highNote), "root" to num(kg.rootNote),
            "layers" to JsonValue.Arr(kg.layers.mapIndexed { index, layer -> obj(
                "sample" to str(layer.sampleName), "velocityLow" to num(layer.velStart), "velocityHigh" to num(layer.velEnd),
                "sourceVelocity" to num(if (role == "melody") BellowsPadMaker.VELOCITIES[index] else 1f),
                "frames" to num(layer.frameCount), "loopStart" to num(layer.loopStartFrame),
                "loopFrames" to num(layer.frameCount - layer.loopStartFrame),
            ) }),
        ) }),
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields))
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Number) = JsonValue.Num(value.toDouble())

    private val PLAYTEST = """
        # BELLOWS phone / MPC validation

        Dry listening accepted the four identities and BODY/PRESSURE differences. The accepted DSP is unchanged.
        Hardware playback has NOT been verified. Model 1 remains frozen; these keygroups use model 2 only.

        Four melody programs cover MIDI 48..72 (C3..C5): nine minor-third roots, each with soft/mid/hard layers
        at source velocities .35/.65/1, MIDI windows 1..42/43..84/85..127. Each sample has its source attack and
        a measured sustain loop. The program supplies note-off release; the host supplies velocity gain once.
        WHEEL melody has DRONE=0. Bellows Tonic C3 is a separate WHEEL bed mapped only to MIDI 48; its tonic
        cannot follow a melody on another track. This is no shared live bellows/aftertouch model.

        ## Files

        Keep each card/<Name>.xty beside card/<Name>_[TrackData]/. That folder contains its WAVs and the
        MPC 2 <Name>.xpm twin. card/<Name>.instrument.json is the phone's sidecar. Preserve its relative paths.
        recipes/ retains the original preset and every melody layer's exact model-2 source recipe.
        The key adapter and host-gain convention in manifest.json are also required to reproduce a layer:
        rendering a source recipe alone includes native velocity gain and is not the exported layer PCM.

        ## Phone

        The existing share/import door imports kits, not this instrument ZIP. Do not import this ZIP as an .xpn.
        Use SnipSnap's BELLOWS MAKE INSTRUMENT to create a package on the phone, or provision each sidecar
        plus matching _[TrackData]/ folder into the app shelf's Instruments/ directory with a development
        file-transfer method. The directory is app-private; ordinary Files copy is not a promised workflow.
        Open MAKE > INSTRUMENTS, choose the instrument, then KEYS. An old app that cannot read velocity
        sidecar version 2 must be updated; the .xty alone does not create an instrument-list entry.

        - [ ] All four programs appear and open without missing samples.
        - [ ] Play MIDI 48, 49, 50, 51, 60, 69, 70, 71, 72; check low/high zones and both sides of a zone boundary.
        - [ ] On the grid, strike near the top/middle/bottom for soft/mid/hard; compare timbre as well as level.
        - [ ] With MIDI if available, try velocities 30, 42, 43, 64, 84, 85, 110 and 127; no gap at layer boundaries.
        - [ ] Hold a note for at least 12 seconds (longer than several loops); no repeated attack, click or level jump.
        - [ ] Release it; the configured fade ends. Play a triad and a wide chord; no excessive bus distortion.
        - [ ] Hold then change OCT/layout, leave KEYS, background the app, or disconnect MIDI; no stuck note.
        - [ ] Reopen the instrument and restart the app; mapping and layers remain present.

        ## MPC

        Copy card/ contents together to the SD card/USB drive. On MPC 3 load <Name>.xty; on MPC 2 or for
        the compatibility check browse <Name>_[TrackData]/ and load <Name>.xpm. Test both formats separately.

        - [ ] All samples resolve after loading, saving a project, closing it and reopening it.
        - [ ] Repeat the low/high-zone, soft/mid/hard, layer-boundary, long hold, release and chord checks above.
        - [ ] Stop/all-notes-off ends held notes; reopening the project does not leave notes stuck.
        - [ ] Put Bellows Tonic C3 on a separate track and hold MIDI 48 while WHEEL melody plays other notes.
        - [ ] The bed stays C3 and is not retriggered by melody; other notes on its track do not repitch it.

        Record device/model, app/firmware version, .xty or .xpm, program, note/velocity and a short recording
        for any failure. Leave these boxes unchecked until tested on the named device.
    """.trimIndent() + "\n"
}
