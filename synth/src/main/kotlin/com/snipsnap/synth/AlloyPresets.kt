package com.snipsnap.synth

/** Twenty-four dry factory recipes approved through the preset-role audition.
 * Each voice retains its previous first preset and adds three fully authored roles.
 * The previous held references remain available to kits outside the active bank. */
object AlloyPresets {
    private fun p(voice: AlloyVoice, name: String, vararg macros: Pair<String, Float>) =
        AlloyPatch(name, voice, Alloy.defaults(voice) + macros.toMap())

    private fun role(voice: AlloyVoice, name: String, config: AlloyConfig, vararg macros: Pair<String, Float>) =
        AlloyPatch(name, voice, Alloy.defaults(voice) + macros.toMap(), config = config)

    fun forVoice(voice: AlloyVoice): List<AlloyPatch> = when (voice) {
        AlloyVoice.BASS -> bass
        AlloyVoice.KEY -> key
        AlloyVoice.PLUCK -> pluck
        AlloyVoice.BRASS -> brass
        AlloyVoice.STRING -> string
        AlloyVoice.SOFT -> soft
    }

    fun all(): List<AlloyPatch> = AlloyVoice.entries.flatMap { forVoice(it) }

    /** Existing material-driven loops retained for starter kits and saved-kit compatibility. */
    fun heldReferences(): List<AlloyPatch> = held

    private val bass = listOf(
        p(AlloyVoice.BASS, "Wooden Current", "MATERIAL" to 0.1f, "FUSE" to 0.35f, "EDGE" to 0.26f, "BODY" to 0.88f, "LENGTH" to 0.32f),
        role(AlloyVoice.BASS, "Rounded Plug", AlloyFactoryRoles.roundedPlug, "MATERIAL" to 0.05f, "FUSE" to 0.12f, "EDGE" to 0.12f, "BODY" to 0.92f, "LENGTH" to 0.02f),
        role(AlloyVoice.BASS, "Elastic Touch", AlloyFactoryRoles.elasticContact, "MATERIAL" to 0.12f, "FUSE" to 0.45f, "EDGE" to 0.78f, "BODY" to 0.05f, "LENGTH" to 0.24f),
        role(AlloyVoice.BASS, "Hollow Rail", AlloyFactoryRoles.hollowRail, "MATERIAL" to 0.32f, "FUSE" to 0.25f, "EDGE" to 0.86f, "BODY" to 0.03f, "LENGTH" to 0.52f),
    )

    private val key = listOf(
        p(AlloyVoice.KEY, "Tine Alloy", "MATERIAL" to 0.48f, "FUSE" to 0.57f, "EDGE" to 0.52f, "BODY" to 0.58f, "LENGTH" to 0.55f),
        role(AlloyVoice.KEY, "Felt Relay", AlloyFactoryRoles.feltRelay, "MATERIAL" to 0.02f, "FUSE" to 0.22f, "EDGE" to 0.18f, "BODY" to 0.82f, "LENGTH" to 0.12f),
        role(AlloyVoice.KEY, "Long Prism", AlloyFactoryRoles.longPrism, "MATERIAL" to 0.5f, "FUSE" to 0.2f, "EDGE" to 0.78f, "BODY" to 0.03f, "LENGTH" to 0.78f),
        role(AlloyVoice.KEY, "Reed Switch", AlloyFactoryRoles.reedSwitch, "MATERIAL" to 0.12f, "FUSE" to 0.32f, "EDGE" to 0.65f, "BODY" to 0.05f, "LENGTH" to 0.48f),
    )

    private val pluck = listOf(
        p(AlloyVoice.PLUCK, "Muted Fusion", "MATERIAL" to 0.24f, "FUSE" to 0.66f, "EDGE" to 0.42f, "BODY" to 0.62f, "LENGTH" to 0.14f),
        role(AlloyVoice.PLUCK, "Damped Pick", AlloyFactoryRoles.dampedPick, "MATERIAL" to 0.04f, "FUSE" to 0.38f, "EDGE" to 0.34f, "BODY" to 0.35f, "LENGTH" to 0.01f),
        role(AlloyVoice.PLUCK, "Open Chime", AlloyFactoryRoles.openChime, "MATERIAL" to 0.5f, "FUSE" to 0.3f, "EDGE" to 0.85f, "BODY" to 0.03f, "LENGTH" to 0.67f),
        role(AlloyVoice.PLUCK, "Nasal Snap", AlloyFactoryRoles.nasalSnap, "MATERIAL" to 0.05f, "FUSE" to 0.55f, "EDGE" to 0.98f, "BODY" to 0.02f, "LENGTH" to 0.22f),
    )

