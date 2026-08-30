package com.harsraj.inprep.feature.session.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.harsraj.inprep.feature.session.domain.SpeechRecognitionRepository
import com.harsraj.inprep.feature.session.domain.SpeechRecognitionStatus
import com.harsraj.inprep.feature.session.domain.model.InterviewQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechRecognitionRepository(
    private val context: Context,
    private val recognizerFactory: (RecognitionListener) -> SpeechRecognizer = { listener ->
        SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
    },
) : SpeechRecognitionRepository, RecognitionListener {
    private val mutableStatus = MutableStateFlow<SpeechRecognitionStatus>(SpeechRecognitionStatus.Idle)
    override val status: StateFlow<SpeechRecognitionStatus> = mutableStatus.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var result = CompletableDeferred<InterviewQuestion>()
    private var listening = false
    private var destroyed = false

    override fun startListening() {
        requireMainThread()
        check(!destroyed) { "Speech recognizer has been released" }
        check(!listening) { "Speech recognition is already listening" }
        check(SpeechRecognizer.isRecognitionAvailable(context)) {
            "Speech recognition is not available on this device"
        }
        if (result.isCompleted) result = CompletableDeferred()
        val activeRecognizer = recognizer ?: recognizerFactory(this).also { recognizer = it }
        listening = true
        mutableStatus.value = SpeechRecognitionStatus.Listening()
        try {
            activeRecognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                },
            )
        } catch (error: RuntimeException) {
            val failure = fail("Speech recognition could not start", error)
            throw failure
        }
    }

    override suspend fun stopAndTranscribe(): InterviewQuestion {
        requireMainThread()
        check(listening || result.isCompleted) { "Speech recognition is not listening" }
        if (listening) recognizer?.stopListening()
        return result.await()
    }

    override fun cancel() {
        requireMainThread()
        if (listening) recognizer?.cancel()
        listening = false
        if (!result.isCompleted) result.cancel(CancellationException("Speech recognition cancelled"))
        mutableStatus.value = SpeechRecognitionStatus.Idle
    }

    override fun destroy() {
        requireMainThread()
        if (destroyed) return
        cancel()
        recognizer?.destroy()
        recognizer = null
        destroyed = true
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!listening) return
        bestTranscript(partialResults)?.let {
            mutableStatus.value = SpeechRecognitionStatus.Listening(it)
        }
    }

    override fun onResults(results: Bundle?) {
        if (!listening) return
        val transcript = bestTranscript(results)
        if (transcript == null) {
            fail("No clear interview question was recognized")
            return
        }
        listening = false
        val question = InterviewQuestion(transcript)
        mutableStatus.value = SpeechRecognitionStatus.Final(question)
        result.complete(question)
    }

    override fun onError(error: Int) {
        if (!listening) return
        fail(errorMessage(error))
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun bestTranscript(bundle: Bundle?): String? =
        SpeechRecognitionResultPolicy.bestTranscript(
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
        )

    private fun fail(message: String, cause: Throwable? = null): SpeechRecognitionException {
        listening = false
        val exception = SpeechRecognitionException(message, cause)
        mutableStatus.value = SpeechRecognitionStatus.Failed(message)
        result.completeExceptionally(exception)
        return exception
    }

    private fun errorMessage(error: Int): String = SpeechRecognitionResultPolicy.errorMessage(error)

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechRecognizer must be owned on the main thread" }
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}

internal object SpeechRecognitionResultPolicy {
    fun bestTranscript(candidates: List<String>): String? = candidates.asSequence()
        .map(String::trim)
        .firstOrNull { it.length >= MIN_TRANSCRIPT_LENGTH && it.any(Char::isLetterOrDigit) }

    fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No clear interview question was recognized"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard. Try again when ready"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Wait a moment and retry"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition could not reach its network service"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required to listen"
        SpeechRecognizer.ERROR_AUDIO -> "The microphone audio could not be captured"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "The speech recognition service is unavailable"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was interrupted"
        else -> "Speech recognition failed"
    }

    private const val MIN_TRANSCRIPT_LENGTH = 3
}

class SpeechRecognitionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
