package com.example.whatsappredial

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class WhatsAppRedialService : AccessibilityService() {

    companion object {
        var isRunning = false
        var contactDataId: Long = -1L
        var maxCalls: Int = 10
        var currentCallCount: Int = 0
        var intervalSeconds: Int = 1
        var limitDuration: Boolean = false
        private var instance: WhatsAppRedialService? = null

        fun startRedial(context: Context, dataId: Long, totalCalls: Int, intervalSec: Int, durationLimit: Boolean) {
            contactDataId = dataId
            maxCalls = totalCalls
            intervalSeconds = intervalSec.coerceAtLeast(1)
            limitDuration = durationLimit
            currentCallCount = 0
            isRunning = true
            instance?.initiateNextCall(context)
        }

        fun stopRedial() {
            isRunning = false
            instance?.handler?.removeCallbacksAndMessages(null)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var inWhatsAppCallScreen = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun initiateNextCall(context: Context) {
        if (!isRunning || contactDataId == -1L) return
        if (currentCallCount >= maxCalls) {
            isRunning = false
            showFullError(context, "Auto Redial Completed")
            return
        }

        currentCallCount++
        try {
            val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, contactDataId)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // SHAREit මඟහැරීම සඳහා WhatsApp හි නිවැරදි Component එක සොයාගැනීම
            val resolveInfoList = context.packageManager.queryIntentActivities(intent, 0)
            var targetComponent: ComponentName? = null
            
            for (info in resolveInfoList) {
                val pkg = info.activityInfo.packageName
                if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                    targetComponent = ComponentName(pkg, info.activityInfo.name)
                    break
                }
            }
            
            if (targetComponent != null) {
                intent.component = targetComponent
            } else {
                intent.setPackage("com.whatsapp")
            }

            context.startActivity(intent)

            if (limitDuration) {
                handler.postDelayed({
                    if (isRunning) endCurrentWhatsAppCall()
                }, 30_000L)
            }
        } catch (e: SecurityException) {
            showFullError(context, "Permission Denial Error: ${e.message}")
            isRunning = false
        } catch (e: Exception) {
            showFullError(context, "Error: ${e.message}")
            isRunning = false
        }
    }

    // Error එක සම්පූර්ණයෙන් පෙන්වීම සඳහා Toast එකේ කාලය වැඩිකර ඇත
    private fun showFullError(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val className = event.className?.toString() ?: ""
        val rootNode = rootInActiveWindow ?: return

        clickCallConfirmationIfPresent(rootNode)

        if (className.contains("VoipActivity") || className.contains("Call")) {
            inWhatsAppCallScreen = true
        } else if (inWhatsAppCallScreen && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            inWhatsAppCallScreen = false
            if (isRunning && currentCallCount < maxCalls) {
                handler.postDelayed({
                    initiateNextCall(applicationContext)
                }, intervalSeconds * 1000L)
            } else if (currentCallCount >= maxCalls) {
                isRunning = false
            }
        }
    }

    private fun clickCallConfirmationIfPresent(node: AccessibilityNodeInfo) {
        val callNodes = node.findAccessibilityNodeInfosByText("Call")
        for (item in callNodes) {
            if (item.isClickable && item.className?.contains("Button") == true) {
                item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            }
        }
    }

    private fun endCurrentWhatsAppCall() {
        val rootNode = rootInActiveWindow ?: return
        val endButtons = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/end_call_button")
        for (btn in endButtons) {
            if (btn.isClickable) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
    }

    override fun onInterrupt() {}
}
