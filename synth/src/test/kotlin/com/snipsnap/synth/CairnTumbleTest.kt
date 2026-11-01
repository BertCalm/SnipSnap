package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class CairnTumbleTest {
    private val voice = CairnVoice.TUMBLE
    private val middle = mapOf("TUNE" to .5f, "WEIGHT" to .5f, "SLOPE" to .4f,
        "GROOVE" to .35f, "FRICTION" to .3f, "BODY" to .4f, "HOLD" to 0f)

    private fun tightens(events: List<Cairn.Event>): Boolean {
        val gaps = events.zipWithNext { a, b -> b.seconds - a.seconds }
        return gaps.size >= 3 && gaps.all { it > 0.0 } &&
            gaps.zipWithNext().all { (a, b) -> b < a } && gaps.last() < .5 * gaps.first()
    }

    @Test fun `a single launch makes a contracting same-stone cascade throughout the control cube`() {
        var minimum = Int.MAX_VALUE; var maximum = 0; var latest = 0.0
        for (weight in listOf(0f,.5f,1f)) for (slope in listOf(0f,.5f,1f))
            for (groove in listOf(0f,.5f,1f)) for (friction in listOf(0f,.5f,1f))
                for (body in listOf(0f,.5f,1f)) {
                    val m = middle + mapOf("WEIGHT" to weight,"SLOPE" to slope,"GROOVE" to groove,
                        "FRICTION" to friction,"BODY" to body)
                    val r = Cairn.motion(voice,m)
                    assertTrue(r.events.all { it.stone == 0 })
                    assertTrue(tightens(r.events), "$m: flight times do not contract")
                    assertTrue(r.events.last().work < .1 * r.events.first().work)
                    assertEquals(0.0,r.finalEnergy,1e-10)
                    assertEquals(r.initialEnergy,r.withdrawnWork+r.dissipatedWork,1e-8)
                    assertTrue(r.dissipatedWork >= 0 && r.largestEnergyIncrease < 1e-9)
                    minimum=min(minimum,r.events.size);maximum=max(maximum,r.events.size)
                    latest=max(latest,r.events.last().seconds)
                }
        assertTrue(latest < 1.9, "the longest cascade overruns its two-second motor period")
        val original=Cairn.motion(voice,middle).events
        val scheduled=original.mapIndexed { i,e -> e.copy(seconds=original.first().seconds+i*.07) }
        assertFalse(tightens(scheduled),"the gesture check accepted an evenly scheduled ratchet")
        println("TUMBLE cube: 243 points, $minimum..$maximum impacts, latest $latest s")
    }

    @Test fun `first impact independently solves damped gravitational free flight and work follows velocity`() {
        val r=Cairn.motion(voice,middle)
        val mass=.55+1.3*middle.getValue("WEIGHT")
        val g=10+8.0*middle.getValue("SLOPE")
        val beta=(.08+1.1*middle.getValue("FRICTION"))/mass
        val launch=(1.05+.35*middle.getValue("SLOPE"))/sqrt(mass)
        // Independent fine-step RK4 trajectory, no collision/root-finding code
        // from the renderer. Interpolate the first downward bed crossing.
        var y=0.0;var v=launch;var t=0.0;val dt=1e-5
        while(true) {
            fun a(u:Double) = -g-beta*u
            val a1=a(v);val a2=a(v+a1*dt/2);val a3=a(v+a2*dt/2);val a4=a(v+a3*dt)
            val ny=y+dt/6*(v+2*(v+a1*dt/2)+2*(v+a2*dt/2)+(v+a3*dt))
            val nv=v+dt/6*(a1+2*a2+2*a3+a4)
            if(ny<0) {
                val part=y/(y-ny)
                assertEquals(t+part*dt,r.events.first().seconds,2e-8)
                assertEquals(abs(v+part*(nv-v)),r.events.first().speedBefore,2e-7)
                break
            }
            y=ny;v=nv;t+=dt
            assertTrue(t<1.0)
        }
        for(e in r.events) assertEquals(.5*mass*(e.speedBefore.pow(2)-e.speedAfter.pow(2)),e.work,1e-12)
        assertEquals(.5*mass*r.events.last().speedAfter.pow(2),r.terminalBrakeWork,1e-12)
        val knock=Cairn.motion(CairnVoice.KNOCK,middle)
        val answer=Cairn.motion(CairnVoice.ANSWER,middle)
        assertTrue(r.events.size > knock.events.size && tightens(r.events))
        assertEquals(0,r.events.count { it.stone>0 })
        assertTrue(answer.events.count { it.stone>0 } >= 6)
    }

    @Test fun `groove friction body and weight change the cascade through the contact law`() {
        for(control in listOf("WEIGHT","SLOPE","GROOVE","FRICTION","BODY")) {
            assertNotEquals(Cairn.motion(voice,middle+(control to .25f)).events,
                Cairn.motion(voice,middle+(control to .75f)).events,control)
        }
        val closed=Cairn.motion(voice,middle+("GROOVE" to 0f))
        val open=Cairn.motion(voice,middle+("GROOVE" to 1f))
        assertTrue(open.events.size>closed.events.size)
        val dry=Cairn.motion(voice,middle+("FRICTION" to 0f))
        val damp=Cairn.motion(voice,middle+("FRICTION" to 1f))
        assertTrue(damp.dissipatedWork>dry.dissipatedWork)
        val coupled=Cairn.motion(voice,middle)
        val isolated=Cairn.motion(voice,middle,disableCoupling=true)
        assertEquals(coupled.events.first().seconds,isolated.events.first().seconds,1e-10)
        assertNotEquals(coupled.events,isolated.events)
    }

    @Test fun `short C3 cascades stay out of drum classes across endpoint controls and softer launches`() {
        val controls=listOf("WEIGHT","SLOPE","GROOVE","FRICTION","BODY")
        for(mask in 0 until 32) for(velocity in listOf(.35f,1f)) {
            val m=middle+controls.mapIndexed { i,k -> k to if(mask and (1 shl i)==0)0f else 1f }.toMap()
            val r=Cairn.inspect(voice,m,48,velocity)
            val c=com.snipsnap.audio.Classifier.classify(com.snipsnap.audio.Snip(r.samples,1,Cairn.RATE))
            assertTrue(c.drumClass in setOf(com.snipsnap.audio.DrumClass.PERC,com.snipsnap.audio.DrumClass.TONAL,
                com.snipsnap.audio.DrumClass.LOOP),"C3 $m velocity$velocity: $c")
        }
    }

    @Test fun `a genuinely silent boundary closes but one changed continuation sample fails`() {
        val full=FloatArray(1024);full[600]=.1f
        assertEquals(0.0,Cairn.silentBoundarySeam(full,512))
        val changedEnd=full.copyOf().also { it[1000]=1e-12f }
        assertTrue(Cairn.silentBoundarySeam(changedEnd,512)>Keys.MAX_SEAM_ERROR)
        val changedPrevious=full.copyOf().also { it[300]=1e-12f }
        assertTrue(Cairn.silentBoundarySeam(changedPrevious,512)>Keys.MAX_SEAM_ERROR)
        val r=Cairn.inspect(voice,middle+mapOf("BODY" to .25f,"FRICTION" to .75f,"HOLD" to 1f))
        assertTrue(r.samples.any { it!=0f })
        assertTrue(r.diagnostics.seamError!!<Keys.MAX_SEAM_ERROR)
    }

    @Test fun `version three adds TUMBLE while all earlier voice renders keep their samples`() {
        for(v in listOf(CairnVoice.KNOCK,CairnVoice.ANSWER)) for(note in listOf(48,60,72))
            for(hold in listOf(0f,1f)) {
                val m=Cairn.defaults(v)+("HOLD" to hold)
                assertContentEquals(Cairn.inspect(v,m,note,model=2).samples,
                    Cairn.inspect(v,m,note,model=3).samples,"$v MIDI$note HOLD$hold")
            }
        for(model in listOf(1,2)) {
            val error=assertFailsWith<IllegalArgumentException> { CairnPatch("Too old",voice,middle,model=model) }
            assertTrue(error.message!!.contains("TUMBLE requires model 3"))
            assertFailsWith<IllegalArgumentException> { Cairn.render(voice,middle,model=model) }
        }
        val p=CairnPatch("One cascade",voice,middle,55,.65f)
        assertEquals(p,PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).patch)
        assertContentEquals(p.render().samples,PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).render().samples)
    }

    @Test fun `densest held cascade closes a real acoustic cycle and survives a higher rate reference`() {
        val m=middle+mapOf("WEIGHT" to 0f,"SLOPE" to 0f,"GROOVE" to 1f,
            "FRICTION" to 0f,"BODY" to 1f,"HOLD" to 1f)
        val a=Cairn.inspect(voice,m,72)
        val b=Cairn.inspect(voice,m,72,options=Cairn.Options(oversample=8,fullLoopPreroll=true))
        assertTrue(a.diagnostics.motion.events.size>=20)
        assertTrue(a.diagnostics.seamError!!<Keys.MAX_SEAM_ERROR)
        assertEquals(2*Cairn.RATE,a.samples.size)
        val error=sqrt(a.samples.indices.sumOf { (a.samples[it]-b.samples[it]).toDouble().pow(2) }/
            b.samples.sumOf { it.toDouble().pow(2) })
        assertTrue(error<.08,"dense C5 HOLD 4x/8x relative RMS $error")
        println("TUMBLE dense C5 HOLD 4x/8x relative RMS $error")
    }
}
