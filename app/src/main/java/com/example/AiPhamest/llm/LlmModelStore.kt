// file: LlmModelStore.kt
package com.example.AiPhamest.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object LlmModelStore {
    private const val TAG = "LlmModelStore"
    const val MODEL_FILENAME = "gemma-3n-E4B-it-int4.task"
    private const val MODEL_URL =
        "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/$MODEL_FILENAME"
    internal const val HF_TOKEN = "Use your token Here"

    private val client by lazy { OkHttpClient() }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Call this from any component to get a ready model file. */
    fun getModelFile(ctx: Context, onProgress: (Float) -> Unit = { }): File {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val finalFile = File(dir, MODEL_FILENAME)
        val tmpFile   = File(dir, "$MODEL_FILENAME.part")

        val expected = fetchRemoteSize()
        if (finalFile.exists()) {
            if (expected <= 0L || finalFile.length() == expected) {
                onProgress(1f)
                return finalFile
            }

            finalFile.delete()
        }
        if (tmpFile.exists()) tmpFile.delete()

        Log.i(TAG, "Downloading model ($expected bytes) to .part…")
        downloadFile(tmpFile, onProgress)


        if (expected > 0 && tmpFile.length() != expected) {
            tmpFile.delete()
            throw IllegalStateException(
                "Model size mismatch: downloaded ${tmpFile.length()} bytes, expected $expected bytes"
            )
        }
        if (!tmpFile.renameTo(finalFile)) {
            throw IllegalStateException("Failed to rename .part → final model file")
        }
        return finalFile
    }

    private fun fetchRemoteSize(): Long {
        return try {
            val headReq = Request.Builder()
                .head()
                .url(MODEL_URL)
                .header("Authorization", "Bearer $HF_TOKEN")
                .build()
            client.newCall(headReq).execute().use { res ->
                if (res.isSuccessful) res.header("Content-Length")?.toLongOrNull() ?: -1L
                else {
                    Log.w(TAG, "HEAD failed: HTTP ${res.code}"); -1L
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD exception: ${e.message}"); -1L
        }
    }

    private fun downloadFile(dest: File, onProgress: (Float) -> Unit) {
        val req = Request.Builder().url(MODEL_URL)
            .header("Authorization", "Bearer $HF_TOKEN").build()
        client.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "HTTP ${res.code}" }
            val body = res.body ?: error("Empty response body")
            val total = body.contentLength()
            dest.outputStream().use { out ->
                body.byteStream().copyToProgress(out, total, onProgress)
            }
        }
    }

    private fun InputStream.copyToProgress(
        out: FileOutputStream,
        total: Long,
        progress: (Float) -> Unit
    ) {
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var done = 0L
        var read: Int
        while (read(buf).also { read = it } != -1) {
            out.write(buf, 0, read)
            done += read
            if (total > 0) {
                val pct = done / total.toFloat()
                mainHandler.post { progress(pct.coerceAtMost(0.99f)) }
            }
        }
        mainHandler.post { progress(1f) }
    }
}