    private val brass = listOf(
        p(AlloyVoice.BRASS, "Breath Metal", "MATERIAL" to 0.9f, "FUSE" to 0.58f, "EDGE" to 0.61f, "BODY" to 0.64f, "LENGTH" to 0.56f),
        role(AlloyVoice.BRASS, "Round Horn", AlloyFactoryRoles.roundHorn, "MATERIAL" to 0.12f, "FUSE" to 0.22f, "EDGE" to 0.22f, "BODY" to 0.82f, "LENGTH" to 0.56f),
        role(AlloyVoice.BRASS, "Hollow Copper", AlloyFactoryRoles.hollowCopper, "MATERIAL" to 0.18f, "FUSE" to 0.28f, "EDGE" to 0.72f, "BODY" to 0.03f, "LENGTH" to 0.55f),
        role(AlloyVoice.BRASS, "Bright Stamp", AlloyFactoryRoles.brightStamp, "MATERIAL" to 0.1f, "FUSE" to 0.42f, "EDGE" to 0.98f, "BODY" to 0.03f, "LENGTH" to 0.1f),
    )

    private val string = listOf(
        p(AlloyVoice.STRING, "Drawn Wire", "MATERIAL" to 0.61f, "FUSE" to 0.47f, "EDGE" to 0.4f, "BODY" to 0.48f, "LENGTH" to 0.7f),
        role(AlloyVoice.STRING, "Dark Bow", AlloyFactoryRoles.darkBow, "MATERIAL" to 0.12f, "FUSE" to 0.2f, "EDGE" to 0.15f, "BODY" to 0.88f, "LENGTH" to 0.75f),
        role(AlloyVoice.STRING, "Picked Fibre", AlloyFactoryRoles.pickedFibre, "MATERIAL" to 0.04f, "FUSE" to 0.42f, "EDGE" to 0.78f, "BODY" to 0.07f, "LENGTH" to 0.04f),
        role(AlloyVoice.STRING, "Beating Glass", AlloyFactoryRoles.beatingGlass, "MATERIAL" to 0.5f, "FUSE" to 0.24f, "EDGE" to 0.72f, "BODY" to 0.03f, "LENGTH" to 0.78f),
    )

    private val soft = listOf(
        p(AlloyVoice.SOFT, "Soft Forge", "MATERIAL" to 0.32f, "FUSE" to 0.32f, "EDGE" to 0.18f, "BODY" to 0.76f, "LENGTH" to 0.84f),
        role(AlloyVoice.SOFT, "Felt Drop", AlloyFactoryRoles.feltDrop, "MATERIAL" to 0.03f, "FUSE" to 0.12f, "EDGE" to 0.12f, "BODY" to 0.9f, "LENGTH" to 0.04f),
        role(AlloyVoice.SOFT, "Velvet Rise", AlloyFactoryRoles.velvetRise, "MATERIAL" to 0.2f, "FUSE" to 0.18f, "EDGE" to 0.18f, "BODY" to 0.3f, "LENGTH" to 0.78f),
        role(AlloyVoice.SOFT, "Hollow Glow", AlloyFactoryRoles.hollowGlow, "MATERIAL" to 0.35f, "FUSE" to 0.24f, "EDGE" to 0.42f, "BODY" to 0.03f, "LENGTH" to 0.74f),
    )

    private val held = listOf(
        p(AlloyVoice.BASS, "Held Cable", "MATERIAL" to .68f, "FUSE" to .76f, "EDGE" to .58f, "BODY" to .82f, "LENGTH" to .76f, "HOLD" to 1f),
        p(AlloyVoice.KEY, "Held Contact", "MATERIAL" to .82f, "FUSE" to .68f, "EDGE" to .34f, "BODY" to .74f, "LENGTH" to .80f, "HOLD" to 1f),
        p(AlloyVoice.PLUCK, "Held Thread", "MATERIAL" to .44f, "FUSE" to .70f, "EDGE" to .88f, "BODY" to .48f, "LENGTH" to .68f, "HOLD" to 1f),
        p(AlloyVoice.BRASS, "Held Copper", "MATERIAL" to .78f, "FUSE" to .72f, "EDGE" to .60f, "BODY" to .84f, "LENGTH" to .68f, "HOLD" to 1f),
        p(AlloyVoice.STRING, "Held Filament", "MATERIAL" to .57f, "FUSE" to .66f, "EDGE" to .48f, "BODY" to .55f, "LENGTH" to .62f, "HOLD" to 1f),
        p(AlloyVoice.SOFT, "Endless Ember", "MATERIAL" to .79f, "FUSE" to .57f, "EDGE" to .28f, "BODY" to .70f, "LENGTH" to .80f, "HOLD" to 1f),
    )
}
