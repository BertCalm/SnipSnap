package com.snipsnap.synth

/** Musical labels for the factory roster; the saved patch remains the audio recipe. */
data class StoopPresetMetadata(
    val category: String,
    val intendedRegister: IntRange,
    val articulationTags: Set<String>,
    val intervalRecipe: List<Int>,
    val intervalDescription: String,
    val description: String,
)

/**
 * Forty-eight provisional recipes with distinct source registrations and roles.
 * Counts meet the coverage target; the published dry audition is the owner's
 * listening gate, not a claim that the roster has received sonic approval.
 */
object StoopPresets {
    private data class Entry(val patch: StoopPatch, val metadata: StoopPresetMetadata)

    private fun p(
        voice: StoopVoice, name: String, source: StoopSource, description: String, tags: String,
        touch: Float? = null, color: Float? = null, shadow: Float? = null,
        wear: Float? = null, length: Float? = null, hold: Float = 0f,
        shadowInterval: Int = 0, shadowDelayMs: Float = 110f,
        shadowArticulation: Float = .25f, shadowReplies: Int = 1,
        chordIntervals: List<Int> = listOf(0, 7, 12),
        midi: Int? = null, velocity: Float = 1f, model: Int = Stoop.MODEL_VERSION,
    ): Entry {
        val macros = LinkedHashMap(Stoop.defaults(voice))
        for ((key, value) in listOf("TOUCH" to touch, "COLOR" to color, "SHADOW" to shadow, "WEAR" to wear, "LENGTH" to length)) {
            value?.let { macros[key] = it }
        }
        macros["HOLD"] = hold
        val root = midi ?: when (voice) {
            StoopVoice.BASS -> 36
            StoopVoice.TINE, StoopVoice.ORGAN, StoopVoice.LEAD -> 60
            StoopVoice.CLAV, StoopVoice.CHORD -> 48
        }
        val patch = StoopPatch(name, voice, macros, midi = root, velocity = velocity, source = source,
            shadowInterval = shadowInterval, shadowDelayMs = shadowDelayMs,
            shadowArticulation = shadowArticulation, shadowReplies = shadowReplies,
            chordIntervals = chordIntervals, model = model)
        val intervals = if (voice == StoopVoice.CHORD) chordIntervals else listOf(0)
        val category = when (voice) {
            StoopVoice.BASS -> "Bass"
            StoopVoice.TINE -> "Tine / key"
            StoopVoice.CLAV -> "Clav / pluck"
            StoopVoice.ORGAN -> "Organ"
            StoopVoice.LEAD -> "Lead"
            StoopVoice.CHORD -> "Chord"
        }
        val register = when (voice) {
            StoopVoice.BASS -> 28..55
            StoopVoice.TINE, StoopVoice.ORGAN, StoopVoice.LEAD -> 48..84
            StoopVoice.CLAV -> 40..76
            StoopVoice.CHORD -> 36..72
        }
        return Entry(patch, StoopPresetMetadata(category, register,
            tags.split(',').map { it.trim() }.toSet(), intervals, intervalDescription(intervals), description))
    }

    private fun intervalDescription(intervals: List<Int>): String = when (intervals) {
        listOf(0) -> "Root only"
        listOf(0, 7, 12) -> "Root, fifth, octave"
        listOf(0, 4, 7) -> "Major triad: root, major third, fifth"
        listOf(0, 3, 7) -> "Minor triad: root, minor third, fifth"
        listOf(0, 7, 14) -> "Open fifth and ninth"
        else -> intervals.joinToString(prefix = "Semitones: ")
    }

