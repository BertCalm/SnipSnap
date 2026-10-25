package com.snipsnap.synth

/**
 * Nine dry first-listen recipes. Distinct body/edge palettes and independent
 * layer, articulation and filter controls expose a broader rounded sound range.
 * Complete saved configurations keep these recipes independent of voice defaults.
 */
object ContourPresets {
    // Promoted shelf rows paste into this helper, then copy their complete saved configuration.
    private fun p(voice: ContourVoice, name: String, vararg macros: Pair<String, Float>) =
        ContourPatch(name, voice, ContourEngine.defaults(voice) + macros.toMap())

    private fun recipe(voice: ContourVoice, name: String, config: ContourConfig,
        scan: Float, offset: Float, motion: Float, filter: Float, drive: Float,
        body: Float, edge: Float, attack: Float, decay: Float, rate: Float, reso: Float, env: Float,
        hold: Float = 0f, edgeResponse: ContourEdgeResponse = ContourEdgeResponse.CONTRAST) = ContourPatch(name, voice, ContourEngine.defaults(voice) + mapOf(
            "TUNE" to .5f, "SCAN" to scan, "OFFSET" to offset, "MOTION" to motion,
            "FILTER" to filter, "DRIVE" to drive, "HOLD" to hold, "BODY" to body,
            "EDGE" to edge, "ATTACK" to attack, "DECAY" to decay, "RATE" to rate,
            "RESO" to reso, "ENV" to env), config.copy(edgeResponse = edgeResponse))

