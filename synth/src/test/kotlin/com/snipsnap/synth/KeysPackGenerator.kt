package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.WavWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.WavInfo
import java.io.File

/**
 * Renders the keygroup acceptance program under testkit/. Run via
 * `./gradlew :synth:generateKeysPack`.
 *
 * This is the S4/S5 door pushed open from our side: VELVET's bass rendered
 * every minor third across two octaves, each zone velocity-layered
 * (soft render underneath), written as a keygroup program the MPC should
 * play chromatically. The writer's structure is corrected against the
 * harvested commercial keygroup programs in reference/golden/keygroup/
 * (real root notes, KeyTrack=False, 8 layer slots, maps-then-Keygroup*
 * tail); loading this pack on hardware is the remaining S4 acceptance
 * check (reference/README.md).
 */
object KeysPackGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val dir = File(root, "SnipSnap Keys")
        dir.deleteRecursively()
        dir.mkdirs()

        // VELVET BASS roots at 55 Hz = A1 = MIDI 33; TUNE snaps semitones.
        val rootMidi = 33
        val keygroups = mutableListOf<Keygroup>()
        for (s in 0..Velvet.TUNE_SEMITONES step 3) {
            val tune = s / Velvet.TUNE_SEMITONES.toFloat()
            val main = Velvet.render(VelvetVoice.BASS, mapOf("TUNE" to tune, "DECAY" to 0.7f))
            val soft = Velocity.soften(main, 0.6f)

            val note = Scales.nameOf(rootMidi + s)
            val mainStem = "Keys_Bass_$note"
            val softStem = "Keys_Bass_${note}_soft"
            WavWriter.write(File(dir, "$mainStem.wav"), main)
            WavWriter.write(File(dir, "$softStem.wav"), soft)

            val midi = rootMidi + s
            val low = if (s == 0) (midi - 9) else midi - 1
            val high = if (s == Velvet.TUNE_SEMITONES) (midi + 9) else midi + 1
            keygroups += Keygroup(
                lowNote = low.coerceIn(0, 127),
                highNote = high.coerceIn(0, 127),
                rootNote = midi,
                layers = listOf(
                    VelocityLayer(softStem, WavInfo.read(File(dir, "$softStem.wav")).frameCount, 1, 63),
                    VelocityLayer(mainStem, WavInfo.read(File(dir, "$mainStem.wav")).frameCount, 64, 127),
                ),
            )
        }

        val program = KeygroupProgram("SnipSnap Keys", keygroups)
        val file = KeygroupWriter().writeTo(dir, program)
        println("wrote ${file.absolutePath} (${keygroups.size} keygroups, ${keygroups.size * 2} samples)")
    }
}
