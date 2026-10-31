package ir.dana.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class DanaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("DanaBootReceiver", "راه‌اندازی خودکار سرویس پس‌زمینه دانا پس از بوت")
            val serviceIntent = Intent(context, DanaVoiceService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
