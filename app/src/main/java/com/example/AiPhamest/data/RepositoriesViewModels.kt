package com.example.AiPhamest.data

/* ---------- imports ---------- */

import android.util.Log
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.google.gson.Gson
import com.example.AiPhamest.llm.SideEffectChecker
import com.example.AiPhamest.Dose
import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.*
import java.util.*
import java.util.concurrent.TimeUnit

// --- MOVED HELPER FUNCTION ---
// By moving this function here, it becomes visible to other files in the same package,
// such as DoseAlertReceiver.kt.
internal fun ScheduleEntity.getLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(this.date.toInstant(), ZoneId.systemDefault())
        .withHour(this.hour).withMinute(this.minute)


/* ---------------------------------------------------------------------- */
/*  Patient - REPOSITORY                                                  */
/* ---------------------------------------------------------------------- */

class PatientRepository(private val dao: PatientDao) {
    val patient = dao.patientFlow()
    suspend fun save(p: PatientEntity) = dao.upsert(p)
}

class PatientViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PatientRepository(AppDatabase.get(app).patientDao())
    val patient =
        repo.patient.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )
    fun save(p: PatientEntity) = viewModelScope.launch { repo.save(p) }
}

/* ---------------------------------------------------------------------- */
/*  PRESCRIPTION - REPOSITORY (AlarmManager version)                      */
/* ---------------------------------------------------------------------- */

