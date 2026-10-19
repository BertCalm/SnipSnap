package com.snipsnap.synth

/**
 * Thirty-six dry playing recipes. The six earlier starting voices stay first in their
 * families; the other recipes author source balances and continuous handoff contours,
 * rather than using random macro offsets. Every patch retains its complete phrase.
 */
object RelayPresets {
    private fun seed(voice: RelayVoice, name: String) = RelayPatch(name, voice, Relay.defaults(voice))

    private fun patch(
        voice: RelayVoice, name: String, pulse: Float, blend: Float, color: Float, length: Float,
        phrase: RelayPhrase, order: Float = 0f, held: Boolean = false,
    ) = RelayPatch(name, voice, Relay.defaults(voice) + mapOf(
        "ORDER" to order, "PULSE" to pulse, "BLEND" to blend,
        "COLOR" to color, "LENGTH" to length, "HOLD" to if (held) 1f else 0f,
    ), phrase)

    /** Indices are PLUCK, ORGAN, BELL, SOFT. All alternatives keep one shared clock. */
    private fun score(
        tempo: Float, route: List<Int>, dwells: FloatArray, overlap: Float,
        gains: FloatArray, tones: FloatArray, decays: FloatArray,
        vararg accents: Pair<Float, Float>,
    ) = RelayPhrase(
        RelayRole.entries.mapIndexed { i, role -> RelaySource(role, gains[i], tones[i], decays[i]) },
        route.mapIndexed { i, source -> RelaySlot(source, dwells[i], overlap) },
        listOf(route, route.drop(1) + route.take(1), route.reversed(), route.drop(2) + route.take(2)),
        accents.map { RelayAccent(it.first, it.second) }, tempo,
    )

    private val keys: List<RelayPatch> by lazy {
        listOf(
            seed(RelayVoice.KEY, "FIRST HANDOFF"),
            // A rounded drawbar core gives chord stabs a warm, lingering center.
            patch(RelayVoice.KEY, "VELVET TURN", .12f, .78f, .35f, .72f,
                score(100f, listOf(1, 0, 3, 2), floatArrayOf(1.5f, .7f, 1.2f, .6f), .35f,
                    floatArrayOf(.72f, 1f, .24f, .70f), floatArrayOf(.45f, .62f, .40f, .35f),
                    floatArrayOf(.25f, 1.8f, .65f, 1.1f), 0f to .65f, 2f to .25f)),
            // A picked entrance dissolves into a broad soft body, with a quiet bell afterimage.
            patch(RelayVoice.KEY, "AFTERIMAGE KEY", .14f, .82f, .53f, .82f,
                score(108f, listOf(0, 3, 1, 2), floatArrayOf(.55f, 1.25f, 1.65f, .55f), .38f,
                    floatArrayOf(.90f, .68f, .42f, 1f), floatArrayOf(.70f, .60f, .75f, .50f),
                    floatArrayOf(.22f, 1.2f, .70f, 1.2f), 0f to .75f, 2.5f to .20f)),
            // A brighter upper register retains a felt-like attack underneath the bell color.
            patch(RelayVoice.KEY, "GLASS FELT", .18f, .66f, .70f, .65f,
                score(118f, listOf(0, 2, 1, 3), floatArrayOf(.8f, 1.3f, 1.25f, .65f), .28f,
                    floatArrayOf(1f, .55f, .80f, .50f), floatArrayOf(.86f, .50f, .90f, .30f),
                    floatArrayOf(.14f, 1f, 1.4f, .70f), 0f to .70f, 1.8f to .30f)),
            // Dark color and a soft second stage suit lower chord voicings without retuning.
            patch(RelayVoice.KEY, "LOW LANTERN", .10f, .86f, .28f, .78f,
                score(92f, listOf(1, 3, 0, 2), floatArrayOf(1.5f, 1.5f, .6f, .4f), .38f,
                    floatArrayOf(.50f, 1f, .25f, .85f), floatArrayOf(.40f, .45f, .30f, .32f),
                    floatArrayOf(.30f, 1.6f, .70f, 1.3f), 0f to .55f, 2.6f to .20f)),
            // A compact muted keyboard gesture, with enough overlap to remain one note.
            patch(RelayVoice.KEY, "PAPER TRANSFER", .24f, .65f, .45f, .52f,
                score(128f, listOf(0, 1, 3, 2), floatArrayOf(1.45f, 1.3f, .8f, .45f), .26f,
                    floatArrayOf(1f, .48f, .25f, .42f), floatArrayOf(.70f, .45f, .50f, .25f),
                    floatArrayOf(.10f, .80f, .40f, .50f), 0f to .80f, 1.5f to .25f, 2.75f to .15f)),
            // An extended soft-to-drawbar opening leaves space behind a melody line.
            patch(RelayVoice.KEY, "LATE BLOOM", .08f, .92f, .48f, .94f,
                score(88f, listOf(3, 1, 2, 0), floatArrayOf(1.4f, 1.6f, .6f, .4f), .50f,
                    floatArrayOf(.42f, .90f, .46f, 1f), floatArrayOf(.55f, .75f, .70f, .48f),
                    floatArrayOf(.45f, 2.1f, 1.4f, 1.7f), 0f to .45f, 2.8f to .20f)),
            // A medium-speed held chord bed with a supported string edge.
            patch(RelayVoice.KEY, "OPEN CIRCUIT", .12f, .88f, .58f, .76f,
                score(96f, listOf(1, 0, 2, 3), floatArrayOf(1.3f, .7f, .7f, 1.3f), .45f,
                    floatArrayOf(.75f, .85f, .50f, .78f), floatArrayOf(.68f, .72f, .78f, .45f),
                    floatArrayOf(.65f, 1.8f, 1.5f, 1.4f), 0f to .45f, 2f to .25f), held = true),
        )
    }

