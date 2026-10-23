package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreaseKeys
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * A saved model-3 CREASE sound as an attack followed by the engine's continuous
 * held gesture. The saved velocity chooses the rendered excitation and timbre;
 * key touches use the player's existing linear gain on that authored sample.
 * RELEASE belongs to the phone/MPC amplitude envelope after a key lifts.
 *
 * Zones render separately so callers can cancel and count progress before
 * [export] writes a complete package. The source patch is never rewritten.
 */
object CreasePadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    /** Legacy finite and HOLD recipes retain their original models; this builder never upgrades them. */
    fun supports(patch: CreasePatch): Boolean = CreaseKeys.supports(patch)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(val patch: CreasePatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) {
                "CREASE held instruments support model ${CreaseKeys.SUPPORTED_MODEL}; model ${patch.model} stays unchanged"
            }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun spec(patch: CreasePatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** Minor-third roots around the saved pitch, with exact roots at clipped range boundaries. */
    fun zoneMidis(spec: Spec): List<Int> = Keys.creasePadMidis(spec.patch.midi)

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "CREASE zone $midi is outside this instrument's roots" }
        return Keys.creasePad(spec.patch, midi, cancelled)
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name must be MPC-safe and at most ${OneNote.MAX_NAME_LENGTH} characters: '$name'"
        }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { index, midi ->
            val note = notes[index]
            require(note.snip.channels == 1 && note.snip.sampleRate == Crease.RATE) {
                "CREASE zone $midi must be mono at ${Crease.RATE} Hz"
            }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1) {
                "CREASE zone $midi needs an attack and a sustain loop of at least two frames"
            }
            require(note.snip.samples.all { it.isFinite() }) { "CREASE zone $midi has nonfinite audio" }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (index == 0) midi else (midis[index - 1] + midi) / 2 + 1,
                highNote = if (index == midis.lastIndex) midi else (midi + midis[index + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(),
                    velStart = 0, velEnd = 127, loopStartFrame = note.loopStartFrame)),
            )
        }
        // The WAV already contains its pressure onset. A program attack would soften it twice.
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** Hear the saved root's actual arrival and a second pass around its held region. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val note = renderZone(spec, spec.patch.midi, cancelled)
        val samples = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val out = samples.copyOf(samples.size + samples.size - start)
        samples.copyInto(out, destinationOffset = samples.size, startIndex = start)
        return Snip(out, channels = 1, sampleRate = note.snip.sampleRate)
    }
}
