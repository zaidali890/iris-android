package com.iris.android.tools

import android.content.Context
import android.provider.ContactsContract

object ContactResolver {

    data class Match(val name: String, val number: String)

    /** Returns a phone number for the given input, resolving by contact name if it's not already one. */
    fun resolve(context: Context, contact: String): String? = resolveBest(context, contact)?.number

    /**
     * Returns the best-ranked contact match, or null if nothing matched. Ranks an exact name match
     * above a "starts with" match above a plain "contains" match, instead of returning whichever
     * row the database cursor happened to return first — that was the root cause of IRIS sometimes
     * texting the wrong person when multiple contacts partially matched the spoken name.
     */
    fun resolveBest(context: Context, contact: String): Match? {
        val looksLikeNumber = contact.count { it.isDigit() } >= 6
        if (looksLikeNumber) return Match(contact, contact.filter { it.isDigit() || it == '+' })

        val query = contact.trim().lowercase()
        val candidates = mutableListOf<Match>()

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$contact%"),
            null
        )
        cursor?.use {
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (it.moveToNext()) {
                val number = it.getString(numberIdx) ?: continue
                val name = it.getString(nameIdx) ?: continue
                candidates.add(Match(name, number))
            }
        }

        if (candidates.isEmpty()) return null

        fun rank(name: String): Int {
            val n = name.lowercase()
            return when {
                n == query -> 0
                n.startsWith(query) -> 1
                n.split(" ").any { it == query } -> 2 // matches one whole word, e.g. "Farhan" in "Muhammad Farhan Khan"
                else -> 3
            }
        }

        // Prefer the best rank; among ties, prefer the shortest name (usually the more specific/direct match).
        return candidates
            .distinctBy { it.number }
            .sortedWith(compareBy({ rank(it.name) }, { it.name.length }))
            .firstOrNull()
    }
}
