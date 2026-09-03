package com.iris.android.services

import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * IRIS doesn't block or silence calls — this always allows every call through unchanged. What it
 * DOES do now: capture the incoming number (and resolve a contact name for it) the moment a call
 * starts ringing, since CallScreeningService is the only reliable, immediate way to get that
 * information — TelephonyCallback (used elsewhere for ringing-state detection) doesn't expose the
 * caller's number on modern Android for privacy reasons. IrisForegroundService reads
 * [lastIncomingCall] once it detects the RINGING state, to build the "who's calling" announcement.
 */
class IrisCallScreeningService : CallScreeningService() {

    data class CallerInfo(val number: String, val name: String?)

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        if (!number.isNullOrBlank()) {
            lastIncomingCall = CallerInfo(number, resolveContactName(number))
        }
        respondToCall(callDetails, CallResponse.Builder().build())
    }

    private fun resolveContactName(number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile var lastIncomingCall: CallerInfo? = null
    }
}
