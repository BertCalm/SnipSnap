package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.LibrationKeys
import com.snipsnap.synth.LibrationPatch
import com.snipsnap.synth.LibrationTouchKeys
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ceil

/** A continuous orbital attack/sustain sample; phone and MPC apply the same key-up volume fade. */
object LibrationPadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    const val RECIPE_SUFFIX = ".libration-keys.json"
    private const val RECIPE_VERSION = 1
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    data class Spec(val patch: LibrationPatch, val releaseSeconds: Float = RELEASE.default,
                    val touchLayers: Boolean = false) {
        private val session = LibrationKeys.Session()
        val layerCount: Int get() = if (touchLayers) LibrationTouchKeys.TOUCHES.size else 1

        init {
            require(supports(patch)) { "LIBRATION keys require finite source model 3" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }

        internal fun note(midi: Int, cancelled: () -> Boolean) = session.note(patch, midi, cancelled)

        internal fun layers(midi: Int, cancelled: () -> Boolean): List<KeyNote> =
            if (touchLayers) LibrationTouchKeys.capture(patch, midi, session, cancelled).map { it.note }
            else listOf(note(midi, cancelled))

        fun toJsonText(): String {
            val fields = linkedMapOf<String, JsonValue>(
                "version" to JsonValue.Num(if (touchLayers) 2.0 else RECIPE_VERSION.toDouble()),
                "heldModel" to JsonValue.Num(LibrationKeys.MODEL_VERSION.toDouble()),
                "releaseSeconds" to JsonValue.Num(releaseSeconds.toDouble()),
                "patch" to patch.toJsonValue(),
            )
            if (touchLayers) {
                fields["touchModel"] = JsonValue.Num(LibrationTouchKeys.RENDER_VERSION.toDouble())
                fields["touchLayers"] = JsonValue.Bool(true)
            }
            return Json.write(JsonValue.Obj(fields))
        }

        companion object {
            fun fromJsonText(text: String): Spec {
                val obj = Json.parse(text).obj()
                val version = obj["version"]?.int()
                if (version != RECIPE_VERSION && version != 2) throw JsonException("unsupported LIBRATION keys recipe version")
                val touchLayers = if (version == 2) {
                    if (obj["touchLayers"]?.bool() != true || obj["touchModel"]?.int() != LibrationTouchKeys.RENDER_VERSION)
                        throw JsonException("unsupported LIBRATION touch capture model")
                    true
                } else {
                    if (obj.containsKey("touchLayers") || obj.containsKey("touchModel"))
                        throw JsonException("LIBRATION touch captures require recipe version 2")
                    false
                }
                if (obj["heldModel"]?.int() != LibrationKeys.MODEL_VERSION) throw JsonException("unsupported LIBRATION held model")
                val patch = LibrationPatch.fromJsonValue(obj["patch"] ?: throw JsonException("LIBRATION keys recipe has no patch")) as LibrationPatch
                val release = obj["releaseSeconds"]?.num() ?: throw JsonException("LIBRATION keys recipe has no release")
                // Validate decimal knob endpoints before narrowing a saved Double to Float.
                val range = RELEASE_MIN_SECONDS.toString().toDouble()..RELEASE_MAX_SECONDS.toString().toDouble()
                if (!release.isFinite() || release !in range)
                    throw JsonException("LIBRATION release is outside its supported range")
                return Spec(patch, release.toFloat(), touchLayers)
            }
        }
    }

    fun supports(patch: LibrationPatch): Boolean = LibrationKeys.supports(patch)
    fun spec(patch: LibrationPatch, releaseFraction: Float, touchLayers: Boolean = false): Spec = Spec(
        patch.copy(macros = patch.macros.toMap()),
        RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
        touchLayers,
    )
    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)
    fun zoneMidis(spec: Spec): List<Int> = LibrationKeys.zoneMidis(spec.patch.midi)

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(!spec.touchLayers) { "LIBRATION touch instruments need renderLayers" }
        require(midi in zoneMidis(spec)) { "LIBRATION zone $midi is outside this instrument" }
        return spec.note(midi, cancelled)
    }

    fun renderLayers(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "LIBRATION zone $midi is outside this instrument" }
        return spec.layers(midi, cancelled)
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(!spec.touchLayers) { "LIBRATION touch instruments need assembleLayers; one sample would discard soft touch" }
        return assembleLayers(name, spec, notes.map { listOf(it) })
    }

    fun assembleLayers(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) { "instrument name is not MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filterNot { it.isWhitespace() }
        val samples = linkedMapOf<String, Snip>()
        val groups = midis.mapIndexed { index, midi ->
            require(notes[index].size == spec.layerCount) { "LIBRATION zone $midi needs ${spec.layerCount} touch samples" }
            val layers = notes[index].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                    "LIBRATION keys must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
                }
                require(note.loopStartFrame in 1 until note.snip.frameCount.toLong() - 1) { "LIBRATION zone $midi needs an onset and sustain loop" }
                require(note.snip.samples.all { it.isFinite() }) { "LIBRATION zone $midi has nonfinite audio" }
                val suffix = if (spec.touchLayers) "_v${layerIndex + 1}" else ""
                val stem = Names.sanitizeStem("${prefix}_$midi$suffix")
                samples[stem] = note.snip
                val window = if (spec.touchLayers) LibrationTouchKeys.VELOCITY_WINDOWS[layerIndex] else 0..127
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(
                lowNote = if (index == 0) midi else (midis[index - 1] + midi) / 2 + 1,
                highNote = if (index == midis.lastIndex) midi else (midi + midis[index + 1]) / 2,
                rootNote = midi,
                layers = layers,
            )
        }
        // The sample already speaks from rest. A second program attack would soften it twice.
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds, explicitNoteOn = true) to samples
    }

    fun recipeFile(destRoot: File, name: String) = File(destRoot, "$name$RECIPE_SUFFIX")

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        return write(name, spec, program, samples, destRoot, overwrite)
    }

    fun exportLayers(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File,
                     overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assembleLayers(name, spec, notes)
        return write(name, spec, program, samples, destRoot, overwrite)
    }

    private fun write(name: String, spec: Spec, program: KeygroupProgram, samples: Map<String, Snip>,
                      destRoot: File, overwrite: Boolean): KeygroupProgram {
        val recipe = recipeFile(destRoot, name)
        if (recipe.exists() && !overwrite) throw DestinationExists(recipe)
        // The authored root span is deliberate; every descriptor must keep it.
        OneNote.writePackage(name, program, samples, destRoot, overwrite, spanKeyboard = false)
        AtomicFile.writeText(recipe, spec.toJsonText())
        return program
    }

    /** Play the actual source through its loop twice and the same note-off law as the exported instrument. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        fun stop() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("LIBRATION instrument preview cancelled")
        }
        stop()
        val midi = spec.patch.midi
        val notes = renderLayers(spec, midi, cancelled)
        val rate = notes.first().snip.sampleRate
        val layers = notes.mapIndexed { index, note ->
            val window = if (spec.touchLayers) LibrationTouchKeys.VELOCITY_WINDOWS[index] else 0..127
            InstrumentStore.Layer("preview$index.wav", note.snip.frameCount.toLong(), note.loopStartFrame,
                window.first, window.last)
        }
        val hard = layers.last()
        val zone = InstrumentStore.Zone(midi, midi, midi, hard.sample, hard.frameCount, hard.loopStartFrame, layers)
        val instrument = InstrumentStore.Instrument("LIBRATION PREVIEW", spec.releaseSeconds, listOf(zone))
        val samples = layers.mapIndexed { index, layer -> layer.sample to notes[index].snip.samples }.toMap()
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, samples), rate)
        val holds = notes.map { it.loopStartFrame.toInt() + (it.snip.frameCount - it.loopStartFrame.toInt()) * 2 }
        val releaseFrames = ceil(spec.releaseSeconds * rate.toDouble()).toInt()
        val out = FloatArray(holds.sum() + notes.size * (releaseFrames + rate / 20))
        val stereo = FloatArray(512 * 2)
        var position = 0
        fun append(framesToPlay: Int) {
            val end = position + framesToPlay
            while (position < end) {
                stop()
                val frames = minOf(512, end - position)
                player.render(stereo, frames)
                for (i in 0 until frames) {
                    check(abs(stereo[i * 2] - stereo[i * 2 + 1]) < 1e-6f) { "LIBRATION mono preview channel mismatch" }
                    out[position + i] = stereo[i * 2]
                }
                position += frames
            }
        }
        for ((index, layer) in layers.withIndex()) {
            // The midpoint selects the capture; live velocity supplies the player's ordinary gain.
            val velocity = if (spec.touchLayers) (layer.velStart + layer.velEnd) / 2 / 127f else 1f
            check(player.noteOn(midi, velocity))
            append(holds[index])
            player.noteOff(midi)
            append(releaseFrames + rate / 20)
        }
        stop()
        return Snip(out, 1, rate)
    }
}
