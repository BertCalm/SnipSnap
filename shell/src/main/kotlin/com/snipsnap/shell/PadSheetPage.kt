package com.snipsnap.shell

/**
 * The pages the pad editor draws, one composable and three benches: the editor itself, and the two
 * screens its `EFFECTS ▸` and `VARIATIONS ▸` doors open. [bench] is where each sits in
 * [Navigation], so Back and the back chip come from the model like every other screen's.
 */
enum class PadSheetPage(val bench: Bench) {
    EDITOR(Bench.PAD_SHEET),
    EFFECTS(Bench.PAD_EFFECTS),
    VARIATIONS(Bench.PAD_VARIATIONS),
    ;

    companion object {
        /** The page a bench draws, or null for a bench that is not one of the editor's pages. */
        fun of(bench: Bench?): PadSheetPage? = entries.firstOrNull { it.bench == bench }
    }
}
