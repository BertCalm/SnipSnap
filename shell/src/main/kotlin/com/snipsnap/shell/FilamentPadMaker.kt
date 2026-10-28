package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.FilamentKeys
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * Model-3 THREAD, PULSE and CURL: three native roots, one velocity layer and a real onset before sustain.
 * The source patch retains its velocity-dependent timbre; the instrument host supplies velocity
 * gain and note-off release. Wider zoning and separate velocity samples remain a later round.
 */
object FilamentPadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    const val PATCH_FILE = "filament.patch.json"
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    data class Spec(val patch: FilamentPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) { "FILAMENT keys require a supported model-3 recipe" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun supports(patch: FilamentPatch): Boolean = FilamentKeys.supports(patch)

    fun spec(patch: FilamentPatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    /** C3, C4 and C5 are rendered at their native pitches; the saved root does not transpose them. */
    fun zoneMidis(spec: Spec): List<Int> = listOf(48, 60, 72)

    /** Rendering can be cancelled before the caller starts its non-cancellable package write. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "FILAMENT zone root must be one of ${zoneMidis(spec)}" }
        return FilamentKeys.layer(spec.patch, midi, cancelled)
    }

    /**
     * Tile C3–C5 at the root midpoints. One host-controlled layer spans the full MIDI velocity
     * range, so its captured source velocity selects timbre without applying dynamic gain twice.
     */
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
                "FILAMENT samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
            }
            require(note.loopStartFrame in 1 until note.snip.frameCount.toLong() - 1) {
                "FILAMENT zone $midi must retain its attack and a sustain loop"
            }
            require(note.snip.samples.all { it.isFinite() && it in -1f..1f }) {
                "FILAMENT zone $midi has non-finite or out-of-range audio"
            }
            val stem = Names.sanitizeStem("${name.filterNot(Char::isWhitespace)}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (index == 0) roots.first() else (roots[index - 1] + midi) / 2 + 1,
                highNote = if (index == roots.lastIndex) roots.last() else (midi + roots[index + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(
                    stem, note.snip.frameCount.toLong(), velStart = 1, velEnd = 127,
                    loopStartFrame = note.loopStartFrame,
                )),
            )
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds) to samples
    }

    /** The existing package door writes MPC 2, MPC 3, sampler markers and the phone's sidecar. */
    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        // Existing patch JSON keeps source controls and saved model replayable beside the WAVs.
        // Release remains a program property, already recorded in the instrument sidecar.
        val dataDir = File(destRoot, Mpc3TrackWriter.trackDataDirName(name))
        AtomicFile.writeText(File(dataDir, PATCH_FILE), spec.patch.toJsonText())
        return program
    }

    /** Standalone preview retains native velocity gain; exported layers let the host own it. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip =
        FilamentKeys.note(spec.patch, 60, cancelled).snip
}
