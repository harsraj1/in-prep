package com.harsraj.inprep.feature.session.presentation

import com.harsraj.inprep.feature.session.domain.model.InterviewContext

sealed interface InterviewSessionAction {
    data class StartRecording(val context: InterviewContext) : InterviewSessionAction

    data object FinishRecording : InterviewSessionAction

    data object StartListening : InterviewSessionAction

    data object FinishListening : InterviewSessionAction

    data object Play : InterviewSessionAction

    data object Pause : InterviewSessionAction

    data object Resume : InterviewSessionAction

    data object PlaybackCompleted : InterviewSessionAction

    data object Cancel : InterviewSessionAction

    data object Retry : InterviewSessionAction

    data object Stop : InterviewSessionAction

    data object Close : InterviewSessionAction

    data object Reset : InterviewSessionAction
}

sealed interface ActionDispatchResult {
    data object Accepted : ActionDispatchResult

    data class Rejected(
        val action: InterviewSessionAction,
        val state: InterviewSessionUiState,
        val reason: String,
    ) : ActionDispatchResult
}
