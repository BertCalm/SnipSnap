package com.snipsnap.synth

import java.util.Random

/**
 * The GLINT factory roster's candidates (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2):
 * sixteen per voice, whose values are a stratified spread and not a choice. Each varied macro takes sixteen
 * evenly spaced levels, `i / 15`, exactly once across the sixteen candidates, in an order shuffled per macro
 * by a recorded seed, so both ends of every range are heard, the ugly end included. DEPTH's levels are scaled
 * to 0 through [DEPTH_MAX], never 1: full DEPTH is the same bare sine on every voice. Nothing here comes from
 * anyone's taste. TUNE is not varied (a pad overrides it per zone) and neither is DECAY (a held pad ignores
 * it); both stay at their defaults, as does any macro this does not name.
 *
 * The owner hears the clips ([GlintRosterAuditionGenerator]) and replies with the numbers to keep; those
 * candidates' values become the roster. The numbering is therefore a contract with whatever was sent: change
 * [SEED], the draw or the order of [varied], and "SWEEP-07" names a different sound.
 */
internal object GlintRosterCandidates {

    const val COUNT = 16

    /** DEPTH's top level. 1 is a bare sine on every voice, so it would be the same candidate four times. */
    const val DEPTH_MAX = 0.9f

    /**
     * The recorded seed, the date the spread was drawn. A voice draws from `Random(SEED + voice.ordinal)`, one
     * shuffle per macro in [varied]'s order, so the voices that share their macros still draw differently.
     */
    const val SEED = 20261002L

    class Candidate(val number: Int, val macros: Map<String, Float>)

    /** The macros whose levels are spread, in the order their shuffles are drawn. VOWEL has no FOLLOW. */
    fun varied(voice: GlintVoice): List<String> =
        if (voice == GlintVoice.VOWEL) listOf("PEAK", "BODY", "BLOOM", "DEPTH")
        else listOf("PEAK", "FOLLOW", "BODY", "BLOOM", "DEPTH")

    /** The sixteen candidates for [voice], numbered 1 to [COUNT]. Every macro of the voice is set. */
    fun forVoice(voice: GlintVoice): List<Candidate> {
        val random = Random(SEED + voice.ordinal)
        val orders = varied(voice).associateWith { shuffled(random) }
        val defaults = Glint.defaults(voice)
        return (0 until COUNT).map { i ->
            val macros = defaults.toMutableMap()
            for ((name, order) in orders) {
                val level = order[i] / (COUNT - 1).toFloat()
                macros[name] = if (name == "DEPTH") level * DEPTH_MAX else level
            }
            Candidate(i + 1, macros)
        }
    }

    /**
     * Fisher-Yates over 0 until [COUNT], from [random] alone. `java.util.Random`'s sequence is specified, so a
     * seed means the same order on every JVM; the stdlib's own shuffle makes no such promise.
     */
    private fun shuffled(random: Random): IntArray {
        val order = IntArray(COUNT) { it }
        for (i in COUNT - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val held = order[i]
            order[i] = order[j]
            order[j] = held
        }
        return order
    }
}
