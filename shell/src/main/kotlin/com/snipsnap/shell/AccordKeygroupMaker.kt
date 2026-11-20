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
import com.snipsnap.synth.Accord
import com.snipsnap.synth.AccordPatch
import com.snipsnap.synth.AccordRoundRobinKits
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.min

/**
 * Finite ACCORD notes as bounded, polyphonic keygroups ([WickKeygroupMaker]'s shape), in HALOBAR's
 * matching-window Cycle layout so velocity x takes survive MPC 3 ([HalobarPadMaker]).
 *
 * Every sample is the engine's own render ([Accord.renderTake]): the saved recipe at a
 * minor-third root, at one of the three touches heard in accord-native-1 (soft .3, medium .75,
 * hard 1; the key's velocity replaces the saved touch), in two of the passage-offset takes kept
 * in accord-rr-1. A key between roots plays its neighbour a semitone fast or slow, which also
 * moves the tempo of its contacts by 6 %.
 *
 * A keygroup holds eight layers, so three touches leave room for two takes each, not all four:
 * [TAKES] picks take 0 (the plain render) and take 2, whose pulses fall half a pulse spacing from
 * take 0's, the pair furthest apart. Keys also stop striking after [KEY_SECONDS] and ring out: a
 * whole eight-second performance on 13 roots x 6 samples is about 105 MB of PCM24 and 140 MB of
 * floats held while MAKE INSTRUMENT runs, more than a phone's heap gives one sheet. A recipe saved
 * shorter than [KEY_SECONDS] keeps its own LENGTH.
 *
 * HOLD: there is no loop. A held key plays its finite note to the end; key-up fades over
 * [Spec.releaseSeconds] (the HALOBAR precedent: finite HOLD, retained onset, key-up fade).
 */
object AccordKeygroupMaker {
    /** Soft, medium and hard: the three touches every ACCORD voice was heard and kept at. */
    val TOUCHES: List<Float> = AccordRoundRobinKits.TOUCHES
    val VELOCITY_WINDOWS = listOf(0..42, 43..84, 85..127)

    /** The engine takes a key deals, in order: the plain render, then the take half a pulse spacing away. */
    val TAKES = listOf(0, 2)
    const val LAYERS = 6

    /** A key strikes for at most this long, then rings out. */
    const val KEY_SECONDS = 4.0
    const val RELEASE_SECONDS = .6f
    private const val SOURCES = "accord-source-recipes.json"

    data class Spec(
        val patch: AccordPatch,
        val lowMidi: Int = Accord.MIN_MIDI,
        val highMidi: Int = Accord.MAX_MIDI,
        val releaseSeconds: Float = RELEASE_SECONDS,
    ) {
        init {
            require(lowMidi in Accord.MIN_MIDI..Accord.MAX_MIDI && highMidi in lowMidi..Accord.MAX_MIDI)
            require(patch.midi in lowMidi..highMidi) { "Keep the authored root inside the sampled range" }
            require(releaseSeconds.isFinite() && releaseSeconds in .01f..1.5f)
        }

        /** How long each key strikes before it rings out. */
        val keySeconds: Double get() = min(patch.sourceSeconds, KEY_SECONDS)
    }

    /** [take] indexes [TAKES]. */
    data class Cell(val midi: Int, val touch: Int, val take: Int)
    data class Note(val recipe: AccordPatch, val snip: Snip)
    data class Assembly(val program: KeygroupProgram, val samples: Map<String, Snip>,
                        val recipes: Map<String, Pair<Cell, AccordPatch>>)

    /** Minor-third roots, both requested edges, and the exact authored note. */
    fun roots(spec: Spec): List<Int> =
        ((spec.lowMidi..spec.highMidi step 3).toList() + spec.highMidi + spec.patch.midi).distinct().sorted()

    /** Each cell's recipe; its take is [Accord.renderTake] (recipe, [TAKES] [[Cell.take]]). */
    fun recipes(spec: Spec): Map<Cell, AccordPatch> = linkedMapOf<Cell, AccordPatch>().apply {
        for (midi in roots(spec)) for ((touch, velocity) in TOUCHES.withIndex()) for (take in TAKES.indices)
            put(Cell(midi, touch, take), spec.patch.copy(midi = midi, velocity = velocity, sourceSeconds = spec.keySeconds))
    }

    fun stem(cell: Cell): String = "Accord_${cell.midi}_T${cell.touch + 1}_R${cell.take + 1}"

