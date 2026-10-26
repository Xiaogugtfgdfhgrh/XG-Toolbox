package com.xg.toolbox.core.shell

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {

    @Binds
    @Singleton
    abstract fun bindShell(impl: RootShell): Shell
}
