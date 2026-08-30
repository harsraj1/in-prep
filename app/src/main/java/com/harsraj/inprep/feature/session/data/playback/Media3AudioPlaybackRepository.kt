package com.harsraj.inprep.feature.session.data.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.harsraj.inprep.feature.session.domain.AudioPlaybackRepository
import com.harsraj.inprep.feature.session.domain.AudioPlaybackStatus
import com.harsraj.inprep.feature.session.domain.model.GeneratedAudioReference
import com.harsraj.inprep.feature.voicebox.data.GeneratedAudioFileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Media3AudioPlaybackRepository(
    context: Context,
    private val audioFiles: GeneratedAudioFileProvider,
    playerFactory: () -> ExoPlayer = {
        ExoPlayer.Builder(context.applicationContext).build()
    },
) : AudioPlaybackRepository, Player.Listener {
    private val mutableStatus = MutableStateFlow<AudioPlaybackStatus>(AudioPlaybackStatus.Idle)
    override val status: StateFlow<AudioPlaybackStatus> = mutableStatus.asStateFlow()

    private val player = playerFactory().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        setHandleAudioBecomingNoisy(true)
        addListener(this@Media3AudioPlaybackRepository)
    }
    private var released = false

    override suspend fun play(audio: GeneratedAudioReference) {
        requireMainThread()
        check(!released) { "Audio player has been released" }
        check(
            mutableStatus.value == AudioPlaybackStatus.Idle ||
                mutableStatus.value == AudioPlaybackStatus.Completed ||
                mutableStatus.value is AudioPlaybackStatus.Failed,
        ) { "Audio is already active" }
        val file = audioFiles.requireFile(audio.temporaryFile)
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMimeType(MimeTypes.AUDIO_WAV)
            .build()
        player.setMediaItem(item, true)
        player.prepare()
        player.play()
        mutableStatus.value = AudioPlaybackStatus.Playing
    }

    override suspend fun pause() {
        requireMainThread()
        check(mutableStatus.value == AudioPlaybackStatus.Playing) { "Audio is not playing" }
        player.pause()
        mutableStatus.value = AudioPlaybackStatus.Paused
    }

    override suspend fun resume() {
        requireMainThread()
        check(mutableStatus.value == AudioPlaybackStatus.Paused) { "Audio is not paused" }
        player.play()
        mutableStatus.value = AudioPlaybackStatus.Playing
    }

    override suspend fun stop() {
        requireMainThread()
        if (released) return
        player.stop()
        player.clearMediaItems()
        mutableStatus.value = AudioPlaybackStatus.Idle
    }

    override fun release() {
        requireMainThread()
        if (released) return
        player.removeListener(this)
        player.stop()
        player.clearMediaItems()
        player.release()
        released = true
        mutableStatus.value = AudioPlaybackStatus.Idle
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && mutableStatus.value != AudioPlaybackStatus.Idle) {
            player.stop()
            player.clearMediaItems()
            mutableStatus.value = AudioPlaybackStatus.Completed
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && mutableStatus.value == AudioPlaybackStatus.Playing &&
            (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        ) {
            mutableStatus.value = AudioPlaybackStatus.Paused
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        player.stop()
        player.clearMediaItems()
        mutableStatus.value = AudioPlaybackStatus.Failed(
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                -> "Generated audio is corrupt or unsupported"
                else -> "Generated audio could not be played"
            },
        )
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "ExoPlayer must be owned on the main thread" }
    }
}
