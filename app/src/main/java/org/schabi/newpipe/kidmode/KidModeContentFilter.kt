/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import android.content.Context
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.kidmode.db.ChannelListStatus

/**
 * Hides blacklisted channels and individually-denied videos from feeds/search/related-videos, so
 * they don't just get blocked when tapped (see [KidModeGate]) but don't show up at all. Called
 * from the async chain that loads each screen's data (`BaseListInfoFragment`, `SearchFragment`,
 * `FeedDatabaseManager`) *before* the result reaches the main thread -- `NewPipeDatabase` is built
 * without `allowMainThreadQueries()`, so these blocking DAO reads must happen on the IO scheduler,
 * the same thread the network load itself already runs on, never inside a `handleResult`/
 * `handleNextItems` callback.
 *
 * A snapshot (one query per list, not one per item) is loaded once per fetch and matched against
 * in-memory, since the number of rules/denials a parent has actually set is expected to be tiny.
 */
object KidModeContentFilter {

    private class Snapshot(
        private val blacklistedChannels: Set<Pair<Int, String>>,
        private val deniedVideoUrls: Set<String>
    ) {
        fun isBlockedChannel(serviceId: Int, channelUrl: String?): Boolean = channelUrl != null && (serviceId to channelUrl) in blacklistedChannels

        fun isDeniedVideo(videoUrl: String): Boolean = videoUrl in deniedVideoUrls
    }

    /** Filters a list of extractor [InfoItem]s (streams and/or channels) for the given fragments. */
    fun <T : InfoItem> filterItems(context: Context, items: List<T>): List<T> {
        if (!KidModeGate(context).isEnabled()) {
            return items
        }
        val snapshot = loadSnapshot(context)
        return items.filterNot { isBlocked(it, snapshot) }
    }

    /** Filters the subscriptions feed's local cache -- covers a channel blacklisted after the kid was already subscribed. */
    fun filterStreamsWithState(context: Context, streams: List<StreamWithState>): List<StreamWithState> {
        if (!KidModeGate(context).isEnabled()) {
            return streams
        }
        val snapshot = loadSnapshot(context)
        return streams.filterNot { streamWithState ->
            val stream = streamWithState.stream
            snapshot.isBlockedChannel(stream.serviceId, stream.uploaderUrl) ||
                snapshot.isDeniedVideo(stream.url)
        }
    }

    private fun isBlocked(item: InfoItem, snapshot: Snapshot): Boolean {
        return when (item) {
            is StreamInfoItem -> snapshot.isBlockedChannel(item.serviceId, item.uploaderUrl) ||
                snapshot.isDeniedVideo(item.url)

            is ChannelInfoItem -> snapshot.isBlockedChannel(item.serviceId, item.url)

            else -> false
        }
    }

    private fun loadSnapshot(context: Context): Snapshot {
        val database = NewPipeDatabase.getInstance(context)
        val blacklisted = database.channelRuleDAO().getAllRules().blockingFirst()
            .filter { it.status == ChannelListStatus.BLACKLISTED }
            .map { it.serviceId to it.channelUrl }
            .toSet()
        val deniedVideoUrls = database.approvalRequestDAO().getDeniedVideoUrls().blockingFirst().toSet()
        return Snapshot(blacklisted, deniedVideoUrls)
    }
}
