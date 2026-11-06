package com.example.whatsappredial

import android.accessibilityservice.AccessibilityService
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
            instance?.callState = 0
            instance?.initiateNextCall(context)
        }

        fun stopRedial() {
            isRunning = false
            instance?.handler?.removeCallbacksAndMessages(null)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    var callState = 0 // 0: Idle (නිකම් ඉන්නවා), 1: Initiated (කෝල් එක ගත්තා), 2: Call active on screen (කෝල් එක තිරයේ තියෙනවා)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun initiateNextCall(context: Context) {
        if (!isRunning || contactDataId == -1L) return
        if (currentCallCount >= maxCalls) {
            isRunning = false
            Toast.makeText(context, "Auto Redial Completed", Toast.LENGTH_LONG).show()
            return
        }

        currentCallCount++
        callState = 1 // කෝල් එක ගන්න පටන් ගත්තා කියලා මාර්ක් කිරීම
        try {
            val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, contactDataId)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(intent)

            if (limitDuration) {
                handler.postDelayed({
                    if (isRunning) endCurrentWhatsAppCall()
                }, 30_000L)
            }
        } catch (e: SecurityException) {
            Toast.makeText(context, "Call Permission Denied. Please grant Phone Call permission.", Toast.LENGTH_LONG).show()
            isRunning = false
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            isRunning = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val rootNode = rootInActiveWindow ?: return

        clickCallConfirmationIfPresent(rootNode)

        // කෝල් එකේ තත්ත්වය බැලීමට රතු පාට End call බටන් එක තිරයේ තියෙනවද යන්න සෙවීම
        val endButtons = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/end_call_button")
        val endByText = rootNode.findAccessibilityNodeInfosByText("End call")
        val isCallUIActive = endButtons.isNotEmpty() || endByText.isNotEmpty()

        if (callState == 1 && isCallUIActive) {
            // කෝල් එක තිරයට ආවා (රින්ග් වීම ආරම්භ වුණා)
            callState = 2
        } else if (callState == 2 && !isCallUIActive) {
            // කෝල් තිරය නැති වුණා (කෝල් එක කට් වුණා හෝ ඉවර වුණා)
            callState = 0
            if (isRunning && currentCallCount < maxCalls) {
                handler.postDelayed({
                    initiateNextCall(applicationContext)
                }, intervalSeconds * 1000L) // දී ඇති තත්පර ගණනක් සිට ඊළඟ කෝල් එක ගැනීම
            } else if (currentCallCount >= maxCalls) {
                isRunning = false
                Toast.makeText(applicationContext, "Auto Redial Completed", Toast.LENGTH_LONG).show()
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