    private val basses: List<RelayPatch> by lazy {
        listOf(
            seed(RelayVoice.BASS, "POCKET RELAY"),
            // A round, sustained fundamental moves through wood and soft body.
            patch(RelayVoice.BASS, "ROUND TRIP", .26f, .78f, .38f, .74f,
                score(110f, listOf(1, 0, 3, 2), floatArrayOf(1.4f, .7f, 1.4f, .5f), .34f,
                    floatArrayOf(.85f, 1f, .24f, .65f), floatArrayOf(.60f, .48f, .35f, .25f),
                    floatArrayOf(1.3f, 1.8f, .80f, 1.1f), 0f to .80f, 1.5f to .35f, 3f to .45f)),
            // A firmer string front adds finger definition without a short kick-like drop.
            patch(RelayVoice.BASS, "WOODEN STEP", .42f, .67f, .55f, .62f,
                score(124f, listOf(0, 1, 3, 2), floatArrayOf(1.1f, 1.5f, 1f, .4f), .28f,
                    floatArrayOf(1f, .80f, .20f, .45f), floatArrayOf(.78f, .52f, .38f, .24f),
                    floatArrayOf(1.1f, 1.4f, .70f, .85f), 0f to .85f, 1.25f to .35f, 2.75f to .45f)),
            // The organ/soft pair carries a low, even bass with little metallic detail.
            patch(RelayVoice.BASS, "SUB EXCHANGE", .12f, .90f, .24f, .84f,
                score(92f, listOf(1, 3, 0, 2), floatArrayOf(1.7f, 1.5f, .5f, .3f), .42f,
                    floatArrayOf(.38f, 1f, .16f, .90f), floatArrayOf(.38f, .40f, .30f, .26f),
                    floatArrayOf(1.5f, 2.2f, .80f, 1.6f), 0f to .65f, 2.5f to .20f)),
            // A slowly rising low color is useful under sparse arrangements.
            patch(RelayVoice.BASS, "LOW CURRENT", .16f, .94f, .42f, .92f,
                score(84f, listOf(3, 1, 0, 2), floatArrayOf(1.6f, 1.5f, .6f, .3f), .48f,
                    floatArrayOf(.65f, .90f, .22f, 1f), floatArrayOf(.56f, .58f, .42f, .42f),
                    floatArrayOf(1.6f, 2.4f, .90f, 1.8f), 0f to .45f, 2.2f to .30f)),
            // Held bass motion stays anchored in the drawbar and rounded string sources.
            patch(RelayVoice.BASS, "ANCHORED LOOP", .32f, .82f, .44f, .80f,
                score(106f, listOf(1, 0, 3, 2), floatArrayOf(1.5f, .75f, 1.25f, .5f), .40f,
                    floatArrayOf(.82f, 1f, .20f, .72f), floatArrayOf(.62f, .55f, .38f, .30f),
                    floatArrayOf(1.4f, 2f, .85f, 1.3f), 0f to .70f, 1.5f to .30f, 3f to .40f), held = true),
        )
    }

