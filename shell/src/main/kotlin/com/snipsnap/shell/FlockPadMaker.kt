package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.FlockHeld
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * A saved held RIBBON as native-note sample zones. Synthesis and capture happen
 * off the playback thread; the existing phone and MPC players own note-off.
 * Each zone contains the authored arrival and one captured movement period.
 */
object FlockPadMaker {
    const val RELEASE_MIN_SECONDS = FlockHeld.MIN_RELEASE_SECONDS
    const val RELEASE_MAX_SECONDS = FlockHeld.MAX_RELEASE_SECONDS
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, FlockHeld.DEFAULT_RELEASE_SECONDS, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    fun supports(voice: FlockVoice): Boolean = voice == FlockVoice.RIBBON

    fun supports(patch: FlockPatch): Boolean = supports(patch.voice) && patch.playback == FlockPlayback.HELD

    /** Release is part of the saved performance, rather than transient MAKE state. */
    data class Spec(val patch: FlockPatch) {
        init {
            require(supports(patch)) { "FLOCK instruments require a held RIBBON patch" }
        }

        val releaseSeconds: Float get() = patch.releaseSeconds
    }

    fun spec(patch: FlockPatch, releaseFraction: Float): Spec = Spec(patch.copy(
        playback = FlockPlayback.HELD,
        releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
    ))

    fun zoneMidis(spec: Spec): List<Int> = FlockHeld.midis(spec.patch)

    /** The saved seed, velocity, renderer model and audible macros reach every native note. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "FLOCK zone $midi is outside this instrument's roots" }
        val held = FlockHeld.render(spec.patch, midi, cancelled)
        require(held.loopEndExclusive == held.snip.frameCount) { "FLOCK sustain must loop to the sample's end" }
        return KeyNote(held.snip, held.loopStartFrame.toLong())
    }

    /**
     * Midpoints limit transposition to one semitone. Coverage stops at the
     * sampled roots so an outer octave cannot accelerate the captured motion.
     */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            val note = notes[i]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) { "FLOCK zone $midi must be mono 44.1 kHz" }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1) {
                "FLOCK zone $midi has no captured sustain loop"
            }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (i == 0) midi else (midis[i - 1] + midi) / 2 + 1,
                highNote = if (i == midis.lastIndex) midi else (midi + midis[i + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(
                    stem, note.snip.frameCount.toLong(), velStart = 0, velEnd = 127,
                    loopStartFrame = note.loopStartFrame,
                )),
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        val recipe = File(destRoot, "$name.patch.json")
        if (!overwrite && recipe.exists()) throw DestinationExists.firstOf(recipe)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        AtomicFile.writeText(recipe, spec.patch.toJsonText())
        return program
    }

    /** The saved note's arrival and two loop passes, suitable for a finite shelf preview. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val note = renderZone(spec, spec.patch.midi, cancelled)
        val samples = note.snip.samples
        val loopStart = note.loopStartFrame.toInt()
        val loopFrames = samples.size - loopStart
        val out = samples.copyOf(samples.size + loopFrames)
        samples.copyInto(out, samples.size, loopStart)
        return Snip(out, channels = 1, sampleRate = note.snip.sampleRate)
    }
}
