package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.TempoFit
import com.snipsnap.audio.WavReader
import kotlin.math.*

/** Immutable CC0 recordings, versioned independently of the earlier modal percussion.
 * Fixed source calibration preserves recorded dynamics; effects never truncate the natural tail.
 */
object BackwashAcoustic {
    private data class Entry(val layer: Int, val take: Int, val file: String)
    private fun bytes(name: String) = checkNotNull(javaClass.getResourceAsStream("/backwash/v5/$name")) {
        "Missing BACKWASH acoustic bank v5: $name"
    }.use { it.readBytes() }
    private val bank by lazy {
        bytes("index.csv").toString(Charsets.UTF_8).lineSequence().drop(1).filter { it.isNotBlank() }.map {
            val c=it.split(','); c[0] to Entry(c[1].toInt(),c[2].toInt(),c[3])
        }.toList().groupBy({it.first},{it.second})
    }
    private val gains by lazy { bytes("gain.csv").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
        .associate { val c=it.split(','); c[0] to c[1].toFloat() } }
    private val cache = object : LinkedHashMap<String, Snip>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snip>?) = size > 12
    }
    @Synchronized private fun sample(file: String): Snip = cache.getOrPut(file) { WavReader.read(bytes(file)) }
    fun layerCount(voice: BackwashVoice): Int = bank.getValue(voice.name).maxOf { it.layer }
    fun takeCount(voice: BackwashVoice): Int = bank.getValue(voice.name).filter { it.layer==1 }.size
    fun sourceId(voice: BackwashVoice, velocity: Float, take: Int): String {
        require(take>=0)
        val v=velocity.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 1f
        val layer=(v*layerCount(voice)).toInt().coerceAtMost(layerCount(voice)-1)+1
        return bank.getValue(voice.name).single { it.layer==layer && it.take==take%takeCount(voice) }.file
    }
    fun source(voice: BackwashVoice, macros: Map<String,Float>, velocity: Float=1f, take: Int=0): Snip {
        require(!voice.legacy)
        val m=Backwash.settled(voice,macros)
        val raw=sample(sourceId(voice,velocity,take))
        val speed=2.0.pow(((m.getValue("TUNE")-.5f)*8/12).toDouble()).toFloat()
        val shifted=if(speed==1f)raw else TempoFit.repitch(raw,1000f,1000f*speed)
        val gain=gains.getValue(voice.name)
        val damp=m.getValue("DAMP")
        val out=FloatArray(shifted.samples.size) { i -> shifted.samples[i]*gain*exp(-damp*8*i/Dsp.RATE) }
        Dsp.fadeTail(out)
        return Snip(out,1,Dsp.RATE)
    }
    /** Authored tambourine roll/riser: real alternate hits, not a looped single waveform. */
    private fun gesture(voice: BackwashVoice,m:Map<String,Float>,velocity:Float,take:Int,articulation:String):Snip {
        if(articulation=="HIT")return source(voice,m,velocity,take)
        require(voice==BackwashVoice.TAMBOURINE)
        val events=(0 until 16).map { i ->
            val progress=i/15f
            val time=if(articulation=="RISER") 2.4*(1-(1-progress).toDouble().pow(1.6)) else i*.125
            val force=if(articulation=="RISER") velocity*(.3f+.7f*progress) else velocity
            Triple((time*Dsp.RATE).toInt(),source(voice,m,force,take+i),if(articulation=="RISER") .3f+.7f*progress else 1f)
        }
        val out=FloatArray(events.maxOf { it.first+it.second.samples.size })
        for((start,s,gain)in events)for(i in s.samples.indices)out[start+i]+=s.samples[i]*gain*.45f
        Dsp.limitPeak(out,.9f);Dsp.fadeTail(out)
        return Snip(out,1,Dsp.RATE)
    }
    fun render(voice:BackwashVoice,macros:Map<String,Float>,velocity:Float=1f,take:Int=0,articulation:String="HIT"):Snip {
        require(articulation in setOf("HIT","ROLL","RISER"))
        val m=Backwash.settled(voice,macros)
        val dry=gesture(voice,m,velocity,take,articulation)
        if(m.getValue("TRAIL")==0f && m.getValue("PRESS")==0f && m.getValue("DRIVE")==0f) return dry
        // True zero bypasses make the approved acoustic source directly accessible.
        val moving=if(m.getValue("TRAIL")==0f)dry else Backwash.chorus(dry,m.getValue("SWAY"),m.getValue("TRAIL"),voice)
        val pressed=Backwash.press(moving,m.getValue("PRESS"))
        val drive=m.getValue("DRIVE")
        val amp=if(drive==0f)pressed else Valve.process(pressed,mapOf("DRIVE" to (.15f+.55f*drive),"SAG" to .12f,"TONE" to .55f,"CAB" to .3f))
        // Parallel amp coloration leaves the instrument's attack and ringing body intact.
        val out=FloatArray(maxOf(pressed.samples.size,amp.samples.size)) { i ->
            val wet=.55f*drive
            (1-wet)*pressed.samples.getOrElse(i){0f}+wet*amp.samples.getOrElse(i){0f}
        }
        Dsp.limitPeak(out,.95f);Dsp.fadeTail(out)
        return Snip(out,1,Dsp.RATE)
    }
}
