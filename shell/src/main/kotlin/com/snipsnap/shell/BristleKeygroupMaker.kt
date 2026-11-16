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
import com.snipsnap.synth.Bristle
import com.snipsnap.synth.BristleHeld
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.audio.SmplChunk
import com.snipsnap.synth.BristlePatch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Native BRISTLE notes as source-bound, polyphonic finite or held keygroups.
 * The complete authored performance survives at its effective root; other roots
 * neutralize TUNE once, rather than adding it to an already absolute root.
 * The accepted finite DSP remains unchanged; held mode uses its own versioned articulation.
 * Playback applies touch gain once.
 */
object BristleKeygroupMaker {
    val TOUCHES = listOf(.35f, .65f, 1f)
    val VELOCITY_WINDOWS = listOf(0..63, 64..104, 105..127)
    private const val SOURCES = "bristle-source-recipes.json"

    fun supports(patch: BristlePatch): Boolean = patch.velocity > 0f &&
        Bristle.rootMidi(patch) in Bristle.MIDI_MIN..Bristle.MIDI_MAX

    data class Spec(
        val patch: BristlePatch,
        val lowMidi: Int = Bristle.MIDI_MIN,
        val highMidi: Int = Bristle.MIDI_MAX,
        val releaseSeconds: Float = .045f,
        val held: Boolean = false,
        val takes: Int = 1,
    ) {
        init {
            require(takes in 1..2)
            require(patch.velocity > 0f) { "BRISTLE keygroups need a nonzero authored excitation" }
            require(lowMidi in Bristle.MIDI_MIN..Bristle.MIDI_MAX && highMidi in lowMidi..Bristle.MIDI_MAX)
            require(Bristle.rootMidi(patch) in lowMidi..highMidi) { "Keep the effective authored root inside the sampled range" }
            require(releaseSeconds.isFinite() && releaseSeconds in .01f..1.5f)
        }
    }

    data class Cell(val midi: Int, val touch: Int, val take: Int = 0)
    data class Note(val recipe: BristlePatch, val snip: Snip, val loopStartFrame: Long = 0)
    data class Assembly(val program: KeygroupProgram, val samples: Map<String, Snip>,
                        val recipes: Map<String, BristlePatch>)

    /** Every semitone: the accepted bass range needs no sample transposition. */
    fun roots(spec: Spec): List<Int> =
        (spec.lowMidi..spec.highMidi).toList()

    fun recipes(spec: Spec): Map<Cell, BristlePatch> = linkedMapOf<Cell, BristlePatch>().apply {
        val authoredRoot = Bristle.rootMidi(spec.patch)
        for (midi in roots(spec)) for ((touch, scale) in TOUCHES.withIndex()) for (take in 0 until spec.takes) {
            val source = if (midi == authoredRoot) spec.patch else spec.patch.copy(
                midi = midi, macros = spec.patch.macros + ("TUNE" to .5f))
            put(Cell(midi, touch, take), source.copy(velocity = spec.patch.velocity * scale, take = (spec.patch.take + take) % 2))
        }
    }

    /** Native excitation changes tone; the existing sample player applies live key velocity once. */
    fun render(spec: Spec, cancelled: () -> Boolean = { false }): Map<Cell, Note> =
        recipes(spec).mapValues { (cell, patch) ->
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("BRISTLE keys cancelled")
            val note = prepareNote(patch, TOUCHES[cell.touch], spec.held, cancelled)
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("BRISTLE keys cancelled")
            Note(patch, note.snip, note.loopStartFrame)
        }

    /** Remove only the sampled touch gain. Live velocity supplies it once; authored source gain remains. */
    fun prepare(patch: BristlePatch, touch: Float): Snip = prepareNote(patch, touch, false).snip

    fun prepareNote(patch: BristlePatch, touch: Float, held: Boolean,
                    cancelled: () -> Boolean = { false }): KeyNote {
        require(touch in TOUCHES)
        val note = if (held) BristleHeld.render(patch, cancelled).note else KeyNote(patch.render())
        val snip = note.snip
        return note.copy(snip = Snip(FloatArray(snip.samples.size) { snip.samples[it] / touch }, snip.channels, snip.sampleRate))
    }

