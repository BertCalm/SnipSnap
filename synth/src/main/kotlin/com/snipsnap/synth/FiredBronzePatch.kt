package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import kotlin.random.Random

/** Editable adapter for the MPC-approved performance recipe.
 * This finite percussion kit retains its authored finishing chain and tails;
 * it does not invent a sustained HOLD mode or run a second house output stage.
 * [model] is the renderer it was made with ([FiredBronzeInstrument.MODEL_VERSION]): a save made before
 * models has none and reads as model 1, the approved kit, so it keeps its sound.
 */
data class FiredBronzePatch(
    override val name: String,
    val voice: FiredBronzeDryKit.Pad,
    override val macros: Map<String, Float> = FiredBronzeInstrument.defaults(),
    val model: Int = FiredBronzeInstrument.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, FiredBronzeInstrument.macros)
        require(model in 1..FiredBronzeInstrument.MODEL_VERSION) { "unsupported fired bronze model $model" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(1f)

    /** [take] is a fresh strike on model 2 (the kit's round robin); take 0 is the reference, on any model. */
    fun render(velocity: Float, take: Int = 0) = FiredBronzeInstrument.render(voice, macros, velocity, take, model)

    /** Model 1 writes no model, so the approved kit's saves read back byte for byte. */
    override fun toJsonValue(): JsonValue.Obj {
        if (model == 1) return Patches.toJsonValue(this)
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "FIRED_BRONZE"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch = Patches.decode(value, ENGINE,
            { n -> FiredBronzeDryKit.Pad.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
            FiredBronzePatch(name, voice, macros, value.obj()["model"]?.int() ?: 1)
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

    /**
     * Renderer models. A save keeps the model it was made with.
     * - **2 (round robin, 2026-11-13):** a later take ([render]'s `take` > 0) is a fresh strike: the
     *   source's contact, rattle and residual noise are re-seeded, and the constructed sources' modes land
     *   with a new balance. Take 0 is model 1's, byte for byte. The kit deals [TAKES] strikes per layer.
     * - **1 (approved on MPC, 2026-11-08):** one strike per layer; a take changes nothing.
     */
    const val MODEL_VERSION = 2

    /** Model 2's kit: each of the four strike layers dealt this many fresh strikes. */
    const val TAKES = 3

    fun render(voice: FiredBronzeDryKit.Pad, values: Map<String, Float>, velocity: Float = 1f, take: Int = 0,
               model: Int = MODEL_VERSION): com.snipsnap.audio.Snip {
        require(model in 1..MODEL_VERSION) { "unsupported fired bronze model $model" }
        require(take >= 0) { "a take is 0 or more, got $take" }
        val m = settled(values)
        val strike = if (model >= 2) take else 0
        val source = FiredBronzeDryKit.Recipe(voice, m.getValue("TUNE"), m.getValue("TOUCH"),
            m.getValue("DAMP"), velocity = velocity, take = strike)
        // The shared response law keeps the approved four-layer performance exact.
        val base = FiredBronzePush.Settings(heat = m.getValue("HEAT"),
            space = m.getValue("SPACE"), amp = m.getValue("AMP"))
        return FiredBronzePush.process(FiredBronzeDryKit.render(source, strike = strike > 0), voice, FiredBronzePush.Route.BOTH,
            FiredBronzePerformance.settings(velocity, base))
    }

    /**
     * Fixed pad order, mix and hat chokes from the approved kit, on [model].
     * - **Model 1** is the kit approved on MPC: four native strike layers per pad, one strike each.
     * - **From model 2** each pad is its full strike with its recipe. The starter deals the four layers and
     *   their fresh strikes through the shared round robin (`Robin`, in the shell), and every layer's first
     *   strike is model 1's layer.
     */
    fun kit(model: Int = MODEL_VERSION): List<ArrangedPad> = FiredBronzePresets.all().map { preset ->
        val patch = preset.copy(model = model)
        val p = patch.voice
        val hard = patch.render()
        if (model >= 2) ArrangedPad(hard, p.kind, recipe = PadRecipe(patch).toJsonValue(),
            level = p.gain, displayName = p.label, muteGroup = p.choke,
            source = mapOf("attribution" to attribution))
        else ArrangedPad(hard, p.kind, recipe = PadRecipe(patch).toJsonValue(),
            level = p.gain, displayName = p.label, muteGroup = p.choke,
            source = mapOf("attribution" to attribution),
            softVariants = FiredBronzePerformance.layerVelocities.dropLast(1).map {
                Velocity.peakMatch(hard, Velocity.atVelocity(patch, it))
            }, layerNames = listOf("LIGHT", "MEDIUM", "FIRM", "FULL"))
    }
}

