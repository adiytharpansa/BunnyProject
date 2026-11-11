package com.bunny.app.presentation.screens.kanal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.ChannelGroupEntity
import com.bunny.app.data.LibraryRepository
import com.bunny.app.data.SubscriptionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Kanal — daftar kanal yang dilanggani + grupnya (langganan lokal). */
@HiltViewModel
class KanalViewModel @Inject constructor(
    private val library: LibraryRepository,
) : ViewModel() {

    val subscriptions: StateFlow<List<SubscriptionEntity>?> = library.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val groups: StateFlow<List<ChannelGroupEntity>> = library.groups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createGroup(name: String) {
        viewModelScope.launch { library.createGroup(name) }
    }

    fun renameGroup(id: Long, name: String) {
        viewModelScope.launch { library.renameGroup(id, name) }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch { library.deleteGroup(id) }
    }

    fun setChannelGroup(channelId: String, groupId: Long?) {
        viewModelScope.launch { library.setChannelGroup(channelId, groupId) }
    }
}
