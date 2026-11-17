package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.*
import kotlin.math.*

/** Native audition candidate, not a registered Patch or factory bank. Model 1 ports samba.py's mechanisms.
 * RNG and house output differ from Python, so this requires listening rather than a byte-parity claim.
 * TUNE moves the body ±6 semitones; STRIKE changes contact brightness; DAMP changes decay rates.
 */
internal object ParadeSamba {
    const val MODEL = 1
    private const val R = Dsp.RATE * Dsp.OVERSAMPLE
    enum class Voice(val root: Double, val players: Int, val widthMs: Double) {
        SURDO_LOW_OPEN(58.0,2,4.0), SURDO_LOW_MUTED(58.0,2,4.0),
        SURDO_MID_OPEN(77.0,2,3.0), SURDO_MID_MUTED(77.0,2,3.0), SURDO_CUTTER(105.0,2,2.0),
        CAIXA(330.0,4,.35), CAIXA_RIM(330.0,4,.25), REPINIQUE_OPEN(285.0,1,.4), REPINIQUE_RIM(285.0,1,.3),
        TAMBORIM_OPEN(640.0,6,.22), TAMBORIM_MUTED(640.0,6,.22),
        CHOCALHO_FORWARD(2600.0,3,.3), CHOCALHO_BACK(3100.0,3,.3),
        AGOGO_LOW(580.0,1,.4), AGOGO_HIGH(870.0,1,.4), REPINIQUE_CALL(285.0,1,.4)
    }
    fun defaults() = mapOf("TUNE" to .5f, "STRIKE" to .5f, "DAMP" to .5f)
    fun settled(m: Map<String, Float>) = defaults().mapValues { (k,v) -> m[k]?.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: v }
    data class Recipe(val voice: Voice, val macros: Map<String,Float> = defaults(), val velocity: Float = 1f, val take: Int = 0, val model: Int = MODEL) {
        init { require(model == MODEL && take >= 0); require(velocity.isFinite() && velocity in .05f..1f) }
        fun render() = ParadeSamba.render(voice, macros, velocity, take)
        fun json() = JsonValue.Obj(mapOf("model" to JsonValue.Num(model.toDouble()), "voice" to JsonValue.Str(voice.name),
            "velocity" to JsonValue.Num(velocity.toDouble()), "take" to JsonValue.Num(take.toDouble()),
            "macros" to JsonValue.Obj(settled(macros).mapValues { JsonValue.Num(it.value.toDouble()) })))
        companion object {
            fun fromJson(value: JsonValue): Recipe {
                val m=value.obj()
                return Recipe(Voice.valueOf(m.getValue("voice").str()),m.getValue("macros").obj().mapValues { it.value.num().toFloat() },
                    m.getValue("velocity").num().toFloat(),m.getValue("take").int(),m.getValue("model").int())
            }
        }
    }
    private class Rng(seed: Int) {
        private val noise = Dsp.Noise(seed)
        fun unit() = ((noise.next().toDouble()+1)*.5).coerceIn(1e-9,1-1e-9)
        fun uniform(a: Double,b: Double) = a+(b-a)*unit()
        fun normal() = sqrt(-2*ln(unit()))*cos(2*PI*unit())
        private fun gamma(shape: Double): Double {
            val d=shape-1.0/3; val c=1/sqrt(9*d)
            while(true) {
                val x=normal();val v=(1+c*x).pow(3);if(v<=0)continue
                val u=unit();if(u<1-.0331*x.pow(4) || ln(u)<.5*x*x+d*(1-v+ln(v)))return d*v
            }
        }
        fun beta(a: Double,b: Double): Double { val x=gamma(a);return x/(x+gamma(b)) }
    }
    private val bells: Map<String,CalabashFits.HandData> by lazy {
        val root=Json.parse(checkNotNull(javaClass.getResourceAsStream("/parade/agogo-fit.json")).bufferedReader().use { it.readText() }).obj()
        fun doubles(v: JsonValue)=v.arr().map { it.num() }.toDoubleArray()
        val bands=doubles(root.getValue("bands"));val times=doubles(root.getValue("times"));val early=root.getValue("early").num()
        root.getValue("voices").obj().mapValues { (_,v) ->
            val d=v.obj();val modes=d.getValue("modes").arr().map { doubles(it) };val glide=doubles(d.getValue("glide"))
            fun col(i: Int)=DoubleArray(modes.size) { modes[it][i] }
            val hand=CalabashFits.Hand(col(0),col(1),col(2),col(3),col(4),glide[0],glide[1],
                d.getValue("residual").arr().map { doubles(it) }.toTypedArray(),d.getValue("residualSoft").arr().map { doubles(it) }.toTypedArray())
            CalabashFits.HandData(hand,bands,times,early)
        }
    }
    private fun band(x: DoubleArray,lo: Double,hi: Double) = CalabashDsp.butter(2,CalabashDsp.Kind.BAND,lo,hi,fs=R).run(x)
    private fun loss(t: Double,start: Double,ramp: Double): Double {
        val u=((t-start)/ramp).coerceIn(0.0,1.0)
        return ramp*(u.pow(3)-.5*u.pow(4))+max(0.0,t-start-ramp)
    }
    fun render(voice: Voice, macros: Map<String,Float> = defaults(), velocity: Float = 1f, take: Int = 0): Snip {
        require(take >= 0)
        val m=settled(macros);val v=velocity.takeIf { it.isFinite() }?.coerceIn(.05f,1f)?.toDouble() ?: 1.0
        val source=raw(voice,m,v,take)
        val raw=FloatArray(source.size) { source[it].toFloat() }
        Tide.bandLimit(raw,R);val out=Dsp.decimate(raw,Dsp.RATE)
        val mean=out.average().toFloat();val hp=Dsp.FixedOnePole(20f,Dsp.RATE)
        for(i in out.indices) { val x=out[i]-mean;out[i]=x-hp.lp(x) }
        Dsp.levelTo(out,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET);Dsp.fadeTail(out)
        return Snip(out,1,Dsp.RATE)
    }
    private fun raw(voice: Voice,m: Map<String,Float>,v: Double,take: Int): DoubleArray {
        if(voice==Voice.REPINIQUE_CALL) {
            val out=DoubleArray((1.95*R).roundToInt())
            for((j,at) in listOf(0.0,.107,.214,.321,.535,.643,.75).withIndex()) {
                val hit=raw(Voice.REPINIQUE_OPEN,m,v*(if(j%4==0).95 else .55),take*32+j);val start=(at*R).roundToInt()
                for(i in hit.indices)out[start+i]+=hit[i]
            };return out
        }
        val tune=2.0.pow((m.getValue("TUNE")-.5)*12/12)
        val damp=2.0.pow((m.getValue("DAMP")-.5)*2)
        val strike=2.0.pow((m.getValue("STRIKE")-.5)*1.2)
        if(voice.name.startsWith("AGOGO")) {
            val name=voice.name.lowercase();val fit=bells.getValue(name)
            val (body,contact)=CalabashFits.strike(name,(v*strike).coerceIn(.05,1.0),
                CalabashDsp.Rng(Dsp.seedFor("PARADE_AGOGO",name,take)),1.2,tuneRatio=tune,
                damp=.3+(m.getValue("DAMP")-.5)*.7,contactMs=.18/strike,fit=fit)
            return CalabashDsp.upsample4(DoubleArray(body.size) { (body[it]+contact[it])*v },(1.2*R).roundToInt())
        }
        val name=voice.name.lowercase()
        val family=when { name.startsWith("surdo_low")->"surdo_low";name.startsWith("surdo_mid")->"surdo_mid";name.startsWith("surdo")->"surdo_cutter";else->name.substringBefore('_') }
        val out=DoubleArray((1.2*R).roundToInt())
        repeat(voice.players) { player ->
            val identity=Rng(Dsp.seedFor("SAMBA_BODY",family,player));val rng=Rng(Dsp.seedFor("SAMBA_CONTACT",name,player,take))
            val start=if(player==0)0 else (rng.uniform(.0005,.008)*R).roundToInt();val n=out.size-start
            val touch=v*rng.uniform(.95,1.04);val brightness=(touch*strike).coerceIn(.05,1.3)
            val f=voice.root*tune*2.0.pow(identity.uniform(-12.0,12.0)/1200)
            val noise=DoubleArray(n) { rng.normal() };var y=DoubleArray(n)
            if(family=="chocalho") {
                val back=voice==Voice.CHOCALHO_BACK;val contacts=DoubleArray(n)
                val times=List(32) { rng.beta(2.0,if(back)4.0 else 2.4)*(if(back).075 else .11)*(1.2-.3*touch) }.sorted()
                for(at in times)contacts[(at*R).roundToInt()]+=rng.uniform(.3,1.0)*touch
                repeat(22) { k ->
                    val hz=1800*(14500.0/1800).pow(k/21.0)*identity.uniform(.94,1.06)*tune
                    if(hz < .47*R) {
                        val radius=exp(-damp/(R*identity.uniform(.002,.012)));val a=2*radius*cos(2*PI*hz/R)
                        val g=.12*exp(-hz/9000*(1-brightness));var p1=0.0;var p2=0.0
                        for(i in y.indices) { val z=g*contacts[i]+a*p1-radius*radius*p2;y[i]+=z;p2=p1;p1=z }
                    }
                }
                val rub=band(noise,3000.0,14000.0);var env=0.0;val decay=exp(-damp/(.0015*R))
                for(i in y.indices) { env=contacts[i]+decay*env;y[i]+=.18*rub[i]*env }
            } else {
                val surdo=family.startsWith("surdo");val tamb=family=="tamborim";val caixa=family=="caixa"
                val ratios=doubleArrayOf(1.0,1.48,1.59,2.14,2.30,2.65,2.92,3.16,3.5,4.06,4.61,5.14)
                val life=if(surdo)when(family) { "surdo_low"->1.25;"surdo_mid"->.95;else->.58 } else if(tamb).16 else if(caixa).24 else .38
                val position=if(surdo)rng.uniform(.18,.32) else rng.uniform(.28,.48)
                val glide=(if(surdo).008 else .025)*touch*touch
                val warp=DoubleArray(n) { val t=it.toDouble()/R;t+glide*.012*(1-exp(-t/.012)) }
                for((k,a) in ratios.withIndex()) {
                    val hz=f*a*identity.uniform(.994,1.006)
                    var gain=cos(position*k*1.4)*exp(-a*(.08+.45*(1-brightness)))/a.pow(if(surdo).8 else .45)
                    if(surdo && k<3)gain*=1.5
                    val decay=exp(-6.9078*damp/(R*(life/a.pow(if(surdo).6 else .45))));var env=gain
                    for(i in y.indices) { y[i]+=env*sin(2*PI*hz*warp[i]);env*=decay }
                }
                val width=max(4,(voice.widthMs*.001*(1.4-.5*touch)/strike*R).roundToInt())
                val force=DoubleArray(width) { .5-.5*cos(2*PI*it/(width-1)) };val sum=force.sum()
                // Long padded contacts use the existing FFT convolution instead of O(frames * width).
                for(i in force.indices)force[i]/=sum
                y=CalabashDsp.conv(y,force,n)
                val impact=if(surdo)band(noise,180.0,2200.0) else if(tamb)band(noise,1200.0,11000.0) else band(noise,700.0,9500.0)
                val wire=if(caixa)band(noise,1900.0,11500.0) else null
                for(i in y.indices) {
                    val t=i.toDouble()/R
                    y[i]+=(if(surdo).32 else 1.1)*brightness.pow(.8)*impact[i]*(1-exp(-t/.0003))*exp(-t/(if(surdo).005 else .0025))
                    if(wire!=null)y[i]+=1.5*wire[i]*(.3+.7*abs(sin(2*PI*f*t)*exp(-t/.055)))*(1-exp(-t/.0005))*exp(-damp*t/.025)
                }
                if(name.endsWith("rim")) {
                    for(i in y.indices)y[i]*=.6
                    for((hz0,g) in listOf(1650.0 to 1.2,2770.0 to .8,4230.0 to .55,6380.0 to .3)) {
                        val hz=hz0*identity.uniform(.96,1.04)*tune
                        for(i in y.indices) { val t=i.toDouble()/R;y[i]+=g*sin(2*PI*hz*t)*exp(-damp*t/(.009*(1650/hz).pow(.35))) }
                    }
                    for(i in y.indices)y[i]+=1.8*impact[i]*exp(-i.toDouble()/R/.0015)
                }
                if(name.endsWith("muted"))for(i in y.indices)y[i]*=exp(-(if(tamb)145 else 60)*loss(i.toDouble()/R,if(tamb).004 else .012,if(tamb).015 else .04)*damp)
                if(tamb) {
                    val delay=(rng.uniform(.0006,.0014)*R).roundToInt()
                    for(i in y.lastIndex downTo delay)y[i]+=.22*touch*y[i-delay]
                }
            }
            for(i in y.indices)out[start+i]+=y[i]*touch*min(i.toDouble()/R/.00015,1.0)/sqrt(voice.players.toDouble())
        }
        return out
    }
}
