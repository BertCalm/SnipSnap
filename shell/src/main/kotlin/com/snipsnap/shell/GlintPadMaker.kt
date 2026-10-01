package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.GlintVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * GLINT, held - a GLINT patch as a keys instrument (docs/superpowers/specs/2026-09-29-glint-paths-design.md §3).
 * [SirenPadMaker]'s shape: RELEASE alone; the onset and the breath come from the patch.
 *
 * Zones render one at a time through [renderZone], so a caller can spread
 * them over threads and show progress as each lands; [assemble] and
 * [export] never render, same division [ResinPadMaker] draws.
 */
object GlintPadMaker {

    /** The same range RESIN's held pad settled on (spec, Decided 4); nothing about GLINT's own release argues for a different one. */
    const val RELEASE_MIN_SECONDS = 0.1f
    const val RELEASE_MAX_SECONDS = 1.5f

    val RELEASE: Knob = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, 0.6f, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(
        val voice: GlintVoice,
        val macros: Map<String, Float>,
        val releaseSeconds: Float = RELEASE.default,
    ) {
        init {
            require(releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    /**
     * The sheet's one stepper as a spec, clamped for the same reason
     * [ResinPadMaker.spec] is: an exponential knob's ends can land a
     * float's width outside the range the spec refuses.
     */
    fun spec(voice: GlintVoice, macros: Map<String, Float>, releaseFraction: Float): Spec =
        Spec(voice, macros, releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** Nine zones from the voice's own root ([Keys.glintPadMidis]) - STEP sits an octave above the rest. */
    fun zoneMidis(spec: Spec): List<Int> = Keys.glintPadMidis(spec.voice)

    /**
     * One zone. There is no settle-and-retry path here - GLINT's held render
     * closes on itself by construction, and [Keys.glintPad] refuses a seam
     * that doesn't - but a zone is several seconds of audio rendered at four
     * times oversampling, so [cancelled] still reaches the render loop
     * ([Keys.glintPad]'s own), the same as [ResinPadMaker.renderZone]'s.
     */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote =
        Keys.glintPad(spec.voice, spec.macros, midi, cancelled)

    /**
     * The zones, in [zoneMidis] order, as a keygroup program and the
     * samples it names — [ResinPadMaker.assemble]'s own layout: edge zones
     * reach nine semitones past the ends, inner zones tile at ±1.
     */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            val note = notes[i]
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = (if (i == 0) midi - 9 else midi - 1).coerceIn(0, 127),
                highNote = (if (i == midis.lastIndex) midi + 9 else midi + 1).coerceIn(0, 127),
                rootNote = midi,
                layers = listOf(
                    VelocityLayer(
                        stem, note.snip.frameCount.toLong(), velStart = 0, velEnd = 127,
                        loopStartFrame = note.loopStartFrame,
                    ),
                ),
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** The middle zone's own render: onset, one breath, and the breath the key holds on. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        return renderZone(spec, midis[midis.size / 2], cancelled).snip
    }
}
