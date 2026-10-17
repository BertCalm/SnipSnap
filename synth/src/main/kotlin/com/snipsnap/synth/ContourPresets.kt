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
            edgeRatio = 1f, edgeLag = .2325f, relation = ContourRelation.LAG,
            bodyLevel = .83f, edgeLevel = .27249998f, subLevel = .075f,
            bodyHarmonics = 1.3375f, edgeRootLevel = .7f,
            edgeAttack = .065f, edgeDecay = .62f, edgeSustain = .45749998f,
            bodyScanBias = -.017500002f, edgeScanBias = -.075f, bodyTravel = .19500001f, edgeTravel = .365f,
            ampAttack = .02775f, ampDecay = .625f, ampSustain = .51f, ampRelease = .7375f,
            sustainSeconds = .86249995f, filterAttack = .03125f, filterDecay = .65f, filterAmount = .24000001f,
            motionShape = ContourMotionShape.FALL, motionSeconds = 1.5f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .2725f, offset = .2f, motion = .3475f,
            filter = .32500002f, drive = .06f),
        recipe(ContourVoice.BASS, "Moving Corner", ContourConfig(
            ContourTable.ODD_EVEN, ContourTable.SOFT_SAW,
            edgeRatio = 1f, edgeLag = .27f, relation = ContourRelation.OPPOSED,
            bodyLevel = .815f, edgeLevel = .32500002f, subLevel = .060000002f,
            bodyHarmonics = 1.25f, edgeRootLevel = .725f,
            edgeAttack = .0915f, edgeDecay = 1.0125f, edgeSustain = .59499997f,
            bodyScanBias = -.03f, edgeScanBias = -.089999996f, bodyTravel = .19f, edgeTravel = .4875f,
            ampAttack = .046499997f, ampDecay = .675f, ampSustain = .5325f, ampRelease = .7425f,
            sustainSeconds = 1.0125f, filterAttack = .05375f, filterDecay = .875f, filterAmount = .22500001f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 1.875f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .3f, offset = .285f, motion = .555f,
            filter = .385f, drive = .065f),
        recipe(ContourVoice.BASS, "Dark Table", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ODD_EVEN,
            edgeRatio = 1f, edgeLag = .2325f, relation = ContourRelation.CURVE,
            bodyLevel = .845f, edgeLevel = .2425f, subLevel = .082499996f,
            bodyHarmonics = 1.275f, edgeRootLevel = .725f,
            edgeAttack = .122499995f, edgeDecay = .975f, edgeSustain = .48000002f,
            bodyScanBias = -.060000002f, edgeScanBias = -.089999996f, bodyTravel = .16499999f, edgeTravel = .35000002f,
            ampAttack = .069000006f, ampDecay = .8125f, ampSustain = .5325f, ampRelease = .8875f,
            sustainSeconds = 1.0125f, filterAttack = .061249997f, filterDecay = .79999995f, filterAmount = .155f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 2.1000001f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .21000001f, offset = .23f, motion = .2325f,
            filter = .22500001f, drive = .045f),
        recipe(ContourVoice.KEY, "Copper Scan", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.COPPER,
            edgeRatio = 1f, edgeLag = .315f, relation = ContourRelation.LAG,
            bodyLevel = .79f, edgeLevel = .26500002f, subLevel = 0f,
            bodyHarmonics = 1.275f, edgeRootLevel = .7125f,
            edgeAttack = .077f, edgeDecay = .66249996f, edgeSustain = .3125f,
            bodyScanBias = -.015f, edgeScanBias = -.17999999f, bodyTravel = .19f, edgeTravel = .43f,
            ampAttack = .031999998f, ampDecay = .9125f, ampSustain = .29000002f, ampRelease = .975f,
            sustainSeconds = .875f, filterAttack = .061249997f, filterDecay = .855f, filterAmount = .25f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 1.725f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .315f, offset = .265f, motion = .47f,
            filter = .375f, drive = .03f),
        recipe(ContourVoice.KEY, "Soft Digital", ContourConfig(
            ContourTable.SAW_HOLLOW, ContourTable.DIGITAL_BELL,
            edgeRatio = 1f, edgeLag = .2325f, relation = ContourRelation.LAG,
            bodyLevel = .79f, edgeLevel = .19500001f, subLevel = 0f,
            bodyHarmonics = 1.2875f, edgeRootLevel = .7375f,
            edgeAttack = .1535f, edgeDecay = 1.0375f, edgeSustain = .4375f,
            bodyScanBias = -.034999996f, edgeScanBias = -.20250002f, bodyTravel = .21000001f, edgeTravel = .3925f,
            ampAttack = .07575f, ampDecay = 1.0375f, ampSustain = .38f, ampRelease = 1.125f,
            sustainSeconds = .875f, filterAttack = .10625f, filterDecay = .8925f, filterAmount = .17999999f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 1.95f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .255f, offset = .21000001f, motion = .29999998f,
            filter = .325f, drive = .01f),
        recipe(ContourVoice.PLUCK, "Bright Margin", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ROUND_PULSE,
            edgeRatio = 1f, edgeLag = .18f, relation = ContourRelation.CURVE,
            bodyLevel = .79f, edgeLevel = .32500002f, subLevel = 0f,
            bodyHarmonics = 1.275f, edgeRootLevel = .75f,
            edgeAttack = .034250002f, edgeDecay = .26500002f, edgeSustain = .21f,
            bodyScanBias = -.044999998f, edgeScanBias = -.112500004f, bodyTravel = .24000001f, edgeTravel = .43f,
            ampAttack = .014249999f, ampDecay = .535f, ampSustain = .145f, ampRelease = .4775f,
            sustainSeconds = .25f, filterAttack = .012499999f, filterDecay = .43f, filterAmount = .345f,
            motionShape = ContourMotionShape.FALL, motionSeconds = .79999995f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .35500002f, offset = .255f, motion = .45749998f,
            filter = .465f, drive = .0375f),
        recipe(ContourVoice.LEAD, "Twin Outline", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ODD_EVEN,
            edgeRatio = 1f, edgeLag = .195f, relation = ContourRelation.OPPOSED,
            bodyLevel = .795f, edgeLevel = .28f, subLevel = 0f,
            bodyHarmonics = 1.2375f, edgeRootLevel = .7375f,
            edgeAttack = .13800001f, edgeDecay = 1.325f, edgeSustain = .6375f,
            bodyScanBias = -.022499999f, edgeScanBias = -.1275f, bodyTravel = .2325f, edgeTravel = .3875f,
            ampAttack = .05625f, ampDecay = .45f, ampSustain = .675f, ampRelease = .8375f,
            sustainSeconds = 1.4f, filterAttack = .07625f, filterDecay = 1.0250001f, filterAmount = .2575f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 1.7f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .36f, offset = .2525f, motion = .475f,
            filter = .4525f, drive = .055f),
        recipe(ContourVoice.HOLLOW, "Hollow Step", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.SAW_HOLLOW,
            edgeRatio = 1f, edgeLag = .285f, relation = ContourRelation.CURVE,
            bodyLevel = .8f, edgeLevel = .365f, subLevel = 0f,
            bodyHarmonics = 1.2249999f, edgeRootLevel = .7f,
            edgeAttack = .1995f, edgeDecay = 1.0375f, edgeSustain = .525f,
            bodyScanBias = -.075f, edgeScanBias = .075f, bodyTravel = .1975f, edgeTravel = .3525f,
            ampAttack = .088f, ampDecay = .75f, ampSustain = .565f, ampRelease = 1.075f,
            sustainSeconds = 1.125f, filterAttack = .10625f, filterDecay = .95000005f, filterAmount = .175f,
            motionShape = ContourMotionShape.RISE, motionSeconds = 2.1000001f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 2f,
        ), scan = .3625f, offset = .305f, motion = .42000002f,
            filter = .3625f, drive = .025f),
        recipe(ContourVoice.SWELL, "Long Contour", ContourConfig(
            ContourTable.SOFT_SAW, ContourTable.ODD_EVEN,
            edgeRatio = 1f, edgeLag = .315f, relation = ContourRelation.LAG,
            bodyLevel = .8f, edgeLevel = .29f, subLevel = 0f,
            bodyHarmonics = 1.275f, edgeRootLevel = .7f,
            edgeAttack = .4625f, edgeDecay = 1.8f, edgeSustain = .7875f,
            bodyScanBias = -.075f, edgeScanBias = -.112500004f, bodyTravel = .285f, edgeTravel = .45999998f,
            ampAttack = .7625f, ampDecay = .7625f, ampSustain = .765f, ampRelease = 1.7249999f,
            sustainSeconds = 2.325f, filterAttack = .4875f, filterDecay = 1.55f, filterAmount = .2f,
            motionShape = ContourMotionShape.CYCLE, motionSeconds = 2.85f, filterType = ContourFilterType.LOW_PASS, loopSeconds = 3.3f,
        ), scan = .2975f, offset = .3f, motion = .58750004f,
            filter = .36f, drive = .0375f, hold = 1f),
    )

    fun forVoice(voice: ContourVoice): List<ContourPatch> = presets.filter { it.voice == voice }
    fun all(): List<ContourPatch> = presets
}
