package ir.dana.assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class DanaVoiceService : Service() {

    companion object {
        private const val TAG = "DanaVoiceService"
        private const val CHANNEL_ID = "dana_voice_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TRIGGER_LISTEN = "ir.dana.assistant.ACTION_TRIGGER_LISTEN"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var systemExecutor: SystemActionExecutor
    private lateinit var aiAgent: DanaAiAgent
    private lateinit var persianSpeaker: PersianVoiceSpeaker

    private var windowManager: WindowManager? = null
    private var floatingBubble: TextView? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isDirectFollowUpMode = false
    private var isExecutingOrSpeaking = false
    private var wakeWordEnabled = true
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        systemExecutor = SystemActionExecutor(this)
        aiAgent = DanaAiAgent(this, systemExecutor)
        persianSpeaker = PersianVoiceSpeaker(this)

        acquirePersistentCpuWakeLock()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("شنود دائمی پس‌زمینه فعال است — کافی است بگویید «دانا ...»")
        )

        val prefs = getSharedPreferences("dana_prefs", Context.MODE_PRIVATE)
        wakeWordEnabled = prefs.getBoolean("wake_word_enabled", true)

        setupFloatingBubble()
        initSpeechRecognizer()

        if (wakeWordEnabled) {
            scheduleNextListen(delayMs = 200L, directMode = false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("dana_prefs", Context.MODE_PRIVATE)
        wakeWordEnabled = prefs.getBoolean("wake_word_enabled", true)

        if (intent?.action == ACTION_TRIGGER_LISTEN) {
            wakeUpScreenIfNeeded()
            isExecutingOrSpeaking = true
            persianSpeaker.speakPersian("جانم؟") {
                isExecutingOrSpeaking = false
                scheduleNextListen(delayMs = 150L, directMode = true)
            }
        } else if (wakeWordEnabled && !isExecutingOrSpeaking) {
            scheduleNextListen(delayMs = 200L, directMode = false)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartIntent = Intent(applicationContext, DanaVoiceService::class.java)
        startForegroundService(restartIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        persianSpeaker.shutdown()
        floatingBubble?.let { windowManager?.removeView(it) }
        cpuWakeLock?.let { if (it.isHeld) it.release() }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquirePersistentCpuWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DanaAssistant:ContinuousVoiceWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در دریافت PartialWakeLock", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpScreenIfNeeded() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DanaAssistant:ScreenWakeLock"
                )
                wl.acquire(10_000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در روشن کردن صفحه", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        if (!android.provider.Settings.canDrawOverlays(this)) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val bubble = TextView(this).apply {
            text = "دانا 🟢"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(32, 20, 32, 20)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 60f
                setColor(Color.parseColor("#2E7D32"))
                setStroke(4, Color.WHITE)
            }
            elevation = 16f
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 400
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (dx < 15 && dy < 15) {
                        isExecutingOrSpeaking = true
                        persianSpeaker.speakPersian("جانم؟") {
                            isExecutingOrSpeaking = false
                            scheduleNextListen(delayMs = 150L, directMode = true)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        floatingBubble = bubble
        windowManager?.addView(bubble, params)
    }

    private fun updateBubbleUi(stateText: String, colorHex: String) {
        mainHandler.post {
            floatingBubble?.apply {
                text = stateText
                (background as? GradientDrawable)?.setColor(Color.parseColor(colorHex))
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (isDirectFollowUpMode) {
                        updateBubbleUi("بفرمایید... 🎙️", "#43A047")
                    } else {
                        updateBubbleUi("دانا 🟢", "#2E7D32")
                    }
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (isExecutingOrSpeaking) return
                    isDirectFollowUpMode = false
                    updateBubbleUi("دانا 🟢", "#2E7D32")

                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                        initSpeechRecognizer()
                    }
                    if (wakeWordEnabled) {
                        scheduleNextListen(delayMs = 350L, directMode = false)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                    handleRecognizedSpeech(spokenText)
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun scheduleNextListen(delayMs: Long, directMode: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isExecutingOrSpeaking) {
                startListeningInternal(directMode)
            }
        }, delayMs)
    }

    private fun startListeningInternal(directMode: Boolean) {
        isDirectFollowUpMode = directMode
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            if (!directMode) {
                muteSystemBeepTemporarily()
            }
            speechRecognizer?.cancel()
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "خطا در شروع شنود پس‌زمینه", e)
            if (wakeWordEnabled) {
                scheduleNextListen(delayMs = 1000L, directMode = false)
            }
        }
    }

    private fun muteSystemBeepTemporarily() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            mainHandler.postDelayed({
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
            }, 400L)
        } catch (_: Exception) {}
    }

    private fun handleRecognizedSpeech(spokenText: String) {
        if (spokenText.isEmpty()) {
            if (wakeWordEnabled) scheduleNextListen(delayMs = 250L, directMode = false)
            return
        }

        Log.i(TAG, "متن فارسی شنیده‌شده در پس‌زمینه: $spokenText")

        if (!isDirectFollowUpMode) {
            if (spokenText.contains("دانا") || spokenText.lowercase().contains("dana")) {
                wakeUpScreenIfNeeded()
                val commandAfterWakeWord = spokenText
                    .replace("دانا", "")
                    .replace("dana", "", ignoreCase = true)
                    .trim()

                if (commandAfterWakeWord.length > 2) {
                    executeUserCommand(commandAfterWakeWord)
                } else {
                    isExecutingOrSpeaking = true
                    persianSpeaker.speakPersian("جانم؟ بفرمایید") {
                        isExecutingOrSpeaking = false
                        scheduleNextListen(delayMs = 150L, directMode = true)
                    }
                }
            } else if (wakeWordEnabled) {
                scheduleNextListen(delayMs = 200L, directMode = false)
            }
            return
        }

        isDirectFollowUpMode = false
        val cleanedCommand = spokenText.removePrefix("دانا").trim().ifEmpty { spokenText }
        executeUserCommand(cleanedCommand)
    }

    private fun executeUserCommand(command: String) {
        isExecutingOrSpeaking = true
        updateBubbleUi("در حال انجام... ⚡", "#FB8C00")
        serviceScope.launch {
            var lastReply = ""
            aiAgent.processUserCommand(command) { responseSpeech ->
                lastReply = responseSpeech
            }
            if (lastReply.isNotBlank()) {
                persianSpeaker.speakPersian(lastReply) {
                    isExecutingOrSpeaking = false
                    updateBubbleUi("دانا 🟢", "#2E7D32")
                    if (wakeWordEnabled) {
                        scheduleNextListen(delayMs = 250L, directMode = false)
                    }
                }
            } else {
                isExecutingOrSpeaking = false
                updateBubbleUi("دانا 🟢", "#2E7D32")
                if (wakeWordEnabled) {
                    scheduleNextListen(delayMs = 250L, directMode = false)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سرویس شنود دائمی دستیار دانا",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val listenIntent = Intent(this, DanaVoiceService::class.java).apply {
            action = ACTION_TRIGGER_LISTEN
        }
        val pendingListen = PendingIntent.getService(
            this, 1, listenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("دانا در پس‌زمینه آماده فرمان است 🟢")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_btn_speak_now, "صحبت مستقیم", pendingListen)
            .setOngoing(true)
            .build()
    }
}
