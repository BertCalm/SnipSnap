package com.snipsnap.synth

import com.snipsnap.audio.*
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class ChitinTest {
    @Test fun `fresh renders reproduce and saved patches keep the sound`() {
        for(v in ChitinVoice.entries) {
            val p=ChitinPatch("Canary",v,Chitin.defaults(v))
            assertContentEquals(Chitin.inspect(v).snip.samples,Chitin.inspect(v).snip.samples)
            assertContentEquals(p.render().samples,Patches.fromJsonText(p.toJsonText()).render().samples)
            val a=p.render();a.samples.fill(0f)
            assertTrue(p.render().peak()>.1f,"cache exposed mutable samples")
        }
    }
    @Test fun `duration has one frame plan and HOLD top is finite`() {
        for(v in ChitinVoice.entries) {
            val top=Chitin.render(v,mapOf("HOLD" to .98f))
            for(h in listOf(.999f,1f)) assertContentEquals(top.samples,Chitin.render(v,mapOf("HOLD" to h)).samples)
            assertFalse(Chitin.isLoop(1f))
            for(h in listOf(0f,.25f,.5f,1f)) {
                val m=mapOf("HOLD" to h);val s=Chitin.render(v,m)
                assertEquals(Chitin.plan(v,m).frames,s.frameCount)
                assertEquals(Classifier.classify(s).drumClass,Chitin.drumClassFor(v,m))
            }
        }
    }
    @Test fun `settling ignores unknown keys and invalid knobs use defaults`() {
        val v=ChitinVoice.SHELL;val baseline=Chitin.render(v).samples
        for(m in listOf(mapOf("MORPH" to Float.NaN),mapOf("PHASE" to Float.POSITIVE_INFINITY),mapOf("ignored" to 1f)))
            assertContentEquals(baseline,Chitin.render(v,m).samples)
        assertTrue(Chitin.render(v,velocity=0f).samples.all { it==0f })
        for(i in 0..20) assertTrue(Chitin.scramble(v,Random(i)).values.all { it in 0f..1f })
    }
    @Test fun `output remains finite bounded and outside drum classes at parameter corners`() {
        val allowed=setOf(DrumClass.TONAL,DrumClass.PERC,DrumClass.LOOP)
        for(v in ChitinVoice.entries) for(tune in listOf(0f,.5f,1f)) for(bits in 0 until 32) {
            val keys=listOf("BITE","COLOR","PHASE","MORPH","PACE")
            val m=keys.mapIndexed { i,k -> k to (if(bits and (1 shl i)==0) 0f else 1f) }.toMap()+mapOf("TUNE" to tune,"HOLD" to 0f)
            val r=Chitin.inspect(v,m);val d=r.diagnostics
            assertTrue(r.snip.samples.all { it.isFinite() })
            assertTrue(r.snip.peak() in .01f.. .991f)
            assertTrue(abs(r.snip.samples.average())<.005)
            assertTrue(d.rawPeak<4 && d.filterPeak<8,"$v $m $d")
            assertTrue(d.energyExcess<1e-9,"phaser injects energy: $d")
            assertTrue(Classifier.classify(r.snip).drumClass in allowed,"$v $m became ${Classifier.classify(r.snip).drumClass}")
        }
    }
    @Test fun `root stays anchored through sync and phase at ordinary corners`() {
        for(v in ChitinVoice.entries) for(t in listOf(.5f,1f)) {
            val m=mapOf("TUNE" to t,"BITE" to 1f,"PHASE" to 1f)
            val s=Chitin.render(v,m);val pitch=assertNotNull(Pitch.detect(s,fromSec=.2f))
            val cents=1200*ln(pitch.hz/Chitin.frequencyFor(v,t))/ln(2.0)
            assertTrue(abs(cents)<10,"$v $t: $cents cents")
            assertTrue(pitch.confidence>.7,"$v $t: ${pitch.confidence}")
        }
    }
    @Test fun `C1 pitch has a direct low frequency projection beyond stock detector range`() {
        val m=mapOf("TUNE" to 0f,"MORPH" to 0f,"HOLD" to 0f)
        val s=Chitin.render(ChitinVoice.SHELL,m).samples
        val root=Chitin.frequencyFor(ChitinVoice.SHELL,0f)
        val first=(.2*44100).toInt();val n=(.4*44100).toInt()
        val best=(-30..30).maxBy { cents ->
            val f=root*2.0.pow(cents/1200.0)
            var re=0.0;var im=0.0
            for(j in 0 until n) {
                val w=.5-.5*cos(2*PI*j/(n-1));val angle=2*PI*f*(first+j)/44100
                re+=s[first+j]*w*cos(angle);im+=s[first+j]*w*sin(angle)
            }
            hypot(re,im)
        }
        assertTrue(abs(best)<=10,"C1 projection: $best cents")
    }
    @Test fun `scattering feedback is passive even under abrupt corner motion`() {
        val random=Random(42)
        val q=.35; val state=DoubleArray(6);var z=0.0;var storage=0.0
        var mutationFailed=false
        var mutatedZ=0.0
        for(i in 0 until 20000) {
            val x=if(i<10000) random.nextDouble(-1.0,1.0) else 0.0
            var y=(1-q)*x+q*z
            for(j in state.indices) {
                val a=random.nextDouble(-.999,.999);val b=sqrt(1-a*a);val old=state[j]
                val next=a*y+b*old;state[j]=b*y-a*old;y=next
            }
            val next=state.sumOf { it*it }+q*y*y
            assertTrue(next-storage<=(1-q)*(x*x-y*y)+1e-11)
            storage=next;z=y
            // A unit allpass already disproves passivity if input normalization is removed.
            val wrong=x+q*mutatedZ
            if(q*(wrong*wrong-mutatedZ*mutatedZ)>(1-q)*(x*x-wrong*wrong)+1e-9) mutationFailed=true
            mutatedZ=wrong
        }
        assertTrue(mutationFailed);assertTrue(storage<1e-12)
    }
    @Test fun `actual phaser preserves C1 C2 C3 while its upper spectrum moves`() {
        val rate=Dsp.RATE*4
        for(midi in listOf(24,36,48)) {
            val root=440*2.0.pow((midi-69)/12.0)
            val phaser=ChitinPhaser(rate,root,1.0,false)
            var dryRe=0.0;var dryIm=0.0;var wetRe=0.0;var wetIm=0.0;var difference=0.0
            for(i in 0 until rate) {
                val t=i.toDouble()/rate
                val x=.3*sin(2*PI*root*t)+.1*sin(2*PI*root*8*t)
                val m=Chitin.smooth(t/.8)
                val next=Chitin.smooth((i/64*64+64).toDouble()/rate/.8)
                val y=phaser.step(x,m,next)
                if(t>.25) {
                    val c=cos(2*PI*root*t);val s=sin(2*PI*root*t)
                    dryRe+=x*c;dryIm+=x*s;wetRe+=y*c;wetIm+=y*s;difference+=(y-x).pow(2)
                }
            }
            val db=20*log10(hypot(wetRe,wetIm)/hypot(dryRe,dryIm))
            assertTrue(abs(db)<.5,"$midi root $db dB");assertTrue(difference>1)
        }
    }
    @Test fun `controls move the waveform and expected zero morph exception holds`() {
        val v=ChitinVoice.SHELL
        for(key in listOf("BITE","COLOR","PHASE","MORPH","PACE")) {
            val a=Chitin.render(v,mapOf(key to .2f)).samples;val b=Chitin.render(v,mapOf(key to .6f)).samples
            assertTrue(a.indices.sumOf { (a[it]-b[it]).toDouble().pow(2) }/a.size>1e-7,key)
        }
        assertContentEquals(Chitin.render(v,mapOf("MORPH" to 0f,"PACE" to 0f)).samples,
            Chitin.render(v,mapOf("MORPH" to 0f,"PACE" to 1f)).samples)
        assertFalse(Chitin.render(v,velocity=.3f).samples.contentEquals(Chitin.render(v).samples))
    }
}
