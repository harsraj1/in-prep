package com.harsraj.inprep.feature.session.data.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.harsraj.inprep.feature.session.domain.VoiceSampleRecorder
import com.harsraj.inprep.feature.session.domain.VoiceSampleRecorderStatus
import com.harsraj.inprep.feature.session.domain.RecordingDurationPolicy
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidVoiceSampleRecorder(
    private val context: Context,
    private val store: PrivateVoiceSampleStore,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val durationPolicy: RecordingDurationPolicy = RecordingDurationPolicy(),
) : VoiceSampleRecorder {
    private val mutableStatus = MutableStateFlow<VoiceSampleRecorderStatus>(VoiceSampleRecorderStatus.Idle)
    override val status: StateFlow<VoiceSampleRecorderStatus> = mutableStatus.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var activeFile: PrivateVoiceSampleStore.SampleFile? = null
    private var startedAtMillis = 0L
    private var elapsedJob: Job? = null
    private var capturedSample: VoiceSampleMetadata? = null

    @Synchronized
    override fun start() {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission is required to record a voice sample")
        }
        check(recorder == null) { "A voice sample is already recording" }
        capturedSample = null
        val sampleFile = store.create()
        val mediaRecorder = createMediaRecorder()
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioChannels(1)
            mediaRecorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
            mediaRecorder.setAudioEncodingBitRate(BIT_RATE_BPS)
            mediaRecorder.setMaxDuration(durationPolicy.maximumMillis.toInt())
            mediaRecorder.setOutputFile(sampleFile.file.absolutePath)
            mediaRecorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishAtMaximumDuration()
            }
            mediaRecorder.setOnErrorListener { _, _, _ -> fail("The microphone recorder stopped unexpectedly") }
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            activeFile = sampleFile
            startedAtMillis = elapsedRealtime()
            mutableStatus.value = VoiceSampleRecorderStatus.Recording(0)
            startElapsedUpdates()
        } catch (error: Exception) {
            runCatching { mediaRecorder.release() }
            store.deleteNow(sampleFile.reference)
            mutableStatus.value = VoiceSampleRecorderStatus.Failed(
                error.message ?: "Unable to start voice recording",
            )
            throw error
        }
    }

    @Synchronized
    override fun finish(): VoiceSampleMetadata {
        capturedSample?.let { return it }
        val elapsed = elapsedRealtime() - startedAtMillis
        if (elapsed < durationPolicy.minimumMillis) {
            cancelInternal()
            throw VoiceSampleTooShortException(durationPolicy.minimumMillis)
        }
        return finishInternal(durationPolicy.requireValid(elapsed))
    }

    @Synchronized
    override fun cancel() {
        cancelInternal()
        mutableStatus.value = VoiceSampleRecorderStatus.Idle
    }

    @Synchronized
    private fun finishAtMaximumDuration() {
        if (recorder == null) return
        runCatching { finishInternal(durationPolicy.maximumMillis) }
            .onFailure { fail(it.message ?: "Unable to finish voice recording") }
    }

    @Synchronized
    private fun finishInternal(durationMillis: Long): VoiceSampleMetadata {
        val mediaRecorder = checkNotNull(recorder) { "Voice recording is not active" }
        val sampleFile = checkNotNull(activeFile)
        elapsedJob?.cancel()
        try {
            mediaRecorder.stop()
        } catch (error: RuntimeException) {
            releaseRecorder()
            store.deleteNow(sampleFile.reference)
            throw error
        }
        releaseRecorder()
        val sample = VoiceSampleMetadata(
            id = UUID.randomUUID().toString(),
            temporaryFile = sampleFile.reference,
            durationMillis = durationMillis,
            createdAtEpochMillis = currentTimeMillis(),
        )
        capturedSample = sample
        mutableStatus.value = VoiceSampleRecorderStatus.Captured(sample)
        return sample
    }

    private fun startElapsedUpdates() {
        elapsedJob?.cancel()
        elapsedJob = scope.launch {
            while (isActive) {
                delay(ELAPSED_UPDATE_MILLIS)
                val elapsed = (elapsedRealtime() - startedAtMillis)
                    .coerceAtMost(durationPolicy.maximumMillis)
                if (recorder != null) mutableStatus.value = VoiceSampleRecorderStatus.Recording(elapsed)
            }
        }
    }

    @Synchronized
    private fun fail(message: String) {
        val file = activeFile
        releaseRecorder()
        file?.let { store.deleteNow(it.reference) }
        mutableStatus.value = VoiceSampleRecorderStatus.Failed(message)
    }

    private fun cancelInternal() {
        val file = activeFile
        runCatching { recorder?.stop() }
        releaseRecorder()
        file?.let { store.deleteNow(it.reference) }
        capturedSample = null
    }

    private fun releaseRecorder() {
        elapsedJob?.cancel()
        elapsedJob = null
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        activeFile = null
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    companion object {
        private const val ELAPSED_UPDATE_MILLIS = 200L
        private const val SAMPLE_RATE_HZ = 44_100
        private const val BIT_RATE_BPS = 128_000
    }
}

class VoiceSampleTooShortException(minimumDurationMillis: Long) :
    IllegalStateException("Record at least ${minimumDurationMillis / 1_000} seconds of clear speech")
