package com.snipsnap.app

import com.snipsnap.audio.Separate
import com.snipsnap.audio.Snip
import com.snipsnap.shell.Layers
import com.snipsnap.shell.SplitAquarium
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Fresh finite print of a frozen desk; no Oboe stream or running loop history is borrowed. */
object SplitAquariumRenderer {
    suspend fun render(parts: Separate.Stn, desk: Layers.Desk, settings: SplitAquarium.Settings, route: SplitAquarium.Route): Snip {
        coroutineContext.ensureActive()
        val plan = SplitAquarium.plan(parts.sines.frameCount, parts.sines.sampleRate, settings, route)
        if (route.patched) require(plan.frames <= NativeAquarium.maximumOutputFrames()) {
            "SPLIT FX PRINT exceeds the native stereo memory bound. Shorten the source or tail."
        }
        val source = Layers.render(parts, desk)
        coroutineContext.ensureActive()
        if (!route.patched) return source // Preserve the legacy dry print, including floating overs.
        val output = FloatArray(plan.frames * 2)
        val block = FloatArray(256 * 2)
        AquariumPerformanceProcessor(source.sampleRate, route.sound, initialBpm = route.bpm).use { bus ->
            for (from in 0 until plan.frames step 256) {
                coroutineContext.ensureActive()
                SplitAquarium.fillBusInput(source, from, block)
                bus.process(block, route.bpm)
                block.copyInto(output, from * 2, 0, minOf(block.size, output.size - from * 2))
            }
            check(!bus.faulted) { "Aquarium faulted; SPLIT print was not saved" }
        }
        return Snip(output, 2, source.sampleRate)
    }
}
