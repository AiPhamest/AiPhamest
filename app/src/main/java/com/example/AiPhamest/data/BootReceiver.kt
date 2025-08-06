// File: BootReceiver.kt
package com.example.AiPhamest.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BootReceiver : BroadcastReceiver() {
    // <<< LOGGING: Add a companion object for the log tag >>>
    companion object {
        private const val TAG = "MedApp-Debug"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            // <<< LOGGING: Make the log more prominent >>>
            Log.w(TAG, "=====================================================")
            Log.w(TAG, "BootReceiver triggered by action: ${intent.action}. Enqueuing RestoreAlarmsWorker.")
            Log.w(TAG, "=====================================================")

            val restoreWork = OneTimeWorkRequestBuilder<RestoreAlarmsWorker>().build()
            WorkManager.getInstance(context).enqueue(restoreWork)
        }
    }
}

class RestoreAlarmsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    // <<< LOGGING: Re-use the tag here >>>
    private val TAG = "MedApp-Debug"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.i(TAG, "RestoreAlarmsWorker: Starting work...")
            val app = applicationContext as android.app.Application
            val repos = AppRepositories(app)

            // --- NEW LOGIC: Only restore the NEXT upcoming dose for each prescription ---
            // Get all unique prescription IDs that have at least one upcoming dose.
            val prescriptionIds = repos.prescription.getUniquePrescriptionIdsWithUpcomingSchedules()

            Log.i(TAG, "RestoreAlarmsWorker: Found ${prescriptionIds.size} prescriptions with upcoming doses to restore.")

            prescriptionIds.forEach { presId ->
                val pres = repos.prescription.getPrescriptionById(presId)
                val nextDose = repos.prescription.getNextUpcomingScheduleForPrescription(presId)
                if (pres != null && nextDose != null) {
                    repos.prescription.scheduleAllAlertsForDose(nextDose, pres)
                    Log.d(TAG, "RestoreAlarmsWorker: Restored next alarm for prescription ID $presId (Schedule ID: ${nextDose.id})")
                } else {
                    Log.w(TAG, "RestoreAlarmsWorker: Skipping prescription $presId (pres=$pres, nextDose=$nextDose)")
                }
            }

            Log.i(TAG, "RestoreAlarmsWorker: Finished successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "RestoreAlarmsWorker: Failed to restore alarms", e)
            Result.failure()
        }
    }
}
