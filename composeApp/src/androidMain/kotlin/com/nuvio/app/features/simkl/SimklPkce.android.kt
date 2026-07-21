package com.nuvio.app.features.simkl

import android.util.Base64
import java.security.MessageDigest

internal actual fun simklSha256Base64Url(value: String): String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
