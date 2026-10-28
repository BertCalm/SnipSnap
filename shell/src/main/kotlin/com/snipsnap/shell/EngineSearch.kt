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
 *    and ignores spaces round it. An empty query matches everything. The aim is to
 *    find a sound, so an engine matches through any of four things:
 *      - its **name**, with the query anywhere in it;
 *      - any of its **voice names**, with the query anywhere in it;
 *      - its catalogue **description**, with the query at the start of a word;
 *      - the **label of a category** it is tagged with, likewise at the start of a word.
 *
 *    Names and voices are matched as substrings because they are short and a half-typed
 *    word (`ell` for BELLOWS) is how they are looked for. Descriptions are sentences,
 *    where a substring would find "hat" in "that" and answer a search for hats with
 *    half the catalogue, so there the query must begin a word. A word begins after
 *    anything that is not a letter or a digit, which makes the second half of
 *    "analog-style" a word.
 *  - **The chips** ([categories]) are an OR: an engine passes when the catalogue tags
 *    it with any selected category. None selected means no filter. An engine the
 *    catalogue does not know passes only while no chip is on, and can then be found
 *    only through its name and voices; a convention law keeps that from being a real
 *    picker engine.
 *  - The two combine with AND, and the result is A to Z by display name
 *    (`name.replace('_', ' ')`), so SCANNED CHAIN comes before SKIN.
 */
fun filterEngines(
    names: List<String>,
    voicesOf: (String) -> List<String>,
    query: String,
    categories: Set<EngineCategory>,
): List<String> {
    val wanted = wanted(query)
    return names.distinct()
        .filter { name ->
            val entry = EngineCatalog.entry(name)
            val inChip = categories.isEmpty() || entry?.categories?.any { it in categories } == true
            inChip && (wanted.isEmpty() || matches(name, entry, voicesOf, wanted))
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.replace('_', ' ') })
}

/** Whether [wanted] (already folded) finds this engine through its name, voices, description or category labels. */
private fun matches(name: String, entry: EngineEntry?, voicesOf: (String) -> List<String>, wanted: String): Boolean =
    wanted in fold(name) ||
        voicesOf(name).any { wanted in fold(it) } ||
        (entry != null && (startsAWord(fold(entry.description), wanted) || entry.categories.any { startsAWord(fold(it.label), wanted) }))

/** Whether [wanted] occurs in [text] beginning a word: at the start, or after anything that is not a letter or a digit. */
private fun startsAWord(text: String, wanted: String): Boolean {
    var from = 0
    while (true) {
        val at = text.indexOf(wanted, from)
        if (at < 0) return false
        if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
        from = at + 1
    }
}

/**
 * The quiet line under an engine's row: its first [shown] voice names, underscores read
 * as spaces and joined with " · ", then " +n" for the n it left out. Empty for no voices.
 * Four fit a phone's row without wrapping for the voice names the picker has, and the
 * count says there is more behind them, which is what the search finds.
 *
 * With a [query], the voices it matches come first, in their own order, ahead of the
 * rest in theirs. THUMP found by "bell" through its COWBELL, the seventh of nine voices,
 * would otherwise show the reason for the hit only inside the "+5".
 */
fun engineVoicesLine(voices: List<String>, shown: Int = 4, query: String = ""): String {
    val names = voices.map { it.replace('_', ' ') }
    val wanted = wanted(query)
    val ordered = if (wanted.isEmpty()) names else names.filter { wanted in fold(it) } + names.filterNot { wanted in fold(it) }
    val head = ordered.take(shown).joinToString(" · ")
    return if (ordered.size > shown) "$head +${ordered.size - shown}" else head
}

/** A name or voice as the query is compared with it: lower case, underscores read as spaces. */
private fun fold(s: String): String = s.replace('_', ' ').lowercase()

/** The query as it is compared: folded, runs of spaces made one, trimmed. Empty for a blank query. */
private fun wanted(query: String): String = fold(query).replace(RUN_OF_SPACES, " ").trim()

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
