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
import com.harsraj.inprep.feature.session.domain.SettingsRepository
import com.harsraj.inprep.feature.session.domain.TemporaryFileCleaner
import com.harsraj.inprep.feature.session.domain.AudioSynthesisRepository
import com.harsraj.inprep.feature.session.domain.VoiceCloningRepository
import com.harsraj.inprep.feature.session.domain.VoiceSampleRecorder
import com.harsraj.inprep.feature.session.domain.SpeechRecognitionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class FakeApplicationContainer(
    val settingsRepository: SettingsRepository = FakeSettingsRepository(),
    val voiceSampleRecorder: VoiceSampleRecorder = FakeVoiceSampleRecorder(),
    val temporaryFileCleaner: TemporaryFileCleaner = FakeTemporaryFileCleaner(),
    val voiceCloningRepository: VoiceCloningRepository = FakeVoiceCloningRepository(),
    val audioSynthesisRepository: AudioSynthesisRepository = FakeAudioSynthesisRepository(),
    val speechRecognitionRepository: SpeechRecognitionRepository = FakeSpeechRecognitionRepository(),
) {
    val fakeVoiceSampleRecorder: FakeVoiceSampleRecorder
        get() = voiceSampleRecorder as FakeVoiceSampleRecorder
    val fakeTemporaryFileCleaner: FakeTemporaryFileCleaner
        get() = temporaryFileCleaner as FakeTemporaryFileCleaner
    val fakeVoiceCloningRepository: FakeVoiceCloningRepository
        get() = voiceCloningRepository as FakeVoiceCloningRepository
    val answerGenerationRepository = FakeAnswerGenerationRepository()
    val fakeSpeechRecognitionRepository: FakeSpeechRecognitionRepository
        get() = speechRecognitionRepository as FakeSpeechRecognitionRepository
    val fakeAudioSynthesisRepository: FakeAudioSynthesisRepository
        get() = audioSynthesisRepository as FakeAudioSynthesisRepository
    val audioPlaybackRepository = FakeAudioPlaybackRepository()

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
        initialPreferences = (settingsRepository as? FakeSettingsRepository)?.preferences,
        dispatcher = dispatcher,
    )
}
