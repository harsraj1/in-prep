package com.harsraj.inprep.feature.settings.domain

import com.harsraj.inprep.feature.session.domain.model.InterviewContext
import com.harsraj.inprep.feature.session.domain.model.SessionPreferences
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference

data class AppSettings(
    val interviewContext: InterviewContext? = null,
    val voiceboxBaseUrl: String? = null,
    val voiceProfile: VoiceProfileReference? = null,
) {
    val reusableSessionPreferences: SessionPreferences?
        get() = interviewContext?.let { context ->
            voiceProfile?.let { profile -> SessionPreferences(context, profile) }
        }
}
