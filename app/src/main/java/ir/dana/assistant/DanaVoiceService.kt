package ir.dana.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    // مدیریت وضعیت‌ها برای جلوگیری از تداخل و هنگ کردن میکروفون
    private var isListening = false
    private var isSpeaking = false
    private var isAwake = false // آیا کاربر کلمه کلیدی "دانا" را گفته است؟

    companion object {
        private const val TAG = "DanaVoiceService"
        private const val CHANNEL_ID = "DanaAssistantChannel"
        private const val NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        
        startForegroundServiceNotification()
        setupSpeechRecognizer()
        startListening(500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // اگر سرویس به هر دلیلی متوقف شده بود، مجدداً شنود را آغاز کن
        if (!isListening && !isSpeaking) {
            startListening(300L)
        }
        return START_STICKY
    }

    private fun setupSpeechRecognizer() {
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@VoiceService)
            
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // دریافت لحظه‌ای کلمات بدون صبر کردن
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                
                // کاهش زمان انتظار سکوت برای حذف تاخیر در گرفتن دستور (۷۰۰ میلی‌ثانیه)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    Log.d(TAG, "دانا آماده شنیدن است...")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    
                    // اگر خطای اشغال بودن داد، کمی بیشتر صبر کن وگرنه سریع ری‌استارت کن
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                        SpeechRecognizer.ERROR_CLIENT -> 600L
                        else -> 250L // برای خطاهای سکوت (NO_MATCH و TIMEOUT) بلافاصله روشن شو
                    }

                    // اگر خطای سخت‌افزاری یا کلاینت رخ داد، نمونه میکروفون را از نو بساز تا هنگ نکند
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                        setupSpeechRecognizer()
                    }

                    if (!isSpeaking) {
                        startListening(delay)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim() ?: ""

                    if (spokenText.isNotEmpty()) {
                        Log.d(TAG, "متن دریافت شده: $spokenText")
                        handleVoiceInput(spokenText)
                    } else if (!isSpeaking) {
                        startListening(250L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = matches?.firstOrNull()?.trim() ?: ""

                    // تشخیص سریع کلمه "دانا" در حین صحبت قبل از تمام شدن جمله
                    if (!isAwake && (partialText == "دانا" || partialText == "جانم دانا")) {
                        isAwake = true
                        updateNotification("گوش می‌دهم... فرمان خود را بگویید")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening(delayMillis: Long = 200L) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isSpeaking) {
                try {
                    speechRecognizer?.cancel()
                    speechRecognizer?.startListening(recognizerIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "خطا در شروع شنود: ${e.message}")
                    setupSpeechRecognizer()
                    startListening(1000L)
                }
            }
        }, delayMillis)
    }

    // بررسی متن و تفکیک کلمه کلیدی "دانا" از دستور اصلی
    private fun handleVoiceInput(text: String) {
        val normalized = text.replace("ي", "ی").replace("ك", "ک").trim()

        // حالت ۱: کاربر فقط گفته "دانا"
        if (normalized == "دانا" || normalized == "سلام دانا") {
            isAwake = true
            updateNotification("دانا بیدار است: دستور دهید...")
            speak("جانم؟ در خدمتم") // بعد از گفتن این جمله، میکروفون خودکار روشن می‌شود
            return
        }

        // حالت ۲: کاربر در یک جمله هم "دانا" را گفته و هم دستور را (مثلا: "دانا تلگرام را باز کن")
        if (normalized.startsWith("دانا ")) {
            val command = normalized.removePrefix("دانا").trim()
            isAwake = false
            executeCommand(command)
            return
        }

        // حالت ۳: دستیار از قبل با گفتن "دانا" بیدار شده و الان کاربر دستور را گفته است
        if (isAwake) {
            isAwake = false
            executeCommand(normalized)
            return
        }

        // اگر هیچکدام نبود، دوباره به حالت شنود برگرد
        startListening(250L)
    }

    // اجرای دستورات صوتی و ادامه دادن چرخه شنود
    private fun executeCommand(command: String) {
        Log.d(TAG, "اجرای دستور: $command")
        updateNotification("آخرین دستور: $command")

        when {
            // دستور باز کردن برنامه‌ها
            command.contains("باز کن") || command.contains("اجرا کن") -> {
                val appName = command
                    .replace("برنامه", "")
                    .replace("اپلیکیشن", "")
                    .replace("را باز کن", "")
                    .replace("رو باز کن", "")
                    .replace("باز کن", "")
                    .replace("اجرا کن", "")
                    .trim()

                if (openAppByName(appName)) {
                    speak("در حال باز کردن $appName")
                } else {
                    speak("برنامه $appName پیدا نشد")
                }
            }

            // دستور تماس با مخاطبین
            command.contains("زنگ بزن") || command.contains("تماس بگیر") || command.startsWith("تماس با") -> {
                val contactName = command
                    .replace("به", "")
                    .replace("با", "")
                    .replace("زنگ بزن", "")
                    .replace("تماس بگیر", "")
                    .replace("تماس", "")
                    .trim()

                if (callContact(contactName)) {
                    speak("در حال تماس با $contactName")
                } else {
                    speak("شماره‌ای برای $contactName پیدا نکردم")
                }
            }

            // دستور تنظیمات یا وای‌فای
            command.contains("تنظیمات") -> {
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                speak("تنظیمات باز شد")
            }

            else -> {
                // اگر دستور ناشناخته بود، بدون توقف به شنود ادامه بده
                startListening(300L)
            }
        }
    }

    private fun openAppByName(appName: String): Boolean {
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        for (appInfo in packages) {
            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
            if (label.contains(appName.lowercase())) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    return true
                }
            }
        }
        return false
    }

    private fun callContact(name: String): Boolean {
        if (name.isEmpty()) return false
        var phoneNumber: String? = null
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) {
                    phoneNumber = it.getString(numberIndex)
                }
            }
        }

        return if (!phoneNumber.isNullOrEmpty()) {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(callIntent)
            true
        } else {
            false
        }
    }

    private fun speak(text: String) {
        if (tts == null) {
            startListening(300L)
            return
        }
        isSpeaking = true
        mainHandler.post { speechRecognizer?.cancel() }

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "DanaUtteranceId")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "DanaUtteranceId")
        
        // فال‌بک امنیتی: اگر موتور صوتی فارسی روی گوشی نصب نبود و قفل کرد، بعد از ۳ ثانیه میکروفون را روشن کن
        mainHandler.postDelayed({
            if (isSpeaking) {
                isSpeaking = false
                startListening(200L)
            }
        }, 3000L)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("fa", "IR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    // به محض تمام شدن صحبت دستیار، بلافاصله میکروفون برای دستور بعدی روشن می‌شود
                    startListening(200L)
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    startListening(200L)
                }
            })
        }
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سرویس دستیار صوتی دانا",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "فعال نگه داشتن شنود دستیار در پس‌زمینه"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification("دانا فعال است و گوش می‌دهد...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("دستیار هوشمند دانا")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
