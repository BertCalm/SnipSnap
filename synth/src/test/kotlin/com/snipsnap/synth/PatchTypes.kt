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

    /** The names of every voice a [type] can be built with, from the enum its `voice` property holds. */
    fun voicesOf(type: Class<out Patch>): List<String> {
        val enum = type.getMethod("getVoice").returnType
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
     * deliberately kept out of [Presets] until the owner's listening verdict. VENT's
     * six provisional recipes span two models in [VentPresets] and render through the CLI;
     * its factory roster and phone picker stay unchanged while it is a prototype.
     * OVERLAP's first finite comparisons likewise stay in its audition generator.
     * WICK keeps its provisional starting points in its own preset object and
     * renders through the CLI while awaiting that verdict.
     * Named here so leaving it out is a recorded decision, not a forgotten line:
     * accepting the engine means filing its recipes in [Presets] and taking it off this list.
     */
    val PROTOTYPES: Set<String> = setOf(VentPatch.ENGINE, OverlapPatch.ENGINE, WickPatch.ENGINE)
}
