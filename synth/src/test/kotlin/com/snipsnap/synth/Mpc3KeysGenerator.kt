package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.WavInfo
import java.io.File

/**
 * Renders the keygroup acceptance program as an MPC 3 **native** instrument
 * track under testkit/ — `SnipSnap MPC3 Keys.xty` beside its
 * `_[TrackData]/` WAVs. Run via `./gradlew :synth:generateMpc3Keys`.
 *
 * The same VELVET bass multisample as `SnipSnap Keys` (every minor third
 * across two octaves, soft layer underneath), written in the Live III's own
 * generation — the S5 door in native form. Zones ride in
 * `program.drum.instruments` with per-layer root notes; if this loads and
 * plays in tune chromatically, keys-on-pads runs natively end to end.
 */
object Mpc3KeysGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val name = "SnipSnap MPC3 Keys"
        val dataDir = File(root, Mpc3TrackWriter.trackDataDirName(name))
        dataDir.deleteRecursively()
        dataDir.mkdirs()

        // VELVET BASS roots at 55 Hz = A1 = MIDI 33; TUNE snaps semitones.
        val rootMidi = 33
        val keygroups = mutableListOf<Keygroup>()
        for (s in 0..Velvet.TUNE_SEMITONES step 3) {
            val tune = s / Velvet.TUNE_SEMITONES.toFloat()
            val main = Velvet.render(VelvetVoice.BASS, mapOf("TUNE" to tune, "DECAY" to 0.7f))
            val soft = Velocity.soften(main, 0.6f)

            val midi = rootMidi + s
            val note = Scales.nameOf(midi)
            val mainStem = "Keys_Bass_$note"
            val softStem = "Keys_Bass_${note}_soft"
            WavWriter.write(File(dataDir, "$mainStem.wav"), main)
            WavWriter.write(File(dataDir, "$softStem.wav"), soft)

            val low = if (s == 0) (midi - 9) else midi - 1
            val high = if (s == Velvet.TUNE_SEMITONES) (midi + 9) else midi + 1
            keygroups += Keygroup(
                lowNote = low.coerceIn(0, 127),
                highNote = high.coerceIn(0, 127),
                rootNote = midi,
                layers = listOf(
                    VelocityLayer(softStem, WavInfo.read(File(dataDir, "$softStem.wav")).frameCount, 1, 63),
                    VelocityLayer(mainStem, WavInfo.read(File(dataDir, "$mainStem.wav")).frameCount, 64, 127),
                ),
            )
        }

        val program = KeygroupProgram(name, keygroups)
        val file = Mpc3TrackWriter().writeKeygroupTo(root, program)
        println("wrote ${file.absolutePath} (${keygroups.size} zones, ${keygroups.size * 2} samples in _[TrackData])")
    }
}
