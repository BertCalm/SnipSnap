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
import com.snipsnap.synth.OrigamiExpression
import com.snipsnap.synth.OrigamiExpressions
import com.snipsnap.synth.OrigamiKeys
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.Velocity
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
    val RELATIVE_VELOCITIES = OrigamiExpressions.LAYER_VELOCITIES
    val VELOCITY_WINDOWS = listOf(1..42, 43..84, 85..127)
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    data class Spec(val patch: OrigamiPatch, val releaseSeconds: Float = RELEASE.default) {
        val layerCount: Int get() = if (patch.expression == OrigamiExpression.FOLD) RELATIVE_VELOCITIES.size else 1

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

    /**
     * The saved velocity is the hard-touch anchor. Softer touches change the
     * held folded share; peak matching leaves key loudness to the player.
     * Render the anchor first, but keep the exported layers in soft-to-hard order.
     */
    fun renderVelocityZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        if (spec.layerCount == 1) return listOf(renderZone(spec, midi, cancelled))
        checkCancelled(cancelled)
        val hard = renderZone(spec, midi, cancelled)
        val softer = RELATIVE_VELOCITIES.dropLast(1).map { relative ->
            checkCancelled(cancelled)
            val patch = spec.patch.copy(velocity = OrigamiExpressions.relativeVelocity(spec.patch, relative))
            val note = OrigamiKeys.note(patch, midi, cancelled)
            checkCancelled(cancelled)
            note.copy(snip = Velocity.peakMatch(hard.snip, note.snip))
        }
        checkCancelled(cancelled)
        return softer + hard
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(spec.layerCount == 1) { "ORIGAMI FOLD expression needs assembleLayers; a single sample would discard its softer layers" }
        return assembleLayers(name, spec, notes.map { listOf(it) })
    }

    /** Each pitch zone retains the length and loop of every independently rendered touch. */
    fun assembleLayers(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} pitch zones, got ${notes.size}" }
        val prefix = name.filterNot { it.isWhitespace() }
        val samples = linkedMapOf<String, Snip>()
        val groups = midis.mapIndexed { i, midi ->
            require(notes[i].size == spec.layerCount) {
                "ORIGAMI zone $midi needs ${spec.layerCount} velocity layers, got ${notes[i].size}"
            }
            val layers = notes[i].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                    "ORIGAMI samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
                }
                require(note.loopStartFrame in 1 until note.snip.frameCount.toLong()) { "ORIGAMI zone $midi needs an attack and sustain loop" }
                val suffix = if (spec.layerCount == 1) "" else "_v${layerIndex + 1}"
                val stem = Names.sanitizeStem("${prefix}_$midi$suffix")
                samples[stem] = note.snip
                val window = if (spec.layerCount == 1) 0..127 else VELOCITY_WINDOWS[layerIndex]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(maxOf(48, midi - 1), minOf(72, midi + 1), midi, layers)
        }
        // The authored attack is already in each sample; only note-off gets a program envelope.
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun recipeFile(destRoot: File, name: String): File = File(destRoot, "$name$RECIPE_SUFFIX")

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        require(spec.layerCount == 1) { "ORIGAMI FOLD expression needs exportLayers; a single sample would discard its softer layers" }
        return exportLayers(name, spec, notes.map { listOf(it) }, destRoot, overwrite)
    }

    fun exportLayers(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assembleLayers(name, spec, notes)
        val recipe = recipeFile(destRoot, name)
        if (recipe.exists() && !overwrite) throw DestinationExists(recipe)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        AtomicFile.writeText(recipe, spec.toJsonText())
        return program
    }

    /** Middle C held for three seconds, then released through the same playback map as KEYS. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        if (spec.layerCount > 1) return expressionPreview(spec, cancelled)
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

    /** Audition the exported layer selection and the player's gain, not just three raw renders. */
    private fun expressionPreview(spec: Spec, cancelled: () -> Boolean): Snip {
        checkCancelled(cancelled)
        val notes = renderVelocityZone(spec, 60, cancelled)
        val rate = notes.last().snip.sampleRate
        val layers = notes.mapIndexed { index, note ->
            val window = VELOCITY_WINDOWS[index]
            InstrumentStore.Layer("preview_v${index + 1}.wav", note.snip.frameCount.toLong(), note.loopStartFrame,
                window.first, window.last)
        }
        val hard = layers.last()
        val zone = InstrumentStore.Zone(60, 60, 60, hard.sample, hard.frameCount, hard.loopStartFrame, layers)
        val instrument = InstrumentStore.Instrument("ORIGAMI PREVIEW", spec.releaseSeconds, listOf(zone))
        val samplesByFile = layers.mapIndexed { index, layer -> layer.sample to notes[index].snip.samples }.toMap()
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, samplesByFile), rate)
        val heldFrames = (PREVIEW_HOLD_SECONDS * rate).toInt()
        val releaseFrames = ceil((spec.releaseSeconds * rate).toDouble()).toInt()
        val performanceFrames = heldFrames + releaseFrames + rate / 20
        val samples = FloatArray(performanceFrames * layers.size)
        val stereo = FloatArray(512 * 2)
        for ((index, touch) in RELATIVE_VELOCITIES.withIndex()) {
            checkCancelled(cancelled)
            check(player.noteOn(60, touch))
            var position = 0
            while (position < performanceFrames) {
                checkCancelled(cancelled)
                if (position == heldFrames) player.noteOff(60)
                val boundary = if (position < heldFrames) heldFrames else performanceFrames
                val frames = minOf(512, boundary - position)
                player.render(stereo, frames)
                for (i in 0 until frames) samples[index * performanceFrames + position + i] = stereo[i * 2]
                position += frames
            }
        }
        checkCancelled(cancelled)
        return Snip(samples, 1, rate)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("ORIGAMI expression cancelled")
    }
}
