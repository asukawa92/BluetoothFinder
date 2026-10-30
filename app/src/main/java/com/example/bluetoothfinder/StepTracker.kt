package com.example.bluetoothfinder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf

class StepTracker(context: Context) : SensorEventListener {
    val steps = mutableIntStateOf(0)
    val lastStepAt = mutableLongStateOf(0L)
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    fun start(): Boolean {
        steps.intValue = 0
        lastStepAt.longValue = 0L
        return sensor != null && manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }
    fun stop() { manager.unregisterListener(this) }
    override fun onSensorChanged(event: SensorEvent) { lastStepAt.longValue = SystemClock.elapsedRealtime(); steps.intValue++ }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
