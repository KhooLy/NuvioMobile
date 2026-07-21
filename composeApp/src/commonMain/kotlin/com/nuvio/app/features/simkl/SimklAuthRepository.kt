package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import com.nuvio.app.features.library.LibraryItem

object SimklAuthRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loaded = false
    private var state = SimklAuthState()
    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()
    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    fun ensureLoaded() { if (!loaded) load() }
    fun hasRequiredCredentials() = SimklConfig.CLIENT_ID.isNotBlank()
    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) { publish(error = "SIMKL_CLIENT_ID is missing from local.properties."); return null }
        val verifier = randomToken(64)
        val oauthState = randomToken(32)
        state = state.copy(pendingState = oauthState, pendingVerifier = verifier)
        save(); publish(status = "Finish SIMKL sign-in in your browser.")
        return "https://simkl.com/oauth/authorize?client_id=${SimklConfig.CLIENT_ID.encodeURLParameter()}&redirect_uri=${SimklConfig.REDIRECT_URI.encodeURLParameter()}&response_type=code&code_challenge=${simklSha256Base64Url(verifier).encodeURLParameter()}&code_challenge_method=S256&state=${oauthState.encodeURLParameter()}"
    }
    fun onAuthCallbackReceived(url: String) {
        ensureLoaded()
        if (!url.startsWith("${SimklConfig.REDIRECT_URI}?", true)) return
        scope.launch { complete(url) }
    }
    fun onDisconnectRequested() { ensureLoaded(); state = SimklAuthState(); save(); publish(status = "SIMKL disconnected.") }
    fun clearLocalState() { loaded = false; state = SimklAuthState(); SimklAuthStorage.savePayload(""); publish() }
    suspend fun authorizedHeaders(): Map<String, String>? {
        ensureLoaded()
        val token = state.accessToken?.takeIf(String::isNotBlank) ?: return null
        return mapOf("Authorization" to "Bearer $token", "User-Agent" to "Nuvio/1.0", "Content-Type" to "application/json")
    }
    internal fun librarySnapshot(): Pair<String?, List<LibraryItem>> { ensureLoaded(); return state.librarySyncCursor to state.libraryItems }
    internal fun saveLibrarySnapshot(cursor: String?, items: List<LibraryItem>) { ensureLoaded(); state = state.copy(librarySyncCursor = cursor, libraryItems = items); save() }
    private suspend fun complete(url: String) {
        val callback = runCatching { Url(url) }.getOrNull()
        val code = callback?.parameters?.get("code")
        val verifier = state.pendingVerifier
        if (code.isNullOrBlank() || verifier.isNullOrBlank() || callback?.parameters?.get("state") != state.pendingState) { state = state.copy(pendingState = null, pendingVerifier = null); save(); publish(error = "SIMKL authorization was not valid."); return }
        publish(loading = true)
        val body = json.encodeToString(TokenRequest(SimklConfig.CLIENT_ID, code, SimklConfig.REDIRECT_URI, verifier))
        val token = runCatching { httpPostJsonWithHeaders("https://api.simkl.com/oauth/token", body, mapOf("User-Agent" to "Nuvio/1.0")) }
            .getOrNull()?.let { runCatching { json.decodeFromString<TokenResponse>(it) }.getOrNull()?.accessToken }
        if (token.isNullOrBlank()) { state = state.copy(pendingState = null, pendingVerifier = null); save(); publish(error = "SIMKL sign-in failed."); return }
        state = state.copy(accessToken = token, pendingState = null, pendingVerifier = null); save(); publish(status = "SIMKL connected.")
    }
    private fun load() { loaded = true; state = SimklAuthStorage.loadPayload()?.let { runCatching { json.decodeFromString<SimklAuthState>(it) }.getOrNull() } ?: SimklAuthState(); publish() }
    private fun save() = SimklAuthStorage.savePayload(json.encodeToString(state))
    private fun publish(loading: Boolean = false, status: String? = null, error: String? = null) { val mode = when { state.isAuthenticated -> SimklConnectionMode.CONNECTED; state.pendingState != null -> SimklConnectionMode.AWAITING_APPROVAL; else -> SimklConnectionMode.DISCONNECTED }; _isAuthenticated.value = state.isAuthenticated; _uiState.value = SimklAuthUiState(mode, hasRequiredCredentials(), loading, status, error) }
    private fun randomToken(size: Int): String { val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"; return buildString(size) { repeat(size) { append(chars[Random.nextInt(chars.length)]) } } }
}

fun handleSimklAuthCallbackUrl(url: String) = SimklAuthRepository.onAuthCallbackReceived(url)

@Serializable private data class TokenRequest(@SerialName("client_id") val clientId: String, val code: String, @SerialName("redirect_uri") val redirectUri: String, @SerialName("code_verifier") val codeVerifier: String, @SerialName("grant_type") val grantType: String = "authorization_code")
@Serializable private data class TokenResponse(@SerialName("access_token") val accessToken: String? = null)
