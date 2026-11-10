package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.Halobar
import com.snipsnap.synth.HalobarPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Restrike
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException

/** Finite mallet keygroups: two native touch levels, three fresh landings per level. */
object HalobarPadMaker {
    const val TAKES = 3
    const val LAYERS = 6
    const val RECIPE_SUFFIX = ".halobar-keys.json"
    val RELEASE = Knob("RELEASE", .1f, 1.5f, .6f, exponential = true)
    val TOUCHES = listOf(.42f, 1f)
    val WINDOWS = listOf(0..79, 80..127)

    data class Spec(val patch: HalobarPatch, val releaseSeconds: Float = RELEASE.default, val seed: Int = 0) {
        init { require(releaseSeconds.isFinite() && releaseSeconds in .1f..1.5f) }
        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(1.0), "patch" to patch.toJsonValue(),
            "releaseSeconds" to JsonValue.Num(releaseSeconds.toDouble()), "seed" to JsonValue.Num(seed.toDouble()),
        )))
        companion object {
            fun fromJsonText(text: String): Spec {
                val o = Json.parse(text).obj()
                require(o.getValue("version").int() == 1) { "unsupported HALOBAR keys recipe" }
                return Spec(HalobarPatch.fromJsonValue(o.getValue("patch")),
                    o.getValue("releaseSeconds").num().toFloat(), o.getValue("seed").int())
            }
        }
    }
    fun spec(patch: HalobarPatch, releaseFraction: Float) = Spec(patch, RELEASE.value(releaseFraction))
    fun secondsLabel(seconds: Float) = "%.2f s".format(java.util.Locale.ROOT, seconds)
    fun zoneMidis(spec: Spec): List<Int> {
        val voice = spec.patch.voice
        return ((Halobar.minimumMidi(voice)..Halobar.maximumMidi(voice) step 3).toList() +
            Halobar.rootMidi(voice) + Halobar.maximumMidi(voice)).distinct().sorted()
    }
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "HALOBAR zone outside its authored register" }
        val patch = spec.patch.copy(macros = spec.patch.macros + ("TUNE" to Halobar.tuneForMidi(spec.patch.voice, midi)))
        return TOUCHES.flatMapIndexed { zone, velocity -> (0 until TAKES).map { take ->
            checkCancelled(cancelled)
            val snip = requireNotNull(Restrike.take(patch, velocity, spec.seed + midi * 31 + zone, take))
            // The house render levels each source; preserve a real soft/hard dynamic range in the bank.
            val gain = if (zone == 0) .6f else 1f
            KeyNote(Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, 1, Halobar.RATE))
        } }
    }
    fun assemble(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH)
        val midis = zoneMidis(spec)
        require(notes.size == midis.size)
        val samples = linkedMapOf<String, Snip>()
        val groups = midis.mapIndexed { index, midi ->
            require(notes[index].size == LAYERS)
            val layers = notes[index].mapIndexed { layer, note ->
                require(note.loopStartFrame == 0L) { "HALOBAR is a finite mallet, not a sustain loop" }
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE)
                require(note.snip.frameCount > 1 && note.snip.samples.all { it.isFinite() && it in -1f..1f })
                val stem = Names.sanitizeStem("${name.replace(" ", "")}_${midi}_v${layer / TAKES + 1}_r${layer % TAKES + 1}")
                samples[stem] = note.snip
                val window = WINDOWS[layer / TAKES]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last)
            }
            Keygroup(if (index == 0) midi else (midis[index - 1] + midi) / 2 + 1,
                if (index == midis.lastIndex) midi else (midi + midis[index + 1]) / 2,
                midi, layers, cycleLayers = true)
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds, explicitNoteOn = true) to samples
    }
    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File,
               overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        val recipe = File(destRoot, "$name$RECIPE_SUFFIX")
        if (recipe.exists() && !overwrite) throw com.snipsnap.kit.DestinationExists(recipe)
        OneNote.writePackage(name, program, samples, destRoot, overwrite, spanKeyboard = false)
        AtomicFile.writeText(recipe, spec.toJsonText())
        return program
    }
    /** A short key audition plays all six root captures with real note-off between them. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midi = Halobar.midiFor(spec.patch.voice, spec.patch.macros["TUNE"] ?: .5f)
        val root = zoneMidis(spec).minBy { kotlin.math.abs(it - midi) }
        val captures = renderZone(spec, root, cancelled)
        val layers = captures.mapIndexed { i, n -> InstrumentStore.Layer("r$i.wav", n.snip.frameCount.toLong(), 0,
            WINDOWS[i / TAKES].first, WINDOWS[i / TAKES].last) }
        val last = layers.last()
        val instrument = InstrumentStore.Instrument(spec.patch.name, spec.releaseSeconds,
            listOf(InstrumentStore.Zone(root, root, root, last.sample, last.frameCount, 0, layers, cycleLayers = true)))
        val engine = InstrumentEngine(InstrumentEngine.Loaded(instrument, Halobar.RATE,
            captures.mapIndexed { i, n -> "r$i.wav" to n.snip.samples }.toMap()), Halobar.RATE)
        val hold = Halobar.RATE
        val release = ((spec.releaseSeconds + .05f) * Halobar.RATE).toInt()
        val out = FloatArray((hold + release) * LAYERS * 2)
        var at = 0
        for (velocity in TOUCHES) repeat(TAKES) {
            checkCancelled(cancelled)
            check(engine.noteOn(root, velocity))
            val a = FloatArray(hold * 2); engine.render(a, hold); a.copyInto(out, at); at += a.size
            engine.noteOff(root)
            val b = FloatArray(release * 2); engine.render(b, release); b.copyInto(out, at); at += b.size
        }
        return Snip(out, 2, Halobar.RATE)
    }
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("HALOBAR keys cancelled")
    }
}
