package com.snipsnap.synth

/** Thirty-six dry playing roles. The accepted starting eight stay first and keep their recipes. */
object EmbouchurePresets {
    enum class Role(val id: String, val label: String) {
        LEAD("lead", "Lead"),
        BASS("bass", "Bass"),
        AIR("air", "Air"),
        BOWED("bowed", "Bowed"),
        SOFT("soft", "Soft"),
    }

    /** Musical intent belongs beside the saved recipe, rather than being inferred by an audition UI. */
    data class Entry(
        val patch: EmbouchurePatch,
        val role: Role,
        val description: String,
        val isInitial: Boolean = false,
    ) {
        val category: String get() = role.id
        val isHeld: Boolean get() = Embouchure.isLoop(patch.macros.getValue("HOLD"))
    }

    // Pin the bank to its accepted renderer even if a later model becomes the editor default.
    private fun p(voice: EmbouchureVoice, name: String, vararg macros: Pair<String, Float>) =
        EmbouchurePatch(name, voice, Embouchure.defaults(voice) + macros.toMap(), model = 2)

    private val initialEntries = listOf(
        Entry(
            p(EmbouchureVoice.REED, "Leaning Reed").copy(gesture = EmbouchureGesture.LEANING_SWELL),
            Role.LEAD, "A rounded reed entry that leans into a brighter phrase.", isInitial = true,
        ),
        Entry(
            p(EmbouchureVoice.BRASS, "Muted Bell", "SHAPE" to .62f).copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.LEAD, "A dark brass color with a firm start and a relaxing finish.", isInitial = true,
        ),
        Entry(
            p(EmbouchureVoice.BASS, "Breath Pocket", "GRAIN" to .32f, "LENGTH" to .35f),
            Role.BASS, "A compact bass phrase with a little breath around its pitched center.", isInitial = true,
        ),
        Entry(
            p(EmbouchureVoice.SOFT, "Soft Entry").copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.SOFT, "A slow, rounded arrival with room for the note to open.", isInitial = true,
        ),
        Entry(
            p(EmbouchureVoice.REED, "Short Overblow", "EFFORT" to .75f, "REED" to .65f, "LENGTH" to .25f)
                .copy(gesture = EmbouchureGesture.OVERPRESSURE),
            Role.LEAD, "An accented reed entry that settles back into its root.", isInitial = true,
        ),
        Entry(
            p(EmbouchureVoice.BOW, "Relaxed Bow", "EFFORT" to .35f, "REED" to .35f, "GRAIN" to .18f)
                .copy(gesture = EmbouchureGesture.BOWED_CAPTURE),
            Role.BOWED, "An easy bow capture followed by a long, restrained phrase.", isInitial = true,
        ),
        Entry(
            p(EmbouchureVoice.WHISTLE, "Narrow Air", "SHAPE" to .65f, "GRAIN" to .08f),
            Role.AIR, "A clear upper voice with a narrow body and little grain.", isInitial = true,
        ),
        Entry(
            p(EmbouchureVoice.SOFT, "Held Effort", "EFFORT" to .45f, "LENGTH" to .65f, "HOLD" to 1f)
                .copy(gesture = EmbouchureGesture.LEANING_SWELL),
            Role.SOFT, "A soft sustain with a repeating, gently leaning effort contour.", isInitial = true,
        ),
    )

