package com.snipsnap.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The phone's roll as a control: gravity along the device's X axis,
 * so flat is 0.5, a quarter turn left is 0 and right is 1. Gravity
 * rather than raw acceleration so a bump in the hand is not a knob
 * turn; the raw sensor is the fallback where no gravity sensor exists.
 *
 * [tilt] is read from the UI frame loop and written from the sensor
 * thread; a volatile float is enough for a single value nobody
 * compares-and-swaps.
 */
class TiltSource(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val available: Boolean get() = sensor != null

    @Volatile
    var tilt: Float = 0.5f
        private set

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
        tilt = 0.5f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values.getOrNull(0) ?: return
        // A sensor that reports NaN is reporting nothing: keep the last
        // reading. `coerceIn` would pass it on (NaN fails every comparison),
        // and TILT is a filter macro at the far end of this.
        if (!gx.isFinite()) return
        tilt = ((gx / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) * 0.5f + 0.5f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
