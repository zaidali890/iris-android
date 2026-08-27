package com.iris.android.services

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * IRIS doesn't screen/block calls today — auto-answering (when enabled) happens separately in
 * IrisForegroundService via TelephonyCallback + TelecomManager. This just allows everything
 * through so the declared service doesn't accidentally interfere with incoming calls.
 */
class IrisCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
