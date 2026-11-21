package me.sorihiko.dangomusicgtnh.musicui

import java.text.Normalizer
import java.util.Locale

internal object SettingsSearchRank {
    private val whitespace = Regex("\\s+")

    @JvmStatic
    fun score(query: String, label: String, detail: String, group: String, tab: String): Int {
        val terms = Normalizer.normalize(query, Normalizer.Form.NFKC)
            .split(whitespace).map(::fold).filter(String::isNotEmpty)
        if (terms.isEmpty()) return -1
        val title = fold(label)
        val fields = listOf(title, fold(detail), fold(group), fold(tab))
        val weights = listOf(80, 18, 24, 7)
        var score = 0
        for (term in terms) {
            val best = fields.indices.maxOf { index ->
                val field = fields[index]
                when {
                    field == term -> weights[index] + 26
                    term in field -> weights[index] + 17
                    term.length >= 2 && orderedMatch(term, field) -> weights[index]
                    else -> -1
                }
            }
            if (best < 0) return -1
            score += best
        }
        return score + if (terms.joinToString("") == title) 40 else 0
    }

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun orderedMatch(term: String, field: String): Boolean {
        var at = 0
        for (char in field) {
            if (char == term[at] && ++at == term.length) return true
        }
        return false
    }
}
