package com.ai.assistance.apex.engine.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream

class EngineAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "EngineAccessibilityService"
        private var instance: EngineAccessibilityService? = null

        fun getInstance(): EngineAccessibilityService? = instance

        fun isServiceEnabled(): Boolean {
            return instance != null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performClick(x: Int, y: Int): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val node = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: rootNode

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.contains(x, y)) {
                val clickResult = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return clickResult
            }

            node.recycle()
            false
        } catch (e: Exception) {
            Log.e(TAG, "performClick($x, $y) failed", e)
            false
        }
    }

    fun performLongPress(x: Int, y: Int): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val node = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: rootNode

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.contains(x, y)) {
                val clickResult = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                node.recycle()
                return clickResult
            }

            node.recycle()
            false
        } catch (e: Exception) {
            Log.e(TAG, "performLongPress($x, $y) failed", e)
            false
        }
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
        return try {
            val path = android.graphics.Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }

            val gestureDescription = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path,
                        0,
                        duration
                    )
                )
                .build()

            dispatchGesture(gestureDescription, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "performSwipe failed", e)
            false
        }
    }

    fun triggerGlobalAction(actionId: Int): Boolean {
        return super.performGlobalAction(actionId)
    }

    fun findFocusedNodeId(): String? {
        return try {
            val rootNode = rootInActiveWindow ?: return null
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val nodeId = focusedNode?.viewIdResourceName
            focusedNode?.recycle()
            rootNode.recycle()
            nodeId
        } catch (e: Exception) {
            Log.e(TAG, "findFocusedNodeId failed", e)
            null
        }
    }

    fun setTextOnNode(nodeId: String, text: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(nodeId)

            if (nodes.isNotEmpty()) {
                val node = nodes[0]
                val arguments = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                node.recycle()
                rootNode.recycle()
                return result
            }

            rootNode.recycle()
            false
        } catch (e: Exception) {
            Log.e(TAG, "setTextOnNode($nodeId) failed", e)
            false
        }
    }

    fun getUiHierarchy(): String {
        return try {
            val rootNode = rootInActiveWindow ?: return ""
            val hierarchy = StringBuilder()
            buildHierarchy(rootNode, 0, hierarchy)
            rootNode.recycle()
            hierarchy.toString()
        } catch (e: Exception) {
            Log.e(TAG, "getUiHierarchy failed", e)
            ""
        }
    }

    private fun buildHierarchy(node: AccessibilityNodeInfo, depth: Int, builder: StringBuilder) {
        val indent = "  ".repeat(depth)
        val id = node.viewIdResourceName ?: "no-id"
        val text = node.text?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        builder.append("$indent[$className] id=$id text=\"$text\" desc=\"$contentDesc\"\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                buildHierarchy(child, depth + 1, builder)
                child.recycle()
            }
        }
    }

    fun getCurrentActivityName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    fun takeScreenshot(path: String, format: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val bounds = Rect()
            rootNode.getBoundsInScreen(bounds)

            rootNode.recycle()

            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)

            val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            val file = File(path)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            bitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot failed", e)
            false
        }
    }
}