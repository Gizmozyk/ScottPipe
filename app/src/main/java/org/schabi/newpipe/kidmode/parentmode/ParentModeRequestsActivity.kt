/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.parentmode

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityParentModeRequestsBinding
import org.schabi.newpipe.kidmode.ParentPairingManager
import org.schabi.newpipe.kidmode.client.KidModeApiClient
import org.schabi.newpipe.kidmode.client.KidModeApiClient.RemoteApprovalRequest
import org.schabi.newpipe.kidmode.db.ParentPairingEntity
import org.schabi.newpipe.util.ThemeHelper

/**
 * Shows a paired kid device's pending requests and lets the parent approve/deny them remotely --
 * calling the exact same `/approve`/`/deny` endpoints already proven (Phases A-C) to share
 * [org.schabi.newpipe.kidmode.KidModeGate] with the kid's local PIN button, so the kid's own
 * waiting dialog reacts identically no matter which device triggers it.
 *
 * Auto-refreshes every 3 seconds while visible, in addition to manual pull-to-refresh: the whole
 * point of this screen is that the kid's video is sitting blocked *right now*.
 */
class ParentModeRequestsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParentModeRequestsBinding
    private lateinit var adapter: ParentModeRequestAdapter

    private val disposables = CompositeDisposable()
    private val parentPairingManager by lazy { ParentPairingManager(this) }
    private val apiClient = KidModeApiClient()

    private var pairingUid: Long = -1L
    private var pairing: ParentPairingEntity? = null
    private var secret: ByteArray? = null
    private var revokedDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)

        pairingUid = intent.getLongExtra(EXTRA_PAIRING_UID, -1L)
        if (pairingUid == -1L) {
            finish()
            return
        }

        binding = ActivityParentModeRequestsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.parent_mode_requests_title)
        }

        adapter = ParentModeRequestAdapter(onApprove = ::approve, onDeny = ::deny)
        binding.parentModeRequestsList.layoutManager = LinearLayoutManager(this)
        binding.parentModeRequestsList.adapter = adapter
        binding.parentModeRequestsSwipeRefresh.setOnRefreshListener { fetchOnce() }
    }

    override fun onStart() {
        super.onStart()
        disposables.add(
            loadPairing()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { loaded ->
                        pairing = loaded.first
                        secret = loaded.second
                        supportActionBar?.subtitle = loaded.first.kidDeviceName
                        startAutoRefresh()
                    },
                    { finish() }
                )
        )
    }

    override fun onStop() {
        super.onStop()
        disposables.clear()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_parent_mode_requests, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == R.id.action_manage_channels) {
            startActivity(
                Intent(this, ParentModeChannelsActivity::class.java)
                    .putExtra(ParentModeChannelsActivity.EXTRA_PAIRING_UID, pairingUid)
            )
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadPairing(): Single<Pair<ParentPairingEntity, ByteArray>> {
        return Single.fromCallable {
            val entity = NewPipeDatabase.getInstance(this).parentPairingDAO().getByIdOnce(pairingUid)
                ?: throw NoSuchElementException("No such pairing")
            entity to parentPairingManager.decryptSecret(entity)
        }.subscribeOn(Schedulers.io())
    }

    private fun startAutoRefresh() {
        disposables.add(
            Observable.interval(0, AUTO_REFRESH_SECONDS, TimeUnit.SECONDS, Schedulers.io())
                .flatMapSingle { fetchRequestsResult() }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { result -> handleFetchResult(result) }
        )
    }

    private fun fetchOnce() {
        disposables.add(
            fetchRequestsResult()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { result -> handleFetchResult(result) }
        )
    }

    private fun fetchRequestsResult(): Single<Result<List<RemoteApprovalRequest>>> {
        val currentPairing = pairing ?: return Single.just(Result.failure(IllegalStateException("Pairing not loaded")))
        val currentSecret = secret ?: return Single.just(Result.failure(IllegalStateException("Pairing not loaded")))
        return apiClient.pendingRequests(currentPairing.host, currentPairing.port, currentPairing.kidDeviceId, currentSecret)
            .map { Result.success(it) as Result<List<RemoteApprovalRequest>> }
            .onErrorReturn { Result.failure(it) }
    }

    private fun handleFetchResult(result: Result<List<RemoteApprovalRequest>>) {
        binding.parentModeRequestsSwipeRefresh.isRefreshing = false
        result.onSuccess { requests ->
            binding.parentModeRequestsError.visibility = View.GONE
            adapter.submitList(requests)
        }.onFailure { e -> handleError(e) }
    }

    private fun handleError(e: Throwable) {
        if (e is KidModeApiClient.KidModeApiException && e.status == 401) {
            showRevokedDialog()
            return
        }
        binding.parentModeRequestsError.visibility = View.VISIBLE
        binding.parentModeRequestsError.text = getString(
            R.string.parent_mode_connection_error,
            pairing?.kidDeviceName ?: ""
        )
    }

    private fun showRevokedDialog() {
        if (revokedDialogShown) return
        revokedDialogShown = true
        AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
            .setTitle(R.string.parent_mode_revoked_title)
            .setMessage(R.string.parent_mode_revoked_message)
            .setCancelable(false)
            .setPositiveButton(R.string.parent_mode_remove_pairing) { _, _ ->
                disposables.add(
                    Completable.fromAction { parentPairingManager.delete(pairingUid) }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { finish() }
                )
            }
            .show()
    }

    private fun approve(request: RemoteApprovalRequest) = resolve(request, apiClient::approve)

    private fun deny(request: RemoteApprovalRequest) = resolve(request, apiClient::deny)

    private fun resolve(
        request: RemoteApprovalRequest,
        call: (String, Int, String, ByteArray, Long) -> Single<RemoteApprovalRequest>
    ) {
        val currentPairing = pairing ?: return
        val currentSecret = secret ?: return
        disposables.add(
            call(currentPairing.host, currentPairing.port, currentPairing.kidDeviceId, currentSecret, request.id)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { fetchOnce() },
                    { e ->
                        if (e is KidModeApiClient.KidModeApiException && e.status == 401) {
                            showRevokedDialog()
                        } else {
                            Toast.makeText(
                                this,
                                e.message ?: getString(R.string.parent_mode_connection_error, currentPairing.kidDeviceName),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
        )
    }

    companion object {
        const val EXTRA_PAIRING_UID = "parent_mode_pairing_uid"
        private const val AUTO_REFRESH_SECONDS = 3L
    }
}
