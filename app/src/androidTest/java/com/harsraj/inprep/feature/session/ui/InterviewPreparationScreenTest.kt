package com.harsraj.inprep.feature.session.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.GeneratedAudioReference
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import com.harsraj.inprep.feature.session.domain.model.PlaybackContent
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileId
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileId
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata
import com.harsraj.inprep.feature.session.presentation.ActionDispatchResult
import com.harsraj.inprep.feature.session.presentation.FailedStage
import com.harsraj.inprep.feature.session.presentation.InterviewSessionAction
import com.harsraj.inprep.feature.session.presentation.InterviewSessionUiState
import com.harsraj.inprep.feature.session.presentation.RecoveryPoint
import com.harsraj.inprep.ui.theme.InPrepTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InterviewPreparationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupValidationShowsHelpfulErrorsBeforeDispatching() {
        val actions = mutableListOf<InterviewSessionAction>()
        setScreen(InterviewSessionUiState.Setup(), actions)

        composeRule.onNodeWithText("Record voice sample").performScrollTo().performClick()

        composeRule.onNodeWithText("Enter the company you are preparing for.").assertIsDisplayed()
        composeRule.onNodeWithText("Enter the role you want to practice for.").assertIsDisplayed()
        assertTrue(actions.isEmpty())

        composeRule.onNodeWithTag(SessionUiTags.COMPANY_FIELD).performTextInput("Sample Company")
        composeRule.onNodeWithTag(SessionUiTags.ROLE_FIELD).performTextInput("Android Engineer")
        composeRule.onNodeWithText("Record voice sample").performScrollTo().performClick()

        assertTrue(actions.single() is InterviewSessionAction.StartRecording)
    }

    @Test
    fun setupCanStartTextOnlyWithoutRecording() {
        val actions = mutableListOf<InterviewSessionAction>()
        setScreen(InterviewSessionUiState.Setup(), actions)
        composeRule.onNodeWithTag(SessionUiTags.COMPANY_FIELD).performTextInput("Sample Company")
        composeRule.onNodeWithTag(SessionUiTags.ROLE_FIELD).performTextInput("Android Engineer")

        composeRule.onNodeWithText("Continue with text answers").performScrollTo().performClick()

        assertEquals(listOf(InterviewSessionAction.StartTextOnly(context)), actions)
    }

    @Test
    fun controlsAreRenderedOnlyForValidStates() {
        var state by mutableStateOf<InterviewSessionUiState>(readyState())
        composeRule.setContent {
            InPrepTheme {
                InterviewPreparationScreen(
                    uiState = state,
                    onAction = { ActionDispatchResult.Accepted },
                )
            }
        }

        composeRule.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Start").assertDoesNotExist()

        composeRule.runOnUiThread { state = InterviewSessionUiState.ReadyToPlay(playbackContent()) }

        composeRule.onNodeWithText("Start").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Listen").assertDoesNotExist()
    }

    @Test
    fun completeHappyPathExposesTheNextValidControl() {
        val sample = VoiceSampleMetadata(
            id = "synthetic-sample",
            temporaryFile = TemporaryFileReference(TemporaryFileId("synthetic-sample-file")),
            durationMillis = 5_000,
            createdAtEpochMillis = 1,
        )
        var state by mutableStateOf<InterviewSessionUiState>(
            InterviewSessionUiState.Recording(context),
        )
        composeRule.setContent {
            InPrepTheme {
                InterviewPreparationScreen(
                    uiState = state,
                    onAction = { ActionDispatchResult.Accepted },
                )
            }
        }

        fun show(next: InterviewSessionUiState, expectedControl: String) {
            composeRule.runOnUiThread { state = next }
            composeRule.onNodeWithText(expectedControl).performScrollTo().assertIsEnabled()
        }

        composeRule.onNodeWithText("Stop recording").performScrollTo().assertIsEnabled()
        show(InterviewSessionUiState.VoiceSampleReady(context, sample), "Clone voice")
        show(InterviewSessionUiState.Ready(context, profile), "Listen")
        show(InterviewSessionUiState.Listening(context, profile), "Finish question")
        show(
            InterviewSessionUiState.QuestionReady(
                context,
                profile,
                "How do you prevent duplicate work?",
            ),
            "Generate answer",
        )
        show(InterviewSessionUiState.ReadyToPlay(playbackContent()), "Start")
        show(InterviewSessionUiState.Playing(playbackContent()), "Pause")
        show(InterviewSessionUiState.Paused(playbackContent()), "Resume")
    }

    @Test
    fun playingAndPausedStatesUsePauseAndResumeLabels() {
        var state by mutableStateOf<InterviewSessionUiState>(
            InterviewSessionUiState.Playing(playbackContent()),
        )
        composeRule.setContent {
            InPrepTheme {
                InterviewPreparationScreen(
                    uiState = state,
                    onAction = { ActionDispatchResult.Accepted },
                )
            }
        }

        composeRule.onNodeWithText("Pause").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Resume").assertDoesNotExist()

        composeRule.runOnUiThread { state = InterviewSessionUiState.Paused(playbackContent()) }

        composeRule.onNodeWithText("Resume").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Pause").assertDoesNotExist()
    }

    @Test
    fun transcriptCanBeReviewedAndEditedBeforeGeneration() {
        val actions = mutableListOf<InterviewSessionAction>()
        setScreen(
            InterviewSessionUiState.QuestionReady(
                context,
                profile,
                "How do you investigate crashes?",
            ),
            actions,
        )

        val transcriptField = composeRule.onNodeWithTag(SessionUiTags.TRANSCRIPT_FIELD)
        transcriptField.performScrollTo()
        transcriptField.performTextClearance()
        transcriptField.performTextInput("How do you investigate ANRs?")
        composeRule.onNodeWithText("Generate answer").performScrollTo().performClick()

        assertEquals(
            InterviewSessionAction.GenerateFromTranscript("How do you investigate ANRs?"),
            actions.single(),
        )
    }

    @Test
    fun recoverableErrorAnnouncesStatusAndDispatchesRetry() {
        val actions = mutableListOf<InterviewSessionAction>()
        val error = InterviewSessionUiState.RecoverableError(
            recoveryPoint = RecoveryPoint.Ready(context, profile),
            failedStage = FailedStage.GENERATE_ANSWER,
            message = "Fake generation failed",
        )
        setScreen(error, actions)

        composeRule.onNodeWithContentDescription(
            "Session status: Action needed: Fake generation failed",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()

        assertEquals(listOf(InterviewSessionAction.Retry), actions)
    }

    @Test
    fun synthesisFailureKeepsTheGeneratedAnswerVisibleForRetry() {
        val actions = mutableListOf<InterviewSessionAction>()
        val answer = GeneratedAnswer("A prepared answer that Gemini should not regenerate.")
        setScreen(
            InterviewSessionUiState.RecoverableError(
                recoveryPoint = RecoveryPoint.AnswerReady(
                    context,
                    profile,
                    InterviewQuestion("How do retries work?"),
                    answer,
                ),
                failedStage = FailedStage.SYNTHESIZE_SPEECH,
                message = "Voicebox could not prepare the audio. Check the trusted-LAN server and retry.",
            ),
            actions,
        )

        composeRule.onNodeWithText(answer.text).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf(InterviewSessionAction.Retry), actions)
    }

    @Test
    fun cloningFailureOffersTextOnlyContinuation() {
        val actions = mutableListOf<InterviewSessionAction>()
        setScreen(
            InterviewSessionUiState.RecoverableError(
                recoveryPoint = RecoveryPoint.Setup(context),
                failedStage = FailedStage.CLONE_VOICE,
                message = "The voice profile could not be created.",
            ),
            actions,
        )

        composeRule.onNodeWithText("Continue with text answers")
            .performScrollTo()
            .performClick()
        assertEquals(listOf(InterviewSessionAction.ContinueWithoutVoice), actions)
    }

    @Test
    fun closeAndResetRequireConfirmation() {
        val actions = mutableListOf<InterviewSessionAction>()
        var state by mutableStateOf<InterviewSessionUiState>(readyState())
        composeRule.setContent {
            InPrepTheme {
                InterviewPreparationScreen(
                    uiState = state,
                    onAction = { action ->
                        actions += action
                        if (action == InterviewSessionAction.Close) {
                            state = InterviewSessionUiState.Closed
                        } else if (action == InterviewSessionAction.Reset) {
                            state = InterviewSessionUiState.Setup()
                        }
                        ActionDispatchResult.Accepted
                    },
                )
            }
        }

        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Close this session?").assertIsDisplayed()
        assertTrue(actions.isEmpty())
        composeRule.onNodeWithText("Close session").performClick()
        assertEquals(InterviewSessionAction.Close, actions.single())

        composeRule.onNodeWithText("Start over").performScrollTo().performClick()
        composeRule.onNodeWithText("Start over?").assertIsDisplayed()
        composeRule.onNodeWithText("Clear and start over").performClick()
        assertEquals(InterviewSessionAction.Reset, actions.last())
    }

    @Test
    fun privacyDialogExplainsEveryExternalDataBoundary() {
        val actions = mutableListOf<InterviewSessionAction>()
        setScreen(readyState(), actions)

        composeRule.onNodeWithText("Privacy").performClick()

        composeRule.onNodeWithText("Privacy and data sharing").assertIsDisplayed()
        composeRule.onNodeWithText("Gemini", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Voicebox server operator", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        assertTrue(actions.isEmpty())
    }

    private fun setScreen(
        state: InterviewSessionUiState,
        actions: MutableList<InterviewSessionAction>,
    ) {
        composeRule.setContent {
            InPrepTheme {
                InterviewPreparationScreen(
                    uiState = state,
                    onAction = {
                        actions += it
                        ActionDispatchResult.Accepted
                    },
                )
            }
        }
    }

    private fun readyState() = InterviewSessionUiState.Ready(context, profile)

    private fun playbackContent() = PlaybackContent(
        context = context,
        voiceProfile = profile,
        question = InterviewQuestion("How do you test state-driven interfaces?"),
        answer = GeneratedAnswer("I render deterministic states and assert valid actions."),
        audio = GeneratedAudioReference(
            id = "test-audio",
            temporaryFile = TemporaryFileReference(TemporaryFileId("test-file")),
        ),
    )

    private val context = InterviewContext("Sample Company", "Android Engineer")
    private val profile = VoiceProfileReference(VoiceProfileId("test-profile"), 1)
}
