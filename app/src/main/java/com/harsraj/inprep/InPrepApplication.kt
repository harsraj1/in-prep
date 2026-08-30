package com.harsraj.inprep

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import com.harsraj.inprep.di.FakeApplicationContainer
import com.harsraj.inprep.feature.settings.data.DataStoreSettingsRepository
import com.harsraj.inprep.feature.settings.domain.VoiceboxBaseUrlValidator
import com.harsraj.inprep.feature.session.data.recording.AndroidVoiceSampleRecorder
import com.harsraj.inprep.feature.session.data.recording.CompositeTemporaryFileCleaner
import com.harsraj.inprep.feature.session.data.recording.PrivateVoiceSampleStore
import com.harsraj.inprep.feature.voicebox.data.PrivateGeneratedAudioStore
import com.harsraj.inprep.feature.voicebox.data.VoiceboxVoiceServices
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

private val Application.settingsDataStore by preferencesDataStore(name = "non_secret_settings")

class InPrepApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val container: FakeApplicationContainer by lazy {
        val validator = VoiceboxBaseUrlValidator(BuildConfig.DEBUG)
        val settingsRepository = DataStoreSettingsRepository(
            dataStore = settingsDataStore,
            voiceboxBaseUrlValidator = validator,
        )
        val sampleStore = PrivateVoiceSampleStore(this).also {
            it.deleteExpired(System.currentTimeMillis())
        }
        val generatedAudioStore = PrivateGeneratedAudioStore(this).also {
            it.deleteExpired(System.currentTimeMillis())
        }
        val voicebox = VoiceboxVoiceServices(
            client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(6, TimeUnit.MINUTES)
                .build(),
            baseUrlProvider = {
                settingsRepository.loadSettings().voiceboxBaseUrl
                    ?: BuildConfig.VOICEBOX_BASE_URL
            },
            baseUrlValidator = validator,
            sampleFiles = sampleStore,
            generatedAudio = generatedAudioStore,
            debugLog = { message -> if (BuildConfig.DEBUG) android.util.Log.d("Voicebox", message) },
        )
        FakeApplicationContainer(
            settingsRepository = settingsRepository,
            voiceSampleRecorder = AndroidVoiceSampleRecorder(this, sampleStore, applicationScope),
            temporaryFileCleaner = CompositeTemporaryFileCleaner(sampleStore, generatedAudioStore),
            voiceCloningRepository = voicebox,
            audioSynthesisRepository = voicebox,
        )
    }
}
