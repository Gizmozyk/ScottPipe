/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogEditTextBinding
import org.schabi.newpipe.util.ThemeHelper

/** Shared PIN-entry dialogs for Kid Mode, used both by [KidModePinManager]'s callers in
 * settings and by the approval-waiting screen. */
object KidModePinPrompt {
    private const val MIN_PIN_LENGTH = 4

    private fun newPinEditTextBinding(context: Context, layoutInflater: LayoutInflater): DialogEditTextBinding {
        val binding = DialogEditTextBinding.inflate(layoutInflater)
        binding.root.context.setTheme(ThemeHelper.getDialogTheme(context))
        binding.dialogEditText.setHint(R.string.kid_mode_pin_hint)
        binding.dialogEditText.setInputType(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        return binding
    }

    /** Prompts for the existing PIN and invokes [onCorrectPin] only if it matches. */
    fun show(context: Context, layoutInflater: LayoutInflater, onCorrectPin: () -> Unit) {
        val pinManager = KidModePinManager(context)
        val binding = newPinEditTextBinding(context, layoutInflater)

        AlertDialog.Builder(context, ThemeHelper.getDialogTheme(context))
            .setTitle(R.string.kid_mode_pin_prompt_title)
            .setView(binding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (pinManager.verifyPin(binding.dialogEditText.text.toString())) {
                    onCorrectPin()
                } else {
                    Toast.makeText(context, R.string.kid_mode_wrong_pin, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** Prompts to set a brand new PIN and invokes [onPinSet] once one is stored. */
    fun showSetPin(context: Context, layoutInflater: LayoutInflater, onPinSet: () -> Unit) {
        val pinManager = KidModePinManager(context)
        val binding = newPinEditTextBinding(context, layoutInflater)

        AlertDialog.Builder(context, ThemeHelper.getDialogTheme(context))
            .setTitle(R.string.kid_mode_set_pin_title)
            .setMessage(R.string.kid_mode_set_pin_message)
            .setView(binding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val pin = binding.dialogEditText.text.toString()
                if (pin.length < MIN_PIN_LENGTH) {
                    Toast.makeText(context, R.string.kid_mode_pin_too_short, Toast.LENGTH_SHORT).show()
                } else {
                    pinManager.setPin(pin)
                    onPinSet()
                }
            }
            .show()
    }
}
