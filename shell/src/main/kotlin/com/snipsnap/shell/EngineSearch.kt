package com.snipsnap.shell

/**
 * What the SYNTH engine picker lists for a query and a set of chips: pure, so the
 * screen draws it and `EngineCatalogTest` decides it.
 *
 *  - [names] are the picker's `Engine.name` values, underscore and all. [voicesOf]
 *    gives the voice enum names of one of them (`HAT_CLOSED`), which is why
 *    "hat" finds ONSET: a person searching for a sound does not know which engine
 *    owns it.
 *  - **The query** is case-insensitive, reads `_` and a space as the same character
 *    and ignores spaces round it. An engine matches when the query is inside its name
 *    or inside any of its voice names. An empty query matches everything. The
 *    descriptions are not searched; they say what an engine is for in words the
 *    voice names do not repeat, and a search through them would answer "kick" with
 *    engines that only mention one.
 *  - **The chips** ([categories]) are an OR: an engine passes when the catalogue tags
 *    it with any selected category. None selected means no filter. An engine the
 *    catalogue does not know passes only while no chip is on; a convention law keeps
 *    that from being a real picker engine.
 *  - The two combine with AND, and the result is A to Z by display name
 *    (`name.replace('_', ' ')`), so SCANNED CHAIN comes before SKIN.
 */
fun filterEngines(
    names: List<String>,
    voicesOf: (String) -> List<String>,
    query: String,
    categories: Set<EngineCategory>,
): List<String> {
    val wanted = fold(query).replace(RUN_OF_SPACES, " ").trim()
    return names.distinct()
        .filter { name ->
            val inChip = categories.isEmpty() ||
                EngineCatalog.entry(name)?.categories?.any { it in categories } == true
            inChip && (wanted.isEmpty() || wanted in fold(name) || voicesOf(name).any { wanted in fold(it) })
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.replace('_', ' ') })
}

/** A name or voice as the query is compared with it: lower case, underscores read as spaces. */
private fun fold(s: String): String = s.replace('_', ' ').lowercase()

private val RUN_OF_SPACES = Regex("""\s+""")

/**
 * The engines the player picked most recently, newest first, with no repeats and no
 * more than [max]. Plain state, not a snapshot state: a screen that shows it reads
 * [list] afresh each time it composes, and keeps its own state to say when to.
 *
 * [encode] is what is saved (the names, comma-separated, newest first) and [decode]
 * is what reads it back. A name that [decode]'s caller no longer has is dropped there
 * and so never saved again: a build that retires an engine does not leave it in
 * everyone's history.
 */
class RecentEngines(private val max: Int = DEFAULT_MAX) {
    init {
        require(max > 0) { "a history of $max engines remembers nothing" }
    }

    private val items = ArrayList<String>()

    /** Records that [name] was just picked: it goes to the front, once, and the oldest fall off. A blank name, or one that holds the separator, is not recorded. */
    fun used(name: String) {
        if (name.isBlank() || SEPARATOR in name) return
        items.remove(name)
        items.add(0, name)
        while (items.size > max) items.removeAt(items.lastIndex)
    }

    /** The engines, newest first: a copy, which a later [used] does not change. */
    val list: List<String> get() = items.toList()

    /** The history as saved: [list], comma-separated. */
    fun encode(): String = items.joinToString(SEPARATOR.toString())

    companion object {
        /** How many engines the picker's RECENT section holds. */
        const val DEFAULT_MAX = 5

        private const val SEPARATOR = ','

        /**
         * The history [encode] wrote, as it stands now: names not in [valid] are
         * dropped, repeats and anything past [max] too. Null, blank and noise give an
         * empty history rather than an error, because a saved string that cannot be
         * read is no reason to stop SYNTH opening.
         */
        fun decode(s: String?, valid: Set<String>, max: Int = DEFAULT_MAX): RecentEngines {
            val history = RecentEngines(max)
            // Oldest first, so that each use pushes the one before it back and the newest ends in front.
            s.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it in valid }.distinct().take(max).reversed()
                .forEach(history::used)
            return history
        }
    }
}
