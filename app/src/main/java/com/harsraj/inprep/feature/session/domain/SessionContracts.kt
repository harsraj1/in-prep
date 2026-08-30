package com.harsraj.inprep.feature.session.domain

import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.GeneratedAudioReference
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata
import com.harsraj.inprep.feature.settings.domain.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface VoiceSampleRecorderStatus {
    data object Idle : VoiceSampleRecorderStatus
    data class Recording(val elapsedMillis: Long) : VoiceSampleRecorderStatus
    data class Captured(val sample: VoiceSampleMetadata) : VoiceSampleRecorderStatus
    data class Failed(val message: String) : VoiceSampleRecorderStatus
}

interface VoiceSampleRecorder {
    val status: StateFlow<VoiceSampleRecorderStatus>

    fun start()

    fun finish(): VoiceSampleMetadata

    fun cancel()
}

interface VoiceSampleFormatConverter {
    suspend fun convert(
        sample: VoiceSampleMetadata,
        requiredFormat: String,
    ): VoiceSampleMetadata
}

interface VoiceCloningRepository {
    suspend fun createVoiceProfile(sample: VoiceSampleMetadata): VoiceProfileReference
}

interface AnswerGenerationRepository {
    suspend fun generateAnswer(
        context: InterviewContext,
        question: InterviewQuestion,
    ): GeneratedAnswer
}

interface SpeechRecognitionRepository {
    val status: StateFlow<SpeechRecognitionStatus>

    fun startListening()

    suspend fun stopAndTranscribe(): InterviewQuestion

    fun cancel()

    fun destroy()
}

sealed interface SpeechRecognitionStatus {
    data object Idle : SpeechRecognitionStatus
    data class Listening(val partialTranscript: String = "") : SpeechRecognitionStatus
    data class Final(val question: InterviewQuestion) : SpeechRecognitionStatus
    data class Failed(val message: String) : SpeechRecognitionStatus
}

interface AudioSynthesisRepository {
    suspend fun synthesize(
        answer: GeneratedAnswer,
        voiceProfile: VoiceProfileReference,
    ): GeneratedAudioReference
}

interface AudioPlaybackRepository {
    val status: StateFlow<AudioPlaybackStatus>

    suspend fun play(audio: GeneratedAudioReference)

    suspend fun pause()

    suspend fun resume()

    suspend fun stop()

    fun release()
}

sealed interface AudioPlaybackStatus {
    data object Idle : AudioPlaybackStatus
    data object Playing : AudioPlaybackStatus
    data object Paused : AudioPlaybackStatus
    data object Completed : AudioPlaybackStatus
    data class Failed(val message: String) : AudioPlaybackStatus
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun loadSettings(): AppSettings

    suspend fun saveInterviewContext(context: InterviewContext)

    suspend fun saveSessionPreferences(preferences: SessionPreferences)

    suspend fun saveVoiceboxBaseUrl(baseUrl: String)

    suspend fun reset()
}

interface TemporaryFileCleaner {
    suspend fun delete(file: TemporaryFileReference)

    suspend fun deleteAll()
}
