// this file SchedulesScreen.kt

package com.example.AiPhamest

import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.animation.animateContentSize
import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep          // ← NEW
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.AiPhamest.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import androidx.compose.material3.Divider          // add this
import androidx.compose.material3.CircularProgressIndicator



/* ──────────────────────────────────────────────────────────────────── */
/*  UI model                                                           */
/* ──────────────────────────────────────────────────────────────────── */

data class Dose(
    val id: String,               // this is just for UI keys, can stay as String
    val scheduleId: Int,          // <-- add this for DB reference
    val prescriptionId: Int,
    val medName: String,
    val dosage: String,
    val time: LocalTime,
    val date: LocalDate,
    var status: DoseStatus,
    val pinned: Boolean = false
)

/* ──────────────────────────────────────────────────────────────────── */
/*  SchedulesScreen                                                    */
/* ──────────────────────────────────────────────────────────────────── */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(
    /** Optionally supply a VM from above; if null we create our own. */
    prescriptionVM: PrescriptionViewModel? = null
) {
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            kotlinx.coroutines.delay(60_000) // 1-min update
        }
    }
    val ctx = LocalContext.current
    val viewModel: PrescriptionViewModel = prescriptionVM ?: viewModel(
        factory = AppVMFactory(ctx.applicationContext as Application)
    )

    /* ---------- DB state ---------- */
    val prescriptionsWithSchedules by
    viewModel.withSchedules.collectAsState(initial = emptyList())

    /* ---------- date picker ---------- */
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.toEpochMilli()
    )

    /* ---------- confirmation dialog ---------- */
    var showConfirm by remember { mutableStateOf(false) }      // ← NEW

    /* ---------- flatten to UI model ---------- */
    // 1. Build allDoses as before:
    val allDoses = remember(prescriptionsWithSchedules, now) {   // <-- add now as dependency
        prescriptionsWithSchedules.flatMap { pw ->
            pw.schedules.map { sched ->
                Dose(
                    id = sched.id.toString(),
                    scheduleId = sched.id,
                    prescriptionId = sched.prescriptionId,
                    medName  = pw.prescription.medicine,
                    dosage   = "${pw.prescription.dose} × ${pw.prescription.strengthUnit}",
                    time     = LocalTime.of(sched.hour, sched.minute),
                    date     = sched.date.toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate(),
                    status   = sched.effectiveStatus(now), // <-- use your new extension here!
                    pinned   = sched.isPinned
                )
            }
        }
    }


    // 2. Find all medicine names that are pinned in any schedule:
    val pinnedMedNames = remember(allDoses) {
        allDoses.filter { it.pinned }.map { it.medName }.toSet()
    }

    // 3. Make a new list, overriding pinned=true for all of that medicine:
    val allDosesByMedPinned = remember(allDoses, pinnedMedNames) {
        allDoses.map { dose ->
            dose.copy(pinned = pinnedMedNames.contains(dose.medName))
        }
    }





    /* ---------- today list + pinned repeats ---------- */
    val baseToday    = allDoses.filter { it.date == selectedDate }
    val pinnedGlobal = allDoses.filter { it.pinned }
    val extraPinned  = pinnedGlobal
        .filter { it.date != selectedDate }
        .map { it.copy(id = UUID.randomUUID().toString(), date = selectedDate) }
    val todayDoses = allDosesByMedPinned
        .filter { it.date == selectedDate }
        .sortedBy { it.time }


    val total   = todayDoses.size
    val taken   = todayDoses.count { it.status == DoseStatus.TAKEN }
    val percent = if (total == 0) 0f else taken / total.toFloat()
    var showRecFor by remember { mutableStateOf<Int?>(null) }

    /* ───────────────────────── UI ───────────────────────── */
    Surface(Modifier.fillMaxSize()) {
        Box {
            Column(Modifier.fillMaxSize()) {

                CalendarStrip(
                    selectedDate     = selectedDate,
                    onDateSelected   = { selectedDate = it },
                    onOpenFullPicker = { showDatePicker = true }
                )

                ProgressSummaryBar(taken, total, percent)

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    todayDoses.groupBy { it.time }.toSortedMap().forEach { (time, list) ->
                        item { TimeHeader(time) }
                        items(items = list, key = { it.id }) { dose ->
                            DoseCard(
                                dose = dose,
                                prescriptionVM = viewModel, // ADD THIS
                                onStatusChange = { viewModel.markTaken(dose.scheduleId) },
                                onPinChange = { newPin ->
                                    viewModel.togglePinForMedicine(dose.medName, newPin)
                                },
                                onSnooze = { viewModel.snooze(dose.scheduleId) },
                                onOpenRecommendations = {
                                    showRecFor = dose.prescriptionId
                                    viewModel.requestRecommendationsIfNeeded(dose.prescriptionId)
                                }
                            )
                        }
                    }
                }


            }

            /* ---------------- Floating‑Action Buttons ---------------- */

            /* existing – add prescription */
            FloatingActionButton(
                onClick = { /* TODO: launch add‑prescription flow */ },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add medicine")
            }

            /* new – wipe everything */
            FloatingActionButton(
                onClick = { showConfirm = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = 24.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor   = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear schedules")
            }
        }
    }

    /* ---------- confirm wipe dialog ---------- */
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllSchedules()
                    showConfirm = false
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
            title = { Text("Clear every schedule?") },
            text  = { Text("All dose cards and reminders will be removed permanently.") }
        )
    }

    /* ---------- full‑screen DatePicker ---------- */
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis
                            ?.toLocalDate()
                            ?.also { selectedDate = it }
                        showDatePicker = false
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = datePickerState) }
    }
}

