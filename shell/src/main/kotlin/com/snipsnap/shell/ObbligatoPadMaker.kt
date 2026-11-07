package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Obbligato
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * OBBLIGATO, held - the air-jet and reed duet as a keys instrument.
 * [KeelPadMaker]'s shape: RELEASE alone. Each zone is the duet's own entry
 * and the first stretch of its held exchange, then that exchange as a loop
 * ([Keys.obbligatoPad]), so a short press plays the entry and a held key
 * keeps the two players trading. One velocity layer, rendered at the
 * engine's default touch.
 *
 * Zones render one at a time through [renderZone], so a caller can show
 * progress; [assemble] and [export] never render. No picker entry calls
 * this yet: the app's keys screens need an Android SDK session to wire and
 * check.
 */
object ObbligatoPadMaker {

    /** The same range every held pad settled on ([KeelPadMaker], [ResinPadMaker]). */
    const val RELEASE_MIN_SECONDS = 0.1f
    const val RELEASE_MAX_SECONDS = 1.5f

    val RELEASE: Knob = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, 0.6f, exponential = true)

    data class Spec(
        val macros: Map<String, Float>,
        val releaseSeconds: Float = RELEASE.default,
    ) {
        init {
            require(releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    /** The sheet's one stepper as a spec, clamped as [KeelPadMaker.spec] is. */
    fun spec(macros: Map<String, Float>, releaseFraction: Float): Spec =
        Spec(macros, releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** Five zones, every minor third across OBBLIGATO's own C4 to C5 ([Keys.obbligatoPadMidis]). */
    @Suppress("UNUSED_PARAMETER")
    fun zoneMidis(spec: Spec): List<Int> = Keys.obbligatoPadMidis()

    /** One zone ([Keys.obbligatoPad]); [cancelled] is asked between the render and the loop search. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote =
        Keys.obbligatoPad(spec.macros, midi, cancelled = cancelled)

    /**
     * The zones as a keygroup program and the samples it names:
     * [KeelPadMaker.assemble]'s layout, edge zones reaching nine semitones
     * past the ends and inner zones tiling at ±1.
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

    /** The middle zone's own render: the entry, then the exchange the key holds on. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        return renderZone(spec, midis[midis.size / 2], cancelled).snip
    }

    /** The engine's own note range, for a caller that labels the keys. */
    val NOTE_RANGE: IntRange = Obbligato.MIDI_MIN..Obbligato.MIDI_MAX
}
