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
        return ScreenSnapshot(timestampMs, root.packageName?.toString().orEmpty(), nodes)
    }

    // recycle() is a no-op from API 33 but still frees nodes on the API 30-32 phones minSdk allows
    @Suppress("DEPRECATION")
    private fun collect(node: AccessibilityNodeInfo, depth: Int, out: MutableList<ScreenNode>) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        out += ScreenNode(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled
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
}
