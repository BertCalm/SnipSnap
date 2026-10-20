package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaConfig
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/** A saved ORPHICA note as a stereo captured-sustain keys instrument. */
object OrphicaPadMaker {
    const val RELEASE_MIN_SECONDS = 0.1f
    const val RELEASE_MAX_SECONDS = 1.5f

    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, 0.6f, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(
        val config: OrphicaConfig,
        val macros: Map<String, Float>,
        val releaseSeconds: Float = RELEASE.default,
    ) {
        init {
            require(releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
            Orphica.configWithMacros(config, macros)
        }
    }

    fun spec(config: OrphicaConfig, macros: Map<String, Float>, releaseFraction: Float): Spec =
        Spec(config, macros, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** Exact note roots near the saved audible root, with boundary roots at the engine's limits. */
    fun zoneMidis(spec: Spec): List<Int> {
        val root = Orphica.configWithMacros(spec.config, spec.macros).midi
        val low = (root - 12).coerceAtLeast(Orphica.MIDI_MIN)
        val high = (root + 12).coerceAtMost(Orphica.MIDI_MAX)
        return ((-12..12 step 3).map { root + it }.filter { it in low..high } + low + high).distinct().sorted()
    }

    /** Capture each zone at its actual note; HOLD does not mutate the saved finite patch. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "ORPHICA zone $midi is outside this instrument's roots" }
        val config = Orphica.configWithMacros(spec.config, spec.macros).copy(hold = 1f)
        return Keys.orphicaPad(config, midi, cancelled)
    }

    /** Stereo stays stereo; ranges stop at the sampled span so outer notes cannot shift grains by an octave. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            val note = notes[i]
            require(note.snip.channels == 2) { "ORPHICA zone $midi must retain stereo" }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1) {
                "ORPHICA zone $midi has no captured sustain loop"
            }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = if (i == 0) midi else (midis[i - 1] + midi) / 2 + 1,
                highNote = if (i == midis.lastIndex) midi else (midi + midis[i + 1]) / 2,
                rootNote = midi,
                layers = listOf(VelocityLayer(
                    stem, note.snip.frameCount.toLong(), velStart = 0, velEnd = 127,
                    loopStartFrame = note.loopStartFrame,
                )),
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val root = Orphica.configWithMacros(spec.config, spec.macros).midi
        return renderZone(spec, root, cancelled).snip
    }
}
