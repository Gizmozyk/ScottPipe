/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.schabi.newpipe.R
import org.schabi.newpipe.kidmode.KidModePinManager
import org.schabi.newpipe.kidmode.KidModePinPrompt
import org.schabi.newpipe.kidmode.server.KidModeServerService

/**
 * See `wiki/features/kid-mode.md` for what Kid Mode does and
 * `wiki/adr/0001-kid-mode-architecture.md` for why it's built this way.
 */
class KidModeSettingsFragment : BasePreferenceFragment() {
    private lateinit var enabledPreference: SwitchPreferenceCompat
    private lateinit var pairDeviceKey: String

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()
        enabledPreference = requirePreference(R.string.kid_mode_enabled_key)
        pairDeviceKey = getString(R.string.kid_mode_pair_device_key)

        val pinManager = KidModePinManager(requireContext())
        if (pinManager.isPinSet()) {
            // Re-entering this screen requires the PIN too, so a kid can't get back in to flip
            // the switch off just because they once saw the screen while it was unlocked.
            enabledPreference.isEnabled = false
            KidModePinPrompt.show(requireContext(), layoutInflater) {
                enabledPreference.isEnabled = true
            }
        }

        enabledPreference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                onToggleOn(pinManager)
            } else {
                onToggleOff(pinManager)
            }
            // Always deny the automatic change: the callbacks above set `isChecked` themselves
            // once (and if) the PIN step actually succeeds.
            false
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == pairDeviceKey) {
            // Pairing grants a new trusted device -- sensitive, same PIN gate as disabling.
            KidModePinPrompt.show(requireContext(), layoutInflater) { startPairing() }
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun startPairing() {
        val code = KidModeServerService.startPairingSession()
        if (code == null) {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.kid_mode_pairing_unavailable)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.kid_mode_pairing_code_title)
            .setMessage(getString(R.string.kid_mode_pairing_code_message, code))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun onToggleOn(pinManager: KidModePinManager) {
        if (pinManager.isPinSet()) {
            enabledPreference.isChecked = true
            KidModeServerService.start(requireContext())
        } else {
            KidModePinPrompt.showSetPin(requireContext(), layoutInflater) {
                enabledPreference.isChecked = true
                KidModeServerService.start(requireContext())
            }
        }
    }

    private fun onToggleOff(pinManager: KidModePinManager) {
        KidModePinPrompt.show(requireContext(), layoutInflater) {
            enabledPreference.isChecked = false
            KidModeServerService.stop(requireContext())
        }
    }
}
