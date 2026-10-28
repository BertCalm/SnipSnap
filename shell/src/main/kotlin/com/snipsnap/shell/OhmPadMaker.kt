package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmPatch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.cos
import kotlin.math.roundToInt

/** Native OHM attack and sustain through the existing phone and MPC keygroup players. */
object OhmPadMaker {
    const val RELEASE_MIN_SECONDS = .02f
    const val RELEASE_MAX_SECONDS = 3f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .3f, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    fun supports(patch: OhmPatch): Boolean = Ohm.isHeld(patch)

    /** The portable patch owns release, source recording and every performance choice. */
    data class Spec(val patch: OhmPatch) {
        init { require(supports(patch)) { "OHM instruments require a model-3 held patch" } }
        val releaseSeconds: Float get() = patch.releaseSeconds
    }

    fun spec(patch: OhmPatch, releaseFraction: Float): Spec = Spec(patch.copy(
        macros = patch.macros + ("HOLD" to 1f),
        releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
    ))

    /** Include the saved note exactly; bound the outer keys and time-scaling to this native span. */
    fun zoneMidis(spec: Spec): List<Int> =
        ((Ohm.MIDI_MIN..Ohm.MIDI_MAX step 3).toList() + spec.patch.midi).distinct().sorted()

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "OHM zone $midi is outside this instrument's roots" }
        checkCancelled(cancelled)
        val note = Ohm.renderHeld(spec.patch.copy(midi = midi), cancelled)
        checkCancelled(cancelled)
        require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) { "OHM zones must be mono 44.1 kHz" }
        require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1) { "OHM zone $midi has no sustain loop" }
        return note
    }

    /** All loops run to the sample's end; note-off release belongs to the player. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val samples = LinkedHashMap<String, Snip>()
        val prefix = name.filterNot { it.isWhitespace() }
        val keygroups = midis.mapIndexed { index, midi ->
            val note = notes[index]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) { "OHM zone $midi must be mono 44.1 kHz" }
            require(note.snip.samples.all { it.isFinite() && it in -1f..1f }) { "OHM zone $midi has invalid PCM" }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1) { "OHM zone $midi has no sustain loop" }
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

    /** Audition the arrival, two loop passes and a release without fading the exported sustain. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val note = renderZone(spec, spec.patch.midi, cancelled)
        val source = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val period = source.size - start
        val release = (spec.releaseSeconds * note.snip.sampleRate).roundToInt().coerceAtLeast(2)
        val releaseAt = source.size + period
        val samples = source.copyOf(releaseAt + release)
        for (i in source.size until samples.size) {
            if (i % 8192 == 0) checkCancelled(cancelled)
            val value = source[start + (i - source.size) % period]
            val gain = if (i < releaseAt) 1.0 else .5 + .5 * cos(Math.PI * (i - releaseAt) / (release - 1))
            samples[i] = (value * gain).toFloat()
        }
        return Snip(samples, 1, note.snip.sampleRate)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("OHM instrument render cancelled")
    }
}
