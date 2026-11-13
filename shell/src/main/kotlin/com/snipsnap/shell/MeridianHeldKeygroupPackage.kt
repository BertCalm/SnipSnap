package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Source bindings around the existing held maker, with no new synthesis or
 * keyboard policy. Original source identity is separate from the intentionally
 * neutral-TUNE, absolute-touch cell recipes used by the existing instrument.
 */
object MeridianHeldKeygroupPackage {
    const val SOURCES = "meridian-held-source-recipes.json"
    private const val TOUCH_POLICY = "ABSOLUTE_030_065_100"
    private const val TUNE_POLICY = "NEUTRAL_AT_ABSOLUTE_NATIVE_ROOT"

    data class Cell(val midi: Int, val touch: Int)
    data class Assembly(val program: KeygroupProgram, val samples: Map<String, Snip>,
                        val recipes: Map<String, MeridianPatch>)

    fun recipes(spec: MeridianPadMaker.Spec): Map<Cell, MeridianPatch> = linkedMapOf<Cell, MeridianPatch>().apply {
        val source = MeridianPadMaker.melodyPatch(spec.patch)
        for (midi in MeridianPadMaker.zoneMidis(spec)) for ((touch, velocity) in MeridianPadMaker.VELOCITIES.withIndex())
            put(Cell(midi, touch), source.copy(midi = midi, velocity = velocity))
    }

