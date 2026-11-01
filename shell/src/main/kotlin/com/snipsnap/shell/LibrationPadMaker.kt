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

    data class Spec(val patch: LibrationPatch, val releaseSeconds: Float = RELEASE.default) {
        private val session = LibrationKeys.Session()

        init {
            require(supports(patch)) { "LIBRATION keys require finite source model 3" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }

        internal fun note(midi: Int, cancelled: () -> Boolean) = session.note(patch, midi, cancelled)

        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(RECIPE_VERSION.toDouble()),
            "heldModel" to JsonValue.Num(LibrationKeys.MODEL_VERSION.toDouble()),
            "releaseSeconds" to JsonValue.Num(releaseSeconds.toDouble()),
            "patch" to patch.toJsonValue(),
        )))

        companion object {
            fun fromJsonText(text: String): Spec {
                val obj = Json.parse(text).obj()
                if (obj["version"]?.int() != RECIPE_VERSION) throw JsonException("unsupported LIBRATION keys recipe version")
                if (obj["heldModel"]?.int() != LibrationKeys.MODEL_VERSION) throw JsonException("unsupported LIBRATION held model")
                val patch = LibrationPatch.fromJsonValue(obj["patch"] ?: throw JsonException("LIBRATION keys recipe has no patch")) as LibrationPatch
                val release = obj["releaseSeconds"]?.num() ?: throw JsonException("LIBRATION keys recipe has no release")
                // Validate decimal knob endpoints before narrowing a saved Double to Float.
                val range = RELEASE_MIN_SECONDS.toString().toDouble()..RELEASE_MAX_SECONDS.toString().toDouble()
                if (!release.isFinite() || release !in range)
                    throw JsonException("LIBRATION release is outside its supported range")
                return Spec(patch, release.toFloat())
            }
        }
    }

    fun supports(patch: LibrationPatch): Boolean = LibrationKeys.supports(patch)
    fun spec(patch: LibrationPatch, releaseFraction: Float): Spec = Spec(
        patch.copy(macros = patch.macros.toMap()),
        RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
    )
    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)
    fun zoneMidis(spec: Spec): List<Int> = LibrationKeys.zoneMidis(spec.patch.midi)

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "LIBRATION zone $midi is outside this instrument" }
        return spec.note(midi, cancelled)
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) { "instrument name is not MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filterNot { it.isWhitespace() }
        val samples = linkedMapOf<String, Snip>()
        val groups = midis.mapIndexed { index, midi ->
            val note = notes[index]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "LIBRATION keys must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
            }
            require(note.loopStartFrame in 1 until note.snip.frameCount.toLong() - 1) { "LIBRATION zone $midi needs an onset and sustain loop" }
            require(note.snip.samples.all { it.isFinite() }) { "LIBRATION zone $midi has nonfinite audio" }
            val stem = Names.sanitizeStem("${prefix}_$midi")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (index == 0) midi else (midis[index - 1] + midi) / 2 + 1,
                highNote = if (index == midis.lastIndex) midi else (midi + midis[index + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(), 0, 127, note.loopStartFrame)),
            )
        }
        // The sample already speaks from rest. A second program attack would soften it twice.
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun recipeFile(destRoot: File, name: String) = File(destRoot, "$name$RECIPE_SUFFIX")

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        val recipe = recipeFile(destRoot, name)
        if (recipe.exists() && !overwrite) throw DestinationExists(recipe)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
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
        val note = renderZone(spec, midi, cancelled)
        val rate = note.snip.sampleRate
        val zone = InstrumentStore.Zone(midi, midi, midi, "preview.wav", note.snip.frameCount.toLong(), note.loopStartFrame)
        val instrument = InstrumentStore.Instrument("LIBRATION PREVIEW", spec.releaseSeconds, listOf(zone))
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, listOf(note.snip.samples)), rate)
        val period = note.snip.frameCount - note.loopStartFrame.toInt()
        val heldFrames = note.loopStartFrame.toInt() + period * 2
        val releaseFrames = ceil(spec.releaseSeconds * rate.toDouble()).toInt()
        val out = FloatArray(heldFrames + releaseFrames + rate / 20)
        val stereo = FloatArray(512 * 2)
        check(player.noteOn(midi))
        var position = 0
        while (position < out.size) {
            stop()
            if (position == heldFrames) player.noteOff(midi)
            val boundary = if (position < heldFrames) heldFrames else out.size
            val frames = minOf(512, boundary - position)
            player.render(stereo, frames)
            for (i in 0 until frames) {
                check(abs(stereo[i * 2] - stereo[i * 2 + 1]) < 1e-6f) { "LIBRATION mono preview channel mismatch" }
                out[position + i] = stereo[i * 2]
            }
            position += frames
        }
        stop()
        return Snip(out, 1, rate)
    }
}
