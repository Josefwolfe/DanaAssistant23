package ir.dana.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class DanaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DanaAccessibility"
        var instance: DanaAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "سرویس دسترسی‌پذیری و بینایی دانا متصل شد.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "سرویس دسترسی‌پذیری دانا متوقف شد.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    suspend fun captureScreenBase64(): String? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            hardwareBuffer.close()

                            if (hwBitmap == null) {
                                cont.resume(null)
                                return
                            }
                            val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()

                            val scaled = Bitmap.createScaledBitmap(
                                softBitmap,
                                (softBitmap.width * 0.5f).toInt().coerceAtLeast(360),
                                (softBitmap.height * 0.5f).toInt().coerceAtLeast(640),
                                true
                            )
                            softBitmap.recycle()

                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 72, baos)
                            scaled.recycle()

                            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            cont.resume(base64)
                        } catch (e: Exception) {
                            Log.e(TAG, "خطا در تبدیل اسکرین‌شات", e)
                            cont.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "عدم موفقیت در ثبت اسکرین‌شات: $errorCode")
                        cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            cont.resume(null)
        }
    }

    fun dumpScreenHierarchy(maxNodes: Int = 85): String {
        val root = rootInActiveWindow ?: return "[]"
        val elements = JSONArray()
        val packageName = root.packageName?.toString() ?: "unknown"

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || elements.length() >= maxNodes) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else ""
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val hasMeaning = text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || isEditable || isClickable
            if (node.isVisibleToUser && hasMeaning && bounds.width() > 0 && bounds.height() > 0) {
                val obj = JSONObject().apply {
                    put("pkg", packageName)
                    if (text.isNotEmpty()) put("text", text.take(100))
                    if (desc.isNotEmpty()) put("desc", desc.take(100))
                    if (hint.isNotEmpty()) put("hint", hint.take(60))
                    if (viewId.isNotEmpty()) put("id", viewId)
                    put("clickable", isClickable)
                    put("editable", isEditable)
                    put("scrollable", isScrollable)
                    put("centerX", bounds.centerX())
                    put("centerY", bounds.centerY())
                }
                elements.put(obj)
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return elements.toString()
    }

    fun readVisibleScreenText(): String {
        val root = rootInActiveWindow ?: return "صفحه‌ای برای خواندن پیدا نشد."
        val texts = mutableListOf<String>()

        fun collectText(node: AccessibilityNodeInfo?) {
            if (node == null || texts.size >= 15) return
            if (node.isVisibleToUser) {
                val t = node.text?.toString()?.trim().orEmpty()
                if (t.length >= 2 && !texts.contains(t)) {
                    texts.add(t)
                }
            }
            for (i in 0 until node.childCount) {
                collectText(node.getChild(i))
            }
        }

        collectText(root)
        return if (texts.isEmpty()) {
            "متن قابل خواندنی در این صفحه دیده نمی‌شود."
        } else {
            "محتوای صفحه: " + texts.joinToString("، ")
        }
    }

    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = findNodeByTextOrDesc(root, query) ?: return false

        var clickableParent: AccessibilityNodeInfo? = targetNode
        while (clickableParent != null && !clickableParent.isClickable) {
            clickableParent = clickableParent.parent
        }

        if (clickableParent != null && clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        val bounds = Rect()
        targetNode.getBoundsInScreen(bounds)
        return tapCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    fun clickSearchButtonInMessenger(): Boolean {
        val searchCandidates = listOf("جستجو", "Search", "search", "menu_search", "action_search")
        for (candidate in searchCandidates) {
            if (clickByText(candidate)) return true
        }
        val dm = resources.displayMetrics
        return tapCoordinates(dm.widthPixels * 0.90f, dm.heightPixels * 0.07f)
    }

    fun clickSendButtonInMessenger(): Boolean {
        val sendKeywords = listOf("ارسال", "Send", "send", "chat_send_button", "btn_send")
        for (keyword in sendKeywords) {
            if (clickByText(keyword)) return true
        }
        return false
    }

    fun tapCoordinates(x: Float, y: Float, durationMs: Long = 100L): Boolean {
        if (x < 0 || y < 0) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPressByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = findNodeByTextOrDesc(root, query) ?: return false
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val bounds = Rect()
        targetNode.getBoundsInScreen(bounds)
        return tapCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat(), durationMs = 700L)
    }

    fun inputText(textToType: String, targetFieldQuery: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false

        var targetNode: AccessibilityNodeInfo? = null
        if (!targetFieldQuery.isNullOrBlank()) {
            targetNode = findNodeByTextOrDesc(root, targetFieldQuery)
        }
        if (targetNode == null || !targetNode.isEditable) {
            targetNode = findFirstEditableNode(root)
        }

        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    fun scrollScreen(direction: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val w = displayMetrics.widthPixels.toFloat()
        val h = displayMetrics.heightPixels.toFloat()

        return when (direction.lowercase()) {
            "down", "پایین", "بعدی" -> swipe(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.25f, 350L)
            "up", "بالا", "قبلی" -> swipe(w * 0.5f, h * 0.25f, w * 0.5f, h * 0.75f, 350L)
            "left", "چپ" -> swipe(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f, 350L)
            "right", "راست" -> swipe(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f, 350L)
            else -> swipe(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.25f, 350L)
        }
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 350L): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun executeGlobalAction(actionName: String): Boolean {
        return when (actionName.uppercase()) {
            "BACK", "بازگشت", "برگرد" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME", "خانه", "صفحه اصلی" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS", "برنامه‌های اخیر" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS", "اعلان‌ها", "نوتیفیکیشن" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS", "تنظیمات سریع" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "SCREENSHOT", "اسکرین شات" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                } else false
            }
            "LOCK_SCREEN", "قفل صفحه" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else false
            }
            else -> false
        }
    }

    private fun findNodeByTextOrDesc(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val q = query.trim().lowercase()

        val text = node.text?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.lowercase().orEmpty()
        } else ""
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()

        if (node.isVisibleToUser && (text.contains(q) || desc.contains(q) || hint.contains(q) || viewId.contains(q))) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = findNodeByTextOrDesc(node.getChild(i), query)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
