package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** Acoustic-instrument percussion banks, not a melodic Patch engine.
 * Body and string modal parameters are design shapes, not measured instruments.
 * One finite contact drives stable, uncoupled modes; there is no energy feedback.
 * Velocity changes contact duration and the string/fingerboard collision threshold.
 * Takes vary contact position, contact width and seeded friction, not output gain.
 */
internal object LegacyTimberKits {
    const val VERSION = 1
    const val TAKES = 4
    val velocities = listOf(.30f, .65f, 1f)
    enum class Instrument(val body: Float, val string: Float) {
        GUITAR(108f, 164.81f), ACOUSTIC_BASS(78f, 82.41f), CELLO(91f, 130.81f), UPRIGHT(63f, 55f)
    }
    enum class Gesture { PALM, KNUCKLE, SLAP, DEAD_STRING, SCRATCH, HARMONIC }
    enum class Genre(val id: String, val title: String, val bpm: Int, val damping: Float) {
        POCKET("timber-pocket", "TIMBER FOLK POCKET", 92, .75f),
        BOOM_BAP("timber-boom-bap", "TIMBER WOOD BOOM BAP", 86, .52f),
        TWO_STEP("timber-two-step", "TIMBER STRING TWO STEP", 132, .32f)
    }
    data class Hit(val name: String, val instrument: Instrument, val gesture: Gesture, val tune: Float = 1f)
    private fun h(n: String, i: Instrument, g: Gesture, t: Float = 1f) = Hit(n,i,g,t)
    /** Genres change instruments and playing techniques in the core drum roles, not an FX mix. */
    fun hits(g: Genre): List<Hit> {
        val base = listOf(
            h("Upright Palm", Instrument.UPRIGHT, Gesture.PALM),
            h("Bass Guitar Heel", Instrument.ACOUSTIC_BASS, Gesture.PALM),
            h("Guitar String Slap", Instrument.GUITAR, Gesture.SLAP),
            h("Guitar Open Rake", Instrument.GUITAR, Gesture.SCRATCH),
            h("Guitar Dead Rake", Instrument.GUITAR, Gesture.DEAD_STRING),
            h("Cello Rib Scratch", Instrument.CELLO, Gesture.SCRATCH),
            h("Guitar Knuckle", Instrument.GUITAR, Gesture.KNUCKLE),
            h("Cello Knuckle", Instrument.CELLO, Gesture.KNUCKLE),
            h("Upright Fingerboard", Instrument.UPRIGHT, Gesture.SLAP),
            h("Bass Guitar Dead String", Instrument.ACOUSTIC_BASS, Gesture.DEAD_STRING),
            h("Cello String Snap", Instrument.CELLO, Gesture.SLAP),
            h("Guitar Bridge Ping", Instrument.GUITAR, Gesture.HARMONIC),
            h("Upright Rib Knock", Instrument.UPRIGHT, Gesture.KNUCKLE),
            h("Bass Guitar String Slap", Instrument.ACOUSTIC_BASS, Gesture.SLAP))
        return when(g) {
            Genre.POCKET -> base
            Genre.BOOM_BAP -> base.toMutableList().apply {
                this[0]=h("Bass Guitar Deep Heel",Instrument.ACOUSTIC_BASS,Gesture.PALM,.82f)
                this[1]=h("Upright Low Palm",Instrument.UPRIGHT,Gesture.PALM,.9f)
                this[2]=h("Upright Backbeat",Instrument.UPRIGHT,Gesture.SLAP,1.12f)
                this[3]=h("Bass Guitar Open Rake",Instrument.ACOUSTIC_BASS,Gesture.SCRATCH,1.15f)
                this[4]=h("Bass Guitar Muted Rake",Instrument.ACOUSTIC_BASS,Gesture.DEAD_STRING,1.15f)
            }
            Genre.TWO_STEP -> base.toMutableList().apply {
                this[0]=h("Cello Heel",Instrument.CELLO,Gesture.PALM,.83f)
                this[1]=h("Guitar Heel",Instrument.GUITAR,Gesture.PALM,.86f)
                this[2]=h("Bass Guitar Tight Slap",Instrument.ACOUSTIC_BASS,Gesture.SLAP,1.3f)
                this[3]=h("Cello Open Rake",Instrument.CELLO,Gesture.SCRATCH,1.35f)
                this[4]=h("Cello Dead Rake",Instrument.CELLO,Gesture.DEAD_STRING,1.35f)
            }
        }
    }
    fun names(g: Genre) = hits(g).map { it.name } + listOf("Knuckle Roll", "String Rake Riser")
    fun build(g: Genre): List<ArrangedPad> = names(g).mapIndexed { role, name ->
        val grid = velocities.map { v -> List(TAKES) { t -> render(g,role,v,t) } }
        ArrangedPad(grid.last().first(), when(role) {
            0,1 -> DrumClass.KICK; 2 -> DrumClass.SNARE
            3 -> DrumClass.HAT_OPEN; 4 -> DrumClass.HAT_CLOSED
            14,15 -> DrumClass.LOOP; else -> DrumClass.PERC
        }, displayName=name, velocityTakes=grid, muteGroup=if(role in 3..4) 1 else 0,
            source=mapOf("engine" to "TIMBER", "model" to VERSION.toString(), "genre" to g.name,
                "role" to role.toString(), "velocities" to velocities.joinToString(","),
                "takes" to TAKES.toString(), "bpm" to g.bpm.toString(),
                "articulation" to if(role==14) "roll" else if(role==15) "riser" else "finite",
                "sourceType" to "procedural-acoustic-percussion"))
    }
    /** Complete source identity is saved on the bank, including the ordered velocity/take grid. */
    fun replay(source: Map<String,String>): List<List<Snip>> {
        require(source["engine"]=="TIMBER" && source["model"]==VERSION.toString())
        val g=Genre.valueOf(source.getValue("genre")); val role=source.getValue("role").toInt()
        require(source["velocities"]==velocities.joinToString(",") && source["takes"]==TAKES.toString())
        return velocities.map { v -> List(TAKES) { t -> render(g,role,v,t) } }
    }
    fun render(g: Genre, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0..15 && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        if(role<14) return strike(hits(g)[role],g,velocity,take)
        val beat=60.0/g.bpm
        val out=FloatArray(((4*beat+.85)*Dsp.RATE).roundToInt())
        // Eighths, sixteenths, then thirty-seconds. Every onset is a fresh deterministic contact.
        val positions=listOf(0.0,.5,1.0,1.5,2.0,2.25,2.5,2.75)+List(8){3.0+it*.125}
        for((i,pos) in positions.withIndex()) {
            val progress=i.toFloat()/positions.lastIndex
            val hit=if(role==14) hits(g)[if(i%2==0) 6 else 7]
                else hits(g)[3].copy(tune=hits(g)[3].tune*(.8f+.6f*progress))
            val x=strike(hit,g,velocity*(.35f+.65f*progress),(take+i)%TAKES).samples
            val start=(pos*beat*Dsp.RATE).roundToInt()
            for(j in x.indices) if(start+j<out.size) out[start+j]+=x[j]*.42f
        }
        Dsp.fadeTail(out)
        check(out.all { it.isFinite() && abs(it)<1f })
        return Snip(out,1,Dsp.RATE)
    }
    private fun strike(hit: Hit, g: Genre, v: Float, take: Int): Snip {
        val rate=Dsp.RATE*Dsp.OVERSAMPLE
        val n=(.85*rate).roundToInt()
        val noise=Dsp.Noise(Dsp.seedFor("TIMBER",VERSION,hit.name,g.name,take))
        val position=.19f+.037f*take
        val duration=when(hit.gesture) {
            Gesture.PALM -> .0045; Gesture.KNUCKLE -> .0012
            Gesture.SLAP -> .0006; Gesture.DEAD_STRING -> .00035
            Gesture.SCRATCH -> .00045; Gesture.HARMONIC -> .001
        }*(1.45-.8*v)*(1+.06*(take-1.5))
        val pulse=max(4,(duration*rate).roundToInt())
        // Band-limit only the contact kernel. Filtering .85 seconds of zeros lets
        // the filter coast at subnormal magnitudes and wastes the render budget.
        // 256 guard frames retain the filter tail before zero extension to the body.
        val excitation=FloatArray(pulse+256)
        // Unit-area contact pulse; velocity scales its impulse after the resonators.
        for(i in 0 until pulse) excitation[i]=(sin(PI*i/(pulse-1)).pow(2)/pulse*2).toFloat()
        val damp=g.damping
        val body=hit.instrument.body*hit.tune
        fun mode(hz:Float,gain:Float,t60:Float):Modes.Mode =
            // Compensate the analytical 1/sin(theta) modal peak, independently of the rendered signal.
            Modes.fixed(hz,gain*sin(2*PI*hz/rate).toFloat(),t60)
        val bodyGain=when(hit.gesture) {
            Gesture.PALM -> 1f; Gesture.KNUCKLE -> .72f; Gesture.SLAP -> .36f
            Gesture.HARMONIC -> .12f; else -> .09f
        }
        val modes=listOf(1f,1.92f,2.73f,4.18f,6.43f).mapIndexed { k,r ->
            val weight=abs(cos(PI*position*(k+1))).toFloat()
            mode(body*r,bodyGain*weight/(1+k*.6f),(.16f+.3f*damp)/(1+k*.32f))
        }.toMutableList()
        val strings=when(hit.gesture) {
            Gesture.PALM,Gesture.KNUCKLE -> .06f
            Gesture.SLAP -> .52f; Gesture.HARMONIC -> .9f; else -> .28f
        }
        for(k in 1..10) {
            val hz=hit.instrument.string*hit.tune*k*sqrt(1f+.0004f*k*k)
            val harmonic=if(hit.gesture==Gesture.HARMONIC && k<3) .04f else 1f
            val weight=abs(sin(PI*position*k)).toFloat()*harmonic
            val decay=if(hit.gesture==Gesture.HARMONIC) .62f else if(hit.gesture==Gesture.DEAD_STRING) .035f else .08f+.17f*damp
            modes+=mode(hz,strings*weight/k*(.3f+.7f*v),decay/(1+k*.13f))
        }
        Tide.bandLimit(excitation,rate)
        val out=Modes.ring(excitation.copyOf(n),1f,modes,rate)
        val scrape=hit.gesture in listOf(Gesture.SCRATCH,Gesture.DEAD_STRING,Gesture.SLAP)
        val friction=Dsp.FixedOnePole(1200f+11500f*v,rate)
        val scratchLength=if(hit.gesture==Gesture.SCRATCH) .12f+.13f*damp else .013f
        val collision=(v-.42f).coerceAtLeast(0f)
        val contact=(.18f+.7f*v)
        for(i in out.indices) {
            val t=i.toFloat()/rate
            val hiss=friction.lp(noise.next())
            // Scratch has moving ridge contacts; dead strings and slaps hit the fingerboard once.
            val ridges=if(hit.gesture==Gesture.SCRATCH) .35f+.65f*abs(sin(2*PI*(73+take*7)*t)).toFloat() else 1f
            val grain=if(scrape) hiss*exp(-t/scratchLength)*ridges*(.18f+.5f*collision) else 0f
            out[i]=(out[i]+grain)*contact*.55f
        }
        Tide.bandLimit(out,rate)
        val pcm=Dsp.decimate(out,Dsp.RATE)
        val dc=Dsp.FixedOnePole(20f,Dsp.RATE)
        val mean=pcm.average().toFloat()
        for(i in pcm.indices) { val x=pcm[i]-mean; pcm[i]=x-dc.lp(x) }
        // Fixed gain preserves native touch dynamics. No per-strike level matching or limiting.
        Dsp.fadeTail(pcm)
        check(pcm.all { it.isFinite() && abs(it)<1f }) { "TIMBER exceeded its fixed output budget" }
        return Snip(pcm,1,Dsp.RATE)
    }
}
