package com.harsraj.inprep.feature.settings.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileId
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.settings.domain.AppSettings
import com.harsraj.inprep.feature.settings.domain.VoiceboxBaseUrlValidator
import java.io.File
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreSettingsRepositoryTest {
    @Test
    fun defaultsAndSavedNonSecretSettingsSurviveRepositoryRecreation() = withRepository { fixture ->
        val repository = fixture.repository
        assertEquals(AppSettings(), repository.loadSettings())

        val context = InterviewContext("Example Company", "Android Engineer")
        val profile = VoiceProfileReference(VoiceProfileId("profile-reference"), 42L)
        repository.saveSessionPreferences(SessionPreferences(context, profile))
        repository.saveVoiceboxBaseUrl("http://192.168.1.50:17493")

        val restored = DataStoreSettingsRepository(fixture.dataStore, validator()).loadSettings()
        assertEquals(context, restored.interviewContext)
        assertEquals(profile, restored.voiceProfile)
        assertEquals("http://192.168.1.50:17493/", restored.voiceboxBaseUrl)
    }

    @Test
    fun resetRemovesEveryPersistedValue() = withRepository { fixture ->
        val repository = fixture.repository
        repository.saveInterviewContext(InterviewContext("Example", "Engineer"))
        repository.saveVoiceboxBaseUrl("http://10.0.0.2:17493/")

        repository.reset()

        val settings = repository.loadSettings()
        assertNull(settings.interviewContext)
        assertNull(settings.voiceProfile)
        assertNull(settings.voiceboxBaseUrl)
    }

    private fun withRepository(test: suspend (TestFixture) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            test(
                TestFixture(
                    dataStore = dataStore,
                    repository = DataStoreSettingsRepository(dataStore, validator()),
                ),
            )
        } finally {
            scope.cancel()
            file.delete()
            File("${file.path}.tmp").delete()
        }
    }

    private fun validator() = VoiceboxBaseUrlValidator(
        isDebugBuild = true,
        resolveHost = { host -> listOf(InetAddress.getByName(host)) },
    )

    private data class TestFixture(
        val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        val repository: DataStoreSettingsRepository,
    )
}
