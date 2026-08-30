package com.harsraj.inprep.feature.session.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harsraj.inprep.feature.session.domain.AnswerGenerationRepository
import com.harsraj.inprep.feature.session.domain.AudioPlaybackRepository
import com.harsraj.inprep.feature.session.domain.AudioSynthesisRepository
import com.harsraj.inprep.feature.session.domain.SettingsRepository
import com.harsraj.inprep.feature.session.domain.SpeechRecognitionRepository
import com.harsraj.inprep.feature.session.domain.TemporaryFileCleaner
import com.harsraj.inprep.feature.session.domain.VoiceCloningRepository
import com.harsraj.inprep.feature.session.domain.VoiceSampleRecorder
import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import com.harsraj.inprep.feature.session.domain.model.PlaybackContent
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class InterviewSessionViewModel(
    private val voiceSampleRecorder: VoiceSampleRecorder,
    private val voiceCloningRepository: VoiceCloningRepository,
    private val answerGenerationRepository: AnswerGenerationRepository,
    private val speechRecognitionRepository: SpeechRecognitionRepository,
    private val audioSynthesisRepository: AudioSynthesisRepository,
    private val audioPlaybackRepository: AudioPlaybackRepository,
    private val settingsRepository: SettingsRepository,
    private val temporaryFileCleaner: TemporaryFileCleaner,
    initialPreferences: SessionPreferences? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val mutableState = MutableStateFlow<InterviewSessionUiState>(
        initialPreferences?.let {
            InterviewSessionUiState.Ready(it.context, it.voiceProfile)
        } ?: InterviewSessionUiState.Setup(),
    )
    val state: StateFlow<InterviewSessionUiState> = mutableState.asStateFlow()

    private var operationJob: Job? = null
    private var retryOperation: RetryOperation? = null

    init {
        if (initialPreferences == null) {
            viewModelScope.launch(dispatcher) {
                val saved = settingsRepository.loadSettings()
                if (mutableState.value is InterviewSessionUiState.Setup) {
                    saved.reusableSessionPreferences?.let { preferences ->
                        mutableState.value = InterviewSessionUiState.Ready(
                            preferences.context,
                            preferences.voiceProfile,
                        )
                    } ?: saved.interviewContext?.let { context ->
                        mutableState.value = InterviewSessionUiState.Setup(context)
                    }
                }
            }
        }
    }

    fun dispatch(action: InterviewSessionAction): ActionDispatchResult {
        val current = mutableState.value
        if (!isValid(action, current)) {
            return ActionDispatchResult.Rejected(
                action = action,
                state = current,
                reason = "${action::class.simpleName} is not valid from ${current::class.simpleName}",
            )
        }

        when (action) {
            is InterviewSessionAction.StartRecording -> startRecording(action.context)
            InterviewSessionAction.FinishRecording -> finishRecording(current as InterviewSessionUiState.Recording)
            InterviewSessionAction.CloneVoice -> cloneVoice(
                (current as InterviewSessionUiState.VoiceSampleReady).context,
                current.sample,
            )
            InterviewSessionAction.DiscardVoiceSample -> discardVoiceSample(
                current as InterviewSessionUiState.VoiceSampleReady,
            )
            is InterviewSessionAction.ReuseVoiceProfile -> reuseVoiceProfile(action.preferences)
            InterviewSessionAction.StartListening -> startListening(current as InterviewSessionUiState.Ready)
            InterviewSessionAction.FinishListening -> finishListening(current as InterviewSessionUiState.Listening)
            InterviewSessionAction.Play -> play((current as InterviewSessionUiState.ReadyToPlay).content)
            InterviewSessionAction.Pause -> pause((current as InterviewSessionUiState.Playing).content)
            InterviewSessionAction.Resume -> resume((current as InterviewSessionUiState.Paused).content)
            InterviewSessionAction.PlaybackCompleted -> playbackCompleted(
                (current as InterviewSessionUiState.Playing).content,
            )
            InterviewSessionAction.Cancel -> cancel(current)
            InterviewSessionAction.Retry -> retry()
            InterviewSessionAction.Stop -> stop(current)
            InterviewSessionAction.Close -> close()
            InterviewSessionAction.Reset -> reset()
        }
        return ActionDispatchResult.Accepted
    }

    private fun startRecording(context: InterviewContext) {
        try {
            voiceSampleRecorder.start()
            launchOperation { settingsRepository.saveInterviewContext(context) }
            retryOperation = null
            mutableState.value = InterviewSessionUiState.Recording(context)
        } catch (error: Exception) {
            retryOperation = RetryOperation.StartRecording(context)
            showError(
                recoveryPoint = RecoveryPoint.Setup(context),
                failedStage = FailedStage.START_RECORDING,
                error = error,
            )
        }
    }

    private fun finishRecording(recording: InterviewSessionUiState.Recording) {
        try {
            val sample = voiceSampleRecorder.finish()
            retryOperation = null
            mutableState.value = InterviewSessionUiState.VoiceSampleReady(recording.context, sample)
        } catch (error: Exception) {
            retryOperation = RetryOperation.StartRecording(recording.context)
            showError(
                recoveryPoint = RecoveryPoint.Setup(recording.context),
                failedStage = FailedStage.START_RECORDING,
                error = error,
            )
        }
    }

    private fun discardVoiceSample(sampleReady: InterviewSessionUiState.VoiceSampleReady) {
        retryOperation = null
        mutableState.value = InterviewSessionUiState.Setup(sampleReady.context)
        launchOperation { temporaryFileCleaner.delete(sampleReady.sample.temporaryFile) }
    }

    private fun reuseVoiceProfile(preferences: SessionPreferences) {
        retryOperation = null
        mutableState.value = InterviewSessionUiState.Ready(
            preferences.context,
            preferences.voiceProfile,
        )
    }

    private fun cloneVoice(context: InterviewContext, sample: VoiceSampleMetadata) {
        mutableState.value = InterviewSessionUiState.Cloning(context, sample)
        retryOperation = RetryOperation.CloneVoice(context, sample)
        launchOperation {
            try {
                val profile = voiceCloningRepository.createVoiceProfile(sample)
                settingsRepository.saveSessionPreferences(SessionPreferences(context, profile))
                temporaryFileCleaner.delete(sample.temporaryFile)
                retryOperation = null
                mutableState.value = InterviewSessionUiState.Ready(context, profile)
            } catch (error: Exception) {
                showError(
                    recoveryPoint = RecoveryPoint.Setup(context),
                    failedStage = FailedStage.CLONE_VOICE,
                    error = error,
                )
            }
        }
    }

    private fun startListening(ready: InterviewSessionUiState.Ready) {
        try {
            speechRecognitionRepository.startListening()
            retryOperation = RetryOperation.StartListening(ready.context, ready.voiceProfile)
            mutableState.value = InterviewSessionUiState.Listening(ready.context, ready.voiceProfile)
        } catch (error: Exception) {
            showError(
                recoveryPoint = RecoveryPoint.Ready(ready.context, ready.voiceProfile),
                failedStage = FailedStage.START_LISTENING,
                error = error,
            )
        }
    }

    private fun finishListening(listening: InterviewSessionUiState.Listening) {
        transcribe(listening.context, listening.voiceProfile)
    }

    private fun transcribe(context: InterviewContext, profile: VoiceProfileReference) {
        mutableState.value = InterviewSessionUiState.Transcribing(context, profile)
        retryOperation = RetryOperation.Transcribe(context, profile)
        launchOperation {
            try {
                val question = speechRecognitionRepository.stopAndTranscribe()
                yield()
                generateAnswer(context, profile, question)
            } catch (error: Exception) {
                showError(
                    recoveryPoint = RecoveryPoint.Ready(context, profile),
                    failedStage = FailedStage.TRANSCRIBE,
                    error = error,
                )
            }
        }
    }

    private suspend fun generateAnswer(
        context: InterviewContext,
        profile: VoiceProfileReference,
        question: InterviewQuestion,
    ) {
        mutableState.value = InterviewSessionUiState.GeneratingAnswer(context, profile, question)
        retryOperation = RetryOperation.GenerateAnswer(context, profile, question)
        try {
            val answer = answerGenerationRepository.generateAnswer(context, question)
            yield()
            synthesizeSpeech(context, profile, question, answer)
        } catch (error: Exception) {
            showError(
                recoveryPoint = RecoveryPoint.Ready(context, profile),
                failedStage = FailedStage.GENERATE_ANSWER,
                error = error,
            )
        }
    }

    private suspend fun synthesizeSpeech(
        context: InterviewContext,
        profile: VoiceProfileReference,
        question: InterviewQuestion,
        answer: GeneratedAnswer,
    ) {
        mutableState.value = InterviewSessionUiState.SynthesizingSpeech(
            context,
            profile,
            question,
            answer,
        )
        retryOperation = RetryOperation.SynthesizeSpeech(context, profile, question, answer)
        try {
            val audio = audioSynthesisRepository.synthesize(answer, profile)
            retryOperation = null
            mutableState.value = InterviewSessionUiState.ReadyToPlay(
                PlaybackContent(context, profile, question, answer, audio),
            )
        } catch (error: Exception) {
            showError(
                recoveryPoint = RecoveryPoint.Ready(context, profile),
                failedStage = FailedStage.SYNTHESIZE_SPEECH,
                error = error,
            )
        }
    }

    private fun play(content: PlaybackContent) {
        mutableState.value = InterviewSessionUiState.Playing(content)
        retryOperation = RetryOperation.Play(content)
        launchOperation {
            try {
                audioPlaybackRepository.play(content.audio)
                retryOperation = null
            } catch (error: Exception) {
                showError(
                    recoveryPoint = RecoveryPoint.ReadyToPlay(content),
                    failedStage = FailedStage.PLAYBACK,
                    error = error,
                )
            }
        }
    }

    private fun pause(content: PlaybackContent) {
        mutableState.value = InterviewSessionUiState.Paused(content)
        launchOperation {
            try {
                audioPlaybackRepository.pause()
            } catch (error: Exception) {
                showError(
                    recoveryPoint = RecoveryPoint.ReadyToPlay(content),
                    failedStage = FailedStage.PLAYBACK,
                    error = error,
                )
            }
        }
    }

    private fun resume(content: PlaybackContent) {
        mutableState.value = InterviewSessionUiState.Playing(content)
        retryOperation = RetryOperation.Play(content)
        launchOperation {
            try {
                audioPlaybackRepository.resume()
                retryOperation = null
            } catch (error: Exception) {
                showError(
                    recoveryPoint = RecoveryPoint.ReadyToPlay(content),
                    failedStage = FailedStage.PLAYBACK,
                    error = error,
                )
            }
        }
    }

    private fun playbackCompleted(content: PlaybackContent) {
        mutableState.value = InterviewSessionUiState.Ready(content.context, content.voiceProfile)
        launchOperation {
            temporaryFileCleaner.delete(content.audio.temporaryFile)
        }
    }

    private fun cancel(current: InterviewSessionUiState) {
        operationJob?.cancel()
        operationJob = null
        retryOperation = null
        when (current) {
            is InterviewSessionUiState.Recording -> {
                voiceSampleRecorder.cancel()
                mutableState.value = InterviewSessionUiState.Setup(current.context)
            }
            is InterviewSessionUiState.Listening -> {
                speechRecognitionRepository.cancel()
                mutableState.value = InterviewSessionUiState.Ready(current.context, current.voiceProfile)
            }
            is InterviewSessionUiState.Cloning -> {
                mutableState.value = InterviewSessionUiState.Setup(current.context)
                launchOperation { temporaryFileCleaner.delete(current.sample.temporaryFile) }
            }
            is InterviewSessionUiState.VoiceSampleReady -> discardVoiceSample(current)
            is InterviewSessionUiState.Transcribing -> {
                speechRecognitionRepository.cancel()
                mutableState.value = InterviewSessionUiState.Ready(current.context, current.voiceProfile)
            }
            is InterviewSessionUiState.GeneratingAnswer -> {
                mutableState.value = InterviewSessionUiState.Ready(current.context, current.voiceProfile)
            }
            is InterviewSessionUiState.SynthesizingSpeech -> {
                mutableState.value = InterviewSessionUiState.Ready(current.context, current.voiceProfile)
            }
            is InterviewSessionUiState.RecoverableError -> restore(current.recoveryPoint)
            else -> Unit
        }
    }

    private fun retry() {
        when (val pending = retryOperation) {
            is RetryOperation.StartRecording -> startRecording(pending.context)
            is RetryOperation.CloneVoice -> cloneVoice(pending.context, pending.sample)
            is RetryOperation.StartListening -> startListening(
                InterviewSessionUiState.Ready(pending.context, pending.profile),
            )
            is RetryOperation.Transcribe -> transcribe(pending.context, pending.profile)
            is RetryOperation.GenerateAnswer -> launchOperation {
                generateAnswer(pending.context, pending.profile, pending.question)
            }
            is RetryOperation.SynthesizeSpeech -> launchOperation {
                synthesizeSpeech(
                    pending.context,
                    pending.profile,
                    pending.question,
                    pending.answer,
                )
            }
            is RetryOperation.Play -> play(pending.content)
            null -> Unit
        }
    }

    private fun stop(current: InterviewSessionUiState) {
        operationJob?.cancel()
        operationJob = null
        retryOperation = null
        val ready = current.readyStateOrNull()
        when (current) {
            is InterviewSessionUiState.Recording -> voiceSampleRecorder.cancel()
            is InterviewSessionUiState.Listening,
            is InterviewSessionUiState.Transcribing,
            -> speechRecognitionRepository.cancel()
            else -> Unit
        }
        mutableState.value = ready ?: when (current) {
            is InterviewSessionUiState.Recording -> InterviewSessionUiState.Setup(current.context)
            is InterviewSessionUiState.VoiceSampleReady -> InterviewSessionUiState.Setup(current.context)
            is InterviewSessionUiState.Cloning -> InterviewSessionUiState.Setup(current.context)
            is InterviewSessionUiState.RecoverableError -> current.recoveryPoint.toState()
            else -> InterviewSessionUiState.Setup()
        }
        launchOperation {
            if (current is InterviewSessionUiState.ReadyToPlay) {
                temporaryFileCleaner.delete(current.content.audio.temporaryFile)
            } else if (current is InterviewSessionUiState.Playing) {
                audioPlaybackRepository.stop()
                temporaryFileCleaner.delete(current.content.audio.temporaryFile)
            } else if (current is InterviewSessionUiState.Paused) {
                audioPlaybackRepository.stop()
                temporaryFileCleaner.delete(current.content.audio.temporaryFile)
            } else if (current is InterviewSessionUiState.Cloning) {
                temporaryFileCleaner.delete(current.sample.temporaryFile)
            } else if (current is InterviewSessionUiState.VoiceSampleReady) {
                temporaryFileCleaner.delete(current.sample.temporaryFile)
            }
        }
    }

    private fun close() {
        operationJob?.cancel()
        operationJob = null
        retryOperation = null
        voiceSampleRecorder.cancel()
        speechRecognitionRepository.cancel()
        mutableState.value = InterviewSessionUiState.Closed
        launchOperation {
            runCatching { audioPlaybackRepository.stop() }
            temporaryFileCleaner.deleteAll()
        }
    }

    private fun reset() {
        operationJob?.cancel()
        operationJob = null
        retryOperation = null
        voiceSampleRecorder.cancel()
        speechRecognitionRepository.cancel()
        mutableState.value = InterviewSessionUiState.Setup()
        launchOperation {
            runCatching { audioPlaybackRepository.stop() }
            temporaryFileCleaner.deleteAll()
            settingsRepository.reset()
        }
    }

    private fun restore(recoveryPoint: RecoveryPoint) {
        retryOperation = null
        mutableState.value = recoveryPoint.toState()
    }

    private fun showError(
        recoveryPoint: RecoveryPoint,
        failedStage: FailedStage,
        error: Exception,
    ) {
        mutableState.value = InterviewSessionUiState.RecoverableError(
            recoveryPoint = recoveryPoint,
            failedStage = failedStage,
            message = error.message ?: "The operation could not be completed",
        )
    }

    private fun launchOperation(block: suspend () -> Unit) {
        operationJob = viewModelScope.launch(dispatcher) { block() }
    }

    private fun isValid(
        action: InterviewSessionAction,
        current: InterviewSessionUiState,
    ): Boolean = when (current) {
        is InterviewSessionUiState.Setup -> action is InterviewSessionAction.StartRecording ||
            action is InterviewSessionAction.ReuseVoiceProfile ||
            action == InterviewSessionAction.Close || action == InterviewSessionAction.Reset
        is InterviewSessionUiState.Recording -> action == InterviewSessionAction.FinishRecording ||
            action.isTerminationAction()
        is InterviewSessionUiState.VoiceSampleReady -> action == InterviewSessionAction.CloneVoice ||
            action == InterviewSessionAction.DiscardVoiceSample || action.isTerminationAction()
        is InterviewSessionUiState.Cloning -> action.isTerminationAction()
        is InterviewSessionUiState.Ready -> action == InterviewSessionAction.StartListening ||
            action == InterviewSessionAction.Close || action == InterviewSessionAction.Reset
        is InterviewSessionUiState.Listening -> action == InterviewSessionAction.FinishListening ||
            action.isTerminationAction()
        is InterviewSessionUiState.Transcribing,
        is InterviewSessionUiState.GeneratingAnswer,
        is InterviewSessionUiState.SynthesizingSpeech,
        -> action.isTerminationAction()
        is InterviewSessionUiState.ReadyToPlay -> action == InterviewSessionAction.Play ||
            action == InterviewSessionAction.Stop || action == InterviewSessionAction.Close ||
            action == InterviewSessionAction.Reset
        is InterviewSessionUiState.Playing -> action == InterviewSessionAction.Pause ||
            action == InterviewSessionAction.PlaybackCompleted ||
            action == InterviewSessionAction.Stop || action == InterviewSessionAction.Close ||
            action == InterviewSessionAction.Reset
        is InterviewSessionUiState.Paused -> action == InterviewSessionAction.Resume ||
            action == InterviewSessionAction.Stop || action == InterviewSessionAction.Close ||
            action == InterviewSessionAction.Reset
        is InterviewSessionUiState.RecoverableError -> action == InterviewSessionAction.Retry ||
            action == InterviewSessionAction.Cancel || action == InterviewSessionAction.Stop ||
            action == InterviewSessionAction.Close || action == InterviewSessionAction.Reset
        InterviewSessionUiState.Closed -> action == InterviewSessionAction.Reset
    }

    private fun InterviewSessionAction.isTerminationAction(): Boolean =
        this == InterviewSessionAction.Cancel || this == InterviewSessionAction.Stop ||
            this == InterviewSessionAction.Close || this == InterviewSessionAction.Reset

    private fun InterviewSessionUiState.readyStateOrNull(): InterviewSessionUiState.Ready? = when (this) {
        is InterviewSessionUiState.Ready -> this
        is InterviewSessionUiState.Listening -> InterviewSessionUiState.Ready(context, voiceProfile)
        is InterviewSessionUiState.Transcribing -> InterviewSessionUiState.Ready(context, voiceProfile)
        is InterviewSessionUiState.GeneratingAnswer -> InterviewSessionUiState.Ready(context, voiceProfile)
        is InterviewSessionUiState.SynthesizingSpeech -> InterviewSessionUiState.Ready(context, voiceProfile)
        is InterviewSessionUiState.ReadyToPlay -> InterviewSessionUiState.Ready(
            content.context,
            content.voiceProfile,
        )
        is InterviewSessionUiState.Playing -> InterviewSessionUiState.Ready(
            content.context,
            content.voiceProfile,
        )
        is InterviewSessionUiState.Paused -> InterviewSessionUiState.Ready(
            content.context,
            content.voiceProfile,
        )
        else -> null
    }

    private fun RecoveryPoint.toState(): InterviewSessionUiState = when (this) {
        is RecoveryPoint.Setup -> InterviewSessionUiState.Setup(context)
        is RecoveryPoint.Ready -> InterviewSessionUiState.Ready(context, voiceProfile)
        is RecoveryPoint.ReadyToPlay -> InterviewSessionUiState.ReadyToPlay(content)
    }

    private sealed interface RetryOperation {
        data class StartRecording(val context: InterviewContext) : RetryOperation

        data class CloneVoice(
            val context: InterviewContext,
            val sample: VoiceSampleMetadata,
        ) : RetryOperation

        data class StartListening(
            val context: InterviewContext,
            val profile: VoiceProfileReference,
        ) : RetryOperation

        data class Transcribe(
            val context: InterviewContext,
            val profile: VoiceProfileReference,
        ) : RetryOperation

        data class GenerateAnswer(
            val context: InterviewContext,
            val profile: VoiceProfileReference,
            val question: InterviewQuestion,
        ) : RetryOperation

        data class SynthesizeSpeech(
            val context: InterviewContext,
            val profile: VoiceProfileReference,
            val question: InterviewQuestion,
            val answer: GeneratedAnswer,
        ) : RetryOperation

        data class Play(val content: PlaybackContent) : RetryOperation
    }
}
