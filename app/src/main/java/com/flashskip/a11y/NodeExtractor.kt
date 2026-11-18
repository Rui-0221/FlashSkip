package com.flashskip.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.flashskip.core.NodeSnapshot
import com.flashskip.core.RectBounds
import com.flashskip.core.WindowSnapshot
import java.io.Closeable

/**
 * Single-pass iterative extractor that converts an Android [AccessibilityNodeInfo]
 * tree into an immutable [WindowSnapshot] while keeping ephemeral references
 * to native nodes for action execution and guaranteeing full [recycle] on [close].
 */
class ExtractedTree(
    val snapshot: WindowSnapshot,
    private val nativeNodes: List<AccessibilityNodeInfo>
) : Closeable {

    fun nodeAt(index: Int): AccessibilityNodeInfo? = nativeNodes.getOrNull(index)

    override fun close() {
        for (node in nativeNodes) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
            }
        }
    }
}

object NodeExtractor {

    const val MAX_NODES = 2000
    const val MAX_DEPTH = 32

    /**
     * Extracts [root] iteratively. Ownership of [root] is transferred to the returned
     * [ExtractedTree] — callers MUST invoke [ExtractedTree.close] in a `use` block.
     */
    fun extract(
        root: AccessibilityNodeInfo,
        activityName: String,
        screenWidth: Int,
        screenHeight: Int,
        maxNodes: Int = MAX_NODES
    ): ExtractedTree {
        val snapshots = ArrayList<NodeSnapshot>(128)
        val natives = ArrayList<AccessibilityNodeInfo>(128)
        val queue = ArrayDeque<PendingNode>()
        queue.addLast(PendingNode(root, parentIndex = -1, depth = 0))

        val rect = Rect()
        val pkg = root.packageName?.toString().orEmpty()
        var rootFullScreen = false

        while (queue.isNotEmpty() && snapshots.size < maxNodes) {
            val item = queue.removeFirst()
            val node = item.node
            val idx = snapshots.size
            natives.add(node)

            node.getBoundsInScreen(rect)
            val bounds = RectBounds(rect.left, rect.top, rect.right, rect.bottom)
            if (idx == 0 && screenWidth > 0 && screenHeight > 0) {
                rootFullScreen = bounds.width >= screenWidth * 0.85f &&
                    bounds.height >= screenHeight * 0.85f
            }

            snapshots.add(
                NodeSnapshot(
                    index = idx,
                    parentIndex = item.parentIndex,
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    text = node.text?.toString().orEmpty(),
                    contentDescription = node.contentDescription?.toString().orEmpty(),
                    bounds = bounds,
                    isVisibleToUser = node.isVisibleToUser,
                    isClickable = node.isClickable,
                    isEnabled = node.isEnabled,
                    depth = item.depth
                )
            )

            if (item.depth < MAX_DEPTH) {
                val count = node.childCount
                for (i in 0 until count) {
                    if (snapshots.size + queue.size >= maxNodes) break
                    val child = node.getChild(i) ?: continue
                    queue.addLast(PendingNode(child, parentIndex = idx, depth = item.depth + 1))
                }
            }
        }

        // Recycle any queued-but-unvisited children if MAX_NODES was hit
        while (queue.isNotEmpty()) {
            try {
                @Suppress("DEPRECATION")
                queue.removeFirst().node.recycle()
            } catch (_: Exception) {
            }
        }

        val win = WindowSnapshot(
            packageName = pkg,
            activityName = activityName,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            isFullScreen = rootFullScreen,
            nodes = snapshots
        )
        return ExtractedTree(win, natives)
    }

    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val parentIndex: Int,
        val depth: Int
    )
}
