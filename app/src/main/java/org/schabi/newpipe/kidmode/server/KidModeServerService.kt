/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.server

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.schabi.newpipe.R

/**
 * Foreground service (see `us.shandian.giga.service.DownloadManagerService` for the pattern this
 * mirrors) that keeps [ApprovalHttpServer] running while Kid Mode is enabled. Started/stopped
 * from [org.schabi.newpipe.settings.KidModeSettingsFragment] when the toggle changes, and from
 * `App.onCreate()` if Kid Mode is already on when the app process (re)starts.
 */
class KidModeServerService : Service() {
    private var server: ApprovalHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            server = ApprovalHttpServer(this, ApprovalHttpServer.DEFAULT_PORT).apply {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KidModeServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KidModeServerService::class.java))
        }
    }
}
