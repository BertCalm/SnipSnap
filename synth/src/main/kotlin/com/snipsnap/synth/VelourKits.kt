package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import kotlin.math.abs

/**
 * Fixed sample starters, not a new synth engine. The heard C source bank is
 * retained at Float PCM precision; its source/settings manifest ships in resources.
 * Additional SKIN roles are listening candidates. Each exported pad is already
 * printed through its treatment, so no fx-only recipe falsely applies it twice.
 * Source provenance records that distinction, and factory rebuilding is deterministic.
 */
object VelourKits {
    enum class Zone(val id: String, val velocity: Float) { SOFT("soft", .42f), MID("mid", .7f), HARD("hard", 1f) }
    data class Role(val name: String, val drumClass: DrumClass, val send: Float,
                    val asset: String? = null, val voice: SkinVoice? = null,
                    val macros: Map<String, Float> = emptyMap(), val dry: Boolean = false)

    val roles: List<Role> = listOf(
        Role("C Kick", DrumClass.KICK, .12f, asset = "kick"),
        Role("C Snare", DrumClass.SNARE, 1f, asset = "snare"),
        Role("Closed Hat", DrumClass.HAT_CLOSED, .25f, voice = SkinVoice.HAT_CLOSED,
            macros = mapOf("DECAY" to .18f, "TONE" to .25f, "RING" to .3f)),
        Role("Open Hat", DrumClass.HAT_OPEN, .3f, voice = SkinVoice.HAT_OPEN,
            macros = mapOf("DECAY" to .25f, "TONE" to .25f, "RING" to .35f)),
        Role("Dry C Kick", DrumClass.KICK, 0f, asset = "kick", dry = true),
        Role("Dry C Snare", DrumClass.SNARE, 0f, asset = "snare", dry = true),
        Role("Cross Stick", DrumClass.PERC, .4f, voice = SkinVoice.STICK,
            macros = mapOf("DECAY" to .2f, "BODY" to .45f)),
        Role("Soft Shaker", DrumClass.PERC, .2f, voice = SkinVoice.SHAKER,
            macros = mapOf("DECAY" to .25f, "TONE" to .3f)),
        Role("Low Tom", DrumClass.TOM, .45f, voice = SkinVoice.TOM,
            macros = mapOf("TUNE" to .15f, "DECAY" to .2f, "TONE" to .25f)),
        Role("Mid Tom", DrumClass.TOM, .45f, voice = SkinVoice.TOM,
            macros = mapOf("TUNE" to .4f, "DECAY" to .2f, "TONE" to .25f)),
        Role("High Tom", DrumClass.TOM, .4f, voice = SkinVoice.TOM,
            macros = mapOf("TUNE" to .65f, "DECAY" to .18f, "TONE" to .3f)),
        Role("Dark Ride", DrumClass.PERC, .18f, voice = SkinVoice.RIDE,
            macros = mapOf("DECAY" to .2f, "TONE" to .2f)),
        Role("Dry Cross Stick", DrumClass.PERC, 0f, voice = SkinVoice.STICK,
            macros = mapOf("DECAY" to .2f, "BODY" to .45f), dry = true),
        Role("Dry Closed Hat", DrumClass.HAT_CLOSED, 0f, voice = SkinVoice.HAT_CLOSED,
            macros = mapOf("DECAY" to .18f, "TONE" to .25f, "RING" to .3f), dry = true),
        Role("Dry Low Tom", DrumClass.TOM, 0f, voice = SkinVoice.TOM,
            macros = mapOf("TUNE" to .15f, "DECAY" to .2f, "TONE" to .25f), dry = true),
        Role("Dry Shaker", DrumClass.PERC, 0f, voice = SkinVoice.SHAKER,
            macros = mapOf("DECAY" to .25f, "TONE" to .3f), dry = true),
    )

    /** Every call owns its sample array; editing a returned snip cannot corrupt the factory bank. */
    fun source(role: Role, zone: Zone = Zone.HARD): Snip = if (role.asset != null) {
        require(role.asset in setOf("kick", "snare"))
        WavReader.read(Velour.resource("${role.asset}-${zone.id}.wav"))
    } else {
        val rendered = Skin.render(requireNotNull(role.voice), role.macros, velocity = zone.velocity)
        val clean = Velour.clean(DoubleArray(rendered.samples.size) { rendered.samples[it].toDouble() })
        // SKIN only reads velocity for struck bodies and levels its output. Preserve that
        // timbral change, then give every candidate a real touch-dependent output level.
        rendered.copy(samples = FloatArray(clean.size) { (clean[it] * zone.velocity).toFloat() })
    }

    fun render(role: Role, zone: Zone, treatment: Velour.Treatment): Snip {
        val source = source(role, zone)
        if (role.dry) return source
        val n = source.frameCount + (Velour.TAIL_SECONDS * Velour.RATE).toInt()
        val dry = source.samples.copyOf(n)
        return Velour.renderBus(dry, FloatArray(n) { dry[it] * role.send }, doubleArrayOf(0.0), treatment)
    }

    fun build(treatment: Velour.Treatment = Velour.Treatment.VELOUR): List<ArrangedPad> = roles.map { role ->
        val layers = Zone.entries.map { render(role, it, treatment) }
        // One attenuation per role shared by every zone, never normalizing a quiet strike upward.
        val peak = layers.maxOf { s -> s.samples.maxOf { abs(it) } }
        val gain = if (peak > .95f) .95f / peak else 1f
        val safe = layers.map { s -> s.copy(samples = FloatArray(s.samples.size) { s.samples[it] * gain }) }
        ArrangedPad(safe.last(), role.drumClass, softVariants = safe.dropLast(1), displayName = role.name,
            layerNames = listOf("Soft", "Medium", "Hard"),
            source = mapOf("factory" to "velour", "model" to "1", "role" to role.name,
                "treatment" to if (role.dry) "dry" else treatment.name.lowercase(java.util.Locale.ROOT),
                "rendered" to "true", "approval" to if (role.asset != null) "source-selected" else "candidate",
                "sourceSettings" to (role.asset ?: "SKIN ${role.voice} ${role.macros}")))
    }
}