    private val entries = listOf(
        // Bass: sub/body proportions and harmonic shapes change the source itself.
        p(StoopVoice.BASS, "Round Corner", StoopSource(shape = .12f, sub = .70f, harmonicTilt = .18f),
            "Rounded root support with a clean, direct attack.", "rounded,clean,direct", touch = .24f, color = .32f, shadow = 0f, wear = 0f, length = .42f),
        p(StoopVoice.BASS, "Rubber Sole", StoopSource(shape = .58f, sub = .32f, harmonicTilt = .46f),
            "Springy upper body above a firm low root.", "rounded,rubbery", touch = .48f, color = .56f, length = .38f),
        p(StoopVoice.BASS, "Muted Wire", StoopSource(shape = .76f, sub = .18f, fmRatio = 1f, fmIndex = .28f, harmonicTilt = .28f),
            "Short string-like body with a restrained muted reply.", "muted,short,answer", touch = .58f, color = .38f, length = .36f, shadowArticulation = .12f),
        p(StoopVoice.BASS, "Square Pocket", StoopSource(shape = .94f, sub = .28f, harmonicTilt = .48f),
            "A clear square-like bass for simple pocket lines.", "square,clean,direct", touch = .38f, color = .42f, shadow = 0f, wear = 0f, length = .36f),
        p(StoopVoice.BASS, "Nose Bass", StoopSource(shape = .82f, sub = .20f, harmonicTilt = .78f),
            "A narrow bright body that remains supported by the root.", "nasal,accented", touch = .62f, color = .72f, wear = .12f, length = .38f),
        p(StoopVoice.BASS, "Heel Slap", StoopSource(shape = .68f, sub = .42f, harmonicTilt = .68f),
            "A defined contact accent with a full low body.", "slap,accented,direct", touch = .88f, color = .66f, shadow = 0f, wear = .16f, length = .36f),
        p(StoopVoice.BASS, "Soft Curb", StoopSource(shape = .24f, sub = .55f, harmonicTilt = .25f),
            "Soft onset and a longer rounded note.", "soft,long", touch = .14f, color = .28f, length = .65f),
        p(StoopVoice.BASS, "Amber Sub", StoopSource(shape = .08f, sub = .88f, harmonicTilt = .34f),
            "Deep support with a small audible harmonic outline.", "sub,rounded,worn", touch = .30f, color = .40f, shadow = 0f, wear = .38f, length = .48f),
        p(StoopVoice.BASS, "Bent Pulse", StoopSource(shape = .88f, sub = .24f, harmonicTilt = .58f, detuneCents = 3f),
            "A lightly spread pulse registration for mobile bass lines.", "pulse,rubbery", touch = .52f, color = .60f, wear = .18f, length = .40f),
        p(StoopVoice.BASS, "Dry Step", StoopSource(shape = .46f, sub = .36f, harmonicTilt = .40f),
            "A tight clean bass with no answering gesture.", "short,clean,direct", touch = .64f, color = .48f, shadow = 0f, wear = 0f, length = .37f),
        p(StoopVoice.BASS, "Grain Pocket", StoopSource(shape = .52f, sub = .48f, harmonicTilt = .52f),
            "Rounded grain on a harmonic mid-bass registration.", "worn,rounded", touch = .42f, color = .55f, shadow = .08f, wear = .68f, length = .42f),
        p(StoopVoice.BASS, "Back Alley", StoopSource(shape = .36f, sub = .40f, harmonicTilt = .38f),
            "A rounded note answered by a quieter octave pluck.", "answer,octave", touch = .40f, shadow = .65f, length = .38f, shadowInterval = 12, shadowDelayMs = 145f, shadowArticulation = .15f),
        p(StoopVoice.BASS, "Fifth Reply", StoopSource(shape = .64f, sub = .30f, harmonicTilt = .56f),
            "A deliberate fifth answer for sparse bass gestures.", "answer,fifth", touch = .52f, shadow = .58f, wear = .18f, length = .46f, shadowInterval = 7, shadowDelayMs = 165f),
        p(StoopVoice.BASS, "Double Step", StoopSource(shape = .72f, sub = .38f, harmonicTilt = .62f),
            "Two muted root replies after a firm bass accent.", "answer,double,muted", touch = .72f, shadow = .54f, length = .36f, shadowDelayMs = 85f, shadowArticulation = .08f, shadowReplies = 2),
        p(StoopVoice.BASS, "Velvet Root", StoopSource(shape = .18f, sub = .62f, harmonicTilt = .44f, detuneCents = -2f),
            "A broad sustained root with softened recording character.", "soft,long,worn", touch = .18f, color = .36f, shadow = .10f, wear = .42f, length = .80f),
        p(StoopVoice.BASS, "Held Corner", StoopSource(shape = .30f, sub = .58f, harmonicTilt = .36f),
            "A settled bass loop with a quiet sustained companion.", "held,rounded", touch = .28f, color = .42f, shadow = .25f, wear = .15f, length = .55f, hold = 1f),

        // Tines: different FM ratios, strike depth and body registration.
        p(StoopVoice.TINE, "Dust Tines", StoopSource(shape = .28f, sub = .12f, fmRatio = 2f, fmIndex = .70f, harmonicTilt = .34f),
            "Warm strikes with softened grain and a modest reply.", "warm,struck,worn", color = .42f, wear = .42f, length = .62f),
        p(StoopVoice.TINE, "Bent Bell", StoopSource(shape = .14f, sub = .04f, fmRatio = 3f, fmIndex = 1.2f, harmonicTilt = .54f, detuneCents = 2f),
            "A bell-like contact above a gentle pitched body.", "bell,struck", touch = .50f, color = .62f, length = .68f, shadowInterval = 12),
        p(StoopVoice.TINE, "Amber Key", StoopSource(shape = .42f, sub = .20f, fmRatio = 1f, fmIndex = .48f, harmonicTilt = .22f),
            "A dark sustained key with a broad lower body.", "warm,soft,long", touch = .26f, color = .30f, length = .78f),
        p(StoopVoice.TINE, "Clean Contact", StoopSource(shape = .35f, sub = .08f, fmRatio = 2f, fmIndex = 1.05f, harmonicTilt = .62f),
            "A clear hard-contact key without wear or shadow.", "clean,accented,direct", touch = .78f, color = .66f, shadow = 0f, wear = 0f, length = .45f),
        p(StoopVoice.TINE, "Soft Bell", StoopSource(shape = .08f, sub = .08f, fmRatio = 1.5f, fmIndex = .38f, harmonicTilt = .30f),
            "A restrained bell for light melodic punctuation.", "soft,bell,clean", touch = .16f, color = .44f, shadow = .08f, wear = 0f, length = .56f),
        p(StoopVoice.TINE, "Low Window", StoopSource(shape = .56f, sub = .26f, fmRatio = 1f, fmIndex = .82f, harmonicTilt = .40f),
            "A full low-key registration with a short ringing tail.", "warm,struck,short", touch = .52f, color = .38f, length = .32f),
        p(StoopVoice.TINE, "Tin Roof", StoopSource(shape = .22f, sub = .06f, fmRatio = 3f, fmIndex = 1.8f, harmonicTilt = .76f),
            "A brighter contact sound rounded by deliberate wear.", "bright,accented,worn", touch = .68f, color = .72f, wear = .65f, length = .40f),
        p(StoopVoice.TINE, "After Key", StoopSource(shape = .46f, sub = .14f, fmRatio = 2f, fmIndex = .62f, harmonicTilt = .48f),
            "A warm strike answered by a dark octave bell.", "answer,octave,struck", shadow = .68f, length = .46f, shadowInterval = 12, shadowDelayMs = 175f, shadowArticulation = .12f),
        p(StoopVoice.TINE, "Night Key", StoopSource(shape = .62f, sub = .22f, fmRatio = 1.5f, fmIndex = .34f, harmonicTilt = .18f),
            "A mellow key with a long quiet body.", "dark,soft,long", touch = .18f, color = .24f, shadow = 0f, wear = .32f, length = .86f),
        p(StoopVoice.TINE, "Held Amber", StoopSource(shape = .38f, sub = .16f, fmRatio = 2f, fmIndex = .54f, harmonicTilt = .32f),
            "A settled warm-key loop with restrained upper contact.", "held,warm", touch = .30f, color = .42f, shadow = .18f, wear = .20f, length = .62f, hold = 1f),

        // Clav: body, harmonic slope and contact vary together.
        p(StoopVoice.CLAV, "Soft Clav", StoopSource(shape = .30f, sub = .22f, fmRatio = 1f, fmIndex = .30f, harmonicTilt = .26f),
            "A rounded muted pick with enough body to carry a riff.", "soft,muted", touch = .28f, color = .38f, length = .30f),
        p(StoopVoice.CLAV, "Short Wire", StoopSource(shape = .84f, sub = .06f, fmRatio = 2f, fmIndex = .75f, harmonicTilt = .72f),
            "A bright short pick for syncopated punctuation.", "bright,picked,short", touch = .80f, color = .68f, shadow = 0f, wear = .15f, length = .24f),
        p(StoopVoice.CLAV, "Back Step", StoopSource(shape = .58f, sub = .16f, fmRatio = 1f, fmIndex = .45f, harmonicTilt = .48f),
            "A firm pluck followed by a soft palm-muted root answer.", "picked,answer,muted", touch = .64f, shadow = .66f, length = .25f, shadowDelayMs = 120f, shadowArticulation = .08f),
        p(StoopVoice.CLAV, "Dry Mute", StoopSource(shape = .46f, sub = .28f, fmRatio = 1f, fmIndex = .22f, harmonicTilt = .20f),
            "A clean dry mute with a low rounded body.", "clean,muted,direct", touch = .46f, color = .32f, shadow = 0f, wear = 0f, length = .25f),
        p(StoopVoice.CLAV, "Copper Pick", StoopSource(shape = .72f, sub = .10f, fmRatio = 3f, fmIndex = .65f, harmonicTilt = .62f),
            "A harder harmonic pick with a controlled bright edge.", "picked,accented,bright", touch = .86f, color = .62f, wear = .28f, length = .28f),
        p(StoopVoice.CLAV, "Round Rhythm", StoopSource(shape = .22f, sub = .34f, fmRatio = 1.5f, fmIndex = .40f, harmonicTilt = .38f),
            "A fuller rounded pluck for lower rhythmic parts.", "rounded,picked", touch = .42f, color = .46f, length = .40f),
        p(StoopVoice.CLAV, "Grain String", StoopSource(shape = .64f, sub = .18f, fmRatio = 2f, fmIndex = .92f, harmonicTilt = .58f),
            "A worn string-like accent with a useful finite tail.", "worn,picked,accented", touch = .70f, color = .60f, shadow = .08f, wear = .72f, length = .35f),
        p(StoopVoice.CLAV, "Two Picks", StoopSource(shape = .52f, sub = .12f, fmRatio = 1.5f, fmIndex = .56f, harmonicTilt = .46f),
            "Two gentle root replies behind a clipped first pick.", "answer,double,picked", touch = .60f, shadow = .50f, length = .24f, shadowDelayMs = 75f, shadowArticulation = .18f, shadowReplies = 2),

        // Organ: registrations retain harmonic identity at both TOUCH ends.
        p(StoopVoice.ORGAN, "Amber Organ", StoopSource(shape = .28f, sub = .28f, harmonicTilt = .24f),
            "A smooth full registration with mild recording warmth.", "smooth,warm", touch = .20f, color = .34f, length = .65f),
        p(StoopVoice.ORGAN, "Small Pipes", StoopSource(shape = .68f, sub = .04f, harmonicTilt = .46f),
            "A hollow restrained registration for small melodic figures.", "hollow,clean", touch = .30f, color = .44f, shadow = .08f, wear = 0f, length = .48f),
        p(StoopVoice.ORGAN, "Corner Stab", StoopSource(shape = .48f, sub = .18f, harmonicTilt = .64f),
            "A clipped organ accent with a brief octave reply.", "clipped,accented,answer", touch = .80f, color = .58f, shadow = .42f, length = .20f, shadowInterval = 12, shadowDelayMs = 90f),
        p(StoopVoice.ORGAN, "Bright Loft", StoopSource(shape = .86f, sub = .08f, harmonicTilt = .86f),
            "A clear upper harmonic registration with direct onset.", "bright,clean,direct", touch = .52f, color = .74f, shadow = 0f, wear = 0f, length = .44f),
        p(StoopVoice.ORGAN, "Worn Room", StoopSource(shape = .36f, sub = .32f, harmonicTilt = .40f),
            "A rounded full organ colored by restrained coarse grain.", "worn,smooth", touch = .36f, color = .42f, shadow = .10f, wear = .66f, length = .56f),
        p(StoopVoice.ORGAN, "Held Pipes", StoopSource(shape = .62f, sub = .14f, harmonicTilt = .52f),
            "A settled organ loop with a quieter sustained octave.", "held,hollow,octave", touch = .32f, color = .50f, shadow = .24f, wear = .16f, length = .58f, hold = 1f, shadowInterval = 12),

        p(StoopVoice.LEAD, "Nose Talk", StoopSource(shape = .88f, sub = .08f, fmRatio = 2f, fmIndex = .65f, harmonicTilt = .74f),
            "A nasal accent for short conversational lead phrases.", "nasal,accented", touch = .68f, color = .72f, length = .40f),
        p(StoopVoice.LEAD, "Pocket Whistle", StoopSource(shape = .10f, sub = .02f, fmRatio = 1f, fmIndex = .16f, harmonicTilt = .18f),
            "A soft clean whistle-like line with a rounded onset.", "soft,clean,direct", touch = .18f, color = .48f, shadow = 0f, wear = 0f, length = .64f),
        p(StoopVoice.LEAD, "Thin Brass", StoopSource(shape = .62f, sub = .10f, fmRatio = 1.5f, fmIndex = 1.1f, harmonicTilt = .66f),
            "A thin brassy accent with a darker root answer.", "bright,accented,answer", touch = .76f, color = .64f, shadow = .40f, length = .42f, shadowArticulation = .16f),
        p(StoopVoice.LEAD, "Long Lantern", StoopSource(shape = .34f, sub = .16f, fmRatio = 2f, fmIndex = .42f, harmonicTilt = .36f, detuneCents = 4f),
            "A rounded sustained lead loop with a light spread.", "held,rounded,long", touch = .24f, color = .44f, shadow = .16f, wear = .30f, length = .80f, hold = 1f),

        p(StoopVoice.CHORD, "Velvet Stack", StoopSource(shape = .28f, sub = .24f, harmonicTilt = .32f, detuneCents = 3f),
            "A soft root/fifth/octave block with a restrained root reply.", "soft,block,open", touch = .28f, color = .38f, length = .52f),
        p(StoopVoice.CHORD, "Small Cinema", StoopSource(shape = .62f, sub = .12f, harmonicTilt = .58f),
            "An explicit major-triad stab with a clear harmonic edge.", "major,clipped,clean", touch = .70f, color = .56f, shadow = 0f, wear = 0f, length = .24f, chordIntervals = listOf(0, 4, 7)),
        p(StoopVoice.CHORD, "Minor Corner", StoopSource(shape = .74f, sub = .18f, harmonicTilt = .46f),
            "An explicit minor-triad accent with a muted root reply.", "minor,clipped,answer", touch = .62f, color = .44f, shadow = .38f, wear = .35f, length = .32f, shadowArticulation = .12f, chordIntervals = listOf(0, 3, 7)),
        p(StoopVoice.CHORD, "Late Strings", StoopSource(shape = .42f, sub = .08f, harmonicTilt = .38f, detuneCents = 7f),
            "A soft open-fifth/ninth loop for a sustained string-like block.", "held,soft,open", touch = .16f, color = .36f, shadow = .12f, wear = .28f, length = .78f, hold = 1f, chordIntervals = listOf(0, 7, 14)),
    )

    private val presets = entries.map { it.patch }
    private val metadata = entries.associate { it.patch.name to it.metadata }

    fun all(): List<StoopPatch> = presets
    fun forVoice(voice: StoopVoice): List<StoopPatch> = presets.filter { it.voice == voice }
    fun metadataFor(name: String): StoopPresetMetadata? = metadata[name]
    fun metadataFor(patch: StoopPatch): StoopPresetMetadata? {
        val original = entries.firstOrNull { it.patch.name == patch.name && it.patch.voice == patch.voice }?.metadata ?: return null
        val intervals = if (patch.voice == StoopVoice.CHORD) patch.chordIntervals else listOf(0)
        return original.copy(intervalRecipe = intervals, intervalDescription = intervalDescription(intervals))
    }
}
