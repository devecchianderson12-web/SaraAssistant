package com.sara.core.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPolicyTest {
    private val idle = ListenConditions(bluetoothAudio = false, charging = false, screenOn = false, hour = 15)

    @Test fun smartListensInTheCarOrCharging() {
        val smart = SmartListening()
        assertFalse(ListenPolicy.shouldListen(ListenMode.SMART, smart, idle))
        assertTrue(ListenPolicy.shouldListen(ListenMode.SMART, smart, idle.copy(bluetoothAudio = true)))
        assertTrue(ListenPolicy.shouldListen(ListenMode.SMART, smart, idle.copy(charging = true)))
        // Pantalla encendida sólo si el usuario lo eligió.
        assertFalse(ListenPolicy.shouldListen(ListenMode.SMART, smart, idle.copy(screenOn = true)))
        assertTrue(ListenPolicy.shouldListen(ListenMode.SMART, smart.copy(screenOn = true), idle.copy(screenOn = true)))
    }

    @Test fun scheduleCrossesMidnight() {
        assertTrue(ListenPolicy.inSchedule(23, 22, 7))
        assertTrue(ListenPolicy.inSchedule(3, 22, 7))
        assertFalse(ListenPolicy.inSchedule(12, 22, 7))
        assertTrue(ListenPolicy.inSchedule(10, 8, 23))
        assertFalse(ListenPolicy.inSchedule(23, 8, 23))
    }

    @Test fun alwaysAndAppOnlyListen() {
        assertTrue(ListenPolicy.shouldListen(ListenMode.ALWAYS, SmartListening(), idle))
        assertTrue(ListenPolicy.shouldListen(ListenMode.APP_ONLY, SmartListening(), idle))
    }
}
