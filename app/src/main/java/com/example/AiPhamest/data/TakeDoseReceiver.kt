// File: TakeDoseReceiver.kt
package com.example.AiPhamest.data

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*

class TakeDoseReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val scheduleId = intent.getIntExtra("schedule_id", -1)
        if (scheduleId == -1) return

        // Cancel all notifications for this dose
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(scheduleId * 10 + 1) // Pre alert
        nm.cancel(scheduleId * 10 + 2) // Main alert
        nm.cancel(scheduleId * 10 + 3) // Missed alert

        // Mark as taken via WorkManager
        val req = OneTimeWorkRequestBuilder<TakeDoseWorker>()
            .setInputData(workDataOf("schedule_id" to scheduleId))
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}

class TakeDoseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val scheduleId = inputData.getInt("schedule_id", -1)
            if (scheduleId == -1) return Result.failure()

            val dao = AppDatabase.get(applicationContext).scheduleDao()
            dao.setStatus(scheduleId, DoseStatus.TAKEN)

            // Trigger expansion logic if needed
            val repos = AppRepositories(applicationContext as android.app.Application)
            repos.prescription.markTakenAndExpand(scheduleId)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}