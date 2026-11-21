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
import com.snipsnap.synth.Drawl
import com.snipsnap.synth.DrawlChords
import com.snipsnap.synth.DrawlKits
import com.snipsnap.synth.DrawlPatch
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
 * Finite DRAWL notes as bounded, polyphonic keygroups ([AccordKeygroupMaker]'s shape), in HALOBAR's
 * matching-window Cycle layout so velocity x takes survive MPC 3.
 *
 * Every sample is the engine's own render: the saved recipe moved to a minor-third root by TUNE
 * (DRAWL plays a voice's root an octave either side, the reach of TUNE, so a voice has 25 keys), at
 * one of the three touches the owner kept (soft .3, medium .75, hard 1, as DRAWL TAKES' zones; the
 * key's velocity replaces the saved touch), in two of the player's takes. A key between roots plays
 * its neighbour a semitone fast or slow.
 *
 * A keygroup holds eight layers, so three touches leave room for two takes each, not all four:
 * [TAKES] picks take 0 (the saved render) and take 1, the first two the owner heard natively.
 * HOLD is capped at [KEY_HOLD] on keys (a key rings at most about 6 s, STEEL's longest): a full
 * 8-second note on 9 roots x 6 samples would hold some 85 MB of floats while MAKE INSTRUMENT runs.
 * A recipe saved shorter keeps its own HOLD.
 *
 * HOLD is finite, the owner's decision: there is no loop. A held key plays its note to the end;
 * key-up fades over [Spec.releaseSeconds] (the HALOBAR and ACCORD policy). A strummed recipe is a
 * chord on one pad, not a key; the chord tool plays chords on these keys ([DrawlChords]).
 */
object DrawlKeygroupMaker {
    /** Soft, medium and hard: the touches every DRAWL voice was heard and kept at. */
    val TOUCHES: List<Float> = DrawlKits.TOUCHES
    val VELOCITY_WINDOWS = listOf(0..42, 43..84, 85..127)

    /** The engine takes a key deals, in order: the saved render, then the first other take. */
    val TAKES = listOf(0, 1)
    const val LAYERS = 6

    /** The longest HOLD a key renders; see the class note. */
    const val KEY_HOLD = .5f
    const val RELEASE_SECONDS = .6f
    private const val SOURCES = "drawl-source-recipes.json"

    data class Spec(
        val patch: DrawlPatch,
        val lowMidi: Int = DrawlChords.range(patch.voice).first,
        val highMidi: Int = DrawlChords.range(patch.voice).last,
        val releaseSeconds: Float = RELEASE_SECONDS,
    ) {
        init {
            require(patch.strum == null) { "A strummed DRAWL recipe is a chord on one pad, not keys" }
            val keys = DrawlChords.range(patch.voice)
            require(lowMidi in keys && highMidi in lowMidi..keys.last)
            require(authoredMidi in lowMidi..highMidi) { "Keep the authored note inside the sampled range" }
            require(releaseSeconds.isFinite() && releaseSeconds in .01f..1.5f)
        }

        /** The note the saved recipe plays. */
        val authoredMidi: Int get() = Drawl.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f)

        /** The HOLD each key renders at. */
        val keyHold: Float get() = min(Drawl.settled(patch.voice, patch.macros).getValue("HOLD"), KEY_HOLD)
    }

    /** [take] indexes [TAKES]. */
    data class Cell(val midi: Int, val touch: Int, val take: Int)
    data class Note(val recipe: DrawlPatch, val snip: Snip)
    data class Assembly(val program: KeygroupProgram, val samples: Map<String, Snip>,
                        val recipes: Map<String, Pair<Cell, DrawlPatch>>)

    /** Minor-third roots, both requested edges, and the exact authored note. */
    fun roots(spec: Spec): List<Int> =
        ((spec.lowMidi..spec.highMidi step 3).toList() + spec.highMidi + spec.authoredMidi).distinct().sorted()

    /** Each cell's recipe: the saved one at the root's TUNE, the key HOLD, the touch and the take. */
    fun recipes(spec: Spec): Map<Cell, DrawlPatch> = linkedMapOf<Cell, DrawlPatch>().apply {
        for (midi in roots(spec)) for ((touch, velocity) in TOUCHES.withIndex()) for (take in TAKES.indices) {
            val macros = spec.patch.macros + ("TUNE" to DrawlChords.tuneFor(spec.patch.voice, midi)) + ("HOLD" to spec.keyHold)
            put(Cell(midi, touch, take), spec.patch.copy(macros = macros, velocity = velocity, take = TAKES[take]))
        }
    }

    fun stem(cell: Cell): String = "Drawl_${cell.midi}_T${cell.touch + 1}_R${cell.take + 1}"

    /** One sample: the engine's render of the cell's recipe, untouched. The sample player applies live key velocity once. */
    fun render(recipe: DrawlPatch): Snip = recipe.render()

    fun render(spec: Spec, cancelled: () -> Boolean = { false }): Map<Cell, Note> =
        recipes(spec).mapValues { (_, patch) ->
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("DRAWL keys cancelled")
            val snip = render(patch)
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("DRAWL keys cancelled")
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
        "DRAWL source must be finite mono 44.1kHz PCM inside full scale"
    }

    fun assemble(name: String, spec: Spec, notes: Map<Cell, Note>): Assembly {
        val expected = recipes(spec)
        require(notes.keys == expected.keys) { "Incomplete DRAWL note/touch/take grid" }
        val samples = linkedMapOf<String, Snip>()
        val source = linkedMapOf<String, Pair<Cell, DrawlPatch>>()
        for ((cell, recipe) in expected) {
            val note = notes.getValue(cell)
            require(note.recipe == recipe) { "DRAWL source recipe differs at $cell" }
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
        require(!Files.isSymbolicLink(destination.toPath()) && !destination.exists()) { "DRAWL package already exists" }
        val assembly = assemble(name, spec, notes)
        val parent = destination.absoluteFile.parentFile.apply { mkdirs() }
        val stage = Files.createTempDirectory(parent.toPath(), ".drawl-keygroups-").toFile()
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
                "keyHold" to JsonValue.Num(spec.keyHold.toString().toDouble()),
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

    private fun patchOf(value: JsonValue): DrawlPatch {
        val patch = DrawlPatch.fromJsonValue(value) as DrawlPatch
        require(value == patch.toJsonValue()) { "Unsupported DRAWL recipe fields cannot be discarded" }
        return patch
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
            "keyHold", "takes", "samples"))
        require(o.getValue("schema").int() == 1 && o.getValue("playback").str() == "FINITE")
        val rawRelease = o.getValue("releaseSeconds").num()
        require(rawRelease.isFinite() && rawRelease in .01..1.5) { "DRAWL release outside supported bounds" }
        val spec = Spec(patchOf(o.getValue("sourcePatch")), o.getValue("lowMidi").int(), o.getValue("highMidi").int(), rawRelease.toFloat())
        require(expectedSpec == null || expectedSpec == spec) { "Unexpected DRAWL source specification" }
        require(o.getValue("keyHold").num().toFloat() == spec.keyHold) { "DRAWL key HOLD changed" }
        require(o.getValue("takes").arr().map { it.int() } == TAKES) { "DRAWL key takes changed" }
        val expected = recipes(spec)
        val fileCells = expected.keys.associateBy { "${stem(it)}.wav" }
        val rows = o.getValue("samples").arr()
        require(rows.size == expected.size) { "Incomplete DRAWL source bindings" }
        val frames = linkedMapOf<Cell, Long>()
        for (row in rows) {
            val binding = row.obj()
            require(binding.keys == setOf("sample", "sha256", "take", "recipe"))
            val filename = binding.getValue("sample").str()
            val cell = fileCells[filename] ?: error("Unknown DRAWL sample binding: $filename")
            require(cell !in frames) { "Duplicate DRAWL sample binding" }
            require(binding.getValue("take").int() == TAKES[cell.take]) { "DRAWL take changed: $filename" }
            require(patchOf(binding.getValue("recipe")) == expected.getValue(cell)) { "DRAWL recipe changed: $filename" }
            val wav = File(data, filename)
            require(!Files.isSymbolicLink(wav.toPath()) && wav.isFile)
            require(hash(wav) == binding.getValue("sha256").str()) { "DRAWL PCM binding changed: $filename" }
            val sheet = requireNotNull(WavReader.readSmplStrict(wav))
            require(sheet.rootNote == cell.midi && sheet.loop == null) { "DRAWL keygroups are finite and rooted" }
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
        require(InstrumentStore.read(sidecar).toProgram() == program) { "DRAWL phone mapping changed" }
        require(sidecar.readText() == InstrumentStore.render(program, data.name)) { "Unsupported DRAWL phone descriptor fields" }
        require(native.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(program))) { "DRAWL native mapping changed" }
        require(legacy.readText() == KeygroupWriter().write(program)) { "DRAWL legacy mapping changed" }
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
