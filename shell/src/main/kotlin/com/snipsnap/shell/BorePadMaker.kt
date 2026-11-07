package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.BoreKeys
import com.snipsnap.synth.BorePatch
import com.snipsnap.synth.BoreVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * BORE held: a keys instrument whose every zone is a held note with its own attack, then a sustain loop
 * ([BoreKeys]; docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md, "R2: the held keygroup, as built").
 * [BellowsPadMaker]'s layered shape: nine zones a voice, two BREATH layers a zone (soft first), RELEASE the one
 * knob, the fade the MPC's keygroup player runs after a key lifts. The player applies velocity as gain; the
 * layers differ by timbre.
 *
 * BORE left [LoopPadMaker] in the same change: its loop-only zones had no attack (a held key started inside
 * the loop), and these zones play the note's own.
 */
object BorePadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    /** Soft first, [BoreKeys.VELOCITY_WINDOWS]: the export's windows and the preview's. */
    val VELOCITY_WINDOWS: List<IntRange> = BoreKeys.VELOCITY_WINDOWS

    /** The voices whose zones were measured. A voice added to BORE is not offered until its own are. */
    val VOICES: Set<BoreVoice> = setOf(BoreVoice.FLUTE, BoreVoice.SAX)

    /**
     * How long [preview] holds its key: past the zone's end (3.72 to 3.92 s as measured), where it wraps to the loop start,
     * and about two seconds on into the loop. A second wrap falls inside the hold only when the loop is the shorter of its
     * two usual lengths (1.92 s, ten vibrato cycles, against 2.12 s).
     */
    const val PREVIEW_HOLD_SECONDS = 6f

    /** The preview's touch: inside the hard layer's window, at the gain a firm key plays. */
    const val PREVIEW_VELOCITY = .8f

    data class Spec(val patch: BorePatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) { "BORE keys are measured for ${VOICES.joinToString()}, not ${patch.voice}" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun supports(patch: BorePatch): Boolean = patch.voice in VOICES

    /** The sheet's one stepper as a spec, clamped as [BellowsPadMaker.spec] is. */
    fun spec(patch: BorePatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    /** The voice's nine zones, its whole TUNE range by minor thirds. The patch's own TUNE does not move them: a key plays its note. */
    fun zoneMidis(spec: Spec): List<Int> = BoreKeys.padMidis(spec.patch.voice)

    /** One zone's two layers at [midi], soft first. Refused, naming the zone, if a layer's join does not hold. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "BORE ${spec.patch.voice} zone root must be one of ${zoneMidis(spec)}" }
        return BoreKeys.pad(spec.patch.voice, spec.patch.macros, midi, cancelled = cancelled)
    }

    /**
     * The zones, in [zoneMidis] order, as a keygroup program and the samples it names: [ResinPadMaker.assemble]'s
     * layout (edge zones reaching nine semitones past the ends, inner zones tiling at plus or minus one), each zone's
     * two layers in [VELOCITY_WINDOWS], each with its own loop start.
     */
    fun assemble(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val roots = zoneMidis(spec)
        require(notes.size == roots.size) { "want ${roots.size} pitch zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = linkedMapOf<String, Snip>()
        val groups = roots.mapIndexed { zoneIndex, midi ->
            require(notes[zoneIndex].size == VELOCITY_WINDOWS.size) { "BORE zone $midi needs ${VELOCITY_WINDOWS.size} velocity layers" }
            val layers = notes[zoneIndex].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) { "BORE samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz" }
                require(note.loopStartFrame in 1 until note.snip.frameCount.toLong()) { "BORE zone $midi must have an attack and a sustain loop" }
                val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}_${layerIndex + 1}")
                samples[stem] = note.snip
                val window = VELOCITY_WINDOWS[layerIndex]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(
                lowNote = (if (zoneIndex == 0) midi - 9 else midi - 1).coerceIn(0, 127),
                highNote = (if (zoneIndex == roots.lastIndex) midi + 9 else midi + 1).coerceIn(0, 127),
                rootNote = midi,
                layers = layers,
            )
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /**
     * The middle zone's hard layer held for [PREVIEW_HOLD_SECONDS] at [PREVIEW_VELOCITY], then released, by the same
     * sample player as the instrument reference: the attack, the first pass into the loop, a wrap, and the chosen
     * release, a player fade that is never baked into the exported samples.
     */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        return preview(spec, midis[midis.size / 2], VELOCITY_WINDOWS.lastIndex, PREVIEW_HOLD_SECONDS, PREVIEW_VELOCITY, cancelled)
    }

    /**
     * Layer [layer] (0 soft, 1 hard) of the zone at [midi], held for [holdSeconds] at [velocity] and released: the
     * audition's way to hear either layer at the same gain, so the two differ by timbre alone.
     */
    internal fun preview(spec: Spec, midi: Int, layer: Int, holdSeconds: Float, velocity: Float, cancelled: () -> Boolean = { false }): Snip {
        require(midi in zoneMidis(spec)) { "BORE ${spec.patch.voice} zone root must be one of ${zoneMidis(spec)}" }
        val breath = BoreKeys.layerBreaths(spec.patch.voice, spec.patch.macros)[layer]
        val note = BoreKeys.padLayer(spec.patch.voice, spec.patch.macros, midi, breath, cancelled = cancelled)
        return play(note, midi, holdSeconds, velocity, spec.releaseSeconds, "BORE preview", cancelled)
    }

    /**
     * [note] as one key at [midi] through [InstrumentEngine]: held for [holdSeconds] at [velocity], then released over
     * [releaseSeconds], with a short silent guard after. [BellowsPadMaker.preview]'s transport, with the hold and the
     * touch as arguments so the audition can play any zone, this maker's or another's, the same way.
     */
    internal fun play(note: KeyNote, midi: Int, holdSeconds: Float, velocity: Float, releaseSeconds: Float, label: String,
                      cancelled: () -> Boolean = { false }): Snip {
        val source = note.snip
        val zone = InstrumentStore.Zone(midi, midi, midi, "preview.wav", source.frameCount.toLong(), note.loopStartFrame)
        val instrument = InstrumentStore.Instrument(label, releaseSeconds, listOf(zone))
        val engine = InstrumentEngine(InstrumentEngine.Loaded(instrument, source.sampleRate, listOf(source.samples)), source.sampleRate)
        check(engine.noteOn(midi, velocity))
        val holdFrames = (holdSeconds * source.sampleRate).roundToInt()
        // A short silent guard also covers rounding in the player's per-frame fade.
        val releaseFrames = (releaseSeconds * source.sampleRate).roundToInt()
        val tailFrames = (source.sampleRate * .01f).roundToInt()
        val mono = FloatArray(holdFrames + releaseFrames + tailFrames)
        val block = FloatArray(256 * 2)
        var at = 0
        fun render(until: Int) {
            while (at < until) {
                if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("$label no longer wanted")
                val frames = minOf(block.size / 2, until - at)
                engine.render(block, frames)
                for (i in 0 until frames) mono[at + i] = block[i * 2]
                at += frames
            }
        }
        render(holdFrames)
        engine.noteOff(midi)
        render(mono.size)
        return Snip(mono, 1, source.sampleRate)
    }
}
