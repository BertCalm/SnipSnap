package com.snipsnap.synth

/** Thirty-six dry factory recipes, expanded after owner approval of the original twelve. */
object AlloyPresets {
    private fun p(voice: AlloyVoice, name: String, vararg macros: Pair<String, Float>) =
        AlloyPatch(name, voice, Alloy.defaults(voice) + macros.toMap())

    fun forVoice(voice: AlloyVoice): List<AlloyPatch> = when (voice) {
        AlloyVoice.BASS -> bass
        AlloyVoice.KEY -> key
        AlloyVoice.PLUCK -> pluck
        AlloyVoice.BRASS -> brass
        AlloyVoice.STRING -> string
        AlloyVoice.SOFT -> soft
    }

    fun all(): List<AlloyPatch> = AlloyVoice.entries.flatMap { forVoice(it) }

    private val bass = listOf(
        p(AlloyVoice.BASS, "Wooden Current", "MATERIAL" to .10f, "FUSE" to .35f, "EDGE" to .26f, "BODY" to .88f, "LENGTH" to .32f),
        p(AlloyVoice.BASS, "Rubber Wire", "MATERIAL" to .58f, "FUSE" to .68f, "EDGE" to .54f, "BODY" to .66f, "LENGTH" to .18f),
        // A rounded anchor, tight metallic pulse, breath-led line and continuous low cable.
        p(AlloyVoice.BASS, "Felt Anchor", "MATERIAL" to .03f, "FUSE" to .12f, "EDGE" to .10f, "BODY" to .98f, "LENGTH" to .62f),
        p(AlloyVoice.BASS, "Tin Pulse", "MATERIAL" to .48f, "FUSE" to .86f, "EDGE" to .82f, "BODY" to .34f, "LENGTH" to .10f),
        p(AlloyVoice.BASS, "Reed Current", "MATERIAL" to .96f, "FUSE" to .50f, "EDGE" to .38f, "BODY" to .76f, "LENGTH" to .48f),
        p(AlloyVoice.BASS, "Held Cable", "MATERIAL" to .68f, "FUSE" to .76f, "EDGE" to .58f, "BODY" to .82f, "LENGTH" to .76f, "HOLD" to 1f),
    )
    private val key = listOf(
        p(AlloyVoice.KEY, "Tine Alloy", "MATERIAL" to .48f, "FUSE" to .57f, "EDGE" to .52f, "BODY" to .58f, "LENGTH" to .55f),
        p(AlloyVoice.KEY, "Warm Circuit", "MATERIAL" to .18f, "FUSE" to .24f, "EDGE" to .30f, "BODY" to .78f, "LENGTH" to .68f),
        // Compact wood, bright breath, a long glassy PM tail and a rounded held contact.
        p(AlloyVoice.KEY, "Wooden Relay", "MATERIAL" to .04f, "FUSE" to .66f, "EDGE" to .36f, "BODY" to .46f, "LENGTH" to .20f),
        p(AlloyVoice.KEY, "Silver Reed", "MATERIAL" to .96f, "FUSE" to .74f, "EDGE" to .68f, "BODY" to .44f, "LENGTH" to .46f),
        p(AlloyVoice.KEY, "Glass Circuit", "MATERIAL" to .53f, "FUSE" to .18f, "EDGE" to .90f, "BODY" to .24f, "LENGTH" to .88f),
        p(AlloyVoice.KEY, "Held Contact", "MATERIAL" to .82f, "FUSE" to .68f, "EDGE" to .34f, "BODY" to .74f, "LENGTH" to .80f, "HOLD" to 1f),
    )
    private val pluck = listOf(
        p(AlloyVoice.PLUCK, "Muted Fusion", "MATERIAL" to .24f, "FUSE" to .66f, "EDGE" to .42f, "BODY" to .62f, "LENGTH" to .14f),
        p(AlloyVoice.PLUCK, "Lit Filament", "MATERIAL" to .62f, "FUSE" to .78f, "EDGE" to .76f, "BODY" to .35f, "LENGTH" to .34f),
        // Damped grain, a lingering tine, a thin breath needle and continuous bright wire.
        p(AlloyVoice.PLUCK, "Dry Grain", "MATERIAL" to .02f, "FUSE" to .22f, "EDGE" to .24f, "BODY" to .84f, "LENGTH" to .06f),
        p(AlloyVoice.PLUCK, "Wire Lantern", "MATERIAL" to .48f, "FUSE" to .38f, "EDGE" to .46f, "BODY" to .70f, "LENGTH" to .84f),
        p(AlloyVoice.PLUCK, "Air Needle", "MATERIAL" to .98f, "FUSE" to .88f, "EDGE" to .64f, "BODY" to .22f, "LENGTH" to .46f),
        p(AlloyVoice.PLUCK, "Held Thread", "MATERIAL" to .44f, "FUSE" to .70f, "EDGE" to .88f, "BODY" to .48f, "LENGTH" to .68f, "HOLD" to 1f),
    )
    private val brass = listOf(
        p(AlloyVoice.BRASS, "Breath Metal", "MATERIAL" to .90f, "FUSE" to .58f, "EDGE" to .61f, "BODY" to .64f, "LENGTH" to .56f),
        p(AlloyVoice.BRASS, "Copper Air", "MATERIAL" to .73f, "FUSE" to .28f, "EDGE" to .39f, "BODY" to .77f, "LENGTH" to .72f),
        // A woody low horn, tight metallic herald, long airy line and settled copper tone.
        p(AlloyVoice.BRASS, "Wooden Horn", "MATERIAL" to .08f, "FUSE" to .66f, "EDGE" to .30f, "BODY" to .88f, "LENGTH" to .36f),
        p(AlloyVoice.BRASS, "Tin Herald", "MATERIAL" to .48f, "FUSE" to .84f, "EDGE" to .86f, "BODY" to .44f, "LENGTH" to .18f),
        p(AlloyVoice.BRASS, "Air Column", "MATERIAL" to .99f, "FUSE" to .16f, "EDGE" to .54f, "BODY" to .22f, "LENGTH" to .94f),
        p(AlloyVoice.BRASS, "Held Copper", "MATERIAL" to .78f, "FUSE" to .72f, "EDGE" to .60f, "BODY" to .84f, "LENGTH" to .68f, "HOLD" to 1f),
    )
    private val string = listOf(
        p(AlloyVoice.STRING, "Drawn Wire", "MATERIAL" to .61f, "FUSE" to .47f, "EDGE" to .40f, "BODY" to .48f, "LENGTH" to .70f),
        p(AlloyVoice.STRING, "Held Filament", "MATERIAL" to .57f, "FUSE" to .66f, "EDGE" to .48f, "BODY" to .55f, "LENGTH" to .62f, "HOLD" to 1f),
        // Full wooden bow, taut bright wire, a long airy weave and compact picked fibre.
        p(AlloyVoice.STRING, "Wooden Bow", "MATERIAL" to .08f, "FUSE" to .32f, "EDGE" to .22f, "BODY" to .82f, "LENGTH" to .88f),
        p(AlloyVoice.STRING, "Silver Tendon", "MATERIAL" to .48f, "FUSE" to .84f, "EDGE" to .82f, "BODY" to .20f, "LENGTH" to .54f),
        p(AlloyVoice.STRING, "Air Loom", "MATERIAL" to .95f, "FUSE" to .60f, "EDGE" to .36f, "BODY" to .64f, "LENGTH" to .94f),
        p(AlloyVoice.STRING, "Short Fibre", "MATERIAL" to .32f, "FUSE" to .24f, "EDGE" to .58f, "BODY" to .42f, "LENGTH" to .12f),
    )
    private val soft = listOf(
        p(AlloyVoice.SOFT, "Soft Forge", "MATERIAL" to .32f, "FUSE" to .32f, "EDGE" to .18f, "BODY" to .76f, "LENGTH" to .84f),
        p(AlloyVoice.SOFT, "Endless Ember", "MATERIAL" to .79f, "FUSE" to .57f, "EDGE" to .28f, "BODY" to .70f, "LENGTH" to .80f, "HOLD" to 1f),
        // Compact felt, lingering tine color, delicate air and a shorter luminous wire accent.
        p(AlloyVoice.SOFT, "Felt Circuit", "MATERIAL" to .04f, "FUSE" to .14f, "EDGE" to .08f, "BODY" to .96f, "LENGTH" to .46f),
        p(AlloyVoice.SOFT, "Opal Tine", "MATERIAL" to .51f, "FUSE" to .64f, "EDGE" to .48f, "BODY" to .46f, "LENGTH" to .92f),
        p(AlloyVoice.SOFT, "Breath Veil", "MATERIAL" to .98f, "FUSE" to .26f, "EDGE" to .10f, "BODY" to .56f, "LENGTH" to .68f),
        p(AlloyVoice.SOFT, "Glowing Wire", "MATERIAL" to .59f, "FUSE" to .84f, "EDGE" to .62f, "BODY" to .30f, "LENGTH" to .28f),
    )
}
