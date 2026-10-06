package com.snipsnap.synth

import kotlin.math.sqrt

/**
 * Shared machinery for every `<Engine>PresetsTest` — one copy, not seven.
 * Originally lived duplicated inside `ThumpPresetsTest`; pulled out here
 * once six more engines needed the same blocklist and spread check, so the
 * naming rule and the distance metric are each defined in exactly one
 * place (`docs/SYNTH_UPGRADE.md`'s own recurring-defect shape is one
 * quantity computed twice — this is that shape, avoided).
 */
internal object PresetTestSupport {

    /**
     * `docs/SYNTH_ROADMAP.md`'s guardrail: no trademarked names or model
     * numbers, "not obvious near-misses of them" either — so the model
     * numbers match as bare substrings, deliberately unbounded. A `\b`
     * word-boundary version looks stricter but is actually a hole: `\b`
     * only fires at a transition between a word character and a
     * non-word one, and a digit run into a following letter never makes
     * that transition, so `\b808\b` fails to match inside `808ISH` or
     * `909CORE` — exactly the near-miss shape the rule forbids, sliding
     * through the guard meant to catch it. Plain substrings close that
     * hole and still catch the hyphenated and spaced forms for free.
     */
    val trademarkBlocklist = Regex(
        """(?i)(808|909|606|707|727|626|636|637|303)""" +
            """|roland|akai|yamaha|korg|native\s*instruments|elektron""" +
            """|linn(drum)?|oberheim|simmons|emu|e-mu|fairlight""" +
            """|tr-?\d{3}|cr-?78|sp-?1200|sp-?12|dmx|acid""" +
            // TIDE's style has makers too (docs/SYNTH_ROADMAP.md, S9).
            """|buchla|serge|make\s*noise""" +
            // SIREN's inspiration is a maker too (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md).
            """|benidub""" +
            // FORK's brief named the electric piano makers directly
            // (docs/superpowers/specs/2026-09-27-fork-electric-piano-engine-design.md).
            """|rhodes|wurlitzer|fender""" +
            // BORE's brief named the tape keyboard and the woodwind makers directly
            // (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md). "mello" alone would
            // also refuse the word "mellow", which is a plain description and not a maker, so the
            // lookahead lets "mellow" through and nothing that spells the keyboard.
            """|mello(?!w)|heckel|chamberlin|selmer|yanagisawa""" +
            // ARCO's brief named the string-machine makers directly
            // (docs/superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md). The arp term is
            // word-bounded on purpose: a bare "arp" would also refuse HARP, SHARP and WARP (sixteen shipped
            // names), the same trap "mello" has with "mellow". A word start before a letter fires at a space or
            // the start of the name (the hole above is a digit running into a letter, which this is not), and the
            // lookahead lets "arpeggio" through.
            """|solina|eminent|string\s*ensemble|\barp(?!eggi)""" +
            // MAGNET's amp and speaker: "rectifier" is a near-miss of an amplifier line and "oxford" a
            // speaker maker (docs/superpowers/specs/2026-09-29-magnet-valve-design.md, "The name").
            """|rectifier|oxford""",
    )

    /**
     * RMS distance over the macro vector (every voice's macros are 0..1, so
     * this is dimension-agnostic). A list that clusters — twelve names for
     * one sound — is the likeliest way a preset pass goes bad quietly: it
     * looks complete and plays dull.
     */
    fun rmsDistance(a: Map<String, Float>, b: Map<String, Float>): Float {
        val keys = a.keys
        val sumSq = keys.sumOf { k -> val d = (a.getValue(k) - b.getValue(k)).toDouble(); d * d }
        return sqrt(sumSq / keys.size).toFloat()
    }
}
