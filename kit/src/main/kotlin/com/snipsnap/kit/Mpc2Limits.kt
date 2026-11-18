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
 *
 * A single-zone chain of takes that carries no robin settings (TEMPEST ROUND ROBIN's pads, CHOP's
 * FOLD DOUBLES) is not split that way: MPC 2 has no Slice Motion, so its `.xpm` windows the pad to
 * the first take ([com.snipsnap.xpm.ChainPlay.firstSliceEnd]) and the other takes never play there.
 * The note names those pads too. What the export writes is unchanged; only the note is new.
 */
object Mpc2Limits {

    /** True for the formats whose program is MPC 2's `.xpm`: the folder, the expansion and the `.xpn`. */
    fun writesXpm(format: ExportFormat): Boolean =
        format == ExportFormat.PROGRAM_FOLDER || format == ExportFormat.EXPANSION || format == ExportFormat.XPN

    /** [kit]'s velocity × round-robin pads (a grid with a zone that deals more than one take), in slot order. */
    fun gridPads(kit: Kit): List<KitPad> =
        kit.pads.filter { p -> p.chain?.zones?.any { it.cycle > 1 } == true }.sortedBy { it.slot }

    /**
     * [kit]'s single-zone take chains that MPC 2 plays as their first take only, in slot order: a chain
     * with no velocity grid and no robin settings (a robin chain goes out as `_RR` files instead).
     */
    fun firstTakePads(kit: Kit): List<KitPad> =
        kit.pads.filter { p -> p.chain?.let { it.zones == null } == true && p.robin == null }.sortedBy { it.slot }

    /** The note an export of [kit] as [format] carries, or null when every pad goes out whole. */
    fun note(format: ExportFormat, kit: Kit): Finding? {
        if (!writesXpm(format)) return null
        val n = gridPads(kit).size
        val m = firstTakePads(kit).size
        if (n == 0 && m == 0) return null
        val grid = if (n == 0) null else {
            val pads = if (n == 1) "1 pad keeps its velocity layers" else "$n pads keep their velocity layers"
            "MPC 2 holds velocity layers or round robin on a pad, not both: $pads, one take each. "
        }
        val chains = if (m == 0) null else {
            val pads = if (m == 1) "1 pad keeps its first take" else "$m pads keep their first take"
            "MPC 2 has no Slice Motion to deal a pad's takes: $pads only. "
        }
        val mpc3 = if (m == 0) "MPC 3 native keeps both." else if (n == 0) "MPC 3 native carries every take." else "MPC 3 native keeps both and carries every take."
        return Finding(Severity.WARN, listOfNotNull(grid, chains).joinToString("") + mpc3)
    }
}
