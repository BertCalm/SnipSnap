package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.OpenSky
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OpenSkyVoice
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * HALO as a held stereo instrument. The saved patch is the source of truth for
 * its timbre, macros, pitch, playing energy and wet mode; making an instrument
 * changes only its transport to an attack followed by a measured sustain loop.
 * RELEASE belongs to the phone/MPC amp envelope after a key lifts.
 *
 * Rendering and packaging stay separate so MAKE can cancel and count each zone
 * before writing the complete dual-generation package through [OneNote].
 */
object OpenSkyPadMaker {
    const val ROOT_MIDI_MIN = 36
    const val ROOT_MIDI_MAX = 96
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    fun supports(voice: OpenSkyVoice): Boolean = voice == OpenSkyVoice.HALO
    fun supports(patch: OpenSkyPatch): Boolean = supports(patch.voice) && patch.midi in ROOT_MIDI_MIN..ROOT_MIDI_MAX
    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(val patch: OpenSkyPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch.voice)) { "OPENSKY instruments support HALO; ${patch.voice} is a finite sound" }
            require(patch.midi in ROOT_MIDI_MIN..ROOT_MIDI_MAX) {
                "OPENSKY instrument root must be MIDI $ROOT_MIDI_MIN..$ROOT_MIDI_MAX, got ${patch.midi}"
            }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun spec(patch: OpenSkyPatch, releaseFraction: Float): Spec = Spec(
        patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
    )

    fun zoneMidis(spec: Spec): List<Int> = Keys.openSkyPadMidis(spec.patch.voice, spec.patch.midi)

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        val patch = spec.patch
        return Keys.openSkyPad(patch.voice, patch.macros, midi, velocity = patch.velocity,
            timbre = patch.timbre, wet = patch.wet, cancelled = cancelled)
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            val note = notes[i]
            require(note.snip.channels == 2 && note.snip.sampleRate == OpenSky.RATE) { "OPENSKY zone $midi must be stereo at ${OpenSky.RATE} Hz" }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount) { "OPENSKY zone $midi needs an attack and a sustain loop" }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = (if (i == 0) midi - 9 else midi - 1).coerceIn(0, 127),
                highNote = (if (i == midis.lastIndex) midi + 9 else midi + 1).coerceIn(0, 127),
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(), velStart = 0, velEnd = 127,
                    loopStartFrame = note.loopStartFrame)),
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** The actual arrival plus two complete sustain passes; frame markers remain stereo frame indices. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        val note = renderZone(spec, midis[midis.size / 2], cancelled)
        val samples = note.snip.samples
        val start = note.loopStartFrame.toInt() * note.snip.channels
        val loopSamples = samples.size - start
        val out = samples.copyOf(samples.size + loopSamples)
        System.arraycopy(samples, start, out, samples.size, loopSamples)
        return Snip(out, channels = note.snip.channels, sampleRate = note.snip.sampleRate)
    }
}
