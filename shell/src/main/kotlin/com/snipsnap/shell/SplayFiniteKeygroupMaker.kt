package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Splay
import com.snipsnap.synth.SplayPatch
import com.snipsnap.synth.SplayAcceptedBank
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Native finite SPLAY notes as source-bound, polyphonic keygroups.
 * The complete authored performance survives at its saved MIDI root; other roots
 * render the same finite patch at their explicit MIDI note. The DSP/model/defaults
 * are unchanged. Five kept settings remain ONE TONGUE voice; HOLD and additional
 * distinct voices remain separate work.
 */
object SplayFiniteKeygroupMaker {
    val TOUCHES = listOf(.30f, .65f, 1f)
    val VELOCITY_WINDOWS = listOf(0..42, 43..84, 85..127)
    private const val SOURCES = "splay-finite-source-recipes.json"

    /** Five premade settings of the existing one voice; all kept sources stay exact. */
    fun factorySpecs(): Map<String, Spec> = SplayAcceptedBank.entries.associate {
        "SPLAY " + it.mechanisms to Spec(it.patch)
    }

    fun supports(patch: SplayPatch): Boolean = patch.velocity > 0f &&
        ((patch.macros["HOLD"] ?: 0f) == 0f && patch.model in 1..2) &&
        patch.midi in Splay.MIDI_MIN..Splay.MIDI_MAX

    data class Spec(
        val patch: SplayPatch,
        val lowMidi: Int = Splay.MIDI_MIN,
        val highMidi: Int = Splay.MIDI_MAX,
        val releaseSeconds: Float = .35f,
    ) {
        init {
            require(((patch.macros["HOLD"] ?: 0f) == 0f && patch.model in 1..2)) {
                "SPLAY finite keygroups require saved models 1/2 and HOLD 0; sustain remains separate"
            }
            require(patch.velocity > 0f) { "SPLAY keygroups need a nonzero authored excitation" }
            require(lowMidi in Splay.MIDI_MIN..Splay.MIDI_MAX && highMidi in lowMidi..Splay.MIDI_MAX)
            require(patch.midi in lowMidi..highMidi) { "Keep the saved authored MIDI root inside the sampled range" }
            require(releaseSeconds.isFinite() && releaseSeconds in .01f..1.5f)
        }
    }

    data class Cell(val midi: Int, val touch: Int)
    data class Note(val recipe: SplayPatch, val snip: Snip)
    data class Assembly(val program: KeygroupProgram, val samples: Map<String, Snip>,
                        val recipes: Map<String, SplayPatch>)

    /** Minor-third roots, both requested edges, and the exact authored note. */
    fun roots(spec: Spec): List<Int> =
        ((spec.lowMidi..spec.highMidi step 3).toList() + spec.highMidi + spec.patch.midi).distinct().sorted()

    fun recipes(spec: Spec): Map<Cell, SplayPatch> = linkedMapOf<Cell, SplayPatch>().apply {
        val authoredRoot = spec.patch.midi
        for (midi in roots(spec)) for ((touch, scale) in TOUCHES.withIndex()) {
            val source = if (midi == authoredRoot) spec.patch else spec.patch.copy(midi = midi)
            put(Cell(midi, touch), source.copy(velocity = spec.patch.velocity * scale))
        }
    }

    /** SPLAY retains its existing gain-only absolute native velocity levels.
     * The sample player applies live key velocity once; no timbral-velocity claim. */
    fun render(spec: Spec, cancelled: () -> Boolean = { false }): Map<Cell, Note> =
        recipes(spec).mapValues { (_, patch) ->
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("SPLAY keys cancelled")
            val snip = patch.render()
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("SPLAY keys cancelled")
            Note(patch, snip)
        }

