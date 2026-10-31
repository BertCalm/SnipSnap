package com.snipsnap.synth

/** Alternate listening recipes, kept separate from the named factory bank.
 * The selected voice configurations are adopted by [Alloy.defaultConfigFor]; each
 * patch still stores its complete model-1 recipe. Numerical separation is not listening approval. */
object AlloyContrastPresets {
    private fun mode(r: List<Float>, l: List<Float>, g: List<Float>) = AlloyConfig.Projection(r, l, g)
    private fun route(from: Int, to: Int, depth: Float) = AlloyConfig.Route(from, to, depth)
    private fun recipe(
        ratios: List<Float>, gains: List<Float>, decays: List<Float>, sustains: List<Float>,
        routes: List<AlloyConfig.Route>, wood: AlloyConfig.Projection, tine: AlloyConfig.Projection,
        breath: AlloyConfig.Projection, envelope: AlloyConfig.Envelope, held: AlloyConfig.Held,
        fuse: Float, cutoff: Float,
    ) = AlloyConfig(ratios.indices.map { AlloyConfig.Operator(ratios[it], gains[it], decays[it], sustains[it]) },
        routes, wood, tine, breath, envelope, held, fuse, cutoff)

    private fun patch(voice: AlloyVoice, name: String, config: AlloyConfig,
        material: Float, fuse: Float, edge: Float, body: Float, length: Float) =
        AlloyPatch(name, voice, Alloy.defaults(voice) + mapOf("MATERIAL" to material, "FUSE" to fuse,
            "EDGE" to edge, "BODY" to body, "LENGTH" to length, "HOLD" to 0f), config = config)

    private val bass = listOf(
        patch(AlloyVoice.BASS, "Wood Anchor", recipe(
            listOf(1f, 2f, 3f, 4f), listOf(.60f, .30f, .08f, 0f),
            listOf(1f, .13f, .07f, .05f), listOf(1f, .65f, .12f, .06f),
            listOf(route(1, 0, .28f), route(2, 1, .18f), route(3, 0, .10f)),
            mode(listOf(1f, 2.71f, 5.63f), listOf(.055f, .026f, .012f), listOf(.50f, .34f, .16f)),
            mode(listOf(1f, 2f, 4.21f), listOf(.20f, .10f, .05f), listOf(.60f, .28f, .12f)),
            mode(listOf(1f, 2f, 5f), listOf(.13f, .08f, .04f), listOf(.60f, .28f, .12f)),
            AlloyConfig.Envelope(.0015f, 1.0f, .18f, .10f), AlloyConfig.Held(.40f, .16f, .18f), 2.0f, 7f),
            .06f, .48f, .45f, .67f, .08f),
        patch(AlloyVoice.BASS, "Rubber Reed", recipe(
            listOf(1f, 3f, 2f, 5f), listOf(.25f, .60f, .05f, .10f),
            listOf(1f, .50f, .20f, .30f), listOf(1f, .90f, .72f, .70f),
            listOf(route(1, 0, .90f), route(2, 0, .20f), route(3, 1, .35f)),
            mode(listOf(1f, 3.15f, 6.30f), listOf(.13f, .07f, .04f), listOf(.45f, .38f, .17f)),
            mode(listOf(1f, 3f, 5.14f), listOf(.36f, .20f, .11f), listOf(.40f, .38f, .22f)),
            mode(listOf(1f, 3f, 5f, 7f), listOf(.45f, .32f, .20f, .10f), listOf(.26f, .34f, .25f, .15f)),
            AlloyConfig.Envelope(.009f, 1.35f, .20f, .25f), AlloyConfig.Held(.55f, .22f, .65f), 2.2f, 12f),
            .90f, .63f, .60f, .44f, .28f),
    )

