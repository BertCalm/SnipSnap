package com.snipsnap.synth

/** Musical starting points on the unchanged, finite model-3 SWELL instrument. */
object OspreyCuratedPresets {
    data class Entry(val id: String, val role: String, val description: String, val patch: OspreyPatch)

    private fun p(name: String, midi: Int, seconds: Float, velocity: Float,
        shore: Float, sea: Float, motion: Float, depth: Float, space: Float, hold: Float) =
        OspreyPatch(
            name, OspreyVoice.SWELL,
            linkedMapOf("SHORE" to shore, "SEA" to sea, "MOTION" to motion,
                "DEPTH" to depth, "SPACE" to space, "HOLD" to hold),
            midi = midi, velocity = velocity, seed = 731, durationSeconds = seconds, modelVersion = 3,
        )

    val entries: List<Entry> = listOf(
        Entry("low_tide", "foundations", "A deep Atlantic root with restrained movement and a close horizon.",
            p("LOW TIDE", 36, 5.5f, .9f, 0f, .1f, .18f, .78f, .02f, .25f)),
        Entry("nordic_ember", "foundations", "A low Nordic body with a little motion around the warm center.",
            p("NORDIC EMBER", 43, 6f, .9f, .25f, .18f, .25f, .68f, .04f, .35f)),
        Entry("night_moor", "foundations", "A sheltered Mediterranean foundation with gentle, sustained pressure.",
            p("NIGHT MOOR", 51, 7f, .85f, .5f, .14f, .18f, .82f, .1f, .55f)),
        Entry("low_lantern", "foundations", "A low Pacific-to-Southern blend with a small, steady sway.",
            p("LOW LANTERN", 48, 6f, .9f, .875f, .24f, .3f, .7f, .04f, .35f)),

        Entry("tide_pebble", "short", "A brief, close Atlantic gesture with little room around it.",
            p("TIDE PEBBLE", 60, 1.75f, .7f, 0f, .14f, .3f, .48f, 0f, 0f)),
        Entry("driftwood", "short", "A short Nordic phrase in a lower register, with an easy decay.",
            p("DRIFTWOOD", 55, 2.5f, .72f, .25f, .2f, .24f, .6f, .03f, 0f)),
        Entry("sea_glass", "short", "A soft upper-register Pacific gesture with a little space.",
            p("SEA GLASS", 63, 2.75f, .65f, .75f, .12f, .4f, .34f, .07f, .05f)),
        Entry("shell_answer", "short", "A compact Mediterranean response with modest movement and a close tail.",
            p("SHELL ANSWER", 58, 3.25f, .75f, .5f, .25f, .38f, .46f, .02f, .08f)),

        Entry("slow_current", "textures", "A long Atlantic-to-Nordic blend with patient movement.",
            p("SLOW CURRENT", 60, 8f, 1f, .125f, .35f, .34f, .62f, .08f, .55f)),
        Entry("crosswater", "textures", "A lower Nordic-to-Mediterranean crossing with more internal motion.",
            p("CROSSWATER", 55, 8f, 1f, .375f, .42f, .65f, .55f, .09f, .6f)),
        Entry("kelp_lantern", "textures", "A higher Pacific texture with moving water and a gently open room.",
            p("KELP LANTERN", 63, 8f, .9f, .75f, .28f, .56f, .64f, .14f, .65f)),
        Entry("warm_wake", "textures", "A Southern phrase with fuller sea energy and moderate movement.",
            p("WARM WAKE", 58, 7.5f, 1f, 1f, .48f, .5f, .5f, .1f, .5f)),

        Entry("blue_hour", "swells", "A low, calm Nordic swell with a long excitation and an open harbor.",
            p("BLUE HOUR", 48, 11f, .9f, .25f, .12f, .18f, .75f, .28f, .9f)),
        Entry("harbor_haze", "swells", "A spacious Mediterranean swell with slow movement and a full finite tail.",
            p("HARBOR HAZE", 51, 12f, .85f, .5f, .22f, .24f, .7f, .4f, 1f)),
        Entry("open_horizon", "swells", "A wide Pacific-to-Southern swell with a sustained, gently moving body.",
            p("OPEN HORIZON", 55, 12f, .9f, .875f, .35f, .3f, .6f, .45f, 1f)),
        Entry("between_lights", "swells", "A calm Nordic-to-Mediterranean swell, held open in a spacious harbor.",
            p("BETWEEN LIGHTS", 60, 11f, .85f, .375f, .18f, .2f, .72f, .35f, .92f)),
    )

    fun all(): List<OspreyPatch> = entries.map { it.patch }
}
