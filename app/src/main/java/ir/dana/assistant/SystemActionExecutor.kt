package ir.dana.assistant

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent

class SystemActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "SystemActionExecutor"

        private val APP_ALIASES = mapOf(
            "ایتا" to listOf("ir.eitaa.messenger", "eitaa", "ایتا"),
            "روبیکا" to listOf("ir.resaneh1.iptv", "rubika", "روبیکا"),
            "بله" to listOf("ir.nasim", "bale", "بله"),
            "سروش" to listOf("mobi.mmdt.ottplus", "soroush", "سروش"),
            "گپ" to listOf("com.gapafzar.messenger", "gap", "گپ"),
            "آیگپ" to listOf("net.iGap", "igap", "آیگپ"),
            "واتساپ" to listOf("com.whatsapp", "whatsapp"),
            "واتس‌اپ" to listOf("com.whatsapp", "whatsapp"),
            "تلگرام" to listOf("org.telegram.messenger", "telegram"),
            "اینستاگرام" to listOf("com.instagram.android", "instagram"),
            "اینستا" to listOf("com.instagram.android", "instagram"),
            "یوتیوب" to listOf("com.google.android.youtube", "youtube"),
            "کروم" to listOf("com.android.chrome", "chrome"),
            "تنظیمات" to listOf("com.android.settings", "settings"),
            "دوربین" to listOf("camera", "دوربین"),
            "گالری" to listOf("gallery", "photos", "گالری"),
            "اسنپ" to listOf("cab.snapp.passenger", "snapp", "اسنپ"),
            "تپسی" to listOf("taxi.tap30.passenger", "tapsi", "تپسی"),
            "دیوار" to listOf("ir.divar", "divar", "دیوار"),
            "دیجی کالا" to listOf("com.digikala", "digikala"),
            "دیجی‌کالا" to listOf("com.digikala", "digikala"),
            "ترب" to listOf("ir.torob", "torob", "ترب"),
            "آپ" to listOf("com.sibche.aspardproject.app", "asanpardakht", "آپ"),
            "بلو" to listOf("com.blubank.android", "blu", "بلو"),
            "نشان" to listOf("org.rajman.neshan.traffic.tehran.navigator", "neshan", "نشان"),
            "بلد" to listOf("ir.balad", "balad", "بلد"),
            "گوگل مپ" to listOf("com.google.android.apps.maps", "maps"),
            "نقشه" to listOf("org.rajman.neshan.traffic.tehran.navigator", "ir.balad", "com.google.android.apps.maps"),
            "ماشین حساب" to listOf("calculator", "ماشین حساب"),
            "ساعت" to listOf("deskclock", "clock", "ساعت"),
            "پیامک" to listOf("mms", "messaging", "messages", "پیام")
        )
    }

    fun openApp(appNameQuery: String): Boolean {
        val pm = context.packageManager
        val normalizedQuery = appNameQuery.trim().lowercase()

        val aliasCandidates = APP_ALIASES.entries.firstOrNull {
            normalizedQuery.contains(it.key) || it.key.contains(normalizedQuery)
        }?.value ?: listOf(normalizedQuery)

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        for (candidate in aliasCandidates + normalizedQuery) {
            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName.lowercase()
                val label = info.loadLabel(pm).toString().lowercase()
                if (pkg == candidate || label.contains(candidate) || pkg.contains(candidate)) {
                    val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return true
                    }
                }
            }
        }
        return false
    }

    fun startNavigation(destinationQuery: String, preferredApp: String? = null): Boolean {
        return try {
            val encodedDest = Uri.encode(destinationQuery.trim())
            val geoUri = Uri.parse("geo:0,0?q=$encodedDest")
            val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (!preferredApp.isNullOrBlank()) {
                when {
                    preferredApp.contains("نشان") -> mapIntent.setPackage("org.rajman.neshan.traffic.tehran.navigator")
                    preferredApp.contains("بلد") -> mapIntent.setPackage("ir.balad")
                    preferredApp.contains("گوگل") -> mapIntent.setPackage("com.google.android.apps.maps")
                }
            }

            try {
                context.startActivity(mapIntent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطا در شروع مسیریابی", e)
            false
        }
    }

    fun copyToClipboard(text: String, label: String = "Dana_Copy"): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun makePhoneCall(target: String): Boolean {
        val number = if (target.count { it.isDigit() } >= 4) {
            target.filter { it.isDigit() || it == '+' }
        } else {
            lookupContactNumber(target)
        }

        if (number.isNullOrBlank()) {
            return false
        }

        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            true
        } catch (e: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطا در برقراری تماس", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    fun sendSms(target: String, messageBody: String): Boolean {
        val number = if (target.count { it.isDigit() } >= 4) {
            target.filter { it.isDigit() || it == '+' }
        } else {
            lookupContactNumber(target)
        } ?: return false

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            val parts = smsManager.divideMessage(messageBody)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            true
        } catch (e: Exception) {
            try {
                val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                    putExtra("sms_body", messageBody)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(smsIntent)
                true
            } catch (ex: Exception) {
                false
            }
        }
    }

    @SuppressLint("Range")
    fun lookupContactNumber(nameQuery: String): String? {
        return try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${nameQuery.trim()}%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "خطا در خواندن مخاطبین", e)
            null
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String = "دانا"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return false
            cameraManager.setTorchMode(cameraId, enabled)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setVolumeLevel(percent: Int, streamType: String = "MUSIC"): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = when (streamType.uppercase()) {
                "RING", "زنگ" -> AudioManager.STREAM_RING
                "ALARM", "آلارم" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            val max = audioManager.getStreamMaxVolume(stream)
            val target = ((percent.coerceIn(0, 100) / 100.0) * max).toInt()
            audioManager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setRingerMode(mode: String): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = when (mode.uppercase()) {
                "SILENT", "سایلنت", "بی‌صدا" -> AudioManager.RINGER_MODE_SILENT
                "VIBRATE", "ویبره" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun controlMedia(command: String): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when (command.uppercase()) {
                "NEXT", "بعدی" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "PREVIOUS", "قبلی" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "PAUSE", "توقف", "قطع" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openSettingsSection(section: String): Boolean {
        val action = when (section.uppercase()) {
            "WIFI", "وای‌فای", "وای فای" -> Settings.ACTION_WIFI_SETTINGS
            "BLUETOOTH", "بلوتوث" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "SOUND", "صدا" -> Settings.ACTION_SOUND_SETTINGS
            "DISPLAY", "صفحه نمایش", "نور" -> Settings.ACTION_DISPLAY_SETTINGS
            "LOCATION", "مکان", "لوکیشن" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun searchWeb(query: String): Boolean {
        return try {
            val url = if (query.startsWith("http://") || query.startsWith("https://")) {
                query
            } else {
                "https://www.google.com/search?q=${Uri.encode(query)}"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
