package com.flashskip.experimental

import com.flashskip.core.FreeformViewport
import com.flashskip.core.RectBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingTaskProofTest {
    private val frame = RectBounds(126, 561, 1074, 2102)
    private val viewport = FreeformViewport(frame, frame, 1, frame.copy(bottom = 656), emptyList())

    private fun proves(type: String = "BASE_APPLICATION", mode: String = "freeform",
                       owner: String = "example", left: Int = 126, resume: Boolean = false): Boolean =
        hasFocusedFloatingTask(sequenceOf(
            "mCurrentFocus=Window{45a u0 example/example.MainActivity}",
            "Window #4 Window{45a u0 example/example.MainActivity}:",
            "mOwnerUid=10000 showForAllUsers=false package=$owner appop=NONE",
            "mAttrs={(0,0)(fillxfill) ty=$type fmt=TRANSLUCENT",
            "mFullConfiguration={ winConfig={ mWindowingMode=$mode mActivityType=standard }",
            "Frames: parent=[126,561][1326,2512] frame=[$left,561][1326,2512]"
        ), "example", viewport, resume)

    @Test fun requiresFocusedFreeformBaseActivityWithMatchingOwnerAndBounds() {
        assertTrue(proves())
        assertFalse(proves(mode = "fullscreen"))
        assertFalse(proves(owner = "example.other"))
        assertFalse(proves(left = 0))
        assertFalse(proves(type = "APPLICATION"))
        assertFalse(proves(type = "APPLICATION_OVERLAY"))
    }

    @Test fun unknownWindowTypeRequiresAnExistingResumeHint() {
        assertFalse(proves(type = "99"))
        assertTrue(proves(type = "99", resume = true))
    }
}
