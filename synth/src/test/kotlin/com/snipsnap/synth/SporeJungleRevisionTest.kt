package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import java.util.zip.GZIPInputStream
import kotlin.math.*
import kotlin.test.*

class SporeJungleRevisionTest {
    private val jungle=SporeSyntheticKits.Genre.JUNGLE
    @Test fun `version one keeps the published rejected sources for existing recipes`() {
        for(slot in listOf(2,4,10)) {
            val old=javaClass.getResourceAsStream("/spore-kit-v1/pad-$slot.wav.gz")!!.use { GZIPInputStream(it).use { z->WavReader.read(z.readBytes()) } }
            val recipe=SporeSyntheticKits.Recipe(jungle,slot-1,version=1)
            val rendered=SporeSyntheticKits.Recipe.fromJsonText(recipe.toJsonText()).render()
            assertEquals(old.samples.size,rendered.samples.size)
            assertTrue(old.samples.indices.all { abs(old.samples[it]-rendered.samples[it])<=3f/8388608 },"legacy pad $slot")
        }
    }
    @Test fun `heel and ghost are quieter shorter and have different shapes before pad mixing`() {
        fun tail(a:FloatArray):Double {
            val cut=(.04*Dsp.RATE).toInt()
            return a.drop(cut).sumOf{it.toDouble().pow(2)}/a.sumOf{it.toDouble().pow(2)}
        }
        for(v in listOf(.3,.65,1.0))for(t in 0..2)for((main,quiet)in listOf(0 to 1,2 to 3)) {
            val a=SporeSyntheticKits.renderPad(jungle,main,v,t)
            val b=SporeSyntheticKits.renderPad(jungle,quiet,v,t)
            assertTrue(b.peak()<a.peak()*.65,"pad $quiet: peak ${b.peak()} vs ${a.peak()}")
            assertTrue(tail(b.samples)<tail(a.samples)*.6,"pad $quiet: tail ${tail(b.samples)} vs ${tail(a.samples)}")
            val dot=a.samples.indices.sumOf{a.samples[it].toDouble()*b.samples[it]}
            val norm=sqrt(a.samples.sumOf{it.toDouble().pow(2)}*b.samples.sumOf{it.toDouble().pow(2)})
            assertTrue(abs(dot/norm)<.9,"pad $quiet: shape remains too similar")
        }
    }
    @Test fun `only three Jungle sources change across the recipe revision`() {
        for(g in SporeSyntheticKits.Genre.entries)for(p in 0..17) {
            if(g==jungle && p in listOf(1,3,9))continue
            assertContentEquals(SporeSyntheticKits.Recipe(g,p,version=1).render().samples,
                SporeSyntheticKits.Recipe(g,p,version=2).render().samples,"$g $p")
        }
    }
}
