package com.harsraj.inprep.feature.session.presentation

import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import com.harsraj.inprep.feature.session.domain.model.PlaybackContent
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata

sealed interface InterviewSessionUiState {
    data class Setup(val savedContext: InterviewContext? = null) : InterviewSessionUiState

    data class Recording(
        val context: InterviewContext,
        val elapsedMillis: Long = 0,
    ) : InterviewSessionUiState

    data class VoiceSampleReady(
        val context: InterviewContext,
        val sample: VoiceSampleMetadata,
    ) : InterviewSessionUiState

    data class Cloning(
        val context: InterviewContext,
        val sample: VoiceSampleMetadata,
    ) : InterviewSessionUiState

    data class Ready(
        val context: InterviewContext,
        val voiceProfile: VoiceProfileReference,
    ) : InterviewSessionUiState

    data class Listening(
        val context: InterviewContext,
        val voiceProfile: VoiceProfileReference,
        val partialTranscript: String = "",
    ) : InterviewSessionUiState

    data class Transcribing(
        val context: InterviewContext,
        val voiceProfile: VoiceProfileReference,
    ) : InterviewSessionUiState

    data class QuestionReady(
        val context: InterviewContext,
        val voiceProfile: VoiceProfileReference,
        val transcript: String,
    ) : InterviewSessionUiState

    data class GeneratingAnswer(
        val context: InterviewContext,
        val voiceProfile: VoiceProfileReference,
        val question: InterviewQuestion,
    ) : InterviewSessionUiState

    data class SynthesizingSpeech(
        val context: InterviewContext,
        val voiceProfile: VoiceProfileReference,
        val question: InterviewQuestion,
        val answer: GeneratedAnswer,
    ) : InterviewSessionUiState

    data class ReadyToPlay(val content: PlaybackContent) : InterviewSessionUiState

    data class Playing(val content: PlaybackContent) : InterviewSessionUiState

    data class Paused(val content: PlaybackContent) : InterviewSessionUiState

    data class RecoverableError(
        val recoveryPoint: RecoveryPoint,
        val failedStage: FailedStage,
        val message: String,
    ) : InterviewSessionUiState

    data object Closed : InterviewSessionUiState
}

sealed interface RecoveryPoint {
    data class Setup(val context: InterviewContext?) : RecoveryPoint

    data class Ready(
        val context: InterviewContext,
        val voiceProfile: VoiceProfileReference,
    ) : RecoveryPoint

    data class ReadyToPlay(val content: PlaybackContent) : RecoveryPoint
}

enum class FailedStage {
    START_RECORDING,
    CLONE_VOICE,
    START_LISTENING,
    TRANSCRIBE,
    GENERATE_ANSWER,
    SYNTHESIZE_SPEECH,
    PLAYBACK,
}
