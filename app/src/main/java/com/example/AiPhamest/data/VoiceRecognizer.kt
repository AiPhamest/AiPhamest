// VoiceRecognizer.kt

package com.example.AiPhamest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

import android.util.Log

fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

class VoiceRecognizer(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {
    private var recognizer: SpeechRecognizer? = null
    private var lastPartialResult: String? = null // <-- NEW

    fun startListening() {
        if (!hasRecordAudioPermission(context)) {
            onError?.invoke("Microphone permission missing.")
            return
        }

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("VoiceRecognizer", "Ready for speech")
                        lastPartialResult = null // reset for new session
                    }
                    override fun onBeginningOfSpeech() {
                        Log.d("VoiceRecognizer", "Speech started")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.d("VoiceRecognizer", "Speech ended")
                    }
                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error - check internet connection"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout - try again"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected - please try speaking more clearly"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy - try again in a moment"
                            SpeechRecognizer.ERROR_SERVER -> "Speech service unavailable - try again later"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected - try again"
                            else -> "Speech recognition error - please try again"
                        }
                        Log.e("VoiceRecognizer", "Speech error: $error - $errorMsg")
                        onError?.invoke(errorMsg)
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        Log.d("VoiceRecognizer", "Results: $matches")
                        if (!matches.isNullOrEmpty()) {
                            onResult(matches[0])
                        } else if (!lastPartialResult.isNullOrBlank()) {
                            Log.d("VoiceRecognizer", "Using fallback partial: $lastPartialResult")
                            onResult(lastPartialResult!!)
                        } else {
                            onError?.invoke("No speech recognized - please try again")
                        }
                        lastPartialResult = null // clear for next recognition
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            lastPartialResult = matches[0]
                        }
                        Log.d("VoiceRecognizer", "Partial results: $matches")
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your side effect...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                // These values can be tweaked if you get too many/too few results or too short a timeout
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                // Remove or comment out the following line if you want to allow short utterances:
                // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
            }

            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("VoiceRecognizer", "Failed to start recognition", e)
            onError?.invoke("Failed to start voice recognition: ${e.message}")
        }
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        lastPartialResult = null
    }
}
