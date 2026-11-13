package com.ubers.observador.service

import android.view.accessibility.AccessibilityNodeInfo
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot

/**
 * Copies the accessibility tree into a [ScreenSnapshot] in one depth-first pass. Nothing else walks
 * the tree: the offer parser and every screen detector read the copy. Child nodes are recycled as
 * soon as they are copied, so no AccessibilityNodeInfo is held after this returns.
 */
object ScreenSnapshotFactory {

    /** Far above any Uber screen seen so far; only a runaway tree is cut. */
    private const val MAX_NODES = 2_000
    private const val MAX_DEPTH = 80

    fun from(root: AccessibilityNodeInfo, timestampMs: Long): ScreenSnapshot {
        val nodes = ArrayList<ScreenNode>()
        collect(root, 0, nodes)
        return ScreenSnapshot(timestampMs, root.packageName?.toString().orEmpty(), nodes, root.windowId)
    }

    // recycle() is a no-op from API 33 but still frees nodes on the API 30-32 phones minSdk allows
    @Suppress("DEPRECATION")
    private fun collect(node: AccessibilityNodeInfo, depth: Int, out: MutableList<ScreenNode>) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        out += ScreenNode(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            left = r.left,
            top = r.top,
            right = r.right,
            bottom = r.bottom,
            visibleToUser = node.isVisibleToUser
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collect(child, depth + 1, out)
            } finally {
                child.recycle()
            }
        }
    }

    /**
     * 1.37.0: the live node at [index] of the same depth-first walk as [from] (same limits, same
     * order), as a copy the caller must recycle; null if the tree is shorter. Only used right after
     * a snapshot of the same [root], to touch the X found in it.
     */
    @Suppress("DEPRECATION")
    fun nodeAt(root: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return null
            if (count++ == index) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    walk(child, depth + 1)?.let { return it }
                } finally {
                    child.recycle()
                }
            }
            return null
        }
        return walk(root, 0)
    }
}
