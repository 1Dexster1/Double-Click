package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.Recording
import com.example.data.RecordingRepository
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var recordingStartTime = 0L
    private var timerJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_TOGGLE -> {
                if (RecordingState.isRecording.value) {
                    stopRecording()
                } else {
                    startRecording()
                }
            }
            ACTION_START -> {
                if (!RecordingState.isRecording.value) {
                    startRecording()
                }
            }
            ACTION_STOP -> {
                if (RecordingState.isRecording.value) {
                    stopRecording()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startRecording() {
        // Double check permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start recording: RECORD_AUDIO permission not granted")
            stopSelf()
            return
        }

        try {
            val rootDir = File(filesDir, "recordings")
            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            currentRecordingFile = File(rootDir, "RECORDING_$timeStamp.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentRecordingFile!!.absolutePath)
                prepare()
                start()
            }

            recordingStartTime = System.currentTimeMillis()
            RecordingState.isRecording.value = true
            RecordingState.recordingDurationSec.value = 0

            // Start foreground immediately
            val notification = buildRecordingNotification(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            startTimer()
            triggerVibration("start")
            Log.d(TAG, "Recording started: ${currentRecordingFile!!.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize and start MediaRecorder", e)
            RecordingState.isRecording.value = false
            stopSelf()
        }
    }

    private fun stopRecording() {
        stopTimer()
        var recordingSaved = false
        var durationMs = 0L

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            durationMs = System.currentTimeMillis() - recordingStartTime
            recordingSaved = durationMs > 500  // Must be longer than 0.5s to be a viable recording
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        } finally {
            mediaRecorder = null
        }

        val file = currentRecordingFile
        if (recordingSaved && file != null && file.exists()) {
            val filePath = file.absolutePath
            val fileName = file.name
            val timestamp = System.currentTimeMillis()

            serviceScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(applicationContext)
                val repository = RecordingRepository(db.recordingDao())
                repository.insert(
                    Recording(
                        filePath = filePath,
                        fileName = fileName,
                        timestamp = timestamp,
                        durationMs = durationMs
                    )
                )
                // Set the value top level on main thread
                withContext(Dispatchers.Main) {
                    RecordingState.lastSavedRecordingName.value = fileName
                }
            }
            Log.d(TAG, "Recording saved: $fileName ($durationMs ms)")
        } else {
            // Delete unsuccessful/corrupted file
            if (file != null && file.exists()) {
                file.delete()
            }
            Log.d(TAG, "Recording stopped but not saved (too short or failed)")
        }

        RecordingState.isRecording.value = false
        RecordingState.recordingDurationSec.value = 0
        currentRecordingFile = null
        triggerVibration("stop")

        stopForeground(true)
        stopSelf()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val currentSeconds = RecordingState.recordingDurationSec.value + 1
                RecordingState.recordingDurationSec.value = currentSeconds
                updateNotification(currentSeconds)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun updateNotification(seconds: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildRecordingNotification(seconds)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildRecordingNotification(seconds: Int): android.app.Notification {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Audio...")
            .setContentText("Duration: $formattedTime - Tap to open")
            .setSmallIcon(android.R.drawable.presence_video_busy) // Red recording circle
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop and Save",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Background Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing controller notifications for background capture"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun triggerVibration(patternType: String) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (patternType == "start") {
                        // Two crisp high-tempo haptic clicks for starting
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 120, 100), -1))
                    } else {
                        // One long solid haptic pulse for saving/stopping
                        vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (patternType == "start") {
                        vibrator.vibrate(longArrayOf(0, 100, 120, 100), -1)
                    } else {
                        vibrator.vibrate(350)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute vibration feedback", e)
        }
    }

    override fun onDestroy() {
        stopTimer()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "RecordingService"
        const val NOTIFICATION_ID = 8812
        const val CHANNEL_ID = "recording_service_channel"

        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_TOGGLE = "com.example.action.TOGGLE"
    }
}