    private val presets = listOf(
        recipe(ContourVoice.BASS, "Rounded Edge", ContourConfig(
            ContourTable.ROUND_PULSE, ContourTable.ODD_EVEN,
            edgeRatio = 1f, edgeLag = .2f, relation = ContourRelation.LAG,
            bodyLevel = .8f, edgeLevel = .3f, subLevel = .04f,
            bodyHarmonics = 1.35f, edgeRootLevel = .28f,
            edgeAttack = .045f, edgeDecay = .55f, edgeSustain = .45f,
            bodyScanBias = 0f, edgeScanBias = -.05f, bodyTravel = .25f, edgeTravel = .5f,
            ampAttack = .025f, ampDecay = .55f, ampSustain = .5f, ampRelease = .65f,
            sustainSeconds = .8f, filterAttack = .025f, filterDecay = .6f, filterAmount = .3f,
            motionShape = ContourMotionShape.FALL, motionSeconds = 1.3f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .5f, offset = .3f, motion = .3f, filter = .38f, drive = .01f,
            body = .8f, edge = .62f, attack = .48f, decay = .45f, rate = .5f, reso = .52f, env = .7f),
        recipe(ContourVoice.BASS, "Moving Corner", ContourConfig(
            ContourTable.ODD_EVEN, ContourTable.FORMANT,
            edgeRatio = 1f, edgeLag = .3f, relation = ContourRelation.OPPOSED,
            bodyLevel = .8f, edgeLevel = .36f, subLevel = .045f,
            bodyHarmonics = 1.35f, edgeRootLevel = .15f,
            edgeAttack = .08f, edgeDecay = 1.1f, edgeSustain = .7f,
            bodyScanBias = -.02f, edgeScanBias = -.12f, bodyTravel = .32f, edgeTravel = .65f,
            ampAttack = .045f, ampDecay = .6f, ampSustain = .58f, ampRelease = .7f,
            sustainSeconds = 1.1f, filterAttack = .055f, filterDecay = .85f, filterAmount = .28f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 1.7f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2.4f,
        ), scan = .42f, offset = .6f, motion = .7f, filter = .46f, drive = .01f,
            body = .78f, edge = .65f, attack = .5f, decay = .6f, rate = .38f, reso = .58f, env = .6f),
        recipe(ContourVoice.BASS, "Dark Table", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.SAW_HOLLOW,
            edgeRatio = 2f, edgeLag = .24f, relation = ContourRelation.CURVE,
            bodyLevel = .83f, edgeLevel = .25f, subLevel = .04f,
            bodyHarmonics = 1.1f, edgeRootLevel = .6f,
            edgeAttack = .1f, edgeDecay = .9f, edgeSustain = .6f,
            bodyScanBias = -.08f, edgeScanBias = -.12f, bodyTravel = .22f, edgeTravel = .4f,
            ampAttack = .06f, ampDecay = .7f, ampSustain = .55f, ampRelease = .85f,
            sustainSeconds = 1.3f, filterAttack = .06f, filterDecay = .8f, filterAmount = .18f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 2.1f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .2f, offset = .3f, motion = .3f, filter = .08f, drive = 0f,
            body = .82f, edge = .4f, attack = .5f, decay = .55f, rate = .45f, reso = .5f, env = .55f),
        recipe(ContourVoice.KEY, "Copper Scan", ContourConfig(
            ContourTable.COPPER, ContourTable.SOFT_SAW,
            edgeRatio = 1f, edgeLag = .32f, relation = ContourRelation.LAG,
            bodyLevel = .78f, edgeLevel = .33f, subLevel = 0f,
            bodyHarmonics = .9f, edgeRootLevel = .3f,
            edgeAttack = .075f, edgeDecay = .75f, edgeSustain = .45f,
            bodyScanBias = -.06f, edgeScanBias = -.15f, bodyTravel = .28f, edgeTravel = .55f,
            ampAttack = .04f, ampDecay = .85f, ampSustain = .32f, ampRelease = .9f,
            sustainSeconds = .9f, filterAttack = .06f, filterDecay = .85f, filterAmount = .28f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 1.7f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2.4f,
        ), scan = .28f, offset = .4f, motion = .5f, filter = .33f, drive = .01f,
            body = .72f, edge = .65f, attack = .62f, decay = .5f, rate = .44f, reso = .5f, env = .68f),
        recipe(ContourVoice.KEY, "Soft Digital", ContourConfig(
            ContourTable.SAW_HOLLOW, ContourTable.DIGITAL_BELL,
            edgeRatio = 1f, edgeLag = .18f, relation = ContourRelation.LAG,
            bodyLevel = .76f, edgeLevel = .3f, subLevel = 0f,
            bodyHarmonics = 1.1f, edgeRootLevel = .1f,
            edgeAttack = .03f, edgeDecay = .4f, edgeSustain = .16f,
            bodyScanBias = -.06f, edgeScanBias = -.22f, bodyTravel = .23f, edgeTravel = .4f,
            ampAttack = .024f, ampDecay = .9f, ampSustain = .3f, ampRelease = .9f,
            sustainSeconds = .7f, filterAttack = .035f, filterDecay = .65f, filterAmount = .3f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 1.7f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .3f, offset = .35f, motion = .35f, filter = .4f, drive = 0f,
            body = .76f, edge = .72f, attack = .42f, decay = .58f, rate = .45f, reso = .5f, env = .6f,
            edgeResponse = ContourEdgeResponse.FOREGROUND),
        recipe(ContourVoice.PLUCK, "Bright Margin", ContourConfig(
            ContourTable.ROUND_PULSE, ContourTable.COPPER,
            edgeRatio = 1f, edgeLag = .12f, relation = ContourRelation.CURVE,
            bodyLevel = .76f, edgeLevel = .35f, subLevel = 0f,
            bodyHarmonics = 1.15f, edgeRootLevel = .1f,
            edgeAttack = .004f, edgeDecay = .15f, edgeSustain = .05f,
            bodyScanBias = -.02f, edgeScanBias = -.2f, bodyTravel = .28f, edgeTravel = .5f,
            ampAttack = .007f, ampDecay = .36f, ampSustain = .08f, ampRelease = .4f,
            sustainSeconds = .22f, filterAttack = .008f, filterDecay = .22f, filterAmount = .5f,
            motionShape = ContourMotionShape.FALL, motionSeconds = .65f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 1.6f,
        ), scan = .4f, offset = .35f, motion = .5f, filter = .28f, drive = .01f,
            body = .84f, edge = .62f, attack = .45f, decay = .32f, rate = .55f, reso = .5f, env = .52f,
            edgeResponse = ContourEdgeResponse.FOREGROUND),
        recipe(ContourVoice.LEAD, "Twin Outline", ContourConfig(
            ContourTable.COPPER, ContourTable.ODD_EVEN,
            edgeRatio = 2f, edgeLag = .22f, relation = ContourRelation.OPPOSED,
            bodyLevel = .78f, edgeLevel = .32f, subLevel = 0f,
            bodyHarmonics = .95f, edgeRootLevel = .35f,
            edgeAttack = .08f, edgeDecay = 1.1f, edgeSustain = .7f,
            bodyScanBias = -.02f, edgeScanBias = -.1f, bodyTravel = .35f, edgeTravel = .6f,
            ampAttack = .04f, ampDecay = .35f, ampSustain = .72f, ampRelease = .85f,
            sustainSeconds = 1.5f, filterAttack = .07f, filterDecay = .9f, filterAmount = .28f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 1.4f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2.8f,
        ), scan = .38f, offset = .5f, motion = .6f, filter = .38f, drive = .02f,
            body = .78f, edge = .64f, attack = .5f, decay = .65f, rate = .42f, reso = .55f, env = .58f),
        recipe(ContourVoice.HOLLOW, "Hollow Step", ContourConfig(
            ContourTable.ODD_EVEN, ContourTable.FORMANT,
            edgeRatio = 1f, edgeLag = .28f, relation = ContourRelation.CURVE,
            bodyLevel = .8f, edgeLevel = .4f, subLevel = 0f,
            bodyHarmonics = 1.2f, edgeRootLevel = .15f,
            edgeAttack = .14f, edgeDecay = 1.1f, edgeSustain = .6f,
            bodyScanBias = -.08f, edgeScanBias = -.03f, bodyTravel = .3f, edgeTravel = .5f,
            ampAttack = .075f, ampDecay = .65f, ampSustain = .55f, ampRelease = .95f,
            sustainSeconds = 1.1f, filterAttack = .09f, filterDecay = .9f, filterAmount = .25f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 1.8f, filterType = ContourFilterType.BAND_PASS, loopSeconds = 2.8f,
        ), scan = .4f, offset = .5f, motion = .5f, filter = .35f, drive = .01f,
            body = .75f, edge = .58f, attack = .58f, decay = .55f, rate = .44f, reso = .6f, env = .7f),
        recipe(ContourVoice.SWELL, "Long Contour", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.FORMANT,
            edgeRatio = 2f, edgeLag = .32f, relation = ContourRelation.LAG,
            bodyLevel = .8f, edgeLevel = .4f, subLevel = 0f,
            bodyHarmonics = 1.3f, edgeRootLevel = .2f,
            edgeAttack = .4f, edgeDecay = 1.5f, edgeSustain = .85f,
            bodyScanBias = -.05f, edgeScanBias = -.12f, bodyTravel = .36f, edgeTravel = .65f,
            ampAttack = .6f, ampDecay = .7f, ampSustain = .8f, ampRelease = 1.6f,
            sustainSeconds = 2.1f, filterAttack = .4f, filterDecay = 1.4f, filterAmount = .25f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 2.8f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 3.6f,
        ), scan = .35f, offset = .5f, motion = .65f, filter = .38f, drive = .015f,
            body = .8f, edge = .6f, attack = .5f, decay = .5f, rate = .46f, reso = .6f, env = .5f, hold = 1f),
    )

    fun forVoice(voice: ContourVoice): List<ContourPatch> = presets.filter { it.voice == voice }
    fun all(): List<ContourPatch> = presets
}
