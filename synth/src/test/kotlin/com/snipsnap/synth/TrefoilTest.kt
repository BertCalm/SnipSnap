package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.*
import kotlin.test.*

class TrefoilTest {
    private fun patch(m: Map<String,Float> = emptyMap(),seconds: Float=1.2f)=
        TrefoilPatch("Test",TrefoilVoice.WEAVE,Trefoil.defaults(TrefoilVoice.WEAVE)+m,seconds=seconds,activeSeconds=seconds*.6f)

    @Test fun `refined reference closes and conserves its measured baselines`() {
        val y=TrefoilTrajectory.reference()
        val a=TrefoilTrajectory.solve(y,TrefoilTrajectory.PERIOD,2001)
        val closure=sqrt(y.indices.sumOf { (a.states.last()[it]-y[it]).pow(2) })
        println("TREFOIL reference closure=$closure energy=${a.diagnostics.energyDrift} work=${a.diagnostics.evaluations}")
        assertEquals("COMPLETE",a.diagnostics.outcome)
        assertTrue(closure<2e-8)
        assertTrue(a.diagnostics.energyDrift<1e-7)
        assertTrue(a.diagnostics.momentumDrift<1e-12)
        assertTrue(a.diagnostics.comDrift<1e-12)
        assertTrue(a.diagnostics.angularDrift<1e-9)
        val tighter=TrefoilTrajectory.solve(y,TrefoilTrajectory.PERIOD,2001,tolerance=1e-12)
        assertTrue(sqrt(y.indices.sumOf { (tighter.states.last()[it]-y[it]).pow(2) })<closure)
    }
    @Test fun `zero nudge is identical for every seed while perturbations retain zero momentum`() {
        assertContentEquals(TrefoilTrajectory.initial(0f,1L),TrefoilTrajectory.initial(0f,Long.MAX_VALUE))
        val a=TrefoilTrajectory.initial(.6f,27L); val b=TrefoilTrajectory.initial(.6f,28L)
        assertFalse(a.contentEquals(b))
        val inv=TrefoilTrajectory.invariant(a)
        assertTrue(hypot(inv.px,inv.py)<1e-14)
        val result=TrefoilTrajectory.solve(a,2.0,1001)
        assertEquals(inv,result.diagnostics.initial)
        assertTrue(result.diagnostics.energyDrift<1e-6)
    }
    @Test fun `participant permutation changes labels without changing physics`() {
        val y=TrefoilTrajectory.initial(.3f,93L); val order=intArrayOf(2,0,1)
        fun permute(x: DoubleArray)=DoubleArray(12) { k -> val half=if(k<6) 0 else 6; val j=k-half; x[half+2*order[j/2]+j%2] }
        val a=TrefoilTrajectory.solve(y,1.5,301); val b=TrefoilTrajectory.solve(permute(y),1.5,301)
        for(i in a.states.indices step 30) { val expected=permute(a.states[i]); for(k in expected.indices) assertEquals(expected[k],b.states[i][k],1e-8) }
    }
    @Test fun `work caps and collision guards end explicitly with finite retained state`() {
        val cap=TrefoilTrajectory.solve(TrefoilTrajectory.reference(),10.0,1001,maxEvaluations=80)
        assertEquals("WORK_LIMIT",cap.diagnostics.outcome)
        assertTrue(cap.states.all { it.all(Double::isFinite) })
        val y=TrefoilTrajectory.reference(); y[0]=.0004; y[1]=0.0; y[2]=-.0004; y[3]=0.0; y[4]=2.0; y[5]=0.0
        val collision=TrefoilTrajectory.solve(y,1.0,101)
        assertEquals("NEAR_COLLISION",collision.diagnostics.outcome)
        assertEquals(0.0,collision.diagnostics.reached)
        assertTrue(collision.states.all { it.contentEquals(y) })
    }
    @Test fun `weave zero freezes geometry and pitch changes reuse the exact trajectory`() {
        val p=patch(mapOf("WEAVE" to 0f))
        val a=TrefoilTrajectory.forPatch(p)
        assertEquals("STATIONARY",a.diagnostics.outcome)
        assertTrue(a.states.all { it.contentEquals(a.states[0]) })
        assertSame(a,TrefoilTrajectory.forPatch(p.copy(midi=84,velocity=.2f)))
        val rates=listOf(0f,.05f,.1f,.25f,.5f,.75f,1f).map(TrefoilTrajectory::cyclesPerSecond)
        assertTrue(rates.zipWithNext().all { (a,b) -> b>a })
        assertEquals(8.0,rates.last(),1e-12)
    }
    @Test fun `every scattering coefficient preserves port energy including intermediate exchange`() {
        for(n in 0..100) {
            val s=Trefoil.rotation(n*.00013,-n*.00007,n*.00019)
            for(i in 0..2) for(j in 0..2) {
                val dot=(0..2).sumOf { s[it*3+i]*s[it*3+j] }
                assertEquals(if(i==j) 1.0 else 0.0,dot,5e-15)
            }
        }
        assertContentEquals(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),Trefoil.rotation(0.0,0.0,0.0))
    }
    @Test fun `one participant impulse reaches the other states only through exchange`() {
        val opts=Trefoil.Options(impulseOnly=true,excitedParticipant=0,memoryEnabled=false,stems=true)
        val off=Trefoil.probe(patch(mapOf("EXCHANGE" to 0f,"MEMORY" to 0f)),opts)
        val on=Trefoil.probe(patch(mapOf("EXCHANGE" to .9f,"MEMORY" to 0f)),opts)
        assertTrue(off.stems[1].all { it==0f } && off.stems[2].all { it==0f })
        assertEquals(0.0,off.diagnostics.participantEnergy[1]); assertEquals(0.0,off.diagnostics.participantEnergy[2])
        assertTrue(on.diagnostics.participantEnergy[1]>1e-8 && on.diagnostics.participantEnergy[2]>1e-8)
        assertTrue(on.stems[1].any { abs(it)>1e-6f } && on.stems[2].any { abs(it)>1e-6f })
        println("TREFOIL impulse receiver peak energies=${on.diagnostics.participantEnergy}")
    }
    @Test fun `unforced core contracts and external work has an explicit finite budget`() {
        val impulse=Trefoil.probe(patch(seconds=2.0f),Trefoil.Options(impulseOnly=true,excitedParticipant=0))
        assertTrue(impulse.diagnostics.unforcedRise<1e-12)
        assertTrue(impulse.diagnostics.coreFinalEnergy<impulse.diagnostics.coreMaxEnergy*.001)
        val p=patch(mapOf("WEAVE" to 1f,"EXCHANGE" to 1f,"THREAD" to 1f,"MEMORY" to 1f,"NUDGE" to .6f))
        val a=Trefoil.probe(p)
        assertTrue(a.diagnostics.finite)
        assertTrue(a.diagnostics.coreMaxEnergy<=a.diagnostics.positiveWork+1e-10)
        assertTrue(a.diagnostics.positiveWork<=a.diagnostics.workBudget+1e-10)
        assertTrue(a.diagnostics.unforcedRise<1e-12)
        assertTrue(a.samples.all(Float::isFinite))
    }
    @Test fun `output is deterministic and diagnostic stem collection changes no sound`() {
        val p=patch(mapOf("NUDGE" to .45f))
        val a=Trefoil.probe(p,Trefoil.Options(stems=true)); val b=Trefoil.probe(p)
        assertContentEquals(a.raw,b.raw); assertContentEquals(a.samples,b.samples)
        for(i in a.raw.indices step 100) assertEquals(a.raw[i],a.stems.sumOf { it[i].toDouble() }.toFloat()+a.memory[i],2e-7f)
        assertTrue(a.diagnostics.rawPeak<1.0)
        assertTrue(abs(a.diagnostics.rawDc)<.001)
    }
    @Test fun `every timbral macro and trajectory motion changes the rendered audio`() {
        for(k in listOf("WEAVE","NUDGE","EXCHANGE","THREAD","MEMORY")) {
            val a=Trefoil.probe(patch(mapOf(k to 0f))).raw
            val b=Trefoil.probe(patch(mapOf(k to 1f))).raw
            val distance=sqrt(a.indices.sumOf { (a[it]-b[it]).toDouble().pow(2) }/a.indices.sumOf { a[it].toDouble().pow(2) })
            println("TREFOIL $k relative difference=$distance")
            assertTrue(distance>.002,"inactive $k")
        }
        val p=patch(); val a=Trefoil.probe(p).raw; val b=Trefoil.probe(p,Trefoil.Options(frozen=true)).raw
        assertFalse(a.contentEquals(b))
    }
    @Test fun `zero velocity is exact silence`() {
        assertTrue(patch().copy(velocity=0f).render().samples.all { it==0f })
    }
    @Test fun `normal whole gestures retain clear roots and avoid drum classes across three registers`() {
        for(v in TrefoilVoice.entries) for(midi in listOf(36,60,84)) {
            val p=TrefoilPatch("Pitch",v,midi=midi,seconds=4f,activeSeconds=1.5f)
            val out=p.render(); val estimate=assertNotNull(Pitch.detect(out,.12f,.3f))
            val cents=1200*ln(estimate.hz/Keys.midiHz(midi))/ln(2.0)
            val onset=Classifier.classify(Snip(out.samples.copyOf((1.2*Dsp.RATE).toInt()),1,Dsp.RATE))
            // Preserve complete envelope evidence while bypassing the generic
            // length>1.5s LOOP shortcut. A truncated rising bass can read KICK
            // because its latest peak has no following tail in that excerpt.
            val whole=Classifier.classify(FeatureExtractor.extract(out).copy(durationSeconds=1.2f))
            println("TREFOIL voice=$v midi=$midi cents=$cents confidence=${estimate.confidence} prefix=${onset.drumClass} whole=${whole.drumClass}")
            assertTrue(abs(cents)<15,"$v MIDI $midi pitch $cents")
            assertTrue(estimate.confidence>.8f)
            assertTrue(whole.drumClass !in TangleMeasure.drumClasses,"$v MIDI $midi whole gesture reads ${whole.drumClass}")
        }
    }
    @Test fun `reference participants follow the same path one third of a period apart`() {
        val y=TrefoilTrajectory.reference()
        val z=TrefoilTrajectory.solve(y,TrefoilTrajectory.PERIOD/3,101).states.last()
        val errors=listOf(intArrayOf(1,2,0),intArrayOf(2,0,1)).map { order ->
            sqrt((0..11).sumOf { k -> val half=if(k<6) 0 else 6; val j=k-half; (z[k]-y[half+2*order[j/2]+j%2]).pow(2) }) }
        assertTrue(errors.min()<2e-8)
    }
    @Test fun `complete recipe and pad replay preserve exact sound and seed`() {
        val p=patch().copy(seed=Long.MAX_VALUE,midi=57,velocity=.42f)
        val q=Patches.fromJsonText(p.toJsonText()) as TrefoilPatch
        assertEquals(p,q); assertEquals(p,Patches.edited(p,"Edited",p.macros).let { (it as TrefoilPatch).copy(name=p.name) })
        assertContentEquals(p.render().samples,q.render().samples)
        val recipe=PadRecipe(patch=p)
        assertContentEquals(recipe.render().samples,PadRecipe.fromJsonText(recipe.toJsonText()).render().samples)
        val soft=Velocity.atVelocity(p,.25f)
        assertEquals(Dsp.RATE,soft.sampleRate)
    }
    @Test fun `unsupported sustain and malformed recipes are refused`() {
        assertFailsWith<IllegalArgumentException> { patch(mapOf("HOLD" to 1f)) }
        assertFailsWith<IllegalArgumentException> { patch(mapOf("THREAD" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { patch().copy(model=99) }
        val obj=LinkedHashMap(patch().toJsonValue().entries)
        val macros=LinkedHashMap((obj.getValue("macros") as JsonValue.Obj).entries)
        macros["HOLD"]=JsonValue.Num(1e-10); obj["macros"]=JsonValue.Obj(macros)
        assertFailsWith<JsonException> { Patches.fromJsonValue(JsonValue.Obj(obj)) }
        assertFailsWith<IllegalArgumentException> { patch().copy(activeSeconds=2f) }
    }
}
