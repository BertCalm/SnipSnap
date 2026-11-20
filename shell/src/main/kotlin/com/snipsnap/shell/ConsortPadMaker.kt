package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.ConsortKeys
import com.snipsnap.synth.ConsortPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * CONSORT held: a keys instrument that sustains while a key is down ([ConsortKeys]), a lead voice and its
 * section on every key. [BorePadMaker]'s shape (nine zones by minor thirds, each sample its attack then a
 * sustain loop, RELEASE the one knob, the fade the keygroup player runs after a key lifts) in ACCORD's and
 * HALOBAR's matching-window Cycle layout: three touch windows a zone, each window's two takes dealt in turn,
 * so velocity and round robin both survive the phone and MPC 3.
 */
object ConsortPadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .4f, exponential = true)

    /** Soft first: the export's windows and the phone's. */
    val VELOCITY_WINDOWS: List<IntRange> = ConsortKeys.VELOCITY_WINDOWS

    /** How long [preview] holds its key: past the first wrap of a two-second loop that starts near 0.3 s. */
    const val PREVIEW_HOLD_SECONDS = 4f

    /** The preview's touch: inside the hard window. */
    const val PREVIEW_VELOCITY = .8f

    data class Spec(val patch: ConsortPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    /** Every CONSORT patch makes keys: a solo voice or a section, any gesture. */
    @Suppress("UNUSED_PARAMETER")
    fun supports(patch: ConsortPatch): Boolean = true

    fun spec(patch: ConsortPatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** The lead voice's nine zones across its two-octave range. The patch's own TUNE does not move them: a key plays its note. */
    fun zoneMidis(spec: Spec): List<Int> = ConsortKeys.padMidis(spec.patch.voice)

    fun instrumentNote(spec: Spec): String {
        val midis = zoneMidis(spec)
        val section = if (spec.patch.chairs.isEmpty()) "" else " · section of ${spec.patch.chairs.size + 1}"
        return "Sustains while held$section. ${Scales.nameOf(midis.first())}–${Scales.nameOf(midis.last())} · three touch layers · " +
            "two takes per touch · ${midis.size * ConsortKeys.LAYERS} samples. Let go to fade."
    }

    /** One zone's six layers at [midi]: soft to hard, each touch's two takes in turn. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "CONSORT ${spec.patch.voice} zone root must be one of ${zoneMidis(spec)}" }
        return ConsortKeys.zone(spec.patch, midi, cancelled)
    }

    /**
     * The zones, in [zoneMidis] order, as a keygroup program and the samples it names: edge zones reaching nine
     * semitones past the ends, inner zones tiling at plus or minus one ([BorePadMaker.assemble]'s spans), each
     * zone's six layers in [VELOCITY_WINDOWS] with their own loop starts, cycling within each window.
     */
    fun assemble(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val roots = zoneMidis(spec)
        require(notes.size == roots.size) { "want ${roots.size} pitch zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = linkedMapOf<String, Snip>()
        val groups = roots.mapIndexed { zoneIndex, midi ->
            require(notes[zoneIndex].size == ConsortKeys.LAYERS) { "CONSORT zone $midi needs ${ConsortKeys.LAYERS} layers" }
            val layers = notes[zoneIndex].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                    "CONSORT samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
                }
                require(note.loopStartFrame in 1 until note.snip.frameCount.toLong()) { "CONSORT zone $midi must have an attack and a sustain loop" }
                val touch = layerIndex / ConsortKeys.TAKES.size
                val take = layerIndex % ConsortKeys.TAKES.size
                val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}_T${touch + 1}_R${take + 1}")
                samples[stem] = note.snip
                val window = VELOCITY_WINDOWS[touch]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(
                lowNote = (if (zoneIndex == 0) midi - 9 else midi - 1).coerceIn(0, 127),
                highNote = (if (zoneIndex == roots.lastIndex) midi + 9 else midi + 1).coerceIn(0, 127),
                rootNote = midi,
                layers = layers,
                cycleLayers = true,
                mpcVelocityCycle = true,
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
     * The middle zone's hard, first take held for [PREVIEW_HOLD_SECONDS] at [PREVIEW_VELOCITY] and released through
     * the keygroup player ([BorePadMaker.play]): the attack, the loop and a wrap, then the chosen release.
     */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        val midi = midis[midis.size / 2]
        val note = ConsortKeys.render(spec.patch, ConsortKeys.Cell(midi, ConsortKeys.TOUCHES.lastIndex, 0), cancelled = cancelled)
        return BorePadMaker.play(note, midi, PREVIEW_HOLD_SECONDS, PREVIEW_VELOCITY, spec.releaseSeconds, "CONSORT preview", cancelled)
    }
}
