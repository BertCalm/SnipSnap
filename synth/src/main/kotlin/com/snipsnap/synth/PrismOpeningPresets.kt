package com.snipsnap.synth

import kotlin.math.pow

/**
 * Four accepted finite body openings, frozen from the round-three saved recipes at a1c23e7.
 * Endpoint data belongs to these patches: donor engines are never rendered or analyzed at runtime.
 * The original carrier template also owns deterministic phases, so its complete data stays intact.
 */
object PrismOpeningPresets {
    // Keep this independent of evolving voice defaults: these exact values seeded the accepted notes.
    private val carrier = PrismTemplate(
        spectralId = PrismSpectrum.ROUNDED, envelopeId = PrismContour.HELD,
        ratioOverrides = List(Prism.PARTIALS) { it + 1f },
        amplitudeOverrides = List(Prism.PARTIALS) { 1f / (it + 1f) },
        groupProfiles = List(Prism.GROUPS) { PrismGroupProfile(1f, 1f, 0f, 1, 1f) },
        attackSeconds = .04f, decaySeconds = 2.6f, loopSeconds = 2f,
    )

    /** Exact accepted dark-body rule. Double arithmetic and the final Float conversion are intentional. */
    internal fun bodyAmplitudes(destination: List<Float>): List<Float> {
        require(destination.size == Prism.PARTIALS && destination.all { it.isFinite() && it in 0f..2f } &&
            destination.first() >= .05f) { "PRISM body requires 32 bounded destination amplitudes and its root" }
        return destination.mapIndexed { index, weight ->
            (weight * (.03 + .97 / (1.0 + (index / 4.0).pow(4)))).toFloat()
                .let { if (index == 0) it.coerceAtLeast(.05f) else it }
        }
    }

    private fun opening(name: String, voice: PrismVoice, tune: Float, destination: List<Float>) = PrismPatch(
        name = name, voice = voice,
        macros = mapOf("TUNE" to tune, "GRAIN" to 0f, "DRIFT" to 0f, "SHAPE" to .4f,
            "STRIKE" to .4f, "BODY" to .5f, "HOLD" to .8f),
        velocity = .8f, template = carrier, model = 1,
        motion = PrismMotion(amplitudesB = destination, morph = 0f, evolve = 1f, seconds = .8f,
            groupLag = 0f, amplitudesA = bodyAmplitudes(destination)),
    )

    private val presets = listOf(
        // fork-soft-keys: frozen measured destination, not a dependency on the source preset.
        opening("Soft Arrival", PrismVoice.KEY, 0.4166666567325592f, listOf(
            0.9516389966011047f, 0.039345335215330124f, 0.011772933416068554f, 0.006825566291809082f,
            0.008232474327087402f, 0.008877857588231564f, 0.00529437605291605f, 0.002574586309492588f,
            0.001752364682033658f, 0.0012218364281579852f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
        )),
        // tessera-paired-wire: frozen measured destination, not a dependency on the source preset.
        opening("Wire Bloom", PrismVoice.KEY, 0.5f, listOf(
            0.9814603924751282f, 0.06184355914592743f, 0.009793437086045742f, 0.0019231881015002728f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
        )),
        // embouchure-relaxed-bow: frozen measured destination, not a dependency on the source preset.
        opening("Bowed Bloom", PrismVoice.KEY, 0.5f, listOf(
            0.9809988737106323f, 0.26526695489883423f, 0.1337607353925705f, 0.14732401072978973f,
            0.10223403573036194f, 0.09060162305831909f, 0.07427246868610382f, 0.0472000315785408f,
            0.03510012850165367f, 0.03927333652973175f, 0.041032906621694565f, 0.038984812796115875f,
            0.03661913797259331f, 0.027493271976709366f, 0.021054115146398544f, 0.01610482856631279f,
            0.011834004893898964f, 0.008850319311022758f, 0.006723717320710421f, 0.0064508202485740185f,
            0.006535015068948269f, 0.00661631440743804f, 0.006694946438074112f, 0.005964972544461489f,
            0.004944208078086376f, 0.004128403961658478f, 0.0034707519225776196f, 0.002936336677521467f,
            0.0027175801806151867f, 0.0027223427314311266f, 0.002726956969127059f, 0.002731431508436799f,
        )),
        // fathom-hollow-growl: frozen measured destination, not a dependency on the source preset.
        opening("Hollow Rise", PrismVoice.BASS, 0.5833333134651184f, listOf(
            0.11999999731779099f, 0.0010000000474974513f, 0.17717128992080688f, 0.060032062232494354f,
            0.031184790655970573f, 0.025457171723246574f, 0.014966812916100025f, 0.007513420656323433f,
            0.005621437914669514f, 0.004271643701940775f, 0.0032814601436257362f, 0.0025108817499130964f,
            0.0018910137005150318f, 0.0014544293517246842f, 0.001193209202028811f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
            0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f, 0.0010000000474974513f,
        )),
    )

    fun all(): List<PrismPatch> = presets

    internal fun descriptionFor(name: String): String? = when (name) {
        "Soft Arrival" -> "An electric-key spectral body opens into its saved upper colour over 0.8 seconds."
        "Wire Bloom" -> "A paired-wire spectral body opens into its saved upper colour over 0.8 seconds."
        "Bowed Bloom" -> "A bowed spectral body opens into its saved upper colour over 0.8 seconds."
        "Hollow Rise" -> "A low hollow body opens into its saved growl colour over 0.8 seconds."
        else -> null
    }
}
