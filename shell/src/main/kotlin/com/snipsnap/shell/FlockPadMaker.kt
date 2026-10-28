package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.Names
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.FlockHeld
import com.snipsnap.synth.FlockTouchKeys
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * A saved held RIBBON as native-note sample zones. Synthesis and capture happen
 * off the playback thread; the existing phone and MPC players own note-off.
 * Each zone contains the authored arrival and one captured movement period.
 */
object FlockPadMaker {
    const val RELEASE_MIN_SECONDS = FlockHeld.MIN_RELEASE_SECONDS
    const val RELEASE_MAX_SECONDS = FlockHeld.MAX_RELEASE_SECONDS
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, FlockHeld.DEFAULT_RELEASE_SECONDS, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    fun supports(voice: FlockVoice): Boolean = voice == FlockVoice.RIBBON

    fun supports(patch: FlockPatch): Boolean = supports(patch.voice) && patch.playback == FlockPlayback.HELD

    /** Release is part of the saved performance, rather than transient MAKE state. */
    data class Spec(val patch: FlockPatch, val touchLayers: Boolean = false) {
        val layerCount: Int get() = if (touchLayers) FlockTouchKeys.TOUCHES.size else 1

        init {
            require(supports(patch)) { "FLOCK instruments require a held RIBBON patch" }
        }

        val releaseSeconds: Float get() = patch.releaseSeconds
    }

    fun spec(patch: FlockPatch, releaseFraction: Float, touchLayers: Boolean = false): Spec = Spec(patch.copy(
        playback = FlockPlayback.HELD,
        releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
    ), touchLayers)

    fun zoneMidis(spec: Spec): List<Int> = FlockHeld.midis(spec.patch)

