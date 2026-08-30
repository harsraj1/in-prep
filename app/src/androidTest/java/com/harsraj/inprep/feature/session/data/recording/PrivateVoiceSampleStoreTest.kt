package com.harsraj.inprep.feature.session.data.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateVoiceSampleStoreTest {
    @Test
    fun sampleIsCreatedOnlyUnderPrivateCacheAndDeletedByReference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateVoiceSampleStore(context)
        val sample = store.create()
        sample.file.writeBytes(byteArrayOf(0x01))

        assertTrue(sample.file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        assertTrue(sample.file.exists())

        store.delete(sample.reference)

        assertFalse(sample.file.exists())
    }

    @Test
    fun expiredSamplesAreRemovedWithoutTouchingOtherCacheFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateVoiceSampleStore(context)
        val sample = store.create()
        sample.file.writeBytes(byteArrayOf(0x01))
        sample.file.setLastModified(1L)
        val unrelated = File(context.cacheDir, "unrelated-${System.nanoTime()}.tmp")
        unrelated.writeBytes(byteArrayOf(0x02))

        store.deleteExpired(nowEpochMillis = 48 * 60 * 60 * 1_000L)

        assertFalse(sample.file.exists())
        assertTrue(unrelated.exists())
        unrelated.delete()
    }
}