    private val key = listOf(
        patch(AlloyVoice.KEY, "Tine Spark", recipe(
            listOf(1f, 3.98f, 2f, 7.11f), listOf(.20f, .55f, .15f, .20f),
            listOf(1f, 1.3f, .50f, .28f), listOf(1f, .85f, .55f, .60f),
            listOf(route(2, 0, .22f), route(1, 0, .33f), route(3, 1, .25f)),
            // Keep wood excitation alive long enough for FUSE to color the body.
            // MATERIAL .5 uses only tines, so its finite reference is unchanged.
            mode(listOf(1f, 3.31f, 6.81f), listOf(.11f, .05f, .025f), listOf(.45f, .35f, .20f)),
            mode(listOf(1f, 2.76f, 5.43f, 8.95f), listOf(.68f, .48f, .30f, .13f), listOf(.26f, .32f, .27f, .15f)),
            mode(listOf(1f, 3f, 6f), listOf(.18f, .10f, .05f), listOf(.30f, .45f, .25f)),
            AlloyConfig.Envelope(.0015f, 2.2f, .30f, .18f), AlloyConfig.Held(.67f, .21f, .10f), 2.0f, 22f),
            .50f, .58f, .68f, .25f, .58f),
        patch(AlloyVoice.KEY, "Felt Hammer", recipe(
            listOf(1f, 2f, 3f, 5f), listOf(.30f, .57f, .18f, .05f),
            listOf(1f, .65f, .30f, .08f), listOf(1f, .65f, .30f, .05f),
            listOf(route(1, 0, .18f), route(2, 1, .28f)),
            mode(listOf(1f, 2.02f, 3.87f), listOf(.10f, .065f, .025f), listOf(.43f, .41f, .16f)),
            mode(listOf(1f, 2f, 4.05f), listOf(.32f, .16f, .06f), listOf(.55f, .32f, .13f)),
            mode(listOf(1f, 2f, 4f), listOf(.13f, .08f, .04f), listOf(.50f, .36f, .14f)),
            AlloyConfig.Envelope(.009f, 1.65f, .30f, .14f), AlloyConfig.Held(.82f, .12f, .12f), 1.2f, 11f),
            .05f, .30f, .42f, .46f, .36f),
    )

    private val pluck = listOf(
        patch(AlloyVoice.PLUCK, "Dry Pick", recipe(
            listOf(1f, 3f, 5f, 7f), listOf(.18f, .58f, .26f, .06f),
            listOf(.14f, .055f, .040f, .025f), listOf(.65f, .70f, .45f, .08f),
            listOf(route(1, 0, .48f), route(2, 0, .22f), route(3, 2, .45f)),
            mode(listOf(1f, 3.32f, 6.81f), listOf(.095f, .055f, .025f), listOf(.42f, .45f, .25f)),
            mode(listOf(1f, 2.14f, 5.45f), listOf(.12f, .065f, .025f), listOf(.34f, .40f, .26f)),
            // Breath needs a longer modal projection to reach the PM body rather
            // than ending as a detached opening. The neutral tine path is unchanged.
            mode(listOf(1f, 3f, 7f), listOf(.16f, .10f, .05f), listOf(.35f, .40f, .25f)),
            // Default and neutral LENGTH still meet the one-second minimum;
            // higher LENGTH values now extend the phrase beyond that minimum.
            AlloyConfig.Envelope(.0005f, 1.0f, .65f, .05f), AlloyConfig.Held(.36f, .24f, .38f), 3.0f, 20f),
            .04f, .62f, .66f, .28f, .02f),
        patch(AlloyVoice.PLUCK, "Wire Chime", recipe(
            listOf(1f, 5.02f, 2f, 8.23f), listOf(.17f, .58f, .12f, .23f),
            listOf(.50f, .95f, .12f, .35f), listOf(.75f, .82f, .18f, .50f),
            listOf(route(2, 0, .24f), route(3, 1, .18f), route(1, 0, .12f)),
            mode(listOf(1f, 4.23f, 8.50f), listOf(.045f, .025f, .012f), listOf(.30f, .40f, .30f)),
            mode(listOf(1f, 3.17f, 6.81f, 10.2f), listOf(.55f, .40f, .24f, .10f), listOf(.20f, .32f, .32f, .16f)),
            mode(listOf(1f, 5f, 8f), listOf(.13f, .07f, .03f), listOf(.25f, .45f, .30f)),
            AlloyConfig.Envelope(.0008f, 1.45f, .55f, .08f), AlloyConfig.Held(.74f, .21f, .08f), 2.2f, 24f),
            .51f, .43f, .74f, .20f, .38f),
    )

