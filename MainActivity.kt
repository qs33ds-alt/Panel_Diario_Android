package com.qs33ds.paneldiario

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

class MainActivity : ComponentActivity(), RecognitionListener {
    private lateinit var webView: WebView
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceActive = false
    private val voiceHandler = Handler(Looper.getMainLooper())
    private val restartVoice = Runnable { if (voiceActive) startRecognizerCycle() }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecognition()
        else js("androidVoiceError", "Necesito permiso de micrófono para añadir tareas por voz.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            webViewClient = WebViewClient()
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        }
        setContentView(webView)
        if (savedInstanceState == null) webView.loadUrl("file:///android_asset/index.html")
        else webView.restoreState(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    inner class AndroidBridge {
        @JavascriptInterface fun startVoice() = runOnUiThread {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecognition()
            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        @JavascriptInterface fun stopVoice() = runOnUiThread { stopRecognition() }
    }

    private fun beginRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("androidVoiceError", "El reconocimiento de voz no está disponible en este dispositivo.")
            return
        }
        voiceHandler.removeCallbacks(restartVoice)
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        voiceActive = true
        js("androidVoiceStart")
        startRecognizerCycle()
    }

    private fun startRecognizerCycle() {
        if (!voiceActive) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        }
        try { speechRecognizer?.startListening(intent) }
        catch (_: Exception) { voiceHandler.postDelayed(restartVoice, 250L) }
    }

    private fun stopRecognition() {
        voiceActive = false
        voiceHandler.removeCallbacks(restartVoice)
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        js("androidVoiceEnd")
    }

    private fun js(function: String, value: String? = null) {
        val call = if (value == null) "window.$function&&window.$function();"
        else "window.$function&&window.$function(${JSONObject.quote(value)});"
        webView.evaluateJavascript(call, null)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { js("androidVoiceInterim", it) }
    }
    override fun onResults(results: Bundle?) {
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { js("androidVoiceFinal", it) }
        if (voiceActive) voiceHandler.postDelayed(restartVoice, 180L)
    }
    override fun onError(error: Int) {
        if (!voiceActive) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                voiceHandler.postDelayed(restartVoice, 180L)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                voiceActive = false
                js("androidVoiceError", "Necesito permiso de micrófono.")
                js("androidVoiceEnd")
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                js("androidVoiceError", "El servicio de voz no ha respondido. Reintentando…")
                voiceHandler.postDelayed(restartVoice, 600L)
            }
            else -> {
                js("androidVoiceError", "No he podido reconocer bien ese fragmento. Puedes seguir hablando.")
                voiceHandler.postDelayed(restartVoice, 300L)
            }
        }
    }

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onSaveInstanceState(outState: Bundle) { webView.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        voiceActive = false
        voiceHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}