    fun assemble(name: String, spec: Spec, notes: Map<Cell, Note>): Assembly {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH)
        val expected = recipes(spec)
        require(notes.keys == expected.keys) { "Incomplete SPLAY note/touch grid" }
        val midis = roots(spec)
        val samples = linkedMapOf<String, Snip>()
        val source = linkedMapOf<String, SplayPatch>()
        val groups = midis.mapIndexed { index, midi ->
            val layers = TOUCHES.indices.map { touch ->
                val cell = Cell(midi, touch)
                val note = notes.getValue(cell)
                require(note.recipe == expected.getValue(cell)) { "SPLAY source recipe differs at $cell" }
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE &&
                    note.snip.frameCount > 0 && note.snip.samples.all { it.isFinite() }) {
                    "SPLAY source must be finite mono 44.1kHz PCM"
                }
                val stem = "Splay_${midi}_T${touch + 1}"
                samples[stem] = note.snip
                source[stem] = note.recipe
                val window = VELOCITY_WINDOWS[touch]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, loopStartFrame = 0)
            }
            val low = if (index == 0) spec.lowMidi else (midis[index - 1] + midi) / 2 + 1
            val high = if (index == midis.lastIndex) spec.highMidi else (midi + midis[index + 1]) / 2
            Keygroup(low, high, midi, layers)
        }
        return Assembly(KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds,
            explicitNoteOn = true), samples, source)
    }

    /**
     * Stage an entirely new portable package directory. This does not overwrite or
     * publish into the app-private shelf: caller/UI admission remains a separate path.
     */
    @Synchronized
    fun writeNewPackage(name: String, spec: Spec, notes: Map<Cell, Note>, destination: File): Assembly {
        require(!Files.isSymbolicLink(destination.toPath()) && !destination.exists()) { "SPLAY package already exists" }
        val assembly = assemble(name, spec, notes)
        val parent = destination.absoluteFile.parentFile.apply { mkdirs() }
        val stage = Files.createTempDirectory(parent.toPath(), ".splay-finite-keygroups-").toFile()
        try {
            OneNote.writePackage(name, assembly.program, assembly.samples, stage, overwrite = false, spanKeyboard = false)
            val data = File(stage, Mpc3TrackWriter.trackDataDirName(name))
            fun num(value: Number) = JsonValue.Num(value.toDouble())
            val source = JsonValue.Obj(linkedMapOf(
                "schema" to num(1), "playback" to JsonValue.Str("FINITE"),
                "sourcePatch" to spec.patch.toJsonValue(),
                "sourcePatchSha256" to JsonValue.Str(hashText(spec.patch.toJsonText())), "lowMidi" to num(spec.lowMidi),
                "highMidi" to num(spec.highMidi),
                // Preserve Float round trips without writing a valid .01f as
                // the slightly smaller Double .009999999776482582.
                "releaseSeconds" to JsonValue.Num(spec.releaseSeconds.toString().toDouble()),
                "samples" to JsonValue.Arr(assembly.recipes.map { (stem, recipe) ->
                    JsonValue.Obj(linkedMapOf("sample" to JsonValue.Str("$stem.wav"),
                        "sha256" to JsonValue.Str(hash(File(data, "$stem.wav"))),
                        "recipe" to recipe.toJsonValue(),
                        "recipeSha256" to JsonValue.Str(hashText(recipe.toJsonText()))))
                }),
            ))
            File(data, SOURCES).writeText(Json.write(source) + "\n")
            validate(name, stage, spec)
            Files.move(stage.toPath(), destination.toPath())
            return assembly
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    /** Rebind editable recipes only to the exact bounded descriptors and PCM24 files. */
    fun validate(name: String, root: File, expectedSpec: Spec? = null): Assembly {
        require(Names.isMpcSafe(name) && !Files.isSymbolicLink(root.toPath()))
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        val metadata = File(data, SOURCES)
        require(!Files.isSymbolicLink(data.toPath()) && !Files.isSymbolicLink(metadata.toPath()))
        val o = Json.parse(metadata.readText()).obj()
        require(o.keys == setOf("schema", "playback", "sourcePatch", "sourcePatchSha256", "lowMidi", "highMidi", "releaseSeconds", "samples"))
        require(o.getValue("schema").int() == 1 && o.getValue("playback").str() == "FINITE")
        val rawRelease = o.getValue("releaseSeconds").num()
        require(rawRelease.isFinite() && rawRelease in .01..1.5) { "SPLAY release outside supported bounds" }
        val source = SplayPatch.fromJsonValue(o.getValue("sourcePatch"))
        require(o.getValue("sourcePatch") == source.toJsonValue()) { "Unsupported SPLAY source fields cannot be discarded" }
        require(o.getValue("sourcePatchSha256").str() == hashText(source.toJsonText())) { "SPLAY source recipe binding changed" }
        val spec = Spec(source,
            o.getValue("lowMidi").int(), o.getValue("highMidi").int(), rawRelease.toFloat())
        require(expectedSpec == null || expectedSpec == spec) { "Unexpected SPLAY source specification" }
        val expected = recipes(spec)
        val fileCells = expected.keys.associateBy { "Splay_${it.midi}_T${it.touch + 1}.wav" }
        val rows = o.getValue("samples").arr()
        require(rows.size == expected.size) { "Incomplete SPLAY source bindings" }
        val notes = linkedMapOf<Cell, Note>()
        for (row in rows) {
            val binding = row.obj()
            require(binding.keys == setOf("sample", "sha256", "recipe", "recipeSha256"))
            val filename = binding.getValue("sample").str()
            val cell = fileCells[filename] ?: error("Unknown SPLAY sample binding: $filename")
            require(cell !in notes) { "Duplicate SPLAY sample binding" }
            val recipe = SplayPatch.fromJsonValue(binding.getValue("recipe"))
            require(binding.getValue("recipe") == recipe.toJsonValue()) { "Unsupported SPLAY recipe fields cannot be discarded" }
            require(recipe == expected.getValue(cell)) { "SPLAY recipe changed: $filename" }
            require(binding.getValue("recipeSha256").str() == hashText(recipe.toJsonText())) { "SPLAY cell recipe binding changed: $filename" }
            val wav = File(data, filename)
            require(!Files.isSymbolicLink(wav.toPath()) && wav.isFile)
            require(hash(wav) == binding.getValue("sha256").str()) { "SPLAY PCM binding changed: $filename" }
            val sheet = requireNotNull(WavReader.readSmplStrict(wav))
            require(sheet.rootNote == cell.midi && sheet.loop == null) { "SPLAY keygroups are finite and rooted" }
            notes[cell] = Note(recipe, WavReader.read(wav))
        }
        val assembly = assemble(name, spec, notes)
        val sidecar = InstrumentStore.sidecar(root, name)
        val native = File(root, "$name.xty")
        val legacy = File(data, "$name.xpm")
        for (file in listOf(sidecar, native, legacy)) require(!Files.isSymbolicLink(file.toPath()))
        require(data.listFiles()!!.map { it.name }.toSet() == fileCells.keys + setOf(SOURCES, legacy.name))
        require(InstrumentStore.read(sidecar).toProgram() == assembly.program) { "SPLAY phone mapping changed" }
        require(sidecar.readText() == InstrumentStore.render(assembly.program, data.name)) { "Unsupported SPLAY phone descriptor fields" }
        require(native.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(assembly.program))) { "SPLAY native mapping changed" }
        require(legacy.readText() == KeygroupWriter().write(assembly.program)) { "SPLAY legacy mapping changed" }
        return assembly
    }

    /** Actual shelf-visible siblings; fresh names only, with rollback at each publication move. */
    fun exportFresh(name: String, spec: Spec, notes: Map<Cell, Note>, destination: File,
                    hooks: InstrumentPackageAdmission.Hooks = InstrumentPackageAdmission.Hooks()): Assembly =
        InstrumentPackageAdmission.publishNew(name, destination,
            build = { packet -> writeNewPackage(name, spec, notes, packet) },
            validate = { packet -> validate(name, packet, spec); Unit }, hooks = hooks)

    private fun hashText(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
