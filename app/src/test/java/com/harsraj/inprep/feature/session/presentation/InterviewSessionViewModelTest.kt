package com.harsraj.inprep.feature.session.presentation

import com.harsraj.inprep.di.FakeApplicationContainer
import com.harsraj.inprep.feature.session.data.fake.FakeSettingsRepository
import com.harsraj.inprep.feature.session.data.fake.FakePlaybackState
import com.harsraj.inprep.feature.session.domain.AnswerGenerationRepository
import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileId
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewSessionViewModelTest {
    private val context = InterviewContext(company = "Example Company", role = "Android Engineer")

    @Test
    fun `normal journey emits every processing state and supports playback controls`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        val observedStates = mutableListOf<InterviewSessionUiState>()
        val collectionJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.takeWhile { it !is InterviewSessionUiState.Closed }.collect {
                observedStates += it
            }
        }

        assertAccepted(viewModel.dispatch(InterviewSessionAction.StartRecording(context)))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Recording)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.FinishRecording))
        assertTrue(viewModel.state.value is InterviewSessionUiState.VoiceSampleReady)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.CloneVoice))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Cloning)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.StartListening))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Listening)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.FinishListening))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Transcribing)
        advanceUntilIdle()
        val questionReady = viewModel.state.value as InterviewSessionUiState.QuestionReady
        assertAccepted(
            viewModel.dispatch(InterviewSessionAction.GenerateFromTranscript(questionReady.transcript)),
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.ReadyToPlay)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Play))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Playing)
        advanceUntilIdle()
        assertEquals(FakePlaybackState.PLAYING, container.fakeAudioPlaybackRepository.playbackState)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Pause))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Paused)
        advanceUntilIdle()
        assertEquals(FakePlaybackState.PAUSED, container.fakeAudioPlaybackRepository.playbackState)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Resume))
        advanceUntilIdle()
        assertEquals(FakePlaybackState.PLAYING, container.fakeAudioPlaybackRepository.playbackState)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.PlaybackCompleted))
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
        assertEquals(
            listOf(container.fakeAudioSynthesisRepository.audio.temporaryFile),
            container.fakeTemporaryFileCleaner.deletedFiles.takeLast(1),
        )

        val stateTypes = observedStates.map { it::class }
        assertTrue(InterviewSessionUiState.Setup::class in stateTypes)
        assertTrue(InterviewSessionUiState.Recording::class in stateTypes)
        assertTrue(InterviewSessionUiState.VoiceSampleReady::class in stateTypes)
        assertTrue(InterviewSessionUiState.Cloning::class in stateTypes)
        assertTrue(InterviewSessionUiState.Ready::class in stateTypes)
        assertTrue(InterviewSessionUiState.Listening::class in stateTypes)
        assertTrue(InterviewSessionUiState.Transcribing::class in stateTypes)
        assertTrue(InterviewSessionUiState.QuestionReady::class in stateTypes)
        assertTrue(InterviewSessionUiState.GeneratingAnswer::class in stateTypes)
        assertTrue(InterviewSessionUiState.SynthesizingSpeech::class in stateTypes)
        assertTrue(InterviewSessionUiState.ReadyToPlay::class in stateTypes)
        assertTrue(InterviewSessionUiState.Playing::class in stateTypes)
        assertTrue(InterviewSessionUiState.Paused::class in stateTypes)
        collectionJob.cancel()
    }

    @Test
    fun `duplicate action is rejected without repeating side effects`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        assertAccepted(viewModel.dispatch(InterviewSessionAction.StartRecording(context)))
        val duplicate = viewModel.dispatch(InterviewSessionAction.StartRecording(context))

        assertTrue(duplicate is ActionDispatchResult.Rejected)
        assertEquals(1, container.fakeVoiceSampleRecorder.startCount)
        assertTrue(viewModel.state.value is InterviewSessionUiState.Recording)
    }

    @Test
    fun `partial and final recognition require transcript review before generation`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadySession(viewModel)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.StartListening))
        container.fakeSpeechRecognitionRepository.emitPartial("How do you")
        advanceUntilIdle()
        assertEquals(
            "How do you",
            (viewModel.state.value as InterviewSessionUiState.Listening).partialTranscript,
        )

        container.fakeSpeechRecognitionRepository.emitFinal("How do you diagnose ANRs?")
        advanceUntilIdle()
        val review = viewModel.state.value as InterviewSessionUiState.QuestionReady
        assertEquals("How do you diagnose ANRs?", review.transcript)
        assertTrue(container.fakeAnswerGenerationRepository.requests.isEmpty())

        assertAccepted(
            viewModel.dispatch(InterviewSessionAction.GenerateFromTranscript("How do you prevent ANRs?")),
        )
        advanceUntilIdle()
        assertEquals(
            "How do you prevent ANRs?",
            container.fakeAnswerGenerationRepository.requests.single().second.text,
        )
    }

    @Test
    fun `recognition failure and host stop return to usable ready state`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadySession(viewModel)

        viewModel.dispatch(InterviewSessionAction.StartListening)
        container.fakeSpeechRecognitionRepository.fail("No speech was heard")
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.RecoverableError)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.Cancel))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)

        viewModel.dispatch(InterviewSessionAction.StartListening)
        viewModel.onHostStopped()
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
        assertFalse(container.fakeSpeechRecognitionRepository.isListening)
    }

    @Test
    fun `recorder elapsed time and errors update explicit UI state`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        assertAccepted(viewModel.dispatch(InterviewSessionAction.StartRecording(context)))
        container.fakeVoiceSampleRecorder.emitElapsed(4_200)
        advanceUntilIdle()
        assertEquals(4_200, (viewModel.state.value as InterviewSessionUiState.Recording).elapsedMillis)

        container.fakeVoiceSampleRecorder.fail("Microphone unavailable")
        advanceUntilIdle()

        val error = viewModel.state.value as InterviewSessionUiState.RecoverableError
        assertEquals(FailedStage.START_RECORDING, error.failedStage)
        assertEquals(
            "The microphone could not start. Check permission and try again.",
            error.message,
        )
    }

    @Test
    fun `host stop cancels active recording and returns to setup`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        viewModel.dispatch(InterviewSessionAction.StartRecording(context))

        viewModel.onHostStopped()

        assertEquals(InterviewSessionUiState.Setup(context), viewModel.state.value)
        assertFalse(container.fakeVoiceSampleRecorder.isRecording)
    }

    @Test
    fun `captured sample waits for clone and can be discarded`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(InterviewSessionAction.StartRecording(context))
        assertAccepted(viewModel.dispatch(InterviewSessionAction.FinishRecording))

        assertTrue(viewModel.state.value is InterviewSessionUiState.VoiceSampleReady)
        assertTrue(container.fakeVoiceCloningRepository.samples.isEmpty())
        assertAccepted(viewModel.dispatch(InterviewSessionAction.DiscardVoiceSample))
        advanceUntilIdle()

        assertEquals(InterviewSessionUiState.Setup(context), viewModel.state.value)
        assertTrue(
            container.fakeVoiceSampleRecorder.sample.temporaryFile in
                container.fakeTemporaryFileCleaner.deletedFiles,
        )
    }

    @Test
    fun `saved voice profile can be reused from setup`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        val preferences = SessionPreferences(
            context,
            container.fakeVoiceCloningRepository.profile,
        )

        assertAccepted(
            viewModel.dispatch(InterviewSessionAction.ReuseVoiceProfile(preferences)),
        )

        assertEquals(
            InterviewSessionUiState.Ready(preferences.context, preferences.voiceProfile),
            viewModel.state.value,
        )
    }

    @Test
    fun `reused profile completes a question without cloning again`() = runTest {
        val preferences = SessionPreferences(
            context,
            VoiceProfileReference(
                VoiceProfileId("saved-profile"),
                10,
            ),
        )
        val container = FakeApplicationContainer(
            settingsRepository = FakeSettingsRepository(preferences),
        )
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(InterviewSessionAction.StartListening)
        viewModel.dispatch(InterviewSessionAction.FinishListening)
        advanceUntilIdle()
        val transcript = (viewModel.state.value as InterviewSessionUiState.QuestionReady).transcript
        viewModel.dispatch(InterviewSessionAction.GenerateFromTranscript(transcript))
        advanceUntilIdle()

        assertTrue(viewModel.state.value is InterviewSessionUiState.ReadyToPlay)
        assertTrue(container.fakeVoiceCloningRepository.samples.isEmpty())
        assertEquals(
            preferences.voiceProfile,
            container.fakeAudioSynthesisRepository.requests.single().second,
        )
    }

    @Test
    fun `cancel releases recording and listening and returns to stable states`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(InterviewSessionAction.StartRecording(context))
        assertAccepted(viewModel.dispatch(InterviewSessionAction.Cancel))
        assertFalse(container.fakeVoiceSampleRecorder.isRecording)
        assertEquals(1, container.fakeVoiceSampleRecorder.cancelCount)
        assertEquals(InterviewSessionUiState.Setup(context), viewModel.state.value)

        createReadySession(viewModel)
        viewModel.dispatch(InterviewSessionAction.StartListening)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.Cancel))
        assertFalse(container.fakeSpeechRecognitionRepository.isListening)
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
    }

    @Test
    fun `retry repeats failed cloning and reaches ready`() = runTest {
        val container = FakeApplicationContainer().apply {
            fakeVoiceCloningRepository.failuresRemaining = 1
        }
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(InterviewSessionAction.StartRecording(context))
        viewModel.dispatch(InterviewSessionAction.FinishRecording)
        viewModel.dispatch(InterviewSessionAction.CloneVoice)
        advanceUntilIdle()

        val error = viewModel.state.value as InterviewSessionUiState.RecoverableError
        assertEquals(FailedStage.CLONE_VOICE, error.failedStage)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.Retry))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Cloning)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
        assertEquals(2, container.fakeVoiceCloningRepository.samples.size)
        assertEquals(1, (container.settingsRepository as FakeSettingsRepository).saveCount)
    }

    @Test
    fun `microphone start denial can be retried without trapping setup`() = runTest {
        val container = FakeApplicationContainer().apply {
            fakeVoiceSampleRecorder.nextStartFailure = SecurityException("permission denied")
        }
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        assertAccepted(viewModel.dispatch(InterviewSessionAction.StartRecording(context)))
        val error = viewModel.state.value as InterviewSessionUiState.RecoverableError
        assertEquals(FailedStage.START_RECORDING, error.failedStage)
        assertFalse(error.message.contains("permission denied", ignoreCase = true))

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Retry))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Recording)
        assertEquals(1, container.fakeVoiceSampleRecorder.startCount)
    }

    @Test
    fun `gemini retry reuses reviewed question and does not listen again`() = runTest {
        val container = FakeApplicationContainer().apply {
            fakeAnswerGenerationRepository.failuresRemaining = 1
        }
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadySession(viewModel)
        viewModel.dispatch(InterviewSessionAction.StartListening)
        viewModel.dispatch(InterviewSessionAction.FinishListening)
        advanceUntilIdle()
        val question = (viewModel.state.value as InterviewSessionUiState.QuestionReady).transcript

        viewModel.dispatch(InterviewSessionAction.GenerateFromTranscript(question))
        advanceUntilIdle()
        assertEquals(
            FailedStage.GENERATE_ANSWER,
            (viewModel.state.value as InterviewSessionUiState.RecoverableError).failedStage,
        )
        viewModel.dispatch(InterviewSessionAction.Retry)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is InterviewSessionUiState.ReadyToPlay)
        assertEquals(2, container.fakeAnswerGenerationRepository.requests.size)
        assertEquals(1, container.fakeSpeechRecognitionRepository.startCount)
        assertEquals(1, container.fakeAudioSynthesisRepository.requests.size)
    }

    @Test
    fun `voicebox synthesis retry reuses generated answer`() = runTest {
        val container = FakeApplicationContainer().apply {
            fakeAudioSynthesisRepository.failuresRemaining = 1
        }
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadyToPlaySession(viewModel, expectReady = false)

        val error = viewModel.state.value as InterviewSessionUiState.RecoverableError
        assertEquals(FailedStage.SYNTHESIZE_SPEECH, error.failedStage)
        assertTrue(error.recoveryPoint is RecoveryPoint.AnswerReady)
        viewModel.dispatch(InterviewSessionAction.Retry)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is InterviewSessionUiState.ReadyToPlay)
        assertEquals(1, container.fakeAnswerGenerationRepository.requests.size)
        assertEquals(2, container.fakeAudioSynthesisRepository.requests.size)
    }

    @Test
    fun `close cancels in flight generation without late error or duplicate request`() = runTest {
        val answer = CompletableDeferred<GeneratedAnswer>()
        var requestCount = 0
        val container = FakeApplicationContainer(
            answerGenerationRepository = object : AnswerGenerationRepository {
                override suspend fun generateAnswer(
                    context: InterviewContext,
                    question: InterviewQuestion,
                ): GeneratedAnswer {
                    requestCount += 1
                    return answer.await()
                }
            },
        )
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadySession(viewModel)
        viewModel.dispatch(InterviewSessionAction.StartListening)
        viewModel.dispatch(InterviewSessionAction.FinishListening)
        advanceUntilIdle()
        val question = (viewModel.state.value as InterviewSessionUiState.QuestionReady).transcript
        viewModel.dispatch(InterviewSessionAction.GenerateFromTranscript(question))
        runCurrent()
        assertTrue(viewModel.state.value is InterviewSessionUiState.GeneratingAnswer)

        viewModel.dispatch(InterviewSessionAction.Close)
        advanceUntilIdle()

        assertEquals(InterviewSessionUiState.Setup(context), viewModel.state.value)
        assertEquals(1, requestCount)
        assertTrue(container.fakeAudioSynthesisRepository.requests.isEmpty())
    }

    @Test
    fun `stop from playback resets player while preserving prepared answer`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadyToPlaySession(viewModel)
        viewModel.dispatch(InterviewSessionAction.Play)
        advanceUntilIdle()

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Stop))
        assertTrue(viewModel.state.value is InterviewSessionUiState.ReadyToPlay)
        advanceUntilIdle()

        assertEquals(FakePlaybackState.STOPPED, container.fakeAudioPlaybackRepository.playbackState)
        assertEquals(1, container.fakeAudioPlaybackRepository.stopCount)
        assertFalse(
            container.fakeAudioSynthesisRepository.audio.temporaryFile in
                container.fakeTemporaryFileCleaner.deletedFiles,
        )
    }

    @Test
    fun `player completion and interruption statuses drive session state`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadyToPlaySession(viewModel)
        val audio = container.fakeAudioSynthesisRepository.audio.temporaryFile

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Play))
        advanceUntilIdle()
        container.fakeAudioPlaybackRepository.complete()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
        assertTrue(audio in container.fakeTemporaryFileCleaner.deletedFiles)

        createReadyToPlaySession(viewModel)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.Play))
        val duplicate = viewModel.dispatch(InterviewSessionAction.Play)
        assertTrue(duplicate is ActionDispatchResult.Rejected)
        advanceUntilIdle()
        container.fakeAudioPlaybackRepository.fail("Generated audio is corrupt or unsupported")
        advanceUntilIdle()
        val error = viewModel.state.value as InterviewSessionUiState.RecoverableError
        assertEquals(FailedStage.PLAYBACK, error.failedStage)
        assertEquals(FakePlaybackState.STOPPED, container.fakeAudioPlaybackRepository.playbackState)
    }

    @Test
    fun `close returns to setup retaining saved choices and reset clears them`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadySession(viewModel)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Close))
        advanceUntilIdle()
        assertEquals(InterviewSessionUiState.Setup(context), viewModel.state.value)
        val settings = container.settingsRepository as FakeSettingsRepository
        assertTrue(settings.preferences != null)
        assertEquals(1, container.fakeTemporaryFileCleaner.deleteAllCount)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Reset))
        advanceUntilIdle()

        assertEquals(InterviewSessionUiState.Setup(), viewModel.state.value)
        assertEquals(null, settings.preferences)
        assertEquals(1, settings.clearCount)
        assertEquals(2, container.fakeTemporaryFileCleaner.deleteAllCount)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.createReadySession(
        viewModel: InterviewSessionViewModel,
    ) {
        viewModel.dispatch(InterviewSessionAction.StartRecording(context))
        viewModel.dispatch(InterviewSessionAction.FinishRecording)
        viewModel.dispatch(InterviewSessionAction.CloneVoice)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.createReadyToPlaySession(
        viewModel: InterviewSessionViewModel,
        expectReady: Boolean = true,
    ) {
        if (viewModel.state.value !is InterviewSessionUiState.Ready) createReadySession(viewModel)
        viewModel.dispatch(InterviewSessionAction.StartListening)
        viewModel.dispatch(InterviewSessionAction.FinishListening)
        advanceUntilIdle()
        val questionReady = viewModel.state.value as InterviewSessionUiState.QuestionReady
        viewModel.dispatch(InterviewSessionAction.GenerateFromTranscript(questionReady.transcript))
        advanceUntilIdle()
        if (expectReady) assertTrue(viewModel.state.value is InterviewSessionUiState.ReadyToPlay)
    }

    private fun assertAccepted(result: ActionDispatchResult) {
        assertEquals(ActionDispatchResult.Accepted, result)
    }
}
