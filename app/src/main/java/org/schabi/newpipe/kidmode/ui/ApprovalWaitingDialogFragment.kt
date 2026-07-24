/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.kidmode.KidModeGate
import org.schabi.newpipe.kidmode.KidModePinPrompt
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity
import org.schabi.newpipe.kidmode.db.ApprovalRequestStatus
import org.schabi.newpipe.kidmode.db.ApprovalRequestType
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ThemeHelper

/**
 * Shown in place of immediately playing a video or subscribing to a channel when Kid Mode blocks
 * the action. This is the local, same-device stand-in for parent approval described in
 * `wiki/features/kid-mode.md` -- a future phase replaces the "Approve as parent" button here with
 * a request that a paired parent device can approve remotely, but this dialog and the
 * [ApprovalRequestEntity] row it observes are exactly what that later phase reuses.
 *
 * All the actual completion work (marking a request resolved, subscribing on approval) happens in
 * [KidModeGate], shared with the embedded HTTP server -- this dialog only reacts to the row's
 * status once [KidModeGate] has already finished it, it never repeats that work itself.
 */
class ApprovalWaitingDialogFragment : DialogFragment() {
    private val disposables = CompositeDisposable()

    private val requestId: Long
        get() = requireArguments().getLong(ARG_REQUEST_ID)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext(), ThemeHelper.getDialogTheme(requireContext()))
            .setTitle(R.string.kid_mode_waiting_title)
            .setMessage(R.string.kid_mode_waiting_loading)
            .setCancelable(true)
            .setNeutralButton(R.string.cancel, null)
            .setNegativeButton(R.string.kid_mode_deny) { _, _ -> deny() }
            .setPositiveButton(R.string.kid_mode_approve_as_parent, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Overridden so a wrong PIN doesn't dismiss the dialog (the default AlertDialog button
        // behavior always dismisses on click).
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            promptForPin()
        }

        val database = NewPipeDatabase.getInstance(requireContext())
        disposables.add(
            database.approvalRequestDAO().getById(requestId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(::onRequestUpdated)
        )
    }

    override fun onStop() {
        disposables.clear()
        super.onStop()
    }

    private fun onRequestUpdated(request: ApprovalRequestEntity) {
        val dialog = dialog as? AlertDialog ?: return

        when (request.status) {
            ApprovalRequestStatus.PENDING -> {
                dialog.setMessage(getString(R.string.kid_mode_waiting_message, request.targetTitle))
            }

            ApprovalRequestStatus.APPROVED -> {
                onApproved(request)
                dismiss()
            }

            ApprovalRequestStatus.DENIED -> {
                Toast.makeText(requireContext(), R.string.kid_mode_denied_toast, Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    private fun promptForPin() {
        KidModePinPrompt.show(requireContext(), layoutInflater) { approve() }
    }

    private fun approve() {
        val context = requireContext().applicationContext
        // Deliberately not added to `disposables` (cleared in onStop()): approval -- which may
        // involve a network fetch for a subscribe request -- must finish even if the dialog is
        // dismissed before it completes.
        KidModeGate(context).approve(requestId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({}, {
                Toast.makeText(context, R.string.general_error, Toast.LENGTH_SHORT).show()
            })
    }

    private fun deny() {
        val context = requireContext().applicationContext
        disposables.add(
            KidModeGate(context).deny(requestId)
                .subscribeOn(Schedulers.io())
                .subscribe()
        )
    }

    private fun onApproved(request: ApprovalRequestEntity) {
        when (request.requestType) {
            ApprovalRequestType.PLAY_VIDEO -> {
                NavigationHelper.openVideoDetailFragment(
                    requireContext(),
                    requireActivity().supportFragmentManager,
                    request.serviceId,
                    request.targetUrl,
                    request.targetTitle,
                    null,
                    false
                )
            }

            ApprovalRequestType.SUBSCRIBE_CHANNEL -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.kid_mode_subscribed_toast, request.targetTitle),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        private const val ARG_REQUEST_ID = "request_id"
        const val TAG = "ApprovalWaitingDialogFragment"

        @JvmStatic
        fun newInstance(requestId: Long): ApprovalWaitingDialogFragment {
            return ApprovalWaitingDialogFragment().apply {
                arguments = Bundle().apply { putLong(ARG_REQUEST_ID, requestId) }
            }
        }
    }
}
