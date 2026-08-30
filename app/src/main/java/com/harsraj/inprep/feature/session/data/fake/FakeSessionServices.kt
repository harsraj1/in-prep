package com.harsraj.inprep.feature.session.data.fake

import com.harsraj.inprep.feature.session.domain.AnswerGenerationRepository
import com.harsraj.inprep.feature.session.domain.AudioPlaybackRepository
import com.harsraj.inprep.feature.session.domain.AudioSynthesisRepository
import com.harsraj.inprep.feature.session.domain.SettingsRepository
import com.harsraj.inprep.feature.session.domain.SpeechRecognitionRepository
import com.harsraj.inprep.feature.session.domain.TemporaryFileCleaner
import com.harsraj.inprep.feature.session.domain.VoiceCloningRepository
import com.harsraj.inprep.feature.session.domain.VoiceSampleRecorder
import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.GeneratedAudioReference
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileId
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileId
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata
import com.harsraj.inprep.feature.settings.domain.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.yield

class FakeVoiceSampleRecorder(
    var sample: VoiceSampleMetadata = VoiceSampleMetadata(
        id = "fake-sample",
        temporaryFile = TemporaryFileReference(TemporaryFileId("fake-sample-file")),
        durationMillis = 5_000,
        createdAtEpochMillis = 1,
    ),
) : VoiceSampleRecorder {
    var isRecording = false
        private set
    var startCount = 0
        private set
    var finishCount = 0
        private set
    var cancelCount = 0
        private set
    var nextStartFailure: Exception? = null
    var nextFinishFailure: Exception? = null

    override fun start() {
        nextStartFailure?.let {
            nextStartFailure = null
            throw it
        }
        check(!isRecording) { "Fake recorder is already recording" }
        startCount += 1
        isRecording = true
    }

    override fun finish(): VoiceSampleMetadata {
        nextFinishFailure?.let {
            nextFinishFailure = null
            throw it
        }
        check(isRecording) { "Fake recorder is not recording" }
        finishCount += 1
        isRecording = false
        return sample
    }

    override fun cancel() {
        cancelCount += 1
        isRecording = false
    }
}

class FakeVoiceCloningRepository(
    var profile: VoiceProfileReference = VoiceProfileReference(
        id = VoiceProfileId("fake-profile"),
        createdAtEpochMillis = 2,
    ),
) : VoiceCloningRepository {
    val samples = mutableListOf<VoiceSampleMetadata>()
    var failuresRemaining = 0

    override suspend fun createVoiceProfile(sample: VoiceSampleMetadata): VoiceProfileReference {
        samples += sample
        yield()
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("Fake voice cloning failed")
        }
        return profile
    }
}

class FakeAnswerGenerationRepository : AnswerGenerationRepository {
    val requests = mutableListOf<Pair<InterviewContext, InterviewQuestion>>()
    var failuresRemaining = 0

    override suspend fun generateAnswer(
        context: InterviewContext,
        question: InterviewQuestion,
    ): GeneratedAnswer {
        requests += context to question
        yield()
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("Fake answer generation failed")
        }
        return GeneratedAnswer(
            "Fake answer for the ${context.role} role at ${context.company}: ${question.text}",
        )
    }
}

class FakeSpeechRecognitionRepository(
    var question: InterviewQuestion = InterviewQuestion("Tell me about a difficult problem."),
) : SpeechRecognitionRepository {
    var isListening = false
        private set
    var startCount = 0
        private set
    var transcribeCount = 0
        private set
    var cancelCount = 0
        private set
    var failuresRemaining = 0

    override fun startListening() {
        check(!isListening) { "Fake recognizer is already listening" }
        startCount += 1
        isListening = true
    }

    override suspend fun stopAndTranscribe(): InterviewQuestion {
        check(isListening) { "Fake recognizer is not listening" }
        transcribeCount += 1
        isListening = false
        yield()
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("Fake transcription failed")
        }
        return question
    }

    override fun cancel() {
        cancelCount += 1
        isListening = false
    }
}

class FakeAudioSynthesisRepository(
    var audio: GeneratedAudioReference = GeneratedAudioReference(
        id = "fake-audio",
        temporaryFile = TemporaryFileReference(TemporaryFileId("fake-audio-file")),
    ),
) : AudioSynthesisRepository {
    val requests = mutableListOf<Pair<GeneratedAnswer, VoiceProfileReference>>()
    var failuresRemaining = 0

    override suspend fun synthesize(
        answer: GeneratedAnswer,
        voiceProfile: VoiceProfileReference,
    ): GeneratedAudioReference {
        requests += answer to voiceProfile
        yield()
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("Fake audio synthesis failed")
        }
        return audio
    }
}

enum class FakePlaybackState {
    STOPPED,
    PLAYING,
    PAUSED,
}

class FakeAudioPlaybackRepository : AudioPlaybackRepository {
    var playbackState = FakePlaybackState.STOPPED
        private set
    var lastAudio: GeneratedAudioReference? = null
        private set
    var playCount = 0
        private set
    var pauseCount = 0
        private set
    var resumeCount = 0
        private set
    var stopCount = 0
        private set
    var failuresRemaining = 0

    override suspend fun play(audio: GeneratedAudioReference) {
        yield()
        failIfRequested()
        lastAudio = audio
        playCount += 1
        playbackState = FakePlaybackState.PLAYING
    }

    override suspend fun pause() {
        yield()
        failIfRequested()
        pauseCount += 1
        playbackState = FakePlaybackState.PAUSED
    }

    override suspend fun resume() {
        yield()
        failIfRequested()
        resumeCount += 1
        playbackState = FakePlaybackState.PLAYING
    }

    override suspend fun stop() {
        yield()
        stopCount += 1
        playbackState = FakePlaybackState.STOPPED
    }

    private fun failIfRequested() {
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("Fake playback failed")
        }
    }
}

class FakeSettingsRepository(
    var preferences: SessionPreferences? = null,
) : SettingsRepository {
    private val mutableSettings = MutableStateFlow(
        AppSettings(
            interviewContext = preferences?.context,
            voiceProfile = preferences?.voiceProfile,
        ),
    )
    override val settings: StateFlow<AppSettings> = mutableSettings
    var saveCount = 0
        private set
    var clearCount = 0
        private set

    override suspend fun loadSettings(): AppSettings {
        yield()
        return mutableSettings.value
    }

    override suspend fun saveInterviewContext(context: InterviewContext) {
        yield()
        mutableSettings.value = mutableSettings.value.copy(interviewContext = context)
    }

    override suspend fun saveSessionPreferences(preferences: SessionPreferences) {
        yield()
        saveCount += 1
        this.preferences = preferences
        mutableSettings.value = mutableSettings.value.copy(
            interviewContext = preferences.context,
            voiceProfile = preferences.voiceProfile,
        )
    }

    override suspend fun saveVoiceboxBaseUrl(baseUrl: String) {
        yield()
        mutableSettings.value = mutableSettings.value.copy(voiceboxBaseUrl = baseUrl)
    }

    override suspend fun reset() {
        yield()
        clearCount += 1
        preferences = null
        mutableSettings.value = AppSettings()
    }
}

class FakeTemporaryFileCleaner : TemporaryFileCleaner {
    val deletedFiles = mutableListOf<TemporaryFileReference>()
    var deleteAllCount = 0
        private set

    override suspend fun delete(file: TemporaryFileReference) {
        yield()
        deletedFiles += file
    }

    override suspend fun deleteAll() {
        yield()
        deleteAllCount += 1
    }
}
