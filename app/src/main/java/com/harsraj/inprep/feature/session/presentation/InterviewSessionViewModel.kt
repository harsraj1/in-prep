package com.harsraj.inprep.feature.session.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harsraj.inprep.feature.session.domain.AnswerGenerationRepository
import com.harsraj.inprep.feature.session.domain.AudioPlaybackRepository
import com.harsraj.inprep.feature.session.domain.AudioPlaybackStatus
import com.harsraj.inprep.feature.session.domain.AudioSynthesisRepository
import com.harsraj.inprep.feature.session.domain.SettingsRepository
import com.harsraj.inprep.feature.session.domain.SpeechRecognitionRepository
import com.harsraj.inprep.feature.session.domain.SpeechRecognitionStatus
import com.harsraj.inprep.feature.session.domain.TemporaryFileCleaner
import com.harsraj.inprep.feature.session.domain.VoiceCloningRepository
import com.harsraj.inprep.feature.session.domain.VoiceSampleRecorder
import com.harsraj.inprep.feature.session.domain.VoiceSampleRecorderStatus
import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import com.harsraj.inprep.feature.session.domain.model.PlaybackContent
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
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
        viewModelScope.launch(dispatcher) {
            voiceSampleRecorder.status.collect { recorderStatus ->
                val current = mutableState.value
                if (current is InterviewSessionUiState.Recording) {
                    when (recorderStatus) {
                        is VoiceSampleRecorderStatus.Recording -> {
                            mutableState.value = current.copy(elapsedMillis = recorderStatus.elapsedMillis)
                        }
                        is VoiceSampleRecorderStatus.Captured -> {
                            mutableState.value = InterviewSessionUiState.VoiceSampleReady(
                                current.context,
                                recorderStatus.sample,
                            )
                        }
                        is VoiceSampleRecorderStatus.Failed -> {
                            retryOperation = RetryOperation.StartRecording(current.context)
                            showError(
                                recoveryPoint = RecoveryPoint.Setup(current.context),
                                failedStage = FailedStage.START_RECORDING,
                                error = IllegalStateException(recorderStatus.message),
                            )
                        }
                        VoiceSampleRecorderStatus.Idle -> Unit
                    }
                }
            }
        }
        viewModelScope.launch(dispatcher) {
            speechRecognitionRepository.status.collect { recognitionStatus ->
                val current = mutableState.value
                if (current is InterviewSessionUiState.Listening) {
                    when (recognitionStatus) {
                        is SpeechRecognitionStatus.Listening -> {
                            mutableState.value = current.copy(
                                partialTranscript = recognitionStatus.partialTranscript,
                            )
                        }
                        is SpeechRecognitionStatus.Final -> acceptTranscript(
                            current.context,
                            current.voiceProfile,
                            recognitionStatus.question.text,
                        )
                        is SpeechRecognitionStatus.Failed -> {
                            retryOperation = RetryOperation.StartListening(current.context, current.voiceProfile)
                            showError(
                                RecoveryPoint.Ready(current.context, current.voiceProfile),
                                FailedStage.TRANSCRIBE,
                                IllegalStateException(recognitionStatus.message),
                            )
                        }
                        SpeechRecognitionStatus.Idle -> Unit
                    }
                }
            }
        }
        viewModelScope.launch(dispatcher) {
            audioPlaybackRepository.status.collect { playbackStatus ->
                val current = mutableState.value
                when {
                    playbackStatus == AudioPlaybackStatus.Completed &&
                        current is InterviewSessionUiState.Playing -> playbackCompleted(current.content)
                    playbackStatus == AudioPlaybackStatus.Paused &&
                        current is InterviewSessionUiState.Playing -> {
                        mutableState.value = InterviewSessionUiState.Paused(current.content)
                    }
                    playbackStatus is AudioPlaybackStatus.Failed &&
                        (current is InterviewSessionUiState.Playing || current is InterviewSessionUiState.Paused) -> {
                        val content = when (current) {
                            is InterviewSessionUiState.Playing -> current.content
                            is InterviewSessionUiState.Paused -> current.content
                            else -> error("Validated playback state changed unexpectedly")
                        }
                        audioPlaybackRepository.stop()
                        retryOperation = RetryOperation.Play(content)
                        showError(
                            RecoveryPoint.ReadyToPlay(content),
                            FailedStage.PLAYBACK,
                            IllegalStateException(playbackStatus.message),
                        )
                    }
                }
            }
        }
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

    fun onHostStopped() {
        if (mutableState.value is InterviewSessionUiState.Recording ||
            mutableState.value is InterviewSessionUiState.Listening ||
            mutableState.value is InterviewSessionUiState.Playing ||
            mutableState.value is InterviewSessionUiState.Paused
        ) {
            if (mutableState.value is InterviewSessionUiState.Playing ||
                mutableState.value is InterviewSessionUiState.Paused
            ) dispatch(InterviewSessionAction.Stop) else dispatch(InterviewSessionAction.Cancel)
        }
    }

    override fun onCleared() {
        voiceSampleRecorder.cancel()
        speechRecognitionRepository.destroy()
        audioPlaybackRepository.release()
        super.onCleared()
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
            is InterviewSessionAction.StartTextOnly -> startTextOnly(action.context)
            InterviewSessionAction.FinishRecording -> finishRecording(current as InterviewSessionUiState.Recording)
            InterviewSessionAction.CloneVoice -> cloneVoice(
                (current as InterviewSessionUiState.VoiceSampleReady).context,
                current.sample,
            )
            InterviewSessionAction.DiscardVoiceSample -> discardVoiceSample(
                current as InterviewSessionUiState.VoiceSampleReady,
            )
            is InterviewSessionAction.ReuseVoiceProfile -> reuseVoiceProfile(action.preferences)
            InterviewSessionAction.StartListening -> when (current) {
                is InterviewSessionUiState.Ready -> startListening(current.context, current.voiceProfile)
                is InterviewSessionUiState.QuestionReady -> startListening(current.context, current.voiceProfile)
                is InterviewSessionUiState.AnswerReady -> startListening(current.context, current.voiceProfile)
                else -> error("Validated listening state changed unexpectedly")
            }
            InterviewSessionAction.FinishListening -> finishListening(current as InterviewSessionUiState.Listening)
            is InterviewSessionAction.GenerateFromTranscript -> generateFromTranscript(
                current as InterviewSessionUiState.QuestionReady,
                action.transcript,
            )
            InterviewSessionAction.Play -> play((current as InterviewSessionUiState.ReadyToPlay).content)
            InterviewSessionAction.Pause -> pause((current as InterviewSessionUiState.Playing).content)
            InterviewSessionAction.Resume -> resume((current as InterviewSessionUiState.Paused).content)
            InterviewSessionAction.PlaybackCompleted -> playbackCompleted(
                (current as InterviewSessionUiState.Playing).content,
            )
            InterviewSessionAction.Cancel -> cancel(current)
            InterviewSessionAction.Retry -> retry()
            InterviewSessionAction.ContinueWithoutVoice -> continueWithoutVoice()
            InterviewSessionAction.Stop -> stop(current)
            InterviewSessionAction.Close -> close(current)
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            retryOperation = RetryOperation.StartRecording(context)
            showError(
                recoveryPoint = RecoveryPoint.Setup(context),
                failedStage = FailedStage.START_RECORDING,
                error = error,
            )
        }
    }

    private fun startTextOnly(context: InterviewContext) {
        retryOperation = null
        mutableState.value = InterviewSessionUiState.Ready(context, null)
        launchOperation { settingsRepository.saveInterviewContext(context) }
    }

    private fun finishRecording(recording: InterviewSessionUiState.Recording) {
        try {
            val sample = voiceSampleRecorder.finish()
            retryOperation = null
            mutableState.value = InterviewSessionUiState.VoiceSampleReady(recording.context, sample)
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(
                    recoveryPoint = RecoveryPoint.Setup(context),
                    failedStage = FailedStage.CLONE_VOICE,
                    error = error,
                )
            }
        }
    }

    private fun startListening(context: InterviewContext, profile: VoiceProfileReference?) {
        mutableState.value = InterviewSessionUiState.Listening(context, profile)
        try {
            speechRecognitionRepository.startListening()
            retryOperation = RetryOperation.StartListening(context, profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showError(
                recoveryPoint = RecoveryPoint.Ready(context, profile),
                failedStage = FailedStage.START_LISTENING,
                error = error,
            )
        }
    }

    private fun finishListening(listening: InterviewSessionUiState.Listening) {
        transcribe(listening.context, listening.voiceProfile)
    }

    private fun transcribe(context: InterviewContext, profile: VoiceProfileReference?) {
        mutableState.value = InterviewSessionUiState.Transcribing(context, profile)
        retryOperation = RetryOperation.Transcribe(context, profile)
        launchOperation {
            try {
                val question = speechRecognitionRepository.stopAndTranscribe()
                yield()
                acceptTranscript(context, profile, question.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(
                    recoveryPoint = RecoveryPoint.Ready(context, profile),
                    failedStage = FailedStage.TRANSCRIBE,
                    error = error,
                )
            }
        }
    }

    private fun acceptTranscript(
        context: InterviewContext,
        profile: VoiceProfileReference?,
        transcript: String,
    ) {
        if (mutableState.value !is InterviewSessionUiState.Listening &&
            mutableState.value !is InterviewSessionUiState.Transcribing
        ) return
        retryOperation = null
        mutableState.value = InterviewSessionUiState.QuestionReady(context, profile, transcript)
    }

    private fun generateFromTranscript(
        questionReady: InterviewSessionUiState.QuestionReady,
        transcript: String,
    ) {
        val normalized = transcript.trim()
        if (normalized.length < 3 || normalized.none(Char::isLetterOrDigit)) {
            retryOperation = null
            showError(
                RecoveryPoint.Ready(questionReady.context, questionReady.voiceProfile),
                FailedStage.TRANSCRIBE,
                IllegalArgumentException("Enter a clear interview question before generating an answer"),
            )
            return
        }
        launchOperation {
            generateAnswer(
                questionReady.context,
                questionReady.voiceProfile,
                InterviewQuestion(normalized),
            )
        }
    }

    private suspend fun generateAnswer(
        context: InterviewContext,
        profile: VoiceProfileReference?,
        question: InterviewQuestion,
    ) {
        mutableState.value = InterviewSessionUiState.GeneratingAnswer(context, profile, question)
        retryOperation = RetryOperation.GenerateAnswer(context, profile, question)
        try {
            val answer = answerGenerationRepository.generateAnswer(context, question)
            yield()
            if (profile == null) {
                retryOperation = null
                mutableState.value = InterviewSessionUiState.AnswerReady(
                    context = context,
                    voiceProfile = null,
                    question = question,
                    answer = answer,
                )
            } else {
                synthesizeSpeech(context, profile, question, answer)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showError(
                recoveryPoint = RecoveryPoint.AnswerReady(context, profile, question, answer),
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
            } catch (cancelled: CancellationException) {
                throw cancelled
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
            } catch (cancelled: CancellationException) {
                throw cancelled
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
            } catch (cancelled: CancellationException) {
                throw cancelled
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
            audioPlaybackRepository.stop()
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
            is InterviewSessionUiState.QuestionReady -> {
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
                pending.context,
                pending.profile,
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

    private fun continueWithoutVoice() {
        val clone = retryOperation as? RetryOperation.CloneVoice ?: return
        operationJob?.cancel()
        operationJob = null
        retryOperation = null
        mutableState.value = InterviewSessionUiState.Ready(clone.context, null)
        launchOperation { temporaryFileCleaner.delete(clone.sample.temporaryFile) }
    }

    private fun stop(current: InterviewSessionUiState) {
        operationJob?.cancel()
        operationJob = null
        retryOperation = null
        val stableState = when (current) {
            is InterviewSessionUiState.ReadyToPlay -> current
            is InterviewSessionUiState.Playing -> InterviewSessionUiState.ReadyToPlay(current.content)
            is InterviewSessionUiState.Paused -> InterviewSessionUiState.ReadyToPlay(current.content)
            else -> current.readyStateOrNull()
        }
        when (current) {
            is InterviewSessionUiState.Recording -> voiceSampleRecorder.cancel()
            is InterviewSessionUiState.Listening,
            is InterviewSessionUiState.Transcribing,
            -> speechRecognitionRepository.cancel()
            else -> Unit
        }
        mutableState.value = stableState ?: when (current) {
            is InterviewSessionUiState.Recording -> InterviewSessionUiState.Setup(current.context)
            is InterviewSessionUiState.VoiceSampleReady -> InterviewSessionUiState.Setup(current.context)
            is InterviewSessionUiState.Cloning -> InterviewSessionUiState.Setup(current.context)
            is InterviewSessionUiState.RecoverableError -> current.recoveryPoint.toState()
            else -> InterviewSessionUiState.Setup()
        }
        launchOperation {
            if (current is InterviewSessionUiState.Playing) {
                audioPlaybackRepository.stop()
            } else if (current is InterviewSessionUiState.Paused) {
                audioPlaybackRepository.stop()
            } else if (current is InterviewSessionUiState.Cloning) {
                temporaryFileCleaner.delete(current.sample.temporaryFile)
            } else if (current is InterviewSessionUiState.VoiceSampleReady) {
                temporaryFileCleaner.delete(current.sample.temporaryFile)
            }
        }
    }

    private fun close(current: InterviewSessionUiState) {
        operationJob?.cancel()
        operationJob = null
        retryOperation = null
        voiceSampleRecorder.cancel()
        speechRecognitionRepository.cancel()
        mutableState.value = InterviewSessionUiState.Setup(current.contextOrNull())
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
            message = userSafeErrorMessage(failedStage, error),
        )
    }

    private fun userSafeErrorMessage(failedStage: FailedStage, error: Exception): String {
        if (error is IllegalArgumentException && failedStage == FailedStage.TRANSCRIBE) {
            return "Enter a clear interview question before generating an answer."
        }
        return when (failedStage) {
            FailedStage.START_RECORDING ->
                "The microphone could not start. Check permission and try again."
            FailedStage.CLONE_VOICE ->
                "The voice profile could not be created. Check the trusted-LAN Voicebox connection and retry."
            FailedStage.START_LISTENING ->
                "Speech recognition could not start. Check microphone access and recognizer availability."
            FailedStage.TRANSCRIBE ->
                "No usable question was captured. Check the connection or try speaking again."
            FailedStage.GENERATE_ANSWER ->
                "The answer service is unavailable or blocked this request. Check connectivity and retry."
            FailedStage.SYNTHESIZE_SPEECH ->
                "Voicebox could not prepare the audio. Check the trusted-LAN server and retry."
            FailedStage.PLAYBACK ->
                "The prepared audio could not be played. Retry or generate the answer again."
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        operationJob = viewModelScope.launch(dispatcher) { block() }
    }

    private fun isValid(
        action: InterviewSessionAction,
        current: InterviewSessionUiState,
    ): Boolean = when (current) {
        is InterviewSessionUiState.Setup -> action is InterviewSessionAction.StartRecording ||
            action is InterviewSessionAction.StartTextOnly ||
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
        is InterviewSessionUiState.QuestionReady -> action is InterviewSessionAction.GenerateFromTranscript ||
            action == InterviewSessionAction.StartListening || action.isTerminationAction()
        is InterviewSessionUiState.AnswerReady -> action == InterviewSessionAction.StartListening ||
            action == InterviewSessionAction.Close || action == InterviewSessionAction.Reset
        is InterviewSessionUiState.ReadyToPlay -> action == InterviewSessionAction.Play ||
            action == InterviewSessionAction.Close || action == InterviewSessionAction.Reset
        is InterviewSessionUiState.Playing -> action == InterviewSessionAction.Pause ||
            action == InterviewSessionAction.PlaybackCompleted ||
            action == InterviewSessionAction.Stop || action == InterviewSessionAction.Close ||
            action == InterviewSessionAction.Reset
        is InterviewSessionUiState.Paused -> action == InterviewSessionAction.Resume ||
            action == InterviewSessionAction.Stop || action == InterviewSessionAction.Close ||
            action == InterviewSessionAction.Reset
        is InterviewSessionUiState.RecoverableError -> action == InterviewSessionAction.Retry ||
            (action == InterviewSessionAction.ContinueWithoutVoice &&
                current.failedStage == FailedStage.CLONE_VOICE) ||
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
        is InterviewSessionUiState.QuestionReady -> InterviewSessionUiState.Ready(context, voiceProfile)
        is InterviewSessionUiState.GeneratingAnswer -> InterviewSessionUiState.Ready(context, voiceProfile)
        is InterviewSessionUiState.AnswerReady -> InterviewSessionUiState.Ready(context, voiceProfile)
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

    private fun InterviewSessionUiState.contextOrNull(): InterviewContext? = when (this) {
        is InterviewSessionUiState.Setup -> savedContext
        is InterviewSessionUiState.Recording -> context
        is InterviewSessionUiState.VoiceSampleReady -> context
        is InterviewSessionUiState.Cloning -> context
        is InterviewSessionUiState.Ready -> context
        is InterviewSessionUiState.Listening -> context
        is InterviewSessionUiState.Transcribing -> context
        is InterviewSessionUiState.QuestionReady -> context
        is InterviewSessionUiState.GeneratingAnswer -> context
        is InterviewSessionUiState.AnswerReady -> context
        is InterviewSessionUiState.SynthesizingSpeech -> context
        is InterviewSessionUiState.ReadyToPlay -> content.context
        is InterviewSessionUiState.Playing -> content.context
        is InterviewSessionUiState.Paused -> content.context
        is InterviewSessionUiState.RecoverableError -> when (val point = recoveryPoint) {
            is RecoveryPoint.Setup -> point.context
            is RecoveryPoint.Ready -> point.context
            is RecoveryPoint.AnswerReady -> point.context
            is RecoveryPoint.ReadyToPlay -> point.content.context
        }
        InterviewSessionUiState.Closed -> null
    }

    private fun RecoveryPoint.toState(): InterviewSessionUiState = when (this) {
        is RecoveryPoint.Setup -> InterviewSessionUiState.Setup(context)
        is RecoveryPoint.Ready -> InterviewSessionUiState.Ready(context, voiceProfile)
        is RecoveryPoint.AnswerReady -> InterviewSessionUiState.AnswerReady(
            context,
            voiceProfile,
            question,
            answer,
        )
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
            val profile: VoiceProfileReference?,
        ) : RetryOperation

        data class Transcribe(
            val context: InterviewContext,
            val profile: VoiceProfileReference?,
        ) : RetryOperation

        data class GenerateAnswer(
            val context: InterviewContext,
            val profile: VoiceProfileReference?,
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
