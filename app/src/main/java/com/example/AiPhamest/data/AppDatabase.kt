package com.example.AiPhamest.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.*
import java.time.LocalDateTime
import java.time.ZoneId

/* ---------- ENUMS ---------- */
enum class Severity { LOW, MEDIUM, HIGH }
enum class DoseStatus { UPCOMING, TAKEN, MISSED }
enum class InputMode { MANUAL, VOICE }

/* ---------- TYPE CONVERTERS ---------- */
class AppTypeConverters {

    // --- Date <-> Long ---
    @TypeConverter
    fun dateToLong(d: Date?): Long? = d?.time

    @TypeConverter
    fun longToDate(v: Long?): Date? = v?.let(::Date)

    // --- Severity <-> String ---
    @TypeConverter
    fun sevToString(s: Severity?): String? = s?.name

    @TypeConverter
    fun strToSev(v: String?): Severity? = v?.let { Severity.valueOf(it) }

    // --- DoseStatus <-> String ---
    @TypeConverter
    fun statusToString(s: DoseStatus?): String? = s?.name

    @TypeConverter
    fun strToStatus(v: String?): DoseStatus? = v?.let { DoseStatus.valueOf(it) }

    // --- InputMode <-> String ---
    @TypeConverter
    fun inputModeToString(i: InputMode?): String? = i?.name

    @TypeConverter
    fun stringToInputMode(v: String?): InputMode? = v?.let { InputMode.valueOf(it) }
}

/* ---------- ENTITIES ---------- */

@Entity(tableName = "prescriptions", indices = [Index("medicine")])
data class PrescriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val medicine: String,
    val strengthUnit: String,
    val dose: Double,
    val frequencyHours: Double,
    val packAmount: Int? = null,
    val packUnit: String? = null,
    val createdAt: Date = Date(),
    // NEW:
    val recommendations: String? = null   // JSON: {"drug":"X","recommendations":[...]}
)

