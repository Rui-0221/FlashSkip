package com.flashskip.core

/**
 * Pure-Kotlin JSON serializer for [WindowSnapshot] so real device UI trees
 * can be exported for debugging and replayed verbatim in JVM regression tests.
 */
object SnapshotJson {

    fun toJson(snapshot: WindowSnapshot): String = buildString {
        append("{\n")
        append("  \"packageName\": ").append(quote(snapshot.packageName)).append(",\n")
        append("  \"activityName\": ").append(quote(snapshot.activityName)).append(",\n")
        append("  \"screenWidth\": ").append(snapshot.screenWidth).append(",\n")
        append("  \"screenHeight\": ").append(snapshot.screenHeight).append(",\n")
        append("  \"isFullScreen\": ").append(snapshot.isFullScreen).append(",\n")
        snapshot.freeform?.let { viewport ->
            append("  \"windowId\": ").append(snapshot.windowId).append(",\n")
            append("  \"freeform\": {\"frame\": ").append(rect(viewport.frame))
            append(", \"nodeRoot\": ").append(rect(viewport.nodeRoot))
            append(", \"captionId\": ").append(viewport.captionId)
            append(", \"caption\": ").append(rect(viewport.caption))
            append(", \"obstructions\": ").append(viewport.obstructions.joinToString(prefix = "[", postfix = "]") { rect(it) })
            append("},\n")
        }
        append("  \"nodes\": [\n")
        snapshot.nodes.forEachIndexed { i, n ->
            append("    {")
            append("\"index\": ").append(n.index).append(", ")
            append("\"parentIndex\": ").append(n.parentIndex).append(", ")
            append("\"depth\": ").append(n.depth).append(", ")
            append("\"className\": ").append(quote(n.className)).append(", ")
            append("\"viewId\": ").append(quote(n.viewId)).append(", ")
            append("\"text\": ").append(quote(n.text.take(64))).append(", ")
            append("\"contentDescription\": ").append(quote(n.contentDescription.take(64))).append(", ")
            append("\"bounds\": [")
                .append(n.bounds.left).append(',')
                .append(n.bounds.top).append(',')
                .append(n.bounds.right).append(',')
                .append(n.bounds.bottom).append("], ")
            append("\"visible\": ").append(n.isVisibleToUser).append(", ")
            append("\"clickable\": ").append(n.isClickable).append(", ")
            append("\"enabled\": ").append(n.isEnabled)
            append('}')
            if (i < snapshot.nodes.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append('}')
    }

    private fun rect(bounds: RectBounds) = "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }
}
