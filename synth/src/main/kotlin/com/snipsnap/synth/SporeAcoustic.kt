package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import kotlin.math.roundToInt

/** CC0 Virtuosity Drums, Versilian Studios; performances by Austin McMahon.
 * Exact sources, conversions and licenses live in resources/spore-station/v7.
 * A curated sample bank, not a new synthesizer. Fixed family gains preserve recorded dynamics.
 * Snare/stick/tom alternates group adjacent recorded dynamic hits; kick/cymbals have native RR.
 */
object SporeAcoustic {
    private val voices = listOf("kick","heel","snare","ghost","rim","stick","closed","open","pedal","ride","tom")
    val names = listOf("Live Kick","Heel Kick","Center Snare","Ghost Snare","Rimshot","Cross Stick",
        "Closed Hat","Open Hat","Pedal Chick","Ride Tip","High Tom")
    val levels = listOf(.95f,.65f,1f,.90f,.88f,.65f,.48f,.56f,.48f,.42f,.70f)
    fun contains(index: Int) = index in voices.indices
    private data class Entry(val voice: String,val zone: Int,val take: Int,val file: String,val gain: Float)
    internal fun bytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/spore-station/v7/$name")) {
        "Missing Spore Station acoustic source: $name"
    }.use { it.readBytes() }
    private val entries by lazy {
        bytes("index.csv").toString(Charsets.UTF_8).lineSequence().drop(1).filter { it.isNotBlank() }.map {
            val c=it.split(',');Entry(c[0],c[1].toInt(),c[2].toInt(),c[3],c[4].toFloat())
        }.toList()
    }
    private val cache=object : LinkedHashMap<String,Snip>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Snip>?)=size>12
    }
    @Synchronized private fun sample(file:String)=cache.getOrPut(file) { WavReader.read(bytes(file)) }
    private fun entry(index:Int,velocity:Float,take:Int):Entry {
        require(contains(index) && velocity.isFinite() && velocity in 0f..1f && take>=0)
        val midi=(velocity*127).roundToInt().coerceIn(1,127)
        val zone=if(midi<42)0 else if(midi<85)1 else 2
        return entries.single { it.voice==voices[index] && it.zone==zone && it.take==take%3 }
    }
    fun sourceId(index:Int,velocity:Float,take:Int)=entry(index,velocity,take).file
    fun render(index:Int,velocity:Float=1f,take:Int=0):Snip {
        val e=entry(index,velocity,take);val raw=sample(e.file)
        // No per-hit normalization, envelope synthesis, pitch shift, or added room.
        return raw.copy(samples=FloatArray(raw.samples.size) { raw.samples[it]*e.gain })
    }
}
