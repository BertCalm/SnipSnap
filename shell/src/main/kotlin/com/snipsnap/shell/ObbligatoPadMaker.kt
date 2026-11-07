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
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoHeld
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.ObbligatoPlayback
import com.snipsnap.synth.PadRecipe
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/** Complete saved duet performances, held by the sampler and released when a key lifts. */
object ObbligatoPadMaker {
    const val ONE_SHOT_FADE_MS = 20f
    const val RELEASE_MIN_SECONDS = ObbligatoPatch.MIN_RELEASE_SECONDS
    const val RELEASE_MAX_SECONDS = ObbligatoPatch.MAX_RELEASE_SECONDS
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS,
        ObbligatoPatch.DEFAULT_RELEASE_SECONDS, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)
    fun supports(patch: ObbligatoPatch): Boolean = ObbligatoHeld.supports(patch)

    /** A finite pad copy keeps its source articulation and saves its final delivery fade. */
    fun deliveryRecipe(patch: ObbligatoPatch): PadRecipe {
        require(supports(patch)) { "OBBLIGATO one-shot delivery requires an expanded held patch" }
        return PadRecipe(patch = patch, terminalFadeMs = ONE_SHOT_FADE_MS)
    }

    data class Spec(val patch: ObbligatoPatch, val releaseSeconds: Float = patch.releaseSeconds) {
        init {
            require(supports(patch)) { "OBBLIGATO instruments require an expanded held patch" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS seconds"
            }
        }
        constructor(macros: Map<String, Float>, releaseSeconds: Float = RELEASE.default) : this(
            ObbligatoPatch("OBBLIGATO HELD", macros + ("HOLD" to 1f),
                model = Obbligato.PERFORMANCE_MODEL, playback = ObbligatoPlayback.HELD,
                releaseSeconds = releaseSeconds), releaseSeconds,
        )
        val macros: Map<String, Float> get() = patch.macros
    }

    fun spec(patch: ObbligatoPatch, releaseFraction: Float): Spec = Spec(patch.copy(
        releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
    ))
    fun spec(macros: Map<String, Float>, releaseFraction: Float): Spec =
        Spec(macros, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    fun zoneMidis(spec: Spec): List<Int> = ObbligatoHeld.midis(spec.patch)

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "OBBLIGATO zone $midi is outside this instrument's roots" }
        stopIfUnwanted(cancelled)
        val held = ObbligatoHeld.render(spec.patch, midi, cancelled)
        require(held.loopEndExclusive == held.snip.frameCount) { "OBBLIGATO sustain must loop to the sample's end" }
        stopIfUnwanted(cancelled)
        return KeyNote(held.snip, held.loopStartFrame.toLong())
    }

    /** Native minor-third roots tile the supported register without extending its outer bounds. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name must be MPC-safe and at most ${OneNote.MAX_NAME_LENGTH} characters: '$name'"
        }
        val midis = zoneMidis(spec)
        val range = Obbligato.midiRange(spec.patch.model)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filterNot { it.isWhitespace() }
        val samples = linkedMapOf<String, Snip>()
        val keygroups = midis.mapIndexed { index, midi ->
            val note = notes[index]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "OBBLIGATO zone $midi must be mono 44.1 kHz"
            }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount) {
                "OBBLIGATO zone $midi must retain its opening before a nonempty sustain loop"
            }
            require(note.snip.samples.all { it.isFinite() && it in -1f..1f }) {
                "OBBLIGATO zone $midi has invalid audio"
            }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (index == 0) range.first else (midis[index - 1] + midi) / 2 + 1,
                highNote = if (index == midis.lastIndex) range.last else (midi + midis[index + 1]) / 2,
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
        AtomicFile.writeText(recipe, spec.patch.copy(releaseSeconds = spec.releaseSeconds).toJsonText())
        return program
    }

    fun recipeFile(destRoot: File, name: String): File = File(destRoot, "$name.patch.json")

    fun previewNoteOffFrame(held: ObbligatoHeld.Held): Int =
        held.snip.frameCount + (held.loopEndExclusive - held.loopStartFrame)

    fun previewReleaseFrames(spec: Spec, sampleRate: Int): Int =
        (sampleRate * (spec.releaseSeconds + .05f)).roundToInt()

    /** The actual sampler holds two complete loop passes, then renders note-off and its release. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midi = spec.patch.midi
        // Preview the exact saved reference even when it is between native export roots.
        stopIfUnwanted(cancelled)
        val held = ObbligatoHeld.render(spec.patch, midi, cancelled)
        val sample = "obbligato-preview.wav"
        val instrument = InstrumentStore.Instrument(spec.patch.name, spec.releaseSeconds,
            listOf(InstrumentStore.Zone(midi, midi, midi, sample,
                held.snip.frameCount.toLong(), held.loopStartFrame.toLong())), explicitNoteOn = true)
        val rate = held.snip.sampleRate
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate,
            mapOf(sample to held.snip.samples)), rate)
        val heldFrames = previewNoteOffFrame(held)
        val releaseFrames = previewReleaseFrames(spec, rate)
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
        check(player.noteOn(midi)) { "OBBLIGATO preview has no zone for its saved reference" }
        append(heldFrames)
        player.noteOff(midi)
        append(releaseFrames)
        stopIfUnwanted(cancelled)
        return Snip(out, channels = 2, sampleRate = rate)
    }

    val NOTE_RANGE: IntRange get() = Obbligato.midiRange(Obbligato.PERFORMANCE_MODEL)

    private fun stopIfUnwanted(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("OBBLIGATO instrument no longer wanted")
    }
}