    /** One sample: the engine's take, untouched. Touch is the timbre; the sample player applies live key velocity once. */
    fun render(recipe: AccordPatch, cell: Cell): Snip = Accord.renderTake(recipe, TAKES[cell.take])

    fun render(spec: Spec, cancelled: () -> Boolean = { false }): Map<Cell, Note> =
        recipes(spec).mapValues { (cell, patch) ->
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("ACCORD keys cancelled")
            val snip = render(patch, cell)
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("ACCORD keys cancelled")
            Note(patch, snip)
        }

    /** The program for [frames] frames per cell: soft to hard windows, each window's takes side by side. */
    private fun program(name: String, spec: Spec, frames: (Cell) -> Long): KeygroupProgram {
        require(Names.isMpcSafe(name))
        val midis = roots(spec)
        val groups = midis.mapIndexed { index, midi ->
            val layers = TOUCHES.indices.flatMap { touch -> TAKES.indices.map { take ->
                val cell = Cell(midi, touch, take)
                val window = VELOCITY_WINDOWS[touch]
                VelocityLayer(stem(cell), frames(cell), window.first, window.last, loopStartFrame = 0)
            } }
            val low = if (index == 0) spec.lowMidi else (midis[index - 1] + midi) / 2 + 1
            val high = if (index == midis.lastIndex) spec.highMidi else (midi + midis[index + 1]) / 2
            Keygroup(low, high, midi, layers, cycleLayers = true, mpcVelocityCycle = true)
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds, explicitNoteOn = true)
    }

    private fun requireSource(snip: Snip) = require(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE &&
        snip.frameCount > 0 && snip.samples.all { it.isFinite() && it in -1f..1f }) {
        "ACCORD source must be finite mono 44.1kHz PCM inside full scale"
    }

    fun assemble(name: String, spec: Spec, notes: Map<Cell, Note>): Assembly {
        val expected = recipes(spec)
        require(notes.keys == expected.keys) { "Incomplete ACCORD note/touch/take grid" }
        val samples = linkedMapOf<String, Snip>()
        val source = linkedMapOf<String, Pair<Cell, AccordPatch>>()
        for ((cell, recipe) in expected) {
            val note = notes.getValue(cell)
            require(note.recipe == recipe) { "ACCORD source recipe differs at $cell" }
            requireSource(note.snip)
            samples[stem(cell)] = note.snip
            source[stem(cell)] = cell to note.recipe
        }
        return Assembly(program(name, spec) { notes.getValue(it).snip.frameCount.toLong() }, samples, source)
    }

