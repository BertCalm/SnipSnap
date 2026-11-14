package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.test.*
class BackwashProcessingTest {
    @Test fun `stronger feedback decays without limiting`() {
        val impulse=FloatArray(44100*3);impulse[0]=1f
        for(v in BackwashVoice.entries){
            val y=BackwashProcessingStudy.chorus(Snip(impulse,1,44100),v).samples
            assertTrue(y.all{it.isFinite()&&abs(it)<4.66f})
            assertTrue(y.sliceArray(2205..8820).any{abs(it)>.001f})
            assertTrue(y.takeLast(44100).maxOf{abs(it)}<1e-6f)
        }
    }
    @Test fun `each treatment changes the rendered snare beyond gain alone`() {
        val v=BackwashVoice.SNARE
        fun normalize(x:FloatArray):FloatArray {val rms=sqrt(x.sumOf{it.toDouble()*it}/x.size);return FloatArray(x.size){(x[it]/rms).toFloat()}}
        val ref=normalize(Backwash.render(v).samples)
        for(t in BackwashTreatment.entries.filter{it!=BackwashTreatment.REFERENCE}){
            val changed=normalize(BackwashProcessingStudy.render(v,emptyMap(),1f,t).samples)
            val difference=sqrt((0 until minOf(ref.size,changed.size)).sumOf{(ref[it]-changed[it]).toDouble().pow(2)}/minOf(ref.size,changed.size))
            assertTrue(difference>.1,"$t is too close to reference after RMS matching")
        }
    }
}
