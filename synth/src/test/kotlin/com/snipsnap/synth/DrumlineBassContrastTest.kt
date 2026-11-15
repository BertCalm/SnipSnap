package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class DrumlineBassContrastTest {
    // Window the body after the contact. Compare spectral energy below the head,
    // independent of output gain; a waveform correlation confuses pitch with timbre.
    private fun lowerBodyShare(x: FloatArray, tune: Float): Double {
        val start=(.02*Dsp.RATE).toInt();val n=(.18*Dsp.RATE).toInt()
        val split=125*2.0.pow((tune-.5)*.7)
        var low=0.0;var total=0.0
        for(hz in 40..800 step 4) {
            val w=2*PI*hz/Dsp.RATE;val c=2*cos(w)
            var a=0.0;var b=0.0
            for(i in 0 until n) {
                val v=x[start+i]*(.5-.5*cos(2*PI*i/(n-1)))+c*a-b
                b=a;a=v
            }
            val power=a*a+b*b-c*a*b
            total+=power;if(hz<split)low+=power
        }
        return low/total
    }
    @Test fun `small bass body separates from low tenor across kit touches and takes`() {
        for(kit in DrumlineKits.Kit.entries) {
            val patches=DrumlineKits.patches(kit)
            val bass=patches.single { it.voice==DrumlineVoice.BASS_1 }
            val tenor=patches.single { it.voice==DrumlineVoice.TENOR_6 }
            for(v in DrumlineKits.velocities) for(take in 0 until DrumlineKits.TAKES) {
                val b=lowerBodyShare(bass.render(v,take).samples,bass.macros.getValue("TUNE"))
                val t=lowerBodyShare(tenor.render(v,take).samples,tenor.macros.getValue("TUNE"))
                val old=lowerBodyShare(bass.copy(model=3).render(v,take).samples,bass.macros.getValue("TUNE"))
                println("BASS ${kit.id},$v,$take,$old,$b,$t")
                assertTrue(b-t>.20,"${kit.id} $v/$take bass body still overlaps tenor: $b vs $t")
                assertTrue(b>old+.20,"${kit.id} $v/$take lower body did not move: $old -> $b")
            }
        }
    }
    @Test fun `published model three remains replayable including bass figures`() {
        val expected=mapOf(
            DrumlineVoice.BASS_1 to "af3e79616b45651310d7c1abc92bbc022bc7a5414d848bbf7821d1f13d75b194",
            DrumlineVoice.BASS_UNISON to "23ac666a4d6fdcab909cb6ffea9f4c68fbface8f748c9103861c5ce3ea51adcf",
            DrumlineVoice.BASS_SPLIT to "a98c92c9abbc7219683be6492d33cbb670bac2778f64d7ea2ac52a48c14c7ecf",
            DrumlineVoice.RIMSHOT to "bf16bcca827da1fe17b7e3abca3a492f9894575cfd38bcb5a6221aa0a1629138"
        )
        for((voice,hash) in expected) {
            val patch=DrumlinePatch("Previous",voice,Drumline.defaults(voice),3)
            assertEquals(patch,Patches.fromJsonText(patch.toJsonText()))
            val out=ByteArrayOutputStream();WavWriter.write(out,patch.render(),WavWriter.BitDepth.PCM_24)
            assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) },voice.name)
        }
    }
    @Test fun `all other voices retain model three audio`() {
        val changed=setOf(DrumlineVoice.BASS_1,DrumlineVoice.BASS_UNISON,DrumlineVoice.BASS_SPLIT)
        for(voice in DrumlineVoice.entries.filter { it !in changed })
            assertContentEquals(Drumline.render(voice,velocity=.6f,take=1,model=3).samples,
                Drumline.render(voice,velocity=.6f,take=1,model=4).samples,voice.name)
    }
}
