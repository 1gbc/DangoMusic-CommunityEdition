package dangomusicce.music.request

import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

internal object NeteaseSongLink {
    private val songHosts = setOf("music.163.com", "y.music.163.com")
    private val songPaths = setOf("/song", "/m/song")
    private val idParameter = Regex("(?:^|&)id=([0-9]+)(?=&|$)")
    private val shortPath = Regex("^/[A-Za-z0-9_-]{1,64}/?$")
    private val protocols = setOf("http", "https")
    private val redirects = setOf(301, 302, 303, 307, 308)

    private fun isWebLink(uri: URI): Boolean =
        uri.rawUserInfo == null && uri.port == -1 && uri.scheme?.lowercase(Locale.ROOT) in protocols

    fun isShortLink(uri: URI): Boolean =
        isWebLink(uri) && uri.host.equals("163cn.tv", true) && shortPath.matches(uri.rawPath.orEmpty())

    fun trackId(uri: URI): Long? {
        if (!isWebLink(uri) || uri.host?.lowercase(Locale.ROOT) !in songHosts) return null
        val fragmentRoute = uri.rawFragment.orEmpty()
        val query = when {
            uri.rawPath in songPaths -> uri.rawQuery.orEmpty()
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && fragmentRoute.substringBefore('?') in songPaths ->
                fragmentRoute.substringAfter('?', "")
            else -> return null
        }
        // A URL supplying two different song IDs doesn't identify an unambiguous version.
        return idParameter.findAll(query).map { it.groupValues[1] }
            .distinct().singleOrNull()?.toLongOrNull()?.takeIf { it > 0L }
    }

    fun resolve(link: URI, redirect: (URI) -> String? = ::readRedirect): Long? {
        var next = link
        repeat(3) {
            if (!isShortLink(next)) return null
            // Only contact the official shortener. Ignore tracking fields from chat and never forward credentials.
            val request = URI("https", "163cn.tv", next.rawPath, null)
            val location = redirect(request) ?: return null
            next = runCatching { request.resolve(location) }.getOrNull() ?: return null
            trackId(next)?.let { return it }
        }
        return null
    }

    private fun readRedirect(uri: URI): String? {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (connection.responseCode in redirects) connection.getHeaderField("Location") else null
        } finally {
            connection.disconnect()
        }
    }
}