    /** Existing assembly remains the one authority for stems, ranges, loops and descriptors. */
    fun assemble(name: String, spec: MeridianPadMaker.Spec, notes: List<List<KeyNote>>): Assembly {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH)
        val (program, samples) = MeridianPadMaker.assemble(name, spec, notes)
        val cells = recipes(spec)
        val bound = linkedMapOf<String, MeridianPatch>()
        for (group in program.keygroups) for ((touch, layer) in group.layers.withIndex())
            bound[layer.sampleName] = cells.getValue(Cell(group.rootNote, touch))
        require(samples.keys == bound.keys)
        return Assembly(program, samples, bound)
    }

    /** Preserve the old three descriptor/PCM files, then add source metadata inside TrackData. */
    @Synchronized
    fun writeNewPackage(name: String, spec: MeridianPadMaker.Spec, notes: List<List<KeyNote>>, destination: File): Assembly {
        require(!Files.isSymbolicLink(destination.toPath()) && !destination.exists()) { "MERIDIAN held package already exists" }
        val assembly = assemble(name, spec, notes)
        val parent = destination.absoluteFile.parentFile.apply { mkdirs() }
        val stage = Files.createTempDirectory(parent.toPath(), ".meridian-held-keygroups-").toFile()
        try {
            OneNote.writePackage(name, assembly.program, assembly.samples, stage, overwrite = false, spanKeyboard = false)
            val data = File(stage, Mpc3TrackWriter.trackDataDirName(name))
            val rows = assembly.program.keygroups.flatMap { group -> group.layers.map { layer ->
                val recipe = assembly.recipes.getValue(layer.sampleName)
                JsonValue.Obj(linkedMapOf(
                    "sample" to JsonValue.Str("${layer.sampleName}.wav"),
                    "sha256" to JsonValue.Str(hash(File(data, "${layer.sampleName}.wav"))),
                    "rootMidi" to num(group.rootNote),
                    "recipe" to recipe.toJsonValue(),
                    "recipeSha256" to JsonValue.Str(hashText(recipe.toJsonText())),
                    "loopStartFrame" to num(layer.loopStartFrame),
                    "loopEndFrameExclusive" to num(layer.frameCount),
                ))
            } }
            val source = JsonValue.Obj(linkedMapOf(
                "schema" to num(1), "playback" to JsonValue.Str("HELD"),
                "sourcePatch" to spec.patch.toJsonValue(),
                "sourcePatchSha256" to JsonValue.Str(hashText(spec.patch.toJsonText())),
                "lowMidi" to num(assembly.program.keygroups.minOf { it.lowNote }),
                "highMidi" to num(assembly.program.keygroups.maxOf { it.highNote }),
                "releaseSeconds" to JsonValue.Num(spec.releaseSeconds.toString().toDouble()),
                "touchPolicy" to JsonValue.Str(TOUCH_POLICY), "tunePolicy" to JsonValue.Str(TUNE_POLICY),
                "samples" to JsonValue.Arr(rows),
            ))
            File(data, SOURCES).writeText(Json.write(source) + "\n")
            validate(name, stage, spec)
            Files.move(stage.toPath(), destination.toPath())
            return assembly
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    /** Fresh-only editable provenance. Altered sampler descriptors are not silently rebound. */
    fun validate(name: String, root: File, expectedSpec: MeridianPadMaker.Spec? = null): Assembly {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH)
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        val metadata = File(data, SOURCES)
        for (file in listOf(root, data, metadata)) require(!Files.isSymbolicLink(file.toPath()))
        val o = Json.parse(metadata.readText()).obj()
        require(o.keys == setOf("schema", "playback", "sourcePatch", "sourcePatchSha256", "lowMidi",
            "highMidi", "releaseSeconds", "touchPolicy", "tunePolicy", "samples"))
        require(o.getValue("schema").int() == 1 && o.getValue("playback").str() == "HELD")
        require(o.getValue("touchPolicy").str() == TOUCH_POLICY && o.getValue("tunePolicy").str() == TUNE_POLICY)
        val rawRelease = o.getValue("releaseSeconds").num()
        require(rawRelease.isFinite() && rawRelease in MeridianPadMaker.RELEASE_MIN_SECONDS.toString().toDouble()..
            MeridianPadMaker.RELEASE_MAX_SECONDS.toString().toDouble())
        val source = MeridianPatch.fromJsonValue(o.getValue("sourcePatch")) as MeridianPatch
        require(o.getValue("sourcePatch") == source.toJsonValue()) { "Unsupported MERIDIAN held source fields" }
        require(o.getValue("sourcePatchSha256").str() == hashText(source.toJsonText())) { "MERIDIAN held source hash changed" }
        val spec = MeridianPadMaker.Spec(source, rawRelease.toFloat())
        require(expectedSpec == null || expectedSpec == spec) { "Unexpected MERIDIAN held source specification" }
        val midis = MeridianPadMaker.zoneMidis(spec)
        require(o.getValue("lowMidi").int() == midis.first() && o.getValue("highMidi").int() == midis.last())
        val cells = recipes(spec)
        val rows = o.getValue("samples").arr()
        require(rows.size == cells.size) { "Incomplete MERIDIAN held bindings" }
        val notes = linkedMapOf<Cell, KeyNote>()
        val sampleNames = linkedMapOf<Cell, String>()
        for (row in rows) {
            val binding = row.obj()
            require(binding.keys == setOf("sample", "sha256", "rootMidi", "recipe", "recipeSha256",
                "loopStartFrame", "loopEndFrameExclusive"))
            val recipe = MeridianPatch.fromJsonValue(binding.getValue("recipe")) as MeridianPatch
            require(binding.getValue("recipe") == recipe.toJsonValue()) { "Unsupported MERIDIAN held cell fields" }
            require(binding.getValue("recipeSha256").str() == hashText(recipe.toJsonText())) { "MERIDIAN held cell hash changed" }
            val midi = binding.getValue("rootMidi").int()
            val touch = MeridianPadMaker.VELOCITIES.indexOf(recipe.velocity)
            val cell = Cell(midi, touch)
            require(cell in cells && cell !in notes && recipe == cells.getValue(cell)) { "Relabelled MERIDIAN held cell" }
            val filename = binding.getValue("sample").str()
            // The authoritative old assembler supplies exact stems after note loading.
            require(filename.endsWith(".wav") && filename == File(filename).name && '/' !in filename && '\\' !in filename)
            val wav = File(data, filename)
            require(!Files.isSymbolicLink(wav.toPath()) && wav.isFile)
            require(hash(wav) == binding.getValue("sha256").str()) { "MERIDIAN held PCM changed" }
            val snip = WavReader.read(wav)
            val start = binding.getValue("loopStartFrame").long()
            val end = binding.getValue("loopEndFrameExclusive").long()
            require(end == snip.frameCount.toLong() && start in 256 until end)
            val sheet = requireNotNull(WavReader.readSmplStrict(wav))
            require(sheet.rootNote == midi && sheet.loop == SmplChunk.Loop(start, end)) { "MERIDIAN held WAV root/loop changed" }
            notes[cell] = KeyNote(snip, start)
            sampleNames[cell] = filename
        }
        val ordered = midis.map { midi -> MeridianPadMaker.VELOCITIES.indices.map { touch ->
            notes.getValue(Cell(midi, touch))
        } }
        val assembly = assemble(name, spec, ordered)
        for (group in assembly.program.keygroups) for ((touch, layer) in group.layers.withIndex())
            require(sampleNames.getValue(Cell(group.rootNote, touch)) == "${layer.sampleName}.wav") { "MERIDIAN held sample name changed" }
        val sidecar = InstrumentStore.sidecar(root, name)
        val native = File(root, "$name.xty")
        val legacy = File(data, "$name.xpm")
        for (file in listOf(sidecar, native, legacy)) require(!Files.isSymbolicLink(file.toPath()))
        require(data.listFiles()!!.map { it.name }.toSet() == assembly.samples.keys.map { "$it.wav" }.toSet() + setOf(SOURCES, legacy.name))
        require(InstrumentStore.read(sidecar).toProgram() == assembly.program) { "MERIDIAN held phone mapping changed" }
        require(sidecar.readText() == InstrumentStore.render(assembly.program, data.name))
        require(native.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(assembly.program))) { "MERIDIAN held native mapping changed" }
        require(legacy.readText() == KeygroupWriter().write(assembly.program)) { "MERIDIAN held legacy mapping changed" }
        return assembly
    }

    /** Validate first; publish TrackData/native/sidecar in order, rolling back handled failures. */
    fun exportFresh(name: String, spec: MeridianPadMaker.Spec, notes: List<List<KeyNote>>, destination: File,
                    hooks: InstrumentPackageAdmission.Hooks = InstrumentPackageAdmission.Hooks()): Assembly =
        InstrumentPackageAdmission.publishNew(name, destination,
            build = { packet -> writeNewPackage(name, spec, notes, packet) },
            validate = { packet -> validate(name, packet, spec); Unit }, hooks = hooks)

    private fun num(value: Number) = JsonValue.Num(value.toDouble())
    private fun hashText(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
