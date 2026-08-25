package com.snipsnap.kit

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * One captured note → a playable instrument. MPC keygroups pitch the
 * sample themselves, so a single pitched snip plus its detected root is a
 * full-range chromatic instrument — sample one note of anything, play it
 * up and down the keys.
 *
 * Unpitched material is refused with a reason, never guessed at: an
 * instrument rooted on noise would be wrong on every key at once.
 */
object OneNote {

    /** Below this the detector is guessing; refuse rather than mistune. */
    const val MIN_CONFIDENCE = 0.5f

    data class Result(
        val program: KeygroupProgram,
        val rootMidi: Int,
        /** "A2", "C#4" — display name of the root. */
        val rootName: String,
        val detectedHz: Float,
        val confidence: Float,
        /** The sample stem the program references. */
        val sampleStem: String,
    )

    /**
     * Build the program from a pitched snip. Throws
     * [IllegalArgumentException] with the reason when no confident pitch
     * is found.
     */
    fun program(name: String, snip: Snip): Result {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        require(snip.frameCount > 0) { "empty snip" }
        val est = Pitch.detect(snip)
            ?: throw IllegalArgumentException(
                "no pitch found - a one-note instrument needs a pitched note, not a hit",
            )
        require(est.confidence >= MIN_CONFIDENCE) {
            "pitch too uncertain (%.0f Hz at confidence %.2f, want >= %.2f) - ".format(
                est.hz, est.confidence, MIN_CONFIDENCE,
            ) + "try a cleaner sustained note"
        }
        val rootMidi = Scales.hzToMidi(est.hz).roundToInt().coerceIn(0, 127)
        val stem = Names.sanitizeStem("${name.filter { !it.isWhitespace() }}_${Scales.nameOf(rootMidi)}")
        val program = KeygroupProgram(
            name,
            listOf(
                Keygroup(
                    lowNote = 0,
                    highNote = 127,
                    rootNote = rootMidi,
                    layers = listOf(
                        VelocityLayer(stem, snip.frameCount.toLong(), velStart = 0, velEnd = 127),
                    ),
                ),
            ),
            volumeRelease = 0.35f,
        )
        return Result(program, rootMidi, Scales.nameOf(rootMidi), est.hz, est.confidence, stem)
    }

    /**
     * Build and write the instrument in the dual-generation layout the
     * suite ships: `<Name>.xty` beside `<Name>_[TrackData]/` holding the
     * WAV and the MPC 2 `.xpm` twin.
     */
    fun export(name: String, snip: Snip, destRoot: File, overwrite: Boolean = false): Result {
        val result = program(name, snip)
        destRoot.mkdirs()
        val xty = File(destRoot, "$name.xty")
        val dataDir = File(destRoot, Mpc3TrackWriter.trackDataDirName(name))
        if ((xty.exists() || dataDir.exists()) && !overwrite) {
            throw IOException("destination already exists: $xty (pass overwrite=true to replace same-named files)")
        }
        dataDir.deleteRecursively()
        dataDir.mkdirs()
        WavWriter.write(File(dataDir, "${result.sampleStem}.wav"), snip)
        Mpc3TrackWriter().writeKeygroupTo(destRoot, result.program)
        KeygroupWriter().writeTo(dataDir, result.program)
        return result
    }
}
