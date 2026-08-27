package com.iris.android.tools

import android.content.Context
import android.provider.ContactsContract

object ContactResolver {
    /** Returns a phone number for the given input, resolving by contact name if it's not already one. */
    fun resolve(context: Context, contact: String): String? {
        val looksLikeNumber = contact.count { it.isDigit() } >= 6
        if (looksLikeNumber) return contact.filter { it.isDigit() || it == '+' }

        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$contact%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return it.getString(numberIndex)
            }
        }
        return null
    }
}
