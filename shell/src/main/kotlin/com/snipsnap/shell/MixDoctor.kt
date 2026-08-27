package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Features
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import kotlin.math.abs
import kotlin.math.exp

/**
 * The Mix Doctor — preflight's musical sibling: it checks the *sound*,
 * not the format. Every finding comes from a real measurement and names
 * the pads and the numbers; [fix] applies only the safe subset (a sub
 * carve that hands the low end to its owner, a level trim, a mute-group
 * assignment, a DC/rumble high-pass — all bin-backed or metadata-only).
 * Taste calls like a bright kit stay advice.
 */
object MixDoctor {

    enum class Code { LOW_MASKING, CLASHING_HATS, HARSH_BUILDUP, LOUD_OUTLIER, DC_RUMBLE }

    data class Finding(
        val code: Code,
        val slots: List<Int>,
        val message: String,
        /** True when [fix] knows a safe treatment; false = advice only. */
        val fixable: Boolean,
    )

    // ---- the thresholds, measured not vibed --------------------------------

    /** Both pads at or past this share of low-band energy fight for the sub. */
    const val LOW_HEAVY = 0.45f

    /** ...and both must be busy: a one-shot kick masks nothing for long. */
    const val LOW_BUSY_SEC = 0.35f

    /** A pad at or past this share of top-band energy reads as bright. */
    const val BRIGHT = 0.55f

    /** This many bright pads is a buildup, not a hat. */
    const val BRIGHT_COUNT = 3

    /**
     * Effective loudness past this multiple of the kit's median screams.
     * Set from the bench: a chopped break's slices legitimately spread to
     * ~3x (a ringing open hat against a low-cut kick), so the bar sits at
     * 4x (~+12 dB) — a pad far louder on purpose, not a lively slice.
     */
    const val OUTLIER_RATIO = 4f

    /** Mean sample offset past this is DC the speaker pays for. */
    const val DC_OFFSET = 0.02f

    /** Where a carved pad hands the sub back to its owner. */
    const val CARVE_HZ = 120f

    private data class Measured(
        val slot: Int,
        val features: Features,
        /** Perceived loudness × the pad's level — what the mix actually hears. */
        val effective: Float,
        val dc: Float,
    )

    fun examine(kit: Kit, kitDir: File): List<Finding> {
        val measured = kit.pads.sortedBy { it.slot }.mapNotNull { pad ->
            val f = File(kitDir, pad.sampleFile)
            if (!f.isFile) return@mapNotNull null
            val snip = WavReader.read(f)
            var mean = 0.0
            for (s in snip.samples) mean += s
            Measured(
                slot = pad.slot,
                features = FeatureExtractor.extract(snip),
                effective = Loudness.of(snip) * pad.level,
                dc = abs(mean / snip.samples.size.coerceAtLeast(1)).toFloat(),
            )
        }
        val findings = mutableListOf<Finding>()

        // Two busy low-heavy pads mask each other in the sub.
        val lowBusy = measured.filter {
            it.features.lowRatio >= LOW_HEAVY && it.features.durationSeconds >= LOW_BUSY_SEC
        }
        for (i in lowBusy.indices) {
            for (j in i + 1 until lowBusy.size) {
                val a = lowBusy[i]
                val b = lowBusy[j]
                findings += Finding(
                    Code.LOW_MASKING, listOf(a.slot, b.slot),
                    "%s and %s fight for the sub (%.0f%% and %.0f%% of their energy below 200 Hz, both sustained) - carve one".format(
                        label(a.slot), label(b.slot), a.features.lowRatio * 100, b.features.lowRatio * 100,
                    ),
                    fixable = true,
                )
            }
        }

        // Open and closed hats outside one mute group never choke.
        val hats = kit.pads.filter { it.drumClass == DrumClass.HAT_CLOSED || it.drumClass == DrumClass.HAT_OPEN }
        if (hats.size >= 2 && !(hats.all { it.muteGroup != 0 } && hats.map { it.muteGroup }.toSet().size == 1)) {
            findings += Finding(
                Code.CLASHING_HATS, hats.map { it.slot },
                "hats on ${hats.joinToString(", ") { label(it.slot) }} don't share a mute group - they'll ring over each other",
                fixable = true,
            )
        }

        // A pile of bright pads is a buildup - but only where brightness
        // isn't the pad's job: hats, snares, claps and percussion are
        // SUPPOSED to live up top, so they never count against the kit.
        val brightByTrade = setOf(
            DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.PERC,
        )
        val padClass = kit.pads.associate { it.slot to it.drumClass }
        val bright = measured.filter {
            it.features.highRatio >= BRIGHT && padClass[it.slot] !in brightByTrade
        }
        if (bright.size >= BRIGHT_COUNT) {
            findings += Finding(
                Code.HARSH_BUILDUP, bright.map { it.slot },
                "${bright.size} pads put most of their energy above 2 kHz (${bright.joinToString(", ") { label(it.slot) }}) - " +
                    "the top end will pile up; taste call, so no auto-fix",
                fixable = false,
            )
        }

        // One pad far louder than the kit's median flattens everything else.
        val loudnesses = measured.map { it.effective }.filter { it > 0f }.sorted()
        if (loudnesses.size >= 3) {
            val median = loudnesses[loudnesses.size / 2]
            for (m in measured) {
                if (median > 0f && m.effective > median * OUTLIER_RATIO) {
                    findings += Finding(
                        Code.LOUD_OUTLIER, listOf(m.slot),
                        "%s is %.1fx the kit's median loudness - trim its level".format(label(m.slot), m.effective / median),
                        fixable = true,
                    )
                }
            }
        }

        // DC offset: energy the speaker pays for and nobody hears.
        for (m in measured) {
            if (m.dc > DC_OFFSET) {
                findings += Finding(
                    Code.DC_RUMBLE, listOf(m.slot),
                    "%s carries a DC offset of %.3f - high-pass it".format(label(m.slot), m.dc),
                    fixable = true,
                )
            }
        }

        // Deterministic report order: by code, then first pad.
        return findings.sortedWith(compareBy({ it.code.ordinal }, { it.slots.first() }))
    }

