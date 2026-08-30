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

interface VoiceSampleRecorder {
    fun start()

    fun finish(): VoiceSampleMetadata

    fun cancel()
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
    fun startListening()

    suspend fun stopAndTranscribe(): InterviewQuestion

    fun cancel()
}

interface AudioSynthesisRepository {
    suspend fun synthesize(
        answer: GeneratedAnswer,
        voiceProfile: VoiceProfileReference,
    ): GeneratedAudioReference
}

interface AudioPlaybackRepository {
    suspend fun play(audio: GeneratedAudioReference)

    suspend fun pause()

    suspend fun resume()

    suspend fun stop()
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
