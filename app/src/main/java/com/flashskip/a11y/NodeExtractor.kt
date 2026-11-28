package com.flashskip.a11y

import android.graphics.Rect
import android.os.SystemClock
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
    val windowId: Int get() = nativeNodes.first().windowId

    fun currentTarget(index: Int, node: AccessibilityNodeInfo, bounds: Rect): NodeSnapshot =
        snapshot.nodes[index].copy(
            className = node.className?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            bounds = snapshot.freeform?.local(bounds.bounds()) ?: bounds.bounds(),
            isVisibleToUser = node.isVisibleToUser,
            isEnabled = node.isEnabled
        )

    override fun close() {
        nativeNodes.forEach(::recycleNode)
    }
}

object NodeExtractor {

    const val MAX_NODES = 2000
    const val MAX_DEPTH = 32

    /**
     * Extracts [root] iteratively. Ownership of [root] is transferred to the returned
     * [ExtractedTree] — callers MUST invoke [ExtractedTree.close] in a `use` block.
     */
    internal fun extract(
        root: AccessibilityNodeInfo,
        activityName: String,
        screenWidth: Int,
        screenHeight: Int,
        maxNodes: Int = MAX_NODES,
        refreshDeadlineMs: Long? = null,
        surface: AppSurface? = AppSurface()
    ): ExtractedTree {
        val snapshots = ArrayList<NodeSnapshot>(128)
        val natives = ArrayList<AccessibilityNodeInfo>(128)
        val queue = ArrayDeque<PendingNode>()
        queue.addLast(PendingNode(root, parentIndex = -1, depth = 0))

        val rect = Rect()
        var rootFullScreen = false
        var complete = true

        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (surface == null) {
                queue.clear()
                natives.add(root)
                return ExtractedTree(WindowSnapshot(pkg, activityName, screenWidth, screenHeight, false,
                    emptyList(), complete = false, windowId = root.windowId), natives)
            }
            while (queue.isNotEmpty() && snapshots.size < maxNodes) {
                val item = queue.removeFirst()
                val node = item.node
                if (refreshDeadlineMs != null && (SystemClock.uptimeMillis() >= refreshDeadlineMs ||
                        !runCatching { node.refresh() }.getOrDefault(false))
                ) {
                    recycleNode(node)
                    complete = false
                    break
                }
                val idx = snapshots.size
                natives.add(node)

                node.getBoundsInScreen(rect)
                val bounds = surface.freeform?.local(rect.bounds()) ?: rect.bounds()
                if (idx == 0 && surface.freeform == null && screenWidth > 0 && screenHeight > 0) {
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

                val count = node.childCount
                if (item.depth < MAX_DEPTH) {
                    for (i in 0 until count) {
                        if (snapshots.size + queue.size >= maxNodes) {
                            complete = false
                            break
                        }
                        val child = node.getChild(i)
                        if (child == null) {
                            complete = false
                        } else {
                            queue.addLast(PendingNode(child, parentIndex = idx, depth = item.depth + 1))
                        }
                    }
                } else if (count > 0) {
                    complete = false
                }
            }

            val win = WindowSnapshot(
                packageName = pkg,
                activityName = activityName,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                isFullScreen = rootFullScreen,
                nodes = snapshots,
                complete = complete && queue.isEmpty(),
                windowId = root.windowId.takeIf { it >= 0 },
                freeform = surface.freeform,
                obstructions = surface.obstructions,
                behindTopWindow = surface.behindTopWindow
            )
            return ExtractedTree(win, natives)
        } catch (e: Exception) {
            natives.forEach(::recycleNode)
            throw e
        } finally {
            queue.forEach { recycleNode(it.node) }
        }
    }

    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val parentIndex: Int,
        val depth: Int
    )
}

private fun recycleNode(node: AccessibilityNodeInfo) {
    try {
        @Suppress("DEPRECATION")
        node.recycle()
    } catch (_: IllegalStateException) {
    }
}
