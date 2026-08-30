package com.harsraj.inprep.feature.session.presentation

import com.harsraj.inprep.di.FakeApplicationContainer
import com.harsraj.inprep.feature.session.data.fake.FakePlaybackState
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        assertTrue(viewModel.state.value is InterviewSessionUiState.Cloning)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.StartListening))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Listening)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.FinishListening))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Transcribing)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.ReadyToPlay)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Play))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Playing)
        advanceUntilIdle()
        assertEquals(FakePlaybackState.PLAYING, container.audioPlaybackRepository.playbackState)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Pause))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Paused)
        advanceUntilIdle()
        assertEquals(FakePlaybackState.PAUSED, container.audioPlaybackRepository.playbackState)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Resume))
        advanceUntilIdle()
        assertEquals(FakePlaybackState.PLAYING, container.audioPlaybackRepository.playbackState)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.PlaybackCompleted))
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
        assertEquals(
            listOf(container.audioSynthesisRepository.audio.temporaryFile),
            container.temporaryFileCleaner.deletedFiles.takeLast(1),
        )

        val stateTypes = observedStates.map { it::class }
        assertTrue(InterviewSessionUiState.Setup::class in stateTypes)
        assertTrue(InterviewSessionUiState.Recording::class in stateTypes)
        assertTrue(InterviewSessionUiState.Cloning::class in stateTypes)
        assertTrue(InterviewSessionUiState.Ready::class in stateTypes)
        assertTrue(InterviewSessionUiState.Listening::class in stateTypes)
        assertTrue(InterviewSessionUiState.Transcribing::class in stateTypes)
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
        assertEquals(1, container.voiceSampleRecorder.startCount)
        assertTrue(viewModel.state.value is InterviewSessionUiState.Recording)
    }

    @Test
    fun `cancel releases recording and listening and returns to stable states`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(InterviewSessionAction.StartRecording(context))
        assertAccepted(viewModel.dispatch(InterviewSessionAction.Cancel))
        assertFalse(container.voiceSampleRecorder.isRecording)
        assertEquals(1, container.voiceSampleRecorder.cancelCount)
        assertEquals(InterviewSessionUiState.Setup(context), viewModel.state.value)

        createReadySession(viewModel)
        viewModel.dispatch(InterviewSessionAction.StartListening)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.Cancel))
        assertFalse(container.speechRecognitionRepository.isListening)
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
    }

    @Test
    fun `retry repeats failed cloning and reaches ready`() = runTest {
        val container = FakeApplicationContainer().apply {
            voiceCloningRepository.failuresRemaining = 1
        }
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(InterviewSessionAction.StartRecording(context))
        viewModel.dispatch(InterviewSessionAction.FinishRecording)
        advanceUntilIdle()

        val error = viewModel.state.value as InterviewSessionUiState.RecoverableError
        assertEquals(FailedStage.CLONE_VOICE, error.failedStage)
        assertAccepted(viewModel.dispatch(InterviewSessionAction.Retry))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Cloning)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
        assertEquals(2, container.voiceCloningRepository.samples.size)
        assertEquals(1, container.settingsRepository.saveCount)
    }

    @Test
    fun `stop from playback releases player and generated audio`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadyToPlaySession(viewModel)
        viewModel.dispatch(InterviewSessionAction.Play)
        advanceUntilIdle()

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Stop))
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
        advanceUntilIdle()

        assertEquals(FakePlaybackState.STOPPED, container.audioPlaybackRepository.playbackState)
        assertEquals(1, container.audioPlaybackRepository.stopCount)
        assertTrue(
            container.audioSynthesisRepository.audio.temporaryFile in
                container.temporaryFileCleaner.deletedFiles,
        )
    }

    @Test
    fun `close blocks actions and reset clears persisted and temporary state`() = runTest {
        val container = FakeApplicationContainer()
        val viewModel = container.createInterviewSessionViewModel(StandardTestDispatcher(testScheduler))
        createReadySession(viewModel)

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Close))
        assertEquals(InterviewSessionUiState.Closed, viewModel.state.value)
        assertTrue(
            viewModel.dispatch(InterviewSessionAction.StartRecording(context)) is
                ActionDispatchResult.Rejected,
        )
        advanceUntilIdle()

        assertAccepted(viewModel.dispatch(InterviewSessionAction.Reset))
        advanceUntilIdle()

        assertEquals(InterviewSessionUiState.Setup(), viewModel.state.value)
        assertEquals(null, container.settingsRepository.preferences)
        assertEquals(1, container.settingsRepository.clearCount)
        assertEquals(2, container.temporaryFileCleaner.deleteAllCount)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.createReadySession(
        viewModel: InterviewSessionViewModel,
    ) {
        viewModel.dispatch(InterviewSessionAction.StartRecording(context))
        viewModel.dispatch(InterviewSessionAction.FinishRecording)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.Ready)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.createReadyToPlaySession(
        viewModel: InterviewSessionViewModel,
    ) {
        createReadySession(viewModel)
        viewModel.dispatch(InterviewSessionAction.StartListening)
        viewModel.dispatch(InterviewSessionAction.FinishListening)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is InterviewSessionUiState.ReadyToPlay)
    }

    private fun assertAccepted(result: ActionDispatchResult) {
        assertEquals(ActionDispatchResult.Accepted, result)
    }
}
