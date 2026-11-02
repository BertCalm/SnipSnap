package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.RimePatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/** Saved Rime contour, native opening and periodic sustain across nine keyboard roots. */
object RimePadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(val patch: RimePatch, val releaseSeconds: Float = RELEASE.default) {
        val heldPatch: RimePatch get() = patch.copy(macros = patch.macros + ("HOLD" to 1f))
        init {
            require(patch.model >= 5) { "RIME instruments require model 5; explicitly upgrade the saved patch" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun spec(patch: RimePatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** Absolute roots around the saved reference note; all other patch fields survive. */
    fun zoneMidis(spec: Spec): List<Int> = Keys.rimePadMidis(spec.patch)

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        if (cancelled()) throw java.util.concurrent.CancellationException("RIME instrument cancelled")
        val note = Keys.rimePad(spec.patch, midi)
        if (cancelled()) throw java.util.concurrent.CancellationException("RIME instrument cancelled")
        return note
    }

    /** Assemble previously rendered zones in [zoneMidis] order, without rendering or writing files. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            val note = notes[i]
            require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                "RIME zone $midi must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
            }
            require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount.toLong() - 1) {
                "RIME zone $midi must hold at least two frames after a positive loop marker"
            }
            require(note.snip.samples.all { it.isFinite() }) { "RIME zone $midi has non-finite audio" }
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                // Keep the key map within the instrument's sampled two-octave range.
                lowNote = if (i == 0) midi else midi - 1,
                highNote = if (i == midis.lastIndex) midi else midi + 1,
                rootNote = midi,
                layers = listOf(VelocityLayer(stem, note.snip.frameCount.toLong(),
                    velStart = 0, velEnd = 127, loopStartFrame = note.loopStartFrame)),
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        val data = File(destRoot, "${name}_[TrackData]")
        File(data, "rime-patch.json").writeText(spec.heldPatch.toJsonText())
        val zones = zoneMidis(spec).map { midi -> spec.heldPatch.copy(midi = midi).toJsonValue() }
        File(data, "rime-zones.json").writeText(com.snipsnap.json.Json.write(com.snipsnap.json.JsonValue.Arr(zones)))
        return program
    }

    /** Preview the saved root even when the zone map shifts at a range boundary. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        return renderZone(spec, midis.minBy { kotlin.math.abs(it - spec.patch.midi) }, cancelled).snip
    }
}
