package com.bunny.app.data

import android.content.Context
import com.bunny.core.backend.ExtractionBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fase 7-C — the bell's memory: which notification items the user
 * has already seen. The seen set lives in plain preferences (ids
 * only, newest last, capped) and never leaves the device.
 */
@Singleton
class NotificationSeenStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun seenIds(): Set<String> = seenList().toSet()

    private fun seenList(): List<String> =
        prefs.getString(KEY_SEEN, null)
            ?.split("\n")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun markSeen(ids: Collection<String>) {
        val merged = (seenList() + ids).distinct().takeLast(MAX_KEPT)
        prefs.edit().putString(KEY_SEEN, merged.joinToString("\n")).apply()
    }

    private companion object {
        const val PREFS_NAME = "bunny_notif_seen"
        const val KEY_SEEN = "seen_ids"
        const val MAX_KEPT = 200
    }
}

/**
 * Fase 7-C — whether the bell should wear its unread dot.
 *
 * Bunny has no server, so "unread" is computed the local way: one
 * light pass mirroring the Notifications page's own fetch (the
 * newest upload of each subscribed channel) compared against the
 * seen set. Refreshed once when the app starts; opening the
 * Notifications page marks what it showed as seen and clears the
 * dot. A failed pass leaves the dot as it was — no invented state.
 */
@Singleton
class NotificationsBadge @Inject constructor(
    private val backend: ExtractionBackend,
    private val library: LibraryRepository,
    private val seenStore: NotificationSeenStore,
) {
    private val _hasUnread = MutableStateFlow(false)
    val hasUnread: StateFlow<Boolean> = _hasUnread.asStateFlow()

    suspend fun refresh() {
        try {
            val subscriptions = library.subscriptions.first().take(MAX_CHANNELS)
            if (subscriptions.isEmpty()) {
                _hasUnread.value = false
                return
            }
            val seen = seenStore.seenIds()
            val latestIds = coroutineScope {
                subscriptions.map { subscription ->
                    async {
                        try {
                            backend.getChannelVideos(subscription.channelId)
                                .items.firstOrNull()?.id
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            _hasUnread.value = latestIds.any { it !in seen }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The badge keeps its previous state; a network failure
            // must not invent an unread dot or clear a real one.
        }
    }

    /** The Notifications page opened: what it shows counts as seen. */
    fun noteOpened(currentIds: List<String>) {
        seenStore.markSeen(currentIds)
        _hasUnread.value = false
    }

    private companion object {
        const val MAX_CHANNELS = 15
    }
}