    private val brass = listOf(
        patch(AlloyVoice.BRASS, "Open Reed", recipe(
            listOf(1f, 2f, 3f, 4f), listOf(.15f, .28f, .32f, .35f),
            listOf(1f, .70f, .60f, .45f), listOf(1f, .92f, .86f, .78f),
            listOf(route(3, 2, .65f), route(2, 1, .70f), route(1, 0, 1.1f)),
            mode(listOf(1f, 2.95f, 5.91f), listOf(.14f, .08f, .035f), listOf(.28f, .40f, .32f)),
            mode(listOf(1f, 2f, 4f, 6f), listOf(.42f, .34f, .22f, .12f), listOf(.20f, .30f, .30f, .20f)),
            mode(listOf(1f, 2f, 3f, 5f, 7f), listOf(.80f, .65f, .48f, .32f, .18f), listOf(.18f, .23f, .24f, .22f, .13f)),
            AlloyConfig.Envelope(.055f, 2.7f, .22f, .90f), AlloyConfig.Held(.90f, .24f, .80f), 2.0f, 20f),
            .96f, .58f, .67f, .26f, .55f),
        patch(AlloyVoice.BRASS, "Muted Copper", recipe(
            listOf(1f, 3f, 5f, 2f), listOf(.18f, .65f, .20f, .06f),
            listOf(1f, .90f, .50f, .25f), listOf(1f, .90f, .70f, .35f),
            listOf(route(2, 0, .42f), route(3, 1, .38f), route(1, 0, .45f)),
            mode(listOf(1f, 3.05f, 5.15f), listOf(.18f, .12f, .07f), listOf(.30f, .45f, .25f)),
            mode(listOf(1f, 3f, 5.1f), listOf(.48f, .35f, .18f), listOf(.25f, .45f, .30f)),
            mode(listOf(1f, 3f, 5f), listOf(.62f, .48f, .26f), listOf(.25f, .46f, .29f)),
            AlloyConfig.Envelope(.024f, 2.1f, .20f, .78f), AlloyConfig.Held(.68f, .20f, .42f), 1.7f, 14f),
            .86f, .50f, .50f, .38f, .42f),
    )

    private val string = listOf(
        patch(AlloyVoice.STRING, "Rosin Bow", recipe(
            listOf(1f, 2f, 3f, 6f), listOf(.16f, .60f, .20f, .14f),
            listOf(1f, 1.2f, .85f, .70f), listOf(1f, .90f, .82f, .72f),
            listOf(route(1, 0, .40f), route(2, 0, .35f), route(3, 2, .30f)),
            mode(listOf(1f, 2f, 3.02f, 5.98f), listOf(.70f, .60f, .45f, .28f), listOf(.18f, .34f, .30f, .18f)),
            mode(listOf(1f, 2f, 3f, 6f), listOf(.95f, .78f, .58f, .40f), listOf(.15f, .35f, .30f, .20f)),
            mode(listOf(1f, 2f, 4f, 6f), listOf(.85f, .72f, .48f, .30f), listOf(.18f, .34f, .30f, .18f)),
            AlloyConfig.Envelope(.14f, 3.0f, .48f, .88f), AlloyConfig.Held(1.35f, .22f, .70f), 1.6f, 17f),
            .16f, .54f, .56f, .34f, .70f),
        patch(AlloyVoice.STRING, "Glass Wire", recipe(
            listOf(1f, 4.06f, 2.01f, 6.12f), listOf(.12f, .58f, .18f, .22f),
            listOf(1f, 1.8f, 1.3f, 1.0f), listOf(1f, .90f, .72f, .75f),
            listOf(route(3, 2, .16f), route(1, 0, .20f), route(2, 0, .12f)),
            mode(listOf(1f, 2.81f, 5.67f), listOf(.50f, .32f, .18f), listOf(.25f, .40f, .35f)),
            mode(listOf(1f, 2.01f, 4.06f, 6.12f), listOf(1.2f, .95f, .75f, .48f), listOf(.12f, .20f, .43f, .25f)),
            mode(listOf(1f, 4f, 6f), listOf(.72f, .58f, .36f), listOf(.20f, .48f, .32f)),
            AlloyConfig.Envelope(.23f, 3.5f, .65f, .90f), AlloyConfig.Held(1.55f, .22f, .08f), 2.0f, 22f),
            .52f, .42f, .60f, .24f, .80f),
    )

