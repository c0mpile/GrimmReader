package com.c0mpile.grimmreader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.server.ServerRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.core.model.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val appearance: Appearance = Appearance(),
    val server: ServerEntity? = null,
    val status: ServerStatus = ServerStatus.ONLINE,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val prefs: AppPreferences,
        private val servers: ServerRepository,
        session: ServerSession,
    ) : ViewModel() {
        val state: StateFlow<SettingsUiState> =
            combine(prefs.appearance, session.server, session.status) { appearance, server, status ->
                SettingsUiState(appearance, server, status)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        fun setAppearance(appearance: Appearance) = viewModelScope.launch { prefs.setAppearance(appearance) }

        fun signOut() = viewModelScope.launch { servers.signOut() }

        fun removeServer(keepDownloads: Boolean) = viewModelScope.launch { servers.remove(keepDownloads) }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
