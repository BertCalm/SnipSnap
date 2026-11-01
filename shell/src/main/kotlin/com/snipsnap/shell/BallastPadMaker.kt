package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.BallastVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * BALLAST, held - a BALLAST patch as a keys instrument
 * (docs/superpowers/specs/2026-10-04-ballast-bass-engine-design.md, the held loop).
 * [GlintPadMaker]'s shape: RELEASE alone. Each zone keeps the note's own
 * onset and the structure settling in front of the loop BALLAST already
 * exports at the top of HOLD ([Keys.ballastPad]), so a short press plays
 * the attack and a held key sustains on the settled bass. One velocity
 * layer, rendered at full touch, the velocity a kit pad renders at.
 *
 * Zones render one at a time through [renderZone], so a caller can show
 * progress as each lands; [assemble] and [export] never render, the same
 * division [ResinPadMaker] draws.
 */
object BallastPadMaker {

    /** The same range every held pad settled on ([ResinPadMaker], [GlintPadMaker]). */
    const val RELEASE_MIN_SECONDS = 0.1f
    const val RELEASE_MAX_SECONDS = 1.5f

    val RELEASE: Knob = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, 0.6f, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(
        val voice: BallastVoice,
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
    fun spec(voice: BallastVoice, macros: Map<String, Float>, releaseFraction: Float): Spec =
        Spec(voice, macros, releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** Thirteen zones, every minor third from C1 to C4 ([Keys.ballastPadMidis]); every voice shares the layout. */
    @Suppress("UNUSED_PARAMETER")
    fun zoneMidis(spec: Spec): List<Int> = Keys.ballastPadMidis()

    /**
     * One zone. A BALLAST zone is several seconds of a modal structure at
     * four times oversampling; [cancelled] is asked before each of the
     * render's passes ([Keys.ballastPad]'s own).
     */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote =
        Keys.ballastPad(spec.voice, spec.macros, midi, cancelled)

    /**
     * The zones, in [zoneMidis] order, as a keygroup program and the
     * samples it names: [ResinPadMaker.assemble]'s own layout, edge zones
     * reaching nine semitones past the ends and inner zones tiling at ±1.
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

    /** The middle zone's own render (F#2): the onset, the structure settling, and the loop the key holds on. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        return renderZone(spec, midis[midis.size / 2], cancelled).snip
    }
}
