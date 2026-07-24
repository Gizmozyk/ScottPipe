/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.parentmode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ItemParentModeHeaderBinding
import org.schabi.newpipe.databinding.ItemParentModeRowBinding
import org.schabi.newpipe.kidmode.db.ParentPairingEntity

sealed class ParentModeRow {
    data class Header(val titleResId: Int) : ParentModeRow()
    data class EmptyHint(val textResId: Int) : ParentModeRow()
    data class PairedDevice(val pairing: ParentPairingEntity) : ParentModeRow()
    data class DiscoveredDevice(val name: String, val host: String, val port: Int) : ParentModeRow()
    object ManualConnect : ParentModeRow()
}

/** Backs [ParentModeActivity]'s single list of section headers, paired devices, and discovered devices. */
class ParentModeDeviceAdapter(
    private val onPairedDeviceClick: (ParentPairingEntity) -> Unit,
    private val onDiscoveredDeviceClick: (ParentModeRow.DiscoveredDevice) -> Unit,
    private val onManualConnectClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<ParentModeRow> = emptyList()

    fun submitList(newRows: List<ParentModeRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ParentModeRow.Header -> VIEW_TYPE_HEADER
        else -> VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(ItemParentModeHeaderBinding.inflate(inflater, parent, false))
        } else {
            RowViewHolder(ItemParentModeRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ParentModeRow.Header -> (holder as HeaderViewHolder).bind(row)
            is ParentModeRow.EmptyHint -> (holder as RowViewHolder).bindEmptyHint(row)
            is ParentModeRow.PairedDevice -> (holder as RowViewHolder).bindPairedDevice(row.pairing)
            is ParentModeRow.DiscoveredDevice -> (holder as RowViewHolder).bindDiscoveredDevice(row)
            ParentModeRow.ManualConnect -> (holder as RowViewHolder).bindManualConnect()
        }
    }

    private class HeaderViewHolder(private val binding: ItemParentModeHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: ParentModeRow.Header) {
            binding.root.setText(header.titleResId)
        }
    }

    private inner class RowViewHolder(private val binding: ItemParentModeRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindEmptyHint(hint: ParentModeRow.EmptyHint) {
            binding.parentModeRowTitle.setText(hint.textResId)
            binding.parentModeRowSubtitle.visibility = View.GONE
            binding.root.setOnClickListener(null)
        }

        fun bindPairedDevice(pairing: ParentPairingEntity) {
            binding.parentModeRowTitle.text = pairing.kidDeviceName
            binding.parentModeRowSubtitle.text = "${pairing.host}:${pairing.port}"
            binding.parentModeRowSubtitle.visibility = View.VISIBLE
            binding.root.setOnClickListener { onPairedDeviceClick(pairing) }
        }

        fun bindDiscoveredDevice(device: ParentModeRow.DiscoveredDevice) {
            binding.parentModeRowTitle.text = device.name
            binding.parentModeRowSubtitle.text = "${device.host}:${device.port}"
            binding.parentModeRowSubtitle.visibility = View.VISIBLE
            binding.root.setOnClickListener { onDiscoveredDeviceClick(device) }
        }

        fun bindManualConnect() {
            binding.parentModeRowTitle.setText(R.string.parent_mode_connect_manually)
            binding.parentModeRowSubtitle.visibility = View.GONE
            binding.root.setOnClickListener { onManualConnectClick() }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ROW = 1
    }
}
