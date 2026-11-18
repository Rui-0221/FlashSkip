package com.flashskip

import com.flashskip.core.NodeSnapshot
import com.flashskip.core.RectBounds
import com.flashskip.core.SnapshotJson
import com.flashskip.core.WindowSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotJsonAndConfigTest {

    @Test
    fun `snapshot json escapes special characters cleanly`() {
        val snap = WindowSnapshot(
            packageName = "com.example.test",
            activityName = "com.example.test.SplashActivity",
            screenWidth = 1080,
            screenHeight = 2400,
            isFullScreen = true,
            nodes = listOf(
                NodeSnapshot(
                    index = 0,
                    parentIndex = -1,
                    className = "android.widget.Button",
                    viewId = "com.example.test:id/skip",
                    text = "跳过 \"3s\"\n",
                    contentDescription = "",
                    bounds = RectBounds(860, 120, 1040, 210),
                    isVisibleToUser = true,
                    isClickable = true,
                    isEnabled = true,
                    depth = 0
                )
            )
        )
        val json = SnapshotJson.toJson(snap)
        assertTrue(json.contains("\"packageName\": \"com.example.test\""))
        assertTrue(json.contains("\\\"3s\\\"\\n"))
    }
}
