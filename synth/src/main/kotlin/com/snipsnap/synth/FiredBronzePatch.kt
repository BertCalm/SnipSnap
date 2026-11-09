package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import kotlin.random.Random

/** Editable adapter for the MPC-approved performance recipe, model 1.
 * This finite percussion kit retains its authored finishing chain and tails;
 * it does not invent a sustained HOLD mode or run a second house output stage.
 */
data class FiredBronzePatch(
    override val name: String,
    val voice: FiredBronzeDryKit.Pad,
    override val macros: Map<String, Float> = FiredBronzeInstrument.defaults(),
) : Patch {
    init { Patches.validateMacros(this, FiredBronzeInstrument.macros) }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = FiredBronzeInstrument.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "FIRED_BRONZE"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch = Patches.decode(value, ENGINE,
            { n -> FiredBronzeDryKit.Pad.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
            FiredBronzePatch(name, voice, macros)
        }
    }
}

object FiredBronzeInstrument {
    // Carry the profile credit in kit.json too; MPC exports preserve pad source metadata.
    private val attribution: String by lazy {
        requireNotNull(javaClass.getResourceAsStream("/fired-bronze-r2/NOTICE.txt"))
            .bufferedReader().use { it.readText() }
    }
    val macros = listOf(MacroSpec("TUNE", .5f), MacroSpec("TOUCH", .5f),
        MacroSpec("DAMP", 0f), MacroSpec("HEAT", .72f),
        MacroSpec("SPACE", .45f), MacroSpec("AMP", .70f))
    fun defaults(): Map<String, Float> = macros.associate { it.name to it.default }
    fun settled(values: Map<String, Float>): Map<String, Float> = macros.associate {
        it.name to (values[it.name]?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: it.default)
    }
    fun scramble(random: Random): Map<String, Float> = macros.associate { it.name to random.nextFloat() }

    fun render(voice: FiredBronzeDryKit.Pad, values: Map<String, Float>, velocity: Float = 1f): com.snipsnap.audio.Snip {
        val m = settled(values)
        val source = FiredBronzeDryKit.Recipe(voice, m.getValue("TUNE"), m.getValue("TOUCH"),
            m.getValue("DAMP"), velocity = velocity)
        // The shared response law keeps the approved four-layer performance exact.
        val base = FiredBronzePush.Settings(heat = m.getValue("HEAT"),
            space = m.getValue("SPACE"), amp = m.getValue("AMP"))
        return FiredBronzePush.process(source.render(), voice, FiredBronzePush.Route.BOTH,
            FiredBronzePerformance.settings(velocity, base))
    }

    /** Fixed pad order, mix, hat chokes and native strike layers from the approved kit. */
    fun kit(): List<ArrangedPad> = FiredBronzePresets.all().map { patch ->
        val p = patch.voice
        val hard = patch.render()
        ArrangedPad(hard, p.kind, recipe = PadRecipe(patch).toJsonValue(),
            level = p.gain, displayName = p.label, muteGroup = p.choke,
            source = mapOf("attribution" to attribution),
            softVariants = FiredBronzePerformance.layerVelocities.dropLast(1).map {
                Velocity.peakMatch(hard, Velocity.atVelocity(patch, it))
            }, layerNames = listOf("LIGHT", "MEDIUM", "FIRM", "FULL"))
    }
}

