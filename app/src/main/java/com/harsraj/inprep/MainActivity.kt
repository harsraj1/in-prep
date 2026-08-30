package com.harsraj.inprep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.harsraj.inprep.feature.session.presentation.InterviewSessionViewModel
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

            InPrepTheme {
                InterviewPreparationScreen(
                    uiState = state,
                    reusablePreferences = container.settingsRepository.preferences,
                    onAction = sessionViewModel::dispatch,
                )
            }
        }
    }
}
