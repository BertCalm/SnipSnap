package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.synth.AquariumPerformanceSettings
import kotlin.math.floor

/** SPLIT owns routing and finite tail; KIT owns the accepted sound and tempo. */
object SplitAquarium {
    data class Settings(val fromKit: Boolean = false, val tailSeconds: Float = 3f) {
        init { require(tailSeconds.isFinite() && tailSeconds in 0f..30f) { "SPLIT FX tail must be in 0..30 seconds" } }
    }
    data class Route(val patched: Boolean, val sound: AquariumPerformanceSettings, val bpm: Double, val warning: String?) {
        val wet: Boolean get() = patched && sound.enabled && !sound.settings.isBypass
    }
    data class PrintPlan(val sourceFrames: Int, val tailFrames: Int) { val frames: Int get() = sourceFrames + tailFrames }

    fun route(kit: Kit?, settings: Settings): Route {
        val loaded = kit?.let(AquariumPerformance::read)
        return Route(settings.fromKit && kit != null,
            if (settings.fromKit) loaded?.settings ?: AquariumPerformanceSettings() else AquariumPerformanceSettings(),
            kit?.tempoBpm?.toDouble() ?: 0.0, loaded?.warning)
    }

    /** Before rendering/allocating: never silently lose body or tail at the shelf's cap. */
    fun plan(sourceFrames: Int, sampleRate: Int, settings: Settings, route: Route): PrintPlan {
        require(sourceFrames > 0 && sampleRate > 0) { "SPLIT PRINT needs nonempty audio at a valid rate" }
        val tail = if (route.wet) (settings.tailSeconds.toDouble() * sampleRate).toLong() else 0L
        val total = sourceFrames.toLong() + tail
        require(total <= Int.MAX_VALUE / 2) { "SPLIT PRINT exceeds stereo memory bounds" }
        val atMpcRate = floor(total * (WavWriter.MPC_SAMPLE_RATE.toDouble() / sampleRate))
        require(atMpcRate <= (SnipStore.IMPORT_MAX_SEC * WavWriter.MPC_SAMPLE_RATE).toInt()) {
            "SPLIT PRINT HOLDS ${SnipStore.IMPORT_MAX_SEC.toInt()}s INCLUDING FX TAIL. SHORTEN THE SOURCE OR TAIL."
        }
        return PrintPlan(sourceFrames, tail.toInt())
    }

    /** Native PadEngine's final mix clamp, before its Aquarium bus. No normalization. */
    fun fillBusInput(source: Snip, fromFrame: Int, out: FloatArray) {
        require(fromFrame >= 0 && out.size % 2 == 0)
        for (i in 0 until out.size / 2) {
            val frame = fromFrame.toLong() + i
            for (c in 0..1) {
                val value = if (frame < source.frameCount)
                    source.samples[frame.toInt() * source.channels + if (source.channels == 1) 0 else c] else 0f
                out[2 * i + c] = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
            }
        }
    }
}
