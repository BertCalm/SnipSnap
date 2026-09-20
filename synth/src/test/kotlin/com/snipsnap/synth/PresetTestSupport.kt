package com.snipsnap.synth

/**
 * Shared machinery for every `<Engine>PresetsTest` — one copy, not seven.
 * Originally lived duplicated inside `ThumpPresetsTest`; pulled out here
 * once six more engines needed the same blocklist, so the naming rule is
 * defined in exactly one place (`docs/SYNTH_UPGRADE.md`'s own
 * recurring-defect shape is one quantity computed twice — this is that
 * shape, avoided).
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
            """|tr-?\d{3}|cr-?78|sp-?1200|sp-?12|dmx|acid""",
    )
}
