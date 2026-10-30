package com.example.bluetoothfinder

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

data class PairedDevice(val address: String, val name: String)

class MainActivity : ComponentActivity() {
    companion object { @Volatile var isVisible = false }
    private val resumeTick = mutableIntStateOf(0)
    override fun onStart() { super.onStart(); isVisible = true }
    override fun onStop() { isVisible = false; super.onStop() }
    override fun onResume() { super.onResume(); resumeTick.intValue++ }

    private fun bluetoothAllowed() = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun locationAllowed() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun paired(): List<PairedDevice> = try {
        if (!bluetoothAllowed()) emptyList() else getSystemService(BluetoothManager::class.java).adapter
            ?.bondedDevices?.map { PairedDevice(it.address, it.name ?: it.address) }?.sortedBy { it.name } ?: emptyList()
    } catch (_: SecurityException) { emptyList() }
    private fun updateTracking() {
        if (Tracked.get(this).isNotEmpty() && bluetoothAllowed()) {
            try { ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java)) }
            catch (_: Exception) { TrackingService.running.value = false }
        } else stopService(Intent(this, TrackingService::class.java))
    }
    private suspend fun phoneLocation(): Location? = try {
        if (!locationAllowed()) null else withTimeoutOrNull(15_000) {
            val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
            val token = CancellationTokenSource()
            try {
                (client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).await()
                    ?: client.lastLocation.await()?.takeIf { LocationPolicy.isRecent(System.currentTimeMillis(), it.time, 120_000) })
                    ?.also { LastPhoneLocation.save(this@MainActivity, it) }
            } finally { token.cancel() }
        }
    } catch (_: Exception) { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            var devices by remember { mutableStateOf(paired()) }
            var selected by remember { mutableStateOf(Tracked.get(this)) }
            var permissionTick by remember { mutableIntStateOf(0) }
            var currentPoint by remember { mutableStateOf<Location?>(null) }
            var locating by remember { mutableStateOf(false) }
            var locationMessage by remember { mutableStateOf<String?>(null) }
            var selectedEvent by remember { mutableStateOf<Disconnect?>(null) }
            var pendingEvent by remember { mutableStateOf<Disconnect?>(null) }
            var historyLimit by remember { mutableIntStateOf(HistoryRetention.get(this)) }
            var historyLimitDialog by remember { mutableStateOf(false) }
            var historyLimitInput by remember { mutableStateOf(historyLimit.toString()) }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            val scanner = remember { SignalScanner(this) }
            var guidedDevice by remember { mutableStateOf<PairedDevice?>(null) }
            var pendingGuide by remember { mutableStateOf<PairedDevice?>(null) }
            DisposableEffect(tab) { onDispose { if (tab == 1) scanner.stop() } }
            val guidePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                if (allowed) pendingGuide?.let { guidedDevice = it; scanner.stop(); scanner.start(180_000) }
                else scanner.message.value = "Для подсказок при ходьбе нужен доступ к датчику шагов"
                pendingGuide = null
            }
            val scanPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                if (grants.values.all { it }) {
                    val device = pendingGuide
                    if (device == null) scanner.start()
                    else if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                        guidedDevice = device; pendingGuide = null; scanner.stop(); scanner.start(180_000)
                    } else guidePermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                } else { scanner.message.value = "Разрешите поиск Bluetooth в настройках"; pendingGuide = null }
            }
            val listState = rememberLazyListState()
            val history by FinderDb.get(this).dao().observe().collectAsStateWithLifecycle(emptyList())
            LaunchedEffect(historyLimit) { FinderDb.get(this@MainActivity).dao().prune(historyLimit) }
            LaunchedEffect(resumeTick.intValue) {
                if (resumeTick.intValue > 0) { devices = paired(); selected = Tracked.get(this@MainActivity); updateTracking() }
            }
            val latest = history.firstOrNull { it.connectionLatitude != null || it.latitude != null }
            val event = selectedEvent ?: latest
            val mapLat = currentPoint?.latitude ?: event?.connectionLatitude ?: event?.latitude
            val mapLon = currentPoint?.longitude ?: event?.connectionLongitude ?: event?.longitude
            val backgroundAllowed = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                permissionTick++; devices = paired(); updateTracking()
            }
            val palette = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF236A75), secondary = androidx.compose.ui.graphics.Color(0xFF336D54), surface = androidx.compose.ui.graphics.Color(0xFFF6F8F7))
            MaterialTheme(colorScheme = palette) {
                if (historyLimitDialog) {
                    val newLimit = historyLimitInput.toIntOrNull()?.takeIf { it in 1..10_000 }
                    AlertDialog(
                        onDismissRequest = { historyLimitDialog = false },
                        title = { Text("Сколько записей хранить") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = historyLimitInput,
                                    onValueChange = { historyLimitInput = it.filter(Char::isDigit).take(5) },
                                    label = { Text("Количество от 1 до 10 000") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Более старые записи будут удалены из базы.", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = { TextButton(onClick = {
                            newLimit?.let {
                                HistoryRetention.set(this@MainActivity, it)
                                historyLimit = it
                                historyLimitDialog = false
                            }
                        }, enabled = newLimit != null) { Text("Сохранить") } },
                        dismissButton = { TextButton(onClick = { historyLimitDialog = false }) { Text("Отмена") } }
                    )
                }
                pendingEvent?.let { item ->
                    val lat = item.connectionLatitude ?: item.latitude
                    val lon = item.connectionLongitude ?: item.longitude
                    AlertDialog(onDismissRequest = { pendingEvent = null },
                        title = { Text(item.name) },
                        text = { Text(if (lat == null || lon == null) "Для этой записи нет координат." else if (item.connectionLatitude != null) "Показать точку последнего подключения?" else "Точка подключения не записана. Доступна точка отключения.") },
                        confirmButton = { TextButton(onClick = {
                            selectedEvent = item; currentPoint = null; pendingEvent = null
                            scope.launch { listState.animateScrollToItem(2) }
                        }, enabled = lat != null && lon != null) { Text("В приложении") } },
                        dismissButton = { TextButton(onClick = {
                            pendingEvent = null
                            if (lat != null && lon != null) openMap(lat, lon, false)
                        }, enabled = lat != null && lon != null) { Text("В обычной карте") } })
                }
                Scaffold(containerColor = palette.surface, bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("◉", fontSize = 30.sp) }, label = { Text("Карта") })
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("⌁", fontSize = 34.sp) }, label = { Text("Поиск рядом") })
                    }
                }) { padding ->
                    if (tab == 1) {
                        SignalSearchScreen(devices, scanner, guidedDevice, Modifier.padding(padding), onCloseGuide = {
                            guidedDevice = null; scanner.stop()
                        }, onGuide = { device ->
                            val scanAllowed = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                            if (!scanAllowed) {
                                pendingGuide = device
                                scanPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                            } else if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                                guidedDevice = device; scanner.stop(); scanner.start(180_000)
                            } else {
                                pendingGuide = device
                                guidePermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        }, onConnect = {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }) {
                            val permissions = buildList {
                                if (Build.VERSION.SDK_INT >= 31) {
                                    add(Manifest.permission.BLUETOOTH_SCAN)
                                    add(Manifest.permission.BLUETOOTH_CONNECT)
                                } else add(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            if (permissions.all { ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED }) scanner.start()
                            else scanPermissionLauncher.launch(permissions.toTypedArray())
                        }
                    } else
                    LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Image(painterResource(R.drawable.cover), contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(24.dp)))
                            Spacer(Modifier.height(12.dp))
                            Text("BluetoothFinder", style = MaterialTheme.typography.headlineMedium, color = palette.primary)
                            Text("Где оборвалась связь с устройством", style = MaterialTheme.typography.bodyLarge)
                        } }
                        item {
                            val active = TrackingService.running.value && selected.isNotEmpty()
                            Card(colors = CardDefaults.cardColors(containerColor = if (active) androidx.compose.ui.graphics.Color(0xFFE1F3E8) else androidx.compose.ui.graphics.Color(0xFFFFF1DE)), shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(if (active) "● Устройства под наблюдением" else "○ Отслеживание выключено", style = MaterialTheme.typography.titleMedium, color = palette.primary)
                                    Text(if (selected.isEmpty()) "Выберите наушники или другое сопряжённое устройство ниже." else if (!active) "Нажмите «Включить отслеживание»." else "Выбрано устройств: ${selected.size}. Координаты обновляются примерно каждые 5 секунд; это увеличивает расход батареи.")
                                    if (selected.isNotEmpty() && !active) Button(onClick = { updateTracking() }) { Text("Включить отслеживание") }
                                }
                            }
                        }
                        item {
                            SectionTitle("Карта")
                            Spacer(Modifier.height(8.dp))
                            if (mapLat != null && mapLon != null) {
                                MapCard(mapLat, mapLon, if (currentPoint == null) event else null)
                                Spacer(Modifier.height(10.dp))
                                if (currentPoint == null && event != null) Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Text(event.name, style = MaterialTheme.typography.titleMedium, color = palette.primary)
                                        if (event.connectionLatitude != null) LegendRow(Color(0xFF1C63B2), "Последнее подключение")
                                        if (event.latitude != null) LegendRow(Color(0xFFDC5437), "Наушники при отключении")
                                        if (event.latitude != null && event.accuracy != null)
                                            Text("Область поиска · примерно ${(event.accuracy + 15f).toInt()} м", style = MaterialTheme.typography.bodyMedium)
                                        Text("Круг учитывает точность GPS и примерную дальность Bluetooth.", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                                    }
                                }
                                if (currentPoint != null) Text("Моё текущее местоположение", style = MaterialTheme.typography.titleSmall)
                                val pointTime = if (event?.connectionLatitude != null) event.connectionTime else event?.locationTime
                                if (currentPoint == null && pointTime != null) Text("Координаты получены ${DateFormat.getDateTimeInstance().format(Date(pointTime))}", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { openMap(mapLat, mapLon, false) }) { Text("Открыть карту") }
                                    TextButton(onClick = { openMap(mapLat, mapLon, true) }) { Text("Маршрут") }
                                }
                            } else Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Точка появится здесь", style = MaterialTheme.typography.titleMedium)
                                    Text("Проверьте карту по текущей позиции или дождитесь отключения устройства.")
                                }
                            }
                            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                                if (!locationAllowed()) locationMessage = "Сначала разрешите геолокацию ниже."
                                else scope.launch {
                                    locating = true; locationMessage = null
                                    val result = phoneLocation(); currentPoint = result
                                    if (result == null) locationMessage = "Не удалось получить координаты. Проверьте геолокацию телефона."
                                    locating = false
                                }
                            }, enabled = !locating) { Text(if (locating) "Определяем позицию…" else "Показать, где я сейчас") }
                            locationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        item {
                            SectionTitle("Мои устройства")
                            Text("Выберите устройство для отслеживания")
                            if (!bluetoothAllowed() || !locationAllowed()) Button(onClick = {
                                val permissions = buildList {
                                    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                                    add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                permissionLauncher.launch(permissions.toTypedArray())
                            }) { Text("Разрешить доступ") }
                        }
                        if (devices.isEmpty()) item { Card { Column(Modifier.padding(16.dp)) {
                            Text("Сопряжённых устройств не найдено")
                            Text("Сопрягите устройство в настройках Bluetooth и обновите список.")
                        } } }
                        items(devices, key = { it.address }) { device ->
                            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = device.address in selected, onCheckedChange = { checked ->
                                        selected = if (checked) selected + device.address else selected - device.address
                                        Tracked.set(this@MainActivity, selected); updateTracking()
                                        if (!checked) LastConnectionLocation.clear(this@MainActivity, device.address)
                                        if (checked && locationAllowed()) scope.launch { phoneLocation()?.let { currentPoint = it } }
                                    })
                                }
                            }
                        }
                        item {
                            TextButton(onClick = { devices = paired() }) { Text("Обновить устройства") }
                            if (!backgroundAllowed && selected.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFF1DE))) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("Нужна геолокация в фоне", style = MaterialTheme.typography.titleSmall)
                                    Text("Сейчас точка запишется, только пока приложение открыто. Для записи при закрытом экране разрешите геолокацию «Всегда».")
                                    TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("Открыть настройки") }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                SectionTitle("История отключений")
                                TextButton(onClick = { historyLimitInput = historyLimit.toString(); historyLimitDialog = true }) {
                                    Text("Последние $historyLimit")
                                }
                            }
                        }
                        if (history.isEmpty()) item { Text("Отключений пока нет. Когда связь с выбранным устройством оборвётся, запись появится здесь.") }
                        items(history, key = { it.id }) { item -> Card(modifier = Modifier.fillMaxWidth().clickable { pendingEvent = item }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium, color = palette.primary)
                                Text(DateFormat.getDateTimeInstance().format(Date(item.time)))
                                Text(if (item.connectionLatitude != null) "Последнее подключение · точность ${item.connectionAccuracy?.toInt() ?: 0} м" else if (item.latitude != null) "Точка отключения · точность ${item.accuracy?.toInt() ?: 0} м" else "Без координат", style = MaterialTheme.typography.bodySmall)
                            }
                        } }
                        item { Text("Android сообщает об отключении только активного Bluetooth-соединения. Для некоторых BLE-устройств событие недоступно.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            @Suppress("UNUSED_EXPRESSION") permissionTick
            @Suppress("UNUSED_EXPRESSION") resumeTick.intValue
        }
    }

    private fun openMap(lat: Double, lon: Double, directions: Boolean) {
        val uri = if (directions) Uri.parse("google.navigation:q=$lat,$lon") else Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { if (directions) setPackage("com.google.android.apps.maps") }
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon"))) }
            catch (_: ActivityNotFoundException) { }
        }
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleLarge) }

