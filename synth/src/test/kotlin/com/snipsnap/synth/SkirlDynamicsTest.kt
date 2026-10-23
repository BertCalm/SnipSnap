package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SkirlDynamicsTest {
    private fun degrees(x: Double) = x*PI/180
    private fun rawRoll() = SkirlDynamics.Parameters(degrees(22.85),.00958946638440411,degrees(7.0))

    @Test
    fun `conservative cylinder matches an independently derived exact steady motion`() {
        for (angle in doubleArrayOf(8.0,22.85,35.0)) {
            val p = SkirlDynamics.Parameters(degrees(angle),0.0,degrees(angle*.5),maxSeconds=.25)
            val trace = SkirlDynamics.solve(p)
            assertEquals(SkirlDynamics.Exit.HORIZON,trace.exit)
            val alpha = p.leanAlphaRad
            val s = sin(alpha); val c = cos(alpha)
            val axis = .5*p.mass*p.radius*p.radius
            val transverse = p.mass*(3*p.radius*p.radius+p.thickness*p.thickness)/12
            val b = p.radius*c-p.thickness*.5*s
            val precession = sqrt(p.mass*p.gravity*b/(s*(transverse*c-axis*p.thickness*.5/p.radius*s)))
            val sigma = -precession*b/p.radius
            // R(t)=Rz(precession*t) Ry(alpha) Rz(sigma*t): independent exact orientation.
            for (index in trace.times.indices step 17) {
                val time = trace.times[index]
                val azimuth = precession*time
                val spin = sigma*time
                val expectedN = doubleArrayOf(s*cos(azimuth),s*sin(azimuth),c)
                val expectedX = doubleArrayOf(c*cos(spin)*cos(azimuth)-sin(spin)*sin(azimuth),
                    c*cos(spin)*sin(azimuth)+sin(spin)*cos(azimuth),-s*cos(spin))
                val q0=trace.quaternions[4*index]; val q1=trace.quaternions[4*index+1]
                val q2=trace.quaternions[4*index+2]; val q3=trace.quaternions[4*index+3]
                val actualN=doubleArrayOf(2*(q1*q3+q0*q2),2*(q2*q3-q0*q1),1-2*(q1*q1+q2*q2))
                val actualX=doubleArrayOf(1-2*(q2*q2+q3*q3),2*(q1*q2+q0*q3),2*(q1*q3-q0*q2))
                for (j in 0..2) {
                    assertEquals(expectedN[j],actualN[j],2e-7,"axis $angle $time")
                    assertEquals(expectedX[j],actualX[j],2e-7,"marker $angle $time")
                    val expectedOmega=sigma*expectedN[j]+if(j==2) precession else 0.0
                    assertEquals(expectedOmega,trace.angularVelocities[3*index+j],2e-5,"omega $angle $time")
                }
                assertEquals(alpha,trace.alphas[index],2e-8)
                assertEquals(trace.mechanicalEnergy[0],trace.mechanicalEnergy[index],1e-8)
                assertEquals(0.0,trace.resistanceWork[index])
            }
            assertTrue(trace.diagnostics.maxQuaternionError<1e-7)
        }
    }

    @Test
    fun `dissipative motion agrees with independent DOP853 fixtures including invalid original defaults`() {
        val resource=checkNotNull(javaClass.getResourceAsStream("/skirl/rolling-reference.csv"))
        val lines=resource.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith('#') }
        for (line in lines) {
            val f=line.split(',')
            val p=SkirlDynamics.Parameters(degrees(f[1].toDouble()),f[2].toDouble(),degrees(f[3].toDouble()))
            val trace=SkirlDynamics.solve(p)
            assertEquals(SkirlDynamics.Exit.valueOf(f[4]),trace.exit,f[0])
            assertEquals(f[5].toDouble(),trace.duration,3e-6,"${f[0]} onset")
            assertEquals(f[6].toDouble(),trace.contactPhases.last(),5e-4,"${f[0]} contact phase")
            assertEquals(f[7].toDouble(),trace.rimPhases.last(),5e-4,"${f[0]} rim phase")
            assertEquals(f[8].toDouble(),trace.normals.last(),3e-4,"${f[0]} normal")
            assertEquals(f[9].toDouble(),trace.mechanicalEnergy.last(),3e-8,"${f[0]} energy")
            assertEquals(f[10].toDouble(),trace.resistanceWork.last(),3e-8,"${f[0]} work")
            assertEquals(f[11].toDouble(),trace.omegaAxis.last(),3e-5,"${f[0]} axial omega")
            assertTrue(trace.diagnostics.minNormal>0.0)
            assertTrue(trace.diagnostics.maxFriction<=p.staticFriction+1e-7)
            assertTrue(trace.diagnostics.relativeEnergyError<2e-6)
            assertTrue(trace.diagnostics.minResistancePower>=0.0)
            assertTrue(trace.diagnostics.maxHeightVelocityError<1e-12)
            assertTrue(!trace.diagnostics.velocityProjectionUsed)
            if (f[0]=="raw_failure") assertEquals(SkirlDynamics.Reason.STATIC_FRICTION,trace.reason)
        }
    }

    @Test
    fun `tighter error controls converge without passive energy correction`() {
        val p=rawRoll().copy(relativeTolerance=2e-7,absoluteTolerance=2e-9,maxStep=.008,descriptorRate=250.0)
        val coarse=SkirlDynamics.solve(p)
        val medium=SkirlDynamics.solve(p.copy(relativeTolerance=2e-9,absoluteTolerance=2e-11,maxStep=.004))
        val fine=SkirlDynamics.solve(p.copy(relativeTolerance=2e-11,absoluteTolerance=2e-13,maxStep=.002))
        val reference=3.0881631291511757
        assertTrue(abs(fine.duration-reference)<3e-7,"fine onset ${fine.duration}")
        assertTrue(abs(fine.contactPhases.last()-184.88794873385817)<5e-5)
        assertTrue(fine.diagnostics.relativeEnergyError<coarse.diagnostics.relativeEnergyError,
            "work error did not improve: ${coarse.diagnostics.relativeEnergyError} -> ${fine.diagnostics.relativeEnergyError}")
        assertTrue(abs(fine.duration-medium.duration)<3e-6)
        assertTrue(fine.diagnostics.relativeEnergyError<1e-7)
    }

    @Test
    fun `one musical time scale changes all rates and event times together`() {
        val p=rawRoll()
        val original=SkirlDynamics.solve(p)
        val faster=SkirlDynamics.solve(p.copy(timeScale=2.0))
        assertEquals(original.duration*.5,faster.duration,3e-6)
        assertEquals(original.contactPhases.last(),faster.contactPhases.last(),5e-4)
        assertEquals(original.rimPhases.last(),faster.rimPhases.last(),5e-4)
        assertEquals(original.resistanceWork.last(),faster.resistanceWork.last(),3e-8)
        assertEquals(original.resistancePower.last(),faster.resistancePower.last(),3e-6)
        assertEquals(original.diagnostics.endContactHz*2,faster.diagnostics.endContactHz,3e-4)
        assertTrue(abs(original.contactPhases.last()-original.bodyMarkerPhases.last())>100)
        assertTrue(abs(original.rimPhases.last()-original.contactPhases.last())>1)
        assertTrue(abs(original.omegaAxis.last()/TWO_PI-original.diagnostics.endContactHz)>1)
    }

    @Test
    fun `deterministic descriptor cache and numerical limits preserve distinct exits`() {
        val p=rawRoll()
        SkirlDynamics.clearCache()
        val first=SkirlDynamics.trace(p)
        assertSame(first,SkirlDynamics.trace(p))
        SkirlDynamics.clearCache()
        val repeated=SkirlDynamics.trace(p)
        assertContentEquals(first.times,repeated.times)
        assertContentEquals(first.contactPhases,repeated.contactPhases)
        assertContentEquals(first.rimPhases,repeated.rimPhases)
        assertContentEquals(first.mechanicalEnergy,repeated.mechanicalEnergy)
        assertEquals(first.diagnostics,repeated.diagnostics)
        val horizon=SkirlDynamics.solve(p.copy(maxSeconds=.01))
        assertEquals(SkirlDynamics.Exit.HORIZON,horizon.exit)
        assertEquals(SkirlDynamics.Reason.HORIZON,horizon.reason)
        val capped=SkirlDynamics.solve(p.copy(maxEvaluations=7))
        assertEquals(SkirlDynamics.Exit.FAILURE,capped.exit)
        assertEquals(SkirlDynamics.Reason.SOLVER_LIMIT,capped.reason)
        val tinyStep=SkirlDynamics.solve(p.copy(maxStep=1e-14))
        assertEquals(SkirlDynamics.Exit.FAILURE,tinyStep.exit)
        assertEquals(SkirlDynamics.Reason.SOLVER_LIMIT,tinyStep.reason)
        assertEquals(0.0,tinyStep.duration)
        val tooFast=SkirlDynamics.solve(p.copy(maxContactHz=1.0))
        assertEquals(SkirlDynamics.Exit.FAILURE,tooFast.exit)
        assertEquals(SkirlDynamics.Reason.RATE_LIMIT,tooFast.reason)
        assertEquals(0.0,tooFast.duration)
    }

    @Test
    fun `production launch and late exit domains complete with an independent friction margin`() {
        // Latest COLLAPSE is the demanding exit; earlier settings cut this same physical path.
        // Add off-grid pairs so this guard is not limited to the calibration lattice.
        val pairs=mutableListOf<Pair<Float,Float>>()
        for (lean in floatArrayOf(0f,.5f,1f)) for (brake in floatArrayOf(0f,.5f,1f)) pairs += lean to brake
        pairs += .13f to .77f
        pairs += .71f to .33f
        for (voice in SkirlVoice.entries) for ((lean,brake) in pairs) {
            val p=Skirl.parametersFor(voice,mapOf("LEAN" to lean,"BRAKE" to brake,"COLLAPSE" to 1f))
            val trace=SkirlDynamics.solve(p)
            assertEquals(SkirlDynamics.Exit.TERMINAL,trace.exit,"$voice $lean $brake ${trace.reason}")
            assertTrue(trace.diagnostics.maxFriction<.72,"$voice $lean $brake ${trace.diagnostics.maxFriction}")
            assertTrue(trace.diagnostics.minNormal>2.7)
            assertTrue(trace.diagnostics.relativeEnergyError<1e-6)
            assertTrue(trace.duration in 1.7..5.6,"$voice $lean $brake ${trace.duration}")
            assertEquals(p.terminalAlphaRad,trace.alphas.last(),1e-9)
        }
        for (voice in SkirlVoice.entries) for (collapse in floatArrayOf(0f,.5f,1f)) {
            val p=Skirl.parametersFor(voice,mapOf("COLLAPSE" to collapse))
            val trace=SkirlDynamics.solve(p)
            assertEquals(SkirlDynamics.Exit.TERMINAL,trace.exit)
            assertEquals(p.terminalAlphaRad,trace.alphas.last(),1e-9)
        }
    }

    @Test
    fun `interruption cancels instead of caching a partial mechanical trace`() {
        SkirlDynamics.clearCache()
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { SkirlDynamics.trace(rawRoll()) }
        } finally {
            Thread.interrupted()
        }
        assertEquals(SkirlDynamics.Exit.TERMINAL,SkirlDynamics.trace(rawRoll()).exit)
    }

    private companion object { const val TWO_PI=2.0*PI }
}
