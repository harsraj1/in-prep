package com.harsraj.inprep.feature.session.data.recording

import android.content.Context
import com.harsraj.inprep.feature.session.domain.TemporaryFileCleaner
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileId
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference
import java.io.File
import java.util.UUID

class PrivateVoiceSampleStore(context: Context) : TemporaryFileCleaner {
    private val directory = File(context.cacheDir, DIRECTORY_NAME)

    fun create(): SampleFile {
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create private sample cache" }
        val id = TemporaryFileId(UUID.randomUUID().toString())
        return SampleFile(TemporaryFileReference(id), fileFor(id))
    }

    override suspend fun delete(file: TemporaryFileReference) {
        fileFor(file.id).delete()
    }

    override suspend fun deleteAll() {
        directory.listFiles()?.forEach(File::delete)
    }

    fun deleteNow(file: TemporaryFileReference) {
        fileFor(file.id).delete()
    }

    fun deleteExpired(nowEpochMillis: Long, maxAgeMillis: Long = MAX_AGE_MILLIS) {
        directory.listFiles()
            ?.filter { nowEpochMillis - it.lastModified() > maxAgeMillis }
            ?.forEach(File::delete)
    }

    private fun fileFor(id: TemporaryFileId): File {
        val file = File(directory, "${id.value}.m4a")
        check(file.canonicalFile.parentFile == directory.canonicalFile) { "Invalid temporary file reference" }
        return file
    }

    data class SampleFile(
        val reference: TemporaryFileReference,
        val file: File,
    )

    private companion object {
        const val DIRECTORY_NAME = "voice-samples"
        const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
