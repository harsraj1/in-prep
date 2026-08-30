package com.harsraj.inprep.feature.session.data.speech

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechRecognitionResultPolicyTest {
    @Test fun `best transcript trims and chooses first valid ranked result`() {
        assertEquals(
            "How do you handle production incidents?",
            SpeechRecognitionResultPolicy.bestTranscript(
                listOf(" ", "How do you handle production incidents?", "lower ranked result"),
            ),
        )
    }

    @Test fun `empty punctuation-only and obviously short results are rejected`() {
        assertNull(SpeechRecognitionResultPolicy.bestTranscript(listOf("", "?", "a")))
    }

    @Test fun `recognizer errors map to actionable categories`() {
        assertEquals(
            "No clear interview question was recognized",
            SpeechRecognitionResultPolicy.errorMessage(SpeechRecognizer.ERROR_NO_MATCH),
        )
        assertEquals(
            "No speech was heard. Try again when ready",
            SpeechRecognitionResultPolicy.errorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
        assertEquals(
            "Speech recognition is busy. Wait a moment and retry",
            SpeechRecognitionResultPolicy.errorMessage(SpeechRecognizer.ERROR_RECOGNIZER_BUSY),
        )
        assertEquals(
            "Speech recognition could not reach its network service",
            SpeechRecognitionResultPolicy.errorMessage(SpeechRecognizer.ERROR_NETWORK),
        )
        assertEquals(
            "Microphone permission is required to listen",
            SpeechRecognitionResultPolicy.errorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
    }
}
