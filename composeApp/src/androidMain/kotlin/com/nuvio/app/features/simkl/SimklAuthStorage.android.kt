package com.nuvio.app.features.simkl

import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SimklAuthStorage {
    private const val preferencesName = "nuvio_simkl_auth"
    private const val payloadKey = "simkl_auth_payload"
    private var context: Context? = null
    fun initialize(applicationContext: Context) { context = applicationContext }
    actual fun loadPayload() = context?.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)?.getString(ProfileScopedKey.of(payloadKey), null)
    actual fun savePayload(payload: String) { context?.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)?.edit()?.putString(ProfileScopedKey.of(payloadKey), payload)?.apply() }
}
