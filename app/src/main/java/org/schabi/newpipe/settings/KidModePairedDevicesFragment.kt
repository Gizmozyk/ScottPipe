/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.text.DateFormat
import java.util.Date
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.kidmode.KidModePairingManager
import org.schabi.newpipe.kidmode.db.PairedDeviceEntity

/**
 * Lists devices paired via the "Pair a parent device" action in [KidModeSettingsFragment], with a
 * way to revoke one. See `wiki/features/kid-mode.md` for the pairing protocol.
 */
class KidModePairedDevicesFragment : BasePreferenceFragment() {
    private val disposables = CompositeDisposable()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()
    }

    override fun onStart() {
        super.onStart()
        val database = NewPipeDatabase.getInstance(requireContext())
        disposables.add(
            database.pairedDeviceDAO().getActive()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(::showDevices)
        )
    }

    override fun onStop() {
        disposables.clear()
        super.onStop()
    }

    private fun showDevices(devices: List<PairedDeviceEntity>) {
        val screen = preferenceScreen
        screen.removeAll()

        if (devices.isEmpty()) {
            screen.addPreference(
                Preference(requireContext()).apply {
                    isSelectable = false
                    setSummary(R.string.kid_mode_no_paired_devices)
                }
            )
            return
        }

        devices.forEach { device ->
            screen.addPreference(
                Preference(requireContext()).apply {
                    title = device.deviceName
                    summary = getString(
                        R.string.kid_mode_paired_device_summary,
                        DateFormat.getDateTimeInstance().format(Date(device.pairedAt))
                    )
                    setOnPreferenceClickListener {
                        confirmRevoke(device)
                        true
                    }
                }
            )
        }
    }

    private fun confirmRevoke(device: PairedDeviceEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.kid_mode_revoke_device_title)
            .setMessage(getString(R.string.kid_mode_revoke_device_message, device.deviceName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> revoke(device) }
            .show()
    }

    private fun revoke(device: PairedDeviceEntity) {
        val context = requireContext().applicationContext
        disposables.add(
            Completable.fromAction { KidModePairingManager(context).revoke(device.deviceId) }
                .subscribeOn(Schedulers.io())
                .subscribe()
        )
    }
}
