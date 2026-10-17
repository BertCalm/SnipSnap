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
        p(StoopVoice.BASS, "Rubber Sole", StoopSource(shape = .05f, sub = .22f, harmonicTilt = .35f),
            "A springy saw-shaped body with a longer filter accent.", "saw,rubbery,medium", touch = .72f, color = .64f, shadow = .10f, wear = .08f, length = .58f),
        p(StoopVoice.BASS, "Muted Wire", StoopSource(shape = .62f, sub = .05f, fmRatio = 1f, fmIndex = .28f, harmonicTilt = .12f),
            "A dark clipped body with a late palm-muted root answer.", "muted,short,answer", touch = .82f, color = .22f, shadow = .35f, wear = .12f, length = .36f, shadowDelayMs = 235f, shadowArticulation = 0f),
        p(StoopVoice.BASS, "Square Pocket", StoopSource(shape = 1f, sub = 0f, harmonicTilt = .80f),
            "A clean square-like line with no sub or answering gesture.", "square,clean,direct", touch = .48f, color = .58f, shadow = 0f, wear = 0f, length = .36f),
        p(StoopVoice.BASS, "Nose Bass", StoopSource(shape = .10f, sub = .08f, harmonicTilt = .88f),
            "A bright mid-bass accent with a lean low register.", "bright,accented,lean", touch = .64f, color = .75f, shadow = .08f, wear = .08f, length = .44f),
        p(StoopVoice.BASS, "Heel Slap", StoopSource(shape = .90f, sub = .58f, harmonicTilt = .70f),
            "A hard pulse accent above a full low body.", "slap,accented,direct", touch = .98f, color = .90f, shadow = 0f, wear = .08f, length = .36f),
        p(StoopVoice.BASS, "Soft Curb", StoopSource(shape = .03f, sub = .40f, harmonicTilt = .06f),
            "A very dark slow-onset root for longer bass phrases.", "dark,soft,long", touch = .04f, color = .08f, shadow = 0f, wear = 0f, length = .76f),
        p(StoopVoice.BASS, "Amber Sub", StoopSource(shape = .04f, sub = 1f, harmonicTilt = .08f),
            "Deep octave support beneath a softly worn root.", "sub,rounded,worn", touch = .16f, color = .14f, shadow = 0f, wear = .55f, length = .58f),
        p(StoopVoice.BASS, "Bent Pulse", StoopSource(shape = .97f, sub = .06f, harmonicTilt = .62f, detuneCents = 12f),
            "A lightly beating pulse with room for a longer bass line.", "pulse,spread,medium", touch = .60f, color = .66f, shadow = 0f, wear = .08f, length = .62f),
        p(StoopVoice.BASS, "Dry Step", StoopSource(shape = .42f, sub = .08f, harmonicTilt = .85f),
            "A bright clipped accent with no wear or answering gesture.", "short,clean,direct", touch = .94f, color = .78f, shadow = 0f, wear = 0f, length = .37f),
        p(StoopVoice.BASS, "Grain Pocket", StoopSource(shape = .76f, sub = .28f, harmonicTilt = .82f),
            "Coarse rounded grain on a longer pulse-heavy bass body.", "worn,pulse,medium", touch = .54f, color = .82f, shadow = 0f, wear = .92f, length = .58f),
        p(StoopVoice.BASS, "Back Alley", StoopSource(shape = .16f, sub = .22f, harmonicTilt = .30f),
            "A rounded bass note with a prominent late octave pluck.", "answer,octave,rounded", touch = .25f, color = .36f, shadow = .95f, wear = .18f, length = .40f, shadowInterval = 12, shadowDelayMs = 240f, shadowArticulation = .08f),
        p(StoopVoice.BASS, "Fifth Reply", StoopSource(shape = .56f, sub = .06f, harmonicTilt = .72f),
            "A clean longer bass note with an explicit bright fifth answer.", "answer,fifth,medium", touch = .58f, color = .62f, shadow = .88f, wear = 0f, length = .72f, shadowInterval = 7, shadowDelayMs = 250f, shadowArticulation = .70f),
        p(StoopVoice.BASS, "Double Step", StoopSource(shape = .86f, sub = .16f, harmonicTilt = .52f),
            "Two late muted root replies behind a firm pulse accent.", "answer,double,muted", touch = .88f, color = .58f, shadow = .95f, wear = .10f, length = .36f, shadowDelayMs = 235f, shadowArticulation = .03f, shadowReplies = 2),
        p(StoopVoice.BASS, "Velvet Root", StoopSource(shape = .32f, sub = .62f, harmonicTilt = .25f, detuneCents = -9f),
            "A softly beating low body for long rounded bass phrases.", "soft,long,spread", touch = .08f, color = .22f, shadow = 0f, wear = .30f, length = .94f),
        p(StoopVoice.BASS, "Held Corner", StoopSource(shape = .96f, sub = .32f, harmonicTilt = .68f),
            "A settled pulse-bass loop with an accented sustained companion.", "held,pulse", touch = .40f, color = .60f, shadow = .42f, wear = .08f, length = .72f, hold = 1f, shadowArticulation = .70f),

        // Tines: different FM ratios, strike depth and body registration.
        p(StoopVoice.TINE, "Dust Tines", StoopSource(shape = .28f, sub = .12f, fmRatio = 2f, fmIndex = .70f, harmonicTilt = .34f),
            "Warm strikes with softened grain and a modest reply.", "warm,struck,worn", color = .42f, wear = .42f, length = .62f),
        p(StoopVoice.TINE, "Bent Bell", StoopSource(shape = .14f, sub = 0f, fmRatio = 3f, fmIndex = 1.55f, harmonicTilt = .54f),
            "A clear bell contact above a longer ringing root.", "bell,struck,long", touch = .70f, color = .80f, shadow = .10f, wear = .04f, length = .80f, shadowInterval = 12, shadowDelayMs = 165f),
        p(StoopVoice.TINE, "Amber Key", StoopSource(shape = .42f, sub = .24f, fmRatio = 1f, fmIndex = .12f, harmonicTilt = .22f),
            "A nearly pure dark key with a broad lower body.", "warm,soft,long", touch = .10f, color = .18f, shadow = 0f, wear = .08f, length = .84f),
        p(StoopVoice.TINE, "Clean Contact", StoopSource(shape = .35f, sub = 0f, fmRatio = 1f, fmIndex = 2.4f, harmonicTilt = .62f),
            "A harmonically rich hard-contact key without wear or shadow.", "clean,accented,direct", touch = .95f, color = .90f, shadow = 0f, wear = 0f, length = .46f),
        p(StoopVoice.TINE, "Soft Bell", StoopSource(shape = .08f, sub = 0f, fmRatio = 4f, fmIndex = .65f, harmonicTilt = .30f),
            "A soft clean bell with widely spaced upper partials.", "soft,bell,clean", touch = .08f, color = .68f, shadow = 0f, wear = 0f, length = .70f),
        p(StoopVoice.TINE, "Low Window", StoopSource(shape = .56f, sub = .48f, fmRatio = 1f, fmIndex = .95f, harmonicTilt = .40f),
            "A short full-bodied key with a low supporting octave.", "warm,struck,short", touch = .62f, color = .46f, shadow = 0f, wear = .18f, length = .22f),
        p(StoopVoice.TINE, "Tin Roof", StoopSource(shape = .22f, sub = 0f, fmRatio = 2f, fmIndex = 2.5f, harmonicTilt = .76f),
            "A hard bright contact rounded by coarse recording character.", "bright,accented,worn", touch = .98f, color = .92f, shadow = 0f, wear = .90f, length = .28f),
        p(StoopVoice.TINE, "After Key", StoopSource(shape = .46f, sub = .06f, fmRatio = 2f, fmIndex = 1.25f, harmonicTilt = .48f),
            "A defined strike followed by a prominent late octave bell.", "answer,octave,struck", touch = .52f, color = .62f, shadow = 1f, wear = .08f, length = .48f, shadowInterval = 12, shadowDelayMs = 250f, shadowArticulation = .05f),
        p(StoopVoice.TINE, "Night Key", StoopSource(shape = .62f, sub = .08f, fmRatio = 1f, fmIndex = .38f, harmonicTilt = .18f, detuneCents = 10f),
            "A softly beating dark key with a long quiet body.", "dark,soft,long,spread", touch = .06f, color = .34f, shadow = 0f, wear = .62f, length = .98f),
        p(StoopVoice.TINE, "Held Amber", StoopSource(shape = .38f, sub = .04f, fmRatio = 1f, fmIndex = 1.6f, harmonicTilt = .32f),
            "A rich settled key loop with a quiet sustained octave.", "held,warm,octave", touch = .45f, color = .70f, shadow = .32f, wear = .15f, length = .78f, hold = 1f, shadowInterval = 12, shadowArticulation = .08f),

        // Clav: body, harmonic slope and contact vary together.
        p(StoopVoice.CLAV, "Soft Clav", StoopSource(shape = .30f, sub = .22f, fmRatio = 1f, fmIndex = .30f, harmonicTilt = .26f),
            "A rounded muted pick with enough body to carry a riff.", "soft,muted", touch = .28f, color = .38f, length = .30f),
        p(StoopVoice.CLAV, "Short Wire", StoopSource(shape = .04f, sub = 0f, fmRatio = 2f, fmIndex = .75f, harmonicTilt = 1f),
            "A bright clean bridge-like pick with a clipped tonal tail.", "bright,picked,short", touch = .98f, color = .94f, shadow = 0f, wear = 0f, length = .24f),
        p(StoopVoice.CLAV, "Back Step", StoopSource(shape = .45f, sub = .06f, fmRatio = 1f, fmIndex = .45f, harmonicTilt = .85f),
            "A notched harmonic pick followed by a late muted root answer.", "picked,answer,muted", touch = .84f, color = .76f, shadow = .95f, wear = .10f, length = .30f, shadowDelayMs = 250f, shadowArticulation = .02f),
        p(StoopVoice.CLAV, "Dry Mute", StoopSource(shape = .46f, sub = .40f, fmRatio = 1f, fmIndex = .22f, harmonicTilt = .06f),
            "A very dark clean mute with a low rounded body.", "dark,clean,muted,direct", touch = .72f, color = .08f, shadow = 0f, wear = 0f, length = .25f),
        p(StoopVoice.CLAV, "Copper Pick", StoopSource(shape = .72f, sub = 0f, fmRatio = 3f, fmIndex = .65f, harmonicTilt = .90f),
            "A hard pick with a hollow attack and a longer tonal tail.", "picked,accented,bright", touch = .88f, color = .84f, shadow = 0f, wear = .28f, length = .36f),
        p(StoopVoice.CLAV, "Round Rhythm", StoopSource(shape = .20f, sub = .55f, fmRatio = 1.5f, fmIndex = .40f, harmonicTilt = .50f),
            "A low rounded pluck with a deliberately long simple body.", "rounded,picked,long", touch = .40f, color = .56f, shadow = 0f, wear = .08f, length = .68f),
        p(StoopVoice.CLAV, "Grain String", StoopSource(shape = .88f, sub = .08f, fmRatio = 2f, fmIndex = .92f, harmonicTilt = 1f),
            "A coarse held string-like registration with a settled harmonic body.", "held,worn,picked", touch = .78f, color = .92f, shadow = .08f, wear = .84f, length = .82f, hold = 1f),
        p(StoopVoice.CLAV, "Two Picks", StoopSource(shape = .98f, sub = 0f, fmRatio = 1.5f, fmIndex = .56f, harmonicTilt = .95f),
            "A wide-position pick with two late accented octave replies.", "answer,double,picked,octave", touch = .50f, color = .62f, shadow = 1f, wear = .08f, length = .40f, shadowInterval = 12, shadowDelayMs = 225f, shadowArticulation = .78f, shadowReplies = 2),

        // Organ: registrations retain harmonic identity at both TOUCH ends.
        p(StoopVoice.ORGAN, "Amber Organ", StoopSource(shape = .28f, sub = .28f, harmonicTilt = .24f),
            "A smooth full registration with mild recording warmth.", "smooth,warm", touch = .20f, color = .34f, length = .65f),
        p(StoopVoice.ORGAN, "Small Pipes", StoopSource(shape = 0f, sub = 0f, harmonicTilt = .80f),
            "A clean odd-harmonic registration for longer hollow phrases.", "hollow,clean,long", touch = .12f, color = .70f, shadow = 0f, wear = 0f, length = .72f),
        p(StoopVoice.ORGAN, "Corner Stab", StoopSource(shape = 1f, sub = .06f, harmonicTilt = .95f),
            "A bright clipped organ accent with a late octave reply.", "clipped,accented,answer", touch = .98f, color = .92f, shadow = .60f, wear = .08f, length = .18f, shadowInterval = 12, shadowDelayMs = 235f, shadowArticulation = .12f),
        p(StoopVoice.ORGAN, "Bright Loft", StoopSource(shape = 1f, sub = 0f, harmonicTilt = 1f),
            "A full bright upper registration with direct onset.", "bright,clean,direct", touch = .48f, color = 1f, shadow = 0f, wear = 0f, length = .52f),
        p(StoopVoice.ORGAN, "Worn Room", StoopSource(shape = .76f, sub = .38f, harmonicTilt = .78f),
            "A thick harmonic organ rounded by coarse recording character.", "worn,full", touch = .58f, color = .66f, shadow = 0f, wear = .92f, length = .40f),
        p(StoopVoice.ORGAN, "Held Pipes", StoopSource(shape = .02f, sub = .06f, harmonicTilt = .94f),
            "A settled hollow pipe loop with a quieter sustained octave.", "held,hollow,octave", touch = .50f, color = .84f, shadow = .35f, wear = 0f, length = .84f, hold = 1f, shadowInterval = 12, shadowArticulation = .12f),

        p(StoopVoice.LEAD, "Nose Talk", StoopSource(shape = .88f, sub = .08f, fmRatio = 2f, fmIndex = .65f, harmonicTilt = .74f),
            "A nasal accent for short conversational lead phrases.", "nasal,accented", touch = .68f, color = .72f, length = .40f),
        p(StoopVoice.LEAD, "Pocket Whistle", StoopSource(shape = .98f, sub = 0f, fmRatio = 1f, fmIndex = .16f, harmonicTilt = .18f),
            "A dark clean whistle-like line with a very soft onset.", "soft,clean,direct", touch = .04f, color = .02f, shadow = 0f, wear = 0f, length = .76f),
        p(StoopVoice.LEAD, "Thin Brass", StoopSource(shape = 0f, sub = .10f, fmRatio = 1.5f, fmIndex = 1.1f, harmonicTilt = .66f),
            "A bright saw-shaped accent with a late muted root answer.", "saw,accented,answer", touch = .95f, color = .66f, shadow = .65f, wear = .08f, length = .30f, shadowDelayMs = 250f, shadowArticulation = .12f),
        p(StoopVoice.LEAD, "Long Lantern", StoopSource(shape = .52f, sub = .04f, fmRatio = 2f, fmIndex = .42f, harmonicTilt = .36f, detuneCents = 12f),
            "A soft sustained lead loop with a gently beating companion.", "held,rounded,long,spread", touch = .12f, color = .30f, shadow = .12f, wear = .18f, length = .94f, hold = 1f, shadowArticulation = .65f),

        p(StoopVoice.CHORD, "Velvet Stack", StoopSource(shape = .28f, sub = .24f, harmonicTilt = .32f, detuneCents = 3f),
            "A soft root/fifth/octave block with a restrained root reply.", "soft,block,open", touch = .28f, color = .38f, length = .52f),
        p(StoopVoice.CHORD, "Small Cinema", StoopSource(shape = 0f, sub = 0f, harmonicTilt = .58f),
            "An explicit clean major-triad stab with a saw-shaped edge.", "major,clipped,clean,saw", touch = .94f, color = .68f, shadow = 0f, wear = 0f, length = .18f, chordIntervals = listOf(0, 4, 7)),
        p(StoopVoice.CHORD, "Minor Corner", StoopSource(shape = 1f, sub = .30f, harmonicTilt = .46f),
            "A dark worn minor-triad block with a late muted root reply.", "minor,rounded,answer,worn", touch = .38f, color = .20f, shadow = .65f, wear = .58f, length = .46f, shadowDelayMs = 250f, shadowArticulation = .08f, chordIntervals = listOf(0, 3, 7)),
        p(StoopVoice.CHORD, "Late Strings", StoopSource(shape = .16f, sub = .04f, harmonicTilt = .38f, detuneCents = 14f),
            "A soft lightly beating open-fifth/ninth loop for sustained blocks.", "held,soft,open,spread", touch = .06f, color = .24f, shadow = .15f, wear = .12f, length = .96f, hold = 1f, chordIntervals = listOf(0, 7, 14)),
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
