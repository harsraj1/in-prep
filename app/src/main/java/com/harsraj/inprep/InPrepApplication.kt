package com.harsraj.inprep

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import com.harsraj.inprep.di.FakeApplicationContainer
import com.harsraj.inprep.feature.settings.data.DataStoreSettingsRepository
import com.harsraj.inprep.feature.settings.domain.VoiceboxBaseUrlValidator
import com.harsraj.inprep.feature.session.data.recording.AndroidVoiceSampleRecorder
import com.harsraj.inprep.feature.session.data.recording.PrivateVoiceSampleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Application.settingsDataStore by preferencesDataStore(name = "non_secret_settings")

class InPrepApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val container: FakeApplicationContainer by lazy {
        val sampleStore = PrivateVoiceSampleStore(this).also {
            it.deleteExpired(System.currentTimeMillis())
        }
        FakeApplicationContainer(
            settingsRepository = DataStoreSettingsRepository(
                dataStore = settingsDataStore,
                voiceboxBaseUrlValidator = VoiceboxBaseUrlValidator(BuildConfig.DEBUG),
            ),
            voiceSampleRecorder = AndroidVoiceSampleRecorder(this, sampleStore, applicationScope),
            temporaryFileCleaner = sampleStore,
        )
    }
}
