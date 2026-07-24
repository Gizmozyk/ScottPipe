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
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.kidmode.KidModePinPrompt
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity
import org.schabi.newpipe.kidmode.db.ApprovalRequestStatus
import org.schabi.newpipe.kidmode.db.ApprovalRequestType
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ThemeHelper

/**
 * Shown in place of immediately playing a video or subscribing to a channel when Kid Mode blocks
 * the action. This is the local, same-device stand-in for parent approval described in
 * `wiki/features/kid-mode.md` -- a future phase replaces the "Approve as parent" button here with
 * a request that a paired parent device can approve remotely, but this dialog and the
 * [ApprovalRequestEntity] row it observes are exactly what that later phase reuses.
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
                onApproved(request, onDone = ::dismiss)
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
        val database = NewPipeDatabase.getInstance(requireContext())
        disposables.add(
            Single.fromCallable {
                database.approvalRequestDAO().updateStatus(
                    requestId,
                    ApprovalRequestStatus.APPROVED,
                    System.currentTimeMillis()
                )
            }
                .subscribeOn(Schedulers.io())
                .subscribe()
        )
    }

    private fun deny() {
        val database = NewPipeDatabase.getInstance(requireContext())
        disposables.add(
            Single.fromCallable {
                database.approvalRequestDAO().updateStatus(
                    requestId,
                    ApprovalRequestStatus.DENIED,
                    System.currentTimeMillis()
                )
            }
                .subscribeOn(Schedulers.io())
                .subscribe()
        )
    }

    private fun onApproved(request: ApprovalRequestEntity, onDone: () -> Unit) {
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
                onDone()
            }

            ApprovalRequestType.SUBSCRIBE_CHANNEL -> {
                // Deliberately not added to `disposables` (cleared in onStop()): this must
                // finish even if the dialog is dismissed before the network fetch completes.
                val context = requireContext().applicationContext
                ExtractorHelper.getChannelInfo(request.serviceId, request.targetUrl, false)
                    .map(SubscriptionEntity::from)
                    .doOnSuccess { subscription ->
                        SubscriptionManager(context).insertSubscription(subscription)
                    }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { subscription ->
                            Toast.makeText(
                                context,
                                getString(R.string.kid_mode_subscribed_toast, subscription.name),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDone()
                        },
                        {
                            Toast.makeText(context, R.string.general_error, Toast.LENGTH_SHORT).show()
                            onDone()
                        }
                    )
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
