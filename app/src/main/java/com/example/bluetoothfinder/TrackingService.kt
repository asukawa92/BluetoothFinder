package com.example.bluetoothfinder

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.location.Location
import android.location.LocationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import androidx.compose.runtime.mutableStateOf

class TrackingService : Service() {
    companion object { val running = mutableStateOf(false) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = ConnectionGate()
    private var receiverRegistered = false
    private var lastSavedAt = 0L
    private var locationSubscribed = false
    private lateinit var notification: Notification
    @Volatile private var recentLocation: Location? = null
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                recentLocation = it
                if (it.time - lastSavedAt >= 30_000) { LastPhoneLocation.save(this@TrackingService, it); lastSavedAt = it.time }
            }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                gate.setBluetoothOn(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON)
                return
            }
            if (!gate.bluetoothOn) return
            val adapterOn = try { getSystemService(BluetoothManager::class.java).adapter?.state == BluetoothAdapter.STATE_ON }
                catch (_: SecurityException) { false }
            if (!adapterOn) return
            if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED && action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED && action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val connected = action == BluetoothDevice.ACTION_ACL_CONNECTED || (action != BluetoothDevice.ACTION_ACL_DISCONNECTED && state == BluetoothProfile.STATE_CONNECTED)
            val disconnected = action == BluetoothDevice.ACTION_ACL_DISCONNECTED || (action != BluetoothDevice.ACTION_ACL_CONNECTED && state == BluetoothProfile.STATE_DISCONNECTED)
            if (!connected && !disconnected) return
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (device == null || !Tracked.get(context).contains(device.address)) return
            val now = System.currentTimeMillis()
            val accepted = if (connected) gate.connected(device.address, now) else gate.disconnected(device.address, now)
            if (!accepted) return
            scope.launch { if (connected) captureConnection(device.address) else record(device) }
        }
    }

    private fun locationEnabled(): Boolean = try {
        val manager = getSystemService(LocationManager::class.java)
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }

    override fun onCreate() {
        super.onCreate()
        if (Tracked.get(this).isEmpty()) { stopSelf(); return }
        running.value = true
        gate.setBluetoothOn(try { getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }
            catch (_: SecurityException) { false })
        val channel = NotificationChannel("tracking", "Отслеживание Bluetooth", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        notification = NotificationCompat.Builder(this, "tracking")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BluetoothFinder")
            .setContentText("Запоминаем позицию для поиска устройств")
            .setContentIntent(open).setOngoing(true).build()
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (fine && locationEnabled() && (background || MainActivity.isVisible)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        ServiceCompat.startForeground(this, 1, notification, type)
        refreshLocationTracking()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun refreshLocationTracking() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine || !locationEnabled() || (!background && !MainActivity.isVisible)) {
            if (locationSubscribed) locationClient.removeLocationUpdates(locationCallback)
            locationSubscribed = false
            return
        }
        if (locationSubscribed) return
        try {
            ServiceCompat.startForeground(this, 1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000)
                .setMinUpdateIntervalMillis(10_000).setMinUpdateDistanceMeters(10f).build()
            locationClient.requestLocationUpdates(request, locationCallback, mainLooper)
                .addOnFailureListener { locationSubscribed = false }
            locationSubscribed = true
        } catch (_: SecurityException) { locationSubscribed = false }
    }

    private suspend fun locate(time: Long): Location? = try {
            val recentPoint = recentLocation?.takeIf { LocationPolicy.isRecent(time, it.time, 15_000) }
            if (recentPoint != null && recentPoint.accuracy <= 25f) recentPoint else {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if ((!fine && !coarse) || (!background && !MainActivity.isVisible))
                LastPhoneLocation.get(this)?.takeIf { LocationPolicy.isRecent(time, it.time, 120_000) }
            else {
                val client = LocationServices.getFusedLocationProviderClient(this)
                val cached = try { client.lastLocation.await() } catch (_: Exception) { null }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0)
                    .setDurationMillis(10_000)
                    .build()
                val fresh = withTimeoutOrNull(11_000) { client.getCurrentLocation(request, CancellationTokenSource().token).await() }
                listOfNotNull(fresh, recentPoint).minByOrNull { it.accuracy }
                    ?: cached?.takeIf { LocationPolicy.isRecent(time, it.time, 120_000) }
                    ?: LastPhoneLocation.get(this)?.takeIf { LocationPolicy.isRecent(time, it.time, 120_000) }
            }
            }
        } catch (_: Exception) { LastPhoneLocation.get(this)?.takeIf { LocationPolicy.isRecent(time, it.time, 120_000) } }

    private suspend fun captureConnection(address: String) {
        locate(System.currentTimeMillis())?.let { LastConnectionLocation.save(this, address, it) }
    }

    private suspend fun record(device: BluetoothDevice) {
        val time = System.currentTimeMillis()
        delay(8_000)
        val adapterOn = try { getSystemService(BluetoothManager::class.java).adapter?.state == BluetoothAdapter.STATE_ON }
            catch (_: SecurityException) { false }
        if (!gate.bluetoothOn || !adapterOn || gate.powerChangedNear(time) || !Tracked.get(this).contains(device.address)) return
        val connection = LastConnectionLocation.get(this, device.address)?.takeIf { it.time <= time }
        val location = locate(time)
        if (location != null) LastPhoneLocation.save(this, location)
        val name = try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 31) device.name ?: device.address else device.address
        } catch (_: SecurityException) { device.address }
        FinderDb.get(this).dao().insert(Disconnect(address = device.address, name = name, time = time,
            latitude = location?.latitude, longitude = location?.longitude, accuracy = location?.accuracy, locationTime = location?.time,
            connectionLatitude = connection?.latitude, connectionLongitude = connection?.longitude,
            connectionAccuracy = connection?.accuracy, connectionTime = connection?.time))
        LastConnectionLocation.clear(this, device.address)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Tracked.get(this).isEmpty()) { stopSelf(); return START_NOT_STICKY }
        refreshLocationTracking()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running.value = false
        try { locationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) { }
        if (receiverRegistered) unregisterReceiver(receiver)
        scope.cancel()
        super.onDestroy()
    }
}
