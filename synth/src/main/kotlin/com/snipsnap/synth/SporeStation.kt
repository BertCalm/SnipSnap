package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import kotlin.math.ln

/** A curated 170 BPM kit, not a new synthesis engine. All motion is saved in ordinary recipes. */
object SporeStation {
    const val BPM = 170
    const val VERSION = 2
    data class Part(val name: String, val patch: Patch, val kind: DrumClass, val level: Float = .7f)
    data class Controls(val body: Float = .5f, val bite: Float = .35f, val dust: Float = .15f,
                        val twist: Float = .1f, val space: Float = .2f) {
        init { require(listOf(body, bite, dust, twist, space).all { it.isFinite() && it in 0f..1f }) }
    }
    private fun m(vararg pairs: Pair<String, Float>) = mapOf(*pairs)
    private fun skin(name: String, voice: SkinVoice, kind: DrumClass, level: Float = .7f,
                     vararg values: Pair<String, Float>) = Part(name, SkinPatch(name, voice, Skin.defaults(voice) + values), kind, level)
    private fun onset(name: String, voice: OnsetVoice, kind: DrumClass, level: Float = .7f,
                      vararg values: Pair<String, Float>) = Part(name, OnsetPatch(name, voice, Onset.defaults(voice) + values, modelVersion = 4), kind, level)
    fun parts(): List<Part> = listOf(
        onset("Root Kick", OnsetVoice.KICK, DrumClass.KICK, .9f, "DECAY" to .14f, "BLEND" to .65f, "SNAP" to .38f),
        skin("Heel Kick", SkinVoice.KICK, DrumClass.KICK, .65f, "TUNE" to .55f, "DECAY" to .16f, "CLICK" to .4f),
        skin("Canopy Crack", SkinVoice.SNARE, DrumClass.SNARE, .85f, "TUNE" to .55f, "DECAY" to .28f, "SNAP" to .72f, "RATTLE" to .62f),
        skin("Understory", SkinVoice.SNARE, DrumClass.SNARE, .28f, "DECAY" to .12f, "SNAP" to .36f, "PUNCH" to .12f, "RATTLE" to .25f),
        onset("Fracture", OnsetVoice.SNARE, DrumClass.SNARE, .75f, "TUNE" to .7f, "DECAY" to .25f, "BLEND" to .35f, "SNAP" to .8f),
        skin("Twig", SkinVoice.STICK, DrumClass.PERC, .5f, "DECAY" to .14f, "BODY" to .25f),
        skin("Needle", SkinVoice.HAT_CLOSED, DrumClass.HAT_CLOSED, .42f, "DECAY" to .12f, "TONE" to .42f, "RING" to .2f),
        skin("Aperture", SkinVoice.HAT_OPEN, DrumClass.HAT_OPEN, .42f, "DECAY" to .24f, "TONE" to .42f, "RING" to .2f),
        skin("Dust", SkinVoice.SHAKER, DrumClass.PERC, .33f, "DECAY" to .2f, "TONE" to .4f),
        skin("Halo Ride", SkinVoice.RIDE, DrumClass.PERC, .4f, "DECAY" to .22f, "TONE" to .38f),
        onset("Root Tom", OnsetVoice.TOM, DrumClass.TOM, .65f, "TUNE" to .2f, "DECAY" to .25f),
        onset("Spore Knock", OnsetVoice.PERC_PLUCK, DrumClass.PERC, .55f, "DECAY" to .2f, "BLEND" to .7f),
        onset("Chrome Seed", OnsetVoice.PERC_METAL, DrumClass.PERC, .45f, "DECAY" to .32f, "BODY" to .5f),
        Part("Liquid Zap", TinesPatch("Liquid Zap", TinesVoice.ZAP, Tines.defaults(TinesVoice.ZAP) + m("DECAY" to .2f, "DROP" to .7f)), DrumClass.PERC, .45f),
        Part("Canopy Call", SirenPatch("Canopy Call", SirenVoice.BIRD, Siren.defaults(SirenVoice.BIRD) + m("HOLD" to .12f, "GRIT" to .15f)), DrumClass.PERC, .4f),
        skin("Event Horizon", SkinVoice.SNARE, DrumClass.PERC, .5f, "DECAY" to .3f, "SNAP" to .65f),
    )
    /** Invert ECHO's exponential time map; the baked delay is tied to this kit's documented tempo. */
    fun echoTime(beats: Float): Float = (ln((60f / BPM * beats) / .06f) / ln(.35f / .06f)).coerceIn(0f, 1f)

