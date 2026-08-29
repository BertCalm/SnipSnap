package com.snipsnap.kit

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The kit escapes the MPC (JJ1): a kit folder as an `.sfz` instrument —
 * the open sampler format nearly every DAW and free sampler loads.
 *
 * Layout: `<Kit> SFZ/<Kit>.sfz` + `Samples/` beside it, samples copied
 * through the same sanitize-and-measure door every MPC export uses.
 * Pads land on keys 36 up (pad A01 = MIDI 36, the MPC 3's own map);
 * velocity layers become `lovel`/`hivel` ranges; mute groups become
 * `group`/`off_by` chokes.
 *
 * The happy surprise: SFZ has **native round robin**
 * (`seq_length`/`seq_position`), so chains and grids export *fully* —
 * every take a region windowing the one chain WAV via `offset`/`end`,
 * cycling exactly as the MPC 3 would. Richer than the MPC 2's own
 * fallback.
 *
 * Mappings that are approximations say so here, once: pad shape uses
 * the preview's own curves (attack ramps over `attack × 0.4s`; decay
 * scales the sample's length with sustain 0; cutoff maps 0..1
 * exponentially onto 20 Hz..20 kHz; resonance onto 0..12 dB), and
 * humanize rides `pitch_random`/`amp_random` at the GG4 scaling
 * (±5 cents, ±1.5 dB at full). Pan randomization has no SFZ v1
 * opcode and is honestly skipped. Deterministic: same kit, same text.
 */
object SfzWriter {

    /** Pad A01's MIDI key — the MPC 3 pad-note map's own base. */
    const val BASE_KEY = 36

    fun write(
        kit: Kit,
        kitDir: File,
        destRoot: File,
        overwrite: Boolean = false,
    ): File {
        val findings = Preflight.check(kit, kitDir)
        if (findings.blocked()) throw ExportBlockedException(findings)

        val dir = File(destRoot, "${kit.name} SFZ")
        if (dir.exists() && !overwrite) {
            throw IOException("destination already exists: $dir (pass overwrite=true to replace same-named files)")
        }
        dir.deleteRecursively()
        val (slots, _) = KitExporter.buildSlots(kit, kitDir, File(dir, "Samples"))

        val sb = StringBuilder()
        sb.append("// ").append(kit.name).append(" - written by SnipSnap\n")
        sb.append("// pad A01 = key ").append(BASE_KEY).append(", chromatic upward\n\n")
        sb.append("<control>\ndefault_path=Samples/\n\n")

        for (p in kit.pads.sortedBy { it.slot }) {
            val pad = slots[p.slot - 1] ?: continue
            sb.append("// ").append(padLabel(p.slot)).append(' ').append(p.displayName).append('\n')
            sb.append("<group>\n")
            sb.append("key=").append(BASE_KEY + p.slot - 1).append('\n')
            sb.append("loop_mode=").append(if (p.oneShot) "one_shot" else "no_loop").append('\n')
            db(p.level)?.let { sb.append("volume=").append(it).append('\n') }
            if (p.pan != 0.5f) {
                sb.append("pan=").append(num((p.pan - 0.5f) * 200f)).append('\n')
            }
            if (p.tuneCoarse != 0) sb.append("transpose=").append(p.tuneCoarse).append('\n')
            if (p.tuneFine != 0) sb.append("tune=").append(p.tuneFine).append('\n')
            if (p.muteGroup != 0) {
                sb.append("group=").append(p.muteGroup).append('\n')
                sb.append("off_by=").append(p.muteGroup).append('\n')
            }
            // The preview's own shape approximations, in SFZ's units.
            p.attack?.let { sb.append("ampeg_attack=").append(num(it * 0.4f)).append('\n') }
            p.decay?.let {
                val seconds = it * pad.frameCount / 44_100f
                sb.append("ampeg_decay=").append(num(seconds)).append('\n')
                sb.append("ampeg_sustain=0\n")
            }
            p.cutoff?.let {
                sb.append("fil_type=lpf_2p\n")
                sb.append("cutoff=").append(num(20f * Math.pow(10.0, 3.0 * it).toFloat())).append('\n')
            }
            p.resonance?.let { sb.append("resonance=").append(num(it * 12f)).append('\n') }
            p.humanize?.let {
                sb.append("pitch_random=").append(num(it * 5f)).append('\n')
                sb.append("amp_random=").append(num(it * 1.5f)).append('\n')
            }

            val chain = p.chain
            val gridZones = chain?.zones
            when {
                chain != null && gridZones != null -> {
                    // The full grid: every zone's takes as seq-cycled regions
                    // windowing the one chain WAV - real round robin.
                    for (z in gridZones) {
                        for (t in 0 until z.cycle) {
                            val w = chain.window(z.baseSlice + t, pad.frameCount)
                            sb.append("<region> sample=").append(pad.sampleName).append(".wav")
                                .append(" lovel=").append(z.velStart).append(" hivel=").append(z.velEnd)
                                .append(" seq_length=").append(z.cycle).append(" seq_position=").append(t + 1)
                                .append(" offset=").append(w.first).append(" end=").append(w.last)
                                .append('\n')
                        }
                    }
                }
                chain != null -> {
                    for (t in 0 until chain.cycle) {
                        val w = chain.window(t, pad.frameCount)
                        sb.append("<region> sample=").append(pad.sampleName).append(".wav")
                            .append(" seq_length=").append(chain.cycle).append(" seq_position=").append(t + 1)
                            .append(" offset=").append(w.first).append(" end=").append(w.last)
                            .append('\n')
                    }
                }
                pad.velocityLayers != null -> {
                    for (l in pad.velocityLayers) {
                        sb.append("<region> sample=").append(l.sampleName).append(".wav")
                            .append(" lovel=").append(l.velStart).append(" hivel=").append(l.velEnd)
                            .append('\n')
                    }
                }
                else -> {
                    sb.append("<region> sample=").append(pad.sampleName).append(".wav\n")
                }
            }
            sb.append('\n')
        }

        val file = File(dir, "${kit.name}.sfz")
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    /** Level → dB re the MPC's own unity (0.707946); null when at unity. */
    private fun db(level: Float): String? {
        if (level <= 0f) return "-96"
        val v = 20.0 * Math.log10(level / 0.707946)
        if (Math.abs(v) < 0.05) return null
        return num(v.toFloat())
    }

    private fun num(v: Float): String {
        val s = String.format(Locale.ROOT, "%.3f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun padLabel(slot: Int): String =
        "%c%02d".format('A' + (slot - 1) / 16, (slot - 1) % 16 + 1)
}
