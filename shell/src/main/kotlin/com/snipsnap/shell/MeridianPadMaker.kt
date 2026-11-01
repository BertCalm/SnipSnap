package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianKits
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException

/**
 * A model-2 fixed-alignment swing as the phone/MPC's supported keygroup shape.
 * Native roots cover C2-C5 at minor thirds, with three physical touch layers.
 * Neighboring keys transpose at most one semitone, including that small change
 * to swing timing. Legacy finite recipes are never upgraded by MAKE INSTRUMENT.
 */
object MeridianPadMaker {
    const val MIDI_MIN = 36
    const val MIDI_MAX = 72
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS,
        MeridianKits.HELD_RELEASE_SECONDS, exponential = true)
    val VELOCITIES = listOf(.30f, .65f, 1f)
    val VELOCITY_WINDOWS = listOf(0..42, 43..84, 85..127)

    data class Spec(val patch: MeridianPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) { "MERIDIAN instruments need a current fixed-alignment HOLD sound" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun supports(patch: MeridianPatch): Boolean = patch.model == 2 && MeridianSynth.isHeld(patch)
    fun spec(patch: MeridianPatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))
    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)
    fun zoneMidis(@Suppress("UNUSED_PARAMETER") spec: Spec): List<Int> = (MIDI_MIN..MIDI_MAX step 3).toList()

    /** Zone MIDI is absolute. A saved TUNE offset must not transpose it again. */
    fun melodyPatch(patch: MeridianPatch): MeridianPatch = patch.copy(macros = patch.macros + ("TUNE" to .5f))

    /**
     * Callback cancellation is checked between native layers. The caller uses
     * runInterruptible for in-flight cancellation: renderHeld checks the thread
     * flag inside its DSP passes and has no separate cancellation callback.
     */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "MERIDIAN zone root must be one of ${zoneMidis(spec)}" }
        val patch = melodyPatch(spec.patch)
        return VELOCITIES.map { velocity -> renderNote(patch, midi, velocity, cancelled) }
    }

    private fun renderNote(patch: MeridianPatch, midi: Int, velocity: Float, cancelled: () -> Boolean): KeyNote {
        checkCancelled(cancelled)
        val held = Meridian.renderHeld(patch.voice, patch.macros, midi, velocity, patch.seedContext)
        checkCancelled(cancelled)
        require(held.loopEndFrameExclusive == held.snip.frameCount && held.loopStartFrame >= 256)
        require(held.seamError.isFinite() && held.seamError < Keys.MAX_SEAM_ERROR)
        return KeyNote(held.snip, held.loopStartFrame.toLong())
    }

    fun assemble(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} pitch zones, got ${notes.size}" }
        val samples = linkedMapOf<String, Snip>()
        val groups = midis.mapIndexed { zoneIndex, midi ->
            require(notes[zoneIndex].size == VELOCITIES.size) { "MERIDIAN zone $midi needs three velocity layers" }
            val layers = notes[zoneIndex].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                    "MERIDIAN samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
                }
                require(note.snip.samples.all { it.isFinite() }) { "MERIDIAN zone $midi has nonfinite samples" }
                require(note.loopStartFrame in 256 until note.snip.frameCount.toLong()) { "MERIDIAN zone $midi needs its real preceding cycle" }
                val seam = Keys.seamError(note.snip.samples, note.loopStartFrame.toInt())
                require(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "MERIDIAN zone $midi does not close" }
                val stem = Names.sanitizeStem("${name.replace(" ", "")}_${midi}_V${layerIndex + 1}")
                samples[stem] = note.snip
                val window = VELOCITY_WINDOWS[layerIndex]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(maxOf(MIDI_MIN, midi - 1), minOf(MIDI_MAX, midi + 1), midi, layers)
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds, explicitNoteOn = true) to samples
    }

    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File,
        overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** The middle native zone at the middle physical touch; no pad-capture fade. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        return renderNote(melodyPatch(spec.patch), midis[midis.size / 2], VELOCITIES[1], cancelled).snip
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("MERIDIAN instrument render cancelled")
    }
}
