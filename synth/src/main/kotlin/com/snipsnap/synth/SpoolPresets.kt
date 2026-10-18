package com.snipsnap.synth

/**
 * Thirty-six original dry recording machines, expanded after approval of the six starting sounds.
 * The first six remain unchanged so voice defaults and the sixteen-pad starter kit stay stable.
 * HOLD recipes use the saved [SpoolHoldMode.FROZEN_SNAPSHOT] strategy; finite second readings are
 * explicitly requested by [SpoolMachine.reengage], rather than implied persistent pad memory.
 */
object SpoolPresets {
    private fun p(voice: SpoolVoice, name: String, vararg macros: Pair<String, Float>) =
        SpoolPatch(name, voice, Spool.defaults(voice) + macros.toMap())

    private fun recording(
        voice: SpoolVoice, name: String, head: Float, strain: Float, answer: Float,
        imprint: Float, splice: Float, machine: SpoolMachine, hold: Float = 0f,
    ) = SpoolPatch(name, voice, Spool.defaults(voice) + mapOf(
        "TUNE" to .5f, "HEAD" to head, "STRAIN" to strain, "RETURN" to answer,
        "IMPRINT" to imprint, "SPLICE" to splice, "HOLD" to hold,
    ), machine)

    /** Calibration changes retain the voice's family order and its original recorded sources. */
    private fun strips(
        voice: SpoolVoice, gains: List<Float>? = null, tensions: List<Float>? = null,
        drags: List<Float>? = null, contact: Float = .82f, doubling: Float = .18f,
    ) = SpoolMachine.rosterFor(voice).mapIndexed { i, strip ->
        strip.copy(gain = gains?.get(i) ?: strip.gain, tension = tensions?.get(i) ?: strip.tension,
            drag = drags?.get(i) ?: strip.drag, contact = contact, doubling = doubling)
    }

