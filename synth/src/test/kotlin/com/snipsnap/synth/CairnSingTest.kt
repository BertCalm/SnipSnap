package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

/** SING's driven-traction contract is separate from the three passive contact laws. */
class CairnSingTest {
    private val voice = CairnVoice.SING
    private val defaults get() = Cairn.defaults(voice)
    private fun rms(a: FloatArray, from: Int = 0, until: Int = a.size): Double =
        sqrt((from until until).sumOf { a[it].toDouble().pow(2) } / (until - from))
    private fun relative(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        return sqrt((0 until n).sumOf { (a[it] - b[it]).toDouble().pow(2) } /
            (0 until n).sumOf { b[it].toDouble().pow(2) })
    }

    @Test fun `exact regenerative energy flow agrees with independent RK4 including zero and negative growth`() {
        for (a in listOf(-5.0, 0.0, 15.0)) for (beta in listOf(50.0, 500.0)) {
            val initial = .0003
            var e = initial; val dt = 1e-5
            fun derivative(x: Double) = 2 * a * x - 4 * beta * x * x
            repeat(50000) {
                val k1 = derivative(e); val k2 = derivative(e + dt * k1 / 2)
                val k3 = derivative(e + dt * k2 / 2); val k4 = derivative(e + dt * k3)
                e += dt / 6 * (k1 + 2 * k2 + 2 * k3 + k4)
            }
            assertEquals(e, CairnSing.advanceEnergy(initial, a, beta, .5), 1e-11)
            val falling = CairnSing.advanceEnergy(initial, -5.0, beta, .5)
            assertTrue(falling in 0.0..<initial)
        }
    }

    @Test fun `traction cube stays inside the analytic energy bound and independently closes its work ledger`() {
        var residual = 0.0; var maxCycles = 0
        for (w in listOf(0f,.5f,1f)) for (s in listOf(0f,.5f,1f))
            for (g in listOf(0f,.5f,1f)) for (f in listOf(0f,.5f,1f)) for (b in listOf(0f,.5f,1f))
                for (held in listOf(false,true)) {
                    val m = defaults + mapOf("WEIGHT" to w,"SLOPE" to s,"GROOVE" to g,
                        "FRICTION" to f,"BODY" to b,"HOLD" to if (held) 1f else 0f)
                    val p = Cairn.motion(voice,m)
                    val r = assertNotNull(p.rubbing)
                    assertTrue(r.energy.all { it.isFinite() && it in 0.0..r.energyBound * (1 + 1e-9) })
                    assertTrue(p.suppliedWork > 0 && p.dissipatedWork > 0)
                    val error = abs(p.initialEnergy + p.suppliedWork - p.finalEnergy - p.withdrawnWork - p.dissipatedWork)
                    assertTrue(error < 1e-10)
                    residual = max(residual,error); maxCycles = max(maxCycles,p.settledCycles)
                    if (held) {
                        assertTrue(p.events.isEmpty() && p.cycleResidual < 1e-9)
                        assertTrue(p.settledCycles in 3..8)
                    } else {
                        assertEquals(1,p.events.size)
                        assertEquals(0,p.events.single().stone)
                        val off = (r.contactEndSeconds * r.rate).roundToInt() + 1
                        assertTrue((off until r.energy.lastIndex).all { r.energy[it + 1] <= r.energy[it] })
                    }
                }
        println("SING cube: 243 controls x finite/held; max work residual=$residual cycles=$maxCycles")
    }

    @Test fun `one soft contact opens into powered rubbing and removing regeneration removes the bloom`() {
        val on = Cairn.inspect(voice)
        val off = Cairn.inspect(voice,options=Cairn.Options(disableCoupling=true))
        val from = (.7 * on.diagnostics.internalRate).roundToInt()
        val until = (1.1 * on.diagnostics.internalRate).roundToInt()
        assertTrue(rms(on.raw,from,until) > 10 * rms(off.raw,from,until))
        assertEquals(1,on.diagnostics.motion.events.size)
        assertTrue(on.diagnostics.motion.suppliedWork > 0 && off.diagnostics.motion.suppliedWork == 0.0)
        assertTrue(on.diagnostics.tailEnergyIncrease < 1e-12)
        assertContentEquals(on.full.copyOfRange(on.diagnostics.outputStartFrame,on.full.size),on.samples)
        assertContentEquals(on.full,Cairn.render(voice,startAtContact=false).samples)
        assertTrue(on.diagnostics.firstContactFrame in 1 until (.03*Cairn.RATE).roundToInt())
    }

