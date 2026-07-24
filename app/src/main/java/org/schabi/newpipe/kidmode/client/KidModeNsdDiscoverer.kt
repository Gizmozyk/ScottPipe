/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Discovers kid devices advertising [org.schabi.newpipe.kidmode.server.ApprovalHttpServer] over
 * NSD (mDNS/DNS-SD) -- the counterpart to
 * [org.schabi.newpipe.kidmode.server.KidModeNsdAdvertiser], run from a parent device instead of a
 * kid's. Uses the older callback-based `discoverServices`/`resolveService` API (not the
 * `DiscoveryRequest`/`ServiceInfoCallback` API added in API 34) since this app's minSdk is 23.
 *
 * Known limitation: the emulator's default networking doesn't carry multicast traffic to the host
 * or between emulator instances, so genuine cross-device discovery can't be verified in that
 * environment -- see `wiki/testing.md`. The "Connect manually" fallback in Parent Mode exists
 * partly because of this, but also because mDNS/NSD is commonly blocked by AP/client isolation on
 * real consumer routers and mesh Wi-Fi systems.
 */
class KidModeNsdDiscoverer(context: Context) {
    private val nsdManager = ContextCompat.getSystemService(context, NsdManager::class.java)
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    interface Callback {
        fun onDeviceFound(name: String, host: String, port: Int)
        fun onDeviceLost(name: String)
    }

    fun start(callback: Callback) {
        val manager = nsdManager ?: return
        stop()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started for $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                resolve(manager, info, callback)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                callback.onDeviceLost(displayName(info.serviceName))
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery start failed for $serviceType: error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery stop failed for $serviceType: error $errorCode")
            }
        }
        discoveryListener = listener

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "NSD discovery not permitted", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "NSD discovery rejected", e)
        }
    }

    fun stop() {
        val manager = nsdManager ?: return
        discoveryListener?.let {
            try {
                manager.stopServiceDiscovery(it)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "NSD discovery was not running", e)
            }
        }
        discoveryListener = null
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo, callback: Callback) {
        manager.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD resolve failed for ${info.serviceName}: error $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    callback.onDeviceFound(displayName(info.serviceName), host, info.port)
                }
            }
        )
    }

    private fun displayName(serviceName: String): String = serviceName.removePrefix("$SERVICE_NAME_PREFIX ")

    companion object {
        private const val TAG = "KidModeNsdDiscoverer"
        private const val SERVICE_TYPE = "_scottpipe._tcp."
        private const val SERVICE_NAME_PREFIX = "ScottPipe Kid Mode -"
    }
}
