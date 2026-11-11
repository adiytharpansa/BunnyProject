package com.bunny.app.presentation.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.PersonaHealthReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Panel Kesehatan Mesin (Lontar Hidup): the Radar's per-persona
 * verdicts, measured at the byte layer on this device and network.
 * Opening the profile shows the last verdicts; the check itself runs
 * on the user's tap (it costs real data, so it is never silent).
 */
@HiltViewModel
class EngineHealthViewModel @Inject constructor(
    private val backend: ExtractionBackend,
) : ViewModel() {

    data class State(
        val reports: List<PersonaHealthReport> = emptyList(),
        val isChecking: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snapshot = try {
                backend.engineHealth()
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(reports = snapshot) }
        }
    }

    fun check() {
        if (_state.value.isChecking) return
        viewModelScope.launch {
            _state.update { it.copy(isChecking = true) }
            val reports = try {
                backend.runEngineHealthCheck()
            } catch (e: Exception) {
                try {
                    backend.engineHealth()
                } catch (e2: Exception) {
                    emptyList()
                }
            }
            _state.update { State(reports = reports, isChecking = false) }
        }
    }
}
