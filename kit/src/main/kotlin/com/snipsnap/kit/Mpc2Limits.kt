package com.snipsnap.kit

/**
 * What MPC 2's `.xpm` cannot hold, said before the export rather than found on the MPC.
 *
 * A drum pad there picks its layers one way: Layer Play is Cycle (1, 2, 3, 4, 1...), Velocity or
 * Random (Akai, MPC Software 2.7 user guide, "Layer Play"). Cycle takes no account of how hard the pad
 * is hit, and Simultaneous Play fires its linked pads at every velocity, so no arrangement of pads and
 * layers both switches by velocity and deals takes. A velocity × round-robin grid therefore goes out
 * as its velocity layers, each its zone's first take ([com.snipsnap.xpm.ChainPlay]). MPC 3 native
 * keeps the whole grid. The owner recorded this as a format limit on 2026-11-14
 * (docs/ENGINE_ACCEPTANCE_GATES.md, "MPC 2 and round robin").
 *
 * A plain robin chain, one zone, is not affected: the `.xpm` cycles its takes as `_RR` files
 * ([RobinSplit]).
 */
object Mpc2Limits {

    /** True for the formats whose program is MPC 2's `.xpm`: the folder, the expansion and the `.xpn`. */
    fun writesXpm(format: ExportFormat): Boolean =
        format == ExportFormat.PROGRAM_FOLDER || format == ExportFormat.EXPANSION || format == ExportFormat.XPN

    /** [kit]'s velocity × round-robin pads (a grid with a zone that deals more than one take), in slot order. */
    fun gridPads(kit: Kit): List<KitPad> =
        kit.pads.filter { p -> p.chain?.zones?.any { it.cycle > 1 } == true }.sortedBy { it.slot }

    /** The note an export of [kit] as [format] carries, or null when every pad goes out whole. */
    fun note(format: ExportFormat, kit: Kit): Finding? {
        if (!writesXpm(format)) return null
        val n = gridPads(kit).size
        if (n == 0) return null
        val pads = if (n == 1) "1 pad keeps its velocity layers" else "$n pads keep their velocity layers"
        return Finding(
            Severity.WARN,
            "MPC 2 holds velocity layers or round robin on a pad, not both: $pads, one take each. " +
                "MPC 3 native keeps both.",
        )
    }
}