    /** The saved seed, velocity, renderer model and audible macros reach every native note. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "FLOCK zone $midi is outside this instrument's roots" }
        val held = FlockHeld.render(spec.patch, midi, cancelled)
        require(held.loopEndExclusive == held.snip.frameCount) { "FLOCK sustain must loop to the sample's end" }
        return KeyNote(held.snip, held.loopStartFrame.toLong())
    }

    /** Soft-first source performances; the saved velocity remains the hard-touch anchor. */
    fun renderLayers(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "FLOCK zone $midi is outside this instrument's roots" }
        return if (spec.touchLayers) FlockTouchKeys.render(spec.patch, midi, cancelled)
        else listOf(renderZone(spec, midi, cancelled))
    }

    /** Legacy single-layer assembly refuses to silently discard a touch instrument's softer samples. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(!spec.touchLayers) { "FLOCK touch instruments need assembleLayers; a single sample would discard their softer layers" }
        return assembleLayers(name, spec, notes.map { listOf(it) })
    }

    /**
     * Midpoints limit transposition to one semitone. Each velocity layer retains
     * its own captured endpoint, so selecting a softer source never borrows the hard loop.
     */
    fun assembleLayers(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name must be MPC-safe and at most ${OneNote.MAX_NAME_LENGTH} characters: '$name'"
        }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            require(notes[i].size == spec.layerCount) {
                "FLOCK zone $midi needs ${spec.layerCount} velocity layers, got ${notes[i].size}"
            }
            val layers = notes[i].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                    "FLOCK zone $midi must be mono 44.1 kHz"
                }
                require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1) {
                    "FLOCK zone $midi has no captured sustain loop"
                }
                require(note.snip.samples.all { it.isFinite() }) { "FLOCK zone $midi has nonfinite audio" }
                val suffix = if (spec.touchLayers) "_v${layerIndex + 1}" else ""
                val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}$suffix")
                samples[stem] = note.snip
                val window = if (spec.touchLayers) FlockTouchKeys.VELOCITY_WINDOWS[layerIndex] else 0..127
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(
                lowNote = if (i == 0) midi else (midis[i - 1] + midi) / 2 + 1,
                highNote = if (i == midis.lastIndex) midi else (midi + midis[i + 1]) / 2,
                rootNote = midi,
                layers = layers,
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        return write(name, spec, program, samples, destRoot, overwrite)
    }

    fun exportLayers(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assembleLayers(name, spec, notes)
        return write(name, spec, program, samples, destRoot, overwrite)
    }

    private fun write(name: String, spec: Spec, program: KeygroupProgram, samples: Map<String, Snip>,
        destRoot: File, overwrite: Boolean): KeygroupProgram {
        val recipe = File(destRoot, "$name.patch.json")
        val deliveryRecipe = recipeFile(destRoot, name)
        if (!overwrite && (recipe.exists() || deliveryRecipe.exists()))
            throw DestinationExists.firstOf(recipe, deliveryRecipe)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        AtomicFile.writeText(recipe, spec.patch.toJsonText())
        if (spec.touchLayers) AtomicFile.writeText(deliveryRecipe, touchRecipe(name, spec))
        else if (deliveryRecipe.exists()) check(deliveryRecipe.delete()) { "could not remove stale FLOCK touch recipe" }
        return program
    }

    /** Source patches keep their historical shape; instrument delivery has its own versioned recipe. */
    fun recipeFile(destRoot: File, name: String): File = File(destRoot, "$name.instrument.recipe.json")

    private fun touchRecipe(name: String, spec: Spec): String = Json.write(JsonValue.Obj(linkedMapOf(
        "version" to JsonValue.Num(1.0),
        "sourcePatch" to JsonValue.Str("$name.patch.json"),
        "touchLayers" to JsonValue.Bool(true),
        "touchVersion" to JsonValue.Num(FlockTouchKeys.RENDER_VERSION.toDouble()),
        "strengths" to JsonValue.Arr(FlockTouchKeys.TOUCHES.map { JsonValue.Num(it.toDouble()) }),
        "velocityWindows" to JsonValue.Arr(FlockTouchKeys.VELOCITY_WINDOWS.map { window ->
            JsonValue.Obj(linkedMapOf(
                "start" to JsonValue.Num(window.first.toDouble()),
                "end" to JsonValue.Num(window.last.toDouble()),
            ))
        }),
        "replayFlags" to JsonValue.Arr(listOf("--patch", "$name.patch.json", "--instrument", "--touch",
            "--release", spec.releaseSeconds.toString()).map { JsonValue.Str(it) }),
    )))

    /** The saved note's arrival and two loop passes, suitable for a finite shelf preview. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        if (spec.touchLayers) return touchPreview(spec, cancelled)
        val note = renderZone(spec, spec.patch.midi, cancelled)
        val samples = note.snip.samples
        val loopStart = note.loopStartFrame.toInt()
        val loopFrames = samples.size - loopStart
        val out = samples.copyOf(samples.size + loopFrames)
        samples.copyInto(out, samples.size, loopStart)
        return Snip(out, channels = 1, sampleRate = note.snip.sampleRate)
    }

    private fun touchPreview(spec: Spec, cancelled: () -> Boolean): Snip {
        val notes = renderLayers(spec, spec.patch.midi, cancelled)
        val layers = notes.mapIndexed { index, note ->
            val window = FlockTouchKeys.VELOCITY_WINDOWS[index]
            InstrumentStore.Layer("touch${index + 1}", note.snip.frameCount.toLong(), note.loopStartFrame,
                window.first, window.last)
        }
        val hard = layers.last()
        val instrument = InstrumentStore.Instrument(spec.patch.name, spec.releaseSeconds,
            listOf(InstrumentStore.Zone(spec.patch.midi, spec.patch.midi, spec.patch.midi,
                hard.sample, hard.frameCount, hard.loopStartFrame, layers)))
        val samples = layers.mapIndexed { index, layer -> layer.sample to notes[index].snip.samples }.toMap()
        val rate = WavWriter.MPC_SAMPLE_RATE
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, samples), rate)
        val heldFrames = (rate * .9f).roundToInt()
        val tailFrames = (rate * (spec.releaseSeconds + .12f)).roundToInt()
        val totalFrames = (heldFrames + tailFrames) * layers.size
        val out = FloatArray(totalFrames * 2)
        var frame = 0
        fun append(count: Int) {
            var left = count
            while (left > 0) {
                if (cancelled() || Thread.currentThread().isInterrupted)
                    throw CancellationException("FLOCK touch preview cancelled")
                val size = minOf(left, 512)
                val block = FloatArray(size * 2)
                player.render(block, size)
                block.copyInto(out, frame * 2)
                frame += size
                left -= size
            }
        }
        for (window in FlockTouchKeys.VELOCITY_WINDOWS) {
            // Source strengths are not MIDI representatives: .35 already selects
            // the middle window. Each press uses its exported window's midpoint.
            val touch = ((window.first + window.last) / 2) / 127f
            check(player.noteOn(spec.patch.midi, touch))
            append(heldFrames)
            player.noteOff(spec.patch.midi)
            append(tailFrames)
        }
        return Snip(out, channels = 2, sampleRate = rate)
    }
}
