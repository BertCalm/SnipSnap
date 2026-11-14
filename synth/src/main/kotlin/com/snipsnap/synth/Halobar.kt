package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

enum class HalobarVoice { VIBRAPHONE, MARIMBA, XYLOPHONE, GLOCKENSPIEL, CROTALES, TUBULAR_BELLS }

/**
 * A clear mallet strike hands its body to rhythmic echoes, then a moving halo.
 *
 * Independently damped bar modes, with separate contact noise and a body
 * send. The send reduces the root so coherent mono echoes do not cancel a long
 * vibraphone note. Returns never re-excite the bars. The fixed-delay feedback
 * path contracts: its Butterworth low-pass has gain <= 1, tanh(d*x)/d has slope
 * <= 1, and the loop gain never exceeds .84. Phase motion and the finite FIR
 * halo sit outside that loop. No limiter supplies the stability argument.
 *
 * Ratios reuse Modes' sourced tuned-bar tables (1:4:10 and 1:3:6), plus its
 * free-free metal bar (1:2.756:5.404:8.933) for glockenspiel. Crotales
 * uses a separate five-mode, center-weighted-disc inspired musical shape.
 * Tubular bells adds a lower, long root-led bell shape.
 * Contact, gains, decays and processing laws are musical shapes, not measured
 * materials. The first three voices began in the Python proof. The motor is amplitude modulation, not fan geometry.
 * HOLD is finite, including its reserved top; a long filed LOOP is not a wrap.
 */
