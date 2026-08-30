package com.harsraj.inprep

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import com.harsraj.inprep.di.FakeApplicationContainer
import com.harsraj.inprep.feature.settings.data.DataStoreSettingsRepository
import com.harsraj.inprep.feature.settings.domain.VoiceboxBaseUrlValidator

private val Application.settingsDataStore by preferencesDataStore(name = "non_secret_settings")

class InPrepApplication : Application() {
    val container: FakeApplicationContainer by lazy {
        FakeApplicationContainer(
            settingsRepository = DataStoreSettingsRepository(
                dataStore = settingsDataStore,
                voiceboxBaseUrlValidator = VoiceboxBaseUrlValidator(BuildConfig.DEBUG),
            ),
        )
    }
}
