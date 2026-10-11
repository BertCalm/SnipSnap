package com.snipsnap.synth

/**
 * Nine dry first-listen recipes. Different table pairs, layer responses and envelopes
 * establish the six voices before the proposed 36-preset library receives sonic approval.
 * Every recipe saves its complete seven-control map and structural configuration.
 */
object ContourPresets {
    private fun p(voice: ContourVoice, name: String, vararg macros: Pair<String, Float>) =
        ContourPatch(name, voice, ContourEngine.defaults(voice) + macros.toMap())

    private val presets = listOf(
        p(ContourVoice.BASS, "Rounded Edge", "SCAN" to .25f, "OFFSET" to .20f, "MOTION" to .25f,
            "FILTER" to .40f, "DRIVE" to .35f),
        p(ContourVoice.BASS, "Moving Corner", "SCAN" to .30f, "OFFSET" to .45f, "MOTION" to .48f,
            "FILTER" to .58f, "DRIVE" to .30f).copy(config = ContourConfig.forVoice(ContourVoice.BASS).copy(
            bodyTable = ContourTable.SOFT_SAW, edgeTable = ContourTable.COPPER,
            relation = ContourRelation.OPPOSED, bodyTravel = .22f, edgeTravel = .60f,
            ampDecay = .45f, ampRelease = .42f,
        )),
        p(ContourVoice.BASS, "Dark Table", "SCAN" to .18f, "OFFSET" to .32f, "MOTION" to .18f,
            "FILTER" to .25f, "DRIVE" to .42f).copy(config = ContourConfig.forVoice(ContourVoice.BASS).copy(
            bodyTable = ContourTable.ROUND_PULSE, edgeTable = ContourTable.ODD_EVEN,
            relation = ContourRelation.CURVE, filterAmount = .38f, ampSustain = .45f,
        )),
        p(ContourVoice.KEY, "Copper Scan", "SCAN" to .36f, "OFFSET" to .40f, "MOTION" to .38f,
            "FILTER" to .60f, "DRIVE" to .24f).copy(config = ContourConfig.forVoice(ContourVoice.KEY).copy(
            bodyTable = ContourTable.SOFT_SAW, edgeTable = ContourTable.COPPER,
            edgeLag = .24f, bodyTravel = .25f, edgeTravel = .52f,
        )),
        p(ContourVoice.KEY, "Soft Digital", "SCAN" to .24f, "OFFSET" to .30f, "MOTION" to .24f,
            "FILTER" to .46f, "DRIVE" to .08f).copy(config = ContourConfig.forVoice(ContourVoice.KEY).copy(
            bodyTable = ContourTable.ROUND_PULSE, edgeTable = ContourTable.DIGITAL_BELL,
            edgeLevel = .24f, ampAttack = .018f, ampDecay = .85f, ampRelease = .90f,
            filterAmount = .42f,
        )),
        p(ContourVoice.PLUCK, "Bright Margin", "SCAN" to .40f, "OFFSET" to .42f, "MOTION" to .48f,
            "FILTER" to .72f, "DRIVE" to .22f).copy(config = ContourConfig.forVoice(ContourVoice.PLUCK).copy(
            bodyTable = ContourTable.DIGITAL_BELL, edgeTable = ContourTable.CLUSTER,
            ampDecay = .28f, ampSustain = .10f, sustainSeconds = .25f, ampRelease = .35f,
            relation = ContourRelation.CURVE,
        )),
        p(ContourVoice.LEAD, "Twin Outline", "SCAN" to .45f, "OFFSET" to .35f, "MOTION" to .40f,
            "FILTER" to .70f, "DRIVE" to .30f).copy(config = ContourConfig.forVoice(ContourVoice.LEAD).copy(
            bodyTable = ContourTable.SAW_HOLLOW, edgeTable = ContourTable.ODD_EVEN,
            relation = ContourRelation.OPPOSED, edgeLag = .12f,
        )),
        p(ContourVoice.HOLLOW, "Hollow Step", "SCAN" to .55f, "OFFSET" to .50f, "MOTION" to .30f,
            "FILTER" to .55f, "DRIVE" to .20f).copy(config = ContourConfig.forVoice(ContourVoice.HOLLOW).copy(
            bodyTable = ContourTable.SAW_HOLLOW, edgeTable = ContourTable.FORMANT,
            filterType = ContourFilterType.BAND_PASS, relation = ContourRelation.CURVE,
        )),
        p(ContourVoice.SWELL, "Long Contour", "SCAN" to .35f, "OFFSET" to .45f, "MOTION" to .55f,
            "FILTER" to .60f, "DRIVE" to .15f, "HOLD" to 1f).copy(config = ContourConfig.forVoice(ContourVoice.SWELL).copy(
            bodyTable = ContourTable.SOFT_SAW, edgeTable = ContourTable.FORMANT,
            motionShape = ContourMotionShape.CYCLE, relation = ContourRelation.LAG,
            bodyTravel = .30f, edgeTravel = .58f, motionSeconds = 2.4f, loopSeconds = 2.4f,
        )),
    )

    fun forVoice(voice: ContourVoice): List<ContourPatch> = presets.filter { it.voice == voice }
    fun all(): List<ContourPatch> = presets
}
