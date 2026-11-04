package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.loop.OrbitSet
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.audio.WavWriter
import com.snipsnap.loop.OrbitEngine
import kotlin.math.floor

/** The set owns routing, tempo and bounce duration; the kit owns the sound. */
object OrbitAquarium {
    data class Route(val settings: AquariumPerformanceSettings, val warning: String?, val tailFrames: Int)

    fun route(kit: Kit, set: OrbitSet): Route {
        val loaded = AquariumPerformance.read(kit)
        val settings = if (set.aquariumFromKit) loaded.settings else AquariumPerformanceSettings()
        val tail = if (set.aquariumFromKit && settings.enabled && !settings.settings.isBypass)
            (set.aquariumTailSeconds.toDouble() * set.sampleRate).toLong() else 0L
        require(tail <= Int.MAX_VALUE) { "Aquarium tail exceeds the frame count" }
        return Route(settings, loaded.warning, tail.toInt())
    }

    /** Refuse the complete print before rendering, rather than letting shelf import truncate it. */
    fun bounceFrames(sourceFrames: Long, tailFrames: Int, sampleRate: Int): Int {
        require(sourceFrames > 0 && tailFrames >= 0 && sampleRate > 0) { "ORBIT bounce needs a valid duration and rate" }
        require(sourceFrames <= OrbitEngine.MAX_RENDER_FRAMES.toLong() - tailFrames) { "ORBIT bounce exceeds stereo memory bounds" }
        // Match the actual resampler's floor at the shelf's MPC rate.
        val atMpcRate = floor((sourceFrames + tailFrames) * (WavWriter.MPC_SAMPLE_RATE.toDouble() / sampleRate))
        require(atMpcRate <= (SnipStore.IMPORT_MAX_SEC * WavWriter.MPC_SAMPLE_RATE).toInt()) {
            "SNIPS HOLDS ${SnipStore.IMPORT_MAX_SEC.toInt()}s INCLUDING FX TAIL. SHORTEN THE SET OR TAIL."
        }
        return sourceFrames.toInt()
    }
}
