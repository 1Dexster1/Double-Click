package com.example.data

import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {
    val allRecordings: Flow<List<Recording>> = recordingDao.getAllRecordings()

    suspend fun insert(recording: Recording) {
        recordingDao.insertRecording(recording)
    }

    suspend fun update(recording: Recording) {
        recordingDao.updateRecording(recording)
    }

    suspend fun delete(id: Int) {
        recordingDao.deleteRecording(id)
    }

    suspend fun getById(id: Int): Recording? {
        return recordingDao.getRecordingById(id)
    }
}
