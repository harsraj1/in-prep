package com.harsraj.inprep.feature.settings.domain

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceboxBaseUrlValidatorTest {
    @Test
    fun `debug accepts private LAN HTTP URL`() {
        val result = validator(debug = true).validate("http://192.168.1.50:17493/")

        assertEquals("http://192.168.1.50:17493/", result.getOrThrow())
    }

    @Test
    fun `debug rejects public HTTP host`() {
        val result = validator(debug = true).validate("http://203.0.113.10:17493/")

        assertTrue(result.isFailure)
    }

    @Test
    fun `release rejects private cleartext URL`() {
        val result = validator(debug = false).validate("http://192.168.1.50:17493/")

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed and non-root URLs are rejected`() {
        assertTrue(validator(debug = true).validate("not a URL").isFailure)
        assertTrue(validator(debug = true).validate("https://voicebox.example/api").isFailure)
    }

    @Test
    fun `HTTPS does not require a private address`() {
        val result = validator(debug = false).validate("https://voicebox.example/")

        assertEquals("https://voicebox.example/", result.getOrThrow())
    }

    private fun validator(debug: Boolean) = VoiceboxBaseUrlValidator(
        isDebugBuild = debug,
        resolveHost = { host -> listOf(InetAddress.getByName(host)) },
    )
}
