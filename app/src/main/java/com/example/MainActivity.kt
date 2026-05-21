package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.Recording
import com.example.data.RecordingRepository
import com.example.service.RecordingService
import com.example.service.RecordingState
import com.example.service.VolumeButtonAccessibilityService
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = AppDatabase.getDatabase(applicationContext)
        val repository = RecordingRepository(db.recordingDao())
        val factory = MainViewModelFactory(repository)

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    EchoClickApp(
                        innerPadding = innerPadding,
                        factory = factory
                    )
                }
            }
        }
    }
}

class MainViewModel(private val repository: RecordingRepository) : ViewModel() {

    val recordings: StateFlow<List<Recording>> = repository.allRecordings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Embedded Media Player State
    var activePlayingId = mutableStateOf<Int?>(null)
        private set
    var isPlaying = mutableStateOf(false)
        private set
    var playbackProgress = mutableStateOf(0f)
        private set
    var playPositionDisplay = mutableStateOf("00:00")
        private set
    var playDurationDisplay = mutableStateOf("00:00")
        private set

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    fun playAudio(recording: Recording, context: Context) {
        if (activePlayingId.value == recording.id) {
            // Already active, toggle play/pause
            if (isPlaying.value) {
                pauseAudio()
            } else {
                resumeAudio()
            }
            return
        }

        // Stop current running audio
        stopAudio()

        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found or deleted", Toast.LENGTH_SHORT).show()
            viewModelScope.launch {
                repository.delete(recording.id)
            }
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(recording.filePath)
                prepare()
                start()
            }
            activePlayingId.value = recording.id
            isPlaying.value = true
            playDurationDisplay.value = formatMs(mediaPlayer?.duration?.toLong() ?: 0L)
            startProgressTracker()

