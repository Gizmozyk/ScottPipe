/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.parentmode

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityParentModeChannelsBinding
import org.schabi.newpipe.databinding.DialogEditTextBinding
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.kidmode.ParentPairingManager
import org.schabi.newpipe.kidmode.client.KidModeApiClient
import org.schabi.newpipe.kidmode.client.KidModeApiClient.RemoteChannelRule
import org.schabi.newpipe.kidmode.db.ParentPairingEntity
import org.schabi.newpipe.util.ThemeHelper

/**
 * Lets a parent proactively whitelist/blacklist a channel for a paired kid device, before the kid
 * ever asks (see `wiki/features/kid-mode.md`'s Phase E section) -- reached from
 * [ParentModeRequestsActivity]'s "Manage channels" menu action.
 */
class ParentModeChannelsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParentModeChannelsBinding
    private lateinit var adapter: ParentModeChannelAdapter

    private val disposables = CompositeDisposable()
    private val parentPairingManager by lazy { ParentPairingManager(this) }
    private val apiClient = KidModeApiClient()

    private var pairingUid: Long = -1L
    private var pairing: ParentPairingEntity? = null
    private var secret: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)

        pairingUid = intent.getLongExtra(EXTRA_PAIRING_UID, -1L)
        if (pairingUid == -1L) {
            finish()
            return
        }

        binding = ActivityParentModeChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.parent_mode_channels_title)
        }

        adapter = ParentModeChannelAdapter(
            onAddChannelClick = ::showAddChannelDialog,
            onRuleClick = ::confirmRemoveRule
        )
        binding.parentModeChannelsList.layoutManager = LinearLayoutManager(this)
        binding.parentModeChannelsList.adapter = adapter
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
                        fetchRules()
                    },
                    { finish() }
                )
        )
    }

    override fun onStop() {
        super.onStop()
        disposables.clear()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
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

    private fun fetchRules() {
        val currentPairing = pairing ?: return
        val currentSecret = secret ?: return
        disposables.add(
            apiClient.channelRules(currentPairing.host, currentPairing.port, currentPairing.kidDeviceId, currentSecret)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ rules -> adapter.submitList(rules) }, ::handleError)
        )
    }

    private fun handleError(e: Throwable) {
        if (e is KidModeApiClient.KidModeApiException && e.status == 401) {
            Toast.makeText(this, R.string.parent_mode_revoked_message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(
            this,
            e.message ?: getString(R.string.parent_mode_connection_error, pairing?.kidDeviceName ?: ""),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showAddChannelDialog() {
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater)
        dialogBinding.root.context.setTheme(ThemeHelper.getDialogTheme(this))
        dialogBinding.dialogEditText.hint = getString(R.string.parent_mode_add_channel_url_hint)

        AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
            .setTitle(R.string.parent_mode_add_channel_dialog_title)
            .setView(dialogBinding.root)
            .setNeutralButton(R.string.cancel, null)
            .setNegativeButton(R.string.parent_mode_blacklist) { _, _ ->
                setRule(dialogBinding.dialogEditText.text.toString(), "BLACKLISTED")
            }
            .setPositiveButton(R.string.parent_mode_whitelist) { _, _ ->
                setRule(dialogBinding.dialogEditText.text.toString(), "WHITELISTED")
            }
            .show()
    }

    private fun setRule(channelUrl: String, status: String) {
        val currentPairing = pairing ?: return
        val currentSecret = secret ?: return

        val serviceId = try {
            NewPipe.getServiceByUrl(channelUrl).serviceId
        } catch (e: Exception) {
            Toast.makeText(this, R.string.parent_mode_invalid_channel_url, Toast.LENGTH_SHORT).show()
            return
        }

        disposables.add(
            apiClient.setChannelRule(
                currentPairing.host,
                currentPairing.port,
                currentPairing.kidDeviceId,
                currentSecret,
                serviceId,
                channelUrl,
                status
            )
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ fetchRules() }, ::handleError)
        )
    }

    private fun confirmRemoveRule(rule: RemoteChannelRule) {
        AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
            .setTitle(R.string.parent_mode_remove_channel_rule_title)
            .setMessage(getString(R.string.parent_mode_remove_channel_rule_message, rule.channelUrl))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> removeRule(rule) }
            .show()
    }

    private fun removeRule(rule: RemoteChannelRule) {
        val currentPairing = pairing ?: return
        val currentSecret = secret ?: return
        disposables.add(
            apiClient.unlistChannel(
                currentPairing.host,
                currentPairing.port,
                currentPairing.kidDeviceId,
                currentSecret,
                rule.serviceId,
                rule.channelUrl
            )
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ fetchRules() }, ::handleError)
        )
    }

    companion object {
        const val EXTRA_PAIRING_UID = "parent_mode_channels_pairing_uid"
    }
}
