package com.flashskip.a11y

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
}
