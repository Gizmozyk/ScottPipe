/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.parentmode

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ItemParentModeRequestBinding
import org.schabi.newpipe.kidmode.client.KidModeApiClient.RemoteApprovalRequest

class ParentModeRequestAdapter(
    private val onApprove: (RemoteApprovalRequest) -> Unit,
    private val onDeny: (RemoteApprovalRequest) -> Unit
) : RecyclerView.Adapter<ParentModeRequestAdapter.ViewHolder>() {

    private var requests: List<RemoteApprovalRequest> = emptyList()

    fun submitList(newRequests: List<RemoteApprovalRequest>) {
        requests = newRequests
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = requests.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemParentModeRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(requests[position])
    }

    inner class ViewHolder(private val binding: ItemParentModeRequestBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(request: RemoteApprovalRequest) {
            val context = binding.root.context
            binding.parentModeRequestTitle.text = request.title
            val typeLabel = when (request.type) {
                "SUBSCRIBE_CHANNEL" -> context.getString(R.string.parent_mode_request_type_subscribe)
                else -> context.getString(R.string.parent_mode_request_type_play)
            }
            binding.parentModeRequestSubtitle.text = request.channelUrl?.let { "$typeLabel — $it" } ?: typeLabel
            binding.parentModeRequestApprove.setOnClickListener { onApprove(request) }
            binding.parentModeRequestDeny.setOnClickListener { onDeny(request) }
        }
    }
}
