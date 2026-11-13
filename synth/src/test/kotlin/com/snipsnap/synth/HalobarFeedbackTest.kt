package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HalobarFeedbackTest {
    @Test fun `guarded feedback preserves stored Float and full Double peak against reference tanh`() {
        val random=Random(773)
        var differentDoubles=0
        repeat(300_000) {
            val x=random.nextDouble(-.0625,.0625)
            val fed=random.nextDouble(-.02,.02)
            val gain=random.nextDouble(.12,.84)
            val drive=random.nextDouble(1.0,3.5)
            val reference=fed+gain*tanh(x)/drive
            val peak=if(random.nextBoolean())1.0 else abs(reference)*.5
            val actual=Halobar.feedbackSampleProbe(fed,gain,drive,x,peak)
            assertEquals(reference.toFloat().toRawBits(),actual.toFloat().toRawBits())
            assertEquals(max(peak,abs(reference)).toRawBits(),max(peak,abs(actual)).toRawBits())
            if(reference.toRawBits()!=actual.toRawBits())differentDoubles++
        }
        assertTrue(differentDoubles>100,"The test must exercise the candidate path as well as reference fallbacks")
    }

    @Test fun `Float midpoint and zero cancellation retain the exact reference Double`() {
        val random=Random(453)
        repeat(5_000) { i ->
            val x=random.nextDouble(.001,.0625)*(if(i%2==0)1 else -1)
            val low=random.nextFloat()*.01f+.005f
            val high=Float.fromBits(low.toRawBits()+1)
            val midpoint=(low.toDouble()+high.toDouble())*.5*(if(i%2==0)1 else -1)
            for(target in listOf(midpoint,0.0)) {
                val fed=target-.444*tanh(x)/2.125
                val reference=fed+.444*tanh(x)/2.125
                assertEquals(reference.toRawBits(),Halobar.feedbackSampleProbe(fed,.444,2.125,x,1.0).toRawBits())
            }
        }
    }

    @Test fun `outside the bounded domain feedback stays on the reference calculation`() {
        for(x in listOf(-0.0,0.0,1e-110,-1e-110,.0625001,-.0625001,1.0,-1.0,Double.POSITIVE_INFINITY,Double.NaN)) {
            val expected=.01+.44*tanh(x)/2.125
            assertEquals(expected.toRawBits(),Halobar.feedbackSampleProbe(.01,.44,2.125,x,1.0).toRawBits())
        }
        for((gain,drive) in listOf(-.1 to 2.0,1.01 to 2.0,.5 to .9,.5 to 4.1)) {
            val expected=.01+gain*tanh(.02)/drive
            assertEquals(expected.toRawBits(),Halobar.feedbackSampleProbe(.01,gain,drive,.02,1.0).toRawBits())
        }
    }

    @Test fun `a possible new peak retains the exact reference Double`() {
        val random=Random(601)
        repeat(10_000) {
            val x=random.nextDouble(-.0625,.0625)
            val fed=random.nextDouble(-.02,.02)
            val reference=fed+.444*tanh(x)/2.125
            for(peak in listOf(abs(reference),Math.nextUp(abs(reference)),Math.nextDown(abs(reference)))) {
                assertEquals(reference.toRawBits(),Halobar.feedbackSampleProbe(fed,.444,2.125,x,peak).toRawBits())
            }
        }
    }
}