    fun recipe(index: Int, mutation: Boolean = false, controls: Controls = Controls()): PadRecipe {
        require(index in 0..15)
        val part = parts()[index]
        val old = part.patch.macros
        val changes = mutableMapOf<String, Float>()
        // BODY is explicitly a source balance/decay control, not a physical simulation.
        val bodyKey = if ("BODY" in old) "BODY" else "DECAY"
        if (bodyKey in old) changes[bodyKey] = (old.getValue(bodyKey) + (controls.body - .5f) * .3f).coerceIn(0f, 1f)
        val biteKey = listOf("PUNCH", "SNAP", "BITE", "GRIT").firstOrNull { it in old }
        if (biteKey != null) changes[biteKey] = (old.getValue(biteKey) + (controls.bite - .35f) * .5f).coerceIn(0f, 1f)
        val patch = part.patch.withMacros(old + changes)
        val color = index >= 10
        val effect = mutation || color
        val space = if (effect) if (mutation) .28f + .72f * controls.space else .08f + .92f * controls.space else 0f
        val twist = if (effect) if (mutation) .2f + .8f * controls.twist else controls.twist else 0f
        val fx = FxChain(
            reverse = index == 15 || mutation && index == 5,
            crunch = if (controls.dust > 0f) m("BITS" to controls.dust * .35f, "RATE" to controls.dust * .3f,
                "TONE" to .8f, "GRIT" to controls.dust * .2f) else null,
            ring = if (effect && index !in listOf(0, 1, 6, 7)) m("FREQ" to .22f, "MIX" to twist * .45f) else null,
            phase = if (effect && index >= 6) m("RATE" to .18f, "DEPTH" to twist, "FEEDBACK" to .12f) else null,
            echo = if (space > 0f && index !in listOf(0, 1, 6, 7)) m("TIME" to echoTime(if (index % 2 == 0) .5f else .75f),
                "REPEAT" to .25f + space * .25f, "TONE" to .28f, "MIX" to space * .55f, "HEAD" to .8f) else null,
            tape = if (mutation) m("WOBBLE" to .12f, "DRIVE" to .12f, "AGE" to .22f) else null,
        )
        return PadRecipe(patch, fx, terminalFadeMs = 4f)
    }

    /** Original single-hit audition comparisons; shelf/export callers use [performance]. */
    fun arranged(mutation: Boolean = false): List<ArrangedPad> = parts().mapIndexed { index, part ->
        val recipe = recipe(index, mutation)
        val hard = recipe.render()
        val soft = listOf(.3f, .6f).map { velocity ->
            // Match layer peaks once, retaining velocity-dependent timbre. The sampler applies velocity gain.
            Velocity.peakMatch(hard, recipe.process(Velocity.atVelocity(requireNotNull(recipe.patch), velocity)))
        }
        ArrangedPad(hard, part.kind, recipe.toJsonValue(), level = part.level, oneShot = true,
            softVariants = soft, displayName = part.name + if (mutation) " Mutation" else "",
            layerNames = listOf("Soft", "Middle", "Hard"), muteGroup = if (index in 6..7) 1 else 0,
            source = mapOf("kit" to "Spore Station", "version" to VERSION.toString(), "bpm" to BPM.toString(), "status" to "candidate-awaiting-listen"))
    }
    enum class Genre(val bpm: Int) { JUNGLE(170), DUB(85), ELECTRO(130) }

    /** Genre siblings change source envelopes and balances as well as their finishing rack. */
    fun genreRecipe(index: Int, genre: Genre, mutation: Boolean = false): PadRecipe {
        val base = recipe(index, mutation, when (genre) {
            Genre.JUNGLE -> Controls()
            Genre.DUB -> Controls(body = .8f, bite = .15f, dust = .25f, space = .8f)
            Genre.ELECTRO -> Controls(body = .3f, bite = .7f, dust = .05f, twist = .6f, space = .1f)
        })
        val patch = requireNotNull(base.patch)
        val changes = when (genre) {
            Genre.JUNGLE -> emptyMap()
            Genre.DUB -> mapOf("TONE" to .22f, "SNAP" to .3f, "BLEND" to .8f)
            Genre.ELECTRO -> mapOf("DECAY" to .12f, "SNAP" to .85f, "BLEND" to .12f, "RING" to .65f)
        }.filterKeys { it in patch.macros }
        // At half-time dub the same printed delay is a sixteenth/dotted sixteenth.
        return base.copy(patch = patch.withMacros(patch.macros + changes))
    }

