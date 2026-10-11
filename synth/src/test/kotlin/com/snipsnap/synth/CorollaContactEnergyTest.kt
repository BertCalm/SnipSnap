package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertTrue

/** Contact must exchange stored work inside the petals, without a passive source or a gain trick. */
class CorollaContactEnergyTest {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    private fun collision(contact: Float): Corolla.Taps = requireNotNull(Corolla.play(
        CorollaVoice.CHATTER,
        mapOf("TUNE" to .5f, "PULL" to .85f, "BLOOM" to 0f, "FIELD" to 0f,
            "CONTACT" to contact, "CHAMBER" to 0f),
        probe = Corolla.Probe(record = true, coupling = false, chamber = false,
            powered = false, opening = false),
        seconds = .15f,
    ).taps)

    @Test
    fun `passive contact stores and returns elastic work without a hidden source`() {
        val clear = collision(0f)
        val touching = collision(.9f)
        assertTrue(clear.contactEnergy.all { it == 0f }, "CONTACT zero stores contact energy")
        assertTrue(touching.poweredInput.all { it == 0f }, "passive impact receives powered work")
        assertTrue(touching.contactEnergy.all { it.isFinite() && it >= 0f })

        // The displacement ramp has ended before this window. Fixed geometry isolates
        // compliant contact from opening work, chamber loading and powered maintenance.
        val start = (rate * .006).toInt()
        val end = touching.energy.size
        val peakStored = touching.contactEnergy.drop(start).max()
        assertTrue(peakStored > 1e-5f, "strong contact stores no measurable elastic work")
        for (i in start + 1 until end) {
            assertTrue(touching.energy[i] <= touching.energy[i - 1] + 1e-7f,
                "total passive energy grows at frame $i")
        }

        var returned = 0.0
        for (i in start + 1 until end) {
            val contactChange = touching.contactEnergy[i] - touching.contactEnergy[i - 1]
            val modalBefore = touching.energy[i - 1] - touching.contactEnergy[i - 1]
            val modalAfter = touching.energy[i] - touching.contactEnergy[i]
            if (contactChange < 0f && modalAfter > modalBefore) {
                returned += (modalAfter - modalBefore).toDouble()
            }
        }
        assertTrue(returned > peakStored * .05,
            "contact potential is only discarded, not returned to the petals: $returned / $peakStored")

        val correction = touching.passiveCorrection.drop(start).sumOf { it.toDouble() }
        val releasedEnergy = touching.energy[start].toDouble()
        assertTrue(correction < releasedEnergy * .10,
            "energy guard replaces impact dynamics with damping: $correction / $releasedEnergy")
        println("COROLLA CONTACT: stored peak $peakStored, returned $returned, " +
            "guard correction $correction / released $releasedEnergy")
    }
}
