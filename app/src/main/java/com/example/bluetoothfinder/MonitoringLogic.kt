package com.example.bluetoothfinder

/** Suppresses duplicate profile broadcasts without hiding a new disconnect after a reconnect. */
class ConnectionGate(private val duplicateWindowMs: Long = 10_000) {
    private val lastConnect = mutableMapOf<String, Long>()
    private val lastDisconnect = mutableMapOf<String, Long>()
    @Volatile var bluetoothOn = true
        private set
    @Volatile var lastPowerChangeAt = Long.MIN_VALUE
        private set

    fun setBluetoothOn(on: Boolean, at: Long = System.currentTimeMillis()) {
        if (bluetoothOn != on) lastPowerChangeAt = at
        bluetoothOn = on
        if (!on) { lastConnect.clear(); lastDisconnect.clear() }
    }

    fun powerChangedNear(eventAt: Long): Boolean = lastPowerChangeAt >= eventAt - 5_000

    fun connected(address: String, at: Long): Boolean {
        if (!bluetoothOn) return false
        lastDisconnect.remove(address)
        val previous = lastConnect.put(address, at)
        return previous == null || at - previous >= duplicateWindowMs
    }

    fun disconnected(address: String, at: Long): Boolean {
        if (!bluetoothOn) return false
        val previous = lastDisconnect.put(address, at)
        return previous == null || at - previous >= duplicateWindowMs
    }
}

data class RssiPoint(val value: Int, val at: Long)

enum class SearchDirection { CONTINUE, TURN_BACK, TRY_ANOTHER, NEARBY, WAIT_FOR_SIGNAL }

object SearchGuidance {
    fun median(points: List<RssiPoint>, now: Long, maxAgeMs: Long = 8_000): Int? {
        val values = points.filter { now - it.at in 0..maxAgeMs }.takeLast(5).map { it.value }.sorted()
        return values.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    }

    fun decide(before: Int, after: Int?, samplesAfter: Int, ignorePeak: Boolean = false): SearchDirection {
        if (after == null || samplesAfter == 0) return SearchDirection.WAIT_FOR_SIGNAL
        if (!ignorePeak && samplesAfter >= 3 && after >= -50) return SearchDirection.NEARBY
        val change = after - before
        return when {
            change <= -5 -> SearchDirection.TURN_BACK
            change >= 5 -> SearchDirection.CONTINUE
            else -> SearchDirection.TRY_ANOTHER
        }
    }
}

object LocationPolicy {
    fun isRecent(eventTime: Long, fixTime: Long, maxAgeMs: Long): Boolean = eventTime - fixTime in 0..maxAgeMs
}
