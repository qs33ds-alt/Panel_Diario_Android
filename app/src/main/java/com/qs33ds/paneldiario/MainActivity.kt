package com.qs33ds.paneldiario

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

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
        @JavascriptInterface fun getUnlockStats(): String = buildUnlockStatsJson()
        @JavascriptInterface fun openUsageAccessSettings() = runOnUiThread {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun buildUnlockStatsJson(): String {
        val root = JSONObject()
        root.put("available", android.os.Build.VERSION.SDK_INT >= 28)
        val granted = android.os.Build.VERSION.SDK_INT >= 28 && hasUsageAccess()
        root.put("permission", granted)
        if (!granted) {
            root.put("today", JSONObject.NULL)
            root.put("avg7", JSONObject.NULL)
            root.put("days", JSONArray())
            return root.toString()
        }
        return try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startDate = today.minusDays(6)
            val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = System.currentTimeMillis() + 1000L
            val counts = linkedMapOf<LocalDate, Int>()
            for (i in 0L..6L) counts[startDate.plusDays(i)] = 0
            val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = manager.queryEvents(startMs, endMs)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                    val day = java.time.Instant.ofEpochMilli(event.timeStamp).atZone(zone).toLocalDate()
                    if (counts.containsKey(day)) counts[day] = (counts[day] ?: 0) + 1
                }
            }
            val days = JSONArray()
            var total = 0
            counts.forEach { (day, count) ->
                total += count
                days.put(JSONObject().put("date", day.toString()).put("count", count))
            }
            root.put("today", counts[today] ?: 0)
            root.put("avg7", total / 7.0)
            root.put("days", days)
            root.toString()
        } catch (_: Exception) {
            root.put("permission", false)
            root.put("today", JSONObject.NULL)
            root.put("avg7", JSONObject.NULL)
            root.put("days", JSONArray())
            root.toString()
        }
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
    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.postDelayed({ webView.evaluateJavascript("window.loadUnlockStats&&window.loadUnlockStats();", null) }, 250L)
    }
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
