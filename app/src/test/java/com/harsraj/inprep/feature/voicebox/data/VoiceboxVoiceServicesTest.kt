package com.harsraj.inprep.feature.voicebox.data

import com.harsraj.inprep.feature.session.data.recording.VoiceSampleFileProvider
import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileId
import com.harsraj.inprep.feature.session.domain.model.TemporaryFileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileId
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata
import com.harsraj.inprep.feature.settings.domain.VoiceboxBaseUrlValidator
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceboxVoiceServicesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var sampleFile: File
    private lateinit var outputDirectory: File

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        sampleFile = temporaryFolder.newFile("synthetic-sample.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        outputDirectory = temporaryFolder.newFolder("generated")
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `clone uses verified chatterbox turbo profile and multipart contract`() = runBlocking {
        server.enqueue(json("""{"id":"profile-1"}"""))
        server.enqueue(json("""{"id":"sample-1"}"""))

        val profile = service().createVoiceProfile(sample())

        assertEquals("profile-1", profile.id.value)
        val profileRequest = server.takeRequest()
        assertEquals("/profiles", profileRequest.path)
        val profileJson = JSONObject(profileRequest.body.readUtf8())
        assertEquals("chatterbox_turbo", profileJson.getString("default_engine"))
        assertEquals("cloned", profileJson.getString("voice_type"))
        val upload = server.takeRequest()
        assertEquals("/profiles/profile-1/samples", upload.path)
        val multipart = upload.body.readUtf8()
        assertTrue(multipart.contains("name=\"file\"; filename=\"voice-sample.m4a\""))
        assertTrue(multipart.contains("Content-Type: audio/mp4"))
        assertTrue(multipart.contains("name=\"reference_text\""))
        assertTrue(multipart.contains(sample().referenceText))
    }

    @Test fun `synthesis polls status and streams wav into bounded private target`() = runBlocking {
        server.enqueue(json("""{"id":"generation-1"}"""))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"generation-1\",\"status\":\"completed\"}\n\n"),
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody("RIFF-safe-test"))

        val audio = service().synthesize(GeneratedAnswer("A concise answer"), profile())

        assertEquals("generation-1", audio.id)
        assertEquals("RIFF-safe-test", File(outputDirectory, "output.wav").readText())
        val generationJson = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("chatterbox_turbo", generationJson.getString("engine"))
        assertEquals("/generate/generation-1/status", server.takeRequest().path)
        assertEquals("/audio/generation-1", server.takeRequest().path)
    }

    @Test fun `malformed profile response is rejected`() {
        server.enqueue(json("not-json"))
        val error = assertThrows(VoiceboxException::class.java) {
            runBlocking { service().createVoiceProfile(sample()) }
        }
        assertEquals(VoiceboxFailure.MALFORMED_RESPONSE, error.failure)
    }

    @Test fun `unsupported generated media is rejected and partial file removed`() {
        enqueueCompletedGeneration(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("bad"))
        val error = assertThrows(VoiceboxException::class.java) {
            runBlocking { service().synthesize(GeneratedAnswer("answer"), profile()) }
        }
        assertEquals(VoiceboxFailure.UNSUPPORTED_MEDIA, error.failure)
        assertFalse(File(outputDirectory, "output.wav").exists())
    }

    @Test fun `oversized generated response is rejected and partial file removed`() {
        enqueueCompletedGeneration(MockResponse().setHeader("Content-Type", "audio/wav").setBody("12345"))
        val error = assertThrows(VoiceboxException::class.java) {
            runBlocking { service(maxAudioBytes = 4).synthesize(GeneratedAnswer("answer"), profile()) }
        }
        assertEquals(VoiceboxFailure.RESPONSE_TOO_LARGE, error.failure)
        assertFalse(File(outputDirectory, "output.wav").exists())
    }

    @Test fun `server errors are structured and non-idempotent requests are not retried`() {
        server.enqueue(json("""{"detail":"model unavailable"}""").setResponseCode(500))
        val error = assertThrows(VoiceboxException::class.java) {
            runBlocking { service().synthesize(GeneratedAnswer("answer"), profile()) }
        }
        assertEquals(VoiceboxFailure.SERVER_FAILURE, error.failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `status timeout maps to firewall or network isolation diagnostic`() {
        server.enqueue(json("""{"id":"generation-1"}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val error = assertThrows(VoiceboxException::class.java) {
            runBlocking { service(readTimeoutMillis = 30).synthesize(GeneratedAnswer("answer"), profile()) }
        }
        assertEquals(VoiceboxFailure.NETWORK_ISOLATION_OR_FIREWALL, error.failure)
    }

    @Test fun `cancelling synthesis cancels status request and asks server to cancel generation`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/generate" -> json("""{"id":"generation-1"}""")
                request.path?.endsWith("/status") == true -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                request.path?.endsWith("/cancel") == true -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val job = async(Dispatchers.IO) {
            service(readTimeoutMillis = 5_000).synthesize(GeneratedAnswer("answer"), profile())
        }
        assertEquals("/generate", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("/generate/generation-1/status", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        job.cancelAndJoin()
        val cancel = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/generate/generation-1/cancel", cancel?.path)
    }

    private fun enqueueCompletedGeneration(audio: MockResponse) {
        server.enqueue(json("""{"id":"generation-1"}"""))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"status\":\"completed\"}\n\n"),
        )
        server.enqueue(audio)
    }

    private fun service(
        maxAudioBytes: Long = 1024,
        readTimeoutMillis: Long = 1_000,
    ): VoiceboxVoiceServices {
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        return VoiceboxVoiceServices(
            client = client,
            baseUrlProvider = { server.url("/").toString() },
            baseUrlValidator = VoiceboxBaseUrlValidator(true) {
                listOf(InetAddress.getByName("127.0.0.1"))
            },
            sampleFiles = VoiceSampleFileProvider { sampleFile },
            generatedAudio = GeneratedAudioTargetStore {
                GeneratedAudioTarget(tempRef("audio"), File(outputDirectory, "output.wav"))
            },
            maxAudioBytes = maxAudioBytes,
            retryDelayMillis = 0,
        )
    }

    private fun sample() = VoiceSampleMetadata(
        id = "sample",
        temporaryFile = tempRef("sample"),
        durationMillis = 5_000,
        createdAtEpochMillis = 1,
    )

    private fun profile() = VoiceProfileReference(VoiceProfileId("profile-1"), 1)
    private fun tempRef(id: String) = TemporaryFileReference(TemporaryFileId(id))
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
