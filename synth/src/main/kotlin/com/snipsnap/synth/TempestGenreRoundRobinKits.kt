package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * Opt-in round robin for the three kept TEMPEST genre kits ([TempestGenreKits]): CIRCUIT TAKES,
 * FOUNDRY TAKES and STEP TAKES, each the same sixteen pads in the same order with [TAKES] takes,
 * dealt in turn on every hit. It follows TEMPEST ROUND ROBIN ([TempestRoundRobinKits]) exactly:
 * one touch, so each pad is a single-zone chain of takes; take 0 is the kept genre pad, sample
 * for sample; a later take is a fresh render of the same recipe that re-rolls only what an
 * analog drum machine leaves to chance between strokes ([Tempest.Takes]). Pitch, envelopes,
 * filters, drive, the repeats and the output stage are untouched, and every hat keeps mute group 1.
 *
 * The takes are renderer takes 0, 2, 5 and 8 ([STRIKES], TEMPEST ROUND ROBIN's own), screened
 * per pad as TEMPEST ROUND ROBIN's were: every take keeps the kept onset within 0.25 ms, stays
 * within 2 dB of the kept hit's spectral shape over the bands within 30 dB of its strongest, and
 * keeps its 90%-energy time within 0.7 to 1.4 times the kept one. [OVERRIDES] lists a pad's own
 * takes where it failed that screen. The pads these kits share with the main kit (CLAP START,
 * HAT CLOSED, HAT OPEN, HAT NOISE, BURR, TRAIL and CLAP SPREAD) keep [STRIKES], so their takes are
 * the bytes the owner already kept in tempest-rr-1.
 *
 * The one exception to "no pitch change" ([DRIFT_PADS]): CIRCUIT's WIRE and STEP's STEP SUB sit at
 * SOURCE 0, where the source has no noise and no free-running partials, so every renderer take is
 * bit-identical to take 0. Their later takes instead drift the oscillator by [DRIFT_CENTS]
 * ([Tempest.drifted]); take 0 is untouched. The owner chose this on 18 Nov 2026.
 *
 * Pads carry no PadRecipe (a single-hit recipe cannot regenerate four takes); the kept recipe rides
 * in the pad's source as JSON beside the take rule. The genre starters are unchanged.
 */
object TempestGenreRoundRobinKits {
    enum class Kit(val id: String, val displayName: String, val source: TempestGenreKits.Kit) {
        CIRCUIT("tempest-circuit-round-robin", "CIRCUIT TAKES", TempestGenreKits.Kit.CIRCUIT),
        FOUNDRY("tempest-foundry-round-robin", "FOUNDRY TAKES", TempestGenreKits.Kit.FOUNDRY),
        STEP("tempest-step-round-robin", "STEP TAKES", TempestGenreKits.Kit.STEP),
    }

    const val VERSION = 1
    const val TAKES = TempestRoundRobinKits.TAKES

    /** The renderer take behind each kit take, in dealing order; the first is the kept hit. TEMPEST ROUND ROBIN's list. */
    val STRIKES: List<Int> = TempestRoundRobinKits.STRIKES

    /** A pad's own renderer takes, by pad name, where it failed the screen on [STRIKES]. */
    val OVERRIDES: Map<String, List<Int>> = linkedMapOf(
        "CIRCUIT BODY" to listOf(0, 3, 4, 5),
        "CIRCUIT TIGHT" to listOf(0, 1, 13, 14),
        "FOUNDRY CRACK" to listOf(0, 1, 3, 4),
        "STEP PEDAL" to listOf(0, 1, 3, 5),
    )

    /** The SOURCE-0 pads whose takes drift the oscillator instead: every renderer take of them is take 0's bytes. */
    val DRIFT_PADS: Set<String> = setOf("WIRE", "STEP SUB")

    /** Each take's oscillator drift on [DRIFT_PADS], in cents, in dealing order; take 0 is the kept hit. */
    val DRIFT_CENTS: List<Double> = listOf(0.0, 4.0, -4.0, 2.0)

    /** [patch]'s renderer takes, in dealing order. */
    fun strikesFor(patch: TempestPatch): List<Int> = OVERRIDES[patch.name] ?: STRIKES

    /** The drift take [take] of [patch] carries, in cents: 0 unless [patch] is one of [DRIFT_PADS]. */
    fun centsFor(patch: TempestPatch, take: Int): Double = if (patch.name in DRIFT_PADS) DRIFT_CENTS[take] else 0.0

    /** The finished take [take] of [patch]. Pure: the same arguments give the same bytes. */
    fun render(patch: TempestPatch, take: Int): Snip {
        require(take in 0 until TAKES)
        val strike = strikesFor(patch)[take]
        val cents = centsFor(patch, take)
        return if (cents == 0.0) Tempest.render(patch.voice, patch.macros, patch.model, strike)
            else Tempest.drifted(patch.voice, patch.macros, patch.model, strike, cents)
    }

    /** Every take of [patch], in dealing order. */
    fun takes(patch: TempestPatch): List<Snip> = (0 until TAKES).map { render(patch, it) }

    fun build(kit: Kit): List<ArrangedPad> {
        val patches = TempestGenreKits.patches(kit.source)
        val accepted = TempestPresets.all()
        val rows = patches.parallelStream().map { takes(it) }.toList()
        return patches.zip(rows).map { (patch, row) ->
            val first = row.first()
            val status = if (patch in accepted) "accepted" else "kept"
            val drift = patch.name in DRIFT_PADS
            ArrangedPad(
                snip = first,
                drumClass = Tempest.drumClassFor(first),
                displayName = patch.name,
                takes = row.drop(1),
                source = mapOf("engine" to "TEMPEST", "role" to patch.voice.name, "model" to patch.model.toString(),
                    "kit" to kit.source.displayName, "status" to status) +
                    (if (status == "accepted") mapOf("factoryPreset" to patch.name) else mapOf("round" to TempestGenreKits.KEPT_ROUND)) +
                    mapOf("recipe" to patch.toJsonText(),
                        "roundRobin" to if (drift) "$TAKES takes: take 1 is the kept hit, later takes drift the oscillator a few cents"
                            else "$TAKES takes: take 1 is the kept hit, later takes re-roll the noise and the machine's free-running details",
                        "rendererTakes" to strikesFor(patch).joinToString(","),
                        "roundRobinVersion" to VERSION.toString()) +
                    (if (drift) mapOf("driftCents" to DRIFT_CENTS.joinToString(",") { it.toString() }) else emptyMap()),
                muteGroup = if (patch.voice == TempestVoice.HAT) 1 else 0,
            )
        }
    }
}
