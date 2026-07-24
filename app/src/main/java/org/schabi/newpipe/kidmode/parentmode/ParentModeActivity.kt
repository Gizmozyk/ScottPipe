/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.parentmode

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityParentModeBinding
import org.schabi.newpipe.databinding.DialogEditTextBinding
import org.schabi.newpipe.databinding.DialogParentModeManualConnectBinding
import org.schabi.newpipe.kidmode.KidModePairingQrCode
import org.schabi.newpipe.kidmode.ParentPairingManager
import org.schabi.newpipe.kidmode.client.KidModeApiClient
import org.schabi.newpipe.kidmode.client.KidModeNsdDiscoverer
import org.schabi.newpipe.kidmode.db.ParentPairingEntity
import org.schabi.newpipe.util.ThemeHelper

/**
 * Entry point for "Parent Mode" (Settings → Kid mode → Parent Mode): discovers kid devices
 * advertising [org.schabi.newpipe.kidmode.server.ApprovalHttpServer] over NSD, lets the user pair
 * with one (or connect manually, since mDNS is commonly blocked by AP/client isolation on real
 * routers), and opens [ParentModeRequestsActivity] for an already-paired one.
 */
class ParentModeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParentModeBinding
    private lateinit var adapter: ParentModeDeviceAdapter

    private val disposables = CompositeDisposable()
    private val parentPairingManager by lazy { ParentPairingManager(this) }
    private val apiClient = KidModeApiClient()
    private val nsdDiscoverer by lazy { KidModeNsdDiscoverer(this) }

    private var pairedDevices: List<ParentPairingEntity> = emptyList()
    private val discoveredDevices = linkedMapOf<String, ParentModeRow.DiscoveredDevice>()

    // zxing-android-embedded's scan Activity requests the CAMERA runtime permission itself and
    // surfaces a denial as a null `contents`, same as a user-cancelled scan -- no separate
    // pre-flight permission request is needed here.
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val payload = KidModePairingQrCode.decode(contents)
        if (payload == null) {
            Toast.makeText(this, R.string.parent_mode_qr_invalid, Toast.LENGTH_SHORT).show()
        } else {
            pair(payload.host, payload.port, payload.host, payload.code)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityParentModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.parent_mode_title)
        }

        adapter = ParentModeDeviceAdapter(
            onPairedDeviceClick = ::openRequests,
            onDiscoveredDeviceClick = ::showPairDialog,
            onManualConnectClick = ::showManualConnectDialog,
            onScanQrCodeClick = ::startQrScan
        )
        binding.parentModeList.layoutManager = LinearLayoutManager(this)
        binding.parentModeList.adapter = adapter
        rebuildRows()
    }

    override fun onStart() {
        super.onStart()
        disposables.add(
            parentPairingManager.getAll()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { devices ->
                    pairedDevices = devices
                    rebuildRows()
                }
        )
        nsdDiscoverer.start(object : KidModeNsdDiscoverer.Callback {
            override fun onDeviceFound(name: String, host: String, port: Int) {
                runOnUiThread {
                    discoveredDevices[name] = ParentModeRow.DiscoveredDevice(name, host, port)
                    rebuildRows()
                }
            }

            override fun onDeviceLost(name: String) {
                runOnUiThread {
                    discoveredDevices.remove(name)
                    rebuildRows()
                }
            }
        })
    }

    override fun onStop() {
        super.onStop()
        nsdDiscoverer.stop()
        disposables.clear()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun rebuildRows() {
        val rows = mutableListOf<ParentModeRow>()
        rows += ParentModeRow.Header(R.string.parent_mode_paired_devices_header)
        if (pairedDevices.isEmpty()) {
            rows += ParentModeRow.EmptyHint(R.string.parent_mode_no_paired_devices)
        } else {
            rows += pairedDevices.map { ParentModeRow.PairedDevice(it) }
        }
        rows += ParentModeRow.Header(R.string.parent_mode_discovered_devices_header)
        rows += ParentModeRow.ManualConnect
        // Camera-less devices (Android TV, some tablets) never see this row -- manual connect
        // stays unconditional as their only option, same as it always has been.
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            rows += ParentModeRow.ScanQrCode
        }
        if (discoveredDevices.isEmpty()) {
            rows += ParentModeRow.EmptyHint(R.string.parent_mode_no_discovered_devices)
        } else {
            rows += discoveredDevices.values.toList()
        }
        adapter.submitList(rows)
    }

    private fun openRequests(pairing: ParentPairingEntity) {
        startActivity(
            Intent(this, ParentModeRequestsActivity::class.java)
                .putExtra(ParentModeRequestsActivity.EXTRA_PAIRING_UID, pairing.uid)
        )
    }

    private fun showPairDialog(device: ParentModeRow.DiscoveredDevice) {
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater)
        dialogBinding.root.context.setTheme(ThemeHelper.getDialogTheme(this))
        dialogBinding.dialogEditText.hint = getString(R.string.parent_mode_pair_dialog_code_hint)
        dialogBinding.dialogEditText.inputType = InputType.TYPE_CLASS_NUMBER

        AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
            .setTitle(getString(R.string.parent_mode_pair_dialog_title, device.name))
            .setView(dialogBinding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                pair(device.host, device.port, device.name, dialogBinding.dialogEditText.text.toString())
            }
            .show()
    }

    private fun showManualConnectDialog() {
        val dialogBinding = DialogParentModeManualConnectBinding.inflate(layoutInflater)
        dialogBinding.root.context.setTheme(ThemeHelper.getDialogTheme(this))

        AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
            .setTitle(R.string.parent_mode_manual_connect_title)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val host = dialogBinding.manualConnectHost.text.toString()
                val port = dialogBinding.manualConnectPort.text.toString().toIntOrNull()
                val code = dialogBinding.manualConnectCode.text.toString()
                if (port == null) {
                    Toast.makeText(this, R.string.parent_mode_invalid_port, Toast.LENGTH_SHORT).show()
                } else {
                    // No discovered name to use here, so fall back to the address itself.
                    pair(host, port, host, code)
                }
            }
            .show()
    }

    private fun startQrScan() {
        qrScanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.parent_mode_scan_qr_prompt))
                .setBeepEnabled(false)
        )
    }

    private fun pair(host: String, port: Int, kidDeviceName: String, code: String) {
        disposables.add(
            apiClient.pair(host, port, Build.MODEL, code)
                .flatMapCompletable { result ->
                    Completable.fromAction {
                        parentPairingManager.save(
                            result.deviceId,
                            kidDeviceName,
                            host,
                            port,
                            Base64.decode(result.sharedSecretBase64, Base64.NO_WRAP)
                        )
                    }.subscribeOn(Schedulers.io())
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { Toast.makeText(this, getString(R.string.parent_mode_paired_toast, kidDeviceName), Toast.LENGTH_SHORT).show() },
                    { e -> Toast.makeText(this, getString(R.string.parent_mode_pairing_failed, e.message ?: e.toString()), Toast.LENGTH_SHORT).show() }
                )
        )
    }
}
