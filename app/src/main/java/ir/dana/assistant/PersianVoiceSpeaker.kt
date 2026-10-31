package ir.dana.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class PersianVoiceSpeaker(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "PersianVoiceSpeaker"
        var sharedInstance: PersianVoiceSpeaker? = null
    }

    private var tts: TextToSpeech? = null
    private var isNativePersianSupported = false
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context.applicationContext, this)
        sharedInstance = this
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val faLocale = Locale("fa", "IR")
            val availability = tts?.isLanguageAvailable(faLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = faLocale
                isNativePersianSupported = true
                Log.i(TAG, "موتور TTS بومی فارسی فعال شد.")
            } else {
                Log.w(TAG, "موتور TTS بومی فارسی روی گوشی یافت نشد؛ از سخنگوی آنلاین فارسی استفاده می‌شود.")
                isNativePersianSupported = false
            }
        }
    }

    fun speakPersian(text: String, onDone: (() -> Unit)? = null) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            onDone?.invoke()
            return
        }

        mainHandler.post {
            if (isNativePersianSupported && tts != null) {
                tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "dana_tts_${System.currentTimeMillis()}")
                val estimatedMs = (cleanText.length * 95L).coerceIn(1000L, 7000L)
                if (onDone != null) {
                    mainHandler.postDelayed({ onDone.invoke() }, estimatedMs)
                }
            } else {
                playOnlinePersianTts(cleanText, onDone)
            }
        }
    }

    private fun playOnlinePersianTts(text: String, onDone: (() -> Unit)?) {
        try {
            mediaPlayer?.release()
            val shortText = text.take(190)
            val encoded = Uri.encode(shortText)
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=fa&client=tw-ob&q=$encoded"

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    it.release()
                    onDone?.invoke()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dana_fallback")
                    onDone?.invoke()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پخش صدای آنلاین فارسی", e)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dana_fallback")
            onDone?.invoke()
        }
    }

    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.shutdown()
    }
}
