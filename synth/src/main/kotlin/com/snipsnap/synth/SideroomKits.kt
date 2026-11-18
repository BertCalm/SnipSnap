package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.*

/** Versioned cache of approved spectral-synth renders, not acoustic sample playback.
 * Python resynth regenerates the bank from magnitude models; Kotlin replays it exactly
 * and authors finite roll/fill scores (with legacy riser replay). No editable Kotlin spectral-engine claim.
 */
object SideroomKits {
    const val VERSION = 2
    const val TAKES = 3
    const val PADS = 10
    val velocities = listOf(.25f, .6f, 1f)
    enum class Kit(val slug: String, val title: String, val bpm: Int) {
        OPEN_ROAD("open-road", "Open Road", 100),
        TIGHT_POCKET("tight-pocket", "Tight Pocket", 122),
        ANGULAR("angular", "Angular", 116),
        HIGH_VOLTAGE("high-voltage", "High Voltage", 128),
        DEEP_POCKET("deep-pocket", "Deep Pocket", 110);
        val id get() = "sideroom-$slug"
    }
    val voices = listOf("kick", "snare", "hat", "openhat", "tom", "floor", "ride", "crash")
    val names = listOf("Kick", "Snare", "Closed Hat", "Open Hat", "Rack Tom", "Floor Tom", "Ride", "Crash", "Snare Roll", "Drum Fill")
    private fun bytes(name: String) = checkNotNull(javaClass.getResourceAsStream("/sideroom/v1/$name")) { "Missing SIDEROOM v1 $name" }.use { it.readBytes() }
    private val hashes by lazy { bytes("index.csv").toString(Charsets.UTF_8).lineSequence().drop(1).filter { it.isNotBlank() }.associate { val p=it.split(','); p[0] to p[1] } }
    private val cache = object : LinkedHashMap<String, Snip>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snip>?) = size > 12
    }
    @Synchronized private fun hit(kit: Kit, role: Int, layer: Int, take: Int): Snip {
        val name="${kit.slug}/${voices[role]}-$layer-$take.wav"
        val stored=cache.getOrPut(name) {
            val raw=GZIPInputStream(bytes("$name.gz").inputStream()).use { it.readBytes() }
            val digest=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest==hashes.getValue(name)) { "SIDEROOM bank hash mismatch: $name" }
            WavReader.read(raw).also { check(it.channels==1 && it.sampleRate==Dsp.RATE) }
        }
        return stored.copy(samples=stored.samples.copyOf())
    }
    fun layer(velocity: Float): Int {
        require(velocity.isFinite() && velocity in 0f..1f)
        val midi=(velocity*127).roundToInt()
        return if(midi<42) 0 else if(midi<85) 1 else 2
    }
    fun render(kit: Kit, role: Int, velocity: Float, take: Int, model: Int = VERSION): Snip {
        require(model in 1..VERSION)
        require(role in 0 until PADS && take in 0 until TAKES)
        val z=layer(velocity)
        if(role<8) return hit(kit,role,z,take)
        val raw=phrase(kit,role,z,take,model)
        val gain=phraseGain(kit,role,model)
        for(i in raw.indices) raw[i]*=gain
        return Snip(raw,1,Dsp.RATE)
    }
    private val phraseGains=mutableMapOf<Triple<Kit,Int,Int>,Float>()
    @Synchronized private fun phraseGain(kit: Kit, role: Int, model: Int)=phraseGains.getOrPut(Triple(kit,role,model)) {
        var peak=0f
        for(z in 0..2) for(t in 0..2) for(x in phrase(kit,role,z,t,model)) peak=max(peak,abs(x))
        min(1f,.86f/peak.coerceAtLeast(1e-9f))
    }
    private fun phrase(kit: Kit, role: Int, layer: Int, take: Int, model: Int): FloatArray {
        if(role==9 && model>=2) return fill(kit,layer,take)
        val beat=60.0/kit.bpm
        data class Event(val at: Int, val samples: FloatArray, val gain: Float)
        val events=mutableListOf<Event>()
        val count=if(role==8)16 else 24
        for(i in 0 until count) {
            val progress=i.toDouble()/(count-1)
            val pos=if(role==8)i*.25 else 7.5*(1-(1-progress).pow(1.55))
            val voice=if(role==8)1 else if(i<8)5 else if(i<16)4 else 1
            val z=if(role==9 && i<8) max(0,layer-1) else layer
            val gain=if(role==8) (if(i%4==0).65f else .43f) else (.25+.35*progress).toFloat()
            events+=Event((pos*beat*Dsp.RATE).roundToInt(),hit(kit,voice,z,(take+i)%TAKES).samples,gain)
        }
        if(role==9) events+=Event((8*beat*Dsp.RATE).roundToInt(),hit(kit,7,layer,take).samples,.45f)
        val out=FloatArray(events.maxOf { it.at+it.samples.size })
        for(e in events) for(i in e.samples.indices) out[e.at+i]+=e.samples[i]*e.gain
        Dsp.fadeTail(out)
        return out
    }
    // A scored bar, with rests and a descending tom answer. No accelerating build
    // or automatic crash: the next downbeat belongs to the player's groove.
    private fun fill(kit: Kit, layer: Int, take: Int): FloatArray {
        // Sixteenth-note slots: S = snare, t = rack tom, F = floor tom, . = rest.
        val score=when(kit) {
            Kit.OPEN_ROAD -> "S.S.SS..tt..FF.F"
            Kit.TIGHT_POCKET -> "S..SS.S...tt.F.S"
            Kit.ANGULAR -> "S..t..S.F..t.F.S"
            Kit.HIGH_VOLTAGE -> "SS..SS..ttttFFFF"
            Kit.DEEP_POCKET -> "S.SS..S.tt.S.F.F"
        }
        val step=60.0/kit.bpm/4*Dsp.RATE
        val events=score.withIndex().filter { it.value!='.' }.mapIndexed { strike, (slot, symbol) ->
            val voice=when(symbol) { 'S'->1; 't'->4; else->5 }
            val accent=slot%4==0 || slot==15
            val gain=if(accent) .68f else if(slot%2==0) .52f else .34f
            Triple((slot*step).roundToInt(),hit(kit,voice,layer,(take+strike)%TAKES).samples,gain)
        }
        val out=FloatArray(events.maxOf { it.first+it.second.size })
        for((at,samples,gain) in events) for(i in samples.indices) out[at+i]+=samples[i]*gain
        Dsp.fadeTail(out)
        return out
    }
    fun source(kit: Kit, role: Int, model: Int = VERSION)=mapOf("engine" to "SIDEROOM", "model" to model.toString(), "kit" to kit.name,
        "role" to role.toString(), "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
        "articulation" to if(role==8) "roll" else if(role==9) (if(model==1) "riser" else "fill") else "finite", "sourceType" to "cached-spectral-synthesis")
    fun replay(source: Map<String,String>): List<List<Snip>> {
        val model=source["model"]?.toIntOrNull()
        require(source["engine"]=="SIDEROOM" && model!=null && model in 1..VERSION)
        require(source["velocities"]==velocities.joinToString(",") && source["takes"]==TAKES.toString())
        val kit=Kit.valueOf(source.getValue("kit"));val role=source.getValue("role").toInt()
        require(source==this.source(kit,role,model)) { "SIDEROOM source metadata changed" }
        return velocities.map { v -> List(TAKES) { t -> render(kit,role,v,t,model) } }
    }
    fun build(kit: Kit): List<ArrangedPad> = List(PADS) { role ->
        val grid=replay(source(kit,role))
        val cls=when(role) { 0->DrumClass.KICK;1->DrumClass.SNARE;2->DrumClass.HAT_CLOSED;3->DrumClass.HAT_OPEN;4,5->DrumClass.TOM;8,9->DrumClass.LOOP;else->DrumClass.PERC }
        ArrangedPad(grid.last().first(),cls,displayName=names[role],velocityTakes=grid,
            muteGroup=if(role in 2..3)1 else if(role>=8)2 else 0,source=source(kit,role))
    }
}
