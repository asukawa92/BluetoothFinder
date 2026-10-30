package com.example.bluetoothfinder

import org.junit.Assert.*
import org.junit.Test

class MonitoringLogicTest {
    @Test fun duplicateDisconnectIsIgnoredButReconnectResetsIt() {
        val gate = ConnectionGate()
        assertTrue(gate.disconnected("buds", 1_000))
        assertFalse(gate.disconnected("buds", 2_000))
        assertTrue(gate.connected("buds", 3_000))
        assertTrue(gate.disconnected("buds", 4_000))
    }

    @Test fun bluetoothPowerOffDoesNotCreateLossEvent() {
        val gate = ConnectionGate()
        gate.setBluetoothOn(false)
        assertFalse(gate.disconnected("buds", 1_000))
        gate.setBluetoothOn(true)
        assertTrue(gate.connected("buds", 2_000))
        assertTrue(gate.disconnected("buds", 3_000))
    }

    @Test fun rapidPowerCycleInvalidatesEarlierDisconnect() {
        val gate = ConnectionGate()
        assertTrue(gate.disconnected("buds", 1_000))
        gate.setBluetoothOn(false, 2_000)
        gate.setBluetoothOn(true, 3_000)
        assertTrue(gate.powerChangedNear(1_000))
        assertFalse(gate.powerChangedNear(20_000))
    }

    @Test fun medianRejectsOldAndExtremeSignalSamples() {
        val now = 20_000L
        val readings = listOf(RssiPoint(-40, 1_000), RssiPoint(-70, 18_000), RssiPoint(-69, 18_500),
            RssiPoint(-20, 19_000), RssiPoint(-71, 19_500), RssiPoint(-68, 20_000))
        assertEquals(-69, SearchGuidance.median(readings, now))
    }

    @Test fun directionsRequireFreshSamplesAndIgnoreSmallChanges() {
        assertEquals(SearchDirection.WAIT_FOR_SIGNAL, SearchGuidance.decide(-70, null, 0))
        assertEquals(SearchDirection.WAIT_FOR_SIGNAL, SearchGuidance.decide(-70, -60, 0))
        assertEquals(SearchDirection.CONTINUE, SearchGuidance.decide(-70, -63, 3))
        assertEquals(SearchDirection.TURN_BACK, SearchGuidance.decide(-70, -77, 3))
        assertEquals(SearchDirection.TRY_ANOTHER, SearchGuidance.decide(-70, -68, 3))
    }

    @Test fun nearbyIsOnlyAHeuristicWithRepeatedStrongSamples() {
        assertEquals(SearchDirection.NEARBY, SearchGuidance.decide(-60, -45, 3))
        assertEquals(SearchDirection.CONTINUE, SearchGuidance.decide(-60, -45, 3, ignorePeak = true))
    }

    @Test fun locationCannotBeFutureOrTooOld() {
        assertTrue(LocationPolicy.isRecent(120_000, 110_000, 15_000))
        assertFalse(LocationPolicy.isRecent(120_000, 100_000, 15_000))
        assertFalse(LocationPolicy.isRecent(120_000, 121_000, 15_000))
    }
}
