package me.sorihiko.dangomusicgtnh.music.request

import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import java.net.URI
import java.text.Normalizer
import java.util.Locale

class SongRequestQuery private constructor(
    val searchText: String,
    val trackId: Long?,
    private val explicitTitle: String?,
    private val explicitArtist: String?,
) {
    fun matches(track: NeteaseTrack): Boolean {
        trackId?.let { return it == track.id }
        val title = normalize(track.name)
        val artists = track.artists.map(::normalize).filter(String::isNotBlank)
        if (title.isEmpty()) return false
        if (explicitTitle != null && explicitArtist == null) return title == explicitTitle
        val credits = artists + artists.joinToString(" ") + artists.joinToString(" / ")
        return if (explicitTitle != null) title == explicitTitle && explicitArtist in credits
        else credits.any { normalize(searchText) == "$title $it" }
    }

    companion object {
        private val spaces = Regex("[\\s\\p{Z}]+")
        private val request = Regex("^/点歌[\\s\\p{Z}]+(.+)$")
        private val shortRequest = Regex("^/song[\\s\\p{Z}]+(.+)$", RegexOption.IGNORE_CASE)
        private val namedId = Regex("^id:([0-9]+)$", RegexOption.IGNORE_CASE)
        private val idParameter = Regex("(?:^|&)id=([0-9]+)(?:&|$)")
        private val songPaths = setOf("/song", "/m/song")
        private val songHosts = setOf("music.163.com", "y.music.163.com")
        private val quoted = Regex("^\"([^\"]+)\"\\s+(?:\"([^\"]+)\"|([^\"]+))$")

        private fun normalize(text: String): String =
            spaces.replace(Normalizer.normalize(text, Normalizer.Form.NFKC).trim(), " ").lowercase(Locale.ROOT)

        @JvmStatic fun parse(content: String): SongRequestQuery? {
            if (content.length > 192) return null
            val input = Normalizer.normalize(content.trim(), Normalizer.Form.NFKC)
            shortRequest.matchEntire(input)?.let { return parseShort(it.groupValues[1].trim()) }
            val phrase = request.matchEntire(input)?.groupValues?.get(1)?.trim() ?: return null
            if ('"' !in phrase) {
                if (' ' !in normalize(phrase)) return null
                return SongRequestQuery(phrase, null, null, null)
            }
            val parts = quoted.matchEntire(phrase)?.groupValues ?: return null
            val title = parts[1].trim()
            val artist = parts[2].ifBlank { parts[3] }.trim()
            if (title.isEmpty() || artist.isEmpty()) return null
            return SongRequestQuery("$title $artist", null, normalize(title), normalize(artist))
        }

        private fun parseShort(phrase: String): SongRequestQuery? {
            if (phrase.isEmpty()) return null
            val id = if (phrase.startsWith("id:", ignoreCase = true)) {
                namedId.matchEntire(phrase)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            } else if (phrase.startsWith("https://", true) || phrase.startsWith("http://", true)) {
                linkId(phrase) ?: return null
            } else null
            if (id != null) return SongRequestQuery(id.toString(), id, null, null)
            val parts = phrase.split(" - ", limit = 2)
            val title = parts[0].trim()
            val artist = parts.getOrNull(1)?.trim()
            if (title.isEmpty() || artist?.isEmpty() == true) return null
            return SongRequestQuery(listOfNotNull(title, artist).joinToString(" "), null,
                normalize(title), artist?.let(::normalize))
        }

        private fun linkId(text: String): Long? {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (uri.host?.lowercase(Locale.ROOT) !in songHosts || uri.rawUserInfo != null ||
                uri.port != -1 || uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
            val fragmentRoute = uri.rawFragment.orEmpty()
            val query = when {
                uri.rawPath in songPaths -> uri.rawQuery.orEmpty()
                uri.rawPath in setOf("", "/") && fragmentRoute.substringBefore('?') in songPaths ->
                    fragmentRoute.substringAfter('?', "")
                else -> return null
            }
            return idParameter.find(query)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0L }
        }
    }
}
