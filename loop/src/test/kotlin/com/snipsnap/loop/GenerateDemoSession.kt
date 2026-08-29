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
 * built from a distinct DrumSynth family. An earlier version of this generator
 * varied blocks only in decay time and RNG seed — numerically distinct
 * (confirmed by AnalyzePhasing: RMS differs interval to interval, the cycle
 * closes exactly at interval 12) but too subtle to place confidently by ear on
 * a phone speaker. This version leans on rhythm density and pitch instead,
 * which read as unmistakably different regardless of playback gear:
 *
 *  - Kick (2 blocks): block 0 is four-on-the-floor and tight; block 1 is
 *    half-time and boomy (half the hits, a much longer tail).
 *  - Snare (3 blocks): a rhythm + timbre ramp — 2, then 4, then 8 hits a bar,
 *    getting noisier each step, ending as a full roll/wash.
 *  - Hats (2 blocks): block 0 is closed 8th notes; block 1 is open quarter
 *    notes — tight ticking versus washy sustain.
 *  - Bass (1 block): fixed by design — a chain of length 1 never advances, so
 *    there is nothing to vary here; it is the still centre the other five
 *    tracks drift against.
 *  - Perc (4 blocks): a density ramp, 1/2/4/8 hits a bar — a build across the
 *    chain's four positions.
 *  - Toms (2 blocks): pure pitch, same rhythm — block 0 at 110 Hz, block 1 a
 *    perfect fifth above at 165 Hz.
 *
 * Test-scoped rather than part of the app: this is a data generator, not
 * product code, and it must never ship. Run it with:
 *
 *   ./gradlew :loop:generateDemoSession
 *
 * which writes loop.json plus its WAVs to build/demo-session (or to
 * -PoutDir=<path>). Push the result to a device with:
 *
 *   adb push build/demo-session /data/local/tmp/snipsnap-session
 *   adb shell run-as com.snipsnap.app rm -rf files/sessions/current
 *   adb shell run-as com.snipsnap.app mkdir -p files/sessions
 *   adb shell run-as com.snipsnap.app cp -r /data/local/tmp/snipsnap-session files/sessions/current
 *   adb shell rm -rf /data/local/tmp/snipsnap-session
 *
 * /data/local/tmp, not /sdcard/Download: on a scoped-storage device `run-as`
 * cannot read the app's own Download directory (it fails silently -- `cp`
 * prints "Permission denied" but the shell command still exits 0, so nothing
 * downstream notices the copy didn't happen). /data/local/tmp is world
 * readable and `run-as` can always see it. Verify the push actually landed
 * with an md5sum compare, not just a directory listing -- a stale prior
 * session's file of the same name will list fine and be silently wrong.
 */
private const val BPM = 90f
private const val BARS_PER_INTERVAL = 1
private val RATE = DrumSynth.RATE // 44,100 -- matches WavWriter's MPC-native rate

// bpm=90, 1 bar: 4 beats * 60/90s = 8/3s * 44,100 = 117,600 frames exactly, and
// it divides evenly by 2, 3, 4 and 8, so every subdivision below lands on a
// whole sample instead of rounding.
private val INTERVAL_FRAMES = (BARS_PER_INTERVAL * 4 * (60.0 / BPM) * RATE).roundToInt()

private data class TrackSpec(val name: String, val chainLength: Int, val level: Float)

// Mixer sums engaged tracks with no headroom of its own (see Mixer.mix) --
// tile()'s hits are individually clamped to +-1, but six of them summed at
// level 1 peaks well over full scale (measured ~3.1-3.9 before this fix),
// and the AudioTrack float sink clips there. Hard clipping is exactly the
// thing that would flatten the density/pitch differences this session
// exists to demonstrate, so each track is scaled down at mix time -- not
// baked into the WAVs -- to keep the summed peak comfortably under 1.0.
// Bass gets less than the rest: it's a continuous drone across the whole
// interval (no attack/decay gaps like the percussive tracks), so at equal
// level it would dominate the RMS and mask the other five tracks' changes.
private val TRACK_SPECS = listOf(
    TrackSpec("Kick", 2, level = 0.22f),
    TrackSpec("Snare", 3, level = 0.22f),
    TrackSpec("Hats", 2, level = 0.18f),
    TrackSpec("Bass", 1, level = 0.14f),
    TrackSpec("Perc", 4, level = 0.20f),
    TrackSpec("Toms", 2, level = 0.20f),
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
    return Track(name = spec.name, chain = blocks, engaged = true, level = spec.level)
}

/**
 * One block's audio: a family per track, an *obviously* different variant per
 * chain position — rhythm density or pitch, not just decay/seed, so the
 * difference reads on a phone speaker, not just in a waveform diff.
 */
private fun synthesize(trackName: String, block: Int): Snip = when (trackName) {
    "Kick" -> when (block) {
        // Block 0: tight, four-on-the-floor. Block 1: half-time, long boom.
        0 -> tile(hit = DrumSynth.kick(seconds = 0.35f, decay = 30.0), stepFrames = INTERVAL_FRAMES / 4)
        else -> tile(hit = DrumSynth.kick(seconds = 0.6f, decay = 9.0), stepFrames = INTERVAL_FRAMES / 2)
    }
    "Snare" -> {
        // A build across the chain: hits double each block (2 -> 4 -> 8 a
        // bar) while the hit itself gets noisier, ending as a roll/wash.
        val hitsPerBar = 2 shl block // 2, 4, 8
        tile(
            hit = DrumSynth.snare(decay = 24.0, noiseMix = 0.25f + block * 0.35f, seed = 10 + block),
            stepFrames = INTERVAL_FRAMES / hitsPerBar,
        )
    }
    "Hats" -> when (block) {
        // Block 0: closed, ticking 8th notes. Block 1: open, washy quarters.
        0 -> tile(hit = DrumSynth.closedHat(seed = 20), stepFrames = INTERVAL_FRAMES / 8)
        else -> tile(hit = DrumSynth.openHat(seed = 21), stepFrames = INTERVAL_FRAMES / 4)
    }
    "Bass" -> DrumSynth.tonal(
        seconds = INTERVAL_FRAMES.toFloat() / RATE,
        freq = 55.0, // chain length 1: never advances, the still centre the rest drift against
    )
    "Perc" -> {
        // A density ramp across all four blocks: 1, 2, 4, 8 hits a bar.
        val hitsPerBar = 1 shl block
        tile(hit = DrumSynth.clap(seed = 30 + block), stepFrames = INTERVAL_FRAMES / hitsPerBar)
    }
    "Toms" -> {
        // Same rhythm both blocks; only the pitch moves, a perfect fifth up.
        val freq = if (block == 0) 110.0 else 110.0 * 1.5
        tile(hit = DrumSynth.tom(seconds = 0.4f, freq = freq), stepFrames = INTERVAL_FRAMES / 2)
    }
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
