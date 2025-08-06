package com.example.AiPhamest.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Define the structure of your JSON warning
data class WarningJson(
    val title: String,
    @SerializedName("drug possible cause")
    val drugPossibleCause: String,
    val warningType: String,
    val severity: String,
    val createdAt: Long? = null,
    val confidence: Float? = null, // Add confidence scoring
    val references: List<String>? = null, // Medical references (maps to recommendations)
    val reasoning: String? = null // Add reasoning to match entity
) {
    // Validation at the model level
    fun isValid(): Boolean {
        return title.isNotBlank() &&
                title.length <= 200 &&
                drugPossibleCause.isNotBlank() &&
                warningType in listOf("SideEffect", "Allergy", "Interaction", "Unknown") &&
                severity.uppercase() in listOf("LOW", "MEDIUM", "HIGH")
    }
}


object WarningGenerator {
    private const val TAG = "WarningGenerator"

    suspend fun generateAndSaveWarnings(
        jsonString: String,
        repo: WarningRepository
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val warnings = parseWarnings(jsonString)
            if (warnings.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No valid warnings found"))
            }

            var savedCount = 0
            warnings.forEach { w ->
                // Use the model's own validation method
                if (w.isValid()) {
                    val severity = parseSeverity(w.severity)

                    // --- THIS CALL IS NOW CORRECTED ---
                    repo.add(
                        title = w.title.take(200),
                        drugPossibleCause = w.drugPossibleCause.take(100),
                        severity = severity,
                        warningType = w.warningType,
                        createdAt = w.createdAt,
                        // Pass the new fields to the repository
                        reasoning = w.reasoning,
                        recommendations = w.references, // 'references' from JSON maps to 'recommendations'
                        confidence = w.confidence
                    )
                    savedCount++
                } else {
                    Log.w(TAG, "Skipped invalid warning: ${w.title}")
                }
            }

            Result.success(savedCount)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process warnings", e)
            Result.failure(e)
        }
    }

    private fun parseWarnings(jsonString: String): List<WarningJson> {
        return try {
            Gson().fromJson(jsonString, object : TypeToken<List<WarningJson>>() {}.type)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing failed, attempting single warning parse", e)
            try {
                listOf(Gson().fromJson(jsonString, WarningJson::class.java))
            } catch (e2: Exception) {
                Log.e(TAG, "All parsing attempts failed", e2)
                emptyList()
            }
        }
    }

    // This function is no longer needed as we use w.isValid()
    // private fun validateWarning(warning: WarningJson): Boolean { ... }

    private fun parseSeverity(severityStr: String): Severity {
        return when (severityStr.uppercase()) {
            "LOW", "MILD" -> Severity.LOW
            "HIGH", "SEVERE", "CRITICAL" -> Severity.HIGH
            else -> Severity.MEDIUM
        }
    }
}