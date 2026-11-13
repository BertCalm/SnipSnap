package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic format/admission fixtures only. Repeated test cycles are not native-history evidence. */
internal object MurmurSelectedSynthetic {
    fun capture(recipe: MurmurSelectedInstrumentSource.CellRecipe): MurmurSelectedInstrumentSource.Capture {
        val held = recipe.outputPolicy == MurmurSelectedInstrumentSource.OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1
        val native = FloatArray(512) { (.12 * sin(2 * PI * it / 64)).toFloat() }
        val samples = if (held) native + native else native.copyOf().also { it[it.lastIndex] = 0f }
        if (held) for (i in 0 until recipe.onsetFrames) {
            samples[i] *= ((1 - cos(PI * i / (recipe.onsetFrames - 1))) * .5).toFloat()
        }
        val start = if (held) native.size else 0
        return MurmurSelectedInstrumentSource.Capture(recipe, Snip(samples, 1, WavWriter.MPC_SAMPLE_RATE), start, samples.size,
            MurmurSelectedInstrumentSource.floatSha256(native),
            MurmurSelectedInstrumentSource.floatSha256(if (held) native else floatArrayOf()),
            MurmurSelectedInstrumentSource.floatSha256(samples),
            if (held) Keys.seamError(samples, start) else 0.0, Keys.midiHz(recipe.midi).toDouble())
    }
}
