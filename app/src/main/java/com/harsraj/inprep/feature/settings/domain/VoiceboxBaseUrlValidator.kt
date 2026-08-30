package com.harsraj.inprep.feature.settings.domain

import java.net.InetAddress
import java.net.URI

class VoiceboxBaseUrlValidator(
    private val isDebugBuild: Boolean,
    private val resolveHost: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
) {
    fun validate(value: String): Result<String> = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Voicebox URL must use http or https"
        }
        require(!uri.host.isNullOrBlank()) { "Voicebox URL must include a host" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Voicebox URL must not contain credentials, a query, or a fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Voicebox URL must not include a path" }

        if (uri.scheme.equals("http", ignoreCase = true)) {
            require(isDebugBuild) { "HTTP Voicebox URLs are allowed only in debug builds" }
            val addresses = resolveHost(uri.host)
            require(addresses.isNotEmpty() && addresses.all { it.isPrivateDevelopmentAddress() }) {
                "HTTP Voicebox hosts must resolve only to loopback, link-local, or private LAN addresses"
            }
        }

        URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/", null, null).toASCIIString()
    }

    private fun InetAddress.isPrivateDevelopmentAddress(): Boolean =
        isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isUniqueLocalIpv6()

    private fun InetAddress.isUniqueLocalIpv6(): Boolean {
        val bytes = address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
}
