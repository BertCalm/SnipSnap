package com.snipsnap.loop

import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Throwaway generator for Task 7's device check.
 *
 * Nothing in this repo yet writes a playable session to disk — SessionStore
 * only reads and writes what it's handed. This synthesises one: six tracks
 * with chain lengths 2, 3, 2, 1, 4, 2 (LCM 12, the spec's worked example), each
 * built from a distinct DrumSynth family so every track is audibly its own
 * instrument and every block within a chain is audibly its own variant — the
 * whole point being that the phasing is something a listener can actually
 * track by ear.
 *
 * Test-scoped rather than part of the app: this is a data generator, not
 * product code, and it must never ship. Run it with:
 *
 *   ./gradlew :loop:generateDemoSession
 *
 * which writes loop.json plus its WAVs to build/demo-session (or to
 * -PoutDir=<path>). Push the result to a device with:
 *
 *   adb push build/demo-session /sdcard/Download/snipsnap-session
 *   adb shell run-as com.snipsnap.app mkdir -p files/sessions
 *   adb shell run-as com.snipsnap.app cp -r /sdcard/Download/snipsnap-session files/sessions/current
 */
private const val BPM = 90f
private const val BARS_PER_INTERVAL = 1
private val RATE = DrumSynth.RATE // 44,100 -- matches WavWriter's MPC-native rate

// bpm=90, 1 bar: 4 beats * 60/90s = 8/3s * 44,100 = 117,600 frames exactly, and
// it divides evenly by 2, 3, 4 and 8, so every subdivision below lands on a
// whole sample instead of rounding.
private val INTERVAL_FRAMES = (BARS_PER_INTERVAL * 4 * (60.0 / BPM) * RATE).roundToInt()

private data class TrackSpec(val name: String, val chainLength: Int)

private val TRACK_SPECS = listOf(
    TrackSpec("Kick", 2),
    TrackSpec("Snare", 3),
    TrackSpec("Hats", 2),
    TrackSpec("Bass", 1),
    TrackSpec("Perc", 4),
    TrackSpec("Toms", 2),
)

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "build/demo-session" })
    outDir.mkdirs()

    val tracks = TRACK_SPECS.map { spec -> buildTrack(spec, outDir) }
    val session = Session(
        tracks = tracks,
        bpm = BPM,
        barsPerInterval = BARS_PER_INTERVAL,
        sampleRate = RATE,
    )
    val cycle = Arrangement.cycleIntervals(session)
    SessionStore.save(session, outDir)

    println("Wrote demo session to ${outDir.absolutePath}")
    println("Chain lengths: ${TRACK_SPECS.map { it.chainLength }} -> cycle = $cycle intervals")
}

private fun buildTrack(spec: TrackSpec, outDir: File): Track {
    val blocks = (0 until spec.chainLength).map { b ->
        val fileName = "${spec.name.lowercase()}_$b.wav"
        WavWriter.write(File(outDir, fileName), synthesize(spec.name, b))
        LoopBlock(fileName)
    }
    return Track(name = spec.name, chain = blocks, engaged = true)
}

/** One block's audio: a family per track, a variant per chain position. */
private fun synthesize(trackName: String, block: Int): Snip = when (trackName) {
    "Kick" -> tile(
        hit = DrumSynth.kick(seconds = 0.35f, decay = 20.0 + block * 6.0),
        stepFrames = INTERVAL_FRAMES / 4, // four kicks a bar
    )
    "Snare" -> tile(
        hit = DrumSynth.snare(decay = 24.0, noiseMix = 0.4f + block * 0.15f, seed = 10 + block),
        stepFrames = INTERVAL_FRAMES / 2, // backbeat-ish, twice a bar
    )
    "Hats" -> tile(
        hit = DrumSynth.closedHat(seed = 20 + block),
        stepFrames = INTERVAL_FRAMES / 8, // eighth notes
    )
    "Bass" -> DrumSynth.tonal(
        seconds = INTERVAL_FRAMES.toFloat() / RATE,
        freq = 55.0 * (1.0 + block * 0.25), // only one block exists (chain length 1)
    )
    "Perc" -> tile(
        hit = DrumSynth.clap(seed = 30 + block),
        stepFrames = INTERVAL_FRAMES / 4,
    )
    "Toms" -> tile(
        hit = DrumSynth.tom(seconds = 0.4f, freq = 110.0 + block * 40.0),
        stepFrames = INTERVAL_FRAMES / 2,
    )
    else -> error("no synth for track '$trackName'")
}

/** Repeat [hit] every [stepFrames] across exactly one interval, summed and clamped. */
private fun tile(hit: Snip, stepFrames: Int): Snip {
    val out = FloatArray(INTERVAL_FRAMES)
    var pos = 0
    while (pos < INTERVAL_FRAMES) {
        val n = minOf(hit.frameCount, INTERVAL_FRAMES - pos)
        for (i in 0 until n) out[pos + i] += hit.samples[i]
        pos += stepFrames
    }
    for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
    return Snip(out, 1, RATE)
}
