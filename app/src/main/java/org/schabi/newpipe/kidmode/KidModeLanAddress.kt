/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import java.net.Inet4Address

/**
 * Best-effort detection of this device's own LAN IPv4 address, for embedding in the pairing QR
 * code shown by [org.schabi.newpipe.settings.KidModeSettingsFragment]'s "Pair a parent device"
 * dialog.
 *
 * Deliberately uses [ConnectivityManager]/[android.net.LinkProperties] rather than
 * `WifiManager.getConnectionInfo()`: the latter needs `ACCESS_WIFI_STATE`, a permission this app
 * has avoided adding (see the NSD advertiser/discoverer classes), and only reports an address
 * when associated to Wi-Fi in station mode. `ConnectivityManager` reflects whatever the active
 * network actually is, matching what [org.schabi.newpipe.kidmode.server.ApprovalHttpServer]
 * (bound to `0.0.0.0`, any interface) is actually reachable on.
 */
object KidModeLanAddress {
    fun currentIpv4Address(context: Context): String? {
        val connectivityManager =
            ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }
}
