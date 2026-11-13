package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import java.util.Locale

/** The accepted sixteen-pad bank shared by factory delivery and the reproducible exporter. */
internal object OsteriaPerformanceBank {
    const val NAME = "SnipSnap Osteria Six Voice Bank"
    const val ARCHIVE_NAME = "SnipSnap-Osteria-Six-Voice-Bank.zip"
    const val PRESS_SECONDS = OsteriaPresetCandidates.GATE_SECONDS

    data class Touch(val id: String, val velocity: Float, val velocityStart: Int, val velocityEnd: Int)

    val touches = listOf(
        Touch("soft", .25f, 1, 41),
        Touch("medium", .60f, 42, 84),
        Touch("hard", 1f, 85, 127),
    )

    data class Pad(
        val slotNumber: Int,
        val candidate: OsteriaPresetCandidates.Candidate,
        val midi: Int,
    ) {
        val slot get() = String.format(Locale.ROOT, "A%02d", slotNumber)
        val id get() = slot.lowercase(Locale.ROOT)
        val noteName get() = OsteriaPerformanceBank.noteName(midi)
        val displayName get() = "${candidate.patch.name} $noteName"
        fun clipId(touch: Touch) = "$id-${touch.id}"

        /** Transpose by rendering the requested root, never by playing a WAV faster. */
        fun patch(touch: Touch): OsteriaPatch = candidate.patch.copy(
            name = displayName,
            midi = midi,
            velocity = touch.velocity,
            config = candidate.patch.config.copy(seconds = PRESS_SECONDS + candidate.patch.config.releaseSeconds),
        )
    }

    data class RenderedPad(val pad: Pad, val productionSamples: List<Snip>) {
        init { require(productionSamples.size == touches.size) }

        // SnipSnap's pad player and the MPC apply incoming velocity at playback.
        // Remove only that nominal gain from each genuine source rendering, so
        // the played hit applies dynamics once while retaining its own timbre.
        val samples = productionSamples.mapIndexed { index, snip ->
            val gain = 1f / touches[index].velocity
            Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, snip.channels, snip.sampleRate)
        }

        fun arranged(): ArrangedPad = ArrangedPad(
            snip = samples.last(),
            drumClass = DrumClass.TONAL,
            recipe = PadRecipe(patch = pad.patch(touches.last())).toJsonValue(),
            // Source calibration and voice balance survive velocity preparation.
            // Avoid applying KitAssembler's additional default -3 dB pad gain.
            level = 1f,
            oneShot = true,
            softVariants = samples.dropLast(1),
            source = mapOf("engine" to "OSTERIA", "candidateId" to pad.candidate.id,
                "bank" to "accepted-six-voice", "pressSeconds" to "$PRESS_SECONDS"),
            displayName = pad.displayName,
            colorHex = when (pad.candidate.patch.voice) {
                OsteriaVoice.BASS -> "#8b7947"
                OsteriaVoice.PLUCK -> "#ab7048"
                OsteriaVoice.REED -> "#722f37"
                OsteriaVoice.BOWED -> "#6e6651"
                OsteriaVoice.PAD -> "#466e70"
                OsteriaVoice.QUARTET -> "#5c6687"
            },
        )
    }

    fun pads(): List<Pad> {
        val candidates = OsteriaSixVoiceCandidates.candidates().associateBy { it.id }
        val layout = listOf(
            "anchor-bass" to 36, "anchor-bass" to 43,
            "roaming-bass" to 36, "roaming-bass" to 43,
            "rounded-pluck" to 48, "bright-pluck" to 48,
            "still-reed" to 48, "remembered-reed" to 48,
            "warm-bow" to 48, "rosin-bow" to 48,
            "anchored-note" to 48, "lingering-color" to 48,
            "warm-swell" to 48, "warm-swell" to 55,
            "memory-drift" to 48, "memory-drift" to 55,
        )
        return layout.mapIndexed { index, (id, midi) -> Pad(index + 1, candidates.getValue(id), midi) }
    }

    fun render(pads: List<Pad> = pads(), completed: (Pad, Touch, Snip) -> Unit = { _, _, _ -> }): List<RenderedPad> =
        pads.map { pad ->
            RenderedPad(pad, touches.map { touch ->
                pad.patch(touch).render().also { completed(pad, touch, it) }
            })
        }

    private fun noteName(midi: Int) = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")[midi % 12] + (midi / 12 - 1)
}
