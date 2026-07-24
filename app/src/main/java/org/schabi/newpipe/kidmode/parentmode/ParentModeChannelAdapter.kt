/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.parentmode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ItemParentModeRowBinding
import org.schabi.newpipe.kidmode.client.KidModeApiClient.RemoteChannelRule

sealed class ChannelRuleRow {
    object AddChannel : ChannelRuleRow()
    object EmptyHint : ChannelRuleRow()
    data class Rule(val rule: RemoteChannelRule) : ChannelRuleRow()
}

/** Backs [ParentModeChannelsActivity]'s single list of channel rules plus the "Add channel" action. */
class ParentModeChannelAdapter(
    private val onAddChannelClick: () -> Unit,
    private val onRuleClick: (RemoteChannelRule) -> Unit
) : RecyclerView.Adapter<ParentModeChannelAdapter.RowViewHolder>() {

    private var rows: List<ChannelRuleRow> = listOf(ChannelRuleRow.AddChannel, ChannelRuleRow.EmptyHint)

    fun submitList(rules: List<RemoteChannelRule>) {
        rows = listOf(ChannelRuleRow.AddChannel) +
            if (rules.isEmpty()) listOf(ChannelRuleRow.EmptyHint) else rules.map { ChannelRuleRow.Rule(it) }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val binding = ItemParentModeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        when (val row = rows[position]) {
            ChannelRuleRow.AddChannel -> holder.bindAddChannel(onAddChannelClick)
            ChannelRuleRow.EmptyHint -> holder.bindEmptyHint()
            is ChannelRuleRow.Rule -> holder.bindRule(row.rule, onRuleClick)
        }
    }

    class RowViewHolder(private val binding: ItemParentModeRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindAddChannel(onClick: () -> Unit) {
            binding.parentModeRowTitle.setText(R.string.parent_mode_add_channel)
            binding.parentModeRowSubtitle.visibility = View.GONE
            binding.root.setOnClickListener { onClick() }
        }

        fun bindEmptyHint() {
            binding.parentModeRowTitle.setText(R.string.parent_mode_no_channel_rules)
            binding.parentModeRowSubtitle.visibility = View.GONE
            binding.root.setOnClickListener(null)
        }

        fun bindRule(rule: RemoteChannelRule, onClick: (RemoteChannelRule) -> Unit) {
            binding.parentModeRowTitle.text = rule.channelUrl
            binding.parentModeRowSubtitle.text = rule.status
            binding.parentModeRowSubtitle.visibility = View.VISIBLE
            binding.root.setOnClickListener { onClick(rule) }
        }
    }
}