    private val soft = listOf(
        patch(AlloyVoice.SOFT, "Felt Bloom", recipe(
            listOf(1f, 3f, 5f, 2f), listOf(.12f, .76f, .15f, .05f),
            listOf(1f, 1.7f, 1.1f, .55f), listOf(1f, .95f, .70f, .50f),
            listOf(route(3, 1, .12f), route(2, 0, .14f), route(1, 0, .18f)),
            mode(listOf(1f, 3f, 5.02f), listOf(.80f, .62f, .34f), listOf(.30f, .48f, .22f)),
            mode(listOf(1f, 3f, 5f), listOf(1.0f, .78f, .48f), listOf(.25f, .50f, .25f)),
            mode(listOf(1f, 3f, 5f), listOf(.70f, .55f, .28f), listOf(.30f, .48f, .22f)),
            AlloyConfig.Envelope(.17f, 3.8f, .75f, .55f), AlloyConfig.Held(1.6f, .20f, .05f), 2.4f, 11f),
            .10f, .28f, .35f, .42f, .78f),
        patch(AlloyVoice.SOFT, "Air Veil", recipe(
            listOf(1f, 2f, 4f, 7f), listOf(.10f, .30f, .43f, .27f),
            listOf(1f, 1.4f, 1.1f, .80f), listOf(1f, .85f, .85f, .75f),
            listOf(route(3, 2, .30f), route(2, 0, .18f), route(1, 0, .16f)),
            mode(listOf(1f, 2.97f, 6.20f), listOf(.60f, .42f, .22f), listOf(.25f, .43f, .32f)),
            mode(listOf(1f, 2f, 4f, 7f), listOf(.90f, .78f, .58f, .35f), listOf(.16f, .27f, .35f, .22f)),
            mode(listOf(1f, 2.07f, 4.13f, 7.23f), listOf(1.4f, 1.2f, .90f, .55f), listOf(.16f, .25f, .35f, .24f)),
            AlloyConfig.Envelope(.13f, 3.6f, .85f, .70f), AlloyConfig.Held(1.4f, .22f, .95f), 1.8f, 19f),
            .99f, .67f, .48f, .30f, .82f),
    )

    fun forVoice(voice: AlloyVoice): List<AlloyPatch> = when (voice) {
        AlloyVoice.BASS -> bass
        AlloyVoice.KEY -> key
        AlloyVoice.PLUCK -> pluck
        AlloyVoice.BRASS -> brass
        AlloyVoice.STRING -> string
        AlloyVoice.SOFT -> soft
    }
    fun all(): List<AlloyPatch> = AlloyVoice.entries.flatMap(::forVoice)
    fun configFor(voice: AlloyVoice): AlloyConfig = forVoice(voice).first().config
    fun held(voice: AlloyVoice): AlloyPatch = forVoice(voice).first().let {
        it.copy(name = "${it.name} Hold", macros = it.macros + ("HOLD" to 1f))
    }
}
