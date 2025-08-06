// THIS FILE NAME: SideEffectChecker.kt
package com.example.AiPhamest.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.*
import com.example.AiPhamest.data.AppDatabase
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Analyzes side effects using its own dedicated LLM engine instance.
 * Processes analysis in background using WorkManager.
 */
object SideEffectChecker {

    private const val TAG = "SideEffectChecker"
    private const val WORK_TAG = "side_effect_analysis"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L
    private val analysisCache = mutableMapOf<String, AnalysisResult>()
    private const val CACHE_SIZE_LIMIT = 100



    @Volatile
    private var engine: LlmInference? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client by lazy { OkHttpClient() }

    data class AnalysisRequest(
        val sideEffectId: Int,
        val description: String,
        val medications: List<String>,
        val chronicDiseases: List<String>,
        val allergies: List<String>,
        val currentMedications: List<String>,
        val gender: String
    )

    data class AnalysisResult(
        val drugPossibleCause: String?,
        val warningType: String,
        val severity: String,
        val confidence: Float? = null,
        val reasoning: String? = null,
        val recommendations: List<String>? = null
    )
    private fun getCacheKey(request: AnalysisRequest): String {
        return "${request.description.hashCode()}_${request.currentMedications.hashCode()}"
    }

    suspend fun analyzeSideEffectWithRetry(
        context: Context,
        request: AnalysisRequest,
        retryCount: Int = 0
    ): AnalysisResult? = withContext(Dispatchers.IO) {
        try {
            analyzeSideEffect(context, request)
        } catch (e: Exception) {
            Log.w(TAG, "Analysis attempt ${retryCount + 1} failed", e)

            if (retryCount < MAX_RETRIES) {
                delay(RETRY_DELAY_MS * (retryCount + 1))
                analyzeSideEffectWithRetry(context, request, retryCount + 1)
            } else {
                performBasicAnalysis(request)
            }
        }
    }

    private fun performBasicAnalysis(request: AnalysisRequest): AnalysisResult {
        val keywords = listOf("rash", "itching", "swelling", "difficulty breathing")
        val isAllergic = keywords.any { request.description.contains(it, ignoreCase = true) }

        return AnalysisResult(
            drugPossibleCause = request.currentMedications.firstOrNull(),
            warningType = if (isAllergic) "Allergy" else "SideEffect",
            severity = if (isAllergic) "HIGH" else "MEDIUM"
        )
    }

    fun analyzeInBackground(
        context: Context,
        request: AnalysisRequest,
        onComplete: ((AnalysisResult?) -> Unit)? = null
    ) {
        val workData = workDataOf(
            "sideEffectId" to request.sideEffectId,
            "description" to request.description,
            "medications" to request.medications.joinToString("|"),
            "chronicDiseases" to request.chronicDiseases.joinToString("|"),
            "allergies" to request.allergies.joinToString("|"),
            "currentMedications" to request.currentMedications.joinToString("|"),
            "gender" to request.gender
        )

        val workRequest = OneTimeWorkRequestBuilder<SideEffectAnalysisWorker>()
            .setInputData(workData)
            .setConstraints(Constraints.Builder().build())
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)

