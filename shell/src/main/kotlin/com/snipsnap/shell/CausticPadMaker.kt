package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * A current CAUSTIC recipe as a real arrival followed by a verified sustain.
 * The captured velocity supplies the native excitation; live keys apply the
 * player's existing velocity gain. Release is the phone/MPC amplitude envelope.
 * The source recipe is retained unchanged, including its original HOLD value.
 */
object CausticPadMaker {
    const val SUPPORTED_MODEL = 2
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    const val PATCH_FILE = "caustic.patch.json"
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    fun supports(patch: CausticPatch): Boolean = patch.model == SUPPORTED_MODEL

    data class Spec(val patch: CausticPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) {
                "CAUSTIC held instruments require model $SUPPORTED_MODEL; model ${patch.model} stays unchanged"
            }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun spec(patch: CausticPatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    /** Minor-third roots around the saved pitch, clipped to the engine's tested register. */
    fun zoneMidis(spec: Spec): List<Int> {
        val root = spec.patch.midi
        val low = (root - 12).coerceAtLeast(CausticInstrument.MIDI_MIN)
        val high = (root + 12).coerceAtMost(CausticInstrument.MIDI_MAX)
        return ((-12..12 step 3).map { root + it }.filter { it in low..high } +
            listOf(low, root, high)).distinct().sorted()
    }

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "CAUSTIC zone $midi is outside this instrument's roots" }
        checkCancelled(cancelled)
        return CausticInstrument.keyNote(spec.patch.voice, spec.patch.macros + ("HOLD" to 1f),
            midi, spec.patch.velocity, spec.patch.model, cancelled)
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name must be MPC-safe and at most ${OneNote.MAX_NAME_LENGTH} characters: '$name'"
        }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filterNot(Char::isWhitespace)
        val samples = linkedMapOf<String, Snip>()
        val keygroups = midis.mapIndexed { index, midi ->
            val note = notes[index]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "CAUSTIC zone $midi must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
            }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1L) {
                "CAUSTIC zone $midi needs an arrival and a sustain loop of at least two frames"
            }
            require(note.snip.samples.all { it.isFinite() && it in -1f..1f }) {
                "CAUSTIC zone $midi has nonfinite or out-of-range audio"
            }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (index == 0) midi else (midis[index - 1] + midi) / 2 + 1,
                highNote = if (index == midis.lastIndex) midi else (midi + midis[index + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(), 0, 127, note.loopStartFrame)),
            )
        }
        // The WAV owns its onset. Only key-up adds a program envelope.
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds, explicitNoteOn = true) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File,
        overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        // Keep the deliberately sampled register identical in the phone and both MPC descriptors.
        OneNote.writePackage(name, program, samples, destRoot, overwrite, spanKeyboard = false)
        val dataDir = File(destRoot, Mpc3TrackWriter.trackDataDirName(name))
        AtomicFile.writeText(File(dataDir, PATCH_FILE), spec.patch.toJsonText())
        return program
    }

    /** Hear the complete arrival, another sustain pass, and actual player note-off. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val note = renderZone(spec, spec.patch.midi, cancelled)
        val sample = "preview.wav"
        val instrument = InstrumentStore.Instrument(spec.patch.name, spec.releaseSeconds,
            listOf(InstrumentStore.Zone(spec.patch.midi, spec.patch.midi, spec.patch.midi,
                sample, note.snip.frameCount.toLong(), note.loopStartFrame)))
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, note.snip.sampleRate,
            mapOf(sample to note.snip.samples)), note.snip.sampleRate)
        val heldFrames = note.snip.frameCount.toLong() * 2 - note.loopStartFrame
        val releaseFrames = ((spec.releaseSeconds + .1f) * note.snip.sampleRate).roundToInt()
        val frames = heldFrames + releaseFrames
        require(frames in 1..(Int.MAX_VALUE / 2).toLong()) { "CAUSTIC preview is too long" }
        val out = FloatArray(frames.toInt() * 2)
        var at = 0
        fun append(count: Int) {
            var remaining = count
            while (remaining > 0) {
                checkCancelled(cancelled)
                val size = minOf(remaining, 512)
                val block = FloatArray(size * 2)
                player.render(block, size)
                block.copyInto(out, at * 2)
                at += size
                remaining -= size
            }
        }
        check(player.noteOn(spec.patch.midi, 1f)) { "CAUSTIC preview root has no zone" }
        append(heldFrames.toInt())
        player.noteOff(spec.patch.midi)
        append(releaseFrames)
        checkCancelled(cancelled)
        return Snip(out, channels = 2, sampleRate = note.snip.sampleRate)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("CAUSTIC instrument render no longer wanted")
    }
}
