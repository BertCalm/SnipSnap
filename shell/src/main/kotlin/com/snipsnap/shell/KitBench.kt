package com.snipsnap.shell

/**
 * What a bench that was opened for one kit does when it arrives: VERSIONS,
 * PAD CAPTURE, SONG and the pad editor with its three tools (PAD SHEET, GRAIN
 * FIELD, SPLICE, STACK) each work on one kit, and the history can bring any of
 * them back after the user has opened a different one. The app asks once per
 * arrival (the bench opened, Back restored it, or the open kit changed or
 * appeared or went away) and acts on the answer, so the user is never shown
 * one kit's versions, one kit's empty pad, one kit's groove or one kit's pad
 * over another kit.
 *
 * A pure function of what the app knows, so every case is pinned on the JVM
 * ([KitBenchTest]). The app's `beatMustStop` reads the same verdict for SONG,
 * so the beat is stopped only for a SONG that will draw.
 */
object KitBench {

    /** What the bench does on arrival. */
    enum class Verdict {
        /** The kit it was opened for is the open kit: draw the screen. */
        DRAW,

        /** It no longer fits: draw nothing and close. */
        CLOSE,

        /** No kit is open and this bench says so in a panel instead of closing. SONG only. */
        NO_KIT,
    }

    /**
     * The benches that work on one filled pad of one kit: the pad editor and
     * the three tools it opens over itself. They share the editor's kit and
     * slot, so they share its verdict.
     */
    val PAD_BENCHES: Set<Bench> = setOf(Bench.PAD_SHEET, Bench.GRAIN_FIELD, Bench.SPLICE, Bench.STACK)

    /**
     * The verdict for [bench] on arrival.
     *
     * [openedFor] is the identity of the kit the bench was opened for (the app
     * passes the kit folder's path, recorded when it opened), [openNow] that of
     * the open kit, each null when there is none. A kit keeps its folder through
     * a restore of a version and through a capture, so the folder is the identity
     * that holds while the screen does its own work.
     *
     * [slotFilled] is about the one pad the bench was opened on: true when it
     * holds a sample now, false when it is empty, null when no pad was recorded.
     * PAD CAPTURE fills one empty pad, so a filled or unrecorded pad closes it:
     * the capture would replace a sample without asking. The [PAD_BENCHES] are
     * the inverse, they work on a pad that is there, so an empty or unrecorded
     * pad closes them. VERSIONS and SONG ignore it.
     *
     * With no open kit, SONG answers [Verdict.NO_KIT] whatever it was opened for
     * (it shows a panel saying why), and the others close.
     */
    fun verdict(bench: Bench, openedFor: String?, openNow: String?, slotFilled: Boolean? = null): Verdict {
        require(bench == Bench.VERSIONS || bench == Bench.PAD_CAPTURE || bench == Bench.SONG || bench in PAD_BENCHES) {
            "$bench is not opened for a kit"
        }
        if (openNow == null) return if (bench == Bench.SONG) Verdict.NO_KIT else Verdict.CLOSE
        if (openedFor == null || openedFor != openNow) return Verdict.CLOSE
        if (bench == Bench.PAD_CAPTURE && slotFilled != false) return Verdict.CLOSE
        if (bench in PAD_BENCHES && slotFilled != true) return Verdict.CLOSE
        return Verdict.DRAW
    }
}