    @Test fun `real preceding held audio closes and agrees with an independently warmed bank at three notes`() {
        for (note in listOf(48,60,72)) {
            val a = Cairn.renderLoop(voice,midi=note)
            val b = Cairn.inspect(voice,defaults+mapOf("HOLD" to 1f),note,
                options=Cairn.Options(fullLoopPreroll=true))
            assertEquals(2*Cairn.RATE,a.samples.size)
            assertEquals(4*Cairn.RATE,a.full.size)
            assertTrue(a.diagnostics.seamError!! < Keys.MAX_SEAM_ERROR)
            val error = relative(a.samples,b.samples)
            assertTrue(error < 1e-5,"SING MIDI$note warm reference $error")
            assertContentEquals(a.full.copyOfRange(a.diagnostics.loopStartFrame,a.full.size),a.samples)
            val changed = a.full.copyOf()
            for (i in changed.size-256 until changed.size) changed[i] *= 1.1f
            assertTrue(Keys.seamError(changed,a.diagnostics.loopStartFrame)>Keys.MAX_SEAM_ERROR)
            println("SING MIDI$note real seam=${a.diagnostics.seamError} warmed RMS=$error")
        }
    }

    @Test fun `C3 endpoints and soft velocity remain finite tonal and quiet in DC`() {
        for (bits in 0 until 32) for (velocity in listOf(.35f,1f)) {
            val m = defaults + listOf("WEIGHT","SLOPE","GROOVE","FRICTION","BODY")
                .mapIndexed { i,k -> k to if (bits and (1 shl i)==0) 0f else 1f }
            val r = Cairn.inspect(voice,m,48,velocity)
            assertTrue(r.raw.all { it.isFinite() } && r.samples.all { it.isFinite() })
            assertTrue(r.diagnostics.rawPeak in .0001..2.0)
            assertTrue(r.samples.maxOf { abs(it) } in .01f.. .991f)
            assertTrue(abs(r.samples.average())<.002)
            val result = Classifier.classify(Snip(r.samples,1,Cairn.RATE)).drumClass
            assertEquals(DrumClass.LOOP,result,"C3 controls $bits velocity $velocity")
            assertEquals(result,Cairn.drumClassFor(voice,m,velocity))
        }
    }

    @Test fun `root stays anchored through weight body and maintained frequency planning`() {
        var maximum = 0.0; var minimumConfidence = 1f
        for (held in listOf(false,true)) for (note in listOf(48,60,72)) for (value in listOf(.25f,.75f)) {
            val m=defaults+mapOf("WEIGHT" to value,"BODY" to value,"HOLD" to if(held)1f else 0f)
            val r=Cairn.render(voice,m,note)
            val p=assertNotNull(Pitch.detect(r,fromSec=.6f,windowSec=.3f))
            val cents=1200.0*log2(p.hz/Cairn.frequencyFor(note))
            assertTrue(abs(cents)<8,"SING MIDI$note BODY$value held$held $cents cents")
            assertTrue(p.confidence>.8f)
            maximum=max(maximum,abs(cents));minimumConfidence=min(minimumConfidence,p.confidence)
        }
        println("SING roots maximum=$maximum cents minimum confidence=$minimumConfidence")
    }

