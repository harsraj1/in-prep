package com.harsraj.inprep.feature.voicebox.data

import android.content.Context
import com.harsraj.inprep.feature.session.domain.TemporaryFileCleaner
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileId
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference
import java.io.File
import java.util.UUID

class PrivateGeneratedAudioStore(context: Context) : TemporaryFileCleaner, GeneratedAudioTargetStore {
    private val directory = File(context.cacheDir, "generated-audio")

    override fun createWavTarget(): GeneratedAudioTarget {
        check(directory.mkdirs() || directory.isDirectory)
        val reference = TemporaryFileReference(TemporaryFileId(UUID.randomUUID().toString()))
        return GeneratedAudioTarget(reference, fileFor(reference))
    }

    override suspend fun delete(file: TemporaryFileReference) {
        fileFor(file).delete()
    }

    override suspend fun deleteAll() {
        directory.listFiles()?.forEach(File::delete)
    }

    fun deleteExpired(nowMillis: Long, maxAgeMillis: Long = 24 * 60 * 60 * 1_000L) {
        directory.listFiles()?.filter { nowMillis - it.lastModified() > maxAgeMillis }?.forEach(File::delete)
    }

    private fun fileFor(reference: TemporaryFileReference): File {
        val file = File(directory, "${reference.id.value}.wav")
        check(file.canonicalFile.parentFile == directory.canonicalFile)
        return file
    }

}

fun interface GeneratedAudioTargetStore {
    fun createWavTarget(): GeneratedAudioTarget
}

data class GeneratedAudioTarget(
    val reference: TemporaryFileReference,
    val file: File,
)
