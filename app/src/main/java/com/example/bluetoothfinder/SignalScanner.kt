package com.example.bluetoothfinder

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

data class SignalReading(val address: String, val rssi: Int, val at: Long)

class SignalScanner(private val context: Context) {
    val readings = mutableStateMapOf<String, Int>()
    val samples = mutableStateListOf<SignalReading>()
    val names = mutableStateMapOf<String, String>()
    val scanning = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)
    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var bleStarted = false
    private var guided = false
    private var until = 0L
    private var generation = 0
    private fun addReading(address: String, rssi: Int, name: String?) {
        val session = generation
        handler.post {
            if (session != generation || !scanning.value) return@post
            readings[address] = rssi
            if (name != null) names[address] = name
            samples.add(SignalReading(address, rssi, SystemClock.elapsedRealtime()))
            if (samples.size > 400) samples.removeAt(0)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                    message.value = "Bluetooth выключен"
                    stop()
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (device != null && rssi != Short.MIN_VALUE) {
                        val name = try { device.name } catch (_: SecurityException) { null }
                        addReading(device.address, rssi.toInt(), name)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (guided && scanning.value && SystemClock.elapsedRealtime() < until)
                        handler.postDelayed({ try { adapter?.startDiscovery() } catch (_: SecurityException) { } }, 600)
                    else if (!bleStarted) scanning.value = false
                }
            }
        }
    }
    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addReading(result.device.address, result.rssi, result.scanRecord?.deviceName)
        }
        override fun onScanFailed(errorCode: Int) { handler.post { message.value = "Поиск BLE недоступен (код $errorCode)" } }
    }

    fun start(durationMillis: Long = 15_000) {
        if (scanning.value) return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            message.value = "Разрешите поиск устройств Bluetooth"
            return
        }
        readings.clear()
        samples.clear()
        names.clear()
        generation++
        message.value = null
        guided = durationMillis > 15_000
        until = SystemClock.elapsedRealtime() + durationMillis
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            receiverRegistered = true
            val classic = adapter?.startDiscovery() == true
            val ble = adapter?.bluetoothLeScanner
            if (ble != null) { ble.startScan(bleCallback); bleStarted = true }
            scanning.value = classic || bleStarted
            if (!scanning.value) { message.value = "Включите Bluetooth, чтобы начать поиск"; stop() }
            else handler.postDelayed({ stop() }, durationMillis)
        } catch (_: Exception) {
            stop()
            message.value = "Не удалось начать поиск Bluetooth"
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        generation++
        if (receiverRegistered) try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        receiverRegistered = false
        try { if (adapter?.isDiscovering == true) adapter?.cancelDiscovery() } catch (_: Exception) { }
        try { if (bleStarted) adapter?.bluetoothLeScanner?.stopScan(bleCallback) } catch (_: Exception) { }
        bleStarted = false
        guided = false
        scanning.value = false
    }
}
