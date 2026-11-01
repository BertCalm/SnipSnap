package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.Names
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.FilamentKeys
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentTouchKeys
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Model-3 THREAD, PULSE and CURL: native octave roots and a real onset before sustain.
 * The source patch retains its velocity-dependent timbre; the instrument host supplies velocity
 * gain and note-off release. Optional touch layers capture three real source strengths.
 */
object FilamentPadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    const val PATCH_FILE = "filament.patch.json"
    const val TOUCH_FILE = "filament.instrument.recipe.json"
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    data class Spec(val patch: FilamentPatch, val releaseSeconds: Float = RELEASE.default,
                    val touchLayers: Boolean = false, val wideKeys: Boolean = false) {
        val layerCount: Int get() = if (touchLayers) FilamentTouchKeys.TOUCHES.size else 1
        val rangeLabel: String get() = if (wideKeys) "C2–C6" else "C3–C5"
        init {
            require(supports(patch)) { "FILAMENT keys require a supported model-3 recipe" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun supports(patch: FilamentPatch): Boolean = FilamentKeys.supports(patch)

    fun spec(patch: FilamentPatch, releaseFraction: Float, touchLayers: Boolean = false,
             wideKeys: Boolean = false): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS), touchLayers, wideKeys)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    /** Native roots preserve the middle captures; wider coverage adds C2 and C6. */
    fun zoneMidis(spec: Spec): List<Int> = if (spec.wideKeys) listOf(36, 48, 60, 72, 84) else listOf(48, 60, 72)

    /** Rendering can be cancelled before the caller starts its non-cancellable package write. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "FILAMENT zone root must be one of ${zoneMidis(spec)}" }
        return FilamentKeys.layer(spec.patch, midi, cancelled)
    }

    /** Soft-first complete captures; each layer carries its own loop and source articulation. */
    fun renderLayers(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "FILAMENT zone root must be one of ${zoneMidis(spec)}" }
        return if (spec.touchLayers) FilamentTouchKeys.render(spec.patch, midi, cancelled)
        else listOf(renderZone(spec, midi, cancelled))
    }

    /**
     * Tile the selected range at root midpoints. One host-controlled layer spans the full MIDI velocity
     * range, so its captured source velocity selects timbre without applying dynamic gain twice.
     */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(!spec.touchLayers) { "FILAMENT touch instruments need assembleLayers; a single sample would discard softer layers" }
        return assembleLayers(name, spec, notes.map { listOf(it) })
    }

    fun assembleLayers(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name isn't MPC-safe or is longer than ${OneNote.MAX_NAME_LENGTH}: '$name'"
        }
        val roots = zoneMidis(spec)
        require(notes.size == roots.size) { "want ${roots.size} pitch zones, got ${notes.size}" }
        val samples = linkedMapOf<String, Snip>()
        val groups = roots.mapIndexed { index, midi ->
            require(notes[index].size == spec.layerCount) {
                "FILAMENT zone $midi needs ${spec.layerCount} velocity layers, got ${notes[index].size}"
            }
            val layers = notes[index].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                    "FILAMENT samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
                }
                require(note.loopStartFrame in 1 until note.snip.frameCount.toLong() - 1) {
                    "FILAMENT zone $midi must retain its attack and a sustain loop"
                }
                require(note.snip.samples.all { it.isFinite() && it in -1f..1f }) {
                    "FILAMENT zone $midi has non-finite or out-of-range audio"
                }
                val suffix = if (spec.touchLayers) "_v${layerIndex + 1}" else ""
                val stem = Names.sanitizeStem("${name.filterNot(Char::isWhitespace)}_${Scales.nameOf(midi)}$suffix")
                samples[stem] = note.snip
                val window = if (spec.touchLayers) FilamentTouchKeys.VELOCITY_WINDOWS[layerIndex] else 1..127
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(
                lowNote = if (index == 0) roots.first() else (roots[index - 1] + midi) / 2 + 1,
                highNote = if (index == roots.lastIndex) roots.last() else (midi + roots[index + 1]) / 2,
                rootNote = midi,
                layers = layers,
            )
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds) to samples
    }

    /** The existing package door writes MPC 2, MPC 3, sampler markers and the phone's sidecar. */
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
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        // Existing patch JSON keeps source controls and saved model replayable beside the WAVs.
        // Release remains a program property, already recorded in the instrument sidecar.
        val dataDir = File(destRoot, Mpc3TrackWriter.trackDataDirName(name))
        AtomicFile.writeText(File(dataDir, PATCH_FILE), spec.patch.toJsonText())
        if (spec.touchLayers || spec.wideKeys) AtomicFile.writeText(File(dataDir, TOUCH_FILE), instrumentRecipe(spec, program))
        return program
    }

    /** Capture settings are delivery metadata, separate from the unchanged source-patch schema. */
    private fun instrumentRecipe(spec: Spec, program: KeygroupProgram): String {
        val strengths = if (spec.touchLayers) FilamentTouchKeys.TOUCHES else listOf(1f)
        val windows = if (spec.touchLayers) FilamentTouchKeys.VELOCITY_WINDOWS else listOf(1..127)
        val fields = linkedMapOf(
            "version" to JsonValue.Num(if (spec.wideKeys) 2.0 else 1.0),
            "sourcePatch" to JsonValue.Str(PATCH_FILE),
            "renderer" to JsonValue.Str("FilamentKeys.layer"),
            "touchVersion" to JsonValue.Num(FilamentTouchKeys.RENDER_VERSION.toDouble()),
            "touchLayers" to JsonValue.Bool(spec.touchLayers),
            "releaseSeconds" to JsonValue.Num(spec.releaseSeconds.toDouble()),
            "strengths" to JsonValue.Arr(strengths.map { JsonValue.Num(it.toDouble()) }),
            "excitationVelocities" to JsonValue.Arr(strengths.map { JsonValue.Num((spec.patch.velocity * it).toDouble()) }),
            "sourcesByRoot" to JsonValue.Arr(zoneMidis(spec).map { midi ->
                JsonValue.Obj(linkedMapOf(
                    "midi" to JsonValue.Num(midi.toDouble()),
                    "layers" to JsonValue.Arr(strengths.map { touch ->
                        (if (spec.touchLayers) FilamentTouchKeys.sourcePatch(spec.patch, midi, touch)
                        else spec.patch.copy(midi = midi, macros = spec.patch.macros + ("HOLD" to 1f))).toJsonValue()
                    }),
                ))
            }),
            "velocityWindows" to JsonValue.Arr(windows.map {
                JsonValue.Obj(linkedMapOf("start" to JsonValue.Num(it.first.toDouble()), "end" to JsonValue.Num(it.last.toDouble())))
            }),
            "gainRule" to JsonValue.Str("Remove each capture's velocity^.72 once; the host supplies note-velocity gain. No loudness matching."),
        )
        // Version 1 compact touch recipes retain their existing shape. Wide recipes record
        // the actual program zones, avoiding a second copy of the midpoint zoning rule.
        if (spec.wideKeys) fields["zones"] = JsonValue.Arr(program.keygroups.map { zone ->
            JsonValue.Obj(linkedMapOf("rootMidi" to JsonValue.Num(zone.rootNote.toDouble()),
                "lowMidi" to JsonValue.Num(zone.lowNote.toDouble()),
                "highMidi" to JsonValue.Num(zone.highNote.toDouble())))
        })
        return Json.write(JsonValue.Obj(fields))
    }

    /** Standalone preview retains native velocity gain; exported layers let the host own it. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        if (!spec.touchLayers) return FilamentKeys.note(spec.patch, 60, cancelled).snip
        val notes = renderLayers(spec, 60, cancelled)
        val layers = notes.mapIndexed { index, note ->
            val window = FilamentTouchKeys.VELOCITY_WINDOWS[index]
            InstrumentStore.Layer("touch${index + 1}", note.snip.frameCount.toLong(), note.loopStartFrame,
                window.first, window.last)
        }
        val hard = layers.last()
        val instrument = InstrumentStore.Instrument(spec.patch.name, spec.releaseSeconds,
            listOf(InstrumentStore.Zone(60, 60, 60, hard.sample, hard.frameCount, hard.loopStartFrame, layers)))
        val samples = layers.mapIndexed { index, layer -> layer.sample to notes[index].snip.samples }.toMap()
        val rate = WavWriter.MPC_SAMPLE_RATE
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, samples), rate)
        val heldFrames = (rate * 1.2).toInt()
        val tailFrames = (rate * (spec.releaseSeconds + .12f)).toInt()
        val out = FloatArray((heldFrames + tailFrames) * layers.size * 2)
        val block = FloatArray(512 * 2)
        var at = 0
        fun append(frames: Int) {
            var done = 0
            while (done < frames) {
                if (cancelled() || Thread.currentThread().isInterrupted)
                    throw CancellationException("FILAMENT touch preview cancelled")
                val count = minOf(512, frames - done)
                player.render(block, count)
                block.copyInto(out, at * 2, 0, count * 2)
                at += count
                done += count
            }
        }
        for (window in FilamentTouchKeys.VELOCITY_WINDOWS) {
            // Capture strengths are timbral inputs; use the MIDI window midpoint to select each sample.
            check(player.noteOn(60, ((window.first + window.last) / 2) / 127f))
            append(heldFrames)
            player.noteOff(60)
            append(tailFrames)
        }
        return Snip(out, channels = 2, sampleRate = rate)
    }
}
