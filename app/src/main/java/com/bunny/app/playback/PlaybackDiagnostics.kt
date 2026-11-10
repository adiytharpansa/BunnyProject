package com.bunny.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small ring of recent playback events, shown on the player screen and
 * attached to error states. Written for Fase 2 after Fase 1's field lesson:
 * a bare "playback failed" is undiagnosable from a user screenshot, while
 * the persona, the chosen source and the player's own error name are not.
 * Original to Bunny — the *idea* is the lesson carried over from CLEAR's
 * crash work, the code is new.
 */
object PlaybackDiagnostics {

    private const val MAX_EVENTS = 60
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    fun log(event: String) {
        val line = "${timeFormat.format(Date())}  $event"
        _events.value = (_events.value + line).takeLast(MAX_EVENTS)
    }

    fun clear() {
        _events.value = emptyList()
    }
}
