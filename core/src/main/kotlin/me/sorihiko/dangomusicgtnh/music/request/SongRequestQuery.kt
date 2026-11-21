package me.sorihiko.dangomusicgtnh.music.request

import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import java.net.URI
import java.text.Normalizer
import java.util.Locale

class SongRequestQuery private constructor(
    val searchText: String,
    val trackId: Long? = null,
    val shortLink: URI? = null,
    private val title: String? = null,
    private val artist: String? = null,
) {
    fun matches(track: NeteaseTrack): Boolean {
        trackId?.let { return it == track.id }
        val trackTitle = normalize(track.name)
        if (trackTitle.isEmpty()) return false
        if (title != null) return trackTitle == normalize(title) && (artist == null || matchesArtist(track, artist))
        val prefix = "$trackTitle "
        val input = normalize(searchText)
        return input.startsWith(prefix) && matchesArtist(track, input.removePrefix(prefix))
    }

    internal fun titleSearch(results: List<NeteaseTrack>): String? {
        if (artist != null) return title
        if (title != null) return null
        val input = normalize(searchText)
        // For the older space-separated command, prefer a catalog title to splitting a multiword title.
        val catalogTitle = results.asSequence().map { it.name }.filter { it.isNotBlank() }
            .filter { input.startsWith(normalize(it) + " ") }.maxByOrNull { normalize(it).length }
        return catalogTitle ?: input.takeIf { it.count { char -> char == ' ' } == 1 }?.substringBefore(' ')
    }

    private fun matchesArtist(track: NeteaseTrack, requested: String): Boolean {
        if (requested.startsWith("id:", ignoreCase = true)) {
            return namedId.matchEntire(requested)?.groupValues?.get(1)?.toLongOrNull()?.toString() in track.artistIds
        }
        val wanted = artistKey(requested)
        if (wanted.isEmpty()) return false
        if (artistKey(track.artists.joinToString(" ")) == wanted) return true
        return track.artists.indices.any { index ->
            val name = artistKey(track.artists[index])
            val aliases = track.artistAliases.getOrNull(index).orEmpty().map(::artistKey).filter(String::isNotEmpty)
            // Some catalog display names combine an alias with the native name (e.g. G.E.M.邓紫棋).
            // Drop only a catalog-confirmed alias, not arbitrary substrings of an artist name.
            name == wanted || wanted in aliases || aliases.any { alias ->
                name.removePrefix(alias) == wanted || name.removeSuffix(alias) == wanted
            }
        }
    }

    companion object {
        private val spaces = Regex("[\\s\\p{Z}]+")
        private val artistSeparators = Regex("[\\s\\p{Z}\\p{P}]+")
        private val command = Regex("^/(song|点歌)[\\s\\p{Z}]+(.+)$", RegexOption.IGNORE_CASE)
        private val namedId = Regex("^id:([0-9]+)$", RegexOption.IGNORE_CASE)
        private val legacyArtistId = Regex("^(.+) (id:[0-9]+)$", RegexOption.IGNORE_CASE)
        private val bareId = Regex("^[0-9]+$")
        private val quoted = Regex("^\"([^\"]+)\"\\s+(?:\"([^\"]+)\"|([^\"]+))$")
        private val sharedSong = Regex("^分享.*?单曲.*?[:：]\\s*(https?://\\S+)(?:\\s.*)?$", RegexOption.IGNORE_CASE)
        private val artistSeparator = Regex(" (?:-|–|—) ")

        private fun normalize(text: String): String =
            spaces.replace(Normalizer.normalize(text, Normalizer.Form.NFKC).trim(), " ").lowercase(Locale.ROOT)

        private fun artistKey(text: String): String = artistSeparators.replace(normalize(text), "").map { char ->
            if (char in '\u30a1'..'\u30f6') char - 0x60 else char
        }.joinToString("")

        @JvmStatic fun parse(content: String): SongRequestQuery? {
            if (content.length > 512 || content.any { it.isISOControl() && it != '\t' }) return null
            val input = Normalizer.normalize(content.trim(), Normalizer.Form.NFKC)
            val match = command.matchEntire(input) ?: return null
            val phrase = match.groupValues[2].trim()
            val link = sharedSong.matchEntire(phrase)?.groupValues?.get(1) ?: phrase
            if (link.startsWith("https://", true) || link.startsWith("http://", true)) {
                val uri = runCatching { URI(link) }.getOrNull() ?: return null
                NeteaseSongLink.trackId(uri)?.let { return SongRequestQuery(it.toString(), trackId = it) }
                if (NeteaseSongLink.isShortLink(uri)) return SongRequestQuery(link, shortLink = uri)
                return null
            }
            if (bareId.matches(phrase) || phrase.startsWith("id:", true)) {
                val digits = namedId.matchEntire(phrase)?.groupValues?.get(1) ?: phrase
                val id = digits.toLongOrNull()?.takeIf { it > 0L } ?: return null
                return SongRequestQuery(id.toString(), trackId = id)
            }
            if (phrase.isBlank() || phrase.length > 192) return null
            val words = spaces.replace(phrase, " ")
            val parts = artistSeparator.split(words, limit = 2)
            if (parts.size == 2) return namedQuery(parts[0], parts[1])
            if (match.groupValues[1].equals("song", true)) return SongRequestQuery(words, title = words)
            legacyArtistId.matchEntire(words)?.let { return namedQuery(it.groupValues[1], it.groupValues[2]) }
            if ('"' in words) {
                val fields = quoted.matchEntire(words)?.groupValues ?: return null
                return namedQuery(fields[1], fields[2].ifBlank { fields[3] })
            }
            if (' ' !in words) return null
            return SongRequestQuery(words)
        }

        private fun namedQuery(title: String, artist: String): SongRequestQuery? {
            val name = title.trim()
            val credit = artist.trim()
            if (name.isEmpty() || credit.isEmpty()) return null
            // Numeric artist IDs are resolved against the song's credits, so search the title itself.
            val search = if (namedId.matches(credit)) name else "$name $credit"
            return SongRequestQuery(search, title = name, artist = credit)
        }
    }
}
