package io.gravital.share.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.gravital.share.ffi.EngineBridge
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    /**
     * EngineBridge is a Kotlin object (singleton), but Hilt needs an explicit
     * @Provides binding for it since objects can't be annotated with @Inject.
     */
    @Provides
    @Singleton
    fun provideEngineBridge(): EngineBridge = EngineBridge
}