@Entity(
    tableName = "schedules",
    foreignKeys = [ForeignKey(
        entity = PrescriptionEntity::class,
        parentColumns = ["id"],
        childColumns = ["prescriptionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("prescriptionId")]
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val prescriptionId: Int,
    val date: Date,
    val hour: Int,
    val minute: Int,
    val dosageNote: String? = null,
    val isPinned: Boolean = false,
    val status: DoseStatus = DoseStatus.UPCOMING,
    val createdAt: Date = Date()
)


@Entity(tableName = "warnings")
data class WarningEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val drugPossibleCause: String,
    val warningType: String,
    val severity: Severity,
    val createdAt: Date = Date(),
    val resolved: Boolean = false,
    // --- NEW FIELDS ---
    val reasoning: String? = null,
    val recommendations: String? = null, // Stored as a JSON string list
    val confidence: Float? = null
)

@Entity(
    tableName = "side_effects",
    foreignKeys = [ ForeignKey(
        entity = PrescriptionEntity::class,
        parentColumns = ["id"],
        childColumns = ["prescriptionId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("prescriptionId")]
)
data class SideEffectEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val prescriptionId: Int? = null,
    val description: String,
    val severity: Severity = Severity.LOW,
    val inputMode: InputMode = InputMode.MANUAL,
    val occurredAt: Date = Date(),
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Entity(
    tableName = "settings",
    indices = [Index(value = ["key"], unique = true)]
)
data class SettingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,
    val value: String
)

@Entity(tableName = "patient")
data class PatientEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val age: Int?,
    val gender: String?,
    val bloodType: String?,
    val emergencyContact: String?,
    val pastSurgeries: String?,
    val chronicDiseases: String?,
    val allergies: String?,
    val medications: String?,
    val updatedAt: Date = Date()
)

/* ---------- RELATIONSHIP ---------- */
data class PrescriptionWithSchedules(
    @Embedded val prescription: PrescriptionEntity,
    @Relation(parentColumn = "id", entityColumn = "prescriptionId")
    val schedules: List<ScheduleEntity>
)

/* ---------- DAOs ---------- */
@Dao
interface PatientDao {
    @Query("SELECT * FROM patient LIMIT 1")
    fun patientFlow(): Flow<PatientEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(patient: PatientEntity)
}

@Dao
interface PrescriptionDao {
    @Insert
    suspend fun insert(entity: PrescriptionEntity): Long

    @Insert
    suspend fun insertAll(entities: List<PrescriptionEntity>): List<Long>

    @Update
    suspend fun update(entity: PrescriptionEntity)

    @Delete
    suspend fun delete(entity: PrescriptionEntity)

    @Query("SELECT * FROM prescriptions WHERE id = :id LIMIT 1")
    suspend fun one(id: Int): PrescriptionEntity?

    /** Bulk wipe all prescriptions */
    @Query("DELETE FROM prescriptions")
    suspend fun deleteAllPrescriptions()

    /** Bare list of prescriptions, newest first */
    @Query("SELECT * FROM prescriptions ORDER BY createdAt DESC")
    fun allPrescriptions(): Flow<List<PrescriptionEntity>>

    /** Prescriptions with their schedules */
    @Transaction
    @Query("SELECT * FROM prescriptions ORDER BY createdAt DESC")
    fun prescriptionsWithSchedules(): Flow<List<PrescriptionWithSchedules>>

    @Query("UPDATE prescriptions SET recommendations = :json WHERE id = :id")
    suspend fun updateRecommendations(id: Int, json: String?)

    @Query("SELECT recommendations FROM prescriptions WHERE id = :id LIMIT 1")
    fun recommendationsFlow(id: Int): kotlinx.coroutines.flow.Flow<String?>
}

@Dao
interface ScheduleDao {
    @Insert
    suspend fun insert(schedule: ScheduleEntity): Long

    @Insert
    suspend fun insertAll(schedules: List<ScheduleEntity>): List<Long>

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Query("""
        SELECT * FROM schedules
         WHERE prescriptionId = :presId
           AND date BETWEEN :start AND :end
         ORDER BY hour, minute
    """)
    fun forPrescriptionOnDate(
        presId: Int,
        start: Date,
        end: Date
    ): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun one(id: Int): ScheduleEntity?

    @Query("UPDATE schedules SET status = :st WHERE id = :id")
    suspend fun setStatus(id: Int, st: DoseStatus)

    @Query("UPDATE schedules SET isPinned = :p WHERE id = :id")
    suspend fun setPinned(id: Int, p: Boolean)

    @Query("DELETE FROM schedules")
    suspend fun deleteAll()

    @Query("""
        UPDATE schedules SET isPinned = :p
        WHERE prescriptionId = :prescriptionId
          AND hour = :hour
          AND minute = :minute
    """)
    suspend fun setPinnedForTime(
        prescriptionId: Int,
        hour: Int,
        minute: Int,
        p: Boolean
    )

    @Query("""
        SELECT * FROM schedules
        WHERE prescriptionId = :prescriptionId
          AND hour = :hour
          AND minute = :minute
    """)
    suspend fun getSchedulesForTime(
        prescriptionId: Int,
        hour: Int,
        minute: Int
    ): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE prescriptionId = :prescriptionId")
    suspend fun getSchedulesForPrescription(prescriptionId: Int): List<ScheduleEntity>

    @Query("SELECT * FROM schedules")
    suspend fun getAllSchedulesList(): List<ScheduleEntity>

    // --- QUERIES ADDED FOR JUST-IN-TIME ALARM SCHEDULING ---
    @Query("SELECT * FROM schedules WHERE prescriptionId = :prescriptionId AND status = 'UPCOMING' ORDER BY date ASC, hour ASC, minute ASC LIMIT 1")
    suspend fun getNextUpcomingForPrescription(prescriptionId: Int): ScheduleEntity?

    @Query("SELECT DISTINCT prescriptionId FROM schedules WHERE status = 'UPCOMING'")
    suspend fun getUniquePrescriptionIdsWithUpcomingSchedules(): List<Int>

    @Query("""
        SELECT * FROM schedules
        WHERE prescriptionId = :prescriptionId
        AND status = 'UPCOMING'
        AND (
            date > (SELECT date FROM schedules WHERE id = :currentScheduleId)
            OR (
                date = (SELECT date FROM schedules WHERE id = :currentScheduleId) AND
                (
                    hour > (SELECT hour FROM schedules WHERE id = :currentScheduleId) OR
                    (hour = (SELECT hour FROM schedules WHERE id = :currentScheduleId) AND minute > (SELECT minute FROM schedules WHERE id = :currentScheduleId))
                )
            )
        )
        ORDER BY date ASC, hour ASC, minute ASC
        LIMIT 1
    """)
    suspend fun getNextUpcomingAfter(prescriptionId: Int, currentScheduleId: Int): ScheduleEntity?
}


@Dao
interface WarningDao {
    @Insert
    suspend fun insert(warning: WarningEntity): Long

    @Insert
    suspend fun insertAll(warnings: List<WarningEntity>): List<Long>

    @Query("SELECT * FROM warnings ORDER BY createdAt DESC")
    fun all(): Flow<List<WarningEntity>>

    @Query("UPDATE warnings SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: Int)

    @Query("DELETE FROM warnings")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(warning: WarningEntity)
}

@Dao
interface SideEffectDao {
    /* Single‑row insert that returns the rowId */
    @Insert
    suspend fun insert(effect: SideEffectEntity): Long

    /* Batch insert */
    @Insert
    suspend fun insertAll(effects: List<SideEffectEntity>): List<Long>

    /* Update severity later */
    @Query("UPDATE side_effects SET severity = :sev WHERE id = :id")
    suspend fun setSeverity(id: Int, sev: String)

    @Query("SELECT * FROM side_effects ORDER BY occurredAt DESC")
    fun all(): Flow<List<SideEffectEntity>>

    @Delete
    suspend fun delete(effect: SideEffectEntity)
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    fun getValueFlow(key: String): Flow<String?>

    suspend fun set(key: String, value: String) =
        put(SettingEntity(key = key, value = value))
}

/* ---------- DATABASE ---------- */
@Database(
    version = 11, // INCREMENTED VERSION
    exportSchema = true,
    entities = [
        PatientEntity::class,
        PrescriptionEntity::class,
        ScheduleEntity::class,
        WarningEntity::class,
        SideEffectEntity::class,
        SettingEntity::class
    ]
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun warningDao(): WarningDao
    abstract fun sideEffectDao(): SideEffectDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "onefile.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10, // ADDED NEW MIGRATION
                        MIGRATION_10_11
                    )
                    .build()
                    .also { INSTANCE = it }
            }

        // --- NEW MIGRATION ---
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE warnings ADD COLUMN reasoning TEXT")
                db.execSQL("ALTER TABLE warnings ADD COLUMN recommendations TEXT")
                db.execSQL("ALTER TABLE warnings ADD COLUMN confidence REAL")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedules ADD COLUMN date INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE schedules ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE schedules ADD COLUMN status TEXT NOT NULL DEFAULT 'UPCOMING'")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN dose INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN frequencyHours INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                  CREATE TABLE prescriptions_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    medicine TEXT NOT NULL,
                    strengthUnit TEXT NOT NULL,
                    dose INTEGER NOT NULL,
                    frequencyHours INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                  )
                """.trimIndent())
                db.execSQL("""
                  INSERT INTO prescriptions_new (id, medicine, strengthUnit, dose, frequencyHours, createdAt)
                  SELECT id,
                         medicine,
                         printf('%s %s', strength, unit),
                         dose,
                         frequencyHours,
                         createdAt
                  FROM prescriptions
                """.trimIndent())
                db.execSQL("DROP TABLE prescriptions")
                db.execSQL("ALTER TABLE prescriptions_new RENAME TO prescriptions")
                db.execSQL("""
                  CREATE INDEX IF NOT EXISTS index_prescriptions_medicine
                  ON prescriptions(medicine)
                """.trimIndent())
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN packAmount INTEGER")
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN packUnit   TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE side_effects ADD COLUMN inputMode TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE side_effects ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE side_effects ADD COLUMN longitude REAL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE patient_new (
                        id INTEGER NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        age INTEGER,
                        gender TEXT,
                        bloodType TEXT,
                        emergencyContact TEXT,
                        pastSurgeries TEXT,
                        chronicDiseases TEXT,
                        allergies TEXT,
                        medications TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                // CORRECTED: The INSERT statement now includes all columns to prevent data loss.
                db.execSQL("""
                    INSERT INTO patient_new (id, name, age, gender, bloodType, emergencyContact, pastSurgeries, chronicDiseases, allergies, medications, updatedAt)
                    SELECT id, name, age, gender, bloodType, emergencyContact, pastSurgeries, chronicDiseases, allergies, medications, updatedAt
                    FROM patient
                """)

                db.execSQL("DROP TABLE patient")
                db.execSQL("ALTER TABLE patient_new RENAME TO patient")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL("""
            CREATE TABLE warnings_new (
                id               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title            TEXT    NOT NULL,
                drugPossibleCause TEXT   NOT NULL,
                warningType      TEXT    NOT NULL,
                severity         TEXT    NOT NULL,
                createdAt        INTEGER NOT NULL,
                resolved         INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

                db.execSQL("""
            INSERT INTO warnings_new
                   (id, title, drugPossibleCause, warningType,
                    severity, createdAt, resolved)
            SELECT id,
                   title,
                   description,          -- old → new
                   ''            AS warningType,   -- <‑‑ give it a default
                   severity,
                   createdAt,
                   resolved
              FROM warnings
        """.trimIndent())

                db.execSQL("DROP TABLE warnings")
                db.execSQL("ALTER TABLE warnings_new RENAME TO warnings")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create the new table with the right types
                db.execSQL("""
            CREATE TABLE prescriptions_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                medicine TEXT NOT NULL,
                strengthUnit TEXT NOT NULL,
                dose REAL NOT NULL,
                frequencyHours REAL NOT NULL,
                packAmount INTEGER,
                packUnit TEXT,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())

                // 2. Copy the data
                db.execSQL("""
            INSERT INTO prescriptions_new (id, medicine, strengthUnit, dose, frequencyHours, packAmount, packUnit, createdAt)
            SELECT id, medicine, strengthUnit, dose, frequencyHours, packAmount, packUnit, createdAt
            FROM prescriptions
        """.trimIndent())

                // 3. Drop old table
                db.execSQL("DROP TABLE prescriptions")

                // 4. Rename new table
                db.execSQL("ALTER TABLE prescriptions_new RENAME TO prescriptions")

                // 5. **RECREATE THE INDEX!**
                db.execSQL("""
            CREATE INDEX IF NOT EXISTS index_prescriptions_medicine
            ON prescriptions(medicine)
        """.trimIndent())
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN recommendations TEXT")
            }
        }


    }
}

/**
 * Correctly calculates the status of a schedule.
 * If the status is already TAKEN or MISSED, it returns that.
 * Otherwise, it checks if the current time is more than 30 minutes past the due time.
 */
fun ScheduleEntity.effectiveStatus(now: LocalDateTime = LocalDateTime.now()): DoseStatus {
    if (status != DoseStatus.UPCOMING) return status // TAKEN or MISSED is final

    val due = LocalDateTime.ofInstant(
        date.toInstant(), ZoneId.systemDefault()
    ).withHour(hour).withMinute(minute)
    return if (now.isAfter(due.plusMinutes(30))) DoseStatus.MISSED
    else DoseStatus.UPCOMING
}