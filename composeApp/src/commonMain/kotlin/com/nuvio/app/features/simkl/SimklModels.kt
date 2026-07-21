package com.nuvio.app.features.simkl

import kotlinx.serialization.Serializable
import com.nuvio.app.features.library.LibraryItem

@Serializable
internal data class SimklAuthState(
    val accessToken: String? = null,
    val pendingState: String? = null,
    val pendingVerifier: String? = null,
    val librarySyncCursor: String? = null,
    val libraryItems: List<LibraryItem> = emptyList(),
) { val isAuthenticated get() = !accessToken.isNullOrBlank() }

enum class SimklConnectionMode { DISCONNECTED, AWAITING_APPROVAL, CONNECTED }

data class SimklAuthUiState(
    val mode: SimklConnectionMode = SimklConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)
