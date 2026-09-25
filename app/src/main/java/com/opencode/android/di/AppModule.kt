package com.opencode.android.di

import com.opencode.android.data.BackendSession
import com.opencode.android.data.ChatRepository
import com.opencode.android.data.MessageCache
import com.opencode.android.data.MessageStore
import com.opencode.android.data.OpenCodeApi
import com.opencode.android.data.ProviderDirectory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Bindings for the data layer.
 *
 * The stores are still the existing `object` singletons (their `init(context)`
 * runs from `Application.onCreate`), but they are now reached through the
 * container rather than by name, so a test can supply a fake by installing a
 * module that overrides these providers.
 *
 * [BackendSession] and [ProviderDirectory] are created here and only here:
 * Hilt owns the single process-wide instance, and the UI reads it through the
 * composition locals provided at the app root. The legacy `shared()` accessors
 * and the `ApiClient` / `ProviderCatalog` static facades are gone.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBackendSession(): BackendSession = BackendSession()

    // Deliberately UNSCOPED: BackendSession swaps its Retrofit instance when the
    // backend URL changes, so every resolution must return the current one.
    @Provides
    fun provideApi(session: BackendSession): OpenCodeApi = session.api

    @Provides
    @Singleton
    fun provideProviderDirectory(session: BackendSession): ProviderDirectory = ProviderDirectory(
        // Resolved per fetch: the directory outlives a backend switch.
        fetch = { session.api.getProviderList() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @Provides
    @Singleton
    fun provideMessageStore(): MessageStore = MessageCache

    @Provides
    @Singleton
    fun provideChatRepository(
        session: BackendSession,
        // Provider (not the instance): the repository resolves the api on each
        // call, so switching backend is picked up without restarting the app.
        api: javax.inject.Provider<OpenCodeApi>,
        messageStore: MessageStore,
    ): ChatRepository = ChatRepository(
        apiProvider = { api.get() },
        messageStore = messageStore,
        streamedMessages = { sessionId, limit -> session.getMessagesStreamed(sessionId, limit) },
    )
}
