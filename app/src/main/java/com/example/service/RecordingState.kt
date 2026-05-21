package com.example.service

import kotlinx.coroutines.flow.MutableStateFlow

object RecordingState {
    val isRecording = MutableStateFlow(false)
    val recordingDurationSec = MutableStateFlow(0)
    
    // Used to signal the UI when a background recording has ended and saved
    val lastSavedRecordingName = MutableStateFlow<String?>(null)
}
