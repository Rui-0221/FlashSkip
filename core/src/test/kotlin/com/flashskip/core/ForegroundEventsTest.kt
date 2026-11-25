package com.flashskip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundEventsTest {
    @Test fun floatingReturnIsBoundToPreviouslyObservedResumeSessionOriginAndCaption() {
        val events = ForegroundEvents()
        val business = ForegroundWindow("example", 2)
        val ad = ForegroundWindow("example", 3)
        events.rememberReturnWindow(business)
        events.recordFocusedApplication(ForegroundWindow("launcher", 1))
        events.onResumeWindowHint(ad, 1000)
        events.trackFloatingLaunch(4, ad, 5, 1100)
        assertNull(events.returnWindowFor(ad, 5100))
        assertEquals(business, events.floatingDestination(4, ad, 5))
        assertNull(events.floatingDestination(7, ad, 5))
        assertNull(events.floatingDestination(4, business, 5))
        assertNull(events.floatingDestination(4, ad, 8))
        events.trackFloatingLaunch(7, ad, 5, 5100)
        assertNull(events.floatingDestination(7, ad, 5))
    }
    @Test fun lateBusinessWindowHintCannotReplaceNewerFocusedAdHint() {
        val events = ForegroundEvents()
        events.recordFocusedApplication(ForegroundWindow("launcher", 1))
        val ad = ForegroundWindow("example", 3)
        events.onResumeWindowHint(ad, 1000)
        events.onResumeWindowHint(ForegroundWindow("example", 2), 980)
        org.junit.Assert.assertEquals(ad, events.resumeWindow(1100))
    }
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

    @Test
    fun `a departure signal probes past the old root and stops after the focused app changes`() {
        val events = ForegroundEvents()
        events.observeRoot(previous, 900L)
        events.onForegroundSignal(1000L)
        events.onWindowSignal(previous.id, 1001L)
        assertEquals(1032L, events.observeRoot(previous, 1001L))
        assertEquals(1080L, events.observeRoot(null, 1032L))
        assertNull(events.observeRoot(destination, 1080L))
        assertNull(events.observeRoot(null, 1140L))
    }

    @Test
    fun `resume window hints schedule late probes without renewing the deadline`() {
        val events = ForegroundEvents()
        events.recordFocusedApplication(previous)
        events.onResumeWindowHint(destination, 1000L)
        events.recordFocusedApplication(destination)
        assertEquals(1140L, events.observeRoot(destination, 1000L))
        assertEquals(1900L, events.observeRoot(destination, 1450L))
        val replacement = destination.copy(id = 99)
        events.onResumeWindowHint(replacement, 3300L)
        assertEquals(replacement, events.resumeWindow(3500L))
        assertNull(events.resumeWindow(4800L))
        events.cancelWindowProbes()
        assertNull(events.observeRoot(replacement, 3600L))
    }

    @Test
    fun `unmapped window keeps the original application window as return evidence`() {
        val events = ForegroundEvents()
        events.recordFocusedApplication(previous)
        events.rememberReturnWindow(previous)
        events.recordFocusedApplication(destination)
        events.onResumeWindowHint(previous.copy(id = 20), 1000L)
        val overlay = previous.copy(id = 20)
        events.recordFocusedApplication(overlay)
        assertEquals(previous, events.returnWindowFor(overlay, 1200L))
        assertNull(events.returnWindowFor(overlay.copy(id = 21), 1200L))
        assertNull(events.returnWindowFor(overlay, 4800L))
        events.recordFocusedApplication(previous)
        assertNull(events.returnWindowFor(previous, 1400L))
    }

    @Test
    fun `a cached same-package window alone cannot confirm a return`() {
        val events = ForegroundEvents()
        events.recordFocusedApplication(previous)
        events.rememberReturnWindow(previous)
        val navigation = previous.copy(id = 20)
        events.recordFocusedApplication(navigation)

        assertNull(events.returnWindowFor(navigation, 1200L))
    }

    @Test
    fun `ordinary window changes and missing roots cannot simulate leaving and returning`() {
        val events = ForegroundEvents()
        assertFalse(events.recordFocusedApplication(previous))
        assertFalse(events.recordFocusedApplication(previous.copy(id = 9)))
        events.observeRoot(null, 1000L)
        assertFalse(events.recordFocusedApplication(previous))
        assertTrue(events.recordFocusedApplication(destination))
        assertTrue(events.recordFocusedApplication(previous))
        assertFalse(events.recordFocusedApplication(previous))
    }
}
