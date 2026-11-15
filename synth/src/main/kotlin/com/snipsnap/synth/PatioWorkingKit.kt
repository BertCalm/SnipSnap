package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Selected source set and per-pad Chewed renders. Shared-bus audition stays separate. */
object PatioWorkingKit {
    const val VERSION = 3
    const val NAME = "SnipSnap Patio Chewed"
    // -3 dB mix trim, separate from source/treatment so pad character is unchanged.
    const val SCRAPE_MIX_TRIM = .7079458f
    fun level(r: PatioStudy.Recipe): Float = r.mixGain *
        (if (r.id == "scrape-short" || r.id == "scrape-long") SCRAPE_MIX_TRIM else 1f)
    val treatment get() = PatioTreatments.ALL.single { it.id == "chewed" }
    fun name(r: PatioStudy.Recipe): String = when (r.id) {
        "scrape-short" -> "Guacharaca Return"
        "scrape-long" -> "Guacharaca Forward"
        else -> r.name
    }
    fun source(r: PatioStudy.Recipe): String = when (r.id) {
        in PatioCore.IDS -> "PatioCore version 1; exact patch in working manifest"
        "alegre-open", "alegre-slap" -> "PatioHand reference version 1; default settings"
        "scrape-short" -> "PatioScrape version 3 RETURN; default settings"
        "scrape-long" -> "PatioScrape version 3 FORWARD; default settings"
        "guache" -> "PatioCharacter version 1 SEEDS; default settings"
        "marimba" -> "PatioCharacter version 1 BUZZ_BAR; default settings"
        else -> "PatioStudy version 1 source patch"
    }
    fun dry(r: PatioStudy.Recipe): Snip = when (r.id) {
        in PatioCore.IDS -> PatioCore.patch(r).render()
        "alegre-open" -> PatioHand.render(PatioHand.Stroke.OPEN, reference = true)
        "alegre-slap" -> PatioHand.render(PatioHand.Stroke.SLAP, reference = true)
        "scrape-short" -> PatioScrape.render(PatioScrape.Direction.RETURN, modelVersion = 3)
        "scrape-long" -> PatioScrape.render(PatioScrape.Direction.FORWARD, modelVersion = 3)
        "guache" -> PatioCharacter.render(PatioCharacter.Voice.SEEDS)
        "marimba" -> PatioCharacter.render(PatioCharacter.Voice.BUZZ_BAR)
        else -> r.dry()
    }
    fun render(r: PatioStudy.Recipe): Snip = PatioTreatments.process(dry(r), treatment, r.seed)
}