    private val plucks: List<RelayPatch> by lazy {
        listOf(
            seed(RelayVoice.PLUCK, "MUTED PASSAGE"),
            // Rounded pick and quiet drawbars support repeated melodic notes.
            patch(RelayVoice.PLUCK, "THUMBPRINT", .30f, .66f, .42f, .54f,
                score(136f, listOf(0, 1, 3, 2), floatArrayOf(1.4f, 1.2f, 1f, .4f), .24f,
                    floatArrayOf(1f, .55f, .24f, .48f), floatArrayOf(.72f, .48f, .48f, .28f),
                    floatArrayOf(.11f, .75f, .45f, .60f), 0f to .85f, 1.5f to .30f, 2.75f to .20f)),
            // A long, light metallic answer remains attached to the picked attack.
            patch(RelayVoice.PLUCK, "COPPER THREAD", .22f, .76f, .68f, .76f,
                score(116f, listOf(0, 2, 1, 3), floatArrayOf(.75f, 1.4f, 1.1f, .75f), .34f,
                    floatArrayOf(1f, .42f, .75f, .50f), floatArrayOf(.88f, .48f, .84f, .32f),
                    floatArrayOf(.18f, 1.1f, 1.3f, .85f), 0f to .75f, 2.1f to .25f)),
            // A soft swell underneath the string removes the hard edge from arpeggios.
            patch(RelayVoice.PLUCK, "SOFT PICK", .12f, .88f, .36f, .70f,
                score(120f, listOf(0, 3, 1, 2), floatArrayOf(1f, 1.5f, 1.1f, .4f), .42f,
                    floatArrayOf(.90f, .45f, .20f, 1f), floatArrayOf(.60f, .42f, .48f, .44f),
                    floatArrayOf(.20f, 1f, .70f, 1f), 0f to .65f, 2.5f to .20f)),
            // Bright upper partials return to a sustained body rather than another attack.
            patch(RelayVoice.PLUCK, "BRIGHT RETURN", .36f, .68f, .76f, .64f,
                score(142f, listOf(0, 2, 3, 1), floatArrayOf(1.2f, .8f, .8f, 1.2f), .28f,
                    floatArrayOf(1f, .62f, .62f, .42f), floatArrayOf(.96f, .66f, .90f, .35f),
                    floatArrayOf(.12f, 1.2f, .95f, .70f), 0f to .85f, 1.75f to .30f, 3f to .35f)),
            // A smooth repeating pluck bed; the source contour closes once per phrase.
            patch(RelayVoice.PLUCK, "PICKED ORBIT", .26f, .82f, .59f, .66f,
                score(122f, listOf(0, 3, 2, 1), floatArrayOf(1.25f, 1.25f, .65f, .85f), .38f,
                    floatArrayOf(1f, .55f, .50f, .72f), floatArrayOf(.82f, .58f, .76f, .42f),
                    floatArrayOf(.38f, 1.4f, 1.1f, 1.1f), 0f to .65f, 1.75f to .25f, 3f to .30f), held = true),
        )
    }