/* ───────────────────────── Helper Composables ───────────────────────── */

@Composable
fun CalendarStrip(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onOpenFullPicker: () -> Unit
) {
    val start = selectedDate.minusDays(3)
    val days = (0..13).map { start.plusDays(it.toLong()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(bottom = 8.dp)
    ) {
        val monthYear = selectedDate.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        Text(
            monthYear,
            modifier = Modifier
                .padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                .clickable { onOpenFullPicker() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(days) { date ->
                DayChip(date, date == selectedDate) { onDateSelected(date) }
            }
        }
    }
}

@Composable
fun DayChip(date: LocalDate, selected: Boolean, onClick: () -> Unit) {
    val dayName   = date.format(DateTimeFormatter.ofPattern("EEE"))
    val dayNumber = date.dayOfMonth.toString()
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface

    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small,
        tonalElevation  = if (selected) 2.dp else 0.dp,
        shadowElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier
            .width(56.dp)
            .clickable { onClick() }
    ) {
        Column(
            Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(dayName, fontSize = 12.sp, color = fg)
            Text(dayNumber, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
        }
    }
}

@Composable
fun ProgressSummaryBar(taken: Int, total: Int, progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            "$taken / $total doses taken • ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium
        )
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )
    }
}

@Composable
fun TimeHeader(time: LocalTime) {
    val formatter = DateTimeFormatter.ofPattern("h:mm a")
    Text(
        time.format(formatter),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp, horizontal = 16.dp),
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp
    )
}

@Composable
fun DoseCard(
    dose: Dose,
    prescriptionVM: PrescriptionViewModel, // ADD THIS
    onStatusChange: (DoseStatus) -> Unit,
    onPinChange: (Boolean) -> Unit,
    onSnooze: () -> Unit,
    onOpenRecommendations: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // ADD: Collect recommendations state
    val recommendations by prescriptionVM.recommendationsFlow(dose.prescriptionId).collectAsState(initial = null)

    /* button labels & colors */
    val (statusText, statusColor, primaryLabel, primaryAction) = when (dose.status) {
        DoseStatus.TAKEN    -> Quad("Taken",   Color(0xFF4CAF50), "Undo")           {
            onStatusChange(DoseStatus.UPCOMING)
        }
        DoseStatus.MISSED   -> Quad("Missed",  Color(0xFFF44336), "Mark as Taken") {
            onStatusChange(DoseStatus.TAKEN)
        }
        DoseStatus.UPCOMING -> Quad("Upcoming",Color(0xFF2196F3), "Take Now")      {
            onStatusChange(DoseStatus.TAKEN)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box {
            Column(
                // FIX: Click action is simplified to request data and expand the card.
                Modifier
                    .clickable {
                        prescriptionVM.requestRecommendationsIfNeeded(dose.prescriptionId)
                        expanded = !expanded
                    }
                    .padding(16.dp)
            ) {
                Text(dose.medName, fontWeight = FontWeight.SemiBold)
                Text(dose.dosage, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(statusColor, shape = MaterialTheme.shapes.small)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, color = statusColor, modifier = Modifier.weight(1f))
                    when (dose.status) {
                        DoseStatus.TAKEN, DoseStatus.MISSED ->
                            TextButton(onClick = primaryAction) { Text(primaryLabel) }
                        DoseStatus.UPCOMING ->
                            Row {
                                TextButton(onClick = primaryAction) { Text(primaryLabel) }
                                Spacer(Modifier.width(4.dp))
                                TextButton(onClick = onSnooze) { Text("Snooze") }
                            }
                    }
                }

                // This logic correctly handles showing the content when the card is expanded.
                if (expanded && recommendations != null) {
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Recommendations:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))

                    recommendations!!.forEach { rec ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("• ", style = MaterialTheme.typography.bodySmall)
                            Text(
                                rec,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else if (expanded && recommendations == null) {
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Loading recommendations...",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            /* animated pin icon */
            val targetColor = if (dose.pinned)
                Color(0xFFE53935) else LocalContentColor.current.copy(alpha = 0.6f)
            val color     by animateColorAsState(targetColor, tween(250), label = "pinColor")
            val scale     by animateFloatAsState(
                targetValue = if (dose.pinned) 1.2f else 1f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 300f),
                label = "pinScale"
            )
            val rotation  by animateFloatAsState(
                targetValue = if (dose.pinned) 0f else -25f,
                animationSpec = tween(250, easing = FastOutSlowInEasing),
                label = "pinRotation"
            )

            IconButton(
                onClick = { onPinChange(!dose.pinned) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer(scaleX = scale, scaleY = scale, rotationZ = rotation)
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    tint = color,
                    contentDescription = if (dose.pinned) "Unpin" else "Pin"
                )
            }
        }
    }
}

/* ─────────── Utility classes & extensions ─────────── */

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun LocalDate.toEpochMilli(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
