package com.safeword.android.di

import com.safeword.android.service.AccessibilityBridge
import com.safeword.android.service.DefaultAccessibilityBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AccessibilityModule {

    @Binds
    @Singleton
    abstract fun bindAccessibilityBridge(impl: DefaultAccessibilityBridge): AccessibilityBridge
}
