package ir.dana.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

class DanaCallManager(private val context: Context) {

    companion object {
        private const val TAG = "DanaCallManager"
    }

    @SuppressLint("MissingPermission")
    fun answerRingingCall(enableSpeaker: Boolean = true): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager.acceptRingingCall()
            }
            if (enableSpeaker) {
                setSpeakerphone(true)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پاسخ به تماس", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun endOrRejectCall(): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در قطع تماس", e)
            false
        }
    }

    fun setSpeakerphone(enabled: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_CALL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        } catch (e: Exception) {
            Log.e(TAG, "خطا در تغییر وضعیت بلندگو", e)
        }
    }
}
