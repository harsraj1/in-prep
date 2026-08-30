package com.harsraj.inprep.feature.session.data.playback

import androidx.test.platform.app.InstrumentationRegistry
import com.harsraj.inprep.feature.session.domain.AudioPlaybackStatus
import com.harsraj.inprep.feature.session.domain.model.GeneratedAudioReference
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileId
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference
import com.harsraj.inprep.feature.voicebox.data.GeneratedAudioFileProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3AudioPlaybackRepositoryTest {
    @Test fun syntheticSilentWavSupportsPlayPauseResumeStopAndCompletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "synthetic-playback-test.wav")
        file.writeBytes(silentWav(durationMillis = 750))
        var repository: Media3AudioPlaybackRepository? = null
        val reference = GeneratedAudioReference(
            "synthetic-test-audio",
            TemporaryFileReference(TemporaryFileId("synthetic-test-audio")),
        )
        try {
            instrumentation.runOnMainSync {
                val activeRepository = Media3AudioPlaybackRepository(
                    instrumentation.targetContext,
                    GeneratedAudioFileProvider { file },
                )
                repository = activeRepository
                runBlocking { activeRepository.play(reference) }
                assertEquals(AudioPlaybackStatus.Playing, activeRepository.status.value)
                runBlocking { activeRepository.pause() }
                assertEquals(AudioPlaybackStatus.Paused, activeRepository.status.value)
                runBlocking { activeRepository.resume() }
                assertEquals(AudioPlaybackStatus.Playing, activeRepository.status.value)
            }

            val activeRepository = requireNotNull(repository)
            val deadline = System.currentTimeMillis() + 5_000
            while (activeRepository.status.value != AudioPlaybackStatus.Completed &&
                System.currentTimeMillis() < deadline
            ) Thread.sleep(25)
            assertEquals(AudioPlaybackStatus.Completed, activeRepository.status.value)

            instrumentation.runOnMainSync {
                runBlocking { activeRepository.stop() }
                assertEquals(AudioPlaybackStatus.Idle, activeRepository.status.value)
            }
        } finally {
            repository?.let { activeRepository ->
                instrumentation.runOnMainSync { activeRepository.release() }
            }
            assertTrue(file.delete() || !file.exists())
        }
    }

    private fun silentWav(durationMillis: Int): ByteArray {
        val sampleRate = 8_000
        val dataSize = sampleRate * durationMillis / 1_000 * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(dataSize)
            put(ByteArray(dataSize))
        }.array()
    }
}
