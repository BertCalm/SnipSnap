package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** Finite drum kits and printed shared-bus phrases, not a new pitched Patch engine.
 * The accepted Triad.source and TriadHands v2 remain unchanged. */
object TriadKits {
    const val VERSION = 1
    enum class Genre(val title: String, val bpm: Int, val rig: Triad.Rig, val tune: Float) {
        TECHNO("Warehouse Techno", 128, Triad.Rig.WAREHOUSE, .42f),
        DEEP_HOUSE("Submerged House", 118, Triad.Rig.SUBMERGED, .34f),
        MINIMAL("Fracture Minimal", 126, Triad.Rig.FRACTURE, .62f)
    }
    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    val roles = listOf("Thud", "Sub", "Clap", "Open Hat", "Closed Hat", "Shaker",
        "Conga", "Bongo", "Rim", "Low Conga", "High Bongo", "Clap Roll", "Hand Roll",
        "Shaker Riser", "Full Groove", "Percussion Break")

    /** Each zone has four actual strikes. No random state survives a render. */
    fun build(genre: Genre): List<ArrangedPad> = roles.mapIndexed { role, name ->
        val grid = velocities.map { velocity -> List(TAKES) { take -> render(genre, role, velocity, take) } }
        val cls = when (role) {
            0, 1 -> DrumClass.KICK; 2 -> DrumClass.CLAP
            3 -> DrumClass.HAT_OPEN; 4 -> DrumClass.HAT_CLOSED
            in 11..15 -> DrumClass.LOOP; else -> DrumClass.PERC
        }
        ArrangedPad(grid.last().first(), cls, displayName = name, velocityTakes = grid,
            muteGroup = if (role in 3..4) 1 else if (role >= 14) 2 else 0,
            source = mapOf("engine" to "TRIAD", "kitVersion" to VERSION.toString(),
                "handRevision" to TriadHands.VERSION.toString(), "genre" to genre.name,
                "role" to role.toString(), "bpm" to genre.bpm.toString(),
                "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                "articulation" to "finite", "processing" to if (role >= 14) "shared-bus-print" else "dry"))
    }

    fun render(genre: Genre, role: Int, velocity: Float, take: Int): Snip {
        require(role in roles.indices && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        if (role < 11) return bounded(hit(genre, role, velocity, take))
        if (role < 14) return flourish(genre, role, velocity, take)
        val s = Triad.Settings(bpm = genre.bpm, bars = 2, tune = genre.tune + (take - 1.5f) * .004f,
            weight = if (genre == Genre.DEEP_HOUSE) .78f else .6f,
            swing = if (genre == Genre.TECHNO) .2f else .55f,
            density = if (genre == Genre.MINIMAL) .5f else .75f)
        val source = Triad.source(s)
        val mix = if (role == 14) source.mix() else FloatArray(source.frames).also { out ->
            for ((lane, stem) in source.stems) if (lane !in setOf("thud", "sub", "clap"))
                for (i in out.indices) out[i] += stem[i]
        }
        // Velocity drives the nonlinear bus itself. Fixed output gain preserves those dynamics.
        for (i in mix.indices) mix[i] *= velocity * .5f
        val wet = Triad.process(mix, genre.bpm, genre.rig).output
        return bounded(wet)
    }

    private fun hit(g: Genre, role: Int, v: Float, take: Int, rise: Float = 0f): FloatArray {
        val t = (g.tune - .5f) * .25f + (take - 1.5f) * .003f + rise * .08f
        fun th(voice: ThumpVoice, vararg controls: Pair<String, Float>): FloatArray =
            Thump.render(voice, mapOf(*controls)).samples
        val x = when (role) {
            0 -> th(ThumpVoice.KICK, "TUNE" to .45f+t, "DECAY" to .22f, "SWEEP" to .65f,
                "CLICK" to .25f+.35f*v, "DRIVE" to .25f+.17f*v)
            1 -> th(ThumpVoice.KICK, "TUNE" to .22f+t, "DECAY" to if(g == Genre.DEEP_HOUSE) .78f else .65f,
                "SWEEP" to .2f, "CLICK" to .1f*v, "DRIVE" to .08f)
            2 -> th(ThumpVoice.CLAP, "SPREAD" to .3f+(take-1.5f)*.015f, "DECAY" to if(g == Genre.MINIMAL) .15f else .3f, "TONE" to .2f+.35f*v+g.tune*.2f)
            3, 4 -> th(if(role == 3) ThumpVoice.HAT_OPEN else ThumpVoice.HAT_CLOSED,
                "METAL" to .8f+(take-1.5f)*.012f, "DECAY" to if(role == 3) (if(g == Genre.MINIMAL) .3f else .5f) else .22f,
                "NOISE" to .08f+.07f*v)
            5 -> Skin.render(SkinVoice.SHAKER, mapOf("DECAY" to .2f, "GRAIN" to .35f+.2f*v+(take-1.5f)*.008f+rise*.25f,
                "SWELL" to .25f)).samples
            6, 7, 9, 10 -> TriadHands.render(role == 7 || role == 10,
                (g.tune + if(role == 9) -.2f else if(role == 10) .2f else 0f).coerceIn(0f,1f), v, take)
            else -> th(ThumpVoice.RIM, "TUNE" to .5f+t, "DECAY" to .15f)
        }
        // A softer contact loses upper partials as well as level. This is outside the accepted source.
        val tone = Dsp.FixedOnePole(2200f + 15000f*v)
        return FloatArray(x.size) { tone.lp(x[it]) * (.25f + .5f*v) }
    }

    private fun flourish(g: Genre, role: Int, v: Float, take: Int): Snip {
        val beat = Dsp.RATE * 60.0 / g.bpm
        val out = FloatArray((beat*4).roundToInt() + Dsp.RATE)
        // A bar builds from eighths to sixteenths to thirty-seconds, then releases naturally.
        val positions = listOf(0.0,.5,1.0,1.5,2.0,2.25,2.5,2.75) + List(8) { 3.0+it*.125 }
        for ((i, pos) in positions.withIndex()) {
            val progress = i.toFloat()/positions.lastIndex
            val lane = when(role) { 11 -> 2; 12 -> if(i%2 == 0) 6 else 7; else -> 5 }
            val sample = hit(g, lane, v*(.35f+.65f*progress), (take+i)%TAKES, progress)
            val start = (pos*beat).roundToInt()
            for (j in 0 until min(sample.size, out.size-start)) out[start+j] += sample[j] * .55f
        }
        return bounded(out)
    }

    private fun bounded(x: FloatArray): Snip {
        check(x.all { it.isFinite() })
        // Safety attenuation only, never boost soft layers back up to the hard layer.
        val mean = x.average().toFloat()
        val dc = Dsp.FixedOnePole(20f)
        for (i in x.indices) { val centered = x[i] - mean; x[i] = centered - dc.lp(centered) }
        val gain = min(1f, .95f / (x.maxOf { abs(it) }.coerceAtLeast(.000001f)))
        for (i in x.indices) x[i] *= gain * min(1f, i / 88f)
        Dsp.fadeTail(x)
        return Snip(x, 1, Dsp.RATE)
    }
}
