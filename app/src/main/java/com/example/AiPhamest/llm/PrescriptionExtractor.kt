// THIS FILE NAME: PrescriptionExtractor.kt
@file:Suppress("SpellCheckingInspection")

package com.example.AiPhamest.llm

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.graphics.scale
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import kotlin.coroutines.resume

/**
 * Helper for model download + MediaPipe LLM usage (vision & text modes).
 *
 * Ensures full integrity of the downloaded model by comparing remote Content-Length,
 * and only renames the temp file when the sizes match.
 */
object PrescriptionExtractor {

    private const val TAG = "PrescriptionExtractor"


    @Volatile
    private var engine: LlmInference? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client by lazy { OkHttpClient() }



    /** Public API: OCR+NLP extraction from a bitmap. */
    suspend fun extract(
        ctx: Context,
        bitmap: Bitmap,
        onDownload: (Float) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        ensureEngine(ctx, onDownload)
        val session = freshSession(ctx, engine!!, deterministic = false)
        session.addQueryChunk(SYSTEM_PROMPT)
        session.addImage(BitmapImageBuilder(bitmap.forModel()).build())
        generateBlocking(session)
    }

    /** Public API: text-only extraction (deterministic). */
    suspend fun textOnlyExtract(
        ctx: Context,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        ensureEngine(ctx)
        val session = freshSession(ctx, engine!!, deterministic = true)
        session.addQueryChunk(prompt)
        generateBlocking(session)
    }

    /** Frees engine resources if needed. */
    fun close() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
        Log.d(TAG, "LLM engine closed.")
    }

    /** Creates a fresh LLM session with optional deterministic sampling. */
    private fun freshSession(
        ctx: Context,
        engine: LlmInference,
        deterministic: Boolean
    ): LlmInferenceSession {
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(!deterministic)
                    .build()
            )
            .apply {
                if (deterministic) setTopK(1).setTopP(0f).setTemperature(0f)
                else setTopK(20).setTopP(0.8f).setTemperature(0.7f)
            }
            .build()

        return LlmInferenceSession.createFromOptions(engine, opts)
    }

    /** Ensures the LLM engine is initialized. */
    suspend fun ensureEngine(
        ctx: Context,
        onDownload: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext

        val modelFile = ensureModel(ctx, onDownload)
        val llmOpts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .setMaxNumImages(1)
            .setMaxTokens(1000)
            .build()
        engine = LlmInference.createFromOptions(ctx, llmOpts)
        Log.i(TAG, "Gemma model ready…")
    }
    /**
     * Downloads the model into `.part`, verifies size, then renames atomically.
     */
    private suspend fun ensureModel(
        ctx: Context,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        LlmModelStore.getModelFile(ctx, onProgress)
    }


    /** Blocks until the LLM finishes streaming its response. */
    private suspend fun generateBlocking(sess: LlmInferenceSession): String =
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            sess.generateResponseAsync { part, done ->
                sb.append(part)
                if (done && cont.isActive) {
                    val out = sb.toString().substringBefore(STOP_SEQ).trim()
                    cont.resume(out)
                    sess.close()
                }
            }
        }

    private const val STOP_SEQ = "###END###"

    /** System prompt instructing the extractor to append STOP_SEQ. */
    private val SYSTEM_PROMPT = """
        You are a clinical assistant. Your task is to extract structured data ONLY from visible, clearly readable prescription lines.
        
        Each output line MUST follow this exact format:
        <name>|<strength_and_unit>|<dose (number)>|<frequency_in_hours>
        
        Rules:
        - Do NOT guess or assume drug names, strengths, doses, or frequencies.
        - Only extract data if fully readable and clearly structured.
        - Extract the dose as a numeric value (e.g., "1 tab" → "1", "2 tablets" → "2")
        - Convert frequency abbreviations to hours between doses:
          - BID → 12
          - TID → 8
          - QID → 6
          - QD → 24
          - BIW → 84
          - TIW → 56
          - QOD → 48
          - Once weekly → 168
        - Omit any drug line if parts are illegible or uncertain.
        - Separate each line with a line break.
        - Output ONLY valid lines in this format.
        - End response with this literal text: ###END###
        
        DO NOT invent or complete missing data. If unclear, SKIP the line entirely.
    """.trimIndent()

    /** Use KTX to scale down very large bitmaps. */
    private fun Bitmap.forModel(maxSide: Int = 768): Bitmap {
        if (width <= maxSide && height <= maxSide) return this
        val ratio = if (width >= height)
            maxSide / width.toFloat()
        else
            maxSide / height.toFloat()
        val w = (width * ratio).toInt().coerceAtLeast(1)
        val h = (height * ratio).toInt().coerceAtLeast(1)
        return this.scale(w, h, true)
    }
}