package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class DrumlineRevisionTest {
    @Test fun `old recipes retain the published model one audio`() {
        val expected = mapOf(
            DrumlineVoice.SNARE to "bca1949ecf7f96ec96bb558a6ebbc295800758621b1c1f95ef0008478a20eaa6",
            DrumlineVoice.RIMSHOT to "39cf0e57b11ae99766bcbd468ef98c9765f3a40afd52b4b2bb5c99c75d5f46d8",
            DrumlineVoice.TENOR_2 to "770c2a372f6ae6c1196422c4bc5bc80365bf0e785cb8a6c8409b355827a4a5bf",
            DrumlineVoice.BASS_3 to "5a82dfbcf8940cf8c482477e113486ddd5540df2123bcb23fb766878f585e7a5",
            DrumlineVoice.CRASH to "0e6e027325498bf50d6425ec5d0abd7f149e6332f252fd09767a0f706866159e",
            DrumlineVoice.CHOKE to "bf12663c9e85772e24db391ccdec3b1ea4970b99f94f292b4d7d611c244b5324",
            DrumlineVoice.TOUCH to "382d0d4ece4c384c4d99b964388a647af34ce0e7aa2e645c91b15f39791e551b"
        )
        for((voice, hash) in expected) {
            val patch=DrumlinePatch("Legacy",voice,Drumline.defaults(voice),1)
            assertEquals(patch,Patches.fromJsonText(patch.toJsonText()))
            assertEquals(1,(patch.withMacros(patch.macros) as DrumlinePatch).model)
            val out=ByteArrayOutputStream()
            WavWriter.write(out,patch.render(),WavWriter.BitDepth.PCM_24)
            val actual=MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(hash,actual,voice.name)
            val noModel=JsonValue.Obj(patch.toJsonValue().entries - "model")
            assertFalse(noModel.entries.containsKey("model"))
            assertEquals(1,(Patches.fromJsonValue(noModel) as DrumlinePatch).model)
        }
    }
    @Test fun `cymbals are unchanged across renderer revisions`() {
        for(v in listOf(DrumlineVoice.CRASH,DrumlineVoice.CHOKE,DrumlineVoice.TOUCH))
            for(velocity in listOf(.3f,1f))
                assertContentEquals(Drumline.render(v,velocity=velocity,take=2,model=1).samples,
                    Drumline.render(v,velocity=velocity,take=2,model=2).samples,v.name)
    }
    private fun energy(x: FloatArray,from: Double,to: Double): Double {
        val start=(from*Dsp.RATE).toInt();val end=minOf(x.size,(to*Dsp.RATE).toInt())
        return (start until end).sumOf { x[it].toDouble()*x[it] }/(end-start)
    }
    private fun band(x: FloatArray,lo: Float,hi: Float): FloatArray {
        val high=Dsp.OnePole(Dsp.RATE);val low=Dsp.OnePole(Dsp.RATE)
        return FloatArray(x.size) { low.lp(x[it]-high.lp(x[it],lo),hi) }
    }
    @Test fun `bass and tenor body survives beyond the initial contact at every touch level`() {
        for(voice in DrumlineVoice.entries.filter { it.name.startsWith("TENOR_") || it.name.matches(Regex("BASS_[1-5]")) })
            for(velocity in listOf(.3f,.6f,1f)) {
                fun body(model: Int): Double {
                    val x=band(Drumline.render(voice,velocity=velocity,model=model).samples,35f,850f)
                    return energy(x,.04,.14)/energy(x,0.0,.025)
                }
                val ratio=body(2)/body(1)
                println("BODY $voice $velocity $ratio")
                assertTrue(ratio>1.2,"$voice at $velocity lost its extra sustained body: $ratio")
            }
    }
    @Test fun `snare snap and rim clack concentrate upper attack energy`() {
        for(voice in listOf(DrumlineVoice.SNARE,DrumlineVoice.RIMSHOT))
            for(velocity in listOf(.3f,.6f,1f)) {
                fun attack(model: Int): Double = (0..2).map { take ->
                    val x=Drumline.render(voice,velocity=velocity,take=take,model=model).samples
                    energy(band(x,1500f,10000f),0.0,.008)/energy(x,0.0,.15)
                }.average()
                val ratio=attack(2)/attack(1)
                println("ATTACK $voice $velocity $ratio")
                assertTrue(ratio>1.1,"$voice at $velocity lost its sharper attack: $ratio")
            }
    }
    @Test fun `revised drum families stay finite and bounded at control corners`() {
        for(voice in listOf(DrumlineVoice.SNARE,DrumlineVoice.RIMSHOT,DrumlineVoice.TENOR_1,DrumlineVoice.TENOR_6,DrumlineVoice.BASS_1,DrumlineVoice.BASS_5)) {
            val names=Drumline.macrosFor(voice).map { it.name }
            for(bits in 0 until (1 shl names.size)) {
                val macros=names.mapIndexed { i,name -> name to ((bits shr i) and 1).toFloat() }.toMap()
                val x=Drumline.render(voice,macros,velocity=if(bits%2==0).3f else 1f,take=1).samples
                assertTrue(x.all { it.isFinite() && abs(it)<=1f },"$voice $macros")
                assertTrue(x.maxOf { abs(it) }>.01f,"silent $voice $macros")
                assertTrue(abs(x.average())<.005,"DC $voice $macros")
                assertEquals(0f,abs(x.last()))
            }
        }
    }
}
