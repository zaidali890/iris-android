package com.iris.android.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

class IrisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need continuous event handling — actions are performed on demand, see below.
    }

    override fun onInterrupt() {}

    /** Searches the current window for a clickable node matching any of the given texts/descriptions. */
    private fun findClickableNode(candidates: List<String>): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (candidate in candidates) {
            val byText = root.findAccessibilityNodeInfosByText(candidate)
            for (node in byText) {
                val clickable = findClickableAncestorOrSelf(node)
                if (clickable != null) return clickable
            }
        }
        return null
    }

    private fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Waits briefly for WhatsApp's chat screen to render (after the click-to-chat intent opens it
     * with the message pre-filled), then taps the Send button. Returns true if a send button was found and tapped.
     */
    suspend fun tapWhatsAppSend(): Boolean {
        // Cold-starting WhatsApp from scratch can take a few seconds to render the chat screen,
        // so this waits up to ~9 seconds total rather than giving up after 3 — that was the main
        // reason auto-send was unreliable. Also tries a couple of label variants WhatsApp uses.
        repeat(30) {
            val node = findClickableNode(listOf("Send", "Send message"))
            if (node != null) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) return true
            }
            delay(300)
        }
        return false
    }

    companion object {
        var instance: IrisAccessibilityService? = null
        fun isEnabled(): Boolean = instance != null
    }
}
