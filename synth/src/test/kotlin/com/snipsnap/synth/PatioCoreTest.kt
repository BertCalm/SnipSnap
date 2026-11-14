package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class PatioCoreTest {
    @Test fun `core patches replay and their changes survive chewed level matching`() {
        for (r in PatioStudy.recipes().filter { it.id in PatioCore.IDS }) {
            val patch = PatioCore.patch(r)
            val replay = Patches.fromJsonValue(patch.toJsonValue())
            assertContentEquals(patch.render().samples, replay.render().samples)
            val old = AuditionLevel.level(PatioTreatments.process(r.dry(), PatioWorkingKit.treatment, r.seed)).samples
            val new = AuditionLevel.level(PatioWorkingKit.render(r)).samples
            assertTrue(new.all { it.isFinite() }); assertTrue(new.maxOf { abs(it) } <= 1f)
            val n = min(old.size,new.size)
            val difference = sqrt((0 until n).sumOf { (old[it]-new[it]).toDouble().pow(2) }/n)
            assertTrue(difference > .003, r.id)
        }
    }
    @Test fun `kick tightens while snare gains wire duration`() {
        fun time90(x: FloatArray): Double {
            val total = x.sumOf { it.toDouble().pow(2) }; var e = 0.0
            for (i in x.indices) { e += x[i].toDouble().pow(2); if(e>=total*.9) return i.toDouble()/Dsp.RATE }
            error("silent")
        }
        for (id in listOf("kick","caixa")) {
            val r=PatioStudy.recipes().single { it.id==id }
            val before=time90(PatioTreatments.process(r.dry(),PatioWorkingKit.treatment,r.seed).samples)
            val after=time90(PatioWorkingKit.render(r).samples)
            println("PATIO CORE $id energy90 before=$before after=$after")
            assertTrue(if(id=="kick") after<before else after>before)
        }
    }
}
