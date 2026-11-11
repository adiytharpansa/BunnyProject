package com.bunny.app.presentation.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.playback.GuestWatchReporter
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.GuestWarmingReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Selera Tamu (Langkah 2): the warming probe's bridge. One tap
 * asks the engine for the two oracles — the guest home browse and
 * the guest history browse, both under the persistent visitor —
 * and the panel shows the counts exactly as answered. The
 * reporter's own counter rides along so "reports sent" and
 * "watches recorded" are never confused for each other.
 */
@HiltViewModel
class GuestWarmingViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    reporter: GuestWatchReporter,
) : ViewModel() {

    private val _report = MutableStateFlow<GuestWarmingReport?>(null)
    val report: StateFlow<GuestWarmingReport?> = _report.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val sentCount: StateFlow<Int> = reporter.sentCount

    fun runProbe() {
        if (_running.value) return
        _running.value = true
        viewModelScope.launch {
            try {
                _report.value = backend.probeGuestWarming()
            } finally {
                _running.value = false
            }
        }
    }
}
