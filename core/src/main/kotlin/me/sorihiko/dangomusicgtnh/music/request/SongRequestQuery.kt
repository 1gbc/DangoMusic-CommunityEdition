package me.sorihiko.dangomusicgtnh.music.request

import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import java.text.Normalizer
import java.util.Locale

class SongRequestQuery private constructor(
    val searchText: String,
    private val explicitTitle: String?,
    private val explicitArtist: String?,
) {
    fun matches(track: NeteaseTrack): Boolean {
        val title = normalize(track.name)
        val artists = track.artists.map(::normalize).filter(String::isNotBlank)
        if (title.isEmpty() || artists.isEmpty()) return false
        val credits = artists + artists.joinToString(" ") + artists.joinToString(" / ")
        return if (explicitTitle != null) title == explicitTitle && explicitArtist in credits
        else credits.any { normalize(searchText) == "$title $it" }
    }

    companion object {
        private val spaces = Regex("[\\s\\p{Z}]+")
        private val request = Regex("^/点歌[\\s\\p{Z}]+(.+)$")
        private val quoted = Regex("^\"([^\"]+)\"\\s+(?:\"([^\"]+)\"|([^\"]+))$")

        private fun normalize(text: String): String =
            spaces.replace(Normalizer.normalize(text, Normalizer.Form.NFKC).trim(), " ").lowercase(Locale.ROOT)

        @JvmStatic fun parse(content: String): SongRequestQuery? {
            if (content.length > 192) return null
            val input = Normalizer.normalize(content.trim(), Normalizer.Form.NFKC)
            val phrase = request.matchEntire(input)?.groupValues?.get(1)?.trim() ?: return null
            if ('"' !in phrase) {
                if (' ' !in normalize(phrase)) return null
                return SongRequestQuery(phrase, null, null)
            }
            val parts = quoted.matchEntire(phrase)?.groupValues ?: return null
            val title = parts[1].trim()
            val artist = parts[2].ifBlank { parts[3] }.trim()
            if (title.isEmpty() || artist.isEmpty()) return null
            return SongRequestQuery("$title $artist", normalize(title), normalize(artist))
        }
    }
}