        onComplete?.let { callback ->
            WorkManager.getInstance(context)
                .getWorkInfoByIdLiveData(workRequest.id)
                .observeForever { info ->
                    info?.let { wi ->
                        if (wi.state.isFinished) {
                            val result = if (wi.state == WorkInfo.State.SUCCEEDED) {
                                parseWorkResult(wi.outputData)
                            } else null
                            callback(result)
                        }
                    }
                }
        }
    }

    suspend fun analyzeSideEffect(
        context: Context,
        request: AnalysisRequest
    ): AnalysisResult? {
        val cacheKey = getCacheKey(request)
        analysisCache[cacheKey]?.let { return it }

        val result = withContext(Dispatchers.IO) {
            try {
                ensureEngine(context) { progress ->
                    Log.d(TAG, "SideEffectChecker model loading: ${(progress * 100).toInt()}%")
                }
                val prompt = buildAnalysisPrompt(request)
                val rawResponse = generateAnalysis(prompt)
                Log.d(TAG, "analyzeSideEffect: RAW LLM RESPONSE:\n$rawResponse")
                val parsedResult = parseAnalysisResult(rawResponse)
                Log.d(TAG, "analyzeSideEffect: PARSED RESULT: $parsedResult")
                parsedResult
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                null
            }
        }
        result?.let {
            if (analysisCache.size >= CACHE_SIZE_LIMIT) {
                analysisCache.clear()
            }
            analysisCache[cacheKey] = it
        }
        return result
    }

    fun close() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
        Log.d(TAG, "SideEffectChecker LLM engine closed.")
    }

    private suspend fun ensureEngine(
        ctx: Context,
        onDownload: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext

        val modelFile = LlmModelStore.getModelFile(ctx, onDownload)
        val llmOpts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(1000) // Keep the specific token setting
            .build()
        engine = LlmInference.createFromOptions(ctx, llmOpts)
        Log.i(TAG, "SideEffectChecker Gemma model ready…")
    }

    private suspend fun generateAnalysis(prompt: String): String = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("LLM engine not initialized")
        val session = createAnalysisSession(currentEngine)
        session.addQueryChunk(prompt)
        generateBlocking(session)
    }

    private fun createAnalysisSession(llmEngine: LlmInference): LlmInferenceSession {
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(10)
            .setTopP(0.9f)
            .setTemperature(0.3f)
            .build()
        return LlmInferenceSession.createFromOptions(llmEngine, opts)
    }

    private suspend fun generateBlocking(session: LlmInferenceSession): String =
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            session.generateResponseAsync { part, done ->
                sb.append(part)
                if (done && cont.isActive) {
                    cont.resume(sb.toString().trim())
                    session.close()
                }
            }
        }

    // --- buildAnalysisPrompt, parseAnalysisResult, parseWorkResult remain the same ---

    private fun buildAnalysisPrompt(request: AnalysisRequest): String {
        return """
        You are a clinical pharmacist AI assistant analyzing a potential adverse drug reaction.

        PATIENT PROFILE:
        - Gender: ${request.gender}
        - Chronic Conditions: ${request.chronicDiseases.joinToString(", ").ifEmpty { "None" }}
        - Known Allergies: ${request.allergies.joinToString(", ").ifEmpty { "None" }}

        MEDICATION HISTORY:
        - Current Medications: ${request.currentMedications.joinToString(", ").ifEmpty { "None" }}
        - All Database Medications: ${request.medications.joinToString(", ").ifEmpty { "None" }}

        REPORTED SIDE EFFECT:
        "${request.description}"

        ANALYSIS FRAMEWORK:
        1. Temporal relationship: Could timing align with medication start/dose changes?
        2. Known side effect profile: Is this a documented reaction for any current medications?
        3. Dose-response relationship: Does severity correlate with dosage?
        4. Alternative explanations: Could this be disease progression or other causes?
        5. Allergic vs non-allergic: Signs of immune-mediated reaction?

        SEVERITY CRITERIA:
        - LOW: Mild discomfort, no functional impairment, self-limiting
        - MEDIUM: Moderate symptoms, some functional impact, monitoring needed
        - HIGH: Severe symptoms, significant impairment, immediate attention required

        CONFIDENCE ASSESSMENT:
        Rate your confidence in the analysis (0.0-1.0) based on:
        - Clarity of temporal relationship
        - Known pharmacological mechanisms
        - Specificity of symptoms

        OUTPUT FORMAT (JSON only):
        {
            "drugPossibleCause": "medication_name_or_null",
            "warningType": "SideEffect_or_Allergy_or_Unknown",
            "severity": "LOW_or_MEDIUM_or_HIGH",
            "confidence": 0.0_to_1.0,
            "reasoning": "brief_clinical_rationale",
            "recommendations": ["action1", "action2"]
        }
    """.trimIndent()
    }

    private fun parseAnalysisResult(response: String): AnalysisResult? {
        return try {
            // Extract JSON from response (in case there's extra text)
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1

            if (jsonStart == -1 || jsonEnd <= jsonStart) {
                Log.e(TAG, "No valid JSON found in response")
                return null
            }

            val jsonString = response.substring(jsonStart, jsonEnd)
            val json = JSONObject(jsonString)
            val recommendationsJson = json.optJSONArray("recommendations")
            val recommendationsList = if (recommendationsJson != null) {
                List(recommendationsJson.length()) { i -> recommendationsJson.getString(i) }
            } else {
                null
            }

            AnalysisResult(
                drugPossibleCause = if (json.isNull("drugPossibleCause")) null else json.getString("drugPossibleCause"),
                warningType = json.getString("warningType"),
                severity = json.getString("severity"),
                confidence = json.optDouble("confidence", -1.0).toFloat().takeIf { it >= 0 },
                reasoning = json.optString("reasoning", null),
                recommendations = recommendationsList
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse analysis result: $response", e)
            null
        }
    }

    private fun parseWorkResult(outputData: Data): AnalysisResult? {
        return try {
            val drugCause = outputData.getString("drugPossibleCause")
            val warningType = outputData.getString("warningType") ?: return null
            val severity = outputData.getString("severity") ?: return null
            val confidence = outputData.getFloat("confidence", 0.8f)

            // ✅ EXTRACT REASONING AND RECOMMENDATIONS:
            val reasoning = outputData.getString("reasoning")
            val recommendationsString = outputData.getString("recommendations")
            val recommendations = recommendationsString?.split("||")?.filter { it.isNotBlank() }

            AnalysisResult(
                drugPossibleCause = drugCause,
                warningType = warningType,
                severity = severity,
                confidence = confidence,
                reasoning = reasoning,
                recommendations = recommendations
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse work result", e)
            null
        }
    }
}


// The SideEffectAnalysisWorker class remains unchanged
class SideEffectAnalysisWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "SideEffectAnalysisWorker"

    override suspend fun doWork(): Result {
        return try {
            val request = parseInputData(inputData) ?: return Result.failure()
            setProgress(workDataOf("status" to "Starting analysis"))
            val result = SideEffectChecker.analyzeSideEffectWithRetry(applicationContext, request)

            if (result != null) {
                setProgress(workDataOf("status" to "Analysis complete"))
                storeAnalysisResult(request.sideEffectId, result)

                // ✅ INCLUDE ALL FIELDS IN OUTPUT DATA:
                val outputData = workDataOf(
                    "drugPossibleCause" to result.drugPossibleCause,
                    "warningType" to result.warningType,
                    "severity" to result.severity,
                    "sideEffectId" to request.sideEffectId,
                    "confidence" to (result.confidence ?: 0.8f),
                    "reasoning" to result.reasoning,
                    "recommendations" to (result.recommendations?.joinToString("||")) // Use || as separator
                )
                Result.success(outputData)
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker execution failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun storeAnalysisResult(sideEffectId: Int, result: SideEffectChecker.AnalysisResult) {
        val db = AppDatabase.get(applicationContext)
        // val analysisEntity = AnalysisResultEntity(sideEffectId = sideEffectId, ... )
        // db.analysisResultDao().insert(analysisEntity)
    }

    private fun parseInputData(data: Data): SideEffectChecker.AnalysisRequest? {
        return try {
            SideEffectChecker.AnalysisRequest(
                sideEffectId = data.getInt("sideEffectId", -1),
                description = data.getString("description") ?: return null,
                medications = data.getString("medications")?.split("|") ?: emptyList(),
                chronicDiseases = data.getString("chronicDiseases")?.split("|") ?: emptyList(),
                allergies = data.getString("allergies")?.split("|") ?: emptyList(),
                currentMedications = data.getString("currentMedications")?.split("|") ?: emptyList(),
                gender = data.getString("gender") ?: "Unknown"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse input data", e)
            null
        }
    }
}