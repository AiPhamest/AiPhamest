// File: DoseAlertReceiver.kt

package com.example.AiPhamest.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.AiPhamest.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DoseAlertReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MedApp-Debug"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i(TAG, "----------------------------------------------------")
        Log.i(TAG, "DoseAlertReceiver triggered!")

        val scheduleId = intent.getIntExtra("scheduleId", -1)
        val kindStr = intent.getStringExtra("kind")

        Log.d(TAG, "DoseAlertReceiver: scheduleId=$scheduleId, kind=$kindStr")

        if (scheduleId == -1 || kindStr == null) {
            Log.e(TAG, "DoseAlertReceiver: Received invalid intent, exiting.")
            return
        }

        val kind = AlertKind.valueOf(kindStr)
        val medName = intent.getStringExtra("medName") ?: "Medicine"
        val dosage = intent.getStringExtra("dosage") ?: ""
        val time = intent.getStringExtra("time") ?: ""
        val isPinned = intent.getBooleanExtra("isPinned", false)
        val notifId = createNotificationId(scheduleId, kind)

        Log.d(TAG, "DoseAlertReceiver: Processing alert for '$medName' at $time")

        when (kind) {
            AlertKind.PRE -> {
                Log.d(TAG, "DoseAlertReceiver: Handling PRE alert.")
                showPreAlert(ctx, notifId, scheduleId, medName, dosage, time)
            }
            AlertKind.MAIN -> {
                Log.d(TAG, "DoseAlertReceiver: Handling MAIN alert.")
                showMainAlert(ctx, notifId, scheduleId, medName, dosage, time)

                val scope = CoroutineScope(Dispatchers.IO)
                scope.launch {
                    val app = ctx.applicationContext as android.app.Application
                    val repos = AppRepositories(app)

                    // --- REVISED AND CORRECTED LOGIC ---
                    val currentSchedule = repos.prescription.getScheduleById(scheduleId)
                    if (currentSchedule == null) {
                        Log.e(TAG, "DoseAlertReceiver: Could not find schedule with ID $scheduleId")
                        return@launch
                    }

                    val pres = repos.prescription.getPrescriptionById(currentSchedule.prescriptionId)
                    if (pres != null) {
                        // The call to getNextUpcomingSchedule is now unambiguous and correct.
                        val nextDose = repos.prescription.getNextUpcomingSchedule(pres.id, currentSchedule.id)
                        if (nextDose != null) {
                            repos.prescription.scheduleAllAlertsForDose(nextDose, pres)
                            Log.i(TAG, "DoseAlertReceiver: Successfully scheduled next dose (ID: ${nextDose.id})")
                        } else {
                            Log.i(TAG, "DoseAlertReceiver: No next dose found for prescription ID=${pres.id}. End of course.")
                        }
                    }

                    // Reschedule repeating alarm for pinned doses
                    if (isPinned) {
                        repos.prescription.rescheduleRepeatingAlarm(scheduleId)
                    }
                }
            }
            AlertKind.MISSED -> {
                Log.d(TAG, "DoseAlertReceiver: Handling MISSED alert.")
                handleMissedDose(ctx, notifId, scheduleId, medName, dosage, time)
            }
        }
    }


    private fun showPreAlert(ctx: Context, notifId: Int, scheduleId: Int, med: String, dosage: String, time: String) {
        showNotification(
            ctx, notifId, "Medicine Reminder", "Take $med ($dosage) at $time in 5 minutes.",
            createTakeIntent(ctx, scheduleId),
            createSnoozeIntent(ctx, scheduleId),
            isOngoing = false
        )
    }

    private fun showMainAlert(ctx: Context, notifId: Int, scheduleId: Int, med: String, dosage: String, time: String) {
        showNotification(
            ctx, notifId, "Time to take your medicine!", "It's time for $med ($dosage).",
            createTakeIntent(ctx, scheduleId),
            createSnoozeIntent(ctx, scheduleId),
            isOngoing = true
        )
    }

    private fun handleMissedDose(ctx: Context, notifId: Int, scheduleId: Int, med: String, dosage: String, time: String) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val dao = AppDatabase.get(ctx).scheduleDao()
            val sched = dao.one(scheduleId)
            if (sched?.status == DoseStatus.UPCOMING) {
                dao.setStatus(scheduleId, DoseStatus.MISSED)
                showNotification(
                    ctx, notifId, "Missed Dose", "You may have missed your $med ($dosage) dose at $time.",
                    createTakeIntent(ctx, scheduleId),
                    snoozeIntent = null,
                    isOngoing = false
                )
            }
        }
    }

    private fun showNotification(
        ctx: Context, notifId: Int, title: String, text: String,
        takeIntent: PendingIntent, snoozeIntent: PendingIntent?, isOngoing: Boolean
    ) {
        val CHANNEL_ID = "dose_alerts"
        val nm = ctx.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Medicine Reminders", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical notifications for taking medicine doses on time."
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            nm.createNotificationChannel(channel)
        }

        Log.i(TAG, "Showing notification (ID $notifId): Title='$title', Text='$text'")

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(isOngoing)
            .setContentIntent(takeIntent)
            .addAction(android.R.drawable.ic_menu_my_calendar, "Take", takeIntent)

        snoozeIntent?.let {
            builder.addAction(android.R.drawable.ic_menu_recent_history, "Snooze 10 Min", it)
        }

        nm.notify(notifId, builder.build())
        Log.d(TAG, "Notification sent to system.")
    }


    private fun createTakeIntent(ctx: Context, scheduleId: Int): PendingIntent {
        val intent = Intent(ctx, TakeDoseReceiver::class.java).apply {
            putExtra("schedule_id", scheduleId)
        }
        return PendingIntent.getBroadcast(
            ctx, scheduleId * 100 + 1,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createSnoozeIntent(ctx: Context, scheduleId: Int): PendingIntent {
        val intent = Intent(ctx, SnoozeReceiver::class.java).apply {
            putExtra("schedule_id", scheduleId)
        }
        return PendingIntent.getBroadcast(
            ctx, scheduleId * 100 + 2,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // --- Unique notification ID helper ---
    private fun createNotificationId(scheduleId: Int, kind: AlertKind): Int {
        return scheduleId * 10 + kind.ordinal
    }
}