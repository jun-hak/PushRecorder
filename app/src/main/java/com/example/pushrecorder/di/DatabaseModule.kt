package com.example.pushrecorder.di

import android.content.Context
import com.example.pushrecorder.data.AppRecordDao
import com.example.pushrecorder.data.AppDatabase
import com.example.pushrecorder.data.NotificationEventJournalDao
import com.example.pushrecorder.data.NotificationDao
import com.example.pushrecorder.data.NotificationRetentionPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideNotificationDao(database: AppDatabase): NotificationDao {
        return database.notificationDao()
    }

    @Provides
    @Singleton
    fun provideNotificationRetentionPolicy(): NotificationRetentionPolicy {
        return NotificationRetentionPolicy.Default
    }

    @Provides
    @Singleton
    fun provideAppRecordDao(database: AppDatabase): AppRecordDao {
        return database.appRecordDao()
    }

    @Provides
    @Singleton
    fun provideNotificationEventJournalDao(database: AppDatabase): NotificationEventJournalDao {
        return database.notificationEventJournalDao()
    }
}
