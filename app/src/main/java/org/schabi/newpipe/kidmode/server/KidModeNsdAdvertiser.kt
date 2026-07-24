/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Advertises [ApprovalHttpServer] over NSD (mDNS/DNS-SD) so a paired parent device can someday
 * (Phase D, not built yet) discover a kid's device on the LAN instead of needing its IP typed in.
 *
 * `NsdManager` doesn't require `ACCESS_WIFI_STATE`/`NEARBY_WIFI_DEVICES` for registration or
 * discovery (unlike raw Wi-Fi scanning or Wi-Fi Direct/Aware APIs), so no extra manifest
 * permission is declared for this.
 *
 * Known limitation: the emulator's default networking doesn't carry multicast traffic to the
 * host or between emulator instances, so genuine cross-device discovery can't be verified in
 * that environment -- see `wiki/testing.md`.
 */
class KidModeNsdAdvertiser(context: Context) {
    private val nsdManager = ContextCompat.getSystemService(context, NsdManager::class.java)
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun advertise(deviceName: String, port: Int) {
        val manager = nsdManager ?: return
        stop()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX $deviceName"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Registered NSD service: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed for ${info.serviceName}: error $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Unregistered NSD service: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed for ${info.serviceName}: error $errorCode")
            }
        }
        registrationListener = listener

        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "NSD registration not permitted", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "NSD registration rejected", e)
        }
    }

    fun stop() {
        val manager = nsdManager ?: return
        registrationListener?.let {
            try {
                manager.unregisterService(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered/never succeeded -- nothing to undo.
                Log.d(TAG, "NSD service was not registered", e)
            }
        }
        registrationListener = null
    }

    companion object {
        private const val TAG = "KidModeNsdAdvertiser"
        private const val SERVICE_TYPE = "_scottpipe._tcp."
        private const val SERVICE_NAME_PREFIX = "ScottPipe Kid Mode -"
    }
}
