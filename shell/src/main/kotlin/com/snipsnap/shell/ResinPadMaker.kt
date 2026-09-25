package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Resin
import com.snipsnap.synth.ResinVoice
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * RESIN, held - a RESIN patch as a keys instrument that sounds while a key
 * is held (docs/superpowers/specs/2026-09-25-resin-held-pad-design.md).
 *
 * Zones render one at a time through [renderZone], so a caller can spread
 * them over threads and show progress as each lands; [assemble] and
 * [export] never render. The package is the shop's own (`OneNote.writePackage`):
 * the MPC reads the `.xty` and `.xpm`, the phone reads the sidecar, and
 * every zone carries its loop.
 */
object ResinPadMaker {

    const val RELEASE_MIN_SECONDS = 0.1f

    /** Modest until the MPC's reading of longer releases is heard (spec, Decided 4). */
    const val RELEASE_MAX_SECONDS = 1.5f

    val ATTACK: Knob = Knob("ATTACK", Resin.ATTACK_MIN_SECONDS, Resin.ATTACK_MAX_SECONDS, 0.3f, exponential = true)
    val RELEASE: Knob = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, 0.6f, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(
        val voice: ResinVoice,
        val macros: Map<String, Float>,
        val attackSeconds: Float = ATTACK.default,
        val releaseSeconds: Float = RELEASE.default,
    ) {
        init {
            require(attackSeconds in Resin.ATTACK_MIN_SECONDS..Resin.ATTACK_MAX_SECONDS) {
                "attack wants ${Resin.ATTACK_MIN_SECONDS}..${Resin.ATTACK_MAX_SECONDS} s, got $attackSeconds"
            }
            require(releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    /**
     * The sheet's two steppers as a spec, [PadMaker.spec]'s shape. Clamped,
     * because an exponential knob's ends can land a float's width outside
     * the range the spec refuses.
     */
    fun spec(voice: ResinVoice, macros: Map<String, Float>, attackFraction: Float, releaseFraction: Float): Spec =
        Spec(
            voice,
            macros,
            attackSeconds = ATTACK.value(attackFraction).coerceIn(Resin.ATTACK_MIN_SECONDS, Resin.ATTACK_MAX_SECONDS),
            releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS),
        )

    fun zoneMidis(spec: Spec): List<Int> = Keys.resinPadMidis(spec.voice)

    fun renderZone(spec: Spec, midi: Int): KeyNote = Keys.resinPad(spec.voice, spec.macros, midi, spec.attackSeconds)

    /**
     * The zones, in [zoneMidis] order, as a keygroup program and the samples
     * it names: `InstrumentSuite`'s layout, edge zones reaching nine
     * semitones past the ends and inner zones tiling at ±1.
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

    /** The middle zone, its head plus two passes of its loop: what a short hold sounds like. */
    fun preview(spec: Spec): Snip {
        val midis = zoneMidis(spec)
        val note = renderZone(spec, midis[midis.size / 2])
        val s = note.snip.samples
        val loopStart = note.loopStartFrame.toInt()
        val out = s.copyOf(s.size + (s.size - loopStart))
        System.arraycopy(s, loopStart, out, s.size, s.size - loopStart)
        return Snip(out, channels = 1, sampleRate = note.snip.sampleRate)
    }
}
