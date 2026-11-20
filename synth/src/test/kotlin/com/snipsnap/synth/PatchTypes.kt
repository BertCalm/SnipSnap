package com.snipsnap.synth

/**
 * Every engine a saved sound can name, read off the code rather than a hand list.
 *
 * [Patch] is a sealed interface, so the compiler writes the complete set of its
 * implementations into the class file's PermittedSubclasses attribute (the module
 * targets JVM 17). A new engine's `XPatch` joins this set the moment it compiles,
 * so a registry test built on it fails until the new engine is wired in, rather
 * than passing quietly because nobody added a line to a list.
 */
internal object PatchTypes {

    /** Every concrete [Patch] type. */
    val all: List<Class<out Patch>> by lazy {
        val permitted = requireNotNull(Patch::class.java.permittedSubclasses) {
            "Patch carries no PermittedSubclasses: it must stay a sealed interface compiled for JVM 17+"
        }
        permitted.map { it.asSubclass(Patch::class.java) }
    }

    /** The `ENGINE` tag a [type] writes into its JSON: the companion's `const val`, a static field on the class. */
    fun engineOf(type: Class<out Patch>): String = type.getField("ENGINE").get(null) as String

    /** Every voice from an enum property, or a single fixed `VOICE` constant when there is no property. */
    fun voicesOf(type: Class<out Patch>): List<String> {
        val getter = try {
            type.getMethod("getVoice")
        } catch (_: NoSuchMethodException) {
            val field = type.getField("VOICE")
            require(field.type == String::class.java &&
                java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                java.lang.reflect.Modifier.isFinal(field.modifiers)) {
                "${type.simpleName}.VOICE must be a constant String"
            }
            val voice = field.get(null) as String
            require(voice.isNotBlank()) { "${type.simpleName}.VOICE must not be blank" }
            return listOf(voice)
        }
        val enum = getter.returnType
        return requireNotNull(enum.enumConstants) { "${type.simpleName}.voice is not an enum" }.map { (it as Enum<*>).name }
    }

    /** Every engine tag, one per [Patch] type. */
    val engines: Set<String> by lazy {
        val tags = all.map(::engineOf)
        require(tags.size == tags.toSet().size) { "two Patch types share an ENGINE tag: $tags" }
        tags.toSet()
    }

    /**
     * The engines with a [Patch] type but no preset roster in [Presets]: SNAP reads its
     * table off a photo, SILK, GYRE and MAGNET have no factory
     * presets. Named here so a new engine has to be either rostered or listed. SCUFF
     * sat here from its Kotlin port until the owner heard its kits (2026-11-13),
     * CLUTTER likewise until the owner approved its kits' audition (2026-11-14); TERRA
     * until the owner chose to promote it and kept its classic kit (2026-11-14). EMBER
     * sat here from its Kotlin port (round 1) until the owner approved its five kits (2026-11-16, "All sound great")
     * and their pads became its factory bank. CALABASH sat here from its Kotlin
     * port (round 1, 2026-11-15) until the owner approved its kits on the shelf (2026-11-15) and they became its
     * factory bank, as CLUTTER's did. SERVO sat here from its port until the owner approved its kits (2026-11-15)
     * and their hits became its factory bank. RIDDIM sat here from its port (round 2a, 2026-11-18) until the owner
     * accepted its six kits on the phone and the MPC (2026-11-19) and their RIDDIM pads became its factory bank.
     */
    val ROSTERLESS: Set<String> = setOf(HydroformPatch.ENGINE, SnapPatch.ENGINE, SilkPatch.ENGINE, GyrePatch.ENGINE, MagnetPatch.ENGINE)

    /**
     * The engines with a [Patch] type and provisional recipes of their own that are
     * deliberately kept out of [Presets] until the owner's listening verdict.
     * Named here so leaving it out is a recorded decision, not a forgotten line:
     * accepting the engine means filing its recipes in [Presets] and taking it off this list. ETCH sat here from
     * its Kotlin port (K1) to round K2 (2026-11-14), when its heard round-3 settings became the factory bank. KINDRED sat
     * here from its port (K1, 2026-11-15) until the owner approved its Kotlin kits (2026-11-18). CONSORT joined at its
     * Kotlin port (K1, 2026-11-19) until the owner heard and kept all of round K3 (2026-11-20, "All sounds good").
     */
    val PROTOTYPES: Set<String> = setOf(SecondlinePatch.ENGINE, ChitinPatch.ENGINE, FerryPatch.ENGINE, FillPatch.ENGINE, BreakerPatch.ENGINE, SootPatch.ENGINE)

    /** These voices of otherwise rostered engines await a factory-preset listening verdict. */
    val PROTOTYPE_VOICES: Map<String, Set<String>> = mapOf(
        HalobarPatch.ENGINE to setOf(HalobarVoice.GLOCKENSPIEL.name,HalobarVoice.CROTALES.name,HalobarVoice.TUBULAR_BELLS.name),
        VentPatch.ENGINE to setOf(VentVoice.ROUND.name, VentVoice.WIRE.name, VentVoice.TEAR.name),
        ThumpPatch.ENGINE to setOf(ThumpVoice.SPARK.name),
        // TERRA's retired membrane: its last pad moved to TUN (2026-11-08), so it has no factory preset.
        TerraPatch.ENGINE to setOf(TerraVoice.COMPOUND_MEMBRANE.name),
    )
}
