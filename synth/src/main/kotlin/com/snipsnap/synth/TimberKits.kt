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
object TimberKits {
    const val VERSION = 2
    const val TAKES = 4
    val velocities = listOf(.30f, .65f, 1f)
    enum class Instrument { GUITAR, ACOUSTIC_BASS, CELLO, UPRIGHT }
    enum class Gesture { PALM, KNUCKLE, SLAP, DEAD_STRING, SCRATCH, HARMONIC, RAKE }
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
            h("Guitar Open Rake", Instrument.GUITAR, Gesture.RAKE),
            h("Guitar Muted Slap", Instrument.GUITAR, Gesture.DEAD_STRING),
            h("Cello Wound String Scrape", Instrument.CELLO, Gesture.SCRATCH),
            h("Guitar Knuckle", Instrument.GUITAR, Gesture.KNUCKLE),
            h("Cello Knuckle", Instrument.CELLO, Gesture.KNUCKLE),
            h("Upright Fingerboard", Instrument.UPRIGHT, Gesture.SLAP),
            h("Bass Guitar Muted Slap", Instrument.ACOUSTIC_BASS, Gesture.DEAD_STRING),
            h("Cello String Snap", Instrument.CELLO, Gesture.SLAP),
            h("Guitar Bridge Tap", Instrument.GUITAR, Gesture.HARMONIC),
            h("Upright Rib Knock", Instrument.UPRIGHT, Gesture.KNUCKLE),
            h("Bass Guitar String Slap", Instrument.ACOUSTIC_BASS, Gesture.SLAP))
        return when(g) {
            Genre.POCKET -> base
            Genre.BOOM_BAP -> base.toMutableList().apply {
                this[0]=h("Bass Guitar Deep Heel",Instrument.ACOUSTIC_BASS,Gesture.PALM,.82f)
                this[1]=h("Upright Low Palm",Instrument.UPRIGHT,Gesture.PALM,.9f)
                this[2]=h("Upright Backbeat",Instrument.UPRIGHT,Gesture.SLAP,1.12f)
                this[3]=h("Bass Guitar Open Rake",Instrument.ACOUSTIC_BASS,Gesture.RAKE,1.15f)
                this[4]=h("Bass Guitar Muted Slap",Instrument.ACOUSTIC_BASS,Gesture.DEAD_STRING,1.15f)
            }
            Genre.TWO_STEP -> base.toMutableList().apply {
                this[0]=h("Cello Heel",Instrument.CELLO,Gesture.PALM,.83f)
                this[1]=h("Guitar Heel",Instrument.GUITAR,Gesture.PALM,.86f)
                this[2]=h("Bass Guitar Tight Slap",Instrument.ACOUSTIC_BASS,Gesture.SLAP,1.3f)
                this[3]=h("Cello Open Rake",Instrument.CELLO,Gesture.RAKE,1.35f)
                this[4]=h("Cello Muted Slap",Instrument.CELLO,Gesture.DEAD_STRING,1.35f)
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
        }, displayName=name, level=level(role), velocityTakes=grid, muteGroup=if(role in 3..4) 1 else 0,
            source=mapOf("engine" to "TIMBER", "model" to VERSION.toString(), "genre" to g.name,
                "role" to role.toString(), "velocities" to velocities.joinToString(","),
                "takes" to TAKES.toString(), "bpm" to g.bpm.toString(),
                "articulation" to if(role==14) "roll" else if(role==15) "riser" else "finite",
                "sourceType" to "procedural-acoustic-percussion"))
    }
    fun level(role: Int): Float = when(role) {
        0,1 -> .9f; 2 -> .8f; 3,4 -> .55f; 14,15 -> .7f; else -> .7f
    }
    /** Complete source identity is saved on the bank, including the ordered velocity/take grid. */
    fun replay(source: Map<String,String>): List<List<Snip>> {
        if(source["model"]=="1") return LegacyTimberKits.replay(source)
        require(source["engine"]=="TIMBER" && source["model"]==VERSION.toString())
        val g=Genre.valueOf(source.getValue("genre")); val role=source.getValue("role").toInt()
        require(source["velocities"]==velocities.joinToString(",") && source["takes"]==TAKES.toString())
        return velocities.map { v -> List(TAKES) { t -> render(g,role,v,t) } }
    }
    fun render(g: Genre, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0..15 && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        if(role<14) return strike(hits(g)[role],g,velocity,take)
        val beat=60.0/g.bpm
        val out=FloatArray(((4*beat+.95)*Dsp.RATE).roundToInt())
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
    /** Dry approved-technique bank, useful for auditions and future authored arrangements. */
    fun contact(instrument: Instrument, gesture: Gesture, velocity: Float, take: Int): Snip =
        TimberModel.strike(Hit("${instrument.name} ${gesture.name}",instrument,gesture),velocity,take)
    private fun strike(hit: Hit, g: Genre, v: Float, take: Int): Snip =
        TimberModel.strike(hit, v, take, g.damping / Genre.POCKET.damping)
}