    private val organs: List<RelayPatch> by lazy {
        listOf(
            seed(RelayVoice.ORGAN, "ORGAN AFTERGLOW"),
            // A reedy, brighter drawbar voice opens gently into bell harmonics.
            patch(RelayVoice.ORGAN, "REED CROSSING", .16f, .76f, .70f, .84f,
                score(96f, listOf(1, 2, 3, 0), floatArrayOf(1.9f, .7f, 1f, .4f), .38f,
                    floatArrayOf(.35f, 1f, .62f, .50f), floatArrayOf(.62f, .98f, .78f, .35f),
                    floatArrayOf(.40f, 2.4f, 1.3f, 1.4f), 0f to .55f, 2.4f to .25f)),
            // An even, dark chord foundation with a soft lower-harmonic bloom.
            patch(RelayVoice.ORGAN, "WARM REGISTER", .08f, .94f, .32f, .96f,
                score(80f, listOf(1, 3, 0, 2), floatArrayOf(2f, 1.3f, .45f, .25f), .52f,
                    floatArrayOf(.28f, 1f, .18f, .85f), floatArrayOf(.40f, .64f, .42f, .42f),
                    floatArrayOf(.65f, 2.8f, 1.1f, 1.8f), 0f to .45f, 2.8f to .15f)),
            // A hollow soft/drawbar mixture leaves room around a lead instrument.
            patch(RelayVoice.ORGAN, "HOLLOW DRAWBARS", .10f, .90f, .48f, .86f,
                score(88f, listOf(3, 1, 2, 0), floatArrayOf(1.35f, 1.8f, .55f, .3f), .46f,
                    floatArrayOf(.22f, .72f, .38f, 1f), floatArrayOf(.42f, .80f, .64f, .60f),
                    floatArrayOf(.70f, 2.3f, 1.4f, 1.4f), 0f to .40f, 2.2f to .20f)),
            // A longer drawn-out stop change for sustained harmony.
            patch(RelayVoice.ORGAN, "SLOW STOP", .06f, .96f, .56f, .94f,
                score(76f, listOf(1, 3, 2, 0), floatArrayOf(2.4f, 1.35f, .75f, .5f), .60f,
                    floatArrayOf(.32f, 1f, .48f, .78f), floatArrayOf(.50f, .88f, .70f, .48f),
                    floatArrayOf(.85f, 3.2f, 1.8f, 2f), 0f to .40f, 3.2f to .20f)),
            // The kit's held organ keeps drawbar weight throughout its broad color cycle.
            patch(RelayVoice.ORGAN, "HELD HANDOFF", .10f, .92f, .52f, .88f,
                score(84f, listOf(1, 3, 2, 0), floatArrayOf(1.8f, 1.2f, .6f, .4f), .52f,
                    floatArrayOf(.38f, 1f, .46f, .76f), floatArrayOf(.55f, .86f, .72f, .48f),
                    floatArrayOf(.80f, 2.8f, 1.7f, 1.8f), 0f to .45f, 2.5f to .20f), held = true),
        )
    }

    private val chimes: List<RelayPatch> by lazy {
        listOf(
            seed(RelayVoice.CHIME, "BELL EXIT"),
            // A darker metal tone carries its ring into a soft, supported body.
            patch(RelayVoice.CHIME, "DUSK METAL", .10f, .86f, .42f, .88f,
                score(90f, listOf(2, 3, 1, 0), floatArrayOf(1.8f, 1.15f, .75f, .3f), .42f,
                    floatArrayOf(.30f, .55f, 1f, .85f), floatArrayOf(.58f, .52f, .72f, .44f),
                    floatArrayOf(.40f, 1.6f, 2.8f, 1.6f), 0f to .45f, 2.6f to .20f)),
            // A clear struck edge stays above a stronger drawbar fundamental.
            patch(RelayVoice.CHIME, "GLASS RELAY", .22f, .72f, .78f, .72f,
                score(112f, listOf(2, 1, 3, 0), floatArrayOf(1.4f, 1.4f, .8f, .4f), .32f,
                    floatArrayOf(.42f, .78f, 1f, .48f), floatArrayOf(.75f, .70f, 1f, .34f),
                    floatArrayOf(.24f, 1.4f, 1.8f, 1.1f), 0f to .55f, 2f to .25f)),
            // A held bell halo shares the cycle with soft and organ body instead of retriggers.
            patch(RelayVoice.CHIME, "LAST LIGHT", .08f, .94f, .62f, .90f,
                score(78f, listOf(2, 3, 1, 0), floatArrayOf(1.65f, 1.4f, .7f, .25f), .56f,
                    floatArrayOf(.24f, .65f, 1f, .90f), floatArrayOf(.55f, .64f, .88f, .48f),
                    floatArrayOf(.80f, 2.4f, 2.6f, 2f), 0f to .35f, 2.7f to .15f), held = true),
        )
    }

