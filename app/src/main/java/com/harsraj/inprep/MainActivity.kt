package com.harsraj.inprep

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.harsraj.inprep.feature.session.presentation.InterviewSessionViewModel
import com.harsraj.inprep.feature.session.presentation.MicrophoneDenialRecovery
import com.harsraj.inprep.feature.session.presentation.MicrophonePermissionNextStep
import com.harsraj.inprep.feature.session.presentation.MicrophonePermissionPolicy
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.ui.InterviewPreparationScreen
import com.harsraj.inprep.ui.theme.InPrepTheme

class MainActivity : ComponentActivity() {
    private val sessionViewModel: InterviewSessionViewModel by viewModels {
        viewModelFactory {
            initializer {
                (application as InPrepApplication).container.createInterviewSessionViewModel()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by sessionViewModel.state.collectAsStateWithLifecycle()
            val container = (application as InPrepApplication).container
            val settings by container.settingsRepository.settings.collectAsState(
                initial = com.harsraj.inprep.feature.settings.domain.AppSettings(),
            )

            InPrepTheme {
                val permissionGate = rememberMicrophonePermissionGate(
                    onGranted = { context ->
                        sessionViewModel.dispatch(
                            com.harsraj.inprep.feature.session.presentation.InterviewSessionAction
                                .StartRecording(context),
                        )
                    },
                )
                InterviewPreparationScreen(
                    uiState = state,
                    reusablePreferences = settings.reusableSessionPreferences,
                    onAction = sessionViewModel::dispatch,
                    onStartRecording = permissionGate.requestRecording,
                )
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) sessionViewModel.onHostStopped()
        }
        lifecycle.addObserver(lifecycleObserver)
    }
}

private class MicrophonePermissionGate(
    val requestRecording: (InterviewContext) -> Unit,
)

@androidx.compose.runtime.Composable
private fun ComponentActivity.rememberMicrophonePermissionGate(
    onGranted: (InterviewContext) -> Unit,
): MicrophonePermissionGate {
    var pendingContext by remember { mutableStateOf<InterviewContext?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    var showDenied by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var requestInFlight by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestInFlight = false
        val context = pendingContext
        if (granted && context != null) {
            pendingContext = null
            onGranted(context)
        } else {
            permanentlyDenied = MicrophonePermissionPolicy.afterDenial(
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            ) == MicrophoneDenialRecovery.OPEN_SETTINGS
            showDenied = true
        }
    }

    fun launchPermissionRequest() {
        if (requestInFlight) return
        requestInFlight = true
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val gate = remember(launcher) {
        MicrophonePermissionGate(requestRecording = request@{ context ->
            if (requestInFlight || showRationale || showDenied) return@request
            pendingContext = context
            when (
                MicrophonePermissionPolicy.beforeRequest(
                    isGranted = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED,
                    shouldShowRationale = shouldShowRequestPermissionRationale(
                        Manifest.permission.RECORD_AUDIO,
                    ),
                )
            ) {
                MicrophonePermissionNextStep.START_RECORDING -> {
                    pendingContext = null
                    onGranted(context)
                }
                MicrophonePermissionNextStep.SHOW_RATIONALE -> showRationale = true
                MicrophonePermissionNextStep.REQUEST_PERMISSION -> launchPermissionRequest()
            }
        })
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Microphone permission") },
            text = { Text("In Prep needs microphone access only while you deliberately record a voice sample.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        launchPermissionRequest()
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false; pendingContext = null }) {
                    Text("Not now")
                }
            },
        )
    }

    if (showDenied) {
        AlertDialog(
            onDismissRequest = { showDenied = false; pendingContext = null },
            title = { Text("Microphone access denied") },
            text = {
                Text(
                    if (permanentlyDenied) {
                        "Enable microphone permission in system settings to record a voice sample. You can continue using the app without recording."
                    } else {
                        "No recording was started. You can retry the permission request or continue without recording."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDenied = false
                        if (permanentlyDenied) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null),
                                ),
                            )
                            pendingContext = null
                        } else {
                            launchPermissionRequest()
                        }
                    },
                ) { Text(if (permanentlyDenied) "Open settings" else "Retry") }
            },
            dismissButton = {
                TextButton(onClick = { showDenied = false; pendingContext = null }) {
                    Text("Not now")
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { pendingContext = null }
    }
    return gate
}
