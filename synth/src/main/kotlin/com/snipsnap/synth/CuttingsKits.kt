package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** Versioned printed collage bank. Pocket Finds preserves the owner-approved Study 04 PCM.
 * Original procedural sources are retained as assets rather than replaced by a different synth.
 * Every pad carries three strike timbres and four real source performances per timbre. */
object CuttingsKits {
    const val VERSION = 1
    enum class Genre(val id: String, val title: String, val bpm: Float, val description: String) {
        POCKET("cuttings-pocket", "CUTTINGS POCKET FINDS", 89f, "Boom-bap cuts with bass catches, borrowed rooms and sampler grain."),
        NIGHT("cuttings-night", "CUTTINGS NIGHT FRAGMENTS", 76f, "Trip-hop: felt kicks, brush backbeats, low hand drums and long metal shadows."),
        CIRCUIT("cuttings-circuit", "CUTTINGS CUT CIRCUIT", 124f, "Big beat: clap backbeats, alternating kicks, tight metal and abrupt cymbal cuts."),
    }
    data class Variant(val voice: String, val dynamic: Int, val take: Int, val file: String, val sha256: String)
    val variants: List<Variant> by lazy {
        Json.parse(resource("manifest.json").decodeToString()).obj().getValue("entries").arr().map {
            val o = it.obj()
            Variant(o.getValue("voice").str(), o.getValue("dynamic").int(), o.getValue("take").int(),
                o.getValue("file").str(), o.getValue("sha256").str())
        }.also { rows -> require(rows.size == 192 && rows.map { Triple(it.voice,it.dynamic,it.take) }.distinct().size == 192) }
    }
    internal fun resource(name: String): ByteArray {
        require('/' !in name && '\\' !in name && ".." !in name)
        return requireNotNull(javaClass.getResourceAsStream("/cuttings/v$VERSION/$name")) { "Missing CUTTINGS asset $name" }.use { it.readBytes() }
    }
    private val names = mapOf("kick" to "Pocket", "snare" to "Borrowed Room", "hat" to "Catch", "wood" to "Offcut",
        "thump" to "Trapdoor", "brush" to "Paper", "openhat" to "Loose Change", "clap" to "Double Exposure",
        "rim" to "Knuckle", "hand" to "Skin Pocket", "shaker" to "Matchbox", "metal" to "Bent Coin",
        "impact" to "Floor Drop", "cymbal" to "Flash", "roll" to "Splice Roll", "riser" to "Closing Credits")
    fun role(voice: String): DrumClass = when (voice) {
        "kick", "thump" -> DrumClass.KICK
        "snare", "brush", "clap" -> DrumClass.SNARE
        "hat" -> DrumClass.HAT_CLOSED
        "openhat" -> DrumClass.HAT_OPEN
        "roll", "riser" -> DrumClass.LOOP
        else -> DrumClass.PERC
    }
    private fun load(voice: String): List<List<Snip>> = (0..2).map { d -> (0..3).map { t ->
        WavReader.read(resource(variants.single { it.voice == voice && it.dynamic == d && it.take == t }.file))
    } }
    /** Twenty pads: fourteen hits plus one-, two- and four-bar roll/riser pairs.
     * Longer phrases are separate playable pads, so duration selection survives all exports. */
    fun build(genre: Genre): List<ArrangedPad> {
        val order = when (genre) {
            Genre.POCKET -> listOf("kick","snare","hat","wood","thump","brush","openhat","clap","rim","hand","shaker","metal","impact","cymbal")
            Genre.NIGHT -> listOf("thump","brush","hat","hand","kick","snare","openhat","rim","clap","wood","shaker","metal","impact","cymbal")
            Genre.CIRCUIT -> listOf("kick","clap","hat","metal","thump","snare","openhat","rim","brush","hand","shaker","wood","impact","cymbal")
        }
        val grids = order.associateWith { voice -> load(voice).map { row -> row.map { treat(it,genre,voice) } } }
        fun pad(voice: String, grid: List<List<Snip>>, bars: Int = 0): ArrangedPad = ArrangedPad(
            grid[2][0], role(voice), displayName = names.getValue(voice) + if (bars > 0) " ${bars}B" else "",
            velocityTakes = grid, oneShot = true, muteGroup = if (voice in listOf("hat","openhat")) 1 else 0,
            source = mapOf("factory" to genre.id, "model" to "$VERSION", "sourceVersion" to "cuttings-r4-sampler-color-1",
                "role" to voice, "genre" to genre.name, "bpm" to "${genre.bpm}", "bars" to "$bars",
                "rendered" to "true", "velocityLayers" to "3", "roundRobins" to "4"))
        return order.map { pad(it,grids.getValue(it)) } + listOf(1,2,4).flatMap { bars ->
            listOf("roll","riser").map { voice ->
                val grid = if (genre == Genre.POCKET && bars == 1) load(voice) else
                    (0..2).map { d -> (0..3).map { take -> phrase(grids,genre,voice,bars,d,take) } }
                pad(voice,grid,bars)
            }
        }
    }
    private fun treat(input: Snip, genre: Genre, voice: String): Snip {
        if (genre == Genre.POCKET) return input
        val low = voice in listOf("kick","thump","hand","impact")
        val semi = if (genre == Genre.NIGHT) when { low -> -2.5; voice == "brush" -> -1.0; else -> -1.5 }
                   else when (voice) { "clap","snare","rim" -> 2.5; "hat","openhat" -> 1.5; "thump" -> -1.5; else -> 0.0 }
        val source = input.samples.copyOf()
        // Protect pitch-up reads; a bounded cubic resampler avoids duplicating whole DSP engines.
        if (semi > 0) for (k in floatArrayOf(1.9616f,1.6629f,1.1111f,.3902f)) Dsp.TptSvf(Dsp.RATE).lowPassInPlace(source,8500f,k)
        val speed = 2.0.pow(semi/12)
        val pitched = Dsp.readAt(Snip(source,1,Dsp.RATE),(source.size/speed).toInt(),cubic=true) { it*speed to 1f }.samples
        val out = FloatArray(pitched.size)
        val damping = if (genre == Genre.CIRCUIT) when (voice) { "hat","rim","clap" -> 7.0; "cymbal","metal" -> 5.0; else -> 1.5 } else if (low) .35 else .8
        val gain = if (genre == Genre.NIGHT && voice in listOf("brush","hand")) 1.10 else if (genre == Genre.CIRCUIT && voice=="clap") 1.12 else .92
        for (i in out.indices) out[i]=(pitched[i]*gain*exp(-max(0.0,i.toDouble()/Dsp.RATE-.02)*damping)).toFloat()
        if (genre == Genre.NIGHT && voice in listOf("metal","wood","cymbal")) {
            val delay = (60.0/genre.bpm*.25*Dsp.RATE).roundToInt()
            for (i in delay until out.size) out[i] += pitched[i-delay]*.12f
        }
        tail(out)
        require(out.all { it.isFinite() && abs(it)<1f })
        return Snip(out,1,Dsp.RATE)
    }
    private fun phrase(grids: Map<String,List<List<Snip>>>, genre: Genre, voice: String, bars: Int, d: Int, take: Int): Snip {
        val beat=60.0/genre.bpm
        val out=FloatArray(((bars*4*beat+.32)*Dsp.RATE).roundToInt())
        data class Event(val beat: Double, val source: String, val gain: Float)
        val events=mutableListOf<Event>()
        val soft = if (genre==Genre.NIGHT) "hand" else "rim"
        val back = if (genre==Genre.CIRCUIT) "clap" else "snare"
        for (bar in 0 until bars) {
            val base=bar*4.0
            if (voice=="roll") {
                for (i in 0..11) events += Event(base+i*.25,if(i%3==0)back else soft,.54f+i*.02f)
                events += Event(base+3,"wood",.75f); events += Event(base+3.5,soft,.57f)
            } else if (bar<bars-1) {
                for(i in 0..7) events += Event(base+i*.5,soft,.35f+.2f*(bar.toFloat()/bars))
            } else {
                val times=doubleArrayOf(0.0,1.0,1.5,2.0,2.5,2.75,3.0,3.25,3.5,3.625,3.75)
                times.forEachIndexed { i, at -> events += Event(base+at,when { i<2 -> "hand"; i<5 -> soft; i==10 -> "wood"; else -> back },.48f+i*.03f) }
            }
        }
        events.forEachIndexed { i,e ->
            val hit=grids.getValue(e.source)[d][(take+i)%4].samples
            val start=(e.beat*beat*Dsp.RATE).roundToInt()
            for(j in 0 until min(hit.size,out.size-start)) out[start+j]+=hit[j]*e.gain
        }
        tail(out)
        require(out.all { it.isFinite() && abs(it)<1f })
        return Snip(out,1,Dsp.RATE)
    }
    private fun tail(out: FloatArray) {
        for(i in 0 until min(441,out.size)) out[out.lastIndex-i]*=i/440f
    }
}
