package com.snipsnap.audio

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import com.snipsnap.xpm.XpmWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Generates the hardware acceptance kits under testkit/.
 *
 * Run via `./gradlew :audio:generateTestKits`. Two kits come out:
 *
 * - **SnipSnap Diag Kit** — pad N plays N beeps. This is the instrument that
 *   settles the open instrument-numbering question: tap A01 on the MPC, and if
 *   you hear two beeps instead of one, numbering is shifted by one and the
 *   writer needs `instrumentBaseIndex = 1`. Pitch also rises with pad number,
 *   so a shift is audible even without counting.
 * - **SnipSnap Test Kit** — synthesized drums on the conventional layout, with
 *   the hats sharing mute group 1. Confirms the kit is actually playable and
 *   that the choke works.
 *
 * Sample names carry their intended pad label (A01_..) so any misalignment is
 * visible on the MPC's screen as well as audible.
 */
object TestKitGenerator {

    private const val RATE = 44_100

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        writeDiagKit(File(root, "SnipSnap Diag Kit"))
        writeDrumKit(File(root, "SnipSnap Test Kit"))
    }

    private fun writeDiagKit(dir: File) {
        dir.mkdirs()
        val pads = (1..16).map { n ->
            val label = PadNoteMap.labelForPad(n)
            val name = "${label}_${n}_beeps"
            val wav = WavWriter.write(File(dir, "$name.wav"), beeps(n))
            Pad(name, WavInfo.read(wav).frameCount)
        }
        XpmWriter().writeTo(dir, DrumProgram("SnipSnap Diag Kit", pads))
        println("wrote ${dir.absolutePath} (${dir.listFiles()!!.size} files)")
    }

    private fun writeDrumKit(dir: File) {
        dir.mkdirs()
        val sounds: List<Triple<String, Snip, Int>> = listOf(
            Triple("Kick", DrumSynth.kick(), 0),
            Triple("Snare", DrumSynth.snare(), 0),
            Triple("HatClosed", DrumSynth.closedHat(), 1),
            Triple("HatOpen", DrumSynth.openHat(), 1),
            Triple("Kick2", DrumSynth.kick(seconds = 0.45f, decay = 14.0), 0),
            Triple("Clap", DrumSynth.clap(), 0),
            Triple("Perc1", DrumSynth.hat(seconds = 0.12f, decay = 60.0, seed = 11), 0),
            Triple("Perc2", DrumSynth.hat(seconds = 0.16f, decay = 45.0, seed = 12), 0),
            Triple("TomHi", DrumSynth.tom(freq = 220.0), 0),
            Triple("TomMid", DrumSynth.tom(freq = 150.0), 0),
            Triple("TomLo", DrumSynth.tom(freq = 100.0), 0),
            Triple("Snare2", DrumSynth.snare(noiseMix = 0.7f, seed = 9), 0),
            Triple("BassA", DrumSynth.tonal(seconds = 1.0f, freq = 110.0), 0),
            Triple("BassD", DrumSynth.tonal(seconds = 1.0f, freq = 146.83), 0),
            Triple("LoopShort", DrumSynth.loop(seconds = 1.5f), 0),
            Triple("LoopLong", DrumSynth.loop(seconds = 2.5f, seed = 8), 0),
        )
        val pads = sounds.mapIndexed { i, (stem, snip, muteGroup) ->
            val name = "${PadNoteMap.labelForPad(i + 1)}_$stem"
            val cleaned = Cleanup.process(snip)
            val wav = WavWriter.write(File(dir, "$name.wav"), cleaned)
            Pad(name, WavInfo.read(wav).frameCount, muteGroup = muteGroup)
        }
        XpmWriter().writeTo(dir, DrumProgram("SnipSnap Test Kit", pads))
        println("wrote ${dir.absolutePath} (${dir.listFiles()!!.size} files)")
    }

    /** N short sine bursts; pitch rises a semitone per pad so shifts are audible. */
    private fun beeps(count: Int): Snip {
        val beepFrames = (0.05f * RATE).toInt()
        val gapFrames = (0.08f * RATE).toInt()
        val fadeFrames = (0.004f * RATE).toInt()
        val out = FloatArray(count * (beepFrames + gapFrames))
        val freq = 440.0 * 2.0.pow((count - 1) / 12.0)

        for (b in 0 until count) {
            val start = b * (beepFrames + gapFrames)
            for (i in 0 until beepFrames) {
                val env = min(1f, min(i.toFloat() / fadeFrames, (beepFrames - 1 - i).toFloat() / fadeFrames))
                out[start + i] = (0.8 * env * sin(2.0 * PI * freq * i / RATE)).toFloat()
            }
        }
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