    fun assemble(name: String, spec: Spec, notes: Map<Cell, Note>): Assembly {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH)
        val expected = recipes(spec)
        require(notes.keys == expected.keys) { "Incomplete BRISTLE note/touch grid" }
        val midis = roots(spec)
        val samples = linkedMapOf<String, Snip>()
        val source = linkedMapOf<String, BristlePatch>()
        val groups = midis.mapIndexed { index, midi ->
            val layers = TOUCHES.indices.flatMap { touch -> (0 until spec.takes).map { take ->
                val cell = Cell(midi, touch, take)
                val note = notes.getValue(cell)
                require(note.recipe == expected.getValue(cell)) { "BRISTLE source recipe differs at $cell" }
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE &&
                    note.snip.frameCount > 0 && note.snip.samples.all { it.isFinite() }) {
                    "BRISTLE source must be finite mono 44.1kHz PCM"
                }
                if (spec.held) {
                    require(note.loopStartFrame == BristleHeld.LOOP_START_FRAME.toLong()) { "BRISTLE held loop start changed" }
                    require(note.snip.frameCount > note.loopStartFrame)
                    require(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR) { "BRISTLE held seam failed" }
                } else require(note.loopStartFrame == 0L) { "BRISTLE finite source acquired a loop" }
                val stem = stem(cell, spec)
                samples[stem] = note.snip
                source[stem] = note.recipe
                val window = VELOCITY_WINDOWS[touch]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, loopStartFrame = note.loopStartFrame)
            } }
            val low = if (index == 0) spec.lowMidi else (midis[index - 1] + midi) / 2 + 1
            val high = if (index == midis.lastIndex) spec.highMidi else (midi + midis[index + 1]) / 2
            Keygroup(low, high, midi, layers, cycleLayers = spec.takes > 1)
        }
        return Assembly(KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds,
            explicitNoteOn = true), samples, source)
    }

    private fun stem(cell: Cell, spec: Spec): String =
        "Bristle_${cell.midi}_T${cell.touch + 1}" + if (spec.takes > 1) "_R${cell.take + 1}" else ""

    private val touchNames = listOf("Soft", "Medium", "Hard")

    /** The current MPC keygroup writer cannot combine cycling and velocity switching. Each companion
     * cycles both takes at one fixed source touch; live velocity still controls gain. */
    fun fixedTouch(assembly: Assembly, touch: Int): Assembly {
        require(touch in TOUCHES.indices)
        val suffix = " ${touchNames[touch]}"
        val childName = assembly.program.name.take(OneNote.MAX_NAME_LENGTH - suffix.length).trimEnd() + suffix
        val program = assembly.program.copy(name = childName, keygroups = assembly.program.keygroups.map { kg ->
            val window = VELOCITY_WINDOWS[touch]
            kg.copy(layers = kg.layers.filter { it.velStart == window.first }.map { it.copy(velStart = 0, velEnd = 127) })
        })
        require(program.keygroups.all { it.mpcCycle && it.layers.size == 2 })
        val stems = program.keygroups.flatMap { it.layers }.map { it.sampleName }.toSet()
        return Assembly(program, assembly.samples.filterKeys { it in stems }.mapValues { (_, snip) ->
            snip.copy(samples = FloatArray(snip.samples.size) { snip.samples[it] * TOUCHES[touch] })
        }, assembly.recipes.filterKeys { it in stems })
    }

    private fun validateCompanions(data: File, assembly: Assembly) {
        for (touch in TOUCHES.indices) {
            val child = fixedTouch(assembly, touch)
            val name = child.program.name
            val root = File(data, "MPC ${touchNames[touch]}")
            val samples = File(root, Mpc3TrackWriter.trackDataDirName(name))
            require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(samples.toPath()))
            require(root.listFiles()!!.map { it.name }.toSet() == setOf("$name.xty", "$name${InstrumentStore.SUFFIX}", samples.name))
            require(samples.listFiles()!!.map { it.name }.toSet() == child.samples.keys.map { "$it.wav" }.toSet() + "$name.xpm")
            val sidecar = InstrumentStore.sidecar(root, name)
            val native = File(root, "$name.xty")
            val legacy = File(samples, "$name.xpm")
            for (f in listOf(sidecar, native, legacy)) require(!Files.isSymbolicLink(f.toPath()))
            require(sidecar.readText() == InstrumentStore.render(child.program, samples.name))
            require(native.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(child.program)))
            require(legacy.readText() == KeygroupWriter().write(child.program))
            for (kg in child.program.keygroups) for (layer in kg.layers) {
                val f = File(samples, "${layer.sampleName}.wav")
                require(!Files.isSymbolicLink(f.toPath()))
                val snip = WavReader.read(f)
                val expected = child.samples.getValue(layer.sampleName)
                require(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.frameCount == expected.frameCount)
                require(snip.samples.indices.all { kotlin.math.abs(snip.samples[it] - expected.samples[it]) < .0000003f }) { "BRISTLE companion PCM changed" }
                val loop = if (layer.loopStartFrame > 0) SmplChunk.Loop(layer.loopStartFrame, layer.frameCount) else null
                require(WavReader.readSmplStrict(f) == SmplChunk(kg.rootNote, loop))
            }
        }
    }

    /**
     * Stage an entirely new portable package directory. This does not overwrite or
     * publish into the app-private shelf: caller/UI admission remains a separate path.
     */
    @Synchronized
    fun writeNewPackage(name: String, spec: Spec, notes: Map<Cell, Note>, destination: File): Assembly {
        require(!Files.isSymbolicLink(destination.toPath()) && !destination.exists()) { "BRISTLE package already exists" }
        val assembly = assemble(name, spec, notes)
        val parent = destination.absoluteFile.parentFile.apply { mkdirs() }
        val stage = Files.createTempDirectory(parent.toPath(), ".bristle-keygroups-").toFile()
        try {
            OneNote.writePackage(name, assembly.program, assembly.samples, stage, overwrite = false, spanKeyboard = false)
            val data = File(stage, Mpc3TrackWriter.trackDataDirName(name))
            fun num(value: Number) = JsonValue.Num(value.toDouble())
            val source = JsonValue.Obj(linkedMapOf(
                "schema" to num(if (spec.takes > 1) 3 else if (spec.held) 2 else 1),
                "playback" to JsonValue.Str(if (spec.held) "HELD_TOUCH_PREPARED" else "FINITE_TOUCH_PREPARED"),
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
                        "recipeSha256" to JsonValue.Str(hashText(recipe.toJsonText()))).apply {
                        if (spec.held) put("loopStartFrame", num(BristleHeld.LOOP_START_FRAME))
                    })
                }),
             ).apply {
                if (spec.held) put("heldModel", num(BristleHeld.MODEL_VERSION))
                if (spec.takes > 1) {
                    put("takes", num(spec.takes)); put("rrModel", num(1))
                    put("mpcPolicy", JsonValue.Str("FIRST_TAKE_PLUS_FIXED_TOUCH_CYCLES"))
                }
            })
            File(data, SOURCES).writeText(Json.write(source) + "\n")
            if (spec.takes > 1) for (touch in TOUCHES.indices) {
                val child = fixedTouch(assembly, touch)
                OneNote.writePackage(child.program.name, child.program, child.samples,
                    File(data, "MPC ${touchNames[touch]}"), overwrite = false, spanKeyboard = false)
            }
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
        val held = when (o.getValue("playback").str()) {
            "FINITE_TOUCH_PREPARED" -> false
            "HELD_TOUCH_PREPARED" -> true
            else -> error("Unknown BRISTLE playback mode")
        }
        val rr = o.getValue("schema").int() == 3
        require(o.keys == setOf("schema", "playback", "sourcePatch", "sourcePatchSha256", "lowMidi", "highMidi", "releaseSeconds", "samples") +
            (if (held) setOf("heldModel") else emptySet()) + (if (rr) setOf("takes", "rrModel", "mpcPolicy") else emptySet()))
        require(o.getValue("schema").int() == if (rr) 3 else if (held) 2 else 1)
        if (rr) {
            require(o.getValue("takes").int() == 2 && o.getValue("rrModel").int() == 1)
            require(o.getValue("mpcPolicy").str() == "FIRST_TAKE_PLUS_FIXED_TOUCH_CYCLES")
        }
        if (held) require(o.getValue("heldModel").int() == BristleHeld.MODEL_VERSION) { "Unknown BRISTLE held renderer" }
        val rawRelease = o.getValue("releaseSeconds").num()
        require(rawRelease.isFinite() && rawRelease in .01..1.5) { "BRISTLE release outside supported bounds" }
        val source = BristlePatch.fromJsonValue(o.getValue("sourcePatch")) as BristlePatch
        require(o.getValue("sourcePatch") == source.toJsonValue()) { "Unsupported BRISTLE source fields cannot be discarded" }
        require(o.getValue("sourcePatchSha256").str() == hashText(source.toJsonText())) { "BRISTLE source recipe binding changed" }
        val spec = Spec(source,
            o.getValue("lowMidi").int(), o.getValue("highMidi").int(), rawRelease.toFloat(), held, if (rr) 2 else 1)
        require(expectedSpec == null || expectedSpec == spec) { "Unexpected BRISTLE source specification" }
        val expected = recipes(spec)
        val fileCells = expected.keys.associateBy { "${stem(it, spec)}.wav" }
        val rows = o.getValue("samples").arr()
        require(rows.size == expected.size) { "Incomplete BRISTLE source bindings" }
        val notes = linkedMapOf<Cell, Note>()
        for (row in rows) {
            val binding = row.obj()
            require(binding.keys == setOf("sample", "sha256", "recipe", "recipeSha256") + if (held) setOf("loopStartFrame") else emptySet())
            val filename = binding.getValue("sample").str()
            val cell = fileCells[filename] ?: error("Unknown BRISTLE sample binding: $filename")
            require(cell !in notes) { "Duplicate BRISTLE sample binding" }
            val recipe = BristlePatch.fromJsonValue(binding.getValue("recipe")) as BristlePatch
            require(binding.getValue("recipe") == recipe.toJsonValue()) { "Unsupported BRISTLE recipe fields cannot be discarded" }
            require(recipe == expected.getValue(cell)) { "BRISTLE recipe changed: $filename" }
            require(binding.getValue("recipeSha256").str() == hashText(recipe.toJsonText())) { "BRISTLE cell recipe binding changed: $filename" }
            val wav = File(data, filename)
            require(!Files.isSymbolicLink(wav.toPath()) && wav.isFile)
            require(hash(wav) == binding.getValue("sha256").str()) { "BRISTLE PCM binding changed: $filename" }
            val sheet = requireNotNull(WavReader.readSmplStrict(wav))
            val snip = WavReader.read(wav)
            val start = if (held) binding.getValue("loopStartFrame").int().toLong() else 0L
            val expectedLoop = if (held) SmplChunk.Loop(start, snip.frameCount.toLong()) else null
            require(sheet.rootNote == cell.midi && sheet.loop == expectedLoop) { "BRISTLE WAV root/loop changed" }
            notes[cell] = Note(recipe, snip, start)
        }
        val assembly = assemble(name, spec, notes)
        val sidecar = InstrumentStore.sidecar(root, name)
        val native = File(root, "$name.xty")
        val legacy = File(data, "$name.xpm")
        for (file in listOf(sidecar, native, legacy)) require(!Files.isSymbolicLink(file.toPath()))
        require(data.listFiles()!!.map { it.name }.toSet() == fileCells.keys + setOf(SOURCES, legacy.name) + if (rr) touchNames.map { "MPC $it" }.toSet() else emptySet())
        require(InstrumentStore.read(sidecar).toProgram() == assembly.program) { "BRISTLE phone mapping changed" }
        require(sidecar.readText() == InstrumentStore.render(assembly.program, data.name)) { "Unsupported BRISTLE phone descriptor fields" }
        require(native.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(assembly.program))) { "BRISTLE native mapping changed" }
        require(legacy.readText() == KeygroupWriter().write(assembly.program)) { "BRISTLE legacy mapping changed" }
        if (rr) validateCompanions(data, assembly)
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
