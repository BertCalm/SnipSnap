package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.*

class BristleVoicesTest {
    @Test fun `six sources remain distinct after gain matching and every macro moves each source`() {
        assertEquals(6,Bristle.musicalVoices.size)
        val renders = Bristle.musicalVoices.associateWith { BristlePresets.forVoice(it).single().render().samples }
        for ((voice,a) in renders) for ((other,b) in renders) if (voice.ordinal < other.ordinal) {
            val gain = a.indices.sumOf { a[it].toDouble()*b[it] } / a.sumOf { it.toDouble()*it }
            val residual = a.indices.sumOf { val d=b[it]-gain*a[it]; d*d } / b.sumOf { it.toDouble()*it }
            println("BRISTLE diversity $voice $other residual=$residual")
            assertTrue(residual > .005,"$voice and $other are gain-equivalent: $residual")
        }
        for (voice in Bristle.musicalVoices) for (macro in Bristle.defaults.keys) {
            val a=Bristle.render(voice,mapOf(macro to .25f)).samples
            val b=Bristle.render(voice,mapOf(macro to .5f)).samples
            assertTrue(a.indices.maxOf { abs(a[it]-b[it]) } > .001f,"$voice $macro")
        }
    }
    @Test fun `starter is sixteen source-bound pads with a genuine three by two grid`() {
        val patches=BristleKits.patches();val pads=BristleKits.build()
        assertEquals(16,pads.size)
        assertEquals(listOf(24,27,29,31,34,36,39,41),patches.take(8).map { it.midi })
        assertEquals(Bristle.Route.entries.toSet(),patches.drop(8).map { it.voice }.toSet())
        for ((patch,pad) in patches.zip(pads)) {
            assertEquals(patch,PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch)
            assertEquals(listOf(2,2,2),pad.velocityTakes.map { it.size })
            assertContentEquals(patch.render().samples,pad.snip.samples)
            for (row in pad.velocityTakes) assertFalse(row[0].samples.contentEquals(row[1].samples))
        }
    }
    @Test fun `six voices differ in steady harmonic balance not just phase or gain`() {
        val spectra=Bristle.musicalVoices.associateWith { voice ->
            val x=BristlePresets.forVoice(voice).single().render().samples
            val amps=(1..12).map { harmonic ->
                var re=0.0;var im=0.0
                for(i in 5000 until 15000) {
                    val window=.5-.5*kotlin.math.cos(2*Math.PI*(i-5000)/9999.0)
                    val phase=2*Math.PI*harmonic*Bristle.frequencyFor(36)*i/44100
                    re+=x[i]*window*kotlin.math.cos(phase);im+=x[i]*window*kotlin.math.sin(phase)
                }
                kotlin.math.sqrt(re*re+im*im)
            }
            amps.map { it/amps.first() }
        }
        for ((voice,a) in spectra) for ((other,b) in spectra) if(voice.ordinal<other.ordinal) {
            val distance=kotlin.math.sqrt(a.indices.sumOf { val d=a[it]-b[it];d*d })
            println("BRISTLE spectral distance $voice $other=$distance")
            assertTrue(distance>.03,"$voice and $other have too similar a harmonic balance: $distance")
        }
    }
}