    /**
     * Stage an entirely new portable package directory. This does not overwrite or
     * publish into the app-private shelf: caller/UI admission remains a separate path.
     */
    @Synchronized
    fun writeNewPackage(name: String, spec: Spec, notes: Map<Cell, Note>, destination: File): Assembly {
        require(!Files.isSymbolicLink(destination.toPath()) && !destination.exists()) { "ACCORD package already exists" }
        val assembly = assemble(name, spec, notes)
        val parent = destination.absoluteFile.parentFile.apply { mkdirs() }
        val stage = Files.createTempDirectory(parent.toPath(), ".accord-keygroups-").toFile()
        try {
            OneNote.writePackage(name, assembly.program, assembly.samples, stage, overwrite = false, spanKeyboard = false)
            val data = File(stage, Mpc3TrackWriter.trackDataDirName(name))
            fun num(value: Number) = JsonValue.Num(value.toDouble())
            val source = JsonValue.Obj(linkedMapOf(
                "schema" to num(1), "playback" to JsonValue.Str("FINITE"),
                "sourcePatch" to spec.patch.toJsonValue(), "lowMidi" to num(spec.lowMidi),
                "highMidi" to num(spec.highMidi),
                // Preserve Float round trips without writing a valid .01f as
                // the slightly smaller Double .009999999776482582.
                "releaseSeconds" to JsonValue.Num(spec.releaseSeconds.toString().toDouble()),
                "keySeconds" to num(spec.keySeconds),
                "takes" to JsonValue.Arr(TAKES.map { num(it) }),
                "samples" to JsonValue.Arr(assembly.recipes.map { (stem, bound) ->
                    JsonValue.Obj(linkedMapOf("sample" to JsonValue.Str("$stem.wav"),
                        "sha256" to JsonValue.Str(hash(File(data, "$stem.wav"))),
                        "take" to num(TAKES[bound.first.take]),
                        "recipe" to bound.second.toJsonValue()))
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

    /**
     * Rebind editable recipes only to the exact bounded descriptors and PCM24 files. Each WAV is
     * read, checked and let go, so validating a package never holds a second copy of its audio.
     */
    fun validate(name: String, root: File, expectedSpec: Spec? = null): KeygroupProgram {
        require(Names.isMpcSafe(name) && !Files.isSymbolicLink(root.toPath()))
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        val metadata = File(data, SOURCES)
        require(!Files.isSymbolicLink(data.toPath()) && !Files.isSymbolicLink(metadata.toPath()))
        val o = Json.parse(metadata.readText()).obj()
        require(o.keys == setOf("schema", "playback", "sourcePatch", "lowMidi", "highMidi", "releaseSeconds",
            "keySeconds", "takes", "samples"))
        require(o.getValue("schema").int() == 1 && o.getValue("playback").str() == "FINITE")
        val rawRelease = o.getValue("releaseSeconds").num()
        require(rawRelease.isFinite() && rawRelease in .01..1.5) { "ACCORD release outside supported bounds" }
        val source = AccordPatch.fromJsonValue(o.getValue("sourcePatch"))
        require(o.getValue("sourcePatch") == source.toJsonValue()) { "Unsupported ACCORD source fields cannot be discarded" }
        val spec = Spec(source, o.getValue("lowMidi").int(), o.getValue("highMidi").int(), rawRelease.toFloat())
        require(expectedSpec == null || expectedSpec == spec) { "Unexpected ACCORD source specification" }
        require(o.getValue("keySeconds").num() == spec.keySeconds) { "ACCORD key length changed" }
        require(o.getValue("takes").arr().map { it.int() } == TAKES) { "ACCORD key takes changed" }
        val expected = recipes(spec)
        val fileCells = expected.keys.associateBy { "${stem(it)}.wav" }
        val rows = o.getValue("samples").arr()
        require(rows.size == expected.size) { "Incomplete ACCORD source bindings" }
        val frames = linkedMapOf<Cell, Long>()
        for (row in rows) {
            val binding = row.obj()
            require(binding.keys == setOf("sample", "sha256", "take", "recipe"))
            val filename = binding.getValue("sample").str()
            val cell = fileCells[filename] ?: error("Unknown ACCORD sample binding: $filename")
            require(cell !in frames) { "Duplicate ACCORD sample binding" }
            require(binding.getValue("take").int() == TAKES[cell.take]) { "ACCORD take changed: $filename" }
            val recipe = AccordPatch.fromJsonValue(binding.getValue("recipe"))
            require(binding.getValue("recipe") == recipe.toJsonValue()) { "Unsupported ACCORD recipe fields cannot be discarded" }
            require(recipe == expected.getValue(cell)) { "ACCORD recipe changed: $filename" }
            val wav = File(data, filename)
            require(!Files.isSymbolicLink(wav.toPath()) && wav.isFile)
            require(hash(wav) == binding.getValue("sha256").str()) { "ACCORD PCM binding changed: $filename" }
            val sheet = requireNotNull(WavReader.readSmplStrict(wav))
            require(sheet.rootNote == cell.midi && sheet.loop == null) { "ACCORD keygroups are finite and rooted" }
            val snip = WavReader.read(wav)
            requireSource(snip)
            frames[cell] = snip.frameCount.toLong()
        }
        val program = program(name, spec) { frames.getValue(it) }
        val sidecar = InstrumentStore.sidecar(root, name)
        val native = File(root, "$name.xty")
        val legacy = File(data, "$name.xpm")
        for (file in listOf(sidecar, native, legacy)) require(!Files.isSymbolicLink(file.toPath()))
        require(data.listFiles()!!.map { it.name }.toSet() == fileCells.keys + setOf(SOURCES, legacy.name))
        require(InstrumentStore.read(sidecar).toProgram() == program) { "ACCORD phone mapping changed" }
        require(sidecar.readText() == InstrumentStore.render(program, data.name)) { "Unsupported ACCORD phone descriptor fields" }
        require(native.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(program))) { "ACCORD native mapping changed" }
        require(legacy.readText() == KeygroupWriter().write(program)) { "ACCORD legacy mapping changed" }
        return program
    }

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