            mediaPlayer?.setOnCompletionListener {
                stopAudio()
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "Failed to play audio", e)
            Toast.makeText(context, "Error playing audio file", Toast.LENGTH_SHORT).show()
            stopAudio()
        }
    }

    private fun pauseAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying.value = false
            }
        }
    }

    private fun resumeAudio() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                isPlaying.value = true
            }
        }
    }

    fun seekTo(progress: Float) {
        mediaPlayer?.let { mp ->
            val position = (progress * mp.duration).toInt()
            mp.seekTo(position)
            playbackProgress.value = progress
            playPositionDisplay.value = formatMs(position.toLong())
        }
    }

    fun stopAudio() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "Failed to release MediaPlayer", e)
        } finally {
            mediaPlayer = null
            activePlayingId.value = null
            isPlaying.value = false
            playbackProgress.value = 0f
            playPositionDisplay.value = "00:00"
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isPlaying.value && mediaPlayer != null) {
                mediaPlayer?.let { mp ->
                    val pos = mp.currentPosition
                    val duration = mp.duration
                    if (duration > 0) {
                        playbackProgress.value = pos.toFloat() / duration.toFloat()
                        playPositionDisplay.value = formatMs(pos.toLong())
                    }
                }
                delay(100)
            }
        }
    }

    fun renameRecording(recording: Recording, newFileName: String, context: Context) {
        val sanitized = newFileName.trim()
        if (sanitized.isEmpty()) return

        viewModelScope.launch {
            try {
                val oldFile = File(recording.filePath)
                val parent = oldFile.parentFile
                val newFile = File(parent, "$sanitized.m4a")

                if (oldFile.renameTo(newFile)) {
                    repository.update(
                        recording.copy(
                            fileName = "$sanitized.m4a",
                            filePath = newFile.absolutePath
                        )
                    )
                    Toast.makeText(context, "File renamed successfully", Toast.LENGTH_SHORT).show()
                } else {
                    // Update database metadata representation even if physical renaming falls back
                    repository.update(recording.copy(fileName = "$sanitized.m4a"))
                    Toast.makeText(context, "Renamed locally", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ViewModel", "Renaming failed", e)
            }
        }
    }

    fun deleteRecording(recording: Recording, context: Context) {
        viewModelScope.launch {
            // Stop playback if deleting active recording
            if (activePlayingId.value == recording.id) {
                stopAudio()
            }

            try {
                val file = File(recording.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e("ViewModel", "Error deleting physical file", e)
            }

            repository.delete(recording.id)
            Toast.makeText(context, "Recording deleted successfully", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleFavorite(recording: Recording) {
        viewModelScope.launch {
            repository.update(recording.copy(isFavorite = !recording.isFavorite))
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
    }

    override fun onCleared() {
        stopAudio()
        super.onCleared()
    }
}

class MainViewModelFactory(private val repository: RecordingRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


@SuppressLint("ContextCastToActivity")
@Composable
fun EchoClickApp(
    innerPadding: PaddingValues,
    factory: MainViewModelFactory,
    viewModel: MainViewModel = viewModel(factory = factory)
) {
    val context = LocalContext.current
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val isRecordingRunning by RecordingState.isRecording.collectAsStateWithLifecycle()
    val recordingDurationSec by RecordingState.recordingDurationSec.collectAsStateWithLifecycle()
    val lastSavedRecordingName by RecordingState.lastSavedRecordingName.collectAsStateWithLifecycle()

    // Interactive state checking
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var isAccessibilityServiceOn by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }

    // Refresh permission statuses when app gets resumed
    var triggerPermissionCheck by remember { mutableStateOf(0) }

    LaunchedEffect(triggerPermissionCheck, isRecordingRunning) {
        hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        isAccessibilityServiceOn = isAccessibilityServiceEnabled(context)
    }

    // Capture background save notifications to report on the screen
    LaunchedEffect(lastSavedRecordingName) {
        lastSavedRecordingName?.let {
            Toast.makeText(context, "Recording saved successfully: $it", Toast.LENGTH_LONG).show()
            RecordingState.lastSavedRecordingName.value = null
        }
    }

    // Launchers for Runtime Permissions
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            Toast.makeText(context, "Microphone permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Microphone permission is required to record audio!", Toast.LENGTH_LONG).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    // Dialog sheets
    var showRenameDialogFor by remember { mutableStateOf<Recording?>(null) }
    var renameInputVal by remember { mutableStateOf("") }
    var showFavoriteFilterOnly by remember { mutableStateOf(false) }

    // Bento Grid Palette setup
    val bentoBg = Color(0xFFF7F9FF)
    val bentoTextDark = Color(0xFF1B1B1F)
    val bentoTextPrimary = Color(0xFF041E49)
    val bentoCardBlue = Color(0xFFD3E3FD)
    val bentoCardPurple = Color(0xFFE8DEF8)
    val bentoPurpleText = Color(0xFF21005D)
    val bentoCardGray = Color(0xFFF3F4F9)
    val bentoCardWhite = Color(0xFFFFFFFF)

    val primaryBlue = bentoTextPrimary
    val recordingRed = Color(0xFFBA1A1A)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bentoBg)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
    ) {
        // Bento App Bar & Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(bentoCardBlue, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Dual Microphone",
                        tint = bentoTextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = "EchoClick",
                        color = bentoTextDark,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.testTag("app_logo_title")
                    )
                    Text(
                        text = "Double-click key listener for rapid recording",
                        color = bentoTextDark.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Quick Toggle filter for favorites styled as a bento action
            IconButton(
                onClick = { showFavoriteFilterOnly = !showFavoriteFilterOnly },
                modifier = Modifier
                    .background(bentoCardGray, CircleShape)
                    .size(44.dp)
                    .testTag("filter_favorites_btn")
            ) {
                Icon(
                    imageVector = if (showFavoriteFilterOnly) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Filter favorites",
                    tint = if (showFavoriteFilterOnly) recordingRed else bentoTextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Dynamic State Wave Widget
        AnimatedVisibility(
            visible = isRecordingRunning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            RecordingPulseWaveScreen(
                seconds = recordingDurationSec,
                activeRed = recordingRed,
                onStopClicked = {
                    val stopIntent = Intent(context, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                }
            )
        }

        AnimatedVisibility(
            visible = !isRecordingRunning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ReadyStateCard(primaryBlue = primaryBlue)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Side-by-Side Bento Grid Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card 1: Screen Off / Lock screen
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = bentoCardWhite),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier
                    .weight(1f)
                    .height(115.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(bentoCardPurple, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = bentoPurpleText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Screen Locked",
                            color = bentoTextDark.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Background listening",
                            color = bentoTextDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Card 2: Sensitivity / Response Interval
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = bentoCardGray),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0).copy(alpha = 0.5f)),
                modifier = Modifier
                    .weight(1f)
                    .height(115.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(bentoCardWhite, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = Color(0xFF334155),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Response Time",
                            color = bentoTextDark.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Triggers within 0.6s",
                            color = bentoTextDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // First Launch Instruction Checklist Card
        val checklistComplete = hasMicPermission && isAccessibilityServiceOn
        if (!checklistComplete) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF7F0),
                ),
                border = BorderStroke(1.dp, Color(0xFFFFDDBB)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Required Setup Steps",
                        color = Color(0xFFA55200),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Step 1: Microphone Permission
                    InteractiveCheckRow(
                        label = "Grant Microphone Permission",
                        isValid = hasMicPermission,
                        onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Step 2: Accessibility Setting Helper
                    InteractiveCheckRow(
                        label = "Enable Volume Listener (EchoClick)",
                        subtitle = "Tap to configure accessibility service",
                        isValid = isAccessibilityServiceOn,
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                                triggerPermissionCheck++
                            } catch (e: Exception) {
                                Toast.makeText(context, "Please open Accessibility Settings manually", Toast.LENGTH_LONG).show()
                            }
                        }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // Step 3: Notification permission (Android 13+)
                        InteractiveCheckRow(
                            label = "Allow System Notifications",
                            isValid = hasNotificationPermission,
                            onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        )
                    }
                }
            }

            // Android 13+ Restricted Setting Workaround Card
            if (!isAccessibilityServiceOn) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF1F5F9),
                    ),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFF475569),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Restricted Setting Guide (Android 13+)",
                                color = Color(0xFF1E293B),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "If Android blocks the service with a \"Restricted setting\" alert, please follow these steps to unlock it:",
                            color = Color(0xFF475569),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "1. Go back to your device home screen.\n2. Long-press the EchoClick app icon and tap \"App Info\" (or go to Settings > Apps > EchoClick).\n3. Tap the 3-dots menu icon at the top right corner.\n4. Select \"Allow restricted settings\".\n5. Authenticate with your PIN/fingerprint, then return here and enable the service.",
                            color = Color(0xFF475569),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        } else {
            // Success Card showing Active and Listening styled in beautiful bento theme
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE6F7ED),
                ),
                border = BorderStroke(1.dp, Color(0xFFC4EED0)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Volume listener active in background",
                            color = Color(0xFF137333),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "You can now lock your device and double-press the Volume Up key to start/stop quiet recordings.",
                            color = Color(0xFF137333).copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = { triggerPermissionCheck++ },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF137333)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Refresh", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }

        // Section Title: Saved audio clips
        Text(
            text = if (showFavoriteFilterOnly) "Starred Recordings" else "Recorded Audio Clips",
            color = bentoTextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        )

        // Filter and view internal audio records
        val filteredList = if (showFavoriteFilterOnly) {
            recordings.filter { it.isFavorite }
        } else recordings

        if (filteredList.isEmpty()) {
            EmptyDataState(
                showFavoriteState = showFavoriteFilterOnly,
                primaryBlue = primaryBlue
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredList, key = { it.id }) { item ->
                    RecordingItemCard(
                        recording = item,
                        currentPlayingId = viewModel.activePlayingId.value,
                        isPlaying = viewModel.isPlaying.value,
                        playbackProgress = viewModel.playbackProgress.value,
                        playPositionText = viewModel.playPositionDisplay.value,
                        playDurationText = viewModel.playDurationDisplay.value,
                        primaryAccent = primaryBlue,
                        recordingRed = recordingRed,
                        onPlayToggle = { viewModel.playAudio(item, context) },
                        onProgressSeek = { pos -> viewModel.seekTo(pos) },
                        onRenameClicked = {
                            showRenameDialogFor = item
                            renameInputVal = item.fileName.removeSuffix(".m4a")
                        },
                        onDeleteClicked = { viewModel.deleteRecording(item, context) },
                        onFavoriteToggle = { viewModel.toggleFavorite(item) },
                        onShareClicked = { shareAudioFile(context, item) }
                    )
                }
            }
        }
    }

    // Rename dialog
    showRenameDialogFor?.let { recording ->
        AlertDialog(
            onDismissRequest = { showRenameDialogFor = null },
            title = {
                Text(
                    text = "Rename Recording",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                OutlinedTextField(
                    value = renameInputVal,
                    onValueChange = { renameInputVal = it },
                    singleLine = true,
                    label = { Text("New file name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_text_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameRecording(recording, renameInputVal, context)
                        showRenameDialogFor = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialogFor = null }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun InteractiveCheckRow(
    label: String,
    isValid: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isValid) Color(0xFFE6F7ED) else Color(0xFFFFF2E6))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isValid) Icons.Filled.CheckCircle else Icons.Filled.Info,
            contentDescription = "Status",
            tint = if (isValid) Color(0xFF1E8E3E) else Color(0xFFE8710A),
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (isValid) Color(0xFF137333) else Color(0xFFB06000),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth()
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = if (isValid) Color(0xFF137333).copy(alpha = 0.7f) else Color(0xFFB06000).copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun RecordingPulseWaveScreen(
    seconds: Int,
    activeRed: Color,
    onStopClicked: () -> Unit
) {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    val dynamicTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)

    // Infinite animation pulsing back and forth
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scaling"
    )

    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2F4)),
        border = BorderStroke(1.dp, activeRed.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recording_wave_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Recording Audio in Background",
                color = activeRed,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Pulse wave graphic icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(110.dp)
                    .scale(pulseScale)
            ) {
                // Multi waves effect
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = activeRed.copy(alpha = 0.12f),
                        radius = size.minDimension / 1.1f
                    )
                    drawCircle(
                        color = activeRed.copy(alpha = 0.22f),
                        radius = size.minDimension / 1.4f
                    )
                }

                IconButton(
                    onClick = onStopClicked,
                    modifier = Modifier
                        .size(64.dp)
                        .background(activeRed, CircleShape)
                        .testTag("record_wave_stop_inner_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop and Save",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "$dynamicTime",
                color = Color(0xFF1B1B1F),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Text(
                text = "Tap the stop button or double-press the Volume Up key to finish recording.",
                color = Color(0xFF534343),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun ReadyStateCard(primaryBlue: Color) {
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFD3E3FD)),
        border = BorderStroke(1.dp, Color(0xFFC2E7FF)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ready_state_card")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // Giant water-mark icon on the bottom right (as in Bento Design HTML)
            Icon(
                imageVector = Icons.Default.Hearing,
                contentDescription = null,
                tint = Color(0xFF041E49).copy(alpha = 0.06f),
                modifier = Modifier
                    .size(130.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = (20).dp, y = (20).dp)
            )

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Active badge tag
                Row(
                    modifier = Modifier
                        .background(Color(0xFF041E49), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFF2ECC71), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Active & Ready",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Service Armed",
                    color = Color(0xFF041E49),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Double-click the Volume Up key to start/stop quiet background recording instantly, even when the screen is locked.",
                    color = Color(0xFF041E49).copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun RecordingItemCard(
    recording: Recording,
    currentPlayingId: Int?,
    isPlaying: Boolean,
    playbackProgress: Float,
    playPositionText: String,
    playDurationText: String,
    primaryAccent: Color,
    recordingRed: Color,
    onPlayToggle: () -> Unit,
    onProgressSeek: (Float) -> Unit,
    onRenameClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onShareClicked: () -> Unit
) {
    val isActivePlayer = currentPlayingId == recording.id
    val formatter = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
    val recordingDateString = formatter.format(Date(recording.timestamp))

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActivePlayer) Color(0xFFE8F0FE) else Color(0xFFFFFFFF)
        ),
        border = BorderStroke(
            1.dp,
            if (isActivePlayer) Color(0xFFADCCF7) else Color(0xFFE2E8F0)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recording_item_card_${recording.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top basic info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play-Pause mini trigger
                IconButton(
                    onClick = onPlayToggle,
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            if (isActivePlayer && isPlaying) Color(0xFFC2E7FF) else Color(0xFFF1F5F9),
                            CircleShape
                        )
                        .testTag("play_icon_btn_${recording.id}")
                ) {
                    Icon(
                        imageVector = if (isActivePlayer && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = if (isActivePlayer && isPlaying) primaryAccent else Color(0xFF334155),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Core labels
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.fileName,
                        color = Color(0xFF1B1B1F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "$recordingDateString  •  ${formatDuration(recording.durationMs)}",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Fav Badge
                IconButton(onClick = onFavoriteToggle) {
                    Icon(
                        imageVector = if (recording.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (recording.isFavorite) recordingRed else Color(0xFF94A3B8),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expand and reveal slider player if actively chosen
            AnimatedVisibility(
                visible = isActivePlayer,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Divider(color = Color(0xFFCBD5E1))

                    // Progress bar slider
                    Slider(
                        value = playbackProgress,
                        onValueChange = onProgressSeek,
                        colors = SliderDefaults.colors(
                            thumbColor = primaryAccent,
                            activeTrackColor = primaryAccent,
                            inactiveTrackColor = Color(0xFFE2E8F0)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .testTag("playback_slider_${recording.id}")
                    )

                    // Timers display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = playDurationText,
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                        Text(
                            text = playPositionText,
                            color = primaryAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action toolbar row (Share, Rename, Delete)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                // Share
                TextButton(
                    onClick = onShareClicked,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", color = Color(0xFF475569), fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Rename
                TextButton(
                    onClick = onRenameClicked,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Rename",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Rename", color = Color(0xFF475569), fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Delete
                TextButton(
                    onClick = onDeleteClicked,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = recordingRed.copy(alpha = 0.9f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", color = recordingRed.copy(alpha = 0.9f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun EmptyDataState(showFavoriteState: Boolean, primaryBlue: Color) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp, horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (showFavoriteState) Icons.Outlined.FavoriteBorder else Icons.Outlined.MusicNote,
                    contentDescription = "No audio records",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(56.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (showFavoriteState) "No starred recordings yet" else "No recordings found",
                    color = Color(0xFF334155),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                if (!showFavoriteState) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Double-press the Volume Up key when setup is complete to start background recording.",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}


// Help Utilities
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, VolumeButtonAccessibilityService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}

private fun formatDuration(durationMs: Long): String {
    val totalSecs = durationMs / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
}

private fun shareAudioFile(context: Context, recording: Recording) {
    try {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }

        // Generate secure provider share URI
        val authority = "${context.packageName}.fileprovider"
        val uriStr: Uri = FileProvider.getUriForFile(context, authority, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uriStr)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share audio file via:"))
    } catch (e: Exception) {
        Log.e("Share", "Sharing file failed", e)
        Toast.makeText(context, "Unexpected error sharing audio file", Toast.LENGTH_SHORT).show()
    }
}
