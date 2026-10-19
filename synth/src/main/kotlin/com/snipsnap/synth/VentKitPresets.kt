package com.snipsnap.synth

import kotlin.math.ln

/**
 * Twelve dry, finite model-2 instruments for a C-minor-pentatonic VENT kit.
 *
 * Each row supplies a root, a short pitched note, a struck accent and a longer passage.
 * The pitched roles use less chaotic motion and stronger LEASH; the accents and textures
 * leave more motion free. These choices change the trajectory and its linear view rather
 * than introducing a drive ladder. Every entry retains its exact note, touch and history.
 * [VentPresets] remains the immutable table used by the earlier controlled auditions.
 */
object VentKitPresets {
    data class Entry(val id: String, val role: String, val patch: VentPatch)

    /** Stable voice-major pad order: PULSE, COIL, PLUME; bass, note, hit, texture in each. */
    val entries: List<Entry> = listOf(
        entry("pulse-bass", "Bass", "Pulse Root", VentVoice.PULSE, 36, .92f, 17, .52,
            chaos = .24f, leash = .88f, view = .12f, damp = .68f, push = .34f),
        entry("pulse-note", "Short note", "Pulse Pin", VentVoice.PULSE, 60, .80f, 29, .26,
            chaos = .48f, leash = .72f, view = .44f, damp = .32f, push = .66f),
        entry("pulse-hit", "Percussive hit", "Pulse Knuckle", VentVoice.PULSE, 48, .88f, 43, .20,
            chaos = .88f, leash = .28f, view = .83f, damp = .18f, push = .92f),
        entry("pulse-texture", "Long texture", "Pulse Dust", VentVoice.PULSE, 67, .67f, 71, 3.6,
            chaos = .79f, leash = .34f, view = .65f, damp = .45f, push = .38f),

        entry("coil-bass", "Bass", "Coil Root", VentVoice.COIL, 36, .90f, 23, .62,
            chaos = .20f, leash = .86f, view = .14f, damp = .72f, push = .28f),
        entry("coil-note", "Short note", "Coil Fleck", VentVoice.COIL, 63, .76f, 31, .32,
            chaos = .48f, leash = .80f, view = .26f, damp = .36f, push = .62f),
        entry("coil-hit", "Percussive hit", "Coil Tick", VentVoice.COIL, 60, .86f, 47, .24,
            chaos = .77f, leash = .42f, view = .76f, damp = .16f, push = .90f),
        entry("coil-texture", "Long texture", "Coil Ribbon", VentVoice.COIL, 60, .70f, 79, 4.2,
            chaos = .72f, leash = .40f, view = .38f, damp = .36f, push = .48f),

        entry("plume-bass", "Bass", "Plume Root", VentVoice.PLUME, 36, .90f, 19, .66,
            chaos = .18f, leash = .95f, view = .12f, damp = .72f, push = .12f),
        entry("plume-note", "Short note", "Plume Reed", VentVoice.PLUME, 67, .75f, 37, .30,
            chaos = .36f, leash = .88f, view = .31f, damp = .46f, push = .18f),
        entry("plume-hit", "Percussive hit", "Plume Knock", VentVoice.PLUME, 48, .90f, 53, .26,
            chaos = .82f, leash = .35f, view = .91f, damp = .20f, push = .85f),
        entry("plume-texture", "Long texture", "Plume Cloud", VentVoice.PLUME, 63, .68f, 83, 4.8,
            chaos = .84f, leash = .38f, view = .71f, damp = .42f, push = .42f),
    )

    /** Paste complete model-2 lines from UserPresets.renderAll here; the kit's twelve pads stay fixed. */
    private val promoted: List<VentPatch> = listOf<VentPatch>(
    ).also { patches ->
        require(patches.all { it.model == Vent.CLEAN_MODEL }) { "VENT factory promotions must use model 2" }
    }

    fun all(): List<VentPatch> = entries.map { it.patch } + promoted

    /** Model-1 voices deliberately have no starter-kit entries. */
    fun forVoice(voice: VentVoice): List<VentPatch> = all().filter { it.voice == voice }

    private fun entry(
        id: String, role: String, name: String, voice: VentVoice, midi: Int, velocity: Float,
        seed: Int, seconds: Double, chaos: Float, leash: Float, view: Float, damp: Float, push: Float,
    ) = Entry(id, role, VentPatch(
        name = name,
        voice = voice,
        macros = linkedMapOf(
            "CHAOS" to chaos, "LEASH" to leash, "VIEW" to view,
            "DAMP" to damp, "PUSH" to push,
            // Inverse of model 2's .18 * (8/.18)^LENGTH, so durations are musical choices.
            "LENGTH" to (ln(seconds/.18)/ln(8.0/.18)).toFloat(),
        ),
        midi = midi,
        velocity = velocity,
        seed = seed,
        model = Vent.CLEAN_MODEL,
    ))
}
