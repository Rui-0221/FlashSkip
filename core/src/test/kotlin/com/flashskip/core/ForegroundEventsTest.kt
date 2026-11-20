package com.flashskip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundEventsTest {
    private val previous = ForegroundWindow("com.example.previous", 7)
    private val destination = ForegroundWindow("com.example.destination", 8)

    @Test
    fun `window signal gets bounded new root probes when foreground is not ready`() {
        val events = ForegroundEvents()
        assertNull(events.observeRoot(previous, 900))
        events.onWindowSignal(destination.id, 1000)
        assertEquals(1032L, events.observeRoot(null, 1000))
        assertEquals(1080L, events.observeRoot(previous, 1032))
        assertNull(events.observeRoot(destination, 1080))
        assertEquals(destination, events.verifiedWindow)
        assertNull(events.observeRoot(null, 1200))
    }

    @Test
    fun `unarmed content can request a bounded check for a different valid window`() {
        val events = ForegroundEvents()
        events.observeRoot(previous, 900)
        assertFalse(events.reserveUnarmedContent(previous, 1000))
        assertFalse(events.reserveUnarmedContent(destination.copy(id = -1), 1000))
        assertTrue(events.reserveUnarmedContent(destination, 1000))
        assertFalse(events.reserveUnarmedContent(destination, 1020))
        assertTrue(events.reserveUnarmedContent(destination, 1240))
        events.observeRoot(destination, 1241)
        assertFalse(events.reserveUnarmedContent(destination, 1500))
        assertNull(events.observeRoot(null, 1600))
    }

    @Test
    fun `older window state must agree with real foreground before being used as navigation evidence`() {
        val events = ForegroundEvents()
        events.observeRoot(destination, 1000)
        assertFalse(events.acceptsStateHint(previous, destination))
        assertFalse(events.acceptsStateHint(destination, null))
        assertTrue(events.acceptsStateHint(destination, destination))
    }
}
