package com.harsraj.inprep.feature.session.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.GeneratedAudioReference
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import com.harsraj.inprep.feature.session.domain.model.PlaybackContent
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileId
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileId
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.presentation.ActionDispatchResult
import com.harsraj.inprep.feature.session.presentation.FailedStage
import com.harsraj.inprep.feature.session.presentation.InterviewSessionAction
import com.harsraj.inprep.feature.session.presentation.InterviewSessionUiState
import com.harsraj.inprep.feature.session.presentation.RecoveryPoint
import com.harsraj.inprep.ui.theme.InPrepTheme

object SessionUiTags {
    const val COMPANY_FIELD = "company_field"
    const val ROLE_FIELD = "role_field"
    const val STATUS = "session_status"
    const val PRIMARY_INTERVIEW_ACTION = "primary_interview_action"
}

@Composable
fun InterviewPreparationScreen(
    uiState: InterviewSessionUiState,
    onAction: (InterviewSessionAction) -> ActionDispatchResult,
    modifier: Modifier = Modifier,
    reusablePreferences: SessionPreferences? = null,
) {
    var showCloseConfirmation by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            val isWide = maxWidth >= 720.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (isWide) 32.dp else 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(
                    canClose = uiState !is InterviewSessionUiState.Closed,
                    onClose = { showCloseConfirmation = true },
                )
                SessionStatus(uiState)

                if (uiState is InterviewSessionUiState.Closed) {
                    ClosedCard(onStartOver = { showResetConfirmation = true })
                } else if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            TargetCard(uiState, onAction)
                            VoiceSampleCard(uiState, reusablePreferences, onAction)
                        }
                        InterviewCard(
                            uiState = uiState,
                            onAction = onAction,
                            modifier = Modifier.weight(1.2f),
                        )
                    }
                } else {
                    TargetCard(uiState, onAction)
                    VoiceSampleCard(uiState, reusablePreferences, onAction)
                    InterviewCard(uiState, onAction)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showCloseConfirmation) {
        ConfirmationDialog(
            title = "Close this session?",
            message = "Listening, playback, and temporary session audio will stop. Your reusable voice profile is kept.",
            confirmLabel = "Close session",
            onDismiss = { showCloseConfirmation = false },
            onConfirm = {
                showCloseConfirmation = false
                onAction(InterviewSessionAction.Close)
            },
        )
    }
    if (showResetConfirmation) {
        ConfirmationDialog(
            title = "Start over?",
            message = "This clears the saved interview target and reusable voice-profile reference.",
            confirmLabel = "Clear and start over",
            onDismiss = { showResetConfirmation = false },
            onConfirm = {
                showResetConfirmation = false
                onAction(InterviewSessionAction.Reset)
            },
        )
    }
}

