package com.flashskip.a11y

import com.flashskip.core.FreeformViewport
import com.flashskip.core.RectBounds
import org.junit.Assert.*
import org.junit.Test

class FloatingWindowLayersTest {
    private val frame = RectBounds(126, 561, 1074, 2102)
    private val target = WindowLayer(21, 1, frame, true, false, true, true, true, false)
    private val caption = target.copy(id = 22, layer = 2, bounds = frame.copy(bottom = 656),
        focused = false, active = false, titled = false)
    private val background = target.copy(id = 20, layer = 0, bounds = RectBounds(0, 0, 1200, 2664),
        focused = false, active = false)

    @Test fun gestureRetryRescansAfterALateTargetRefreshOrForegroundSignal() {
        assertTrue(isFreshGestureRetry(1000L, 1096L, foregroundChanged = false))
        assertFalse(isFreshGestureRetry(1000L, 1097L, foregroundChanged = false))
        assertFalse(isFreshGestureRetry(1000L, 1495L, foregroundChanged = false))
        assertFalse(isFreshGestureRetry(1000L, 1005L, foregroundChanged = true))
    }

    @Test fun focusedFullscreenInputRechecksFrameFocusAndHigherLayers() {
        val owner = background.copy(focused = true, active = true)
        val button = RectBounds(1000, 150, 1110, 210)
        val strip = caption.copy(bounds = RectBounds(0, 0, 1200, 120))
        assertTrue(listOf(owner, strip).allowsFocusedFullscreenInput(owner.id, owner.bounds, button))
        assertTrue(listOf(owner.copy(active = false), strip).allowsFocusedFullscreenInput(owner.id, owner.bounds, button))
        assertFalse(listOf(owner, strip.copy(bounds = button)).allowsFocusedFullscreenInput(owner.id, owner.bounds, button))
        assertFalse(listOf(owner, strip.copy(active = true)).allowsFocusedFullscreenInput(owner.id, owner.bounds, button))
        assertFalse(listOf(owner.copy(focused = false)).allowsFocusedFullscreenInput(owner.id, owner.bounds, button))
        assertFalse(listOf(owner.copy(bounds = owner.bounds.copy(top = 8))).allowsFocusedFullscreenInput(owner.id, owner.bounds, button))
    }

    @Test fun unscaledFloatingInputRequiresTheSameCaptionAndUnobstructedPhysicalTarget() {
        val viewport = FreeformViewport(frame, frame, caption.id, caption.bounds, emptyList(), background.bounds)
        val button = RectBounds(50, 220, 140, 280)
        val stack = listOf(target, caption, background)
        assertTrue(stack.allowsFocusedUnscaledInput(target.id, viewport, button))
        assertFalse(stack.allowsFocusedUnscaledInput(target.id, viewport.copy(nodeRoot = frame.copy(right = 1073)), button))
        assertFalse(stack.allowsFocusedUnscaledInput(target.id, viewport, button.copy(top = 25, bottom = 60)))
        assertFalse(listOf(target, caption.copy(id = 24), background).allowsFocusedUnscaledInput(target.id, viewport, button))
        assertFalse(listOf(target, caption.copy(bounds = caption.bounds.copy(bottom = 655)), background)
            .allowsFocusedUnscaledInput(target.id, viewport, button))
        val overlay = background.copy(id = 27, layer = 3, application = false,
            bounds = requireNotNull(viewport.screenTarget(button)))
        assertFalse((stack + overlay).allowsFocusedUnscaledInput(target.id, viewport, button))
        assertFalse((stack + background.copy(id = 27, active = true))
            .allowsFocusedUnscaledInput(target.id, viewport, button))
    }

    @Test fun sidebarAndBackgroundAreNeverSelectedInsteadOfTheTask() {
        val stack = listOf(caption, background, target)
        assertEquals(caption, stack.captionFor(target.id, frame))
        assertNull(stack.captionFor(background.id, background.bounds))
        assertNull(stack.captionFor(caption.id, caption.bounds))
        assertNull((stack + background.copy(id = 11, layer = 3, application = false, focused = true)).captionFor(target.id, frame))
        assertNull(listOf(caption, target, background.copy(active = true)).captionFor(target.id, frame))
    }

    @Test fun modalDialogAndOverlayAreRejectedEvenIfActiveAndFocused() {
        val dialog = target.copy(bounds = RectBounds(235, 930, 965, 1490))
        assertNull(listOf(caption, dialog, background).captionFor(dialog.id, dialog.bounds))
        assertNull(listOf(target, background).captionFor(target.id, frame))
        assertNull(listOf(caption, target.copy(pip = true)).captionFor(target.id, frame))
        assertNull(listOf(caption, target.copy(application = false, unmapped = true)).captionFor(target.id, frame))
        assertNull(listOf(caption, target, background.copy(id = 8, layer = 3)).captionFor(target.id, frame))
    }

    @Test fun topStripCanHoldFocusWithoutBecomingTheFloatingTask() {
        val owner = target.copy(focused = false, active = false)
        val banner = target.copy(id = 30, layer = 3, bounds = RectBounds(150, 610, 1050, 790))
        val stack = listOf(banner, caption, owner, background)
        assertEquals(banner, stack.competingBanner(owner))
        assertEquals(caption, stack.captionFor(owner.id, frame, bannerId = banner.id))
        assertEquals(setOf(caption.bounds, banner.bounds), stack.higherObstructions(owner).toSet())
        assertNull(stack.competingBanner(background))
        assertNull(listOf(banner.copy(application = false), caption, owner).competingBanner(owner))
        assertNull(listOf(banner.copy(bounds = frame.copy(top = 930)), caption, owner).competingBanner(owner))
        assertNull((stack + background.copy(id = 31, layer = 4, active = true)).competingBanner(owner))
    }

    @Test fun higherFullScreenDialogBlocksReadingEvenWithAShallowBanner() {
        val owner = background.copy(focused = false, active = false)
        val banner = target.copy(bounds = RectBounds(20, 70, 1180, 380))
        val dialog = owner.copy(id = 30, layer = 2, bounds = RectBounds(160, 310, 1090, 900))
        assertEquals(banner, listOf(banner, owner).competingBanner(owner))
        assertNull(listOf(dialog, banner, owner).competingBanner(owner))
        assertNull(listOf(banner.copy(layer = -1), owner).competingBanner(owner))
    }
}
