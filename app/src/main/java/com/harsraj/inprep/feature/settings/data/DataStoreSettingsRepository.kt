package com.harsraj.inprep.feature.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.harsraj.inprep.feature.session.domain.SettingsRepository
import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileId
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.settings.domain.AppSettings
import com.harsraj.inprep.feature.settings.domain.VoiceboxBaseUrlValidator
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val voiceboxBaseUrlValidator: VoiceboxBaseUrlValidator,
) : SettingsRepository {
    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toAppSettings)

    override suspend fun loadSettings(): AppSettings = settings.first()

    override suspend fun saveInterviewContext(context: InterviewContext) {
        dataStore.edit { values ->
            values[Keys.COMPANY] = context.company
            values[Keys.ROLE] = context.role
        }
    }

    override suspend fun saveSessionPreferences(preferences: SessionPreferences) {
        dataStore.edit { values ->
            values[Keys.COMPANY] = preferences.context.company
            values[Keys.ROLE] = preferences.context.role
            values[Keys.VOICE_PROFILE_ID] = preferences.voiceProfile.id.value
            values[Keys.VOICE_PROFILE_CREATED_AT] = preferences.voiceProfile.createdAtEpochMillis
        }
    }

    override suspend fun saveVoiceboxBaseUrl(baseUrl: String) {
        val validated = voiceboxBaseUrlValidator.validate(baseUrl).getOrThrow()
        dataStore.edit { it[Keys.VOICEBOX_BASE_URL] = validated }
    }

    override suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    private fun toAppSettings(values: Preferences): AppSettings {
        val context = values[Keys.COMPANY]?.takeIf(String::isNotBlank)?.let { company ->
            values[Keys.ROLE]?.takeIf(String::isNotBlank)?.let { role -> InterviewContext(company, role) }
        }
        val profile = values[Keys.VOICE_PROFILE_ID]?.takeIf(String::isNotBlank)?.let { id ->
            VoiceProfileReference(
                id = VoiceProfileId(id),
                createdAtEpochMillis = values[Keys.VOICE_PROFILE_CREATED_AT] ?: 0L,
            )
        }
        return AppSettings(
            interviewContext = context,
            voiceboxBaseUrl = values[Keys.VOICEBOX_BASE_URL],
            voiceProfile = profile,
        )
    }

    private object Keys {
        val COMPANY = stringPreferencesKey("company")
        val ROLE = stringPreferencesKey("role")
        val VOICEBOX_BASE_URL = stringPreferencesKey("voicebox_base_url")
        val VOICE_PROFILE_ID = stringPreferencesKey("voice_profile_id")
        val VOICE_PROFILE_CREATED_AT = longPreferencesKey("voice_profile_created_at")
    }
}
