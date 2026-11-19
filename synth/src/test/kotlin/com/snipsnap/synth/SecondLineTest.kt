package com.snipsnap.synth

import kotlin.test.*
import kotlin.math.*

class SecondLineTest {
    @Test fun `model one triangle retains the original audition PCM`() {
        val path=java.nio.file.Files.createTempFile("triangle-model-one", ".wav").toFile()
        try {
            val recipe=ParadeSecondLine.Recipe(ParadeSecondLine.Voice.TRIANGLE,model=1)
            val restored=ParadeSecondLine.Recipe.fromJson(recipe.json())
            com.snipsnap.audio.WavWriter.write(path,restored.render(),com.snipsnap.audio.WavWriter.BitDepth.PCM_16)
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("5712405c360d8c176e7689c46165a15d170e81ca7ff5eb370b24a9cbe3d08802",digest)
        } finally { path.delete() }
    }

    @Test fun `muted triangle releases progressively with much less late ring`() {
        fun ratio(x: FloatArray): Double {
            val early=x.take(1323).sumOf { it.toDouble()*it }/1323
            val late=x.sliceArray(8820 until 22050).sumOf { it.toDouble()*it }/13230
            return 10*log10(max(late,1e-20)/max(early,1e-20))
        }
        val open=ParadeSecondLine.render(ParadeSecondLine.Voice.TRIANGLE).samples
        val muted=ParadeSecondLine.render(ParadeSecondLine.Voice.TRIANGLE_MUTED).samples
        assertTrue(ratio(muted)<ratio(open)-12,"Muted triangle retained the open ring")
    }

    @Test fun `binary control corners stay finite bounded and silent at release`() {
        for(voice in ParadeSecondLine.Voice.entries)for(bits in 0..7) {
            val m=ParadeSecondLine.defaults().keys.withIndex().associate { (i,key)->key to ((bits shr i) and 1).toFloat() }
            val x=ParadeSecondLine.render(voice,m,if(bits%2==0).05f else 1f,bits%3).samples
            assertTrue(x.all { it.isFinite() && abs(it)<=1f },"$voice corner $bits")
            assertTrue(abs(x.average())<.005 && x.last()==0f,"$voice corner $bits DC/release")
            assertTrue(x.maxOf { abs(it) }>.01f,"$voice corner $bits silent")
        }
    }
    @Test fun `controls move each contact family and unknown controls settle`() {
        for(voice in listOf(ParadeSecondLine.Voice.BASS_OPEN,ParadeSecondLine.Voice.SNARE_CENTER,
            ParadeSecondLine.Voice.RIMSHOT,ParadeSecondLine.Voice.CYMBAL_CRASH,ParadeSecondLine.Voice.TRIANGLE,ParadeSecondLine.Voice.BELL)) {
            for(key in ParadeSecondLine.defaults().keys) {
                val a=ParadeSecondLine.render(voice,mapOf(key to .2f),.6f).samples
                val b=ParadeSecondLine.render(voice,mapOf(key to .8f),.6f).samples
                val aa=sqrt(a.sumOf { it.toDouble()*it });val bb=sqrt(b.sumOf { it.toDouble()*it })
                assertTrue(sqrt(a.indices.sumOf { (a[it]/aa-b[it]/bb).pow(2) })>.01,"$voice $key")
            }
        }
        assertEquals(ParadeSecondLine.defaults(),ParadeSecondLine.settled(mapOf("DAMP" to Float.NaN,"TUNE" to Float.POSITIVE_INFINITY,"UNKNOWN" to 1f)))
        assertFailsWith<IllegalArgumentException> { ParadeSecondLine.Recipe(ParadeSecondLine.Voice.BASS_OPEN,model=3) }
        assertFailsWith<IllegalArgumentException> { ParadeSecondLine.Recipe(ParadeSecondLine.Voice.BASS_OPEN,take=-1) }
    }
}
