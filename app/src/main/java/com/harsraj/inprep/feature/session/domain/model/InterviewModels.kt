package com.harsraj.inprep.feature.session.domain.model

@JvmInline
value class VoiceProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Voice profile ID must not be blank" }
    }
}

@JvmInline
value class TemporaryFileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Temporary file ID must not be blank" }
    }
}

data class InterviewContext(
    val company: String,
    val role: String,
) {
    init {
        require(company.isNotBlank()) { "Company must not be blank" }
        require(role.isNotBlank()) { "Role must not be blank" }
    }
}

data class TemporaryFileReference(
    val id: TemporaryFileId,
)

data class VoiceSampleMetadata(
    val id: String,
    val temporaryFile: TemporaryFileReference,
    val durationMillis: Long,
    val createdAtEpochMillis: Long,
    val format: VoiceSampleFormat = VoiceSampleFormat.M4A_AAC,
) {
    init {
        require(id.isNotBlank()) { "Voice sample ID must not be blank" }
        require(durationMillis > 0) { "Voice sample duration must be positive" }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
    }
}

enum class VoiceSampleFormat {
    /** Internal capture format only; Voicebox compatibility is not yet confirmed. */
    M4A_AAC,
}

data class VoiceProfileReference(
    val id: VoiceProfileId,
    val createdAtEpochMillis: Long,
) {
    init {
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
    }
}

data class InterviewQuestion(
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "Question must not be blank" }
    }
}

data class GeneratedAnswer(
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "Answer must not be blank" }
    }
}

data class GeneratedAudioReference(
    val id: String,
    val temporaryFile: TemporaryFileReference,
) {
    init {
        require(id.isNotBlank()) { "Generated audio ID must not be blank" }
    }
}

data class SessionPreferences(
    val context: InterviewContext,
    val voiceProfile: VoiceProfileReference,
)

data class PlaybackContent(
    val context: InterviewContext,
    val voiceProfile: VoiceProfileReference,
    val question: InterviewQuestion,
    val answer: GeneratedAnswer,
    val audio: GeneratedAudioReference,
)
