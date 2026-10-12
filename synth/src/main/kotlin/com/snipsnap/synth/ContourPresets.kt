package com.snipsnap.synth

/**
 * Nine dry first-listen recipes. Rounded attacks, delayed edges and contrasting
 * low-partial palettes establish each voice before owner-approved expansion.
 * Complete saved configurations keep these recipes independent of voice defaults.
 */
object ContourPresets {
    // Promoted shelf rows paste into this helper, then copy their complete saved configuration.
    private fun p(voice: ContourVoice, name: String, vararg macros: Pair<String, Float>) =
        ContourPatch(name, voice, ContourEngine.defaults(voice) + macros.toMap())

    private fun recipe(voice: ContourVoice, name: String, config: ContourConfig,
        scan: Float, offset: Float, motion: Float, filter: Float, drive: Float, hold: Float = 0f) =
        ContourPatch(name, voice, linkedMapOf("TUNE" to .5f, "SCAN" to scan,
            "OFFSET" to offset, "MOTION" to motion, "FILTER" to filter,
            "DRIVE" to drive, "HOLD" to hold), config)

    private val presets = listOf(
        recipe(ContourVoice.BASS, "Rounded Edge", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ROUND_PULSE,
            edgeRatio = 1f, edgeLag = .25f, relation = ContourRelation.LAG,
            bodyLevel = .84f, edgeLevel = .26f, subLevel = .07f,
            bodyHarmonics = 1.1f, edgeRootLevel = .8f,
            edgeAttack = .085f, edgeDecay = .7f, edgeSustain = .35f,
            bodyScanBias = -.05f, edgeScanBias = -.1f, bodyTravel = .18f, edgeTravel = .32f,
            ampAttack = .035f, ampDecay = .65f, ampSustain = .52f, ampRelease = .8f,
            sustainSeconds = .9f, filterAttack = .04f, filterDecay = .7f, filterAmount = .12f,
            motionShape = ContourMotionShape.FALL, motionSeconds = 1.6f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .28f, offset = .2f, motion = .38f,
            filter = .3f, drive = 0f),
        recipe(ContourVoice.BASS, "Moving Corner", ContourConfig(
            ContourTable.ODD_EVEN, ContourTable.SOFT_SAW,
            edgeRatio = 1f, edgeLag = .3f, relation = ContourRelation.OPPOSED,
            bodyLevel = .82f, edgeLevel = .33f, subLevel = .05f,
            bodyHarmonics = 1.05f, edgeRootLevel = .8f,
            edgeAttack = .12f, edgeDecay = 1.2f, edgeSustain = .52f,
            bodyScanBias = -.04f, edgeScanBias = -.12f, bodyTravel = .18f, edgeTravel = .45f,
            ampAttack = .06f, ampDecay = .75f, ampSustain = .55f, ampRelease = .85f,
            sustainSeconds = 1.1f, filterAttack = .07f, filterDecay = 1f, filterAmount = .1f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 2.1f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .3f, offset = .23f, motion = .58f,
            filter = .32f, drive = .02f),
        recipe(ContourVoice.BASS, "Dark Table", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ODD_EVEN,
            edgeRatio = 1f, edgeLag = .25f, relation = ContourRelation.CURVE,
            bodyLevel = .86f, edgeLevel = .22f, subLevel = .08f,
            bodyHarmonics = 1.1f, edgeRootLevel = .85f,
            edgeAttack = .16f, edgeDecay = 1.2f, edgeSustain = .4f,
            bodyScanBias = -.1f, edgeScanBias = -.12f, bodyTravel = .14f, edgeTravel = .3f,
            ampAttack = .09f, ampDecay = .9f, ampSustain = .56f, ampRelease = 1f,
            sustainSeconds = 1.1f, filterAttack = .08f, filterDecay = .9f, filterAmount = .08f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 2.4f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .22f, offset = .2f, motion = .25f,
            filter = .2f, drive = 0f),
        recipe(ContourVoice.KEY, "Copper Scan", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.COPPER,
            edgeRatio = 1f, edgeLag = .34f, relation = ContourRelation.LAG,
            bodyLevel = .8f, edgeLevel = .23f, subLevel = 0f,
            bodyHarmonics = 1.05f, edgeRootLevel = .8f,
            edgeAttack = .1f, edgeDecay = .65f, edgeSustain = .2f,
            bodyScanBias = -.04f, edgeScanBias = -.24f, bodyTravel = .17f, edgeTravel = .4f,
            ampAttack = .04f, ampDecay = .95f, ampSustain = .3f, ampRelease = 1f,
            sustainSeconds = .8f, filterAttack = .08f, filterDecay = .9f, filterAmount = .1f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 1.9f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .3f, offset = .22f, motion = .5f,
            filter = .3f, drive = 0f),
        recipe(ContourVoice.KEY, "Soft Digital", ContourConfig(
            ContourTable.SAW_HOLLOW, ContourTable.DIGITAL_BELL,
            edgeRatio = 1f, edgeLag = .25f, relation = ContourRelation.LAG,
            bodyLevel = .8f, edgeLevel = .18f, subLevel = 0f,
            bodyHarmonics = 1.1f, edgeRootLevel = .8f,
            edgeAttack = .2f, edgeDecay = 1.1f, edgeSustain = .35f,
            bodyScanBias = -.08f, edgeScanBias = -.27f, bodyTravel = .18f, edgeTravel = .33f,
            ampAttack = .095f, ampDecay = 1.1f, ampSustain = .42f, ampRelease = 1.2f,
            sustainSeconds = .8f, filterAttack = .14f, filterDecay = .95f, filterAmount = .1f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 2.2f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .26f, offset = .18f, motion = .32f,
            filter = .28f, drive = 0f),
        recipe(ContourVoice.PLUCK, "Bright Margin", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ROUND_PULSE,
            edgeRatio = 1f, edgeLag = .2f, relation = ContourRelation.CURVE,
            bodyLevel = .8f, edgeLevel = .3f, subLevel = 0f,
            bodyHarmonics = 1.1f, edgeRootLevel = .8f,
            edgeAttack = .045f, edgeDecay = .3f, edgeSustain = .12f,
            bodyScanBias = -.06f, edgeScanBias = -.15f, bodyTravel = .22f, edgeTravel = .42f,
            ampAttack = .018f, ampDecay = .62f, ampSustain = .16f, ampRelease = .52f,
            sustainSeconds = .25f, filterAttack = .015f, filterDecay = .5f, filterAmount = .16f,
            motionShape = ContourMotionShape.FALL, motionSeconds = .9f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .34f, offset = .2f, motion = .45f,
            filter = .38f, drive = 0f),
        recipe(ContourVoice.LEAD, "Twin Outline", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ODD_EVEN,
            edgeRatio = 1f, edgeLag = .22f, relation = ContourRelation.OPPOSED,
            bodyLevel = .8f, edgeLevel = .25f, subLevel = 0f,
            bodyHarmonics = 1.05f, edgeRootLevel = .8f,
            edgeAttack = .18f, edgeDecay = 1.5f, edgeSustain = .55f,
            bodyScanBias = -.03f, edgeScanBias = -.17f, bodyTravel = .22f, edgeTravel = .35f,
            ampAttack = .07f, ampDecay = .5f, ampSustain = .66f, ampRelease = .9f,
            sustainSeconds = 1.4f, filterAttack = .1f, filterDecay = 1.2f, filterAmount = .11f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 1.9f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .33f, offset = .22f, motion = .5f,
            filter = .37f, drive = .02f),
        recipe(ContourVoice.HOLLOW, "Hollow Step", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.SAW_HOLLOW,
            edgeRatio = 1f, edgeLag = .32f, relation = ContourRelation.CURVE,
            bodyLevel = .8f, edgeLevel = .34f, subLevel = 0f,
            bodyHarmonics = 1.05f, edgeRootLevel = .8f,
            edgeAttack = .26f, edgeDecay = 1.2f, edgeSustain = .45f,
            bodyScanBias = -.1f, edgeScanBias = .1f, bodyTravel = .18f, edgeTravel = .35f,
            ampAttack = .11f, ampDecay = .8f, ampSustain = .58f, ampRelease = 1.15f,
            sustainSeconds = 1.1f, filterAttack = .14f, filterDecay = 1.1f, filterAmount = .1f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 2.4f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .3f, offset = .24f, motion = .46f,
            filter = .3f, drive = 0f),
        recipe(ContourVoice.SWELL, "Long Contour", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ODD_EVEN,
            edgeRatio = 1f, edgeLag = .36f, relation = ContourRelation.LAG,
            bodyLevel = .8f, edgeLevel = .28f, subLevel = 0f,
            bodyHarmonics = 1.05f, edgeRootLevel = .8f,
            edgeAttack = .5f, edgeDecay = 1.8f, edgeSustain = .75f,
            bodyScanBias = -.1f, edgeScanBias = -.15f, bodyTravel = .28f, edgeTravel = .42f,
            ampAttack = .8f, ampDecay = .8f, ampSustain = .76f, ampRelease = 1.8f,
            sustainSeconds = 2.4f, filterAttack = .5f, filterDecay = 1.6f, filterAmount = .1f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 3f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 3.6f,
        ), scan = .28f, offset = .25f, motion = .6f,
            filter = .28f, drive = 0f, hold = 1f),
    )

    fun forVoice(voice: ContourVoice): List<ContourPatch> = presets.filter { it.voice == voice }
    fun all(): List<ContourPatch> = presets
}
