package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Pitch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.test.*

class BristleTest {
    @Test fun `hard touch preserves accepted Python raw source within Float tolerance`() {
        for (route in Bristle.anchors) for (slide in listOf(0.0,.65)) {
            val bytes = javaClass.getResourceAsStream("/bristle/${route}_${slide}.f32")!!.readBytes()
            val ref = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val raw = Bristle.synthesize(Bristle.Recipe(route,macros=mapOf("SLIDE" to slide.toFloat())))
            var worst=0.0
            for (i in 0 until ref.limit()) worst=max(worst,abs(raw[i*16]-ref.get(i)).toDouble())
            println("BRISTLE Python raw $route slide=$slide maxError=$worst")
            assertTrue(worst < .0003, "$route $slide error $worst")
        }
    }
    @Test fun `every bass note has finite bounded output and stable root`() {
        for (route in Bristle.Route.entries) for (midi in 24..48) {
            val s=Bristle.Recipe(route,midi).render()
            assertEquals(44100,s.samples.size)
            assertTrue(s.samples.all { it.isFinite() && abs(it)<=.991f })
            assertTrue(abs(s.samples.average())<.005)
            // The shared detector starts at 40 Hz; low C1..D#1 are checked by
            // measuring their periodicity at their known period, not pretending it detects them.
            val period=(44100/Bristle.frequencyFor(midi)).roundToInt()
            val x=s.samples
            var error=0.0; var energy=0.0
            for(i in 7000..13000) { error+=(x[i+period]-x[i]).toDouble().pow(2); energy+=x[i].toDouble().pow(2) }
            assertTrue(error/energy < .01,"periodic root $midi")
            if(midi>=28) {
                val p=assertNotNull(Pitch.detect(s, .15f,.2f))
                assertTrue(abs(1200*log2(p.hz/Bristle.frequencyFor(midi)))<15,"$route $midi ${p.hz}")
            }
        }
    }
    @Test fun `soft touch changes brightness and level without changing hard source`() {
        for(route in Bristle.Route.entries) {
            val soft=Bristle.Recipe(route,velocity=.35f).render()
            val hard=Bristle.Recipe(route).render()
            val a=FeatureExtractor.extract(soft).centroidHz
            val b=FeatureExtractor.extract(hard).centroidHz
            println("BRISTLE $route soft centroid=$a hard=$b")
            assertTrue(a < b*.95f)
            assertTrue(soft.samples.maxOf { abs(it) } < hard.samples.maxOf { abs(it) })
            assertTrue(Bristle.Recipe(route,velocity=0f).render().samples.all { it==0f })
        }
    }
    @Test fun `recipes preserve routing touch and controls and refuse incompatible versions`() {
        for(route in Bristle.Route.entries) {
            val r=Bristle.Recipe(route,31,.6f,mapOf("SLIDE" to .4f,"HOLD" to .2f))
            val saved=Bristle.Recipe.fromJsonText(r.toJsonText())
            assertEquals(r,saved)
            assertContentEquals(r.render().samples,saved.render().samples)
        }
        assertFailsWith<IllegalArgumentException> { Bristle.Recipe(model=2) }
        assertFailsWith<IllegalArgumentException> { Bristle.Recipe(velocity=Float.NaN) }
        assertContentEquals(Bristle.render(Bristle.Route.A).samples,
            Bristle.render(Bristle.Route.A,mapOf("GRIT" to Float.NaN,"UNKNOWN" to 1f)).samples)
    }
    @Test fun `corner states remain bounded before normalization and controls move`() {
        for(route in Bristle.Route.entries) for(bits in 0 until 32) {
            val m=listOf("WEIGHT","GRIT","DARK","BITE","SLIDE").mapIndexed { i,k -> k to ((bits shr i) and 1).toFloat() }.toMap()
            val raw=Bristle.synthesize(Bristle.Recipe(route,macros=m))
            assertTrue(raw.all { it.isFinite() && abs(it)<4f })
        }
        for(k in Bristle.defaults.keys) {
            val a=Bristle.render(Bristle.Route.A,mapOf(k to .25f)).samples
            val b=Bristle.render(Bristle.Route.A,mapOf(k to .5f)).samples
            assertTrue(a.indices.maxOf { abs(a[it]-b[it]) }>.001f,k)
        }
    }
}