    private val presets by lazy {
        listOf(
            p(SpoolVoice.STRING, "SOFT FRAME", "TUNE" to 0.5f, "HEAD" to 0.15f, "STRAIN" to 0.10f, "RETURN" to 0.40f, "IMPRINT" to 0.15f, "SPLICE" to 0.05f, "HOLD" to 0f),
            p(SpoolVoice.CHOIR, "RETURNING CHOIR", "TUNE" to 0.5f, "HEAD" to 0.25f, "STRAIN" to 0.15f, "RETURN" to 0.45f, "IMPRINT" to 0.20f, "SPLICE" to 0.05f, "HOLD" to 0f),
            p(SpoolVoice.FLUTE, "FLUTE MEMORY", "TUNE" to 0.5f, "HEAD" to 0.20f, "STRAIN" to 0.10f, "RETURN" to 0.40f, "IMPRINT" to 0.15f, "SPLICE" to 0.10f, "HOLD" to 0f),
            p(SpoolVoice.BRASS, "WORN BRASS", "TUNE" to 0.5f, "HEAD" to 0.20f, "STRAIN" to 0.15f, "RETURN" to 0.40f, "IMPRINT" to 0.15f, "SPLICE" to 0.10f, "HOLD" to 0f),
            p(SpoolVoice.PALIMPSEST, "GHOST COPY", "TUNE" to 0.5f, "HEAD" to 0.65f, "STRAIN" to 0.40f, "RETURN" to 0.65f, "IMPRINT" to 0.70f, "SPLICE" to 0.25f, "HOLD" to 0f),
            p(SpoolVoice.CUT, "SHORT SPLICE", "TUNE" to 0.5f, "HEAD" to 0.55f, "STRAIN" to 0.35f, "RETURN" to 0.50f, "IMPRINT" to 0.45f, "SPLICE" to 0.75f, "HOLD" to 0f),

            // Strings: aligned close contact through longer, diagonal and returning bow gestures.
            recording(SpoolVoice.STRING, "DIAGONAL HEAD", .64f, .18f, .56f, .34f, .14f,
                SpoolMachine(roster = strips(SpoolVoice.STRING, gains = listOf(.84f, .11f, .05f)),
                    gateSeconds = 1.55f, headOffsets = listOf(-.24f, .34f, -.18f))),
            recording(SpoolVoice.STRING, "CLOSE BOW", .04f, .04f, .14f, .04f, 0f,
                SpoolMachine(roster = strips(SpoolVoice.STRING, gains = listOf(.98f, .015f, .005f),
                    contact = .96f, doubling = .02f), gateSeconds = .62f, returnSeconds = .72f,
                    headOffsets = listOf(0f, -.04f, .05f), spliceSchedule = emptyList())),
            recording(SpoolVoice.STRING, "LONG FRAME", .12f, .08f, .40f, .12f, .02f,
                SpoolMachine(roster = strips(SpoolVoice.STRING, contact = .75f, doubling = .35f),
                    gateSeconds = 2.25f, sourceSeconds = 3.5f, returnSeconds = 1.55f,
                    headOffsets = listOf(0f, -.08f, .12f))),
            recording(SpoolVoice.STRING, "CHAMBER VEIL", .38f, .14f, .46f, .38f, .08f,
                SpoolMachine(roster = strips(SpoolVoice.STRING, gains = listOf(.80f, .16f, .04f),
                    contact = .63f, doubling = .85f), gateSeconds = 1.8f, returnSeconds = 1.25f,
                    headOffsets = listOf(.08f, -.30f, .22f))),
            recording(SpoolVoice.STRING, "TENSION LINE", .24f, .32f, .30f, .18f, .05f,
                SpoolMachine(roster = strips(SpoolVoice.STRING, tensions = listOf(.84f, 1.22f, 1.02f),
                    drags = listOf(.18f, .05f, .22f)), gateSeconds = 1.05f, returnSeconds = .95f,
                    motorTorque = .82f)),
            recording(SpoolVoice.STRING, "RETURNING BOW", .28f, .16f, .82f, .28f, .04f,
                SpoolMachine(roster = strips(SpoolVoice.STRING, contact = .88f, doubling = .42f),
                    gateSeconds = .9f, returnSeconds = 1.65f, headOffsets = listOf(.06f, -.18f, .24f))),
            recording(SpoolVoice.STRING, "FRAME CYCLE", .30f, .20f, .62f, .36f, .12f,
                SpoolMachine(roster = strips(SpoolVoice.STRING, gains = listOf(.88f, .08f, .04f),
                    doubling = .55f), gateSeconds = 1.10f, returnSeconds = 1.10f,
                    headOffsets = listOf(0f, -.22f, .28f)), hold = 1f),

            // Choir: short near vowels, sustained ensembles, and a finite inherited afterimage.
            recording(SpoolVoice.CHOIR, "NEAR VOWEL", .06f, .06f, .18f, .06f, 0f,
                SpoolMachine(roster = strips(SpoolVoice.CHOIR, gains = listOf(.98f, .015f, .005f),
                    contact = .94f, doubling = .02f), gateSeconds = .75f, returnSeconds = .75f,
                    headOffsets = listOf(0f, -.04f, .04f), spliceSchedule = emptyList())),
            recording(SpoolVoice.CHOIR, "LONG MURMUR", .16f, .10f, .58f, .22f, .02f,
                SpoolMachine(roster = strips(SpoolVoice.CHOIR, contact = .72f, doubling = .32f),
                    gateSeconds = 2.3f, sourceSeconds = 3.6f, returnSeconds = 1.55f)),
            recording(SpoolVoice.CHOIR, "DOUBLE CHOIR", .34f, .16f, .42f, .20f, .06f,
                SpoolMachine(roster = strips(SpoolVoice.CHOIR, gains = listOf(.82f, .13f, .05f),
                    contact = .86f, doubling = 1f), gateSeconds = 1.65f, returnSeconds = 1.15f,
                    headOffsets = listOf(.04f, -.24f, .19f))),
            recording(SpoolVoice.CHOIR, "GLASS VOWEL", .52f, .12f, .36f, .44f, .16f,
                SpoolMachine(roster = strips(SpoolVoice.CHOIR, gains = listOf(.80f, .04f, .16f),
                    contact = .66f, doubling = .08f), gateSeconds = 1.2f, returnSeconds = 1.05f,
                    headOffsets = listOf(-.14f, .28f, -.32f),
                    spliceSchedule = listOf(SpoolSplice(.30f, .08f), SpoolSplice(.68f, -.10f)))),
            recording(SpoolVoice.CHOIR, "CHOIR AFTERIMAGE", .43f, .24f, .70f, .72f, .12f,
                SpoolMachine(roster = strips(SpoolVoice.CHOIR, gains = listOf(.86f, .10f, .04f)),
                    gateSeconds = .9f, returnSeconds = 1.1f, headOffsets = listOf(0f, -.26f, .20f),
                    routes = listOf(SpoolRoute(1, 0, .48f), SpoolRoute(2, 1, .36f)),
                    writeDelaySamples = 96, reengage = true)),
            recording(SpoolVoice.CHOIR, "BREATHED ANSWER", .30f, .22f, .84f, .30f, .04f,
                SpoolMachine(roster = strips(SpoolVoice.CHOIR, gains = listOf(.83f, .03f, .14f),
                    contact = .60f, doubling = .28f), gateSeconds = .62f, returnSeconds = 1.7f,
                    headOffsets = listOf(.02f, -.20f, .26f))),
            recording(SpoolVoice.CHOIR, "VOWEL WHEEL", .36f, .18f, .66f, .42f, .10f,
                SpoolMachine(roster = strips(SpoolVoice.CHOIR, contact = .76f, doubling = .65f),
                    gateSeconds = 1.25f, returnSeconds = 1.35f,
                    headOffsets = listOf(.06f, -.24f, .18f)), hold = 1f),

            // Flutes keep the fundamental and airflow clear while varying the finite breath shape.
            recording(SpoolVoice.FLUTE, "CLOSE BREATH", .04f, .04f, .16f, .04f, 0f,
                SpoolMachine(roster = strips(SpoolVoice.FLUTE, gains = listOf(.98f, .015f, .005f),
                    contact = .95f, doubling = 0f), gateSeconds = .42f, returnSeconds = .65f,
                    headOffsets = listOf(0f, -.03f, .04f), spliceSchedule = emptyList())),
            recording(SpoolVoice.FLUTE, "LONG AIR", .12f, .08f, .46f, .14f, .02f,
                SpoolMachine(roster = strips(SpoolVoice.FLUTE, contact = .72f, doubling = .25f),
                    gateSeconds = 2.2f, sourceSeconds = 3.5f, returnSeconds = 1.5f)),
            recording(SpoolVoice.FLUTE, "FLUTE DOUBLING", .34f, .16f, .36f, .24f, .08f,
                SpoolMachine(roster = strips(SpoolVoice.FLUTE, gains = listOf(.85f, .11f, .04f),
                    contact = .84f, doubling = .95f), gateSeconds = 1.45f, returnSeconds = 1.05f,
                    headOffsets = listOf(.03f, -.22f, .20f))),
            recording(SpoolVoice.FLUTE, "PAPER WIND", .50f, .28f, .52f, .44f, .26f,
                SpoolMachine(roster = strips(SpoolVoice.FLUTE, gains = listOf(.84f, .10f, .06f),
                    tensions = listOf(.91f, 1.15f, .96f), contact = .64f, doubling = .12f),
                    gateSeconds = 1.0f, returnSeconds = 1.0f, headOffsets = listOf(-.12f, .32f, -.24f),
                    spliceSchedule = listOf(SpoolSplice(.27f, .10f), SpoolSplice(.61f, -.14f)))),
            recording(SpoolVoice.FLUTE, "RETURNING AIR", .27f, .14f, .86f, .30f, .04f,
                SpoolMachine(roster = strips(SpoolVoice.FLUTE, contact = .78f, doubling = .40f),
                    gateSeconds = .78f, returnSeconds = 1.75f, headOffsets = listOf(.04f, -.18f, .24f))),

            // Brass: close punctuation, softened contact, and competing mechanical spring loads.
            recording(SpoolVoice.BRASS, "SOFT BELL", .10f, .08f, .24f, .10f, .02f,
                SpoolMachine(roster = strips(SpoolVoice.BRASS, gains = listOf(.84f, .13f, .03f),
                    contact = .52f, doubling = .45f), gateSeconds = 1.25f, returnSeconds = .90f,
                    headOffsets = listOf(0f, -.08f, .12f))),
            recording(SpoolVoice.BRASS, "BRASS PUNCTUATION", .03f, .06f, .18f, .04f, 0f,
                SpoolMachine(roster = strips(SpoolVoice.BRASS, gains = listOf(.99f, .005f, .005f),
                    contact = .98f, doubling = 0f), gateSeconds = .35f, returnSeconds = .65f,
                    headOffsets = listOf(0f, -.03f, .03f), spliceSchedule = emptyList())),
            recording(SpoolVoice.BRASS, "LONG HORN", .18f, .12f, .60f, .24f, .04f,
                SpoolMachine(roster = strips(SpoolVoice.BRASS, contact = .80f, doubling = .35f),
                    gateSeconds = 2.15f, sourceSeconds = 3.5f, returnSeconds = 1.50f)),
            recording(SpoolVoice.BRASS, "THREE SPRINGS", .46f, .43f, .62f, .34f, .16f,
                SpoolMachine(roster = strips(SpoolVoice.BRASS, gains = listOf(.82f, .12f, .06f),
                    tensions = listOf(.78f, 1.28f, .94f), drags = listOf(.20f, .05f, .27f),
                    contact = .86f, doubling = .28f), gateSeconds = 1.15f, returnSeconds = 1.2f,
                    motorTorque = .76f, headOffsets = listOf(.02f, -.28f, .32f))),
            recording(SpoolVoice.BRASS, "BRASS AFTERIMAGE", .40f, .22f, .76f, .64f, .20f,
                SpoolMachine(roster = strips(SpoolVoice.BRASS, gains = listOf(.86f, .10f, .04f)),
                    gateSeconds = .82f, returnSeconds = 1.45f, headOffsets = listOf(-.08f, .24f, -.18f),
                    routes = listOf(SpoolRoute(1, 0, .50f), SpoolRoute(2, 1, .32f)),
                    spliceSchedule = listOf(SpoolSplice(.32f, .08f), SpoolSplice(.66f, -.12f)))),

            // Experimental memories preserve their recorded donors and disclose the second encounter.
            recording(SpoolVoice.PALIMPSEST, "BORROWED ATTACK", .56f, .28f, .76f, .86f, .18f,
                SpoolMachine(roster = strips(SpoolVoice.PALIMPSEST, gains = listOf(.62f, .24f, .14f),
                    contact = .88f, doubling = .12f), gateSeconds = .72f, returnSeconds = .88f,
                    headOffsets = listOf(0f, -.28f, .22f),
                    routes = listOf(SpoolRoute(1, 0, .55f), SpoolRoute(2, 1, .32f), SpoolRoute(0, 2, .40f)),
                    writeDelaySamples = 64, reengage = true)),
            recording(SpoolVoice.PALIMPSEST, "NEIGHBOR PRINT", .82f, .48f, .84f, .94f, .32f,
                SpoolMachine(roster = strips(SpoolVoice.PALIMPSEST, gains = listOf(.24f, .60f, .16f),
                    tensions = listOf(.88f, 1.16f, 1.02f), contact = .76f, doubling = .48f),
                    gateSeconds = 1.18f, returnSeconds = 1.15f, headOffsets = listOf(-.32f, .36f, -.18f),
                    routes = listOf(SpoolRoute(0, 1, .24f), SpoolRoute(2, 1, .30f), SpoolRoute(1, 0, .52f)),
                    writeDelaySamples = 176, reengage = true)),
            recording(SpoolVoice.PALIMPSEST, "CARBON CYCLE", .70f, .36f, .74f, .82f, .28f,
                SpoolMachine(roster = strips(SpoolVoice.PALIMPSEST, gains = listOf(.42f, .42f, .16f),
                    contact = .72f, doubling = .60f), gateSeconds = 1.0f, returnSeconds = 1.2f,
                    headOffsets = listOf(-.18f, .30f, -.24f), writeDelaySamples = 128), hold = 1f),

            // Reordered windows are deterministic; finite folding has exactly one final reread.
            recording(SpoolVoice.CUT, "FOLDED RECORDING", .68f, .46f, .78f, .70f, .92f,
                SpoolMachine(roster = strips(SpoolVoice.CUT, gains = listOf(.68f, .20f, .12f),
                    contact = .84f, doubling = .16f), gateSeconds = 1.12f, returnSeconds = 1.0f,
                    headOffsets = listOf(-.18f, .28f, -.32f),
                    spliceSchedule = listOf(SpoolSplice(.18f, .18f), SpoolSplice(.39f, -.22f),
                        SpoolSplice(.62f, .15f), SpoolSplice(.84f, -.24f)), reengage = true)),
            recording(SpoolVoice.CUT, "ANGLED SPLICE", .88f, .60f, .64f, .48f, .64f,
                SpoolMachine(roster = strips(SpoolVoice.CUT, gains = listOf(.58f, .28f, .14f),
                    tensions = listOf(.82f, 1.25f, .96f), drags = listOf(.16f, .06f, .24f)),
                    gateSeconds = .68f, returnSeconds = 1.25f, motorTorque = .82f,
                    headOffsets = listOf(-.36f, .42f, -.26f),
                    spliceSchedule = listOf(SpoolSplice(.24f, .20f), SpoolSplice(.56f, -.18f), SpoolSplice(.80f, .12f)))),
            recording(SpoolVoice.CUT, "SPLICED CIRCLE", .58f, .38f, .70f, .60f, .86f,
                SpoolMachine(roster = strips(SpoolVoice.CUT, gains = listOf(.72f, .18f, .10f),
                    contact = .78f, doubling = .35f), gateSeconds = .92f, returnSeconds = 1.0f,
                    headOffsets = listOf(.08f, -.26f, .22f),
                    spliceSchedule = listOf(SpoolSplice(.20f, .12f), SpoolSplice(.42f, -.18f),
                        SpoolSplice(.65f, .16f), SpoolSplice(.86f, -.14f))), hold = 1f),
        )
    }

    fun forVoice(voice: SpoolVoice): List<SpoolPatch> = presets.filter { it.voice == voice }
    fun all(): List<SpoolPatch> = presets
}
