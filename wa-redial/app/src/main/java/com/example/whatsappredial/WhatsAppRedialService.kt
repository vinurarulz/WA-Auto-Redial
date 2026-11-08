package com.example.whatsappredial

import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
        
        var onStateChanged: ((Boolean) -> Unit)? = null

        fun startRedial(context: Context, dataId: Long, totalCalls: Int, intervalSec: Int, durationLimit: Boolean) {
            contactDataId = dataId
            maxCalls = totalCalls
            intervalSeconds = intervalSec.coerceAtLeast(1)
            limitDuration = durationLimit
            currentCallCount = 0
            isRunning = true
            instance?.callState = 0
            onStateChanged?.invoke(true)
            instance?.initiateNextCall(context)
        }

        fun stopRedial(context: Context? = null) {
            isRunning = false
            instance?.handler?.removeCallbacksAndMessages(null)
            onStateChanged?.invoke(false)
            if (context != null && currentCallCount > 0) {
                Toast.makeText(context, "Auto Redial Stopped! ($currentCallCount Calls Made)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    var callState = 0 
    var timeInState1 = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun initiateNextCall(context: Context) {
        if (!isRunning || contactDataId == -1L) return
        
        if (currentCallCount >= maxCalls) {
            stopRedial(context)
            return
        }

        currentCallCount++
        callState = 1
        timeInState1 = 0
        
        Toast.makeText(context, "Dialing Call $currentCallCount of $maxCalls", Toast.LENGTH_SHORT).show()
        
        try {
            val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, contactDataId)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(intent)

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
            stopRedial(context)
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
                stopRedial(applicationContext)
            }
        }
    }

    private val checkCallRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (currentCallCount > maxCalls) {
                stopRedial(applicationContext)
                return
            }

            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val isAudioActive = am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL
            
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOff = !pm.isInteractive

            val rootNode = rootInActiveWindow
            val isUIActive = isWhatsAppCallUIActive(rootNode)

            val isCallRunning = isAudioActive || isScreenOff || isUIActive

            if (callState == 1) {
                if (isCallRunning) {
                    callState = 2 
                    timeInState1 = 0
                } else {
                    timeInState1++
                    if (timeInState1 >= 15) { 
                        callState = 3
                        handler.postDelayed(nextCallRunnable, 0L)
                    }
                }
            } else if (callState == 2) {
                if (!isCallRunning) {
                    callState = 3 
                    handler.postDelayed(nextCallRunnable, 4000L)
                }
            } else if (callState == 3) {
                if (isCallRunning) {
                    callState = 2
                    handler.removeCallbacks(nextCallRunnable)
                }
            }

            handler.postDelayed(this, 1000L) 
        }
    }

    private fun isWhatsAppCallUIActive(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val ends = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/end_call_button")
        if (ends != null && ends.isNotEmpty()) return true
        return searchForActiveCallText(node)
    }

    private fun searchForActiveCallText(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        
        if (text.contains("end call") || desc.contains("end call")) return true
        if (desc.contains("decline")) return true
        if (text.contains("ringing") || desc.contains("ringing")) return true
        if (text.contains("calling") || desc.contains("calling")) return true
        if (text.contains("ongoing call") || desc.contains("ongoing call")) return true
        if (text.contains("return to call") || desc.contains("return to call")) return true
        if (text.contains("නාද වේ") || desc.contains("නාද වේ")) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchForActiveCallText(child)) return true
        }
        return false
    }

    // UI Click කරන කොටස සම්පූර්ණයෙන්ම ඉවත් කර ඇත
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
