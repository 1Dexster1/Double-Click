package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

class VolumeButtonAccessibilityService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        Log.d(TAG, "onKeyEvent - KeyCode: $keyCode, Action: $action")

        // We only care about Volume Up key presses
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (action == KeyEvent.ACTION_DOWN) {
                val currentTime = System.currentTimeMillis()
                val timeDifference = currentTime - lastVolumeUpPressTime

                Log.d(TAG, "Volume Up Key Action Down! Time diff: $timeDifference ms")

                if (timeDifference < DOUBLE_PRESS_INTERVAL_MS) {
                    // Double-press detected!
                    lastVolumeUpPressTime = 0L // Reset
                    Log.d(TAG, "SUCCESS: Double Volume Up detected. Triggering Recording Toggle!")
                    triggerRecordingToggle()
                    return true // Swallow this second keypress event completely!
                }
                lastVolumeUpPressTime = currentTime
            }
        }

        // Pass-through standard behavior so single presses function normally (increasing system volume)
        return super.onKeyEvent(event)
    }

    private fun triggerRecordingToggle() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_TOGGLE
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice recording through RecordingService", e)
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Accessibility services require implementing this, unused here
    }

    override fun onInterrupt() {
        // Accessibility services require implementing this, unused here
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "VolumeButtonAccessibilityService connected!")
        isServiceRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "VolumeButtonAccessibilityService destroyed!")
        isServiceRunning = false
    }

    companion object {
        private const val TAG = "VolumeButtonService"
        private const val DOUBLE_PRESS_INTERVAL_MS = 600L
        private var lastVolumeUpPressTime = 0L

        // To reactively track in-app if service is currently running
        var isServiceRunning = false
    }
}
