package com.snipsnap.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The phone's roll and pitch as a control: gravity along the device's X
 * and Y axes, so flat is 0.5 both ways, a quarter turn left/down is 0 and
 * right/up is 1. Gravity rather than raw acceleration so a bump in the
 * hand is not a knob turn; the raw sensor is the fallback where no
 * gravity sensor exists.
 *
 * [tilt] and [pitch] are read from the UI frame loop and written from the
 * sensor thread; a volatile float each is enough for a single value
 * nobody compares-and-swaps. The two axes are read and guarded
 * independently — a NaN or a missing reading on one must not also hold
 * the other back.
 */
class TiltSource(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val available: Boolean get() = sensor != null

    @Volatile
    var tilt: Float = 0.5f
        private set

    /** Roll's own companion axis, front-back — TILT's cursor on GRAIN FIELD reads both. */
    @Volatile
    var pitch: Float = 0.5f
        private set

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
        tilt = 0.5f
        pitch = 0.5f
    }

    override fun onSensorChanged(event: SensorEvent) {
        // A sensor that reports NaN, or doesn't report an axis at all, is
        // reporting nothing on that axis: keep its last reading.
        // `coerceIn` would pass a NaN on (it fails every comparison), and
        // TILT is a filter macro / a field cursor at the far end of this.
        val gx = event.values.getOrNull(0)
        if (gx != null && gx.isFinite()) {
            tilt = ((gx / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) * 0.5f + 0.5f)
        }
        val gy = event.values.getOrNull(1)
        if (gy != null && gy.isFinite()) {
            pitch = ((gy / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) * 0.5f + 0.5f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
