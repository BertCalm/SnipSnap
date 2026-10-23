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
import com.snipsnap.synth.OrigamiKeys
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.ceil

/** FAN/STILL attack and periodic sustain, with note-off release supplied by the keygroup player. */
object OrigamiPadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    const val RECIPE_SUFFIX = ".origami-keys.json"
    const val PREVIEW_HOLD_SECONDS = 3f
    private const val RECIPE_VERSION = 1
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    data class Spec(val patch: OrigamiPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) { "ORIGAMI keys require model-4 FAN or STILL; other recipes keep their finite sound" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }

        /** Keep the finite source recipe intact; the independently versioned held renderer adds no macros. */
        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(RECIPE_VERSION.toDouble()),
            "heldModel" to JsonValue.Num(OrigamiKeys.MODEL_VERSION.toDouble()),
            "releaseSeconds" to JsonValue.Num(releaseSeconds.toDouble()),
            "patch" to patch.toJsonValue(),
        )))

        companion object {
            fun fromJsonText(text: String): Spec {
                val obj = Json.parse(text).obj()
                val version = obj["version"]?.int() ?: throw JsonException("ORIGAMI keys recipe has no version")
                if (version != RECIPE_VERSION) throw JsonException("unsupported ORIGAMI keys recipe version $version")
                val heldModel = obj["heldModel"]?.int() ?: throw JsonException("ORIGAMI keys recipe has no held model")
                if (heldModel != OrigamiKeys.MODEL_VERSION) throw JsonException("unsupported ORIGAMI held model $heldModel")
                val patch = OrigamiPatch.fromJsonValue(obj["patch"] ?: throw JsonException("ORIGAMI keys recipe has no patch")) as OrigamiPatch
                val release = obj["releaseSeconds"]?.num()?.toFloat() ?: throw JsonException("ORIGAMI keys recipe has no release")
                return Spec(patch, release)
            }
        }
    }

    fun supports(patch: OrigamiPatch): Boolean = OrigamiKeys.supports(patch)
    fun spec(patch: OrigamiPatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)
    fun zoneMidis(spec: Spec): List<Int> = (48..72 step 3).toList()

    /** Absolute keyboard pitch overrides both saved MIDI and TUNE without altering the saved recipe. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "ORIGAMI zone root must be one of ${zoneMidis(spec)}" }
        return OrigamiKeys.note(spec.patch, midi, cancelled)
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} pitch zones, got ${notes.size}" }
        val prefix = name.filterNot { it.isWhitespace() }
        val samples = linkedMapOf<String, Snip>()
        val groups = midis.mapIndexed { i, midi ->
            val note = notes[i]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "ORIGAMI samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
            }
            require(note.loopStartFrame in 1 until note.snip.frameCount.toLong()) { "ORIGAMI zone $midi needs an attack and sustain loop" }
            val stem = Names.sanitizeStem("${prefix}_$midi")
            samples[stem] = note.snip
            Keygroup(maxOf(48, midi - 1), minOf(72, midi + 1), midi,
                listOf(VelocityLayer(stem, note.snip.frameCount.toLong(), 0, 127, note.loopStartFrame)))
        }
        // The authored attack is already in each sample; only note-off gets a program envelope.
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun recipeFile(destRoot: File, name: String): File = File(destRoot, "$name$RECIPE_SUFFIX")

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        val recipe = recipeFile(destRoot, name)
        if (recipe.exists() && !overwrite) throw DestinationExists(recipe)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        AtomicFile.writeText(recipe, spec.toJsonText())
        return program
    }

    /** Middle C held for three seconds, then released through the same playback map as KEYS. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("ORIGAMI preview cancelled")
        }
        checkCancelled()
        val note = renderZone(spec, 60, cancelled)
        val rate = note.snip.sampleRate
        val zone = InstrumentStore.Zone(60, 60, 60, "preview.wav", note.snip.frameCount.toLong(), note.loopStartFrame)
        val instrument = InstrumentStore.Instrument("ORIGAMI PREVIEW", spec.releaseSeconds, listOf(zone))
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, listOf(note.snip.samples)), rate)
        val heldFrames = (PREVIEW_HOLD_SECONDS * rate).toInt()
        val releaseFrames = ceil((spec.releaseSeconds * rate).toDouble()).toInt()
        val samples = FloatArray(heldFrames + releaseFrames + rate / 20)
        val stereo = FloatArray(512 * 2)
        check(player.noteOn(60))
        var position = 0
        while (position < samples.size) {
            checkCancelled()
            if (position == heldFrames) player.noteOff(60)
            val boundary = if (position < heldFrames) heldFrames else samples.size
            val frames = minOf(512, boundary - position)
            player.render(stereo, frames)
            for (i in 0 until frames) samples[position + i] = stereo[i * 2]
            position += frames
        }
        checkCancelled()
        return Snip(samples, 1, rate)
    }
}