    private val additions = listOf(
        // REED: seven finite lead/bass articulations and one restrained sustain in the full bank.
        Entry(
            p(EmbouchureVoice.REED, "Tongue Point", "EFFORT" to .62f, "REED" to .58f,
                "SHAPE" to .22f, "GRAIN" to .10f, "LENGTH" to .12f)
                .copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.LEAD, "A short reed punctuation with a direct start and a clear edge.",
        ),
        Entry(
            p(EmbouchureVoice.REED, "Round Reed", "EFFORT" to .34f, "REED" to .28f,
                "SHAPE" to .56f, "GRAIN" to .12f, "LENGTH" to .46f)
                .copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.LEAD, "A gentler reed line with rounded contact and a gradual opening.",
        ),
        Entry(
            p(EmbouchureVoice.REED, "Open Reed", "EFFORT" to .52f, "REED" to .62f,
                "SHAPE" to .18f, "GRAIN" to .27f, "LENGTH" to .48f)
                .copy(gesture = EmbouchureGesture.LEANING_SWELL),
            Role.LEAD, "An open body and tighter contact give this swelling lead a brighter outline.",
        ),
        Entry(
            p(EmbouchureVoice.REED, "Breath Accent", "EFFORT" to .60f, "REED" to .48f,
                "SHAPE" to .38f, "GRAIN" to .46f, "LENGTH" to .24f)
                .copy(gesture = EmbouchureGesture.OVERPRESSURE),
            Role.LEAD, "A brief pressure accent with a breathier edge around the note.",
        ),
        Entry(
            p(EmbouchureVoice.REED, "Low Murmur", "TUNE" to 0f, "EFFORT" to .42f,
                "REED" to .38f, "SHAPE" to .63f, "GRAIN" to .18f, "LENGTH" to .60f)
                .copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.BASS, "A lower reed register with a hollow body and an unhurried release.",
        ),
        Entry(
            p(EmbouchureVoice.REED, "Held Reed", "EFFORT" to .48f, "REED" to .47f,
                "SHAPE" to .36f, "GRAIN" to .12f, "LENGTH" to .54f, "HOLD" to 1f)
                .copy(gesture = EmbouchureGesture.LEANING_SWELL),
            Role.LEAD, "A middle-register reed sustain with restrained repeating motion.",
        ),

        // BRASS: calls, a dark cup, short punctuation, a lower line and a held bell.
        Entry(
            p(EmbouchureVoice.BRASS, "Bell Call", "EFFORT" to .67f, "REED" to .58f,
                "SHAPE" to .26f, "GRAIN" to .10f, "LENGTH" to .32f)
                .copy(gesture = EmbouchureGesture.OVERPRESSURE),
            Role.LEAD, "A clear brass call with an accented entry and an open body.",
        ),
        Entry(
            p(EmbouchureVoice.BRASS, "Warm Cup", "EFFORT" to .43f, "REED" to .30f,
                "SHAPE" to .76f, "GRAIN" to .08f, "LENGTH" to .46f)
                .copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.SOFT, "A dark, rounded brass phrase that eases into its center.",
        ),
        Entry(
            p(EmbouchureVoice.BRASS, "Short Bell", "EFFORT" to .62f, "REED" to .42f,
                "SHAPE" to .50f, "GRAIN" to .12f, "LENGTH" to .10f)
                .copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.LEAD, "Compact brass punctuation with a quick start and a tidy relaxation.",
        ),
        Entry(
            p(EmbouchureVoice.BRASS, "Lower Brass", "TUNE" to 0f, "EFFORT" to .51f,
                "REED" to .52f, "SHAPE" to .57f, "GRAIN" to .16f, "LENGTH" to .62f)
                .copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.BASS, "A broad lower-register brass line with a longer pressure release.",
        ),
        Entry(
            p(EmbouchureVoice.BRASS, "Held Bell", "EFFORT" to .56f, "REED" to .46f,
                "SHAPE" to .46f, "GRAIN" to .08f, "LENGTH" to .55f, "HOLD" to 1f)
                .copy(gesture = EmbouchureGesture.OVERPRESSURE),
            Role.LEAD, "A round brass sustain with gentle motion and little air noise.",
        ),

        // BASS: contrasting pocket, deep, accented, breathy and sustained foundations.
        Entry(
            p(EmbouchureVoice.BASS, "Reed Pocket", "EFFORT" to .53f, "REED" to .50f,
                "SHAPE" to .34f, "GRAIN" to .14f, "LENGTH" to .22f)
                .copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.BASS, "A focused bass note with a clean start and a compact phrase.",
        ),
        Entry(
            p(EmbouchureVoice.BASS, "Deep Column", "TUNE" to 0f, "EFFORT" to .48f,
                "REED" to .36f, "SHAPE" to .68f, "GRAIN" to .10f, "LENGTH" to .65f)
                .copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.BASS, "A deep, dark foundation that leaves room for a long relaxation.",
        ),
        Entry(
            p(EmbouchureVoice.BASS, "Biting Bass", "EFFORT" to .65f, "REED" to .62f,
                "SHAPE" to .40f, "GRAIN" to .27f, "LENGTH" to .18f)
                .copy(gesture = EmbouchureGesture.OVERPRESSURE),
            Role.BASS, "A firmer bass accent with tighter contact and a little grit.",
        ),
        Entry(
            p(EmbouchureVoice.BASS, "Air Floor", "EFFORT" to .47f, "REED" to .32f,
                "SHAPE" to .50f, "GRAIN" to .42f, "LENGTH" to .44f)
                .copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.BASS, "A breathier bass entry that opens into a clear pitched center.",
        ),
        Entry(
            p(EmbouchureVoice.BASS, "Held Foundation", "TUNE" to 0f, "EFFORT" to .46f,
                "REED" to .40f, "SHAPE" to .57f, "GRAIN" to .08f, "LENGTH" to .48f, "HOLD" to 1f)
                .copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.BASS, "A low bass sustain with a steady center and a restrained contour.",
        ),

        // WHISTLE: the original narrow voice, an open call, an airy upper phrase and a sustain.
        Entry(
            p(EmbouchureVoice.WHISTLE, "Clear Call", "EFFORT" to .52f, "REED" to .48f,
                "SHAPE" to .12f, "GRAIN" to .14f, "LENGTH" to .28f)
                .copy(gesture = EmbouchureGesture.LEANING_SWELL),
            Role.LEAD, "An open upper voice with a more direct entry and a small swell.",
        ),
        Entry(
            p(EmbouchureVoice.WHISTLE, "Air Thread", "TUNE" to 1f, "EFFORT" to .39f,
                "REED" to .24f, "SHAPE" to .35f, "GRAIN" to .36f, "LENGTH" to .68f)
                .copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.AIR, "A high, airy phrase with loose contact and a lingering finish.",
        ),
        Entry(
            p(EmbouchureVoice.WHISTLE, "Held Air", "EFFORT" to .36f, "REED" to .31f,
                "SHAPE" to .28f, "GRAIN" to .06f, "LENGTH" to .62f, "HOLD" to 1f)
                .copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.AIR, "A clear upper sustain with very little grain and gentle motion.",
        ),

        // BOW: short capture, hollow body, friction phrase and two restrained sustain colors.
        Entry(
            p(EmbouchureVoice.BOW, "Pointed Bow", "EFFORT" to .64f, "REED" to .62f,
                "SHAPE" to .24f, "GRAIN" to .14f, "LENGTH" to .18f)
                .copy(gesture = EmbouchureGesture.BOWED_CAPTURE),
            Role.LEAD, "A shorter bowed attack with tighter contact and a clear harmonic edge.",
        ),
        Entry(
            p(EmbouchureVoice.BOW, "Hollow String", "TUNE" to 0f, "EFFORT" to .46f,
                "REED" to .48f, "SHAPE" to .74f, "GRAIN" to .20f, "LENGTH" to .55f)
                .copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.BOWED, "A lower bowed phrase with a hollow body and a gentler capture.",
        ),
        Entry(
            p(EmbouchureVoice.BOW, "Rosin Line", "EFFORT" to .53f, "REED" to .54f,
                "SHAPE" to .37f, "GRAIN" to .46f, "LENGTH" to .66f)
                .copy(gesture = EmbouchureGesture.LEANING_SWELL),
            Role.BOWED, "A longer bow phrase with friction grain around its changing pressure.",
        ),
        Entry(
            p(EmbouchureVoice.BOW, "Held String", "EFFORT" to .46f, "REED" to .45f,
                "SHAPE" to .40f, "GRAIN" to .12f, "LENGTH" to .60f, "HOLD" to 1f)
                .copy(gesture = EmbouchureGesture.BOWED_CAPTURE),
            Role.BOWED, "A clear middle bowed sustain with restrained friction texture.",
        ),
        Entry(
            p(EmbouchureVoice.BOW, "Low Bow Hold", "TUNE" to 0f, "EFFORT" to .43f,
                "REED" to .38f, "SHAPE" to .64f, "GRAIN" to .10f, "LENGTH" to .74f, "HOLD" to 1f)
                .copy(gesture = EmbouchureGesture.BOWED_CAPTURE),
            Role.BOWED, "A low bowed sustain with dark body color and an unhurried contour.",
        ),

        // SOFT: keep the accepted entry/sustain, then add a warm line, punctuation and airy height.
        Entry(
            p(EmbouchureVoice.SOFT, "Warm Arrival", "EFFORT" to .42f, "REED" to .32f,
                "SHAPE" to .68f, "GRAIN" to .12f, "LENGTH" to .54f)
                .copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.SOFT, "A warm soft voice that opens sooner and relaxes without a hard edge.",
        ),
        Entry(
            p(EmbouchureVoice.SOFT, "Velvet Cut", "EFFORT" to .50f, "REED" to .40f,
                "SHAPE" to .40f, "GRAIN" to .16f, "LENGTH" to .16f)
                .copy(gesture = EmbouchureGesture.PRESSURE_RELEASE),
            Role.LEAD, "A compact soft lead with a clearer start and a rounded release.",
        ),
        Entry(
            p(EmbouchureVoice.SOFT, "Feather Breath", "TUNE" to 1f, "EFFORT" to .35f,
                "REED" to .22f, "SHAPE" to .30f, "GRAIN" to .40f, "LENGTH" to .60f)
                .copy(gesture = EmbouchureGesture.LEANING_SWELL),
            Role.AIR, "An airy upper soft phrase with a little more light around its center.",
        ),
        Entry(
            p(EmbouchureVoice.SOFT, "Held Velvet", "TUNE" to 0f, "EFFORT" to .36f,
                "REED" to .26f, "SHAPE" to .73f, "GRAIN" to .08f, "LENGTH" to .78f, "HOLD" to 1f)
                .copy(gesture = EmbouchureGesture.SOFT_ENTRY),
            Role.SOFT, "A low, dark soft sustain with little grain and a broad contour.",
        ),
    )

    private val entries = initialEntries + additions
    private val initialPresets = initialEntries.map { it.patch }
    private val presets = entries.map { it.patch }
    private val byName = entries.associateBy { it.patch.name }

    /** The exact first eight, in their established starter-kit and reference order. */
    fun initial(): List<EmbouchurePatch> = initialPresets
    fun forVoice(voice: EmbouchureVoice): List<EmbouchurePatch> = presets.filter { it.voice == voice }
    fun all(): List<EmbouchurePatch> = presets
    fun catalog(): List<Entry> = entries
    fun metadataFor(name: String): Entry? = byName[name]
}
