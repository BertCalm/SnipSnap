package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.TrefoilHeld
import com.snipsnap.synth.TrefoilPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/** The native steady TREFOIL articulation, with note-off owned by the sample player. */
object TrefoilPadMaker {
    const val ONE_SHOT_FADE_MS = 20f
    const val RELEASE_MIN_SECONDS = TrefoilHeld.MIN_RELEASE_SECONDS
    const val RELEASE_MAX_SECONDS = TrefoilHeld.MAX_RELEASE_SECONDS
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS,
        TrefoilHeld.DEFAULT_RELEASE_SECONDS, exponential = true)

    /** The phone uses seconds; the same numeric MPC envelope value is not a hardware timing measurement. */
    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    fun supports(patch: TrefoilPatch): Boolean = TrefoilHeld.supports(patch)

    /** A finite pad copy retains its held source recipe and fades only its final 20 ms. */
    fun deliveryRecipe(patch: TrefoilPatch): PadRecipe {
        require(supports(patch)) { "TREFOIL one-shot delivery requires a model-2 steady held patch" }
        return PadRecipe(patch = patch, terminalFadeMs = ONE_SHOT_FADE_MS)
    }

    data class Spec(val patch: TrefoilPatch) {
        init { require(supports(patch)) { "TREFOIL instruments require a model-2 steady held patch" } }
        val releaseSeconds: Float get() = patch.releaseSeconds
    }

    fun spec(patch: TrefoilPatch, releaseFraction: Float): Spec = Spec(patch.copy(
        releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
    ))

    fun zoneMidis(spec: Spec): List<Int> = spec.patch.midi.let { root ->
        val first = if (root < 48) root else if (root > 72) root - 24 else root - 12
        listOf(first, first + 12, first + 24)
    }

    /** The saved topology, KNOT, RING and velocity reach every native reference note. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "TREFOIL zone $midi is outside this instrument's roots" }
        stopIfUnwanted(cancelled)
        val held = TrefoilHeld.render(spec.patch.copy(midi = midi), cancelled)
        require(held.loopEndExclusive == held.snip.frameCount) { "TREFOIL sustain must loop to the sample's end" }
        stopIfUnwanted(cancelled)
        return KeyNote(held.snip, held.loopStartFrame.toLong())
    }

    /** Three octave-spaced samples, with midpoint splits and at most six semitones of resampling. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name must be MPC-safe and at most ${OneNote.MAX_NAME_LENGTH} characters: '$name'"
        }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filterNot { it.isWhitespace() }
        val samples = linkedMapOf<String, Snip>()
        val keygroups = midis.mapIndexed { index, midi ->
            val note = notes[index]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "TREFOIL zone $midi must be mono 44.1 kHz"
            }
            require(note.loopStartFrame > 0 && note.snip.frameCount.toLong() % note.loopStartFrame == 0L) {
                "TREFOIL zone $midi needs one onset period followed by complete sustain periods"
            }
            require(note.snip.samples.all { it.isFinite() && it in -1f..1f }) {
                "TREFOIL zone $midi has invalid audio"
            }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (index == 0) midi - 6 else (midis[index - 1] + midi) / 2 + 1,
                highNote = if (index == midis.lastIndex) midi + 6 else (midi + midis[index + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(), 0, 127, note.loopStartFrame)),
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds, explicitNoteOn = true) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File,
        overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        val recipe = recipeFile(destRoot, name)
        val sidecar = InstrumentStore.sidecar(destRoot, name)
        if (!overwrite && (recipe.exists() || sidecar.exists())) throw DestinationExists.firstOf(recipe, sidecar)
        OneNote.writePackage(name, program, samples, destRoot, overwrite, spanKeyboard = false)
        AtomicFile.writeText(recipe, spec.patch.toJsonText())
        return program
    }

    fun recipeFile(destRoot: File, name: String): File = File(destRoot, "$name.patch.json")

    /** One onset and two sustain passes, followed by the actual sample player's note-off envelope. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midi = spec.patch.midi
        val note = renderZone(spec, midi, cancelled)
        val sample = "trefoil-preview.wav"
        val instrument = InstrumentStore.Instrument(spec.patch.name, spec.releaseSeconds,
            listOf(InstrumentStore.Zone(midi, midi, midi, sample,
                note.snip.frameCount.toLong(), note.loopStartFrame)))
        val rate = note.snip.sampleRate
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate,
            mapOf(sample to note.snip.samples)), rate)
        val heldFrames = note.snip.frameCount + (note.snip.frameCount - note.loopStartFrame).toInt()
        val releaseFrames = (rate * (spec.releaseSeconds + .05f)).roundToInt()
        val out = FloatArray((heldFrames + releaseFrames) * 2)
        var frame = 0
        fun append(count: Int) {
            var remaining = count
            while (remaining > 0) {
                stopIfUnwanted(cancelled)
                val frames = minOf(remaining, 512)
                val block = FloatArray(frames * 2)
                player.render(block, frames)
                block.copyInto(out, frame * 2)
                frame += frames
                remaining -= frames
            }
        }
        check(player.noteOn(midi)) { "TREFOIL preview has no zone for the saved reference" }
        append(heldFrames)
        player.noteOff(midi)
        append(releaseFrames)
        stopIfUnwanted(cancelled)
        return Snip(out, channels = 2, sampleRate = rate)
    }

    private fun stopIfUnwanted(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("TREFOIL instrument no longer wanted")
    }
}
