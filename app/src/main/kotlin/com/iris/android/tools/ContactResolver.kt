package com.iris.android.tools

import com.iris.android.data.AllowedContactEntity

object ContactResolver {

    sealed class Resolution {
        data class Found(val name: String, val number: String) : Resolution()
        data class Suggestions(val names: List<String>) : Resolution()
        data object NotFound : Resolution()
    }

    /**
     * Resolves a spoken/typed contact reference against the user's curated allow-list.
     * A bare phone number always passes through directly — that's an unambiguous instruction with
     * no name-matching involved, so it isn't restricted to the allow-list the way name lookups are.
     *
     * For name lookups: tries an exact / whole-word / substring match first (fast path for the
     * common case of getting the name basically right). Only when none of those succeed does it
     * fall back to fuzzy (edit-distance) suggestions — asking "did you mean X or Y" is exactly what
     * was requested, and only when a confident direct match doesn't exist.
     */
    fun resolve(contacts: List<AllowedContactEntity>, query: String): Resolution {
        val looksLikeNumber = query.count { it.isDigit() } >= 6
        if (looksLikeNumber) {
            val number = query.filter { it.isDigit() || it == '+' }
            return Resolution.Found(query, number)
        }

        val q = query.trim().lowercase()
        if (contacts.isEmpty()) return Resolution.NotFound

        fun rank(name: String): Int {
            val n = name.lowercase()
            return when {
                n == q -> 0
                n.split(" ").any { it == q } -> 1 // whole word, e.g. "Farhan" inside "Farhan Ali"
                n.contains(q) -> 2
                else -> Int.MAX_VALUE
            }
        }

        val ranked = contacts.map { it to rank(it.name) }.filter { it.second != Int.MAX_VALUE }
        if (ranked.isNotEmpty()) {
            val best = ranked.sortedWith(compareBy({ it.second }, { it.first.name.length })).first().first
            return Resolution.Found(best.name, best.number)
        }

        // No direct match at all — offer the closest few names by edit distance instead of failing outright.
        val scored = contacts
            .map { it to editDistance(q, it.name.lowercase()) }
            .sortedBy { it.second }
        val closest = scored.take(3).filter { it.second <= 4 } // cap distance so wildly unrelated names aren't suggested
        return if (closest.isEmpty()) Resolution.NotFound else Resolution.Suggestions(closest.map { it.first.name })
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