    @Test fun `every control changes normalized sound and pressure loss pairs interact`() {
        for (name in listOf("WEIGHT","SLOPE","GROOVE","FRICTION","BODY","HOLD")) {
            val a=Cairn.inspect(voice,defaults+(name to .25f))
            val b=Cairn.inspect(voice,defaults+(name to .75f))
            assertTrue(relative(a.samples,b.samples)>.005,"$name has no audible-scale activity")
        }
        val effects=mutableListOf<Double>()
        for(body in listOf(.25f,.75f)) {
            val low=Cairn.motion(voice,defaults+mapOf("BODY" to body,"FRICTION" to .25f))
            val high=Cairn.motion(voice,defaults+mapOf("BODY" to body,"FRICTION" to .75f))
            effects+=high.suppliedWork-low.suppliedWork
        }
        assertTrue(abs(effects[1]-effects[0])>1e-6)
    }

    @Test fun `silence velocity cache finite HOLD and model four recipes retain their full contract`() {
        for (held in listOf(false,true)) {
            val m=defaults+("HOLD" to if(held)1f else 0f)
            val silent=Cairn.inspect(voice,m,velocity=0f)
            assertTrue(silent.samples.all { it==0f } && silent.raw.all { it==0f })
            assertTrue(silent.diagnostics.motion.events.isEmpty())
            val soft=Cairn.inspect(voice,m,velocity=.35f)
            val hard=Cairn.inspect(voice,m)
            assertTrue(soft.diagnostics.motion.suppliedWork<hard.diagnostics.motion.suppliedWork)
            assertTrue(relative(soft.samples,hard.samples)>.005)
            Cairn.clearMotionCache()
            val a=Cairn.inspect(voice,m)
            val tuned=Cairn.inspect(voice,m+("TUNE" to .75f))
            assertFalse(a.diagnostics.mechanicsCacheHit);assertTrue(tuned.diagnostics.mechanicsCacheHit)
            assertEquals(a.diagnostics.motion,tuned.diagnostics.motion)
            Cairn.clearMotionCache();assertContentEquals(a.samples,Cairn.inspect(voice,m).samples)
            val patch=CairnPatch("Sung stone",voice,m,midi=57,velocity=.6f,startAtContact=false)
            assertEquals(patch,CairnPatch.fromJsonText(patch.toJsonText()))
            assertContentEquals(Cairn.render(voice,m,velocity=.35f).samples,Velocity.atVelocity(CairnPatch("Touch",voice,m),.35f).samples)
        }
        Cairn.clearMotionCache()
        val a=Cairn.inspect(voice,defaults+("HOLD" to .25f))
        val b=Cairn.inspect(voice,defaults+("HOLD" to .75f))
        assertFalse(b.diagnostics.mechanicsCacheHit);assertTrue(b.samples.size>a.samples.size)
        for(model in 1..3) {
            assertTrue(assertFailsWith<IllegalArgumentException> { CairnPatch("Old",voice,defaults,model=model) }.message!!.contains("SING requires model 4"))
            assertTrue(assertFailsWith<IllegalArgumentException> { Cairn.render(voice,model=model) }.message!!.contains("SING requires model 4"))
        }
        assertContentEquals(Cairn.render(voice).samples,Cairn.render(voice,mapOf("UNKNOWN" to 1f,"BODY" to Float.NaN)).samples)
        repeat(24) { i ->
            val m=Cairn.scramble(voice,Random(i),temperature=1f)
            assertTrue(m.values.all { it in 0f..1f } && m.getValue("HOLD")<.99f)
        }
    }

    @Test fun `new voice retains model three contacts and agrees with an independent high rate render`() {
        for (v in listOf(CairnVoice.KNOCK,CairnVoice.ANSWER,CairnVoice.TUMBLE))
            for (held in listOf(false,true)) for(note in listOf(48,60,72)) {
                val m=Cairn.defaults(v)+("HOLD" to if(held)1f else 0f)
                assertContentEquals(Cairn.render(v,m,note,model=3).samples,Cairn.render(v,m,note,model=4).samples)
            }
        for(held in listOf(false,true)) {
            val m=defaults+("HOLD" to if(held)1f else 0f)
            val a=Cairn.inspect(voice,m,72,startAtContact=false)
            val b=Cairn.inspect(voice,m,72,startAtContact=false,options=Cairn.Options(oversample=8))
            val error=relative(a.samples,b.samples)
            println("SING held=$held C5 4x/8x relative RMS=$error")
            assertTrue(error<.04)
        }
    }
}
