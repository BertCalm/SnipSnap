package com.snipsnap.shell

import com.snipsnap.audio.Scales
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
import com.snipsnap.synth.LatticeKeys
import com.snipsnap.synth.LatticePatch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.ceil

/** TRACE's authored arrival and continuous held collector; the player supplies note-off release. */
object LatticePadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    const val RECIPE_SUFFIX = ".lattice-keys.json"
    private const val RECIPE_VERSION = 1
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    data class Spec(val patch: LatticePatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) { "LATTICE keys require model-2 TRACE" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }

        /** Keyboard delivery has its own version; the original finite source patch stays replayable. */
        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(RECIPE_VERSION.toDouble()),
            "heldModel" to JsonValue.Num(LatticeKeys.MODEL_VERSION.toDouble()),
            "releaseSeconds" to JsonValue.Num(releaseSeconds.toDouble()),
            "patch" to patch.toJsonValue(),
        )))

        companion object {
            fun fromJsonText(text: String): Spec {
                val obj = Json.parse(text).obj()
                val version = obj["version"]?.int() ?: throw JsonException("LATTICE keys recipe has no version")
                if (version != RECIPE_VERSION) throw JsonException("unsupported LATTICE keys recipe version $version")
                val heldModel = obj["heldModel"]?.int() ?: throw JsonException("LATTICE keys recipe has no held model")
                if (heldModel != LatticeKeys.MODEL_VERSION) throw JsonException("unsupported LATTICE held model $heldModel")
                val patch = LatticePatch.fromJsonValue(obj["patch"] ?: throw JsonException("LATTICE keys recipe has no patch"))
                val release = obj["releaseSeconds"]?.num()?.toFloat() ?: throw JsonException("LATTICE keys recipe has no release")
                return Spec(patch, release)
            }
        }
    }

    fun supports(patch: LatticePatch): Boolean = LatticeKeys.supports(patch)
    fun spec(patch: LatticePatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)
    fun zoneMidis(spec: Spec): List<Int> = (LatticeKeys.MIN_MIDI..LatticeKeys.MAX_MIDI step 3).toList()

    /** Absolute roots override saved MIDI/TUNE. Remove capture gain once; keep its velocity-shaped spectrum. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "LATTICE zone root must be one of ${zoneMidis(spec)}" }
        return LatticeKeys.layer(spec.patch, midi, cancelled)
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name isn't MPC-safe or is longer than ${OneNote.MAX_NAME_LENGTH}: '$name'"
        }
        val roots = zoneMidis(spec)
        require(notes.size == roots.size) { "want ${roots.size} pitch zones, got ${notes.size}" }
        val samples = linkedMapOf<String, Snip>()
        val groups = roots.mapIndexed { index, midi ->
            val note = notes[index]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "LATTICE samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
            }
            require(note.loopStartFrame in 1 until note.snip.frameCount.toLong() - 1) {
                "LATTICE zone $midi must retain its attack and a sustain loop"
            }
            require(note.snip.samples.all { it.isFinite() && it in -1f..1f }) {
                "LATTICE zone $midi has non-finite or out-of-range audio"
            }
            val stem = Names.sanitizeStem("${name.filterNot(Char::isWhitespace)}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (index == 0) roots.first() else (roots[index - 1] + midi) / 2 + 1,
                highNote = if (index == roots.lastIndex) roots.last() else (midi + roots[index + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(), 1, 127, note.loopStartFrame)),
            )
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun recipeFile(destRoot: File, name: String): File = File(destRoot, "$name$RECIPE_SUFFIX")

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File,
               overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        val recipe = recipeFile(destRoot, name)
        if (recipe.exists() && !overwrite) throw DestinationExists(recipe)
        // Keep every delivery descriptor inside the pitch range measured by the held renderer.
        OneNote.writePackage(name, program, samples, destRoot, overwrite, spanKeyboard = false)
        AtomicFile.writeText(recipe, spec.toJsonText())
        return program
    }

    /** Hear the exported C4 layer at full key velocity, wrap once, then release through the normal player. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("LATTICE preview cancelled")
        }
        checkCancelled()
        val note = renderZone(spec, 60, cancelled)
        val rate = note.snip.sampleRate
        val zone = InstrumentStore.Zone(60, 60, 60, "preview.wav", note.snip.frameCount.toLong(), note.loopStartFrame)
        val instrument = InstrumentStore.Instrument("LATTICE PREVIEW", spec.releaseSeconds, listOf(zone))
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, listOf(note.snip.samples)), rate)
        val loopFrames = note.snip.frameCount - note.loopStartFrame.toInt()
        val heldFrames = maxOf(rate * 3, note.snip.frameCount + loopFrames)
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
