package com.snipsnap.shell

import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import kotlin.math.*

/** SPLINTER's delivery: [BreakcoreDelivery] with its groove, its hat choke and its approved-source notes. */
object SplinterDelivery {
    const val CELLS = 162

    /** Eight bars at 200 BPM that use every pad: break, slices, the gabber bar, the rush,
     * then the stutter, the riser and the landing. Slots are 1-based pads. */
    fun groove(): Mpc3Clip {
        val notes = mutableListOf<Mpc3Note>()
        fun hit(slot: Int, beat: Double, v: Float) { notes += Mpc3Note(Mpc3Note.noteFor(slot), (beat * 960).roundToLong(), v) }
        fun breakBar(b: Double, ride: Boolean) {
            hit(1, b, 1f); hit(1, b + .5, .7f); hit(3, b + 1, 1f); hit(4, b + 1.75, .4f); hit(4, b + 2.25, .35f)
            hit(1, b + 2.5, .85f); hit(1, b + 2.75, .6f); hit(3, b + 3, .95f); hit(4, b + 3.75, .4f)
            for (i in 0..7) hit(if (ride) 9 else 7, b + i * .5, if (i % 2 == 0) .7f else .45f)
        }
        breakBar(0.0, true)
        hit(11, 4.0, 1f); hit(12, 5.0, 1f); hit(11, 6.0, .8f); hit(12, 7.0, .9f); hit(14, 7.5, .7f); hit(5, 6.5, .7f)
        for (i in 0..7) hit(7, 6 + i * .25, if (i % 2 == 0) .65f else .4f)
        for (i in 0..2) { hit(2, 8.0 + i, 1f); hit(8, 8.5 + i, .6f) }
        hit(6, 9.0, .6f); hit(15, 10.0, .8f); hit(10, 11.0, .8f); hit(10, 11.5, .7f)
        hit(16, 12.0, 1f); hit(17, 12.0, .85f)
        hit(1, 16.0, 1f); hit(13, 16.0, .9f); hit(1, 19.0, .9f); hit(3, 19.5, .9f)
        breakBar(20.0, false)
        hit(18, 24.0, .9f); for (i in 0..3) hit(8, 24.5 + i, .5f)
        hit(2, 28.0, 1f); hit(16, 28.0, .8f); hit(3, 29.0, 1f); hit(11, 30.0, 1f); hit(12, 31.0, 1f)
        return Mpc3Clip(Splinter.TITLE, 8, notes.sortedBy { it.timePulses })
    }

    private val spec by lazy {
        BreakcoreDelivery.Spec(Splinter, "splinter", groove(), choke = 8 to 7, touchSlots = listOf(1, 3, 11),
            readme = "${Splinter.TITLE}: sample-free synthesis at ${Splinter.BPM} BPM. 18 pads, three velocities x three takes on every pad, including the snare rush and riser.\nKick and snare sources are the owner-approved Spore pair; the edits and the arrangement await listening.\nNative sourceRecipe metadata rerenders through Kotlin; not an app-editable synth patch. Native, SFZ and MPC 3 grids and PCM are software-verified. Phone and MPC playback unverified. No MPC 2 export.\n",
            status = "approved kick/snare sources; edits and arrangement awaiting listen",
            manifestExtra = mapOf("kickModelSha256" to SporeKick.MODEL_SHA256, "snareModelSha256" to SporeSnare.MODEL_SHA256))
    }
    fun generate(root: File) = BreakcoreDelivery.generate(spec, root)
    @JvmStatic fun main(args: Array<String>) = generate(File(args.first()))
}
