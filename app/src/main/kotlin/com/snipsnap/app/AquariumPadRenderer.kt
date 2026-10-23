package com.snipsnap.app

import com.snipsnap.audio.Snip
import com.snipsnap.synth.AquariumOfflineRenderer
import com.snipsnap.synth.AquariumRecipe
import com.snipsnap.synth.AquariumRenderRecipe

/** Blocking finite render on a worker thread, using the accepted native core without an Oboe stream. */
object AquariumPadRenderer : AquariumOfflineRenderer {
    override fun render(source: Snip, recipe: AquariumRenderRecipe): Snip {
        require(source.frameCount > 0) { "Aquarium needs a nonempty source" }
        val tail = recipe.tailFrames(source.sampleRate)
        val frames = source.frameCount.toLong() + tail
        require(frames <= NativeAquarium.maximumOutputFrames()) { "Aquarium print exceeds the native memory bound" }
        val parameters = FloatArray(AquariumRecipe.PARAMETERS.size) { index ->
            recipe.settings.parameters.getValue(AquariumRecipe.PARAMETERS[index].id)
        }
        val samples = NativeAquarium.renderOffline(
            source.samples, source.channels, source.sampleRate, parameters, recipe.settings.seed,
            recipe.bpm, tail, recipe.fadeFrames(source.sampleRate).coerceAtMost(frames.toInt()),
            recipe.outputGain, recipe.peakCeiling, 256,
        )
        check(samples.size.toLong() == frames * 2L) { "Aquarium native renderer returned an incomplete print" }
        return Snip(samples, 2, source.sampleRate)
    }
}
