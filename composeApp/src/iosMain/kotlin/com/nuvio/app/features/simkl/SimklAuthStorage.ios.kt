package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object SimklAuthStorage {
    private const val payloadKey = "simkl_auth_payload"
    actual fun loadPayload() = NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))
    actual fun savePayload(payload: String) { NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey)) }
}
