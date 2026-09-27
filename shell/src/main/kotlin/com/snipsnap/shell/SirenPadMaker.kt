package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.SirenVoice
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * SIREN, held — a SIREN patch as a keys instrument that sounds while a key
 * is down (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md,
 * door 3). [ResinPadMaker]'s own shape, simplified by what SIREN's own LOOP
 * render already promises: it closes on itself exactly (`Keys.sirenPad`'s
 * own KDoc), so there is no ATTACK to dial in and no per-zone settle to
 * retry — RELEASE alone, the fade the MPC's own keygroup player runs after
 * a key lifts.
 *
 * Zones render one at a time through [renderZone], so a caller can spread
 * them over threads and show progress as each lands; [assemble] and
 * [export] never render, same division [ResinPadMaker] draws.
 */
object SirenPadMaker {

    /** The same range RESIN's held pad settled on (spec, Decided 4); nothing about SIREN's own release argues for a different one. */
    const val RELEASE_MIN_SECONDS = 0.1f
    const val RELEASE_MAX_SECONDS = 1.5f

    val RELEASE: Knob = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, 0.6f, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(
        val voice: SirenVoice,
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
    fun spec(voice: SirenVoice, macros: Map<String, Float>, releaseFraction: Float): Spec =
        Spec(voice, macros, releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** Same nine zones for every voice ([Keys.sirenPadMidis]'s own KDoc) — `spec.voice` decides the sound, never the layout. */
    fun zoneMidis(spec: Spec): List<Int> = Keys.sirenPadMidis()

    /** One zone. SIREN's render is short and uninterruptible mid-flight, unlike RESIN's own settle-and-retry path, so there is no cancellation lambda to poll. */
    fun renderZone(spec: Spec, midi: Int): KeyNote = Keys.sirenPad(spec.voice, spec.macros, midi)

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

    /**
     * The middle zone's own render: two loop passes back to back, the
     * shape [Keys.sirenPad] always produces. What a held key sounds like,
     * with no extra assembly — unlike [ResinPadMaker.preview], which has an
     * unlooped attack head to splice in front of the loop.
     */
    fun preview(spec: Spec): Snip {
        val midis = zoneMidis(spec)
        return renderZone(spec, midis[midis.size / 2]).snip
    }
}
