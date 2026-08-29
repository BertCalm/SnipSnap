package com.snipsnap.loop

import java.io.File
import kotlin.math.sqrt

/**
 * Throwaway diagnostic for the "does it actually vary" question raised after
 * the first on-device listening pass: dropouts are gone, but does a full
 * twelve-interval cycle actually differ interval-to-interval, or is the demo
 * session accidentally playing the same thing on a loop?
 *
 * Loads a session (default: build/demo-session, same layout GenerateDemoSession
 * writes), bounces one interval past a full cycle through the real
 * bake -> mix -> sink path (Bouncer, same code playback uses), splits the
 * result back into per-interval chunks, and prints RMS + peak per interval
 * per channel. RMS is the cheap, standard loudness/content summary: two
 * intervals with different material almost never land on the same RMS by
 * chance, whereas peak alone can coincide (e.g. two different hi-hat hits
 * clipping to the same transient level). Reporting both costs nothing and
 * peak is a useful cross-check.
 *
 * Interval 12 is included specifically to verify the cycle closes: interval 0
 * and interval 12 should match (same blocks all six chains), while intervals
 * 0..11 should NOT all match each other -- that's the whole feature.
 *
 * Test-scoped, not product code. Run with:
 *
 *   ./gradlew :loop:analyzePhasing
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "build/demo-session" })
    val session = SessionStore.load(dir)
    val source = KitSampleSource(dir)

    val cycle = Arrangement.cycleIntervals(session)
    println("Loaded session from ${dir.absolutePath}")
    println("Chain lengths: ${session.tracks.map { it.chain.size }} -> cycle = $cycle intervals")

    val renderIntervals = cycle + 1 // one past the cycle, to check closure
    val bounce = Bouncer.render(session, source, intervals = renderIntervals)
    println("Bounced $renderIntervals intervals, ${bounce.frameCount} frames, ${bounce.channels}ch @ ${bounce.sampleRate}Hz")
    println()

    val framesPerInterval = session.intervalFrames
    val samplesPerInterval = framesPerInterval * bounce.channels

    data class Stats(val rmsL: Float, val rmsR: Float, val peakL: Float, val peakR: Float)

    fun statsFor(interval: Int): Stats {
        val start = interval * samplesPerInterval
        var sumSqL = 0.0
        var sumSqR = 0.0
        var peakL = 0f
        var peakR = 0f
        var i = start
        val end = start + samplesPerInterval
        while (i < end) {
            val l = bounce.samples[i]
            val r = bounce.samples[i + 1]
            sumSqL += l.toDouble() * l
            sumSqR += r.toDouble() * r
            if (kotlin.math.abs(l) > peakL) peakL = kotlin.math.abs(l)
            if (kotlin.math.abs(r) > peakR) peakR = kotlin.math.abs(r)
            i += 2
        }
        val n = framesPerInterval
        return Stats(
            rmsL = sqrt(sumSqL / n).toFloat(),
            rmsR = sqrt(sumSqR / n).toFloat(),
            peakL = peakL,
            peakR = peakR,
        )
    }

    val stats = (0 until renderIntervals).map { statsFor(it) }

    println("interval | rmsL     rmsR     | peakL    peakR")
    println("---------+---------------------+-----------------")
    for ((i, s) in stats.withIndex()) {
        val marker = if (i == cycle) "  <- should match interval 0 (cycle closes)" else ""
        println(
            "%2d       | %.6f %.6f | %.6f %.6f%s".format(i, s.rmsL, s.rmsR, s.peakL, s.peakR, marker),
        )
    }
    println()

    // Verdict: how many distinct RMS values (to 4dp) appear among intervals 0..cycle-1?
    val distinctWithinCycle = stats.take(cycle).map { "%.4f/%.4f".format(it.rmsL, it.rmsR) }.toSet()
    println("Distinct (rmsL,rmsR) signatures among the $cycle intervals of one cycle: ${distinctWithinCycle.size}")
    if (distinctWithinCycle.size <= 1) {
        println("VERDICT: ENGINE BROKEN -- every interval in the cycle is numerically identical.")
    } else {
        println("VERDICT: engine produces distinct material across the cycle (numerically).")
    }

    val closureL = kotlin.math.abs(stats[0].rmsL - stats[cycle].rmsL)
    val closureR = kotlin.math.abs(stats[0].rmsR - stats[cycle].rmsR)
    println("Cycle closure check (RMS): |rmsL[0]-rmsL[$cycle]| = %.8f, |rmsR[0]-rmsR[$cycle]| = %.8f".format(closureL, closureR))

    // RMS matching is necessary but not sufficient -- two different signals
    // can share an RMS. Confirm the actual samples of interval 0 and interval
    // `cycle` are bit-identical, which is the real claim "the cycle closes"
    // is making.
    val start0 = 0
    val startCycle = cycle * samplesPerInterval
    var sampleMismatch = -1
    for (i in 0 until samplesPerInterval) {
        if (bounce.samples[start0 + i] != bounce.samples[startCycle + i]) {
            sampleMismatch = i
            break
        }
    }
    if (sampleMismatch == -1) {
        println("Cycle closure check (samples): interval 0 and interval $cycle are bit-identical, all $samplesPerInterval samples.")
    } else {
        println("Cycle closure check (samples): MISMATCH at sample offset $sampleMismatch " +
            "(interval 0 = ${bounce.samples[start0 + sampleMismatch]}, interval $cycle = ${bounce.samples[startCycle + sampleMismatch]})")
    }
}
