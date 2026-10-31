package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.AquariumOfflineRenderer
import com.snipsnap.synth.AquariumRenderRecipe
import java.io.File
import kotlin.math.abs

/**
 * Source-backed finite Aquarium prints. The renderer is supplied by the host; this module
 * neither loads a native library nor substitutes a different DSP implementation on the JVM.
 * Every edit starts from the saved pre-Aquarium WAVs, including all velocity layers.
 *
 * Zero effect mixes mean REMOVE here: restore the original audio, recipe and channel count.
 * A direct call to AquariumOfflineRenderer instead honors its explicit gain/tail/fade policy.
 * Sustain-loop WAVs refuse because a fresh modulation clock and reverb tail are not a loop proof.
 */
object AquariumPadSheet {
    private val store by lazy {
        PrintedPadStore(PrintedPadStore.Format(
            namespace = ".aquarium",
            markerKey = "aquariumPad",
            descriptorKey = "render",
            stateKey = "aquariumState",
            undoKey = "aquariumUndo",
            label = "Aquarium",
            validateDescriptor = {
                require(!AquariumRenderRecipe.fromJsonValue(it).isBypass) {
                    "Stored Aquarium pads require an active effect; zero mixes mean REMOVE"
                }
            },
            validateAudio = ::validateAudio,
            validateFile = { file ->
                require(WavReader.readSmplStrict(file)?.loop == null) {
                    "Aquarium finite prints cannot preserve a sustain loop; use a finite source first"
                }
            },
            preserveSmplRoot = true,
        ))
    }

    /** Tolerant display reader. Editing/import doors still validate the complete stored envelope. */
    fun read(recipe: JsonValue.Obj?): AquariumRenderRecipe? = try {
        store.descriptor(recipe)?.let(AquariumRenderRecipe::fromJsonValue)
    } catch (_: Exception) { null }

    fun preview(
        model: KitBuilderModel,
        slot: Int,
        recipe: AquariumRenderRecipe,
        renderer: AquariumOfflineRenderer,
    ): Snip = store.preview(model, slot) { source ->
        if (recipe.isBypass) source else rendered(source, recipe, renderer)
    }

    /** Adopts new immutable WAV names only after every source file rendered and passed validation. */
    fun apply(
        model: KitBuilderModel,
        slot: Int,
        recipe: AquariumRenderRecipe,
        renderer: AquariumOfflineRenderer,
    ): KitPad = store.apply(model, slot, recipe.toJsonValue(), recipe.isBypass, "AQUARIUM") { source ->
        rendered(source, recipe, renderer)
    }

    fun remove(model: KitBuilderModel, slot: Int): KitPad = store.remove(model, slot)
    fun undo(model: KitBuilderModel, slot: Int): KitPad = store.undo(model, slot)
    fun canUndo(model: KitBuilderModel, slot: Int): Boolean = store.canUndo(model, slot)

    /** The pad REMOVE would bring back, read for the kit bin's sweep ([PrintedPads.sourcePad]). */
    internal fun sourcePad(kitDir: File, pad: KitPad): KitPad = store.sourcePad(kitDir, pad)

    /**
     * Carries a standalone Aquarium baseline when a pad moves to another kit. A baseline whose
     * prior recipe itself needs hidden editing sources refuses; copy its complete kit folder.
     */
    fun importPad(sourceDir: File, targetDir: File, original: KitPad, copied: KitPad): KitPad =
        store.importPad(sourceDir, targetDir, original, copied)

    /** Source-only check for callers that must refuse before creating a destination kit. */
    fun requireImportable(sourceDir: File, original: KitPad) = store.requireImportable(sourceDir, original)

    private fun rendered(source: Snip, recipe: AquariumRenderRecipe, renderer: AquariumOfflineRenderer): Snip {
        val expectedFrames = source.frameCount.toLong() + recipe.tailFrames(source.sampleRate)
        require(expectedFrames <= Int.MAX_VALUE / 2) { "Aquarium stereo print is too large" }
        val output = renderer.render(source, recipe)
        require(output.channels == 2 && output.sampleRate == source.sampleRate && output.frameCount.toLong() == expectedFrames) {
            "Aquarium renderer changed the saved stereo, sample-rate or tail contract"
        }
        require(output.samples.all { it.isFinite() && abs(it) <= recipe.peakCeiling }) {
            "Aquarium print exceeds its saved peak ceiling; reduce the output gain or effect settings"
        }
        val fade = recipe.fadeFrames(source.sampleRate).coerceAtMost(output.frameCount)
        if (fade > 0) require(output.samples.takeLast(2).all { it == 0f }) {
            "Aquarium renderer did not finish the saved end fade"
        }
        return output
    }

    private fun validateAudio(snip: Snip) {
        require(snip.frameCount > 0 && snip.sampleRate in AquariumRenderRecipe.MIN_SAMPLE_RATE..AquariumRenderRecipe.MAX_SAMPLE_RATE &&
            snip.samples.all { it.isFinite() && abs(it) <= 32f }) {
            "Aquarium needs finite mono or stereo audio at 8..192 kHz with sample magnitude at most 32"
        }
    }
}
