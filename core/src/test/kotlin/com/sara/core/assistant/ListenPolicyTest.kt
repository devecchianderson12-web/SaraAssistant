package com.sara.core.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPolicyTest {
    private val idle = ListenConditions(appOpen = false, bluetoothAudio = false, charging = false, screenOn = false, hour = 15)

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

    @Test fun alwaysListens() = assertTrue(ListenPolicy.shouldListen(ListenMode.ALWAYS, SmartListening(), idle))

    @Test fun appOpenCombinesWithTheCar() {
        val both = SmartListening(appOpen = true, bluetooth = true, charging = false)
        // App abierta: escucha (para decirle «conectate al auto»).
        assertTrue(ListenPolicy.shouldListen(ListenMode.SMART, both, idle.copy(appOpen = true)))
        // App cerrada pero en el auto: sigue escuchando.
        assertTrue(ListenPolicy.shouldListen(ListenMode.SMART, both, idle.copy(bluetoothAudio = true)))
        // App cerrada y se bajó del auto: micrófono cerrado.
        assertFalse(ListenPolicy.shouldListen(ListenMode.SMART, both, idle))
        assertTrue(ListenPolicy.keepsRunningWhenAppClosed(ListenMode.SMART, both))
        assertFalse(ListenPolicy.keepsRunningWhenAppClosed(ListenMode.SMART, SmartListening(appOpen = true, bluetooth = false, charging = false)))
    }
}
