package com.example.pushrecorder.di

import com.example.pushrecorder.appinfo.AppInfoResolver
import com.example.pushrecorder.appinfo.AppInfoSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppInfoModule {
    @Binds
    @Singleton
    abstract fun bindAppInfoSource(resolver: AppInfoResolver): AppInfoSource
}
