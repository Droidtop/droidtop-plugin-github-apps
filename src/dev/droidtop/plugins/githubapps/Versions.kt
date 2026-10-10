package dev.droidtop.plugins.githubapps

/**
 * Release tags and version names compared the way people write them (`v1.2.3`, `2024.05.01`, `1.0-rc2`,
 * `release-3.4`). Pure.
 *
 * A version is cut into tokens, numbers and words. Numbers compare as numbers, however long; a word names a stage
 * (dev < alpha < beta < rc < release); a version that ends sooner is the older one unless what the longer one adds
 * is zeros (`1.0` equals `1.0.0`) or a pre-release word (`1.0` is newer than `1.0-rc1`). What comes before the first
 * digit (`v`, `release-`, `app-v`) and build metadata after `+` are ignored. In the spirit of Obtainium's
 * version handling (https://github.com/ImranR98/Obtainium, GPL-3.0), rewritten for this plugin.
 */
object Versions {
    private sealed class Token {
        class Num(val digits: String) : Token()

        class Word(val text: String, val rank: Int) : Token()
    }

    /** The stage a word names; 10 is a release (final, ga, stable, or any word that is not a pre-release marker). */
    private fun rankOf(word: String): Int = when (word) {
        "dev", "snapshot", "nightly", "canary" -> 0
        "alpha", "a" -> 1
        "beta", "b", "preview", "pre", "m", "milestone" -> 2
        "rc", "cr", "candidate" -> 3
        else -> 10
    }

    private val TOKEN = Regex("[0-9]+|[a-z]+")

    private fun tokens(version: String): List<Token> {
        var text = version.lowercase().trim().substringBefore('+')
        val firstDigit = text.indexOfFirst { it in '0'..'9' }
        if (firstDigit > 0) text = text.substring(firstDigit)
        return TOKEN.findAll(text).map { m ->
            val v = m.value
            if (v[0] in '0'..'9') Token.Num(v.trimStart('0').ifEmpty { "0" }) else Token.Word(v, rankOf(v))
        }.toList()
    }

    private fun compareNum(a: String, b: String): Int = if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)

    /** [x] against a token the other version does not have. */
    private fun againstNothing(x: Token): Int = when (x) {
        is Token.Num -> if (x.digits == "0") 0 else 1
        is Token.Word -> if (x.rank < 10) -1 else 1
    }

    private fun compareTokens(x: Token?, y: Token?): Int = when {
        x == null && y == null -> 0
        x == null -> -againstNothing(y!!)
        y == null -> againstNothing(x)
        x is Token.Num && y is Token.Num -> compareNum(x.digits, y.digits)
        x is Token.Word && y is Token.Word -> if (x.rank != y.rank) x.rank.compareTo(y.rank) else x.text.compareTo(y.text)
        x is Token.Num -> 1
        else -> -1
    }

    /** Negative when [a] is older than [b], zero when they are the same version, positive when [a] is newer. */
    fun compare(a: String, b: String): Int {
        val ta = tokens(a)
        val tb = tokens(b)
        for (i in 0 until maxOf(ta.size, tb.size)) {
            val c = compareTokens(ta.getOrNull(i), tb.getOrNull(i))
            if (c != 0) return c
        }
        return 0
    }

    /** Whether the version names itself a pre-release (`1.0-rc1`, `2.0.0-beta`, `nightly`). */
    fun isPreRelease(version: String): Boolean = tokens(version).any { it is Token.Word && it.rank < 10 }

    /** The newest of [items] by [compare] of their [version] (the first of equals), or null when there are none. */
    fun <T> newest(items: List<T>, version: (T) -> String): T? {
        var best: T? = null
        for (item in items) if (best == null || compare(version(item), version(best)) > 0) best = item
        return best
    }
}
