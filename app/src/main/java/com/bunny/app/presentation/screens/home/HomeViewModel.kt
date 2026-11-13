package com.bunny.app.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bunny.app.data.AccountTasteRepository
import com.bunny.app.data.AuthRepository
import com.bunny.app.data.DesktopAuth
import com.bunny.app.data.LibraryRepository
import com.bunny.app.data.PersonalDataRepository
import com.bunny.app.data.YouTubeDataApi
import com.bunny.app.data.FeedbackKind
import com.bunny.app.data.SurveyPrompt
import com.bunny.app.data.TasteRepository
import com.bunny.app.playback.FeedPreviewManager
import com.bunny.app.playback.PreviewStatus
import com.bunny.app.presentation.components.ThumbnailPreloader
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoItem
import com.bunny.core.rank.FeedRanker
import com.bunny.core.rank.TasteProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val library: LibraryRepository,
    private val auth: AuthRepository,
    private val previewManager: FeedPreviewManager,
    private val thumbnails: ThumbnailPreloader,
    private val desktopAuth: DesktopAuth,
    private val dataApi: YouTubeDataApi,
    private val personal: PersonalDataRepository,
    private val accountTasteRepo: AccountTasteRepository,
    private val tasteRepo: TasteRepository,
    private val sabrCoordinator: com.bunny.engine.lontar.sabr.SabrPlaybackCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(backendId = backend.id))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Jatah bar state: estimated month usage + the user's budget. */
    val jatah: StateFlow<JatahState> = combine(
        library.quotaBytes,
        library.monthUsageBytes(),
    ) { quota, used -> JatahState(usedBytes = used, quotaBytes = quota) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JatahState())

    fun setQuota(bytes: Long?) {
        viewModelScope.launch { library.setQuotaBytes(bytes) }
    }

    // ------------------------------------------------ survei kepuasan

    private val _survey = MutableStateFlow<SurveyPrompt?>(null)

    /** "Sepadan dengan waktu Anda?" tentang tontonan terakhir; null bila tak ada. */
    val survey: StateFlow<SurveyPrompt?> = _survey.asStateFlow()

    /** [worthIt]: true = sepadan, false = kurang, null = dilewati. */
    fun answerSurvey(worthIt: Boolean?) {
        val prompt = _survey.value ?: return
        _survey.value = null
        viewModelScope.launch { tasteRepo.answerSurvey(prompt, worthIt) }
    }

    // ------------------------------------------------ umpan balik kartu

    /** ⋮ → "Tidak tertarik": kartu hilang seketika dan tak akan muncul lagi. */
    fun notInterested(item: VideoItem) {
        removeFromFeed { it.id == item.id }
        viewModelScope.launch {
            tasteRepo.recordFeedback(FeedbackKind.NOT_INTERESTED, item)
        }
    }

    /** ⋮ → "Jangan rekomendasikan channel": semua video channel itu hilang. */
    fun dontRecommendChannel(item: VideoItem) {
        removeFromFeed { candidate ->
            (item.channelId != null && candidate.channelId == item.channelId) ||
                (item.channelName.isNotEmpty() && candidate.channelName == item.channelName)
        }
        viewModelScope.launch {
            tasteRepo.recordFeedback(FeedbackKind.DONT_RECOMMEND_CHANNEL, item)
        }
    }

    private fun removeFromFeed(predicate: (VideoItem) -> Boolean) {
        _uiState.update { it.copy(items = it.items.filterNot(predicate)) }
    }

    // ------------------------------------------------------ pratinjau

    private val _previewingId = MutableStateFlow<String?>(null)

    /** The video whose card currently shows an inline preview. */
    val previewingId: StateFlow<String?> = _previewingId.asStateFlow()

    /**
     * First-frame / aspect / failure state of the running preview. The
     * card keeps its thumbnail until the first frame is rendered.
     */
    val previewStatus: StateFlow<PreviewStatus> = previewManager.status

    /** Attach point for the preview surface in the previewing card. */
    val previewPlayer: ExoPlayer get() = previewManager.player

    private var previewJob: Job? = null

    /**
     * Videos whose preview already failed in this session (no playable
     * source, or every source errored). They keep their thumbnail instead
     * of being retried on every scroll.
     */
    private val failedPreviewIds = mutableSetOf<String>()

    fun startPreview(videoId: String) {
        if (_previewingId.value == videoId || videoId in failedPreviewIds) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                val streams = backend.getPlaybackStreams(videoId)
                // No suspension point between start() and the id update, so
                // a cancel can never land between them.
                if (previewManager.start(streams)) {
                    _previewingId.value = videoId
                    // Fase 8 mechanism 14: a card whose preview is
                    // actually playing is the strongest open-candidate
                    // there is — warm its SABR offer on the response
                    // this preview just fetched (metadata only).
                    sabrCoordinator.warm(videoId)
                } else {
                    // Nothing playable: never mark the card as previewing,
                    // or it would show a black surface forever.
                    failedPreviewIds += videoId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Previews are decoration: a failure stays silent and
                // the card simply keeps its thumbnail.
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        _previewingId.value = null
        previewManager.stop()
    }

    /** Ikon speaker pada kartu: nyalakan atau matikan suara cuplikan yang sedang berjalan. */
    fun setPreviewSound(enabled: Boolean) {
        previewManager.setSoundEnabled(enabled)
    }

    override fun onCleared() {
        previewManager.stop()
        super.onCleared()
    }

    init {
        // A preview whose every source failed returns its card to the
        // thumbnail and is not retried this session.
        viewModelScope.launch {
            previewManager.status.collect { status ->
                if (status.failed) {
                    _previewingId.value?.let { failedPreviewIds += it }
                    stopPreview()
                }
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                _survey.value = tasteRepo.pendingSurvey()
                val status = backend.healthCheck()
                _uiState.update { it.copy(backendStatus = status) }
                // Home is ALWAYS the locally composed mixture,
                // ranked by Mesin Selera — signed in or not. The
                // Fase 5 server-home branch died with the cookie
                // door (its engine session no longer exists, so it
                // could only ever answer empty and strand signed-in
                // users on an error); removed 2026-11-12.
                val page = backend.getHomeFeed(page = null)
                // Selera Akun: while signed in, quietly refresh the
                // account taste profile (at most once a day, rebuilt
                // on-device from the account's subscriptions and
                // likes) and let it enrich the candidate pool with
                // the subscriptions' newest uploads. Enrichment
                // only — a failure here never fails Home.
                var accountTaste: AccountTasteRepository.AccountTaste? =
                    null
                if (auth.isSignedIn) {
                    try {
                        if (desktopAuth.ensureFresh(null)) {
                            val token = auth.dataToken
                            if (token != null) {
                                accountTaste =
                                    accountTasteRepo.refreshIfStale(
                                        dataApi,
                                        token,
                                    )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // fall through with whatever profile exists
                    }
                    if (accountTaste == null) {
                        accountTaste = accountTasteRepo.profile()
                    }
                }
                var candidates = page.items
                if (accountTaste != null) {
                    try {
                        val fromSubs = personal.latestFeed()
                        if (fromSubs.isNotEmpty()) {
                            val known = candidates.map { it.id }.toSet()
                            candidates = candidates + fromSubs
                                .filter { it.id !in known }
                                .map {
                                    it.copy(reason = "Dari langganan Anda")
                                }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // pool enrichment only; the mixture stands
                    }
                }
                // Fase 7-B (Selera Tamu langkah 4): the watch-next
                // chain. YouTube's own list adapts to what was just
                // watched — Bunny walks that list from the most
                // recent local watches, one-two steps out, and the
                // newcomers join the pool carrying an honest chip.
                // Applies to guest and signed-in alike; enrichment
                // only — a failed walk never fails Home.
                try {
                    val seeds = library.recentOpenedVideoIds(CHAIN_SEED_COUNT)
                    if (seeds.isNotEmpty()) {
                        val known = candidates.map { it.id }.toMutableSet()
                        known += seeds
                        val chain = mutableListOf<VideoItem>()
                        var frontier = seeds
                        var step = 0
                        while (step < CHAIN_STEPS && frontier.isNotEmpty()) {
                            val level = coroutineScope {
                                frontier.map { seed ->
                                    async {
                                        try {
                                            backend.getRelatedVideos(seed)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            emptyList()
                                        }
                                    }
                                }.awaitAll()
                            }
                            val nextFrontier = mutableListOf<String>()
                            for (related in level) {
                                for (item in related) {
                                    if (item.isLive || item.id in known) continue
                                    if (chain.size >= CHAIN_MAX_ITEMS) break
                                    known += item.id
                                    chain += item.copy(reason = CHAIN_REASON)
                                    nextFrontier += item.id
                                }
                            }
                            frontier = nextFrontier.take(CHAIN_SEED_COUNT)
                            step++
                        }
                        candidates = candidates + chain
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // pool enrichment only; the mixture stands
                }
                // Mesin Selera: the mixture is candidate generation;
                // this ranking pass is Bunny's local second stage,
                // fed by a one-shot snapshot of the on-device taste
                // profile (subscriptions, opened channels, searches)
                // plus, while signed in, the account's own taste.
                val taste = TasteProfile(
                    subscribedChannelIds = library.subscriptions.first()
                        .map { it.channelId }
                        .toSet(),
                    channelAffinity = library.channelAffinity.first(),
                    searchTerms = library.recentSearches.first()
                        .flatMap { query -> query.lowercase().split(Regex("\\s+")) }
                        .filter { it.length >= 3 }
                        .toSet(),
                    accountTerms = accountTaste?.terms ?: emptyMap(),
                    accountChannelIds = accountTaste?.let {
                        it.subscribedChannelIds + it.likedChannelIds
                    } ?: emptySet(),
                    // Aturan churn dari model resmi YouTube: yang sudah
                    // tampil di Beranda muatan sebelumnya tapi tak pernah
                    // dibuka turun peringkatnya di muatan ini.
                    demotedIds = library.feedImpressions.first().toSet() -
                        library.openedVideoIds.first().toSet(),
                )
                // Mesin Selera tahap 2: sinyal waktu tonton, umpan balik, dan
                // peluruhan waktu dari TasteRepository melengkapi profil dasar.
                // Benih eksplorasi berbeda tiap muatan, jadi Tarik-untuk-segarkan
                // memberi sedikit kejutan seperti beranda YouTube.
                val enriched = tasteRepo.enrich(taste)
                val ranked = FeedRanker.rank(
                    candidates,
                    enriched,
                    explorationSeed = System.currentTimeMillis(),
                )
                // Chip Alasan for the account's hand in the order: a
                // title matching one of the account's own interest
                // words says so. Subscription-pool items already
                // carry their chip.
                val annotated = if (
                    accountTaste != null && accountTaste.terms.isNotEmpty()
                ) {
                    val byWeight = accountTaste.terms.entries
                        .sortedByDescending { it.value }
                    ranked.map { item ->
                        if (item.reason == "Dari langganan Anda" ||
                            item.reason == CHAIN_REASON
                        ) {
                            item
                        } else {
                            val hit = byWeight.firstOrNull {
                                item.title.lowercase().contains(it.key)
                            }
                            if (hit != null) {
                                item.copy(
                                    reason = "Sesuai minat Anda: ${hit.key}",
                                )
                            } else {
                                item
                            }
                        }
                    }
                } else {
                    ranked
                }
                // Tunggu thumbnail layar pertama (paralel) lalu tampilkan
                // semuanya bersamaan; skeleton / indikator refresh tetap
                // tampil selama menunggu.
                thumbnails.preload(annotated)
                _uiState.update {
                    it.copy(isLoading = false, items = annotated)
                }
                // Catat tayangan muatan ini untuk aturan churn berikutnya
                // (sesudah peringkat dihitung, supaya daftar ini tidak
                // menghukum dirinya sendiri).
                library.recordFeedImpressions(annotated.map { it.id })
            } catch (e: CancellationException) {
                // Cancellation is control flow, never a feed error.
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    companion object {
        /** Fase 7-B: how many recent watches seed the chain walk. */
        private const val CHAIN_SEED_COUNT = 3

        /** Fase 7-B: how many watch-next hops out the walk goes. */
        private const val CHAIN_STEPS = 2

        /** Fase 7-B: the chain's pool budget for one Home load. */
        private const val CHAIN_MAX_ITEMS = 24

        /** Fase 7-B: the honest chip chain items carry. */
        private const val CHAIN_REASON = "Dari tontonan terakhir"
    }
}
