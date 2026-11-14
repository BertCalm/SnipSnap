package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitStore
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.*
import kotlin.test.*

class TempestExpansionTest {
    private val voices = Tempest.EXPANSION_VOICES
    private fun energy(x: FloatArray, a: Double, b: Double): Double {
        val from = (a*Dsp.RATE).toInt(); val to = (b*Dsp.RATE).toInt()
        return (from until to).sumOf { x[it].toDouble().pow(2) }/(to-from)
    }
    @Test fun `wide noisy drive corners retain the measured voltage guard`() {
        assertEquals(.8,TempestPercussion.driveVoltageFor(TempestVoice.SNARE,1.0,1.0),1e-12)
        assertEquals(2.6,TempestPercussion.driveVoltageFor(TempestVoice.SNARE,0.0,1.0),1e-12)
        assertEquals(8.0/1.5,TempestPercussion.driveVoltageFor(TempestVoice.CLAP,0.0,1.0),1e-12)
        for (v in voices) for (s in listOf(0.0,.5,1.0)) for (f in listOf(0.0,.5,1.0))
            assertTrue(TempestPercussion.driveVoltageFor(v,s,f)>0)
    }
    @Test fun `diagnostic capture preserves exact new audio and records finite filter input`() {
        for (v in voices) {
            val capture=FloatArray(Tempest.OUTPUT_FRAMES*4)
            val measured=TempestPercussion.synthesize(v,emptyMap(),Dsp.RATE*4,capture)
            assertContentEquals(Tempest.synthesize(v,emptyMap(),Dsp.RATE*4),measured)
            assertTrue(capture.all { it.isFinite() } && capture.any { abs(it)>.01f })
        }
    }
    @Test fun `all new six-control corners stay finite bounded and land before the fade`() {
        val keys = listOf("TUNE", "SOURCE", "FILTER", "DRIVE", "SPACE", "DECAY")
        for (v in voices) for (bits in 0 until 64) {
            val m = keys.mapIndexed { i, k -> k to if (bits and (1 shl i) == 0) 0f else 1f }.toMap()
            val raw = Tempest.synthesize(v, m, Dsp.RATE*4)
            assertTrue(raw.all { it.isFinite() && abs(it)<5f }, "$v $m raw bound")
            val attack = (0 until Dsp.RATE*4/5).sumOf { raw[it].toDouble().pow(2) }/(Dsp.RATE*4/5)
            val tail = raw.takeLast(Dsp.RATE*4/100).sumOf { it.toDouble().pow(2) }/(Dsp.RATE*4/100)
            assertTrue(tail < attack*1e-5, "$v $m pre-fade tail $tail vs $attack")
            val x = Tempest.finish(raw)
            assertEquals(Tempest.OUTPUT_FRAMES, x.size)
            assertTrue(x.all { it.isFinite() && it in -1f..1f })
            assertTrue(x.maxOf { abs(it) } > .01f)
            assertTrue(abs(x.average()) < .01, "$v $m DC")
            assertTrue(Loudness.of(Snip(x,1,Dsp.RATE)) >= .9f*Dsp.MELODIC_LOUDNESS_TARGET || x.maxOf { abs(it) }>=.95f)
        }
    }
    @Test fun `every new control moves at each adjacent quarter after output leveling`() {
        val inactive=mutableListOf<String>()
        for (v in voices) for (key in listOf("TUNE", "SOURCE", "FILTER", "DRIVE", "SPACE", "DECAY")) {
            val renders = listOf(0f,.25f,.5f,.75f,1f).map { Tempest.render(v,mapOf(key to it)).samples }
            for ((a,b) in renders.zipWithNext()) {
                val distance = a.indices.sumOf { (a[it]-b[it]).toDouble().pow(2) }/a.sumOf { it.toDouble().pow(2) }
                if (distance<=.03) inactive += "$v $key distance $distance"
            }
        }
        assertTrue(inactive.isEmpty(),inactive.joinToString("; "))
    }
    @Test fun `new voices are deterministic and ignore unknown or reserved values`() {
        for (v in voices) {
            val x = Tempest.render(v).samples
            assertContentEquals(x,Tempest.render(v).samples)
            for (hold in listOf(0f,.5f,1f)) assertContentEquals(x,Tempest.render(v,mapOf("HOLD" to hold,"UNKNOWN" to .8f)).samples)
            for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))
                assertContentEquals(x,Tempest.render(v,Tempest.defaults(v).mapValues { bad }).samples)
            val patch = TempestPatch("Trial",v,Tempest.defaults(v))
            assertEquals(3,patch.model)
            assertEquals(patch,Patches.fromJsonText(patch.toJsonText()))
            assertContentEquals(x,PadRecipe.fromJsonValue(PadRecipe(patch).toJsonValue()).render().samples)
            assertTrue(Velocity.atVelocity(patch,.3f).samples.all { it.isFinite() })
        }
    }
    @Test fun `new voices refuse legacy models while approved voices retain their default`() {
        for (v in voices) {
            for (model in listOf(0,1,2,4)) {
                assertFailsWith<IllegalArgumentException> { Tempest.render(v,model=model) }
                assertFailsWith<IllegalArgumentException> { TempestPatch("Invalid",v,emptyMap(),model) }
            }
            val p=TempestPatch("Trial",v,emptyMap())
            assertFailsWith<IllegalArgumentException> { TempestPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries-"model")) }
        }
        for (v in Tempest.APPROVED_VOICES) {
            assertEquals(2,TempestPatch("Approved",v,emptyMap()).model)
            assertContentEquals(Tempest.render(v,model=2).samples,Tempest.render(v,model=3).samples)
        }
    }
    @Test fun `DECAY extends the body independently of SPACE`() {
        for (v in voices) {
            fun ratio(d: Float): Double {
                val x=Tempest.render(v,mapOf("DECAY" to d,"SPACE" to 0f,"DRIVE" to 0f)).samples
                return energy(x,.08,.30)/energy(x,.0,.035)
            }
            assertTrue(ratio(.8f)>2*ratio(.1f), "$v decay ${ratio(.1f)} vs ${ratio(.8f)}")
        }
    }
    @Test fun `clap produces three separate early strikes before its release`() {
        val source=.45f;val spacing=.008+.022*source
        val x=Tempest.render(TempestVoice.CLAP,mapOf("SOURCE" to source,"SPACE" to 0f,"DRIVE" to 0f)).samples
        for (n in 0..2) {
            val peak=energy(x,n*spacing+.001,n*spacing+.006)
            val valley=energy(x,n*spacing+.012,n*spacing+.016)
            assertTrue(peak>1.3*valley,"clap burst $n peak $peak valley $valley")
        }
    }
    @Test fun `settled tom body retains its pitched role`() {
        for (tune in listOf(.25f,.5f,.75f)) {
            val x=Tempest.render(TempestVoice.TOM,mapOf("TUNE" to tune,"SOURCE" to 0f,"FILTER" to 1f,"DRIVE" to 0f,"SPACE" to 0f,"DECAY" to .7f))
            val p=assertNotNull(Pitch.detect(x,fromSec=.15f,windowSec=.15f))
            val cents=1200*log2(p.hz/Tempest.frequencyFor(tune))
            assertTrue(abs(cents)<35,"tom $tune cents $cents")
        }
    }
    @Test fun `accepted model three recipes are present in the factory roster`() {
        assertEquals(22,TempestPresets.all().size)
        for (v in voices) assertEquals(TempestExpansion.forVoice(v),Presets.forVoice("TEMPEST",v.name))
        for (p in TempestExpansion.fullKitPatches()) assertEquals(p,Patches.fromJsonText(p.toJsonText()))
    }
    @Test fun `full sixteen pad kit covers all six roles with exact recipe and export replay`() {
        val arranged=SynthKits.tempestFullAudition()
        assertEquals(16,arranged.size)
        val counts=arranged.map { PadRecipe.fromJsonValue(it!!.recipe!!).patch!!.voiceName }.groupingBy { it }.eachCount()
        assertEquals(mapOf("KICK" to 2,"SNARE" to 2,"CLAP" to 2,"TOM" to 3,"HAT" to 3,"METAL" to 4),counts)
        for (a in arranged.filterNotNull()) {
            val recipe=PadRecipe.fromJsonValue(a.recipe!!)
            assertNull(recipe.fx);assertNull(recipe.board)
            assertContentEquals(a.snip.samples,recipe.render().samples)
            assertEquals(Classifier.classify(a.snip).drumClass,a.drumClass)
        }
        val root=kotlin.io.path.createTempDirectory("tempest-full-test").toFile()
        try {
            val zip=TempestKitGenerator.generate(File(root,"kit"),fullKit=true)
            val data=File(root,"kit").walkTopDown().single { it.name=="kit.json" }.parentFile
            val kit=KitStore.load(data)
            assertEquals((1..16).toList(),kit.pads.map { it.slot })
            for (pad in kit.pads) {
                val expected=File(root,"expected.wav")
                WavWriter.write(expected,PadRecipe.fromJsonValue(pad.recipe!!).render(),WavWriter.BitDepth.PCM_24)
                assertContentEquals(expected.readBytes(),File(data,pad.sampleFile).readBytes(),pad.displayName)
                assertTrue(pad.oneShot && !pad.loop && pad.velocityLayers.isEmpty())
                assertEquals(if (pad.slot in 10..12) 1 else 0,pad.muteGroup)
            }
            ZipFile(zip).use { z ->
                val names=z.entries().asSequence().map { it.name }.toList()
                assertEquals(16,names.count { it.endsWith(".wav") })
                assertEquals(1,names.count { it.endsWith(".xtd") })
                assertEquals(1,names.count { it.endsWith(".xpm") })
            }
        } finally { root.deleteRecursively() }
    }
}
