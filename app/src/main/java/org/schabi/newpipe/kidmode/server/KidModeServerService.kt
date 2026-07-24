/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.server

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.schabi.newpipe.R

/**
 * Foreground service (see `us.shandian.giga.service.DownloadManagerService` for the pattern this
 * mirrors) that keeps [ApprovalHttpServer] running, and advertises it over NSD via
 * [KidModeNsdAdvertiser], while Kid Mode is enabled. Started/stopped from
 * [org.schabi.newpipe.settings.KidModeSettingsFragment] when the toggle changes, and from
 * `App.onCreate()` if Kid Mode is already on when the app process (re)starts.
 */
class KidModeServerService : Service() {
    private var server: ApprovalHttpServer? = null
    private var pairingSession: KidModePairingSession? = null
    private var nsdAdvertiser: KidModeNsdAdvertiser? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            val session = KidModePairingSession()
            val newServer = ApprovalHttpServer(this, ApprovalHttpServer.DEFAULT_PORT, session).apply {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            val advertiser = KidModeNsdAdvertiser(this).apply {
                advertise(Build.MODEL, newServer.listeningPort)
            }

            pairingSession = session
            server = newServer
            nsdAdvertiser = advertiser
            activePairingSession = session
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        activePairingSession = null
        nsdAdvertiser?.stop()
        nsdAdvertiser = null
        server?.stop()
        server = null
        pairingSession = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, getString(R.string.kid_mode_notification_channel_id))
            .setSmallIcon(R.drawable.ic_child_care)
            .setContentTitle(getString(R.string.kid_mode_notification_title))
            .setContentText(getString(R.string.kid_mode_notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 9137244

        @Volatile
        private var activePairingSession: KidModePairingSession? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KidModeServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KidModeServerService::class.java))
        }

        /**
         * Generates a fresh 6-digit pairing code for "Pair a parent device" in
         * [org.schabi.newpipe.settings.KidModeSettingsFragment], or `null` if the service isn't
         * currently running (Kid Mode must be on first).
         */
        fun startPairingSession(): String? = activePairingSession?.start(System.currentTimeMillis())
    }
}
