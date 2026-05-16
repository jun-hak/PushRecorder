package com.example.pushrecorder.appinfo

import com.example.pushrecorder.data.AppRecordDao
import com.example.pushrecorder.data.AppRecordEntity

class FakeAppRecordDao : AppRecordDao {
    val records = linkedMapOf<String, AppRecordEntity>()
    var upsertCount = 0

    override suspend fun getAppRecord(packageName: String): AppRecordEntity? {
        return records[packageName]
    }

    override suspend fun getInstalledAppRecords(): List<AppRecordEntity> {
        return records.values.filter { record -> record.isInstalled }
    }

    override suspend fun upsert(record: AppRecordEntity) {
        records[record.packageName] = record
        upsertCount += 1
    }
}
