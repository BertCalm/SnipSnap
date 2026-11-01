package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class CairnHoldTest {
    private val middle = mapOf("TUNE" to .5f, "WEIGHT" to .5f, "SLOPE" to .4f,
        "GROOVE" to .35f, "FRICTION" to .3f, "BODY" to .4f, "HOLD" to 1f)

    @Test fun `powered gestures spend real primary input and keep voice identities`() {
        for (v in CairnVoice.entries) for (m in listOf(middle, Cairn.defaults(v) + ("HOLD" to 1f),
            middle.mapValues { if (it.key == "HOLD") 1f else 0f }, middle.mapValues { 1f })) {
            val r = Cairn.motion(v, m)
            assertTrue(r.suppliedWork > 0)
            assertTrue(r.settledCycles in 3..8 && r.cycleResidual < 1e-8)
            assertTrue(r.largestEnergyIncrease < 1e-9 && r.dissipatedWork >= 0)
            assertTrue(r.finalEnergy + r.withdrawnWork <= r.initialEnergy + r.suppliedWork + 1e-8)
            assertTrue(r.events.isNotEmpty() && r.events.first().stone == 0)
            assertEquals(r.events.size, r.precedingEvents.size)
            assertTrue(r.events.zip(r.precedingEvents).all { (a,b) -> a.stone == b.stone && abs(a.seconds-b.seconds)<1e-7 })
            val isolated = Cairn.motion(v, m, disableCoupling = true)
            assertEquals(0, isolated.events.count { it.stone > 0 })
        }
        val knock = Cairn.motion(CairnVoice.KNOCK, middle)
        val answer = Cairn.motion(CairnVoice.ANSWER, middle)
        assertEquals(0, knock.events.count { it.stone > 0 })
        assertTrue(answer.events.count { it.stone > 0 } >= 6)
        assertTrue(knock.periodSeconds - knock.events.last().seconds > 1.0)
        assertTrue(answer.events.first { it.stone > 0 }.seconds > answer.events.first().seconds + .15)
    }

    @Test fun `real preceding cycles close at three notes without fades or copied audio`() {
        for (v in CairnVoice.entries) for (note in listOf(48,60,72)) {
            val r = Cairn.renderLoop(v, midi = note)
            val d = r.diagnostics
            assertEquals((Cairn.loopSeconds(v)*Cairn.RATE).roundToInt(), r.samples.size)
            assertEquals(r.samples.size + (if(v == CairnVoice.TUMBLE) Cairn.RATE else Cairn.RATE/4), r.full.size)
            assertContentEquals(r.full.copyOfRange(d.loopStartFrame,r.full.size), r.samples)
            assertTrue(d.seamError!! < Keys.MAX_SEAM_ERROR)
            assertTrue(r.samples.all { it.isFinite() } && r.raw.all { it.isFinite() })
            assertTrue(r.samples.maxOf { abs(it) } in .01f.. .991f)
            assertTrue(abs(r.samples.average()) < .002)
            val snip = Snip(r.samples,1,Cairn.RATE)
            assertEquals(DrumClass.LOOP, Classifier.classify(snip).drumClass)
            assertEquals(DrumClass.LOOP, Cairn.drumClassFor(v,middle))
            val reference=Cairn.inspect(v,Cairn.defaults(v)+("HOLD" to 1f),note,
                options=Cairn.Options(fullLoopPreroll=true))
            val relative=sqrt(r.samples.indices.sumOf { (r.samples[it]-reference.samples[it]).toDouble().pow(2) }/
                reference.samples.sumOf { it.toDouble().pow(2) })
            assertTrue(relative<1e-5,"$v MIDI$note sparse preroll differs from full-cycle reference: $relative")
            val changed = r.full.copyOf()
            for (i in changed.size-256 until changed.size) changed[i] *= 1.15f
            assertTrue(Keys.seamError(changed,d.loopStartFrame) > Keys.MAX_SEAM_ERROR,
                "seam guard accepted a changed real continuation")
            println("CAIRN HOLD $v MIDI$note seam=${d.seamError} cycles=${d.motion.settledCycles}")
        }
    }

    @Test fun `loop controls corners soft velocity and silence stay bounded and deterministic`() {
        for (v in CairnVoice.entries) {
            for (body in listOf(.25f,.75f)) for (friction in listOf(.25f,.75f)) {
                val r = Cairn.inspect(v,middle + mapOf("BODY" to body,"FRICTION" to friction))
                assertTrue(r.diagnostics.seamError!! < Keys.MAX_SEAM_ERROR)
                assertTrue(r.samples.all { it.isFinite() })
            }
            val silent = Cairn.inspect(v,middle,velocity=0f)
            assertTrue(silent.samples.all { it==0f } && silent.diagnostics.motion.events.isEmpty())
            val soft = Cairn.inspect(v,middle,velocity=.5f)
            val hard = Cairn.inspect(v,middle)
            assertTrue(soft.diagnostics.motion.suppliedWork < hard.diagnostics.motion.suppliedWork)
            assertNotEquals(soft.diagnostics.motion.events, hard.diagnostics.motion.events)
            Cairn.clearMotionCache()
            val a=Cairn.inspect(v,middle); assertFalse(a.diagnostics.mechanicsCacheHit)
            val tuned=Cairn.inspect(v,middle+("TUNE" to .75f)); assertTrue(tuned.diagnostics.mechanicsCacheHit)
            assertEquals(a.diagnostics.motion,tuned.diagnostics.motion)
            val shot=Cairn.inspect(v,middle+("HOLD" to 0f)); assertFalse(shot.diagnostics.mechanicsCacheHit)
            assertEquals(0.0,shot.diagnostics.motion.periodSeconds)
            Cairn.clearMotionCache(); assertContentEquals(a.samples,Cairn.inspect(v,middle).samples)
        }
    }

    @Test fun `light ANSWER reinforces the root without moving its frequency or the approved defaults`() {
        for (v in listOf(CairnVoice.KNOCK, CairnVoice.ANSWER)) {
            assertContentEquals(Cairn.render(v,model=1).samples,Cairn.render(v,model=2).samples)
        }
        for (note in listOf(48,60,72)) {
            val m=middle+mapOf("WEIGHT" to 0f,"HOLD" to 0f)
            val old=Cairn.inspect(CairnVoice.ANSWER,m,note,model=1)
            val current=Cairn.inspect(CairnVoice.ANSWER,m,note)
            assertEquals(old.diagnostics.requestedHz,current.diagnostics.requestedHz)
            assertEquals(old.diagnostics.motion.events,current.diagnostics.motion.events)
            val p=assertNotNull(Pitch.detect(Snip(current.samples,1,Cairn.RATE),fromSec=.2f,windowSec=.25f))
            val cents=1200*log2(p.hz/Cairn.frequencyFor(note))
            println("CAIRN light ANSWER MIDI$note $cents cents confidence=${p.confidence}")
            assertTrue(abs(cents)<12 && p.confidence>=.3f)
        }
    }

    @Test fun `model one defaults match the six preserved audition WAV hashes`() {
        val hashes=listOf(
            "a9cad2db5166bdd4188317117c1e996f3fd9a86a10188379b7e80ab3946d9646",
            "81955edcc549e8a851d651b93b48b81755382603232c53ca63a5b220d89f66f6",
            "627c5c8a87ddc25ebbd67f969569dfe6210d32bafe64d18d9e065488827ea23c",
            "07b1905ad53745f505ea9e6788907941e5161351bd25892e63467afd4ad7da35",
            "1274b98c2a4b868b7587a20abce79dbf61a553b9f5f29cc213ca7f15644722ee",
            "1f80ec26add470ed4dea2eb5e3fa952b4b54fdf430c108772625e25f582e7e6f")
        var index=0
        for(v in listOf(CairnVoice.KNOCK, CairnVoice.ANSWER)) for(note in listOf(48,60,72)) {
            val file=File.createTempFile("cairn-reference-", ".wav")
            try {
                WavWriter.write(file,Cairn.render(v,midi=note,model=1))
                val digest=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(hashes[index++],digest,"$v MIDI$note changed the preserved reference")
            } finally { file.delete() }
        }
    }

    @Test fun `held recipes round trip and old recipes keep their rendering contract`() {
        for (v in CairnVoice.entries) {
            val p=CairnPatch("Continuing ${v.name}",v,middle)
            assertEquals(p,CairnPatch.fromJsonText(p.toJsonText()))
            assertContentEquals(p.render().samples,Cairn.renderLoop(v,middle).samples)
            if (v == CairnVoice.TUMBLE) continue
            val legacy=p.copy(model=1,macros=middle+("HOLD" to 0f))
            assertEquals(legacy,CairnPatch.fromJsonText(legacy.toJsonText()))
            val e=assertFailsWith<IllegalArgumentException> { legacy.copy(macros=middle) }
            assertTrue(e.message!!.contains("model 1 reserves HOLD"))
        }
    }
}
