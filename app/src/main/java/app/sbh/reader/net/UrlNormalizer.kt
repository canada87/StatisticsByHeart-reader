package app.sbh.reader.net

import java.net.URI

object UrlNormalizer {
    /**
     * Turns what the user typed into the writer origin used as base URL (no trailing slash, no `/api/v1` suffix).
     * Returns null when it cannot be a valid http(s) URL. A missing scheme means `https://`.
     */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = try {
            URI(s)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        var path = uri.path.orEmpty().trimEnd('/')
        path = path.removeSuffix("/api/v1")
        return "$scheme://$host$port$path"
    }

    fun isCleartext(baseUrl: String) = baseUrl.startsWith("http://", ignoreCase = true)
}
