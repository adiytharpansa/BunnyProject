package com.bunny.app.presentation.screens.kanal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.LibraryRepository
import com.bunny.app.data.SubscriptionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Kanal — daftar kanal yang dilanggani (sumber: langganan lokal). */
@HiltViewModel
class KanalViewModel @Inject constructor(
    library: LibraryRepository,
) : ViewModel() {
    val subscriptions: StateFlow<List<SubscriptionEntity>?> = library.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