@Composable
private fun Header(canClose: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "In Prep",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Practice a focused interview with your private voice profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canClose) {
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun SessionStatus(uiState: InterviewSessionUiState) {
    val status = uiState.statusText()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (uiState) {
                is InterviewSessionUiState.RecoverableError -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SessionUiTags.STATUS)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Session status: $status"
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (uiState.isBusy()) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = "Working" },
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TargetCard(
    uiState: InterviewSessionUiState,
    onAction: (InterviewSessionAction) -> ActionDispatchResult,
) {
    val setup = uiState as? InterviewSessionUiState.Setup
    if (setup == null) {
        val context = uiState.interviewContext() ?: return
        SectionCard(title = "Interview target") {
            Text(context.company, style = MaterialTheme.typography.titleMedium)
            Text(context.role, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val focusManager = LocalFocusManager.current
    var company by rememberSaveable { mutableStateOf(setup.savedContext?.company.orEmpty()) }
    var role by rememberSaveable { mutableStateOf(setup.savedContext?.role.orEmpty()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val companyError = attempted && company.isBlank()
    val roleError = attempted && role.isBlank()

    LaunchedEffect(setup.savedContext) {
        setup.savedContext?.let {
            company = it.company
            role = it.role
        }
    }

    fun record() {
        attempted = true
        if (company.isNotBlank() && role.isNotBlank()) {
            focusManager.clearFocus()
            onAction(
                InterviewSessionAction.StartRecording(
                    InterviewContext(company.trim(), role.trim()),
                ),
            )
        }
    }

    SectionCard(title = "1. Choose your target") {
        Text(
            "This context keeps practice answers relevant to the role you want.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = company,
            onValueChange = { company = it },
            label = { Text("Company") },
            placeholder = { Text("Enter a company") },
            supportingText = if (companyError) {
                { Text("Enter the company you are preparing for.") }
            } else null,
            isError = companyError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SessionUiTags.COMPANY_FIELD),
        )
        OutlinedTextField(
            value = role,
            onValueChange = { role = it },
            label = { Text("Target role") },
            placeholder = { Text("Enter a role") },
            supportingText = if (roleError) {
                { Text("Enter the role you want to practice for.") }
            } else null,
            isError = roleError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { record() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SessionUiTags.ROLE_FIELD),
        )
        Button(onClick = { record() }, modifier = Modifier.fillMaxWidth()) {
            Text("Record voice sample")
        }
    }
}

@Composable
private fun VoiceSampleCard(
    uiState: InterviewSessionUiState,
    reusablePreferences: SessionPreferences?,
    onAction: (InterviewSessionAction) -> ActionDispatchResult,
) {
    SectionCard(title = "2. Prepare your voice") {
        when (uiState) {
            is InterviewSessionUiState.Setup -> {
                Text("Record a short, clear sample in a quiet place.")
                reusablePreferences?.let { preferences ->
                    OutlinedButton(
                        onClick = {
                            onAction(InterviewSessionAction.ReuseVoiceProfile(preferences))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Reuse saved voice profile")
                    }
                }
            }
            is InterviewSessionUiState.Recording -> {
                Text("Recording… Speak naturally for several seconds.")
                ActionRow {
                    Button(onClick = { onAction(InterviewSessionAction.FinishRecording) }) {
                        Text("Stop recording")
                    }
                    OutlinedButton(onClick = { onAction(InterviewSessionAction.Cancel) }) {
                        Text("Discard")
                    }
                }
            }
            is InterviewSessionUiState.VoiceSampleReady -> {
                Text("Sample captured. Clone it now or discard it and try again.")
                ActionRow {
                    Button(onClick = { onAction(InterviewSessionAction.CloneVoice) }) {
                        Text("Clone voice")
                    }
                    OutlinedButton(
                        onClick = { onAction(InterviewSessionAction.DiscardVoiceSample) },
                    ) {
                        Text("Discard")
                    }
                }
            }
            is InterviewSessionUiState.Cloning -> Text("Creating the reusable voice profile…")
            is InterviewSessionUiState.RecoverableError -> {
                if (uiState.failedStage.isVoiceStage()) {
                    ErrorContent(uiState.message) {
                        onAction(InterviewSessionAction.Retry)
                    }
                } else {
                    Text("Your voice profile remains ready.")
                }
            }
            is InterviewSessionUiState.Closed -> Text("Session closed.")
            else -> Text("Voice profile ready for this practice session.")
        }
    }
}

@Composable
private fun InterviewCard(
    uiState: InterviewSessionUiState,
    onAction: (InterviewSessionAction) -> ActionDispatchResult,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "3. Practice the interview", modifier = modifier) {
        val question = uiState.questionText()
        val answer = uiState.answerText()
        LabeledContent("Recognized question", question ?: "No question captured yet.")
        HorizontalDivider()
        LabeledContent("Generated answer", answer ?: "Your practice answer will appear here.")

        when (uiState) {
            is InterviewSessionUiState.Ready -> PrimaryAction("Listen") {
                onAction(InterviewSessionAction.StartListening)
            }
            is InterviewSessionUiState.Listening -> {
                PrimaryAction("Finish question") { onAction(InterviewSessionAction.FinishListening) }
                StopButton(onAction)
            }
            is InterviewSessionUiState.Transcribing,
            is InterviewSessionUiState.GeneratingAnswer,
            is InterviewSessionUiState.SynthesizingSpeech,
            -> StopButton(onAction)
            is InterviewSessionUiState.ReadyToPlay -> {
                PrimaryAction("Start") { onAction(InterviewSessionAction.Play) }
                StopButton(onAction)
            }
            is InterviewSessionUiState.Playing -> {
                PrimaryAction("Pause") { onAction(InterviewSessionAction.Pause) }
                StopButton(onAction)
            }
            is InterviewSessionUiState.Paused -> {
                PrimaryAction("Resume") { onAction(InterviewSessionAction.Resume) }
                StopButton(onAction)
            }
            is InterviewSessionUiState.RecoverableError -> {
                if (!uiState.failedStage.isVoiceStage()) {
                    ErrorContent(uiState.message) { onAction(InterviewSessionAction.Retry) }
                    StopButton(onAction)
                }
            }
            else -> Text(
                "Complete voice setup to begin.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun PrimaryAction(label: String, action: () -> Unit) {
    Button(
        onClick = action,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SessionUiTags.PRIMARY_INTERVIEW_ACTION),
    ) { Text(label) }
}

@Composable
private fun StopButton(onAction: (InterviewSessionAction) -> ActionDispatchResult) {
    OutlinedButton(
        onClick = { onAction(InterviewSessionAction.Stop) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Stop") }
}

@Composable
private fun ErrorContent(message: String, retry: () -> Unit) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
    Button(onClick = retry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
}

@Composable
private fun LabeledContent(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ClosedCard(onStartOver: () -> Unit) {
    SectionCard(title = "Session closed") {
        Text("Microphone, playback, and temporary session resources have been released.")
        Button(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
            Text("Start over")
        }
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun InterviewSessionUiState.statusText(): String = when (this) {
    is InterviewSessionUiState.Setup -> "Enter your target and prepare a voice sample."
    is InterviewSessionUiState.Recording -> "Recording your voice sample."
    is InterviewSessionUiState.VoiceSampleReady -> "Voice sample ready to clone."
    is InterviewSessionUiState.Cloning -> "Creating your voice profile."
    is InterviewSessionUiState.Ready -> "Ready to listen for an interview question."
    is InterviewSessionUiState.Listening -> "Listening. Speak the interview question now."
    is InterviewSessionUiState.Transcribing -> "Transcribing the question."
    is InterviewSessionUiState.GeneratingAnswer -> "Generating a targeted answer."
    is InterviewSessionUiState.SynthesizingSpeech -> "Preparing spoken audio."
    is InterviewSessionUiState.ReadyToPlay -> "Answer ready to play."
    is InterviewSessionUiState.Playing -> "Playing the answer."
    is InterviewSessionUiState.Paused -> "Playback paused."
    is InterviewSessionUiState.RecoverableError -> "Action needed: $message"
    InterviewSessionUiState.Closed -> "Session closed and temporary resources released."
}

private fun InterviewSessionUiState.isBusy(): Boolean = when (this) {
    is InterviewSessionUiState.Cloning,
    is InterviewSessionUiState.Transcribing,
    is InterviewSessionUiState.GeneratingAnswer,
    is InterviewSessionUiState.SynthesizingSpeech,
    -> true
    else -> false
}

private fun InterviewSessionUiState.interviewContext(): InterviewContext? = when (this) {
    is InterviewSessionUiState.Setup -> savedContext
    is InterviewSessionUiState.Recording -> context
    is InterviewSessionUiState.VoiceSampleReady -> context
    is InterviewSessionUiState.Cloning -> context
    is InterviewSessionUiState.Ready -> context
    is InterviewSessionUiState.Listening -> context
    is InterviewSessionUiState.Transcribing -> context
    is InterviewSessionUiState.GeneratingAnswer -> context
    is InterviewSessionUiState.SynthesizingSpeech -> context
    is InterviewSessionUiState.ReadyToPlay -> content.context
    is InterviewSessionUiState.Playing -> content.context
    is InterviewSessionUiState.Paused -> content.context
    is InterviewSessionUiState.RecoverableError -> when (val point = recoveryPoint) {
        is RecoveryPoint.Setup -> point.context
        is RecoveryPoint.Ready -> point.context
        is RecoveryPoint.ReadyToPlay -> point.content.context
    }
    InterviewSessionUiState.Closed -> null
}

private fun InterviewSessionUiState.questionText(): String? = when (this) {
    is InterviewSessionUiState.GeneratingAnswer -> question.text
    is InterviewSessionUiState.SynthesizingSpeech -> question.text
    is InterviewSessionUiState.ReadyToPlay -> content.question.text
    is InterviewSessionUiState.Playing -> content.question.text
    is InterviewSessionUiState.Paused -> content.question.text
    is InterviewSessionUiState.RecoverableError ->
        (recoveryPoint as? RecoveryPoint.ReadyToPlay)?.content?.question?.text
    else -> null
}

private fun InterviewSessionUiState.answerText(): String? = when (this) {
    is InterviewSessionUiState.SynthesizingSpeech -> answer.text
    is InterviewSessionUiState.ReadyToPlay -> content.answer.text
    is InterviewSessionUiState.Playing -> content.answer.text
    is InterviewSessionUiState.Paused -> content.answer.text
    is InterviewSessionUiState.RecoverableError ->
        (recoveryPoint as? RecoveryPoint.ReadyToPlay)?.content?.answer?.text
    else -> null
}

private fun FailedStage.isVoiceStage(): Boolean =
    this == FailedStage.START_RECORDING || this == FailedStage.CLONE_VOICE

private val PreviewContext = InterviewContext("Sample Company", "Android Engineer")
private val PreviewProfile = VoiceProfileReference(VoiceProfileId("preview-profile"), 1)
private val PreviewContent = PlaybackContent(
    context = PreviewContext,
    voiceProfile = PreviewProfile,
    question = InterviewQuestion("How do you design a resilient offline-first feature?"),
    answer = GeneratedAnswer("I begin with clear data ownership and an explicit synchronization policy."),
    audio = GeneratedAudioReference(
        "preview-audio",
        TemporaryFileReference(TemporaryFileId("preview-file")),
    ),
)
private val PreviewAction: (InterviewSessionAction) -> ActionDispatchResult = {
    ActionDispatchResult.Accepted
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SetupPreview() {
    InPrepTheme { InterviewPreparationScreen(InterviewSessionUiState.Setup(), onAction = PreviewAction) }
}

@Preview(showBackground = true, widthDp = 840, heightDp = 720)
@Composable
private fun ReadyToPlayPreview() {
    InPrepTheme {
        InterviewPreparationScreen(
            InterviewSessionUiState.ReadyToPlay(PreviewContent),
            onAction = PreviewAction,
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ErrorDarkPreview() {
    InPrepTheme(darkTheme = true) {
        InterviewPreparationScreen(
            InterviewSessionUiState.RecoverableError(
                recoveryPoint = RecoveryPoint.Ready(PreviewContext, PreviewProfile),
                failedStage = FailedStage.GENERATE_ANSWER,
                message = "The fake answer generator needs another try.",
            ),
            onAction = PreviewAction,
        )
    }
}
