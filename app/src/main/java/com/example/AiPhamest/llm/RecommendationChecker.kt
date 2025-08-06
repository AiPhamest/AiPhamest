// THIS FILE NAME: RecommendationChecker.kt
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
 * Generates brief, practical drug-use recommendations (e.g., "take at night",
 * "drink plenty of water", "take after food") using the same on-device LLM flow
 * as SideEffectChecker. Runs in background via WorkManager and stores results
 * on the prescription row.
 */
object RecommendationChecker {

    private const val TAG = "RecommendationChecker"
    private const val WORK_TAG = "drug_recommendations"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L

    private val cache = mutableMapOf<String, List<String>>()
    private const val CACHE_SIZE_LIMIT = 200



    @Volatile private var engine: LlmInference? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client by lazy { OkHttpClient() }

    data class RequestData(
        val prescriptionId: Int,
        val drugName: String
    )

    /** Enqueue background work (idempotent-ish; caller can guard with "if not saved"). */
    fun enqueue(context: Context, presId: Int, drugName: String) {
        // TIP: Optionally cancel any old stuck jobs before enqueueing (usually needed only after major updates)
        // WorkManager.getInstance(context).cancelUniqueWork("$WORK_TAG-$presId")

        val data = workDataOf(
            "prescriptionId" to presId,
            "drugName" to drugName
        )
        val req = OneTimeWorkRequestBuilder<RecommendationWorker>()
            .setInputData(data)
            .addTag("$WORK_TAG-$presId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_TAG-$presId",
            ExistingWorkPolicy.REPLACE, // was KEEP; now REPLACE for re-runs
            req
        )
    }

    /** Direct (suspending) API with retries; used by the Worker. */
    suspend fun getRecommendationsWithRetry(
        context: Context,
        drugName: String,
        retry: Int = 0
    ): List<String>? = withContext(Dispatchers.IO) {
        val key = drugName.lowercase().trim()
        cache[key]?.let { return@withContext it }

        try {
            ensureEngine(context)
            val raw = generate(buildPrompt(drugName))
            val list = parse(raw)
            if (list != null) {
                if (cache.size >= CACHE_SIZE_LIMIT) cache.clear()
                cache[key] = list
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Attempt ${retry + 1} failed for '$drugName'", e)
            if (retry < MAX_RETRIES) {
                delay(RETRY_DELAY_MS * (retry + 1))
                getRecommendationsWithRetry(context, drugName, retry + 1)
            } else null
        }
    }

    fun close() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }

    // ---------------- LLM plumbing (same pattern as SideEffectChecker) ----------------

    private suspend fun ensureEngine(ctx: Context) = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        val modelFile = LlmModelStore.getModelFile(ctx) { p ->
            Log.d(TAG, "Recommendation model loading ${(p * 100).toInt()}%")
        }
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(600)
            .build()
        engine = LlmInference.createFromOptions(ctx, opts)
        Log.i(TAG, "RecommendationChecker Gemma model ready")
    }

    private suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val e = engine ?: throw IllegalStateException("LLM engine not initialized")
        val sess = LlmInferenceSession.createFromOptions(
            e,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(10)
                .setTopP(0.9f)
                .setTemperature(0.2f)
                .build()
        )
        sess.addQueryChunk(prompt)
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            sess.generateResponseAsync { part, done ->
                sb.append(part)
                if (done && cont.isActive) {
                    cont.resume(sb.toString().trim())
                    sess.close()
                }
            }
        }
    }

    // ---------------- Prompt + parsing ----------------

    private fun buildPrompt(drugName: String): String = """
        You are a clinical pharmacist assistant. The user will give a single medication name.
        Return ONLY JSON with short, practical recommendations for taking/using it.

        Medication: "$drugName"

        RULES:
        - Output valid JSON only. No prose.
        - Keep each recommendation concise (max ~8 words).
        - Focus on general-use, safe, common-sense guidance.
        - Do not include side-effects, warnings, or dosing changes.
        - Prefer items like: "take after food", "take at night",
          "drink plenty of water", "avoid alcohol", "do not crush",
          "stay consistent daily time", "store at room temperature".
        - Provide 3–8 recommendations.

        JSON FORMAT:
        {
          "drug": "string",
          "recommendations": ["string", "string", "string", ...]
        }
    """.trimIndent()

    private fun parse(raw: String): List<String>? {
        Log.d("RecommendationChecker", "LLM RAW: $raw")
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        val json = JSONObject(raw.substring(start, end + 1))
        val arr = json.optJSONArray("recommendations") ?: return null
        return List(arr.length()) { i ->
            arr.optString(i).trim()
        }.filter { it.isNotBlank() }
    }
}

/** WorkManager worker that fetches and stores the recommendations into prescriptions.recommendations (JSON). */
class RecommendationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "RecommendationWorker" // Make sure this tag is unique and easy to find

    override suspend fun doWork(): Result {
        val presId = inputData.getInt("prescriptionId", -1)
        val drugName = inputData.getString("drugName")

        if (presId <= 0 || drugName.isNullOrBlank()) {
            Log.e(TAG, "Worker stopping: Invalid input data. presId=$presId, drugName=$drugName")
            return Result.failure() // Permanent failure, don't retry
        }

        // --- ENHANCED LOGGING FOR DEBUGGING ---
        Log.i(TAG, "Starting work for '$drugName' (ID: $presId). Attempt count: $runAttemptCount")

        try {
            // STEP 1: Fetch recommendations from the checker, which handles the model and LLM.
            Log.d(TAG, "==> STEP 1: Calling RecommendationChecker to get recommendations.")
            val list = RecommendationChecker.getRecommendationsWithRetry(applicationContext, drugName)

            if (list == null) {
                // This is a critical failure point. It means the checker failed after all its retries.
                // This could be due to a failed model download, a corrupt model file, or the LLM
                // consistently failing to generate a valid response.
                Log.e(TAG, "<== STEP 1 FAILED: RecommendationChecker returned null for '$drugName'.")
                // We retry a few times as the network or model might just be initializing.
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
            Log.d(TAG, "<== STEP 1 SUCCESS: Received ${list.size} recommendation(s).")


            // STEP 2: Build the JSON object to store in the database.
            Log.d(TAG, "==> STEP 2: Building JSON object.")
            val json = JSONObject().apply {
                put("drug", drugName)
                // Using org.json.JSONArray to be explicit
                put("recommendations", org.json.JSONArray(list))
            }.toString()
            Log.d(TAG, "<== STEP 2 SUCCESS: JSON created.")


            // STEP 3: Update the database with the final JSON string.
            Log.d(TAG, "==> STEP 3: Updating database for prescription ID $presId.")
            AppDatabase.get(applicationContext)
                .prescriptionDao()
                .updateRecommendations(presId, json)

            Log.i(TAG, "WORKER FINISHED SUCCESSFULLY for '$drugName' (ID: $presId).")
            return Result.success(workDataOf("count" to list.size))

        } catch (e: Exception) {
            // This will catch any other unexpected errors (e.g., during JSON creation, DB update).
            Log.e(TAG, "WORKER FAILED with an unexpected exception for '$drugName'", e)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
