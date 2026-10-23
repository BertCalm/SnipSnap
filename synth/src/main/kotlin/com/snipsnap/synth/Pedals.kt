package com.snipsnap.synth

/**
 * Which side of the amp a pick sits on. The rack's order is fixed; a board
 * renders as one pass per side around the amp, the rack's own order inside
 * each pass (docs/superpowers/specs/2026-10-21-pedal-library-and-board-design.md,
 * decision 3). PRE is the board in front of the amp (a wah, a compressor),
 * AMP is the amp itself, POST is the amp's loop and what comes after the
 * speaker (modulation, delay, reverb).
 */
enum class PedalSide(val id: String) {
    PRE("pre"), AMP("amp"), POST("post");

    companion object {
        fun fromId(id: String): PedalSide = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown pedal side '$id' - one of: ${entries.joinToString(", ") { it.id }}")
    }
}

/**
 * The pedal library: the one place a pedal is declared. The pad sheet's
 * PEDALS row, the CLI `treat --pedals` form, the replay and the tests all
 * read this list; adding a pedal is one [Entry] here plus its listening
 * gate. A pedal that has not passed its gate stays [Entry.gated] false: it
 * draws no chip and is reachable from the CLI and saved kits, the pedal
 * board spec's standing rule for a section that fails its gate.
 *
 * An entry's [Entry.chain] is the character in [Treatments.EXTRA] (or the
 * seeded bank) by reference, so `treat <character>` and a pick of the same
 * key never disagree on what the pedal is. A board (an entry that sets
 * several sections: RIG) owns its own chain and its own key, and that key is
 * not a [Treatments] name.
 *
 * Nothing here is heard: the day-one entries are the two chips the owner
 * picked at the pedal board's round two (DRIVE, COMP), the WAH section
 * awaiting its gate, and RIG as the first board, authored as a hypothesis
 * for its own gate.
 */
object Pedals {

    /** One pedal or board in the library. */
    class Entry(
        /** The chip word (`WAH`), unique across every sheet row. */
        val word: String,
        /** The saved name of a pick of it (`wahed`); a character name for a pedal, its own for a board. */
        val key: String,
        /** What it sets, at full. */
        val chain: FxChain,
        /** Where it sits by default. */
        val side: PedalSide,
        /** Whether the user may flip it between PRE and POST. Never for the amp or a board. */
        val movable: Boolean,
        /** Passed its owner listen: draws a chip. */
        val gated: Boolean,
    ) {
        /** The sections the chain sets, in rack order. */
        val sections: List<String> = FxChain.SECTION_NAMES.filter { chain.section(it) != null }

        /** A board sets more than one section. */
        val isBoard: Boolean get() = sections.size > 1

        /** Whether the pre-amp end fade runs ahead of this entry's amp: its VALVE DRIVE is above VALVE's own. */
        val fade: Boolean get() = Treatments.needsPreAmpFade(chain)

        /** The sides a pick of this entry may sit on. */
        val sides: List<PedalSide>
            get() = when {
                side == PedalSide.AMP -> listOf(PedalSide.AMP)
                movable -> listOf(PedalSide.PRE, PedalSide.POST)
                else -> listOf(side)
            }

        /** The chain a pick at [amount] sets: [Treatments.chain]'s own law, so a one-pick board is exactly a chip. */
        fun chainAt(amount: Float): FxChain = when {
            amount <= 0f -> FxChain()
            amount >= 0.999f -> chain
            else -> Treatments.fade(chain, amount)
        }

        init {
            require(word.isNotBlank() && word == word.uppercase()) { "a pedal's word is one upper-case chip word, got '$word'" }
            require(key.isNotBlank() && key == key.lowercase()) { "a pedal's key is a lower-case saved name, got '$key'" }
            require(sections.isNotEmpty()) { "pedal $word sets no section" }
            require(!(side == PedalSide.AMP && movable)) { "the amp ($word) cannot move" }
            require(!(isBoard && movable)) { "a board ($word) cannot move" }
            require(sections.contains("valve") == (side == PedalSide.AMP)) {
                "$word sets ${sections.joinToString()} so its side must be ${if (sections.contains("valve")) "amp" else "pre or post"}, got ${side.id}"
            }
        }
    }

    /** Where a section's natural slot falls relative to the amp. */
    fun sideOf(section: String): PedalSide {
        val names = FxChain.SECTION_NAMES
        val valve = names.indexOf("valve")
        val at = names.indexOf(section)
        require(at >= 0) { "unknown fx section '$section'" }
        return when {
            at < valve -> PedalSide.PRE
            at == valve -> PedalSide.AMP
            else -> PedalSide.POST
        }
    }

    private fun character(name: String): FxChain = Treatments.chain(name, 1f)

    val entries: List<Entry> = listOf(
        // A drive pedal: VALVE at the top of its gain law. The amp: it cannot move. The owner's pick, round two.
        Entry("DRIVE", "driven", character("driven"), PedalSide.AMP, movable = false, gated = true),
        // A compressor pedal for now (SQUASH at its strongest): in front of the amp by convention, movable.
        Entry("COMP", "compressed", character("compressed"), PedalSide.PRE, movable = true, gated = true),
        // The WAH section's AUTO move: the first pedal on a board, movable (the owner heard both ways). Gate pending.
        Entry("WAH", "wahed", character("wahed"), PedalSide.PRE, movable = true, gated = false),
        // The first board: a compressor into the drive pedal into one slapback. A hypothesis for its own gate:
        // the brainstorm's RIG, values as the characters it is made of. It contains the amp, so it sits at AMP.
        Entry(
            "RIG", "rig",
            FxChain(
                squash = character("compressed").squash,
                valve = character("driven").valve,
                echo = character("slapback").echo,
            ),
            PedalSide.AMP, movable = false, gated = false,
        ),
    )

    init {
        require(entries.map { it.word }.distinct().size == entries.size) { "two pedals share a word" }
        require(entries.map { it.key }.distinct().size == entries.size) { "two pedals share a key" }
        for (e in entries) {
            if (!e.isBoard) require(e.key in Treatments.names) { "pedal ${e.word}'s key '${e.key}' is not a rack character" }
            else require(e.key !in Treatments.names) { "board ${e.word}'s key '${e.key}' collides with a rack character" }
        }
    }

    /** The entries that draw a chip, in library order. */
    fun gated(): List<Entry> = entries.filter { it.gated }

    /** The entry saved as [key], or an error naming the library. */
    fun byKey(key: String): Entry = entries.firstOrNull { it.key == key }
        ?: throw IllegalArgumentException("unknown pedal '$key' - the library has: ${entries.joinToString(", ") { it.key }}")

    /** The entry drawn as [word], or null. */
    fun byWord(word: String): Entry? = entries.firstOrNull { it.word == word }
}
