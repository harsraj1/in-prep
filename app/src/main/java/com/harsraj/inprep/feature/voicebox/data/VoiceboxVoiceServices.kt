package com.harsraj.inprep.feature.voicebox.data

import com.harsraj.inprep.feature.session.data.recording.VoiceSampleFileProvider
import com.harsraj.inprep.feature.session.domain.AudioSynthesisRepository
import com.harsraj.inprep.feature.session.domain.VoiceCloningRepository
import com.harsraj.inprep.feature.session.domain.model.GeneratedAnswer
import com.harsraj.inprep.feature.session.domain.model.GeneratedAudioReference
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileId
import com.harsraj.inprep.feature.session.domain.model.VoiceProfileReference
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleFormat
import com.harsraj.inprep.feature.session.domain.model.VoiceSampleMetadata
import com.harsraj.inprep.feature.settings.domain.VoiceboxBaseUrlValidator
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

class VoiceboxVoiceServices(
    private val client: OkHttpClient,
    private val baseUrlProvider: suspend () -> String,
    private val baseUrlValidator: VoiceboxBaseUrlValidator,
    private val sampleFiles: VoiceSampleFileProvider,
    private val generatedAudio: GeneratedAudioTargetStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxAudioBytes: Long = 25L * 1024 * 1024,
    private val retryDelayMillis: Long = 250,
    private val debugLog: (String) -> Unit = {},
) : VoiceCloningRepository, AudioSynthesisRepository {
    override suspend fun createVoiceProfile(sample: VoiceSampleMetadata): VoiceProfileReference {
        require(sample.format == VoiceSampleFormat.M4A_AAC) { "Voicebox requires a supported voice sample format" }
        val baseUrl = validatedBaseUrl()
        val profileId = createProfile(baseUrl)
        try {
            uploadSample(baseUrl, profileId, sample)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { bestEffortDeleteProfile(baseUrl, profileId) }
            throw failure
        }
        return VoiceProfileReference(VoiceProfileId(profileId), nowMillis())
    }

    override suspend fun synthesize(
        answer: GeneratedAnswer,
        voiceProfile: VoiceProfileReference,
    ): GeneratedAudioReference {
        val baseUrl = validatedBaseUrl()
        val generationId = startGeneration(baseUrl, voiceProfile.id.value, answer.text)
        var generationCompleted = false
        try {
            awaitGeneration(baseUrl, generationId)
            generationCompleted = true
            return downloadAudio(baseUrl, generationId)
        } catch (failure: Throwable) {
            if (!generationCompleted) {
                withContext(NonCancellable) { bestEffortCancel(baseUrl, generationId) }
            }
            throw failure
        }
    }

    private suspend fun createProfile(baseUrl: String): String {
        val body = JSONObject()
            .put("name", "In Prep ${UUID.randomUUID().toString().take(8)}")
            .put("language", "en")
            .put("voice_type", "cloned")
            .put("default_engine", ENGINE)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder().url("${baseUrl}profiles").post(body).build()
        return execute(request).use { response ->
            ensureSuccess(response)
            parseRequiredString(response.body?.string(), "id")
        }
    }

    private suspend fun uploadSample(baseUrl: String, profileId: String, sample: VoiceSampleMetadata) {
        val file = sampleFiles.requireFile(sample.temporaryFile)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("reference_text", sample.referenceText)
            .addFormDataPart("file", "voice-sample.m4a", file.asRequestBody(M4A))
            .build()
        execute(Request.Builder().url("${baseUrl}profiles/$profileId/samples").post(multipart).build()).use {
            ensureSuccess(it)
            parseRequiredString(it.body?.string(), "id")
        }
    }

    private suspend fun startGeneration(baseUrl: String, profileId: String, text: String): String {
        val body = JSONObject()
            .put("profile_id", profileId)
            .put("text", text)
            .put("language", "en")
            .put("engine", ENGINE)
            .put("personality", false)
            .put("normalize", true)
            .toString()
            .toRequestBody(JSON)
        return execute(Request.Builder().url("${baseUrl}generate").post(body).build()).use {
            ensureSuccess(it)
            parseRequiredString(it.body?.string(), "id")
        }
    }

    private suspend fun awaitGeneration(baseUrl: String, generationId: String) {
        val request = Request.Builder().url("${baseUrl}generate/$generationId/status").get().build()
        executeGetWithRetry(request).use { response ->
            ensureSuccess(response)
            requireMediaType(response, "text/event-stream")
            val source = response.body?.source() ?: malformed("Missing status stream")
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: malformed("Status stream ended early")
                if (!line.startsWith("data:")) continue
                val event = parseObject(line.removePrefix("data:").trim())
                when (event.optString("status")) {
                    "completed" -> return
                    "failed", "not_found" -> throw VoiceboxException(
                        VoiceboxFailure.SERVER_FAILURE,
                        event.optString("error").ifBlank { "Voicebox generation failed" },
                    )
                }
            }
        }
    }

    private suspend fun downloadAudio(baseUrl: String, generationId: String): GeneratedAudioReference {
        val target = generatedAudio.createWavTarget()
        try {
            executeGetWithRetry(Request.Builder().url("${baseUrl}audio/$generationId").get().build()).use { response ->
                ensureSuccess(response)
                requireMediaType(response, "audio/wav", "audio/x-wav")
                val body = response.body ?: malformed("Missing generated audio")
                if (body.contentLength() > maxAudioBytes) oversized()
                withContext(Dispatchers.IO) {
                    body.byteStream().use { input ->
                        target.file.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > maxAudioBytes) oversized()
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            return GeneratedAudioReference(generationId, target.reference)
        } catch (failure: Throwable) {
            target.file.delete()
            throw failure
        }
    }

    private suspend fun executeGetWithRetry(request: Request): Response {
        var lastFailure: Throwable? = null
        repeat(MAX_GET_ATTEMPTS) { attempt ->
            try {
                val response = execute(request)
                if (response.code !in TRANSIENT_HTTP || attempt == MAX_GET_ATTEMPTS - 1) return response
                response.close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: VoiceboxException) {
                lastFailure = failure
                if (attempt == MAX_GET_ATTEMPTS - 1) throw failure
            }
            if (retryDelayMillis > 0) delay(retryDelayMillis)
        }
        throw lastFailure ?: error("Retry loop completed without a result")
    }

    private suspend fun execute(request: Request): Response = try {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }.also { debugLog("Voicebox ${request.method} ${sanitizedPath(request.url.encodedPath)} -> ${it.code}") }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        throw mapNetworkFailure(failure)
    }

    private suspend fun bestEffortDeleteProfile(baseUrl: String, profileId: String) {
        runCatching { execute(Request.Builder().url("${baseUrl}profiles/$profileId").delete().build()).close() }
    }

    private suspend fun bestEffortCancel(baseUrl: String, generationId: String) {
        runCatching { execute(Request.Builder().url("${baseUrl}generate/$generationId/cancel").post(EMPTY_BODY).build()).close() }
    }

    private suspend fun validatedBaseUrl(): String {
        val raw = baseUrlProvider().trim()
        if (raw.isEmpty()) throw VoiceboxException(VoiceboxFailure.INVALID_URL, "Configure the Voicebox LAN address first")
        return baseUrlValidator.validate(raw).getOrElse {
            throw VoiceboxException(VoiceboxFailure.INVALID_URL, it.message ?: "Invalid Voicebox URL", it)
        }
    }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        val detail = runCatching { JSONObject(response.body?.string().orEmpty()).optString("detail") }.getOrNull()
        val message = detail?.takeIf(String::isNotBlank) ?: "Voicebox server returned HTTP ${response.code}"
        throw VoiceboxException(VoiceboxFailure.SERVER_FAILURE, message)
    }

    private fun requireMediaType(response: Response, vararg supported: String) {
        val actual = response.body?.contentType()?.let { "${it.type}/${it.subtype}" }
        if (actual !in supported) {
            throw VoiceboxException(VoiceboxFailure.UNSUPPORTED_MEDIA, "Voicebox returned unsupported media type")
        }
    }

    private fun parseRequiredString(body: String?, field: String): String = try {
        parseObject(body).getString(field).takeIf(String::isNotBlank) ?: malformed("Blank '$field' in response")
    } catch (error: JSONException) {
        throw VoiceboxException(VoiceboxFailure.MALFORMED_RESPONSE, "Voicebox returned malformed JSON", error)
    }

    private fun parseObject(body: String?): JSONObject = try {
        JSONObject(body ?: throw JSONException("missing body"))
    } catch (error: JSONException) {
        throw VoiceboxException(VoiceboxFailure.MALFORMED_RESPONSE, "Voicebox returned malformed JSON", error)
    }

    private fun malformed(message: String): Nothing = throw VoiceboxException(VoiceboxFailure.MALFORMED_RESPONSE, message)
    private fun oversized(): Nothing = throw VoiceboxException(VoiceboxFailure.RESPONSE_TOO_LARGE, "Generated audio exceeded the safe download limit")

    private fun mapNetworkFailure(error: IOException): VoiceboxException = when (error) {
        is UnknownHostException -> VoiceboxException(VoiceboxFailure.UNREACHABLE_HOST, "Voicebox host could not be resolved", error)
        is ConnectException -> VoiceboxException(VoiceboxFailure.UNREACHABLE_HOST, "Voicebox is not accepting connections", error)
        is NoRouteToHostException, is SocketTimeoutException -> VoiceboxException(
            VoiceboxFailure.NETWORK_ISOLATION_OR_FIREWALL,
            "Voicebox timed out; check trusted Wi-Fi, firewall, and client isolation",
            error,
        )
        else -> VoiceboxException(VoiceboxFailure.UNREACHABLE_HOST, "Voicebox network request failed", error)
    }

    private fun sanitizedPath(path: String): String = when {
        path.matches(Regex("/profiles/[^/]+/samples")) -> "/profiles/{id}/samples"
        path.matches(Regex("/profiles/[^/]+")) -> "/profiles/{id}"
        path.matches(Regex("/generate/[^/]+/status")) -> "/generate/{id}/status"
        path.matches(Regex("/generate/[^/]+/cancel")) -> "/generate/{id}/cancel"
        path.matches(Regex("/audio/[^/]+")) -> "/audio/{id}"
        else -> path
    }

    private companion object {
        const val ENGINE = "chatterbox_turbo"
        const val MAX_GET_ATTEMPTS = 3
        val TRANSIENT_HTTP = setOf(502, 503, 504)
        val JSON = "application/json; charset=utf-8".toMediaType()
        val M4A = "audio/mp4".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

enum class VoiceboxFailure {
    INVALID_URL,
    UNREACHABLE_HOST,
    NETWORK_ISOLATION_OR_FIREWALL,
    SERVER_FAILURE,
    MALFORMED_RESPONSE,
    UNSUPPORTED_MEDIA,
    RESPONSE_TOO_LARGE,
}

class VoiceboxException(
    val failure: VoiceboxFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
