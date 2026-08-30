package com.harsraj.inprep.feature.session.data.recording

import com.harsraj.inprep.feature.session.domain.TemporaryFileCleaner
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference

class CompositeTemporaryFileCleaner(
    private vararg val cleaners: TemporaryFileCleaner,
) : TemporaryFileCleaner {
    override suspend fun delete(file: TemporaryFileReference) {
        cleaners.forEach { runCatching { it.delete(file) } }
    }

    override suspend fun deleteAll() {
        cleaners.forEach { runCatching { it.deleteAll() } }
    }
}
