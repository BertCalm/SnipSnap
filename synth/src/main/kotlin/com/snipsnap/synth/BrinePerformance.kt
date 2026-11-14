package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** R8 performance recipes. Preserve R7 benchmark renders; no new engine registration. */
object BrinePerformance {
    val velocities = listOf(.35f, .65f, .95f)
    fun hit(ride: Boolean, velocity: Float, take: Int): Snip {
        require(velocity in 0f..1f && take in 0..1)
        val e = if (ride) BrineMetal.revised(BrineCymbals.event(BrineCymbals.Voice.RIDE, 0.0, velocity))
            else BrineKitExpansion.Event(BrineKit.parts()[1], 0.0, velocity)
        return BrineKitExpansion.renderEvents(listOf(e), if (ride) 8.6 else 2.0,
            sourceOverride = { if (ride) BrineReferenceCymbals.rideSource(velocity, take)
                else Skin.render(e.part.voice, e.part.macros, velocity, take).samples })
    }
    fun phrase(ride: Boolean, alternating: Boolean, dynamics: Boolean): Snip {
        val events = (0..11).map { n ->
            val v = if (dynamics) velocities[(n/4).coerceAtMost(2)] else .65f
            if (ride) BrineMetal.revised(BrineCymbals.event(BrineCymbals.Voice.RIDE,n*.5,v))
            else BrineKitExpansion.Event(BrineKit.parts()[1],n*.5,v)
        }
        var index=0
        return BrineKitExpansion.renderEvents(events,11.0,sourceOverride = { e ->
            val take = if (alternating) index++ % 2 else 0
            if (ride) BrineReferenceCymbals.rideSource(e.velocity,take)
            else Skin.render(e.part.voice,e.part.macros,e.velocity,take).samples
        })
    }
    /** Six bars: two hats, two ride, two hats with crash accents. Shared recording and pedal bus. */
    fun arrangement(cymbalScale: Float): Snip {
        require(cymbalScale in 0f..1f)
        val events = (0..2).flatMap { section ->
            BrineCymbals.events(if(section==1) BrineCymbals.Groove.RIDE else BrineCymbals.Groove.HATS)
                .filter { section==2 || it.cymbal !in listOf(BrineCymbals.Voice.CRASH,BrineCymbals.Voice.CHOKED_CRASH) }
                .map(BrineMetal::revised).map { e -> e.copy(beat=e.beat+8*section,
                    part=if(e.cymbal!=null || e.part.voice in listOf(SkinVoice.HAT_CLOSED,SkinVoice.HAT_OPEN))
                        e.part.copy(gain=e.part.gain*cymbalScale) else e.part) }
        }
        return BrineKitExpansion.renderEvents(events,20.0,sourceOverride={e ->
            e.cymbal?.takeIf {it!=BrineCymbals.Voice.HALF_HAT}?.let {BrineReferenceCymbals.source(it)}
        })
    }
}
