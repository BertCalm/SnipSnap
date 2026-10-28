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
     * table off a photo, TERRA, SILK, GYRE and MAGNET ship voices without factory
     * presets. Named here so a new engine has to be either rostered or listed.
     */
    val ROSTERLESS: Set<String> = setOf(SnapPatch.ENGINE, TerraPatch.ENGINE, SilkPatch.ENGINE, GyrePatch.ENGINE, MagnetPatch.ENGINE)

    /**
     * The engines with a [Patch] type and provisional recipes of their own that are
     * deliberately kept out of [Presets] until the owner's listening verdict.
     * WICK keeps its provisional starting points in its own preset object and
     * renders through the CLI while awaiting that verdict.
     * NACRE's finite PEARL duet likewise has provisional listening recipes only.
     * SCOUR's R1 voices carry one provisional default each until the first Kotlin listen.
     * Named here so leaving it out is a recorded decision, not a forgotten line:
     * accepting the engine means filing its recipes in [Presets] and taking it off this list.
     */
    val PROTOTYPES: Set<String> = setOf(CairnPatch.ENGINE, WickPatch.ENGINE, NacrePatch.ENGINE, FerryPatch.ENGINE, ScourPatch.ENGINE)

    /** These voices of otherwise rostered engines await a factory-preset listening verdict. */
    val PROTOTYPE_VOICES: Map<String, Set<String>> = mapOf(
        VentPatch.ENGINE to setOf(VentVoice.ROUND.name, VentVoice.WIRE.name, VentVoice.TEAR.name),
        ThumpPatch.ENGINE to setOf(ThumpVoice.SPARK.name),
    )
}
