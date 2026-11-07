package com.example.whatsappredial

import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
    var callState = 0 // 0: Idle, 1: Starting, 2: Active, 3: Ending
    var timeInState1 = 0

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
        callState = 1
        timeInState1 = 0
        
        try {
            val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, contactDataId)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(intent)

            // කෝල් එක load වීමට තත්පර 2ක් ලබා දී System Audio පරීක්ෂාව ආරම්භ කිරීම
            handler.removeCallbacks(checkCallRunnable)
            handler.removeCallbacks(nextCallRunnable)
            handler.postDelayed(checkCallRunnable, 2000L)

            if (limitDuration) {
                handler.postDelayed({
                    if (isRunning) endCurrentWhatsAppCall()
                }, 30_000L)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            isRunning = false
        }
    }

    private val nextCallRunnable = Runnable {
        if (callState == 3) {
            callState = 0
            if (isRunning && currentCallCount < maxCalls) {
                handler.postDelayed({
                    initiateNextCall(applicationContext)
                }, intervalSeconds * 1000L)
            } else if (currentCallCount >= maxCalls) {
                isRunning = false
                Toast.makeText(applicationContext, "Auto Redial Completed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val checkCallRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            // 1. Android System එකෙන් සෘජුවම කෝල් එකක් තිබේදැයි බැලීම (තිරයට වඩා 1000% ක් නිවැරදිය)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val isAudioActive = am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL

            // 2. තිරයේ බටන් වලින්ද අතිරේකව බැලීම
            val rootNode = rootInActiveWindow
            val isUIActive = hasEndCallUI(rootNode)

            val isCallRunning = isAudioActive || isUIActive

            if (callState == 1) {
                if (isCallRunning) {
                    callState = 2 // කෝල් එක සාර්ථකව සම්බන්ධ වී ඇත
                    timeInState1 = 0
                } else {
                    timeInState1++
                    if (timeInState1 >= 10) { // තත්පර 10ක් ගිහින් කෝල් එක වැදුනේ නැත්නම් ඊළඟ එකට යන්න
                        callState = 3
                        handler.postDelayed(nextCallRunnable, 0L)
                    }
                }
            } else if (callState == 2) {
                if (!isCallRunning) {
                    callState = 3 // කෝල් එක කට් වී ඇත
                    handler.postDelayed(nextCallRunnable, 2500L) // තහවුරු කරගැනීමට තත්පර 2.5ක Debounce එකක්
                }
            } else if (callState == 3) {
                if (isCallRunning) {
                    // False alarm එකක් නම් ආයෙත් කෝල් එකට මාරු වන්න
                    callState = 2
                    handler.removeCallbacks(nextCallRunnable)
                }
            }

            handler.postDelayed(this, 1000L) // සෑම තත්පරයකට වරක්ම තත්ත්වය පරීක්ෂා කිරීම
        }
    }

    private fun hasEndCallUI(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val ends = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/end_call_button")
        if (ends != null && ends.isNotEmpty()) return true
        return searchForEndCallText(node)
    }

    private fun searchForEndCallText(node: AccessibilityNodeInfo): Boolean {
        if (node.text?.toString()?.contains("End call", true) == true) return true
        if (node.contentDescription?.toString()?.contains("End call", true) == true) return true
        if (node.contentDescription?.toString()?.contains("Decline", true) == true) return true
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchForEndCallText(child)) return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val rootNode = rootInActiveWindow ?: return
        clickCallConfirmationIfPresent(rootNode)
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
