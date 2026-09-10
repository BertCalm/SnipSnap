package com.snipsnap.kit

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The kit as a DecentSampler `.dspreset` (JJ2) — the free sampler's XML,
 * one more door out of the MPC world. Layout mirrors the SFZ export:
 * `<Kit> DecentSampler/<Kit>.dspreset` + `Samples/`.
 *
 * One `<group>` per pad — or per grid *zone*, since `seqLength` lives on
 * the group and zones may cycle differently. Chains cycle for real here
 * too: `seqMode="round_robin"` with per-sample `seqPosition` and
 * `start`/`end` windows into the one chain WAV. Velocity zones ride
 * `loVel`/`hiVel`; mute groups become tag chokes
 * (`tags`/`silencedByTags`); level, pan and tuning carry over; the amp
 * shape uses the preview's approximations. One-shot pads get a release
 * as long as the sample so note-off never clips them (the DS idiom for
 * drums). Filter shape and humanize have no per-sample DS home and are
 * honestly skipped. Deterministic: same kit, same text.
 */
object DecentSamplerWriter {

    /** Pad A01's MIDI key — same map as everywhere else. */
    const val BASE_KEY = 36

    fun write(
        kit: Kit,
        kitDir: File,
        destRoot: File,
        overwrite: Boolean = false,
    ): File {
        val findings = Preflight.check(kit, kitDir)
        if (findings.blocked()) throw ExportBlockedException(findings)

        val dir = File(destRoot, "${kit.name} DecentSampler")
        if (dir.exists() && !overwrite) {
            throw DestinationExists(dir)
        }
        dir.deleteRecursively()
        val (slots, _) = KitExporter.buildSlots(kit, kitDir, File(dir, "Samples"))

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!-- ").append(xml(kit.name)).append(" - written by SnipSnap; pad A01 = note ")
            .append(BASE_KEY).append(" -->\n")
        sb.append("<DecentSampler minVersion=\"1.6.0\">\n")
        sb.append("  <groups>\n")

        for (p in kit.pads.sortedBy { it.slot }) {
            val pad = slots[p.slot - 1] ?: continue
            val key = BASE_KEY + p.slot - 1
            val chain = p.chain
            val gridZones = chain?.zones

            fun groupOpen(seqLength: Int?) {
                sb.append("    <group")
                seqLength?.let { sb.append(" seqMode=\"round_robin\" seqLength=\"").append(it).append('"') }
                db(p.level)?.let { sb.append(" volume=\"").append(it).append("dB\"") }
                if (p.pan != 0.5f) sb.append(" pan=\"").append(num((p.pan - 0.5f) * 200f)).append('"')
                val tuning = p.tuneCoarse + p.tuneFine / 100f
                if (tuning != 0f) sb.append(" groupTuning=\"").append(num(tuning)).append('"')
                if (p.muteGroup != 0) {
                    sb.append(" tags=\"choke").append(p.muteGroup).append('"')
                    sb.append(" silencedByTags=\"choke").append(p.muteGroup).append('"')
                    sb.append(" silencingMode=\"fast\"")
                }
                p.attack?.let { sb.append(" attack=\"").append(num(it * 0.4f)).append('"') }
                p.decay?.let {
                    sb.append(" decay=\"").append(num(it * pad.frameCount / 44_100f)).append('"')
                    sb.append(" sustain=\"0\"")
                }
                if (p.oneShot) {
                    // Ring out the whole hit whatever the note length.
                    sb.append(" release=\"").append(num(pad.frameCount / 44_100f)).append('"')
                }
                sb.append(">\n")
            }

            fun sample(
                stem: String,
                loVel: Int,
                hiVel: Int,
                start: Long? = null,
                end: Long? = null,
                seqPosition: Int? = null,
            ) {
                sb.append("      <sample path=\"Samples/").append(xml(stem)).append(".wav\"")
                sb.append(" rootNote=\"").append(key).append("\" loNote=\"").append(key)
                    .append("\" hiNote=\"").append(key).append('"')
                if (loVel != 0 || hiVel != 127) {
                    sb.append(" loVel=\"").append(loVel).append("\" hiVel=\"").append(hiVel).append('"')
                }
                start?.let { sb.append(" start=\"").append(it).append('"') }
                end?.let { sb.append(" end=\"").append(it).append('"') }
                seqPosition?.let { sb.append(" seqPosition=\"").append(it).append('"') }
                sb.append("/>\n")
            }

            when {
                chain != null && gridZones != null -> {
                    // One group per zone: seqLength is a group attribute and
                    // zones may cycle differently.
                    for (z in gridZones) {
                        groupOpen(seqLength = z.cycle)
                        for (t in 0 until z.cycle) {
                            val w = chain.window(z.baseSlice + t, pad.frameCount)
                            sample(pad.sampleName, z.velStart, z.velEnd, w.first, w.last, t + 1)
                        }
                        sb.append("    </group>\n")
                    }
                }
                chain != null -> {
                    groupOpen(seqLength = chain.cycle)
                    for (t in 0 until chain.cycle) {
                        val w = chain.window(t, pad.frameCount)
                        sample(pad.sampleName, 0, 127, w.first, w.last, t + 1)
                    }
                    sb.append("    </group>\n")
                }
                else -> {
                    groupOpen(seqLength = null)
                    val layers = pad.velocityLayers
                    if (layers != null) {
                        for (l in layers) sample(l.sampleName, l.velStart, l.velEnd)
                    } else {
                        sample(pad.sampleName, 0, 127)
                    }
                    sb.append("    </group>\n")
                }
            }
        }

        sb.append("  </groups>\n")
        sb.append("</DecentSampler>\n")

        val file = File(dir, "${kit.name}.dspreset")
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    /** Level → dB re the MPC's unity (0.707946); null when at unity. */
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

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