    /** Re-struck drums use the existing strike model. Other sources vary a timbral parameter,
     * never TUNE, so successive hits change character without detuning the kit's root. */
    fun take(index: Int, genre: Genre, velocity: Float, take: Int, mutation: Boolean = false): com.snipsnap.audio.Snip {
        require(take in 0..2)
        val r = genreRecipe(index, genre, mutation)
        val patch = requireNotNull(r.patch)
        val struck = Restrike.take(patch, velocity, 170 + index, take)
        val raw = struck ?: run {
            val key = listOf("TONE", "SNAP", "RING", "BODY", "GRIT", "DROP").first { it in patch.macros }
            val delta = listOf(0f, -.035f, .035f)[take]
            val varied = patch.withMacros(patch.macros + (key to (patch.macros.getValue(key) + delta).coerceIn(0f, 1f)))
            Velocity.atVelocity(varied, velocity)
        }
        return r.process(raw)
    }

    /** Four beats: an accented sixteenth roll, or a rising-density reverse-metal gesture.
     * Absolute beat positions prevent accumulated sample rounding; every event is re-rendered. */
    fun gesture(genre: Genre, rising: Boolean, velocity: Float, take: Int): com.snipsnap.audio.Snip {
        val beats = if (rising) (0..7).map { it * .25 } + (0..15).map { 2.0 + it * .125 }
                    else (0..15).map { it * .25 }
        val length = (4.0 * 60 / genre.bpm * Dsp.RATE).toInt()
        val out = FloatArray(length)
        beats.forEachIndexed { n, beat ->
            val progress = beat / 4.0
            val force = velocity * (if (rising) .3f + .7f * progress.toFloat() else if (n % 4 == 0) 1f else .65f)
            val hit = take(if (rising) 12 else 2, genre, force, (take + n) % 3)
            val samples = if (rising) hit.samples.reversedArray() else hit.samples
            val start = (beat * 60 / genre.bpm * Dsp.RATE).toInt()
            val gain = if (rising) .2f + .8f * progress.toFloat() else 1f
            for (i in 0 until minOf(samples.size, length - start)) out[start + i] += samples[i] * gain
        }
        Dsp.limitPeak(out, .9f)
        Dsp.fadeTail(out, ms = 4f)
        return com.snipsnap.audio.Snip(out, 1, Dsp.RATE)
    }

    /** Fixed shelf bank: 16 hits plus B01 roll and B02 riser, each a 3 × 3 grid.
     * No single-hit recipe is attached to a whole grid: that would lose eight takes on replay. */
    fun performance(genre: Genre = Genre.JUNGLE, mutation: Boolean = false): List<ArrangedPad> =
        (0..17).map { index ->
            val hard = if (index < 16) take(index, genre, 1f, 0, mutation) else gesture(genre, index == 17, 1f, 0)
            val grid = listOf(.3f, .6f, 1f).map { velocity -> (0..2).map { t ->
                val sound = if (index < 16) take(index, genre, velocity, t, mutation) else gesture(genre, index == 17, velocity, t)
                Velocity.peakMatch(hard, sound)
            } }
            val part = parts().getOrNull(index)
            ArrangedPad(hard, part?.kind ?: if (hard.durationSeconds >= 1.5) DrumClass.LOOP else DrumClass.PERC,
                level = if (genre == Genre.DUB && index in 6..9) .28f else part?.level ?: .45f,
                displayName = part?.name ?: if (index == 16) "Canopy Roll" else "Spore Ascent",
                muteGroup = if (index in 6..7) 1 else 0, velocityTakes = grid,
                source = mapOf("kit" to "Spore Station", "version" to VERSION.toString(),
                    "genre" to genre.name, "mutation" to mutation.toString(), "bpm" to genre.bpm.toString(),
                    "articulation" to "3 velocity zones x 3 round-robin takes", "status" to "candidate-awaiting-listen"))
        }

}
