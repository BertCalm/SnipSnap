package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Shared conservative post-render export cleanup for audition and MPC files. */
internal object BrineExportTail {
    // Remove only trailing near-silence after the complete pedal chain, never the attack.
    // Scan the whole tail so a delayed return cannot be mistaken for silence between echoes.
    fun trim(input: Snip): Snip {
        val threshold=input.peak()*0.000031622777f // -90 dB relative to this hit's peak
        val last=input.samples.indexOfLast { kotlin.math.abs(it)>threshold }
        if(last<0) return input
        val end=minOf(input.frameCount,last+1+(input.sampleRate*.12f).toInt())
        if(end==input.frameCount) return input
        val x=input.samples.copyOf(end)
        Dsp.fadeTailCosine(x,.01f) // Fade only the final, already inaudible 10 ms.
        return Snip(x,1,input.sampleRate)
    }
}
