package com.snipsnap.synth

/** Complete preset-role configurations from the owner-reviewed listening pack.
 * Independent carrier balances and envelopes keep the roles distinct beyond their
 * opening transient. The selected voice still supplies its breath and held routing;
 * these finite roles use MATERIAL <= .5, so their contrast does not rely on air noise.
 * Constants are authored shapes, frozen by the approved-patch fixtures. */
internal object AlloyFactoryRoles {
    // Muted bass anchor: Soft contact → nearly pure root → short rounded decay.
    val roundedPlug: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.BASS).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.85f, 0.2f, 0.05f),
            AlloyConfig.Operator(1f, 0.04f, 0.08f, 0.01f),
            AlloyConfig.Operator(2f, 0.03f, 0.06f, 0f),
            AlloyConfig.Operator(3f, 0f, 0.03f, 0f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.1f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2.7f, 4.1f), listOf(0.045f, 0.018f, 0.008f), listOf(0.65f, 0.25f, 0.1f)),
        tine = AlloyConfig.Projection(listOf(1f, 2.7f, 4.1f), listOf(0.045f, 0.018f, 0.008f), listOf(0.65f, 0.25f, 0.1f)),
        envelope = AlloyConfig.Envelope(0.004f, 0.65f, 0.85f, 0.05f),
        fuseRadians = 0.7f,
        cutoffRatio = 4f,
    )

    // Rubbery bass attack: Hard contact → bright phase bend → hollow octave body.
    val elasticContact: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.BASS).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.2f, 0.6f, 0.8f),
            AlloyConfig.Operator(1f, 0.15f, 0.1f, 0.03f),
            AlloyConfig.Operator(2f, 0.85f, 0.16f, 0.85f),
            AlloyConfig.Operator(3f, 0.35f, 0.08f, 0.3f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 2.5f),
            AlloyConfig.Route(3, 2, 0.3f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2.02f, 3.8f), listOf(0.12f, 0.055f, 0.018f), listOf(0.5f, 0.35f, 0.15f)),
        tine = AlloyConfig.Projection(listOf(1f, 2.02f, 3.8f), listOf(0.12f, 0.055f, 0.018f), listOf(0.5f, 0.35f, 0.15f)),
        envelope = AlloyConfig.Envelope(0.0007f, 1.5f, 0.36f, 0.25f),
        fuseRadians = 2.5f,
        cutoffRatio = 18f,
    )

    // Odd harmonic bass line: Firm onset → persistent hollow reed spectrum → deliberate release.
    val hollowRail: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.BASS).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.15f, 1f, 1f),
            AlloyConfig.Operator(3f, 0.85f, 0.5f, 0.9f),
            AlloyConfig.Operator(5f, 0.55f, 0.35f, 0.7f),
            AlloyConfig.Operator(7f, 0.35f, 0.2f, 0.6f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.45f),
            AlloyConfig.Route(3, 1, 0.15f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 3f, 5f), listOf(0.18f, 0.1f, 0.045f), listOf(0.35f, 0.4f, 0.25f)),
        tine = AlloyConfig.Projection(listOf(1f, 3f, 5f), listOf(0.18f, 0.1f, 0.045f), listOf(0.35f, 0.4f, 0.25f)),
        envelope = AlloyConfig.Envelope(0.014f, 1.7f, 0.24f, 0.86f),
        fuseRadians = 1.5f,
        cutoffRatio = 20f,
    )

    // Damped wooden key: Rounded hammer → warm harmonic body → compact tail.
    val feltRelay: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.KEY).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.72f, 0.4f, 0.7f),
            AlloyConfig.Operator(2f, 0.2f, 0.18f, 0.12f),
            AlloyConfig.Operator(3f, 0.1f, 0.1f, 0.04f),
            AlloyConfig.Operator(4f, 0.04f, 0.06f, 0f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.12f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2.71f, 5.23f), listOf(0.075f, 0.035f, 0.012f), listOf(0.55f, 0.3f, 0.15f)),
        tine = AlloyConfig.Projection(listOf(1f, 2.71f, 5.23f), listOf(0.075f, 0.035f, 0.012f), listOf(0.55f, 0.3f, 0.15f)),
        envelope = AlloyConfig.Envelope(0.009f, 1.2f, 0.7f, 0.08f),
        fuseRadians = 1.2f,
        cutoffRatio = 6f,
    )

    // Ringing electric key: Sharp tine → strong upper harmonics → long glassy decay.
    val longPrism: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.KEY).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.15f, 1f, 0.65f),
            AlloyConfig.Operator(3f, 0.85f, 1.4f, 0.08f),
            AlloyConfig.Operator(5f, 0.42f, 0.7f, 0.015f),
            AlloyConfig.Operator(9f, 0.25f, 0.32f, 0f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.18f),
            AlloyConfig.Route(2, 1, 0.12f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 3f, 5f, 9f), listOf(0.8f, 0.55f, 0.3f, 0.13f), listOf(0.22f, 0.33f, 0.29f, 0.16f)),
        tine = AlloyConfig.Projection(listOf(1f, 3f, 5f, 9f), listOf(0.8f, 0.55f, 0.3f, 0.13f), listOf(0.22f, 0.33f, 0.29f, 0.16f)),
        envelope = AlloyConfig.Envelope(0.0008f, 2.65f, 0.7f, 0.06f),
        fuseRadians = 1.4f,
        cutoffRatio = 24f,
    )

    // Hollow electric key: Soft click → odd harmonic reed tone → steady held body and release.
    val reedSwitch: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.KEY).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.14f, 0.8f, 1f),
            AlloyConfig.Operator(3f, 0.9f, 0.65f, 0.7f),
            AlloyConfig.Operator(5f, 0.55f, 0.4f, 0.55f),
            AlloyConfig.Operator(7f, 0.28f, 0.2f, 0.4f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.3f),
            AlloyConfig.Route(3, 1, 0.1f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 3.02f, 5.05f), listOf(0.18f, 0.1f, 0.04f), listOf(0.45f, 0.35f, 0.2f)),
        tine = AlloyConfig.Projection(listOf(1f, 3.02f, 5.05f), listOf(0.18f, 0.1f, 0.04f), listOf(0.45f, 0.35f, 0.2f)),
        envelope = AlloyConfig.Envelope(0.016f, 1.7f, 0.25f, 0.65f),
        fuseRadians = 1.6f,
        cutoffRatio = 18f,
    )

    // Muted pluck: Small pick click → compact harmonic body → rapid damping.
    val dampedPick: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.PLUCK).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.45f, 0.12f, 0.08f),
            AlloyConfig.Operator(2f, 0.48f, 0.065f, 0.01f),
            AlloyConfig.Operator(3f, 0.2f, 0.04f, 0f),
            AlloyConfig.Operator(5f, 0.1f, 0.025f, 0f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.55f),
            AlloyConfig.Route(2, 0, 0.2f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 3.32f, 6.81f), listOf(0.045f, 0.025f, 0.008f), listOf(0.5f, 0.35f, 0.15f)),
        tine = AlloyConfig.Projection(listOf(1f, 3.32f, 6.81f), listOf(0.045f, 0.025f, 0.008f), listOf(0.5f, 0.35f, 0.15f)),
        envelope = AlloyConfig.Envelope(0.0005f, 0.65f, 1f, 0.05f),
        fuseRadians = 2.2f,
        cutoffRatio = 10f,
    )

    // Ringing wire pluck: Bright wire impact → separated upper harmonics → long ring.
    val openChime: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.PLUCK).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.12f, 0.65f, 0.6f),
            AlloyConfig.Operator(4f, 0.9f, 1.3f, 0.02f),
            AlloyConfig.Operator(6f, 0.5f, 0.72f, 0.01f),
            AlloyConfig.Operator(9f, 0.25f, 0.35f, 0f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.12f),
            AlloyConfig.Route(3, 2, 0.14f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 4f, 6f, 9f), listOf(0.55f, 0.4f, 0.24f, 0.1f), listOf(0.2f, 0.32f, 0.32f, 0.16f)),
        tine = AlloyConfig.Projection(listOf(1f, 4f, 6f, 9f), listOf(0.55f, 0.4f, 0.24f, 0.1f), listOf(0.2f, 0.32f, 0.32f, 0.16f)),
        envelope = AlloyConfig.Envelope(0.0005f, 2f, 0.65f, 0.05f),
        fuseRadians = 1.8f,
        cutoffRatio = 24f,
    )

    // Biting pluck lead: Hard narrow pick → strong decaying phase buzz → dry pitched finish.
    val nasalSnap: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.PLUCK).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.25f, 0.55f, 0.85f),
            AlloyConfig.Operator(2f, 0.4f, 0.2f, 0.35f),
            AlloyConfig.Operator(3f, 0.75f, 0.14f, 0.35f),
            AlloyConfig.Operator(5f, 0.35f, 0.07f, 0.14f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 2.5f),
            AlloyConfig.Route(2, 1, 0.65f),
            AlloyConfig.Route(3, 0, 0.45f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2.2f, 4.4f), listOf(0.14f, 0.075f, 0.025f), listOf(0.35f, 0.4f, 0.25f)),
        tine = AlloyConfig.Projection(listOf(1f, 2.2f, 4.4f), listOf(0.14f, 0.075f, 0.025f), listOf(0.35f, 0.4f, 0.25f)),
        envelope = AlloyConfig.Envelope(0.0005f, 1.15f, 0.55f, 0.17f),
        fuseRadians = 2.4f,
        cutoffRatio = 22f,
    )

    // Warm sustained horn: Rounded swell → low harmonic body → soft breath-free release.
    val roundHorn: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.BRASS).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.65f, 1f, 1f),
            AlloyConfig.Operator(2f, 0.4f, 0.6f, 0.9f),
            AlloyConfig.Operator(3f, 0.17f, 0.4f, 0.7f),
            AlloyConfig.Operator(4f, 0.07f, 0.2f, 0.4f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.2f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2f, 3f), listOf(0.35f, 0.2f, 0.1f), listOf(0.45f, 0.35f, 0.2f)),
        tine = AlloyConfig.Projection(listOf(1f, 2f, 3f), listOf(0.35f, 0.2f, 0.1f), listOf(0.45f, 0.35f, 0.2f)),
        envelope = AlloyConfig.Envelope(0.1f, 2f, 0.42f, 0.86f),
        fuseRadians = 1.2f,
        cutoffRatio = 6f,
    )

    // Hollow reed horn: Clear tongue → pronounced odd harmonics → level sustained line.
    val hollowCopper: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.BRASS).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.15f, 1f, 1f),
            AlloyConfig.Operator(3f, 0.85f, 0.7f, 0.92f),
            AlloyConfig.Operator(5f, 0.58f, 0.6f, 0.86f),
            AlloyConfig.Operator(7f, 0.32f, 0.45f, 0.75f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.35f),
            AlloyConfig.Route(3, 1, 0.14f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 3f, 5f, 7f), listOf(0.5f, 0.32f, 0.2f, 0.1f), listOf(0.25f, 0.34f, 0.25f, 0.16f)),
        tine = AlloyConfig.Projection(listOf(1f, 3f, 5f, 7f), listOf(0.5f, 0.32f, 0.2f, 0.1f), listOf(0.25f, 0.34f, 0.25f, 0.16f)),
        envelope = AlloyConfig.Envelope(0.026f, 2.1f, 0.24f, 0.94f),
        fuseRadians = 1.7f,
        cutoffRatio = 22f,
    )

    // Brass stab: Fast bright tongue → nested phase bite → short punched release.
    val brightStamp: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.BRASS).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.2f, 0.5f, 1f),
            AlloyConfig.Operator(2f, 0.7f, 0.24f, 0.48f),
            AlloyConfig.Operator(3f, 0.6f, 0.15f, 0.3f),
            AlloyConfig.Operator(4f, 0.3f, 0.08f, 0.1f),
        ),
        routing = listOf(
            AlloyConfig.Route(3, 2, 0.65f),
            AlloyConfig.Route(2, 1, 0.8f),
            AlloyConfig.Route(1, 0, 1.4f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2.95f, 5.91f), listOf(0.12f, 0.06f, 0.025f), listOf(0.3f, 0.4f, 0.3f)),
        tine = AlloyConfig.Projection(listOf(1f, 2.95f, 5.91f), listOf(0.12f, 0.06f, 0.025f), listOf(0.3f, 0.4f, 0.3f)),
        envelope = AlloyConfig.Envelope(0.0015f, 0.75f, 0.75f, 0.16f),
        fuseRadians = 2.2f,
        cutoffRatio = 24f,
    )

    // Slow wooden string: Slow bow entrance → dark harmonic sustain → long soft release.
    val darkBow: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.STRING).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.65f, 1f, 1f),
            AlloyConfig.Operator(2f, 0.4f, 1.2f, 0.95f),
            AlloyConfig.Operator(3f, 0.18f, 0.8f, 0.85f),
            AlloyConfig.Operator(4f, 0.08f, 0.5f, 0.65f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.15f),
            AlloyConfig.Route(2, 0, 0.12f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2f, 3f, 6f), listOf(0.7f, 0.6f, 0.45f, 0.28f), listOf(0.18f, 0.34f, 0.3f, 0.18f)),
        tine = AlloyConfig.Projection(listOf(1f, 2f, 3f, 6f), listOf(0.7f, 0.6f, 0.45f, 0.28f), listOf(0.18f, 0.34f, 0.3f, 0.18f)),
        envelope = AlloyConfig.Envelope(0.3f, 3.2f, 0.75f, 0.92f),
        fuseRadians = 1.2f,
        cutoffRatio = 5f,
    )

    // Short picked string: Immediate pick → bright harmonic ping → fast damped finish.
    val pickedFibre: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.STRING).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.25f, 0.16f, 0.55f),
            AlloyConfig.Operator(2f, 0.7f, 0.12f, 0.5f),
            AlloyConfig.Operator(3f, 0.4f, 0.07f, 0.3f),
            AlloyConfig.Operator(5f, 0.15f, 0.04f, 0.06f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.7f),
            AlloyConfig.Route(3, 1, 0.25f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2.9f, 5.8f), listOf(0.09f, 0.045f, 0.016f), listOf(0.35f, 0.4f, 0.25f)),
        tine = AlloyConfig.Projection(listOf(1f, 2.9f, 5.8f), listOf(0.09f, 0.045f, 0.016f), listOf(0.35f, 0.4f, 0.25f)),
        envelope = AlloyConfig.Envelope(0.0005f, 0.7f, 0.95f, 0.05f),
        fuseRadians = 2.1f,
        cutoffRatio = 18f,
    )

    // Shimmering string sustain: Gentle onset → close detuned partial beating → long clear release.
    val beatingGlass: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.STRING).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.14f, 1f, 1f),
            AlloyConfig.Operator(2.01f, 0.82f, 2f, 0.94f),
            AlloyConfig.Operator(3.02f, 0.56f, 1.5f, 0.88f),
            AlloyConfig.Operator(4.03f, 0.38f, 1f, 0.82f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.12f),
            AlloyConfig.Route(2, 0, 0.08f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2.01f, 4.06f, 6.12f), listOf(1.2f, 0.95f, 0.75f, 0.48f), listOf(0.12f, 0.2f, 0.43f, 0.25f)),
        tine = AlloyConfig.Projection(listOf(1f, 2.01f, 4.06f, 6.12f), listOf(1.2f, 0.95f, 0.75f, 0.48f), listOf(0.12f, 0.2f, 0.43f, 0.25f)),
        envelope = AlloyConfig.Envelope(0.16f, 3.3f, 0.7f, 0.9f),
        fuseRadians = 1.3f,
        cutoffRatio = 22f,
    )

    // Soft percussive key: Gentle tap → nearly pure warm note → compact felt decay.
    val feltDrop: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.SOFT).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.8f, 0.3f, 0.3f),
            AlloyConfig.Operator(1f, 0.05f, 0.18f, 0.04f),
            AlloyConfig.Operator(2f, 0.08f, 0.1f, 0.02f),
            AlloyConfig.Operator(3f, 0.02f, 0.05f, 0f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.1f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2.7f, 4.1f), listOf(0.06f, 0.025f, 0.01f), listOf(0.65f, 0.25f, 0.1f)),
        tine = AlloyConfig.Projection(listOf(1f, 2.7f, 4.1f), listOf(0.06f, 0.025f, 0.01f), listOf(0.65f, 0.25f, 0.1f)),
        envelope = AlloyConfig.Envelope(0.008f, 0.85f, 0.85f, 0.05f),
        fuseRadians = 0.7f,
        cutoffRatio = 4f,
    )

    // Soft slow pad: Slow rounded swell → warm even-harmonic sustain → long falling release.
    val velvetRise: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.SOFT).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.18f, 1f, 1f),
            AlloyConfig.Operator(2f, 0.7f, 1.5f, 0.9f),
            AlloyConfig.Operator(3f, 0.35f, 1f, 0.85f),
            AlloyConfig.Operator(4f, 0.1f, 0.5f, 0.6f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.2f),
            AlloyConfig.Route(2, 1, 0.1f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 2f, 3f), listOf(0.8f, 0.5f, 0.3f), listOf(0.5f, 0.35f, 0.15f)),
        tine = AlloyConfig.Projection(listOf(1f, 2f, 3f), listOf(0.8f, 0.5f, 0.3f), listOf(0.5f, 0.35f, 0.15f)),
        envelope = AlloyConfig.Envelope(0.3f, 3.1f, 0.85f, 0.9f),
        fuseRadians = 1f,
        cutoffRatio = 5f,
    )

    // Soft odd harmonic pad: Soft bloom → hollow organ-like harmonic body → clear lingering release.
    val hollowGlow: AlloyConfig = Alloy.defaultConfigFor(AlloyVoice.SOFT).copy(
        operators = listOf(
            AlloyConfig.Operator(1f, 0.2f, 1f, 1f),
            AlloyConfig.Operator(3f, 0.88f, 1.7f, 0.94f),
            AlloyConfig.Operator(5f, 0.52f, 1.1f, 0.82f),
            AlloyConfig.Operator(7f, 0.24f, 0.6f, 0.65f),
        ),
        routing = listOf(
            AlloyConfig.Route(1, 0, 0.15f),
            AlloyConfig.Route(3, 1, 0.08f),
        ),
        wood = AlloyConfig.Projection(listOf(1f, 3f, 5f), listOf(0.9f, 0.65f, 0.35f), listOf(0.3f, 0.48f, 0.22f)),
        tine = AlloyConfig.Projection(listOf(1f, 3f, 5f), listOf(0.9f, 0.65f, 0.35f), listOf(0.3f, 0.48f, 0.22f)),
        envelope = AlloyConfig.Envelope(0.18f, 3f, 0.85f, 0.78f),
        fuseRadians = 1.2f,
    )

}
