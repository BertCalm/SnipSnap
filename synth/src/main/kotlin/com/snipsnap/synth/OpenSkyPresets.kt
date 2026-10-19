package com.snipsnap.synth

/** The eight accepted R3 source recipes, retained exactly as auditioned. */
object OpenSkyPresets {
    data class Entry(val id: String, val description: String, val patch: OpenSkyPatch)

    private fun entry(id: String, name: String, voice: OpenSkyVoice, rise: Float, width: Float,
        glow: Float, air: Float, description: String, timbre: OpenSkyTimbre): Entry = Entry(
        id, description, OpenSkyPatch(name, voice, OpenSky.defaults(voice) +
            mapOf("RISE" to rise, "WIDTH" to width, "GLOW" to glow, "AIR" to air), timbre = timbre),
    )

    private val roster = listOf(
        entry("first-light", "First Light", OpenSkyVoice.ANTHEM, 0.25f, 0.2f, 0.25f, 0.45f,
            "A tight pulse-led brass attack with narrow detune, a settling body and a short release. Compare its dry source with Full Column's layered saw and octave body.",
            OpenSkyTimbre(pulseMix = .9f, pulseWidth = .25f, sineMix = .1f, detuneScale = .3f,
                toneScale = .65f, attackScale = .45f, sustainLevel = .65f, decaySeconds = .15f, releaseScale = .45f)),
        entry("full-column", "Full Column", OpenSkyVoice.ANTHEM, 0.85f, 0.95f, 0.7f, 0.85f,
            "A wide saw anthem with an added fixed octave layer, brighter tone and fuller sustain. Listen for the layered source before comparing the wet halo.",
            OpenSkyTimbre(octaveMix = .4f, detuneScale = 1.5f, toneScale = 1.55f,
                attackScale = .7f, releaseScale = 1.3f)),
        entry("blue-hour", "Blue Hour", OpenSkyVoice.SOAR, 0.2f, 0.25f, 0.25f, 0.2f,
            "A rounded sine-supported lead with a stronger center, restrained detune and dark tone. Listen for its smooth body beside Solar Arc's cutting pulse/saw edge.",
            OpenSkyTimbre(sineMix = .8f, centerWeightScale = 2.7f, detuneScale = .25f,
                toneScale = .4f, attackScale = 1.1f)),
        entry("solar-arc", "Solar Arc", OpenSkyVoice.SOAR, 0.8f, 0.65f, 0.5f, 0.9f,
            "A cutting saw/pulse lead with a narrow pulse shape, octave support and a quick attack. Compare its upper edge and settling articulation with Blue Hour's smooth center.",
            OpenSkyTimbre(pulseMix = .55f, pulseWidth = .32f, sineMix = .05f, octaveMix = .15f,
                centerWeightScale = .75f, detuneScale = 1.2f, toneScale = 1.8f,
                attackScale = .35f, sustainLevel = .8f, decaySeconds = .16f, releaseScale = .65f)),
        entry("soft-horizon", "Soft Horizon", OpenSkyVoice.HALO, 0.25f, 0.5f, 0.75f, 0.15f,
            "A dark sine-rich pad with a slow entrance and long source release. Follow its rounded dry swell beside Open Sky's faster, brighter layered entrance.",
            OpenSkyTimbre(sineMix = .8f, detuneScale = .5f, toneScale = .35f,
                attackScale = 1.7f, releaseScale = 2f)),
        entry("open-sky", "Open Sky", OpenSkyVoice.HALO, 0.85f, 0.95f, 0.9f, 0.85f,
            "A luminous saw/pulse pad with aligned root support, a fixed octave layer and a quicker opening. Compare the dry harmonic layers with Soft Horizon's darker, slower swell.",
            OpenSkyTimbre(pulseMix = .25f, pulseWidth = .42f, sineMix = .25f, octaveMix = .45f,
                detuneScale = 1.3f, toneScale = 1.6f, attackScale = .65f, releaseScale = 1.3f)),
        entry("sun-fleck", "Sun Fleck", OpenSkyVoice.CRYSTAL, 0.2f, 0.25f, 0.2f, 0.35f,
            "A short fundamental-led ping with subdued upper partials, fast partial decay and a brief release. Listen for the compact struck body beside Skylight's upper ringing tones.",
            OpenSkyTimbre(centerWeightScale = .4f, attackScale = .5f, sustainLevel = .35f,
                decaySeconds = .08f, releaseScale = .3f, bellBrightnessScale = .25f, bellDecayScale = .3f)),
        entry("skylight", "Skylight", OpenSkyVoice.CRYSTAL, 0.75f, 0.85f, 0.8f, 0.9f,
            "An upper-partial bell with a stronger fourth-harmonic center and longer ringing decay. Compare its dry partial balance and lingering source with Sun Fleck's compact fundamental-led ping.",
            OpenSkyTimbre(centerWeightScale = 2.3f, releaseScale = 2.3f,
                bellBrightnessScale = 2f, bellDecayScale = 2.8f)),
    )

    fun entries(): List<Entry> = roster
    fun all(): List<OpenSkyPatch> = roster.map { it.patch }
    fun forVoice(voice: OpenSkyVoice): List<OpenSkyPatch> = roster.map { it.patch }.filter { it.voice == voice }
}