class PrescriptionRepository(
    private val pDao: PrescriptionDao,
    private val sDao: ScheduleDao,
    private val app: Application
) {

    // --- DAO access functions ---
    suspend fun getUniquePrescriptionIdsWithUpcomingSchedules(): List<Int> {
        return sDao.getUniquePrescriptionIdsWithUpcomingSchedules()
    }

    suspend fun getPrescriptionById(prescriptionId: Int): PrescriptionEntity? {
        return pDao.one(prescriptionId)
    }

    // CORRECTED: This now correctly calls the DAO method that finds the next schedule using the current schedule's ID.
    suspend fun getNextUpcomingSchedule(prescriptionId: Int, afterScheduleId: Int): ScheduleEntity? {
        return sDao.getNextUpcomingAfter(prescriptionId, afterScheduleId)
    }

    // ADDED: This function was missing but called elsewhere. It finds the very next dose.
    suspend fun getNextUpcomingScheduleForPrescription(prescriptionId: Int): ScheduleEntity? {
        return sDao.getNextUpcomingForPrescription(prescriptionId)
    }

    suspend fun getScheduleById(scheduleId: Int): ScheduleEntity? {
        return sDao.one(scheduleId)
    }

    fun ensureRecommendationsForPrescription(prescription: PrescriptionEntity) {
        if (prescription.recommendations.isNullOrBlank()) {
            com.example.AiPhamest.llm.RecommendationChecker.enqueue(
                app, prescription.id, prescription.medicine
            )
        }
    }

    fun recommendationsFlow(prescriptionId: Int): Flow<List<String>?> =
        pDao.recommendationsFlow(prescriptionId).map { json ->
            if (json.isNullOrBlank()) return@map null
            try {
                val obj = org.json.JSONObject(json)
                val arr = obj.optJSONArray("recommendations") ?: return@map null
                List(arr.length()) { i -> arr.getString(i) }.filter { it.isNotBlank() }
            } catch (_: Exception) { null }
        }


    companion object {
        private const val TAG = "MedApp-Debug"
        private val FIVE_MIN_IN_MILLIS = TimeUnit.MINUTES.toMillis(5)
        private val THIRTY_MIN_IN_MILLIS = TimeUnit.MINUTES.toMillis(30)
    }

    val all: Flow<List<PrescriptionEntity>> = pDao.allPrescriptions()
    val withSchedules: Flow<List<PrescriptionWithSchedules>> = pDao.prescriptionsWithSchedules()

    // --- OCR parsing ---
    private val ocrRegex = Regex(
        """^(.+?)\s*\|\s*(\d+(?:\.\d+)?(?:mg|mcg|g|ml|IU))\s*\|\s*(\d+)\s*\|\s*(\d+(?:\.\d+)?)(?:\s*\|\s*(\d+)\s*(p|ml))?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private fun parseOcr(raw: String): List<PrescriptionEntity> = raw.lines()
        .map(String::trim).filter(String::isNotBlank).mapNotNull { line ->
            ocrRegex.find(line)?.let { m ->
                PrescriptionEntity(
                    medicine = m.groupValues[1].trim(),
                    strengthUnit = m.groupValues[2].trim(),
                    dose = m.groupValues[3].toDouble(),
                    frequencyHours = m.groupValues[4].toDouble(),
                    packAmount = m.groupValues[5].toIntOrNull(),
                    packUnit = m.groupValues[6].takeIf(String::isNotBlank)?.lowercase(),
                    createdAt = Date()
                )
            }
        }
    suspend fun addFromOcr(text: String) {
        val entities = parseOcr(text)
        if (entities.isNotEmpty()) {
            pDao.insertAll(entities)
        }
    }

    /** Schedules the very first alarm for a new prescription. */
    suspend fun createFirstDose(presId: Int, firstTime: LocalTime) {
        if (sDao.getSchedulesForPrescription(presId).isNotEmpty()) return
        val pres = pDao.one(presId) ?: return

        val generated = ScheduleGenerator
            .forCourse(pres, firstTime)
            .mapIndexed { i, s -> if (i == 0) s.copy(status = DoseStatus.TAKEN) else s }

        if (generated.isEmpty()) {
            Log.d(TAG, "createFirstDose: nothing generated for $presId")
            return
        }

        sDao.insertAll(generated)

        // **ADD THIS: Trigger recommendations fetch**
        ensureRecommendationsForPrescription(pres)

        val nextDose = getNextUpcomingScheduleForPrescription(pres.id)
        if (nextDose != null) {
            scheduleAllAlertsForDose(nextDose, pres)
            Log.i(TAG, "createFirstDose: Scheduled initial alarm for schedule ID ${nextDose.id}")
        }
    }

    /** Marks a dose as taken and schedules the next one in the chain. */
    suspend fun markTakenAndExpand(scheduleId: Int) {
        val sched = sDao.one(scheduleId) ?: return
        if (sched.status == DoseStatus.TAKEN) return

        sDao.setStatus(scheduleId, DoseStatus.TAKEN)
        cancelAlertsForDose(sched)

        val pres = pDao.one(sched.prescriptionId) ?: return
        val nextDose = getNextUpcomingScheduleForPrescription(sched.prescriptionId)
        if (nextDose != null) {
            scheduleAllAlertsForDose(nextDose, pres)
            Log.i(TAG, "markTakenAndExpand: Scheduled next alarm for schedule ID ${nextDose.id}")
        }
    }

    /** Snoozes a dose by rescheduling its MAIN alert. */
    suspend fun snooze(scheduleId: Int, minutes: Int = 10) {
        val sched = sDao.one(scheduleId) ?: return
        val pres = pDao.one(sched.prescriptionId) ?: return

        Log.i(TAG, "Snooze: Initiated for schedule ID $scheduleId for $minutes minutes.")

        cancelAlertsForDose(sched)

        val triggerAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
        scheduleExactAlarm(sched, pres, AlertKind.MAIN, triggerAt)
    }

    /** Schedules the next day's alarm for a repeating (pinned) dose. */
    suspend fun rescheduleRepeatingAlarm(scheduleId: Int) {
        val sched = sDao.one(scheduleId) ?: return
        val pres = pDao.one(sched.prescriptionId) ?: return

        if (!sched.isPinned) return

        val originalDateTime = sched.getLocalDateTime()
        val nextDayTriggerTime = originalDateTime.plusDays(1)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        scheduleExactAlarm(sched, pres, AlertKind.PRE, nextDayTriggerTime - FIVE_MIN_IN_MILLIS)
        scheduleExactAlarm(sched, pres, AlertKind.MAIN, nextDayTriggerTime)
        scheduleExactAlarm(sched, pres, AlertKind.MISSED, nextDayTriggerTime + THIRTY_MIN_IN_MILLIS)
    }

    /** Toggles a specific time slot as pinned/unpinned. */
    suspend fun setPinnedForTime(prescriptionId: Int, hour: Int, minute: Int, pin: Boolean) {
        sDao.setPinnedForTime(prescriptionId, hour, minute, pin)
        if (pin) {
            val pres = pDao.one(prescriptionId) ?: return
            val schedules = sDao.getSchedulesForTime(prescriptionId, hour, minute)
            schedules.forEach { schedule -> rescheduleRepeatingAlarm(schedule.id) }
        } else {
            val schedules = sDao.getSchedulesForTime(prescriptionId, hour, minute)
            schedules.forEach { cancelAlertsForDose(it) }
        }
    }

    /** Toggles all schedules for a given medicine as pinned/unpinned. */
    suspend fun setPinnedForMedicine(medName: String, pin: Boolean) {
        val prescriptions = pDao.allPrescriptions().first()
            .filter { it.medicine.equals(medName, true) }

        if (prescriptions.isEmpty()) {
            Log.w(TAG, "setPinnedForMedicine: No prescriptions found for '$medName'")
            return
        }

        for (pres in prescriptions) {
            val existingSchedules = sDao.getSchedulesForPrescription(pres.id)
            existingSchedules.forEach { schedule -> sDao.setPinned(schedule.id, pin) }
            Log.d(TAG, "Set isPinned=$pin for ${existingSchedules.size} schedules of '${pres.medicine}'")

            if (pin) {
                ensureDailySchedulesExist(pres)
                val nextDose = getNextUpcomingScheduleForPrescription(pres.id)
                if (nextDose != null) {
                    scheduleAllAlertsForDose(nextDose, pres)
                    Log.i(TAG, "setPinnedForMedicine: Scheduled next upcoming alarm for pinned med '${pres.medicine}' (Schedule ID: ${nextDose.id})")
                }
            } else {
                Log.w(TAG, "Unpinning medicine '${pres.medicine}'. Cancelling all related alarms.")
                existingSchedules.forEach { schedule -> cancelAlertsForDose(schedule) }
            }
        }
    }

    /** Schedules PRE, MAIN, and MISSED alerts for a single dose. */
    fun scheduleAllAlertsForDose(sched: ScheduleEntity, pres: PrescriptionEntity) {
        val doseDateTime = sched.getLocalDateTime()
        val triggerTs = doseDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        Log.i(TAG, "scheduleAllAlertsForDose: Processing schedule ID ${sched.id} for time $doseDateTime")

        scheduleExactAlarm(sched, pres, AlertKind.PRE, triggerTs - FIVE_MIN_IN_MILLIS)
        scheduleExactAlarm(sched, pres, AlertKind.MAIN, triggerTs)
        scheduleExactAlarm(sched, pres, AlertKind.MISSED, triggerTs + THIRTY_MIN_IN_MILLIS)
    }

    /** The core AlarmManager scheduling logic. */
    private fun scheduleExactAlarm(
        sched: ScheduleEntity,
        pres: PrescriptionEntity,
        kind: AlertKind,
        triggerAt: Long
    ) {
        val triggerTimeStr = java.time.Instant.ofEpochMilli(triggerAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        Log.d(TAG, "scheduleExactAlarm: Attempting to schedule ID ${sched.id}, Kind: $kind, TriggerAt: $triggerTimeStr")

        if (triggerAt < System.currentTimeMillis()) {
            Log.w(TAG, "scheduleExactAlarm: SKIPPED schedule ID ${sched.id} ($kind) because its trigger time is in the past.")
            return
        }

        val mgr = app.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!mgr.canScheduleExactAlarms()) {
                Log.e(TAG, "scheduleExactAlarm: FAILED for schedule ID ${sched.id}. Cannot schedule exact alarms, permission not granted.")
                return
            }
        }
        val pi = PendingIntent.getBroadcast(
            app,
            createPendingIntentRequestCode(sched.id, kind),
            Intent(app, DoseAlertReceiver::class.java).apply {
                putExtra("kind", kind.name)
                putExtra("scheduleId", sched.id)
                putExtra("medName", pres.medicine)
                putExtra("dosage", "${pres.dose} × ${pres.strengthUnit}")
                putExtra("time", "%02d:%02d".format(sched.hour, sched.minute))
                putExtra("isPinned", sched.isPinned)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.i(TAG, "scheduleExactAlarm: SUCCESS for schedule ID ${sched.id} ($kind). Alarm set for $triggerTimeStr.")
        } catch (e: SecurityException) {
            Log.e(TAG, "scheduleExactAlarm: CRITICAL FAILURE for schedule ID ${sched.id}. SecurityException: ${e.message}", e)
        }
    }

    /** Cancels all pending alarms for a given schedule ID. */
    private fun cancelAlertsForDose(sched: ScheduleEntity) {
        val mgr = app.getSystemService(AlarmManager::class.java)
        Log.w(TAG, "cancelAlertsForDose: Cancelling all alarms for schedule ID ${sched.id}")
        AlertKind.values().forEach { kind ->
            val pi = PendingIntent.getBroadcast(
                app,
                createPendingIntentRequestCode(sched.id, kind),
                Intent(app, DoseAlertReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mgr.cancel(pi)
        }
    }

    /** Creates a unique request code to avoid alarm collisions. */
    private fun createPendingIntentRequestCode(scheduleId: Int, kind: AlertKind): Int {
        return scheduleId * 10 + kind.ordinal
    }

    /** Full data wipe: cancels all alarms then clears database tables. */
    suspend fun clearAllData() {
        val allSchedules = sDao.getAllSchedulesList()
        allSchedules.forEach { cancelAlertsForDose(it) }
        sDao.deleteAll()
        pDao.deleteAllPrescriptions()
    }

    /** Populates the database with daily schedules for pinned meds (does not set alarms). */
    private suspend fun ensureDailySchedulesExist(p: PrescriptionEntity) {
        val hourMinPairs = sDao.getSchedulesForPrescription(p.id)
            .filter { it.isPinned }
            .map { it.hour to it.minute }
            .distinct()

        if (hourMinPairs.isEmpty()) return

        val today = LocalDate.now()
        val twoMonthsAhead = today.plusMonths(2)
        val toInsert = mutableListOf<ScheduleEntity>()
        for ((hr, min) in hourMinPairs) {
            var d = today
            while (!d.isAfter(twoMonthsAhead)) {
                val existingForTime = sDao.getSchedulesForTime(p.id, hr, min)
                val exists = existingForTime.any { schedule ->
                    val scheduleDate = schedule.date.toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    scheduleDate == d
                }
                if (!exists) {
                    toInsert += ScheduleEntity(
                        prescriptionId = p.id,
                        date = Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()),
                        hour = hr,
                        minute = min,
                        isPinned = true,
                        status = DoseStatus.UPCOMING,
                        dosageNote = "${p.dose} × ${p.strengthUnit}"
                    )
                }
                d = d.plusDays(1)
            }
        }
        if (toInsert.isNotEmpty()) {
            sDao.insertAll(toInsert)
        }
    }
}

enum class AlertKind { PRE, MAIN, MISSED }

/* ... The rest of the file (WarningRepository, ViewModels, etc.) is unchanged ... */
/* ---------------------------------------------------------------------- */
/*  WARNING - REPOSITORY                                                  */
/* ---------------------------------------------------------------------- */

class WarningRepository(private val dao: WarningDao) {
    val warnings = dao.all()
    suspend fun add(
        title: String,
        drugPossibleCause: String,
        severity: Severity,
        warningType: String,
        createdAt: Long? = null,
        // --- NEW PARAMETERS ---
        reasoning: String?,
        recommendations: List<String>?,
        confidence: Float?
    ) = dao.insert(
        WarningEntity(
            title             = title,
            drugPossibleCause = drugPossibleCause,
            warningType       = warningType,
            severity          = severity,
            createdAt         = createdAt?.let(::Date) ?: Date(),
            reasoning         = reasoning,
            recommendations   = recommendations?.let { Gson().toJson(it) }, // Serialize list
            confidence        = confidence
        )
    )
    suspend fun resolve(id: Int) = dao.markResolved(id)
    suspend fun clearAll()       = dao.deleteAll()
    suspend fun delete(warning: WarningEntity) = dao.delete(warning)
}

/* ---------------------------------------------------------------------- */
/*  SIDE EFFECT - REPOSITORY                                              */
/* ---------------------------------------------------------------------- */

class SideEffectRepository(private val dao: SideEffectDao) {
    val sideEffects = dao.all()

    suspend fun log(
        pId: Int?,
        desc: String,
        sev: Severity,
        inputMode: InputMode,
        latitude: Double? = null,
        longitude: Double? = null
    ): Long = dao.insert(
        SideEffectEntity(
            prescriptionId = pId,
            description    = desc,
            severity       = sev,
            inputMode      = inputMode,
            occurredAt     = Date(),
            latitude       = latitude,
            longitude      = longitude
        )
    )

    suspend fun updateSeverity(id: Int, sev: Severity) =
        dao.setSeverity(id, sev.name)
    suspend fun delete(effect: SideEffectEntity) = dao.delete(effect)
}

/* ---------------------------------------------------------------------- */
/*  SETTINGS - REPOSITORY                                                 */
/* ---------------------------------------------------------------------- */

class SettingsRepository(private val dao: SettingsDao) {
    fun value(key: String) = dao.getValueFlow(key)
    suspend fun set(key: String, value: String) =
        dao.set(key, value)
}

/* ---------------------------------------------------------------------- */
/*  AGGREGATE HOLDER                                                      */
/* ---------------------------------------------------------------------- */

class AppRepositories(app: Application) {
    private val db = AppDatabase.get(app)
    val patient       = PatientRepository(db.patientDao())
    val prescription  = PrescriptionRepository(db.prescriptionDao(), db.scheduleDao(), app)
    val warning       = WarningRepository(db.warningDao())
    val sideEffect    = SideEffectRepository(db.sideEffectDao())
    val settings      = SettingsRepository(db.settingsDao())
}

/* ---------------------------------------------------------------------- */
/*  VIEW MODELS                                                           */
/* ---------------------------------------------------------------------- */

private const val LAST_OCR_KEY = "last_ocr_raw"

class PrescriptionViewModel(app: Application) : AndroidViewModel(app) {
    private val repos = AppRepositories(app)

    val list = repos.prescription.all
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val withSchedules = repos.prescription.withSchedules
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val lastSavedRawText = repos.settings.value(LAST_OCR_KEY)
        .map { it?.takeIf(String::isNotBlank) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _tempOcr = MutableStateFlow<String?>(null)
    val tempOcr: StateFlow<String?> = _tempOcr.asStateFlow()

    fun setTempOcr(text: String?) { _tempOcr.value = text }

    fun ingestOcr(text: String) =
        viewModelScope.launch(Dispatchers.Default) {
            repos.prescription.addFromOcr(text)
            repos.settings.set(LAST_OCR_KEY, text)
        }

    fun createFirstDose(presId: Int, firstTime: LocalTime) =
        viewModelScope.launch { repos.prescription.createFirstDose(presId, firstTime) }

    fun markTaken(scheduleId: Int) =
        viewModelScope.launch { repos.prescription.markTakenAndExpand(scheduleId) }

    fun snooze(scheduleId: Int, minutes: Int = 10) =
        viewModelScope.launch { repos.prescription.snooze(scheduleId, minutes) }

    fun togglePin(dose: Dose, pin: Boolean) = viewModelScope.launch {
        repos.prescription.setPinnedForTime(
            dose.prescriptionId,
            dose.time.hour,
            dose.time.minute,
            pin
        )
    }

    fun clearLastSaved() =
        viewModelScope.launch { repos.settings.set(LAST_OCR_KEY, "") }

    fun clearAllSchedules() =
        viewModelScope.launch { repos.prescription.clearAllData() }

    fun clearAllData() =
        viewModelScope.launch { repos.prescription.clearAllData() }

    fun togglePinForMedicine(medName: String, pin: Boolean) = viewModelScope.launch {
        repos.prescription.setPinnedForMedicine(medName, pin)
    }

    fun recommendationsFlow(prescriptionId: Int) =
        repos.prescription.recommendationsFlow(prescriptionId)

    /** If not saved yet, this will queue a background fetch. Safe to call on click. */
    fun requestRecommendationsIfNeeded(prescriptionId: Int) = viewModelScope.launch {
        val pres = repos.prescription.getPrescriptionById(prescriptionId) ?: return@launch
        repos.prescription.ensureRecommendationsForPrescription(pres)
    }
}

class WarningViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = WarningRepository(AppDatabase.get(app).warningDao())
    val warnings = repo.warnings.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun add(
        title: String,
        drugPossibleCause: String,
        severity: Severity,
        warningType: String,
        createdAt: Long? = null,
        // --- NEW PARAMETERS ---
        reasoning: String?,
        recommendations: List<String>?,
        confidence: Float?
    ) = viewModelScope.launch {
        repo.add(
            title             = title,
            drugPossibleCause = drugPossibleCause,
            severity          = severity,
            warningType       = warningType,
            createdAt         = createdAt,
            reasoning         = reasoning,
            recommendations   = recommendations,
            confidence        = confidence
        )
    }

    fun resolve(id: Int) =
        viewModelScope.launch { repo.resolve(id) }

    fun clearAll() = viewModelScope.launch { repo.clearAll() }

    fun importWarningsFromJson(jsonString: String) {
        viewModelScope.launch {
            WarningGenerator.generateAndSaveWarnings(jsonString, repo)
        }
    }
    fun delete(warning: WarningEntity) = viewModelScope.launch {
        repo.delete(warning)
    }
}

class SideEffectViewModel(app: Application) : AndroidViewModel(app) {
    private val repos = AppRepositories(app)
    val effects = repos.sideEffect.sideEffects
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // --- ADD A TAG FOR LOGGING ---
    private val TAG = "SideEffectViewModel"

    fun log(
        desc: String,
        inputMode: InputMode,
        latitude: Double? = null,
        longitude: Double? = null
    ) = viewModelScope.launch {
        // ... (code to log the side effect entity)
        val seId = repos.sideEffect.log(
            pId        = null,
            desc       = desc,
            sev        = Severity.LOW,
            inputMode  = inputMode,
            latitude   = latitude,
            longitude  = longitude
        ).toInt()

        // ... (code to build the request)
        val patient = repos.patient.patient.first()
        val gender       = patient?.gender ?: "Unknown"
        val chronic      = patient?.chronicDiseases.orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty)
        val allergies    = patient?.allergies.orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty)
        val currentMeds  = patient?.medications.orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty)
        val allMeds      = repos.prescription.all.first().map { it.medicine }

        val req = SideEffectChecker.AnalysisRequest(
            sideEffectId       = seId,
            description        = desc,
            medications        = allMeds,
            chronicDiseases    = chronic,
            allergies          = allergies,
            currentMedications = currentMeds,
            gender             = gender
        )

        SideEffectChecker.analyzeInBackground(getApplication(), req) { result ->
            // --- LOGGING STEP 3: LOG THE FINAL RESULT BEFORE SAVING ---
            Log.d(TAG, "analyzeInBackground Callback: Received result: $result")

            result ?: return@analyzeInBackground

            viewModelScope.launch {
                Log.d(TAG, "Saving warning with data: $result")
                repos.warning.add(
                    title             = desc,
                    drugPossibleCause = result.drugPossibleCause ?: "Unknown",
                    warningType       = result.warningType,
                    severity          = Severity.valueOf(result.severity.uppercase()),
                    reasoning         = result.reasoning,
                    recommendations   = result.recommendations,
                    confidence        = result.confidence
                )

                repos.sideEffect.updateSeverity(
                    seId,
                    Severity.valueOf(result.severity.uppercase())
                )
            }
        }
    }

    fun delete(effect: SideEffectEntity) = viewModelScope.launch {
        repos.sideEffect.delete(effect)
    }
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repos = AppRepositories(app)
    fun value(key: String) = repos.settings.value(key)
    fun set(key: String, value: String) =
        viewModelScope.launch { repos.settings.set(key, value) }
}

/* ---------------------------------------------------------------------- */
/*  FACTORY                                                               */
/* ---------------------------------------------------------------------- */

class AppVMFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        PatientViewModel::class.java -> PatientViewModel(app)
        PrescriptionViewModel::class.java -> PrescriptionViewModel(app)
        WarningViewModel::class.java      -> WarningViewModel(app)
        SideEffectViewModel::class.java   -> SideEffectViewModel(app)
        SettingsViewModel::class.java     -> SettingsViewModel(app)
        else -> error("Unknown VM: ${modelClass.simpleName}")
    } as T
}