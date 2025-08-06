// File: SnoozeReceiver.kt
package com.example.AiPhamest.data

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val scheduleId = intent.getIntExtra("schedule_id", -1)
        if (scheduleId == -1) return

        // Cancel current notifications
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(scheduleId * 10 + 1) // Pre alert
        nm.cancel(scheduleId * 10 + 2) // Main alert

        // Trigger snooze via WorkManager
        val req = OneTimeWorkRequestBuilder<SnoozeWorker>()
            .setInputData(workDataOf("schedule_id" to scheduleId))
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}

class SnoozeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val scheduleId = inputData.getInt("schedule_id", -1)
            if (scheduleId == -1) return Result.failure()

            val repos = AppRepositories(applicationContext as android.app.Application)
            repos.prescription.snooze(scheduleId, 10)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}