    private val flows: List<RelayPatch> by lazy {
        listOf(
            seed(RelayVoice.FLOW, "SOFT EXCHANGE"),
            // A soft-to-warm swell works as a short ambient chord rather than a stepped phrase.
            patch(RelayVoice.FLOW, "WARM DRIFT", .06f, .98f, .32f, .96f,
                score(72f, listOf(3, 1, 0, 2), floatArrayOf(1.6f, 1.5f, .6f, .3f), .64f,
                    floatArrayOf(.30f, .80f, .22f, 1f), floatArrayOf(.42f, .60f, .50f, .42f),
                    floatArrayOf(.85f, 2.6f, 1.5f, 2.2f), 0f to .35f, 2.8f to .15f)),
            // A brighter distant bell appears inside a slow soft contour.
            patch(RelayVoice.FLOW, "DISTANT WINDOWS", .08f, .94f, .66f, .92f,
                score(76f, listOf(3, 2, 1, 0), floatArrayOf(1.55f, 1.15f, .9f, .4f), .58f,
                    floatArrayOf(.24f, .50f, .82f, 1f), floatArrayOf(.48f, .62f, .90f, .56f),
                    floatArrayOf(.90f, 2.2f, 2.4f, 2f), 0f to .30f, 2.4f to .20f)),
            // Six deliberately slow beats give the spectral change time to breathe.
            patch(RelayVoice.FLOW, "SLOW PRISM", .04f, 1f, .56f, .96f,
                score(72f, listOf(3, 1, 2, 0), floatArrayOf(2.1f, 1.8f, 1.3f, .8f), .80f,
                    floatArrayOf(.38f, .72f, .65f, 1f), floatArrayOf(.56f, .72f, .86f, .58f),
                    floatArrayOf(1.1f, 3f, 2.6f, 2.8f), 0f to .25f, 4f to .15f)),
            // A dark, hollow moving bed with a faint string texture in its later contour.
            patch(RelayVoice.FLOW, "SHADOW PASS", .10f, .96f, .26f, .88f,
                score(80f, listOf(3, 0, 1, 2), floatArrayOf(1.7f, .65f, 1.35f, .3f), .60f,
                    floatArrayOf(.58f, .62f, .20f, 1f), floatArrayOf(.48f, .50f, .40f, .56f),
                    floatArrayOf(.95f, 2.2f, 1.6f, 2f), 0f to .35f, 2.6f to .15f)),
            // The kit's long held texture visits all four colors with broad continuous overlap.
            patch(RelayVoice.FLOW, "FOUR CORNERS", .06f, 1f, .52f, .94f,
                score(72f, listOf(3, 1, 2, 0), floatArrayOf(1.8f, 1.5f, 1.4f, 1.3f), .78f,
                    floatArrayOf(.58f, .78f, .65f, 1f), floatArrayOf(.62f, .74f, .84f, .56f),
                    floatArrayOf(1.2f, 2.8f, 2.5f, 2.6f), 0f to .25f, 3.5f to .15f), held = true),
        )
    }

    fun forVoice(voice: RelayVoice): List<RelayPatch> = when (voice) {
        RelayVoice.KEY -> keys
        RelayVoice.BASS -> basses
        RelayVoice.PLUCK -> plucks
        RelayVoice.ORGAN -> organs
        RelayVoice.CHIME -> chimes
        RelayVoice.FLOW -> flows
    }.toList()

    fun all(): List<RelayPatch> = RelayVoice.entries.flatMap(::forVoice)
}