@Composable private fun SignalSearchScreen(devices: List<PairedDevice>, scanner: SignalScanner, guidedDevice: PairedDevice?, modifier: Modifier = Modifier,
                                             onCloseGuide: () -> Unit, onGuide: (PairedDevice) -> Unit, onConnect: () -> Unit, onScan: () -> Unit) {
    val scope = rememberCoroutineScope()
    var helpDevice by remember { mutableStateOf<PairedDevice?>(null) }
    helpDevice?.let { device ->
        AlertDialog(onDismissRequest = { helpDevice = null }, title = { Text("Помочь в поиске?") },
            text = { Text("Выберите направление и пройдите несколько шагов. Приложение сравнит силу сигнала до и после движения.") },
            confirmButton = { TextButton(onClick = { helpDevice = null; scope.launch { delay(200); onGuide(device) } }) { Text("Начать") } },
            dismissButton = { TextButton(onClick = { helpDevice = null }) { Text("Отмена") } })
    }
    if (guidedDevice != null) DirectionGuideDialog(guidedDevice, scanner, onCloseGuide) { onGuide(guidedDevice) }
    val nearbyNamed = scanner.names.entries.filter { (address, name) ->
        devices.none { it.address == address || it.name.equals(name, ignoreCase = true) }
    }.take(20).map { PairedDevice(it.key, it.value) }
    val visible = devices + nearbyNamed
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Поиск рядом", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text("Найдите устройство по силе сигнала. Идите в сторону, где показатель растёт.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (scanner.scanning.value) scanner.stop() else onScan() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (scanner.scanning.value) "Остановить поиск" else "Сканировать 15 секунд")
            }
            scanner.message.value?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Сила сигнала меняется из-за стен и положения наушников; это не расстояние в метрах.", style = MaterialTheme.typography.bodySmall)
        }
        if (visible.isEmpty()) item { Text("Сначала сопрягите наушники в настройках Bluetooth.") }
        items(visible, key = { it.address }) { device ->
            val rssi = scanner.readings[device.address] ?: scanner.names.entries
                .firstOrNull { it.value.equals(device.name, ignoreCase = true) }?.key?.let { scanner.readings[it] }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { helpDevice = device }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                    if (rssi == null) Text("Сигнал пока не найден", style = MaterialTheme.typography.bodyMedium)
                    else {
                        val level = ((rssi + 100) / 60f).coerceIn(0f, 1f)
                        Text("$rssi dBm · " + when { rssi >= -60 -> "очень сильный"; rssi >= -75 -> "сильный"; rssi >= -90 -> "слабый"; else -> "очень слабый" })
                        LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                    }
                    if (devices.any { it.address == device.address }) TextButton(onClick = onConnect) { Text("Подключить в настройках Bluetooth") }
                    Text("Нажмите для наведения", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Text("Некоторые наушники не отвечают на поиск, когда лежат в закрытом кейсе или уже подключены. Тогда уровень сигнала недоступен.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun DirectionGuideDialog(device: PairedDevice, scanner: SignalScanner, onClose: () -> Unit, onRestart: () -> Unit) {
    val context = LocalContext.current
    val tracker = remember(device.address) { StepTracker(context) }
    var sensorReady by remember(device.address) { mutableStateOf(true) }
    var run by remember(device.address) { mutableIntStateOf(0) }
    var baseline by remember(device.address, run) { mutableStateOf<Int?>(null) }
    var baselineTime by remember(device.address, run) { mutableLongStateOf(0L) }
    var stepStart by remember(device.address, run) { mutableIntStateOf(0) }
    var expectedSteps by remember(device.address, run) { mutableIntStateOf(6) }
    var arrow by remember(device.address, run) { mutableStateOf("↑") }
    var instruction by remember(device.address, run) { mutableStateOf("Ждём сигнал наушников…") }
    var note by remember(device.address, run) { mutableStateOf<String?>(null) }
    var near by remember(device.address, run) { mutableStateOf(false) }
    var ignorePeak by remember(device.address, run) { mutableStateOf(false) }
    DisposableEffect(device.address) {
        sensorReady = tracker.start()
        onDispose { tracker.stop() }
    }
    val samples = scanner.samples.filter {
        it.address == device.address || scanner.names[it.address]?.equals(device.name, ignoreCase = true) == true
    }
    val now = SystemClock.elapsedRealtime()
    val fresh = samples.filter { now - it.at in 0..8_000 }
    val latest = fresh.lastOrNull()
    LaunchedEffect(samples.size, run) {
        val median = SearchGuidance.median(fresh.map { RssiPoint(it.rssi, it.at) }, now)
        if (baseline == null && median != null) {
            baseline = median; baselineTime = latest!!.at; stepStart = tracker.steps.intValue
            instruction = "Сделайте 6 шагов прямо"
        }
        if (!ignorePeak && fresh.size >= 3 && median != null && median >= -50) {
            near = true; instruction = "Похоже, вы уже рядом! Осмотрите место вокруг."
        }
    }
    Dialog(onDismissRequest = onClose) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F8F7)), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Поиск · ${device.name}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                DirectionArrow(arrow, near)
                Text(instruction, style = MaterialTheme.typography.titleLarge)
                if (!near && baseline != null) Text("Шаги: ${(tracker.steps.intValue - stepStart).coerceAtLeast(0)} / $expectedSteps", style = MaterialTheme.typography.bodyLarge)
                Text("Сигнал: ${latest?.rssi?.let { "$it dBm" } ?: "не найден"}", style = MaterialTheme.typography.bodyMedium)
                if (!sensorReady) Text("Датчик шагов недоступен — считайте шаги сами.", style = MaterialTheme.typography.bodySmall)
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (near) Button(onClick = { near = false; ignorePeak = true; instruction = "Сделайте $expectedSteps шагов прямо" }, modifier = Modifier.fillMaxWidth()) { Text("Искать дальше") }
                else Button(onClick = {
                    val checkedAt = SystemClock.elapsedRealtime()
                    val after = samples.filter { it.at > baselineTime + 1_000 && checkedAt - it.at in 0..8_000 && it.at >= tracker.lastStepAt.longValue }
                    val value = SearchGuidance.median(after.map { RssiPoint(it.rssi, it.at) }, checkedAt)
                    if (baseline == null || value == null) note = "Ждём свежий сигнал после шагов. Попробуйте ещё раз через пару секунд."
                    else {
                        val change = value - baseline!!
                        note = "Изменение сигнала: ${if (change > 0) "+" else ""}$change dBm"
                        when (SearchGuidance.decide(baseline!!, value, after.size, ignorePeak)) {
                            SearchDirection.TURN_BACK -> { arrow = "↓"; expectedSteps = 12; instruction = "Развернитесь и сделайте 12 шагов" }
                            SearchDirection.CONTINUE -> { arrow = "↑"; expectedSteps = 6; instruction = "Сигнал сильнее. Сделайте ещё 6 шагов прямо" }
                            SearchDirection.NEARBY -> { near = true; instruction = "Похоже, вы уже рядом! Осмотрите место вокруг." }
                            else -> { arrow = "↗"; expectedSteps = 6; instruction = "Разница мала. Поверните немного и сделайте 6 шагов" }
                        }
                        baseline = value; baselineTime = after.last().at; stepStart = tracker.steps.intValue
                    }
                }, enabled = baseline != null && scanner.scanning.value, modifier = Modifier.fillMaxWidth()) { Text("Сделал шаги") }
                Row {
                    TextButton(onClick = { run++; onRestart() }) { Text("Заново") }
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
                Text("Направление оценивается по изменению сигнала при ходьбе.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun DirectionArrow(direction: String, near: Boolean) {
    Canvas(Modifier.size(132.dp)) {
        val color = if (near) Color(0xFF336D54) else Color(0xFF236A75)
        val stroke = 14.dp.toPx()
        if (near) {
            drawCircle(color, radius = size.minDimension * .36f, style = Stroke(width = stroke))
            drawCircle(color, radius = size.minDimension * .12f)
        } else rotate(if (direction == "↓") 180f else if (direction == "↗") 45f else 0f) {
            val w = size.width; val h = size.height
            drawLine(color, Offset(w * .5f, h * .82f), Offset(w * .5f, h * .18f), stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * .5f, h * .18f), Offset(w * .24f, h * .44f), stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * .5f, h * .18f), Offset(w * .76f, h * .44f), stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun MapCard(lat: Double, lon: Double, event: Disconnect?) {
    val context = LocalContext.current
    key(lat, lon, event?.id) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            var mapView by remember { mutableStateOf<TileMapView?>(null) }
            Box(Modifier.fillMaxWidth().height(290.dp)) {
                AndroidView(factory = { TileMapView(context, lat, lon,
                    event?.connectionLatitude, event?.connectionLongitude,
                    event?.latitude, event?.longitude,
                    if (event?.latitude != null) (event.accuracy ?: 0f) + 15f else null
                ).also { mapView = it } }, modifier = Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { mapView?.zoomBy(1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("+") }
                    FilledTonalButton(onClick = { mapView?.zoomBy(-1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("−") }
                }
            }
        }
    }
}
