package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class DrumlineRimContrastTest {
    private fun energy(x: FloatArray, from: Int, end: Int) =
        (from until minOf(end,x.size)).sumOf { x[it].toDouble()*x[it] }

    @Test fun `rimshot differs from the snare across both kit grids after level matching`() {
        for(kit in DrumlineKits.Kit.entries) {
            val patches=DrumlineKits.patches(kit)
            val snare=patches.single { it.voice==DrumlineVoice.SNARE }
            val rim=patches.single { it.voice==DrumlineVoice.RIMSHOT }
            for(v in DrumlineKits.velocities) for(take in 0 until DrumlineKits.TAKES) {
                val a=snare.render(v,take).samples;val b=rim.render(v,take).samples
                val n=(.15*Dsp.RATE).toInt()
                val aa=energy(a,0,n);val bb=energy(b,0,n)
                val corr=(0 until n).sumOf { a[it].toDouble()*b[it] }/sqrt(aa*bb)
                val start=(.025*Dsp.RATE).toInt()
                val tailRatio=(energy(b,start,n)/bb)/(energy(a,start,n)/aa)
                println("RIM ${kit.id} $v $take $corr $tailRatio")
                assertTrue(abs(corr)<.75,"${kit.id} $v/$take rim is still a near-copy: $corr")
                assertTrue(tailRatio<.55,"${kit.id} $v/$take shared wire tail still dominates: $tailRatio")
            }
        }
    }

    @Test fun `published model two remains replayable including scored rim accents`() {
        val expected=mapOf(
            DrumlineVoice.SNARE to "14d0271f30bdab5e3ce736fd00791c99e33e97402df5f6ab5b54129a7ccecc73",
            DrumlineVoice.RIMSHOT to "78d30e9ca66ba462a37f8c9d00bc373b15a6b3a50b60621330108ce711b95253",
            DrumlineVoice.ROLL to "c07c5cca4e0d7ea63b0fb5117c5321390850fac0ebce169603b4141e78afd5e0",
            DrumlineVoice.RISER to "7cd30d4fe1f0b1723e2d16b47a2e41898f9d5a90ddffebeced6649e104ffda3e",
            DrumlineVoice.TENOR_2 to "d6b96b9f464812cc24d1e4bc25f3663ead6d467e8b51e6b8b0c3493904498e11",
            DrumlineVoice.BASS_5 to "08c465f01c251f608c106662b81382a066a5a50c347b21edd85b30ad2b6acfad",
            DrumlineVoice.CRASH to "0e6e027325498bf50d6425ec5d0abd7f149e6332f252fd09767a0f706866159e"
        )
        for((voice,hash) in expected) {
            val patch=DrumlinePatch("Previous",voice,Drumline.defaults(voice),2)
            assertEquals(patch,Patches.fromJsonText(patch.toJsonText()))
            val out=ByteArrayOutputStream();WavWriter.write(out,patch.render(),WavWriter.BitDepth.PCM_24)
            val actual=MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(hash,actual,voice.name)
        }
    }

    @Test fun `snare bass tenors cymbals and snare-only figures retain model two audio`() {
        val changed=setOf(DrumlineVoice.RIMSHOT,DrumlineVoice.ROLL,DrumlineVoice.RISER)
        for(voice in DrumlineVoice.entries.filter { it !in changed })
            assertContentEquals(Drumline.render(voice,velocity=.6f,take=1,model=2).samples,
                Drumline.render(voice,velocity=.6f,take=1,model=3).samples,voice.name)
    }
}
