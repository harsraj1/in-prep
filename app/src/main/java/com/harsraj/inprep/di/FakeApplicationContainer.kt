package com.harsraj.inprep.di

import com.harsraj.inprep.feature.session.data.fake.FakeAnswerGenerationRepository
import com.harsraj.inprep.feature.session.data.fake.FakeAudioPlaybackRepository
import com.harsraj.inprep.feature.session.data.fake.FakeAudioSynthesisRepository
import com.harsraj.inprep.feature.session.data.fake.FakeSettingsRepository
import com.harsraj.inprep.feature.session.data.fake.FakeSpeechRecognitionRepository
import com.harsraj.inprep.feature.session.data.fake.FakeTemporaryFileCleaner
import com.harsraj.inprep.feature.session.data.fake.FakeVoiceCloningRepository
import com.harsraj.inprep.feature.session.data.fake.FakeVoiceSampleRecorder
import com.harsraj.inprep.feature.session.presentation.InterviewSessionViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class FakeApplicationContainer {
    val voiceSampleRecorder = FakeVoiceSampleRecorder()
    val voiceCloningRepository = FakeVoiceCloningRepository()
    val answerGenerationRepository = FakeAnswerGenerationRepository()
    val speechRecognitionRepository = FakeSpeechRecognitionRepository()
    val audioSynthesisRepository = FakeAudioSynthesisRepository()
    val audioPlaybackRepository = FakeAudioPlaybackRepository()
    val settingsRepository = FakeSettingsRepository()
    val temporaryFileCleaner = FakeTemporaryFileCleaner()

    fun createInterviewSessionViewModel(
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ): InterviewSessionViewModel = InterviewSessionViewModel(
        voiceSampleRecorder = voiceSampleRecorder,
        voiceCloningRepository = voiceCloningRepository,
        answerGenerationRepository = answerGenerationRepository,
        speechRecognitionRepository = speechRecognitionRepository,
        audioSynthesisRepository = audioSynthesisRepository,
        audioPlaybackRepository = audioPlaybackRepository,
        settingsRepository = settingsRepository,
        temporaryFileCleaner = temporaryFileCleaner,
        initialPreferences = settingsRepository.preferences,
        dispatcher = dispatcher,
    )
}
