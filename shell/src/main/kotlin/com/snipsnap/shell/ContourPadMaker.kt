package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * A complete saved CONTOUR recipe as a seventeen-zone held instrument.
 * [Keys.contourPad] renders the settled sustain, omitting the finite sound's
 * original attack. A 5 ms entry eases key-on into that settled phase; RELEASE
 * controls the player's fade after a key lifts.
 * Saved excitation velocity is baked into each sample; playing velocity uses
 * the standard keygroup volume response.
 */
object ContourPadMaker {
    private const val ENTRY_FRAMES = 221 // 5 ms at 44.1 kHz, including both ramp endpoints.
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(val patch: ContourPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun spec(patch: ContourPatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** Fixed absolute roots: the saved TUNE is replaced by each zone's MIDI note. */
    fun zoneMidis(spec: Spec): List<Int> = Keys.contourPadMidis(spec.patch.voice)

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        val note = Keys.contourPad(spec.patch.voice, spec.patch.macros, midi, spec.patch.config, spec.patch.velocity, cancelled)
        // A settled cycle can begin at an arbitrary nonzero phase. Ease the initial
        // key-on into it without touching the second copy used for every loop wrap.
        val frames = minOf(ENTRY_FRAMES, note.loopStartFrame.toInt())
        for (i in 0 until frames) {
            val x = if (frames > 1) i.toFloat() / (frames - 1) else 0f
            note.snip.samples[i] = if (i == 0) 0f else note.snip.samples[i] * x * x * (3f - 2f * x)
        }
        return note
    }

    /** Assemble previously rendered zones in [zoneMidis] order, without rendering or writing files. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            val note = notes[i]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "CONTOUR zone $midi must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
            }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount.toLong() - 1) {
                "CONTOUR zone $midi must hold at least two frames after a positive loop marker"
            }
            require(note.snip.samples.all { it.isFinite() }) { "CONTOUR zone $midi has non-finite audio" }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                // Keep the key map within the engine's supported four-octave range.
                lowNote = if (i == 0) midi else midi - 1,
                highNote = if (i == midis.lastIndex) midi else midi + 1,
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(),
                    velStart = 0, velEnd = 127, loopStartFrame = note.loopStartFrame)),
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** The exact middle-zone sustain used in the exported instrument. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        return renderZone(spec, midis[midis.size / 2], cancelled).snip
    }
}
