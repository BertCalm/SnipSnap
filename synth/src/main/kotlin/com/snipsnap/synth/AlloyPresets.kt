package com.snipsnap.synth

/** Twelve dry audition candidates. Expansion to the proposed 36 follows owner listening approval. */
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
    )
    private val key = listOf(
        p(AlloyVoice.KEY, "Tine Alloy", "MATERIAL" to .48f, "FUSE" to .57f, "EDGE" to .52f, "BODY" to .58f, "LENGTH" to .55f),
        p(AlloyVoice.KEY, "Warm Circuit", "MATERIAL" to .18f, "FUSE" to .24f, "EDGE" to .30f, "BODY" to .78f, "LENGTH" to .68f),
    )
    private val pluck = listOf(
        p(AlloyVoice.PLUCK, "Muted Fusion", "MATERIAL" to .24f, "FUSE" to .66f, "EDGE" to .42f, "BODY" to .62f, "LENGTH" to .14f),
        p(AlloyVoice.PLUCK, "Lit Filament", "MATERIAL" to .62f, "FUSE" to .78f, "EDGE" to .76f, "BODY" to .35f, "LENGTH" to .34f),
    )
    private val brass = listOf(
        p(AlloyVoice.BRASS, "Breath Metal", "MATERIAL" to .90f, "FUSE" to .58f, "EDGE" to .61f, "BODY" to .64f, "LENGTH" to .56f),
        p(AlloyVoice.BRASS, "Copper Air", "MATERIAL" to .73f, "FUSE" to .28f, "EDGE" to .39f, "BODY" to .77f, "LENGTH" to .72f),
    )
    private val string = listOf(
        p(AlloyVoice.STRING, "Drawn Wire", "MATERIAL" to .61f, "FUSE" to .47f, "EDGE" to .40f, "BODY" to .48f, "LENGTH" to .70f),
        p(AlloyVoice.STRING, "Held Filament", "MATERIAL" to .57f, "FUSE" to .66f, "EDGE" to .48f, "BODY" to .55f, "LENGTH" to .62f, "HOLD" to 1f),
    )
    private val soft = listOf(
        p(AlloyVoice.SOFT, "Soft Forge", "MATERIAL" to .32f, "FUSE" to .32f, "EDGE" to .18f, "BODY" to .76f, "LENGTH" to .84f),
        p(AlloyVoice.SOFT, "Endless Ember", "MATERIAL" to .79f, "FUSE" to .57f, "EDGE" to .28f, "BODY" to .70f, "LENGTH" to .80f, "HOLD" to 1f),
    )
}