    /**
     * Apply the safe subset and return what was actually fixed. The
     * caller owns [KitBuilderModel.save].
     */
    fun fix(model: KitBuilderModel): List<Finding> {
        val findings = examine(model.kit, model.kitDir)
        val fixed = mutableListOf<Finding>()
        val carved = mutableSetOf<Int>()

        for (finding in findings.filter { it.fixable }) {
            when (finding.code) {
                Code.LOW_MASKING -> {
                    // Carve the *less* low-committed of the pair: the boomier
                    // pad is usually the one meant to own the sub.
                    val pick = finding.slots.minByOrNull { slot ->
                        FeatureExtractor.extract(
                            WavReader.read(File(model.kitDir, model.pad(slot)!!.sampleFile)),
                        ).lowRatio
                    }!!
                    if (pick in carved || model.pad(pick)!!.velocityLayers.isNotEmpty()) continue
                    // A shelf can't un-sub a sub-heavy pad; ownership can.
                    // The boomier pad keeps the sub, this one leaves it.
                    model.replaceAudio(
                        pick,
                        JsonValue.Obj(
                            linkedMapOf(
                                "doctor" to JsonValue.Str("sub-carve"),
                                "highpassHz" to JsonValue.Num(CARVE_HZ.toDouble()),
                            ),
                        ),
                    ) { snip -> highpass(snip, CARVE_HZ) }
                    carved += pick
                    fixed += finding
                }
                Code.CLASHING_HATS -> {
                    val group = finding.slots.mapNotNull { model.pad(it)?.muteGroup }.firstOrNull { it != 0 }
                        ?: ((model.kit.pads.maxOfOrNull { it.muteGroup } ?: 0) + 1).coerceAtMost(32)
                    finding.slots.forEach { slot -> model.update(slot) { it.copy(muteGroup = group) } }
                    fixed += finding
                }
                Code.LOUD_OUTLIER -> {
                    val slot = finding.slots.single()
                    val pad = model.pad(slot)!!
                    val effective = Loudness.of(WavReader.read(File(model.kitDir, pad.sampleFile))) * pad.level
                    val medians = model.kit.pads.mapNotNull { p ->
                        val f = File(model.kitDir, p.sampleFile)
                        if (f.isFile) Loudness.of(WavReader.read(f)) * p.level else null
                    }.filter { it > 0f }.sorted()
                    val median = medians[medians.size / 2]
                    // Land it a touch above the median - loud pads are loud on purpose.
                    val target = (pad.level * (median * 1.2f / effective)).coerceIn(0.05f, 1f)
                    model.update(slot) { it.copy(level = target) }
                    fixed += finding
                }
                Code.DC_RUMBLE -> {
                    val slot = finding.slots.single()
                    if (model.pad(slot)!!.velocityLayers.isNotEmpty()) continue
                    model.replaceAudio(
                        slot,
                        JsonValue.Obj(linkedMapOf("doctor" to JsonValue.Str("dc-rumble-highpass"))),
                    ) { snip -> highpassDc(snip) }
                    fixed += finding
                }
                Code.HARSH_BUILDUP -> Unit // never fixable; here for exhaustiveness
            }
        }
        return fixed
    }

    /** Remove DC and sub-audible rumble: mean subtraction + a 25 Hz one-pole HP. */
    internal fun highpassDc(snip: Snip): Snip {
        val out = FloatArray(snip.samples.size)
        for (c in 0 until snip.channels) {
            var mean = 0.0
            for (f in 0 until snip.frameCount) mean += snip.samples[f * snip.channels + c]
            val dc = (mean / snip.frameCount.coerceAtLeast(1)).toFloat()
            val a = (1.0 - exp(-2.0 * Math.PI * 25.0 / snip.sampleRate)).toFloat()
            var lp = 0f
            for (f in 0 until snip.frameCount) {
                val i = f * snip.channels + c
                val s = snip.samples[i] - dc
                lp += a * (s - lp)
                out[i] = s - lp
            }
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** A real high-pass (two cascaded one-poles, ~12 dB/oct) — the carve. */
    internal fun highpass(snip: Snip, cutoffHz: Float, stages: Int = 2): Snip {
        var current = snip.samples.copyOf()
        val a = (1.0 - exp(-2.0 * Math.PI * cutoffHz / snip.sampleRate)).toFloat()
        repeat(stages) {
            val out = FloatArray(current.size)
            for (c in 0 until snip.channels) {
                var lp = 0f
                for (f in 0 until snip.frameCount) {
                    val i = f * snip.channels + c
                    lp += a * (current[i] - lp)
                    out[i] = current[i] - lp
                }
            }
            current = out
        }
        return Snip(current, snip.channels, snip.sampleRate)
    }

    private fun label(slot: Int): String = PadNoteMap.labelForPad(slot)
}