object Halobar {
    const val RATE = Dsp.RATE
    const val MODEL_VERSION = 1
    const val ROOT_MIDI = 60
    const val MIN_MIDI = 48
    const val MAX_MIDI = 84
    const val BPM = 72.0
    const val MAX_SECONDS = 12.0
    const val MAX_SOURCE_SECONDS = 8.0
    const val MAX_TAIL_SECONDS = 6.0
    const val MAX_FINITE_HOLD = .98f
    private const val TWO_PI = 2.0 * PI
    private const val LOG_1000 = 6.907755278982137
    private const val CACHE_BYTES = 6 * 1024 * 1024
    private const val STEM_CACHE_BYTES = 8 * 1024 * 1024
    private const val STAGE_CACHE_BYTES = 8 * 1024 * 1024
    private val divisions = doubleArrayOf(1.5, 1.0, .75, .5, .375, .25)
    val RATE_LABELS = listOf("Dotted quarter", "Quarter", "Dotted eighth", "Eighth", "Dotted sixteenth", "Sixteenth")
    fun rateLabel(value: Float): String = RATE_LABELS[min(5,((if(value.isFinite())value.coerceIn(0f,1f) else .45f)*6).toInt())]
    private val specs = listOf(MacroSpec("TUNE", .5f), MacroSpec("TOUCH", .4f),
        MacroSpec("RATE", .45f), MacroSpec("RETURN", .45f), MacroSpec("SPACE", .35f),
        MacroSpec("MOTION", .25f), MacroSpec("HOLD", .3f))

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: HalobarVoice): List<MacroSpec> = specs
    fun defaults(voice: HalobarVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(macros: Map<String, Float>, voice: HalobarVoice): Map<String, Float> =
        defaults(voice).mapValues { (name, fallback) -> macros[name]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback }
    fun rootMidi(voice: HalobarVoice) = when(voice) {
        HalobarVoice.CROTALES -> 96
        HalobarVoice.GLOCKENSPIEL -> 72
        else -> ROOT_MIDI
    }
    fun minimumMidi(voice: HalobarVoice) = when(voice) {
        HalobarVoice.CROTALES -> 84
        HalobarVoice.GLOCKENSPIEL -> 60
        HalobarVoice.TUBULAR_BELLS -> 48
        HalobarVoice.XYLOPHONE -> 57
        else -> MIN_MIDI
    }
    fun maximumMidi(voice: HalobarVoice) = when(voice) {
        HalobarVoice.CROTALES -> 108
        HalobarVoice.GLOCKENSPIEL -> 96
        else -> MAX_MIDI
    }
    fun midiFor(voice: HalobarVoice, tune: Float): Int {
        val t = if (tune.isFinite()) tune.coerceIn(0f, 1f) else .5f
        val bottom = minimumMidi(voice)
        val root = rootMidi(voice)
        return (if (t < .5f) bottom + 2f*(root-bottom)*t else root + 2f*(maximumMidi(voice)-root)*(t-.5f)).roundToInt()
    }
    fun tuneForMidi(voice: HalobarVoice, midi: Int): Float {
        val bottom = minimumMidi(voice)
        val root = rootMidi(voice)
        val top = maximumMidi(voice)
        require(midi in bottom..top) { "HALOBAR MIDI must be $bottom..$top for $voice" }
        return if (midi <= root) (midi-bottom)/(2f*(root-bottom)) else .5f+(midi-root)/(2f*(top-root))
    }
    fun frequencyFor(voice: HalobarVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(@Suppress("UNUSED_PARAMETER") hold: Float) = false
    fun scramble(voice: HalobarVoice, random: Random = Random.Default, temperature: Float = .35f,
        near: Patch? = null): Map<String, Float> {
        val d = defaults(voice)
        val start = settled(d + (near?.macros ?: emptyMap()), voice)
        return Dsp.scrambleNear(start, if (temperature.isFinite()) temperature.coerceIn(0f,1f) else .35f, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(MAX_FINITE_HOLD) }
    }

    private data class Material(val ratios: DoubleArray, val gains: DoubleArray,
        val t60: DoubleArray, val soft: Double, val hard: Double, val contactFloor: Double = .000065)
    private fun material(voice: HalobarVoice): Material = when (voice) {
        HalobarVoice.VIBRAPHONE -> Material(ratios(Modes.Material.WOOD_MARIMBA),
            doubleArrayOf(1.0,.47,.20), doubleArrayOf(6.2,1.8,.65), .0014,.00012)
        HalobarVoice.MARIMBA -> Material(ratios(Modes.Material.WOOD_MARIMBA),
            doubleArrayOf(1.0,.66,.34), doubleArrayOf(1.65,.43,.16), .0023,.00018)
        HalobarVoice.XYLOPHONE -> Material(ratios(Modes.Material.WOOD_XYLO),
            doubleArrayOf(1.0,1.10,.55), doubleArrayOf(.62,.18,.075), .00085,.00007)
        // A compact steel-bar approximation: four inharmonic modes and a hard
        // contact, without the vibraphone motor or wooden tuned harmonics.
        // Ratios are sourced by Modes; weights, losses and contact are shapes.
        HalobarVoice.GLOCKENSPIEL -> Material(ratios(Modes.Material.METAL_BAR),
            doubleArrayOf(1.0,.50,.26,.12), doubleArrayOf(3.8,1.5,.60,.28), .0010,.00009)
        // A center-weighted bronze-disc inspired spectrum, not a thin-plate
        // solver or measured crotale. Ratios, weights, decays and contact are
        // musical shapes; the near octave and longer ring distinguish bars.
        HalobarVoice.CROTALES -> Material(doubleArrayOf(1.0,2.01,3.94,6.10,8.60),
            doubleArrayOf(1.0,.38,.23,.13,.07), doubleArrayOf(8.0,4.8,2.8,1.6,.9), .0018,.00025,.000094)
        // Root-led tubular-bell shape: octave/chime pair over a low, long body.
        // Not a measured tube or a virtual-pitch reconstruction. Keeping an
        // explicit root makes chord voicings and keygroup roots unambiguous.
        HalobarVoice.TUBULAR_BELLS -> Material(doubleArrayOf(1.0,2.0,2.756,4.0,5.404,8.933),
            doubleArrayOf(1.0,.28,.12,.10,.06,.03), doubleArrayOf(9.0,6.0,4.5,3.0,2.0,1.0), .0030,.00040,.00012)
    }
    private fun ratios(type: Modes.Material) = Modes.tableFor(type).map { it.ratio.toDouble() }.toDoubleArray()

    internal data class Plan(val midi: Int, val hz: Double, val frames: Int,
        val sourceFrames: Int, val delayFrames: Int, val headFrames: List<Int>,
        val loopGain: Double, val loopCutoffHz: Double, val effectiveHold: Float,
        val contactSeconds: Double, val modeT60: List<Double>, val tailCapped: Boolean,
        val sourceCapped: Boolean, val rateIndex: Int)

    internal fun plan(voice: HalobarVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Plan {
        val m = settled(macros, voice)
        val mat = material(voice)
        val midi = midiFor(voice, m.getValue("TUNE"))
        val hz = Keys.midiHz(midi).toDouble()
        val hold = m.getValue("HOLD").coerceAtMost(MAX_FINITE_HOLD)
        val life = .5 + 1.8*hold
        val register = (261.625565/hz).pow(.18)
        val t60 = mat.t60.map { it*life*register }.toMutableList()
        // The lowest wooden bars retain a pitched bass body even with HOLD
        // down. This is actual fundamental decay, not silence added to change
        // a duration label. The floor recedes above A3 and leaves the default
        // F3/F4/F5 reference decays unchanged.
        if(voice==HalobarVoice.MARIMBA) t60[0]=max(t60[0],1.6*min(1.0,(220.0/hz).pow(4)))
        val bodySeconds = t60.max()+.05
        val sourceFrames = ceil(min(MAX_SOURCE_SECONDS,bodySeconds)*RATE).toInt()
        val index = min(5, (m.getValue("RATE")*6).toInt())
        val beat = 60.0/BPM
        val delay = (beat*divisions[index]*RATE).roundToInt()
        val heads = listOf(delay,delay+(beat*.5*RATE).roundToInt(),2*delay)
        val gain = .12+.72*m.getValue("RETURN")
        val repeats = ceil(-LOG_1000/ln(gain)).toInt()
        val desiredTail = repeats*delay.toDouble()/RATE + (heads.max()-delay).toDouble()/RATE + .9
        val hasReturn = m.getValue("SPACE") > 0f
        val seconds = if (hasReturn) min(MAX_SECONDS,sourceFrames.toDouble()/RATE+min(MAX_TAIL_SECONDS,desiredTail)) else sourceFrames.toDouble()/RATE
        val frames = ceil(seconds*RATE).toInt().coerceAtMost((MAX_SECONDS*RATE).toInt())
        val u = .78*velocityValue(velocity)
        val touch = (m.getValue("TOUCH")+.35*(u-.78)).coerceIn(0.0,1.0)
        val contact = (mat.soft*(mat.hard/mat.soft).pow(touch)*(261.625565/hz).pow(.65)).coerceIn(mat.contactFloor,.004)
        return Plan(midi,hz,frames,sourceFrames,delay,heads,gain,
            5700.0*(1900.0/5700.0).pow(m.getValue("RETURN").toDouble()),hold,
            contact,t60,hasReturn && (desiredTail>MAX_TAIL_SECONDS || sourceFrames.toDouble()/RATE+desiredTail>MAX_SECONDS),
            bodySeconds>MAX_SOURCE_SECONDS,index)
    }
    fun drumClassFor(voice: HalobarVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (plan(voice,macros).frames > (1.5*RATE).toInt()) DrumClass.LOOP else DrumClass.PERC
    private fun velocityValue(v: Float): Double = (if (v.isFinite()) v.coerceIn(0f,1f) else 1f).toDouble()
    private fun cancelCheck() { if (Thread.currentThread().isInterrupted) throw CancellationException("HALOBAR render cancelled") }

    internal data class Probe(val samples: FloatArray, val plan: Plan, val rawPeak: Float,
        val loopPeak: Double, val kernelL1: Double, val endingRmsBeforeFade: Double,
        val outputGain: Double)

    private data class RenderKey(val voice: HalobarVoice, val controls: List<Float>, val velocity: Float, val model: Int)
    private val cache = LinkedHashMap<RenderKey,FloatArray>(8,.75f,true)
    private var cacheBytes = 0
    private data class StemKey(val voice: HalobarVoice, val controls: List<Float>, val velocity: Float)
    private data class Stems(val plan: Plan, val dry: FloatArray, val echo: FloatArray?,
        val halo: FloatArray?, val loopPeak: Double, val kernelNorm: Double)
    private val stemCache = LinkedHashMap<StemKey,Stems>(2,.75f,true)
    private var stemCacheBytes = 0
    private data class SourceKey(val voice: HalobarVoice, val midi: Int, val touch: Float,
        val hold: Float, val motor: Float, val velocity: Float, val dryFrames: Int)
    private data class Source(val dry: FloatArray, val send: FloatArray, val kernelNorm: Double)
    private data class ReturnKey(val source: SourceKey, val division: Int, val amount: Float, val frames: Int)
    private data class Return(val dry: FloatArray, val echo: FloatArray, val loopPeak: Double, val kernelNorm: Double)
    private val sourceCache = LinkedHashMap<SourceKey,Source>(2,.75f,true)
    private val returnCache = LinkedHashMap<ReturnKey,Return>(2,.75f,true)
    private var sourceCacheBytes = 0
    private var returnCacheBytes = 0
    internal fun clearCache() {
        synchronized(cache) { cache.clear(); cacheBytes=0 }
        synchronized(stemCache) { stemCache.clear(); stemCacheBytes=0 }
        synchronized(sourceCache) { sourceCache.clear(); sourceCacheBytes=0 }
        synchronized(returnCache) { returnCache.clear(); returnCacheBytes=0 }
    }
    internal fun stageCacheBytesForProbe(): List<Int> = listOf(
        synchronized(sourceCache) { sourceCacheBytes }, synchronized(returnCache) { returnCacheBytes })

    private fun rememberSource(key: SourceKey, source: Source) = synchronized(sourceCache) {
        fun bytes(s: Source) = (s.dry.size+s.send.size)*4
        val size=bytes(source)
        if(size<=STAGE_CACHE_BYTES) {
            sourceCache.remove(key)?.let { sourceCacheBytes-=bytes(it) }
            while(sourceCacheBytes+size>STAGE_CACHE_BYTES && sourceCache.isNotEmpty()) {
                val iterator=sourceCache.entries.iterator();val entry=iterator.next()
                sourceCacheBytes-=bytes(entry.value);iterator.remove()
            }
            sourceCache[key]=source;sourceCacheBytes+=size
        }
    }
    private fun rememberReturn(key: ReturnKey, returns: Return) = synchronized(returnCache) {
        fun bytes(r: Return) = (r.dry.size+r.echo.size)*4
        val size=bytes(returns)
        if(size<=STAGE_CACHE_BYTES) {
            returnCache.remove(key)?.let { returnCacheBytes-=bytes(it) }
            while(returnCacheBytes+size>STAGE_CACHE_BYTES && returnCache.isNotEmpty()) {
                val iterator=returnCache.entries.iterator();val entry=iterator.next()
                returnCacheBytes-=bytes(entry.value);iterator.remove()
            }
            returnCache[key]=returns;returnCacheBytes+=size
        }
    }
    private fun cachedStems(key: StemKey): Stems? = synchronized(stemCache) { stemCache[key] }
    private fun rememberStems(key: StemKey, stems: Stems) = synchronized(stemCache) {
        fun bytes(s: Stems) = (s.dry.size+(s.echo?.size ?: 0)+(s.halo?.size ?: 0))*4
        val size=bytes(stems)
        if(size<=STEM_CACHE_BYTES) {
            stemCache.remove(key)?.let { stemCacheBytes-=bytes(it) }
            while(stemCacheBytes+size>STEM_CACHE_BYTES && stemCache.isNotEmpty()) {
                val iterator=stemCache.entries.iterator();val entry=iterator.next()
                stemCacheBytes-=bytes(entry.value);iterator.remove()
            }
            // These arrays are private and never modified after preparation.
            // Finishing always owns a new output, including on concurrent hits.
            stemCache[key]=stems;stemCacheBytes+=size
        }
    }
    private fun cached(key: RenderKey): FloatArray? = synchronized(cache) { cache[key]?.copyOf() }
    private fun remember(key: RenderKey, samples: FloatArray) = synchronized(cache) {
        val size = samples.size*4
        if (size <= CACHE_BYTES) {
            cache.remove(key)?.let { cacheBytes-=it.size*4 }
            while (cacheBytes+size>CACHE_BYTES && cache.isNotEmpty()) {
                val iterator=cache.entries.iterator(); val entry=iterator.next()
                cacheBytes-=entry.value.size*4; iterator.remove()
            }
            cache[key]=samples.copyOf(); cacheBytes+=size
        }
    }

    fun render(voice: HalobarVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        model: Int = MODEL_VERSION): Snip {
        cancelCheck()
        require(model == MODEL_VERSION) { "unsupported HALOBAR model $model" }
        val m=settled(macros,voice)
        // Canonicalize the reserved HOLD top before keying or any seed calculation.
        val canonical=m + ("HOLD" to m.getValue("HOLD").coerceAtMost(MAX_FINITE_HOLD))
        val v=velocityValue(velocity).toFloat()
        val key=RenderKey(voice,specs.map { canonical.getValue(it.name) },v,model)
        cached(key)?.let { return Snip(it,1,RATE) }
        val samples=if(v==0f) FloatArray(plan(voice,canonical,v).frames) else {
            val space=canonical.getValue("SPACE")
            // At positive SPACE, only the final echo/halo gains change. The
            // prepared source and returns depend on every other control and
            // velocity. SPACE=0 has a different duration and bypasses this cache.
            val stemKey=if(space>0f) StemKey(voice,specs.filter { it.name!="SPACE" }
                .map { canonical.getValue(it.name) },v) else null
            val stems=stemKey?.let(::cachedStems) ?: prepare(voice,canonical,
                plan(voice,canonical,v),v,Dsp.OVERSAMPLE,reuseStages=true).also { if(stemKey!=null)rememberStems(stemKey,it) }
            finish(stems,space,normalize=true).samples
        }
        remember(key,samples)
        return Snip(samples,1,RATE)
    }

    /** A fresh mallet landing; take zero preserves every existing model-1 byte. */
    fun renderTake(voice: HalobarVoice, macros: Map<String, Float>, velocity: Float,
        seed: Int, take: Int, model: Int = MODEL_VERSION): Snip {
        require(take >= 0) { "a take is 0 or more" }
        require(model == MODEL_VERSION) { "unsupported HALOBAR model $model" }
        if (take == 0) return render(voice, macros, velocity, model)
        cancelCheck()
        val m = settled(macros, voice)
        val v = velocityValue(velocity).toFloat()
        val identity = Dsp.seedFor("HALOBAR_LANDING_V1", seed, take)
        val rng = Random(identity)
        val p = plan(voice, m, v).let { it.copy(contactSeconds = it.contactSeconds * (1.0 + .14 * rng.nextDouble())) }
        if (v == 0f) return Snip(FloatArray(p.frames), 1, RATE)
        // Bypass both caches: a nonreference landing must never reuse take zero.
        return Snip(finish(prepare(voice, m, p, v, Dsp.OVERSAMPLE, identity),
            m.getValue("SPACE"), normalize = true).samples, 1, RATE)
    }

    /** Fresh calculation for diagnostics and timing; never satisfied by completed-audio cache. */
    internal fun renderProbe(voice: HalobarVoice, macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f, normalize: Boolean = false, oversample: Int = Dsp.OVERSAMPLE): Probe {
        require(oversample == 4 || oversample == 8) { "HALOBAR probe rate must be 4x or 8x" }
        cancelCheck()
        val m=settled(macros,voice); val p=plan(voice,m,velocity)
        if (velocityValue(velocity)==0.0) return Probe(FloatArray(p.frames),p,0f,0.0,0.0,0.0,1.0)
        return finish(prepare(voice,m,p,velocity,oversample),m.getValue("SPACE"),normalize)
    }

    private fun prepare(voice: HalobarVoice,m: Map<String,Float>,p: Plan,velocity: Float,
        oversample: Int, landing: Int? = null, reuseStages: Boolean = false): Stems {
        // A probe and a fresh landing always execute the reference path. Stage
        // reuse is only for positive-SPACE, reference-take production renders.
        if(reuseStages && landing==null && oversample==Dsp.OVERSAMPLE && m.getValue("SPACE")>0f)
            return prepareReusable(voice,m,p,velocity)
        val rate=RATE*oversample
        // A finite source needs only a short filter flush. Processing seconds of
        // exact zero through Float IIRs enters subnormal arithmetic on the JVM.
        val dry=FloatArray(min(p.frames*oversample,p.sourceFrames*oversample+256))
        val send=if(m.getValue("SPACE")>0f) FloatArray(p.frames*oversample) else null
        val kernelNorm=bars(voice,m,p,velocity,rate,oversample,dry,send,landing)
        val loopPeak=if(send!=null) echoes(send,m,p,rate,oversample) else 0.0
        // Only nonlinear audio needs 4x. The allpasses and finite convolution
        // are linear at the delivery rate, with ample Nyquist guard for the LFO.
        val sourceOut=down(dry,rate)
        val echo=send?.let { down(it,rate) }
        echo?.let { phase(it,m.getValue("MOTION").toDouble()) }
        val halo=echo?.let(::haloConvolution)
        cancelCheck()
        return Stems(p,sourceOut,echo,halo,loopPeak,kernelNorm)
    }

    private fun prepareReusable(voice: HalobarVoice,m: Map<String,Float>,p: Plan,velocity: Float): Stems {
        val oversample=Dsp.OVERSAMPLE;val rate=RATE*oversample
        val dryFrames=min(p.frames*oversample,p.sourceFrames*oversample+256)
        // Only vibraphone puts MOTION in its source. The other motors are
        // inactive; their motion edits can reuse the unmodulated echo too.
        val sourceKey=SourceKey(voice,p.midi,m.getValue("TOUCH"),p.effectiveHold,
            if(voice==HalobarVoice.VIBRAPHONE)m.getValue("MOTION") else 0f,velocity,dryFrames)
        val returnKey=ReturnKey(sourceKey,p.rateIndex,m.getValue("RETURN"),p.frames)
        val returns=synchronized(returnCache) { returnCache[returnKey] } ?: run {
            val source=synchronized(sourceCache) { sourceCache[sourceKey] } ?: run {
                val dry=FloatArray(dryFrames)
                // bars writes only the finite body. The original remaining
                // send frames are exact +0; recreate those when echoing.
                val send=FloatArray(min(dryFrames,p.sourceFrames*oversample))
                val norm=bars(voice,m,p,velocity,rate,oversample,dry,send)
                val prepared=Source(down(dry,rate),send,norm)
                cancelCheck();rememberSource(sourceKey,prepared);prepared
            }
            val send=FloatArray(p.frames*oversample)
            source.send.copyInto(send)
            val peak=echoes(send,m,p,rate,oversample)
            val prepared=Return(source.dry,down(send,rate),peak,source.kernelNorm)
            cancelCheck();rememberReturn(returnKey,prepared);prepared
        }
        // All cached arrays remain immutable. Phase, convolution and finishing
        // own their outputs, including concurrent edits sharing one source.
        val echo=returns.echo.copyOf()
        phase(echo,m.getValue("MOTION").toDouble())
        val halo=haloConvolution(echo)
        cancelCheck()
        return Stems(p,returns.dry,echo,halo,returns.loopPeak,returns.kernelNorm)
    }

    private fun finish(stems: Stems,space: Float,normalize: Boolean): Probe {
        val p=stems.plan
        val out=stems.dry.copyOf(p.frames)
        var ending=0.0
        if(stems.echo!=null) {
            val echo=stems.echo
            val halo=checkNotNull(stems.halo)
            val echoGain=1.55*space.toDouble().pow(.65)
            val haloGain=.75*space.toDouble().pow(1.2)
            val fade=min((.1*RATE).roundToInt(),out.size)
            for(i in out.indices) {
                var wet=echoGain*echo[i]+haloGain*halo[i]
                if(i>=out.size-fade) {
                    ending+=wet*wet
                    if(p.tailCapped) wet*=cosineFall(i-(out.size-fade),fade)
                }
                out[i]=(out[i]+wet).toFloat()
            }
            ending=sqrt(ending/fade)
        }
        var peak=0f; var mean=0.0
        for(x in out) { peak=max(peak,abs(x)); mean+=x }
        mean/=out.size
        val hp=Dsp.FixedOnePole(20f,RATE)
        for(i in out.indices) { val x=out[i]-mean.toFloat(); out[i]=x-hp.lp(x) }
        var before=0f; for(x in out) before=max(before,abs(x))
        if(normalize) Dsp.levelTo(out,RATE,Dsp.MELODIC_LOUDNESS_TARGET)
        var after=0f; for(x in out) after=max(after,abs(x))
        Dsp.fadeTail(out)
        cancelCheck()
        return Probe(out,p,peak,stems.loopPeak,stems.kernelNorm,ending,if(before>0)after/before.toDouble() else 1.0)
    }

    private fun cosineFall(i: Int,n: Int) = if(n<=1) 0.0 else .5+.5*cos(PI*i/(n-1))
    private fun down(raw: FloatArray,rate: Int): FloatArray {
        cancelCheck(); Tide.bandLimit(raw,rate)
        if(rate==RATE*Dsp.OVERSAMPLE) return Dsp.decimate(raw,RATE)
        var snip=Snip(raw,1,rate)
        while(snip.sampleRate>RATE) snip=Resampler.resample(snip,snip.sampleRate/2)
        return snip.samples
    }

    private fun bars(voice: HalobarVoice,m: Map<String,Float>,p: Plan,v: Float,
        rate: Int,oversample: Int,dry: FloatArray,send: FloatArray?,landing: Int? = null): Double {
        val material=material(voice)
        val length=max(5,(p.contactSeconds*rate).roundToInt())
        val force=FloatArray(length+(.002*rate).roundToInt())
        var sum=0.0
        for(i in 0 until length) { force[i]=(.5-.5*cos(TWO_PI*i/(length-1))).toFloat(); sum+=force[i] }
        for(i in 0 until length) force[i]=(force[i]/sum).toFloat()
        Tide.bandLimit(force,rate)
        val norm=force.sumOf { abs(it.toDouble()) }
        val u=.78*velocityValue(v)
        val amplitude=.63*u
        val landingRng = landing?.let { Random(it) }
        val states=Array(material.ratios.size) { index ->
            val hz=p.hz*material.ratios[index]
            val r=exp(-LOG_1000/(p.modeT60[index]*rate))
            val theta=TWO_PI*hz/rate
            doubleArrayOf(2*r*cos(theta),-r*r,(material.gains[index] * (landingRng?.let { .96 + .08 * it.nextDouble() } ?: 1.0))*r*sin(theta),0.0,0.0,
                if(hz<.44*RATE)1.0 else 0.0)
        }
        val end=min(dry.size,p.sourceFrames*oversample)
        val fade=min(end,(.1*rate).roundToInt())
        val motor=2.0+5*m.getValue("MOTION")
        val step=TWO_PI*motor/rate; val cr=cos(step);val sr=sin(step)
        var c=1.0;var s=0.0
        val depth=if(voice==HalobarVoice.VIBRAPHONE) .65*m.getValue("MOTION") else 0.0
        val openDecay=exp(-1.0/(rate*.010));var closed=1.0
        for(i in 0 until end) {
            if(i and 16383==0) cancelCheck()
            val x=if(i>0&&i<=force.size) force[i-1].toDouble() else 0.0
            var body=0.0;var feed=0.0
            for(k in states.indices) {
                val z=states[k]
                if(z[5]==0.0) continue
                val y=z[2]*x+z[0]*z[3]+z[1]*z[4]
                z[4]=z[3];z[3]=y
                body+=y;feed+=y*(if(k==0).35 else 1.0)
            }
            val tremolo=1-depth*.5+depth*.5*c
            val damping=if(i>=end-fade) cosineFall(i-(end-fade),fade) else 1.0
            dry[i]=(body*amplitude*tremolo*damping).toFloat()
            if(send!=null) send[i]=(feed*amplitude*tremolo*(1-closed)*damping).toFloat()
            // Once subtraction has rounded to unity, this envelope can no
            // longer change the send. Stop its otherwise subnormal tail;
            // the factor (1-closed) stays exactly the same Double value.
            if(closed!=0.0) {
                closed*=openDecay
                if(1-closed==1.0)closed=0.0
            }
            if(depth!=0.0) {
                val nc=c*cr-s*sr;s=s*cr+c*sr;c=nc
            }
        }
        val noise=Dsp.Noise(if (landing == null) Dsp.seedFor("HALOBAR_SOURCE_V1",voice.name,p.midi)
            else Dsp.seedFor("HALOBAR_SOURCE_TAKE_V1",voice.name,p.midi,landing))
        val hitFrames=(RATE*.009).toInt()
        val hit=FloatArray(hitFrames)
        val lp1=Dsp.FixedOnePole(min(8500.0,p.hz*9).toFloat(),RATE)
        val lp2=Dsp.FixedOnePole(min(8500.0,p.hz*9).toFloat(),RATE)
        val gain=if(voice==HalobarVoice.XYLOPHONE) .025+.08*m.getValue("TOUCH") else .015+.055*m.getValue("TOUCH")
        for(i in hit.indices) hit[i]=(lp2.lp(lp1.lp(noise.next()))*(.5-.5*cos(TWO_PI*i/(hit.size-1)))*exp(-i/(RATE*.0018))*gain*u).toFloat()
        val internalHit=FloatArray(hit.size*oversample)
        for(i in internalHit.indices) {
            val j=i/oversample;val f=(i%oversample).toFloat()/oversample
            internalHit[i]=hit[j]*(1-f)+(if(j+1<hit.size)hit[j+1] else 0f)*f
        }
        Tide.bandLimit(internalHit,rate)
        for(i in 0 until min(dry.size,internalHit.size)) dry[i]+=internalHit[i]
        return norm
    }

    /** Fixed two-pole Butterworth, Double state and no resonant gain. */
    private class LowPass(hz: Double,rate: Int) {
        private val k=tan(PI*hz/rate)
        private val norm=1/(1+sqrt(2.0)*k+k*k)
        private val b0=k*k*norm;private val b1=2*b0
        private val a1=2*(k*k-1)*norm;private val a2=(1-sqrt(2.0)*k+k*k)*norm
        private var z1=0.0;private var z2=0.0
        fun step(x: Double): Double {
            val y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b0*x-a2*y;return y
        }
    }

    /**
     * The small-signal Taylor remainder is below 9e-15*abs(x) at abs(x)<=1/16.
     * The wider 2e-12 bound includes coefficient/evaluation roundoff, the
     * reference tanh's error and the final gain/division/addition. Accept a
     * candidate only when both bounds round to the same stored Float and the
     * existing Double peak cannot move. Ambiguous cases retain the reference.
     */
    private fun quantizedFeedback(fed: Double,gain: Double,drive: Double,x: Double,peak: Double): Double {
        val ax=abs(x)
        if(ax>=1e-100 && ax<=.0625 && gain>=0.0 && gain<=1.0 && drive>=1.0 && drive<=4.0) {
            val z=x*x
            val approximate=x*(1+z*(-1.0/3+z*(2.0/15+z*(-17.0/315+z*(62.0/2835)))))
            val q=fed+gain*approximate/drive
            val error=2e-12*(abs(fed)+ax)
            val lower=q-error;val upper=q+error
            if(lower.toFloat().toRawBits()==upper.toFloat().toRawBits() && max(abs(lower),abs(upper))<=peak) return q
        }
        return fed+gain*tanh(x)/drive
    }

    internal fun feedbackSampleProbe(fed: Double,gain: Double,drive: Double,x: Double,peak: Double): Double =
        quantizedFeedback(fed,gain,drive,x,peak)

    private fun echoes(bus: FloatArray,m: Map<String,Float>,p: Plan,rate: Int,oversample: Int,
        gainOverride: Double? = null, energyTrace: MutableList<Double>? = null): Double {
        val heads=IntArray(p.headFrames.size) { p.headFrames[it]*oversample }
        val delay=p.delayFrames*oversample
        val history=FloatArray(heads.max()+1)
        val low=LowPass(p.loopCutoffHz,rate)
        val sendLow=LowPass(6800.0,rate)
        val hp=Dsp.FixedOnePole(85f,rate)
        val drive=1+2.5*m.getValue("RETURN")
        var index=0;var peak=0.0;var passEnergy=0.0
        val gain=gainOverride ?: p.loopGain
        // The source has faded to exact zero; 100 ms also settles the 85 Hz
        // send filter far below PCM resolution. Feedback keeps running.
        val feedEnd=min(bus.size,p.sourceFrames*oversample+(rate*.1).roundToInt())
        for(i in bus.indices) {
            if(i and 16383==0) cancelCheck()
            val input=bus[i]
            val fed=if(i<feedEnd) sendLow.step((input-hp.lp(input)).toDouble()) else 0.0
            // Each offset is smaller than the ring. One wrap preserves the
            // exact read position without integer division for every sample.
            val delayedIndex=index-delay
            val delayed=if(i>=delay)history[if(delayedIndex<0)delayedIndex+history.size else delayedIndex].toDouble() else 0.0
            val x=drive*low.step(delayed)
            // Energy traces retain the full reference Double calculation.
            val q=if(energyTrace==null) quantizedFeedback(fed,gain,drive,x,peak) else fed+gain*tanh(x)/drive
            history[index]=q.toFloat();peak=max(peak,abs(q))
            if(energyTrace!=null) { passEnergy+=q*q; if((i+1)%delay==0) { energyTrace.add(passEnergy);passEnergy=0.0 } }
            var out=0.0
            for(k in heads.indices) if(i>=heads[k]) {
                val read=index-heads[k]
                out+=history[if(read<0)read+history.size else read]*(if(k==0).62 else if(k==1).25 else .13)
            }
            bus[i]=out.toFloat()
            index++;if(index==history.size)index=0
        }
        return peak
    }

    internal fun feedbackEnergyProbe(gainOverride: Double? = null): List<Double> {
        val m=settled(mapOf("RETURN" to 1f),HalobarVoice.VIBRAPHONE)
        val p=plan(HalobarVoice.VIBRAPHONE,m)
        val rate=RATE*Dsp.OVERSAMPLE;val delay=p.delayFrames*Dsp.OVERSAMPLE
        val input=FloatArray(delay*12)
        for(i in 0 until delay)input[i]=(1e-5*sin(TWO_PI*700*i/rate)*(.5-.5*cos(TWO_PI*i/(delay-1)))).toFloat()
        val trace=mutableListOf<Double>()
        echoes(input,m,p,rate,Dsp.OVERSAMPLE,gainOverride,trace)
        return trace
    }

    private fun phase(bus: FloatArray,motion: Double) {
        if(motion==0.0)return
        val coefficients=doubleArrayOf(310.0,970.0,3100.0).map { val k=tan(PI*it/RATE);(k-1)/(k+1) }
        val x=DoubleArray(3);val y=DoubleArray(3)
        val angle=TWO_PI*(.08+.18*motion)/RATE;val cr=cos(angle);val sr=sin(angle)
        var c=1.0;var s=0.0
        for(i in bus.indices) {
            var z=bus[i].toDouble()
            for(k in 0..2) { val q=coefficients[k]*z+x[k]-coefficients[k]*y[k];x[k]=z;y[k]=q;z=q }
            val mix=motion*(.32+.22*s)
            bus[i]=(bus[i]*(1-mix)+z*mix).toFloat()
            val nc=c*cr-s*sr;s=s*cr+c*sr;c=nc
        }
    }

    private const val HOP=4096
    private const val FFT_SIZE=HOP*2
    private data class Spectrum(val re: FloatArray,val im: FloatArray)
    private val haloKernel: FloatArray by lazy {
        val noise=Dsp.Noise(Dsp.seedFor("HALOBAR_HALO_V1"))
        val filter=LowPass(4000.0,RATE)
        val kernel=FloatArray((.9*RATE).toInt())
        var energy=0.0
        for(i in kernel.indices) {
            val t=i.toDouble()/RATE
            val value=filter.step(noise.next().toDouble())*exp(-t/.15)*min(t/.018,1.0)
            kernel[i]=value.toFloat();energy+=value*value
        }
        val gain=.24/sqrt(energy)
        for(i in kernel.indices)kernel[i]=(kernel[i]*gain).toFloat()
        kernel
    }
    private val haloSpectra: List<Spectrum> by lazy {
        val kernel=haloKernel
        (0 until (kernel.size+HOP-1)/HOP).map { partition ->
            val re=FloatArray(FFT_SIZE);val im=FloatArray(FFT_SIZE)
            val size=min(HOP,kernel.size-partition*HOP)
            kernel.copyInto(re,0,partition*HOP,partition*HOP+size)
            Fft.forward(re,im);Spectrum(re,im)
        }
    }
    internal fun haloKernelForProbe()=haloKernel.copyOf()
    internal fun convolveHaloForProbe(input: FloatArray)=haloConvolution(input)

    /** Uniform partitioned convolution; immutable kernel spectra, per-render history. */
    private fun haloConvolution(input: FloatArray): FloatArray {
        val kernels=haloSpectra;val count=kernels.size
        val reHistory=Array(count) { FloatArray(FFT_SIZE) };val imHistory=Array(count) { FloatArray(FFT_SIZE) }
        val out=FloatArray(input.size);val overlap=FloatArray(HOP)
        val sumRe=FloatArray(FFT_SIZE);val sumIm=FloatArray(FFT_SIZE)
        var block=0
        for(start in input.indices step HOP) {
            cancelCheck()
            val slot=block%count;val re=reHistory[slot];val im=imHistory[slot]
            re.fill(0f);im.fill(0f)
            input.copyInto(re,0,start,min(start+HOP,input.size));Fft.forward(re,im)
            sumRe.fill(0f);sumIm.fill(0f)
            for(part in 0 until min(count,block+1)) {
                val old=(slot-part+count)%count;val a=reHistory[old];val b=imHistory[old]
                val k=kernels[part]
                for(bin in 0 until FFT_SIZE) {
                    sumRe[bin]+=a[bin]*k.re[bin]-b[bin]*k.im[bin]
                    sumIm[bin]+=a[bin]*k.im[bin]+b[bin]*k.re[bin]
                }
            }
            Fft.inverse(sumRe,sumIm)
            for(i in 0 until min(HOP,input.size-start)) out[start+i]=sumRe[i]+overlap[i]
            for(i in overlap.indices)overlap[i]=sumRe[i+HOP]
            block++
        }
        return out
    }
}
