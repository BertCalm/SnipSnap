package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsKeys
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/** The accepted model-2 sound as attack/sustain samples; the player supplies velocity gain and release. */
object BellowsPadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)
    val VELOCITIES = listOf(.35f, .65f, 1f)
    val VELOCITY_WINDOWS = listOf(1..42, 43..84, 85..127)

    data class Spec(val patch: BellowsPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) { "BELLOWS keys require model 2; model-1 recipes retain their original finite renderer" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    fun supports(patch: BellowsPatch): Boolean = patch.model == 2
    fun spec(patch: BellowsPatch, releaseFraction: Float): Spec = Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))
    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)
    fun zoneMidis(spec: Spec): List<Int> = (48..72 step 3).toList()

    /** Absolute played pitch: saved TUNE does not move the keyboard zones. WHEEL melody has no embedded drone. */
    fun melodyPatch(patch: BellowsPatch): BellowsPatch = patch.copy(macros = patch.macros +
        mapOf("TUNE" to 0f, "HOLD" to 1f) + if (patch.voice == BellowsVoice.WHEEL) mapOf("DRONE" to 0f) else emptyMap())

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "BELLOWS zone root must be one of ${zoneMidis(spec)}" }
        val patch = melodyPatch(spec.patch)
        return VELOCITIES.map { velocity ->
            BellowsKeys.layer(patch.voice, patch.macros, midi, velocity, patch.gesture, patch.model, cancelled)
        }
    }

    fun assemble(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val roots = zoneMidis(spec)
        require(notes.size == roots.size) { "want ${roots.size} pitch zones, got ${notes.size}" }
        val samples = linkedMapOf<String, Snip>()
        val groups = roots.mapIndexed { zoneIndex, midi ->
            require(notes[zoneIndex].size == VELOCITIES.size) { "BELLOWS zone $midi needs three velocity layers" }
            val layers = notes[zoneIndex].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == 44_100) { "BELLOWS samples must be mono 44100 Hz" }
                require(note.loopStartFrame in 1 until note.snip.frameCount.toLong()) { "BELLOWS zone $midi must have an attack and sustain loop" }
                val stem = Names.sanitizeStem("${name.replace(" ", "")}_${midi}_${layerIndex + 1}")
                samples[stem] = note.snip
                val window = VELOCITY_WINDOWS[layerIndex]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(maxOf(48, midi - 1), minOf(72, midi + 1), midi, layers)
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** A separate tonic-only program cannot repitch its bed when another melody note is played. */
    fun exportTonicBed(name: String, spec: Spec, note: KeyNote, destRoot: File, tonicMidi: Int = 48,
                       overwrite: Boolean = false): KeygroupProgram {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        require(tonicMidi in 48..72)
        require(note.loopStartFrame in 1 until note.snip.frameCount.toLong()) { "BELLOWS tonic bed must sustain" }
        val stem = Names.sanitizeStem("${name.replace(" ", "")}_$tonicMidi")
        val program = KeygroupProgram(name, listOf(Keygroup(tonicMidi, tonicMidi, tonicMidi,
            listOf(VelocityLayer(stem, note.snip.frameCount.toLong(), 1, 127, note.loopStartFrame)))),
            volumeRelease = spec.releaseSeconds)
        OneNote.writePackage(name, program, mapOf(stem to note.snip), destRoot, overwrite)
        return program
    }

    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val patch = melodyPatch(spec.patch)
        return BellowsKeys.note(patch.voice, patch.macros, 60, .65f, patch.gesture, patch.model, cancelled).snip
    }
}
