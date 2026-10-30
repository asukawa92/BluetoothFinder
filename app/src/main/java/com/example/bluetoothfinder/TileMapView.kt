package com.example.bluetoothfinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.cos
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.abs

class TileMapView(context: Context, private val lat: Double, private val lon: Double,
                  private val connectionLat: Double? = null, private val connectionLon: Double? = null,
                  private val disconnectLat: Double? = null, private val disconnectLon: Double? = null,
                  private val searchRadiusMeters: Float? = null) : View(context) {
    private var zoom = 17
    private var centerX = tileX(lon, zoom)
    private var centerY = tileY(lat, zoom)
    private val tiles = LruCache<String, Bitmap>(48)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newFixedThreadPool(3)
    @Volatile private var failed = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tilePixels get() = (256 * resources.displayMetrics.density).toInt().coerceAtLeast(256)
    private var lastX = 0f
    private var lastY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var accumulated = 1f
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            accumulated *= detector.scaleFactor
            if (accumulated > 1.3f) { zoomBy(1); accumulated = 1f }
            if (accumulated < 0.77f) { zoomBy(-1); accumulated = 1f }
            return true
        }
    })

    private fun tileX(longitude: Double, level: Int) = (longitude + 180.0) / 360.0 * (1 shl level)
    private fun tileY(latitude: Double, level: Int): Double {
        val rad = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl level)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (connectionLat != null && connectionLon != null && disconnectLat != null && disconnectLon != null) {
            for (level in 18 downTo 3) {
                val dx = abs(tileX(connectionLon, level) - tileX(disconnectLon, level)) * tilePixels
                val dy = abs(tileY(connectionLat, level) - tileY(disconnectLat, level)) * tilePixels
                if (dx <= w * .65 && dy <= h * .55) {
                    zoom = level
                    centerX = (tileX(connectionLon, level) + tileX(disconnectLon, level)) / 2
                    centerY = (tileY(connectionLat, level) + tileY(disconnectLat, level)) / 2
                    break
                }
            }
        }
    }

    fun zoomBy(delta: Int) {
        val oldN = 1 shl zoom
        val centerLon = centerX / oldN * 360.0 - 180.0
        val centerLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * centerY / oldN))))
        zoom = (zoom + delta).coerceIn(3, 18)
        centerX = tileX(centerLon, zoom)
        centerY = tileY(centerLat, zoom)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); lastX = event.x; lastY = event.y; return true }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                centerX -= (event.x - lastX).toDouble() / tilePixels
                centerY -= (event.y - lastY).toDouble() / tilePixels
                lastX = event.x; lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> { lastX = event.getX(0); lastY = event.getY(0) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun requestTile(key: String) {
        if (System.currentTimeMillis() < (retryAfter[key] ?: 0L)) return
        if (!inFlight.add(key)) return
        try { executor.submit {
            try {
                val connection = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "BluetoothFinder/1.0 (Android map preview)")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                try { connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream)?.let { tiles.put(key, it) } } }
                finally { connection.disconnect() }
                retryAfter.remove(key)
            } catch (_: Exception) { retryAfter[key] = System.currentTimeMillis() + 30_000; failed++ }
            inFlight.remove(key)
            postInvalidate()
        } } catch (_: java.util.concurrent.RejectedExecutionException) { inFlight.remove(key) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(224, 237, 232))
        val size = tilePixels.toFloat()
        var shown = 0
        val n = 1 shl zoom
        val minX = floor(centerX - width / (2 * size)).toInt() - 1
        val maxX = floor(centerX + width / (2 * size)).toInt() + 1
        val minY = floor(centerY - height / (2 * size)).toInt() - 1
        val maxY = floor(centerY + height / (2 * size)).toInt() + 1
        for (rawX in minX..maxX) for (rawY in minY..maxY) {
            if (rawY !in 0 until n) continue
            val tx = (rawX % n + n) % n
            val key = "$zoom/$tx/$rawY"
            val left = width / 2f + (rawX - centerX).toFloat() * size
            val top = height / 2f + (rawY - centerY).toFloat() * size
            val bitmap = tiles.get(key)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + size, top + size), paint)
                shown++
            } else {
                requestTile(key)
                paint.color = Color.rgb(202, 222, 214)
                paint.style = Paint.Style.STROKE
                canvas.drawRect(left, top, left + size, top + size, paint)
                paint.style = Paint.Style.FILL
            }
        }
        if (disconnectLat != null && disconnectLon != null && searchRadiusMeters != null) {
            val x = width / 2f + (tileX(disconnectLon, zoom) - centerX).toFloat() * size
            val y = height / 2f + (tileY(disconnectLat, zoom) - centerY).toFloat() * size
            val metersPerPixel = cos(Math.toRadians(disconnectLat)) * 2 * PI * 6378137 / ((1 shl zoom) * size)
            val radius = (searchRadiusMeters / metersPerPixel).toFloat()
            paint.color = Color.argb(65, 237, 102, 66)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius, paint)
            paint.color = Color.rgb(220, 84, 55)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * resources.displayMetrics.density
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.FILL
        }
        fun marker(latitude: Double, longitude: Double, color: Int, earbud: Boolean = false) {
            val x = width / 2f + (tileX(longitude, zoom) - centerX).toFloat() * size
            val y = height / 2f + (tileY(latitude, zoom) - centerY).toFloat() * size
            val d = resources.displayMetrics.density
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, (if (earbud) 19f else 13f) * d, paint)
            paint.color = color
            canvas.drawCircle(x, y, (if (earbud) 16f else 9f) * d, paint)
            if (earbud) {
                paint.color = Color.WHITE
                canvas.drawOval(x - 9f * d, y - 10f * d, x + 6f * d, y + 1f * d, paint)
                canvas.drawRoundRect(x - 1f * d, y - 1f * d, x + 5f * d, y + 11f * d, 3f * d, 3f * d, paint)
                paint.color = color
                canvas.drawOval(x - 5f * d, y - 7f * d, x + 1f * d, y - 3f * d, paint)
            }
        }
        if (connectionLat != null && connectionLon != null) marker(connectionLat, connectionLon, Color.rgb(28, 99, 178))
        if (disconnectLat != null && disconnectLon != null) marker(disconnectLat, disconnectLon, Color.rgb(220, 84, 55), true)
        if (connectionLat == null && disconnectLat == null) marker(lat, lon, Color.rgb(28, 99, 178), true)
        paint.textSize = 13f * resources.displayMetrics.scaledDensity
        paint.color = Color.BLACK
        val label = if (shown == 0 && failed > 5) "Тайлы недоступны · откройте карту кнопкой ниже" else if (shown == 0) "Загрузка карты…" else "© OpenStreetMap contributors"
        canvas.drawText(label, 12f * resources.displayMetrics.density, height - 12f * resources.displayMetrics.density, paint)
    }

    override fun onDetachedFromWindow() {
        executor.shutdownNow()
        super.onDetachedFromWindow()
    }
}
