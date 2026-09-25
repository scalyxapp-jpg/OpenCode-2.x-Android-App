package com.opencode.android.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.opencode.android.data.BackendSession
import com.opencode.android.data.ProviderDirectory

/**
 * The process-wide backend connection, provided once at the app root
 * (`MainActivity`) so composables no longer reach for the legacy `ApiClient`
 * static facade. Read `LocalBackendSession.current.api` per call: the session
 * swaps its Retrofit instance when the backend URL changes.
 */
val LocalBackendSession = staticCompositionLocalOf<BackendSession> {
    error("LocalBackendSession was not provided; wrap the UI in CompositionLocalProvider")
}

/**
 * The process-wide provider catalog, provided once at the app root so
 * composables no longer reach for the legacy `ProviderCatalog` static facade.
 */
val LocalProviderDirectory = staticCompositionLocalOf<ProviderDirectory> {
    error("LocalProviderDirectory was not provided; wrap the UI in CompositionLocalProvider")
}
