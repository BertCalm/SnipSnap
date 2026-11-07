package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Standalone octave room -> approved destruction core, with explicit saved clock and seed.
 * The public Snip between stages is Float32; parity replays that boundary separately from
 * the historical Python Float64 chain. Separate wet/core controls are not a board AMT law.
 * Why it is built this way: composing public processors keeps the accepted core unchanged,
 * preserves each stage's diagnostics and makes Off an exact core-only comparison.
 */
object Obliterate {
    const val MODEL = "obliterate-kotlin-r1"
    data class Settings(
        val shimmer: ObliterateShimmer.Settings = ObliterateShimmer.Settings(),
        val core: ObliterateCore.Settings = ObliterateCore.Settings(),
    )
    data class RenderResult(
        val audio: Snip,
        val settings: Settings,
        val shimmer: ObliterateShimmer.Diagnostics,
        val core: ObliterateCore.Diagnostics,
        val model: String = MODEL,
    )
    fun render(snip: Snip, settings: Settings = Settings()): RenderResult {
        val pre = ObliterateShimmer.render(snip, settings.shimmer)
        val core = ObliterateCore.render(pre.audio, settings.core)
        return RenderResult(core.audio, settings, pre.diagnostics, core.diagnostics)
    }
}
